'use strict';
const { DB } = require('../db');
const { getVehicleType } = require('./master-data');

class CommuteQuoteError extends Error { constructor(status, message) { super(message); this.status = status; } }
const fail = (status, message) => { throw new CommuteQuoteError(status, message); };
const key = value => String(value || '').normalize('NFKC').trim().replace(/\s+/gu, ' ').toLowerCase();
function endpoint(value, name, required = true) {
  if (typeof value !== 'string' || value.trim().length > 120 || /[\u0000-\u001f]/.test(value) || (required && (value.match(/[\p{L}\p{N}]/gu) || []).length < 2)) fail(400, `Enter a valid ${name}.`);
  return value.trim();
}
function aliases(values) {
  const found = new Map();
  for (const value of values) if (typeof value === 'string' && value.trim() && !found.has(key(value))) found.set(key(value), value.trim());
  return [...found.values()];
}
async function commuteEndpoints(db = DB) {
  const routes = await db.prepare("SELECT id,name,origin,destination,basePriceEtb FROM routes WHERE active=TRUE AND COALESCE(companyId,'')='' AND COALESCE(routeType,'DAILY_COMMUTE')='DAILY_COMMUTE' ORDER BY name,id").all();
  const stops = await db.prepare("SELECT s.routeId,s.stopName,s.stopNameAm,s.stopOrder,s.id FROM route_stops s JOIN routes r ON r.id=s.routeId WHERE r.active=TRUE AND COALESCE(r.companyId,'')='' AND COALESCE(r.routeType,'DAILY_COMMUTE')='DAILY_COMMUTE' ORDER BY s.routeId,s.stopOrder,s.id").all();
  const byRoute = new Map();
  for (const stop of stops) { if (!byRoute.has(stop.routeId)) byRoute.set(stop.routeId, []); byRoute.get(stop.routeId).push(stop); }
  return routes.map(route => {
    const ordered = byRoute.get(route.id) || [], first = ordered[0], last = ordered.at(-1);
    // Stop translations are aliases only when the route actually uses that stop
    // as its endpoint, rather than attaching unrelated explicit endpoint labels.
    const departureAliases = aliases(route.origin?.trim() ? [route.origin, ...(key(route.origin) === key(first?.stopName) || key(route.origin) === key(first?.stopNameAm) ? [first?.stopName, first?.stopNameAm] : [])] : [first?.stopName, first?.stopNameAm]);
    const destinationAliases = aliases(route.destination?.trim() ? [route.destination, ...(key(route.destination) === key(last?.stopName) || key(route.destination) === key(last?.stopNameAm) ? [last?.stopName, last?.stopNameAm] : [])] : [last?.stopName, last?.stopNameAm]);
    return { id: route.id, name: route.name, departure: departureAliases[0] || '', destination: destinationAliases[0] || '', departureAliases, destinationAliases, passengerMonthlyFareEtb: Number(route.basePriceEtb) };
  }).filter(route => route.departure && route.destination && key(route.departure) !== key(route.destination));
}
const publicRoute = ({ passengerMonthlyFareEtb, ...route }) => route;
async function commuteSuggestions(query = {}, db = DB) {
  const field = query.field === undefined ? 'departure' : query.field;
  if (!['departure','destination'].includes(field)) fail(400, 'Choose departure or destination suggestions.');
  const q = endpoint(query.q ?? '', 'search', false), departure = endpoint(query.departure ?? '', 'departure', false);
  const choices = (await commuteEndpoints(db)).filter(route => (!departure || field !== 'destination' || route.departureAliases.some(label => key(label) === key(departure))) && (!q || route[field + 'Aliases'].some(label => key(label).includes(key(q)))));
  const places = new Map();
  for (const route of choices) {
    const label = route[field], old = places.get(key(label));
    places.set(key(label), { label, aliases: aliases([...(old?.aliases || []), ...route[field + 'Aliases']]) });
  }
  return { places: [...places.values()].slice(0, 50), routes: choices.slice(0, 100).map(publicRoute) };
}
function vehicleMonthlyPrice(route, type) {
  const fare = Number(route?.passengerMonthlyFareEtb), capacity = Number(type.capacity);
  const fixed = route && Number.isFinite(fare) && fare > 0;
  const amount = fixed ? Math.round((fare * capacity + Number.EPSILON) * 100) / 100 : null;
  if (fixed && (!Number.isFinite(amount) || amount <= 0 || amount > 1000000)) fail(409, 'The route and vehicle monthly price is outside the supported range. Contact the administrator.');
  return { mode: fixed ? 'FIXED' : 'PROPOSED', amountEtb: amount, currency: 'ETB', basis: 'PER_VEHICLE', source: fixed ? 'ROUTE_PASSENGER_FARE_X_CAPACITY' : null,
    passengerCapacity: capacity, passengerMonthlyFareEtb: fixed ? fare : null };
}
async function commuteQuote(input, db = DB) {
  const departure = endpoint(input.proposedDeparture ?? input.departure, 'departure'), destination = endpoint(input.proposedDestination ?? input.destination, 'destination');
  if (key(departure) === key(destination)) fail(400, 'Choose two distinct departure and destination places.');
  const type = await getVehicleType(String(input.vehicleType || '').toUpperCase(), db);
  if (!type) fail(400, 'Choose an active vehicle type.');
  const matches = (await commuteEndpoints(db)).filter(route => route.departureAliases.some(label => key(label) === key(departure)) && route.destinationAliases.some(label => key(label) === key(destination)));
  const route = matches.length === 1 ? matches[0] : null;
  return { routeMatch: { status: route ? 'EXACT' : matches.length ? 'AMBIGUOUS' : 'NONE', routeId: route?.id || null, routeName: route?.name || null }, price: vehicleMonthlyPrice(route, type) };
}
module.exports = { commuteSuggestions, commuteQuote, commuteEndpoints, vehicleMonthlyPrice, CommuteQuoteError };
