'use strict';
const crypto = require('crypto');
const fs = require('fs/promises');
const path = require('path');
const jwt = require('jsonwebtoken');
const { DB } = require('../db');
const { JWT_SECRET } = require('../middleware/auth');
const { normalizePhone } = require('./onboarding');
const { driverEligible } = require('./operational-onboarding');
const { isAccountClosed } = require('./account-deletion');
const { getVehicleType, listVehicleTypes, effectiveBoardingRadius } = require('./master-data');
const { publicBaseUrl } = require('./public-url');
const { initMobilitySchema } = require('./mobility-schema');
class MobilityError extends Error { constructor(status, message, code) { super(message); this.status = status; this.code = code; } }
const fail = (status, message, code) => { throw new MobilityError(status, message, code); };
const id = prefix => prefix + crypto.randomUUID();
const hash = value => crypto.createHash('sha256').update(String(value)).digest('hex');
const secret = () => crypto.randomBytes(32).toString('base64url');
function text(value, label, max = 150, optional = false) {
  if (optional && value == null) return '';
  if (typeof value !== 'string' || value.trim().length < (optional ? 0 : 2) || value.trim().length > max || /[\u0000-\u001f]/.test(value)) fail(400, 'Enter a valid ' + label + '.');
  return value.trim();
}
function person(name, phone) {
  const normalized = normalizePhone(phone);
  if (!normalized) fail(400, 'Enter a valid Ethiopian mobile phone number.');
  return { name: text(name, 'name', 100), phone: normalized };
}
function money(value, max = 1000000) {
  if (!['number','string'].includes(typeof value) || value === '' || !Number.isFinite(Number(value)) || Number(value) <= 0 || Number(value) > max || Math.abs(Number(value) * 100 - Math.round(Number(value) * 100)) > 0.000001) fail(400, 'Enter a positive ETB price with at most two decimal places.');
  return Math.round(Number(value) * 100) / 100;
}
function coordinates(latitude, longitude, optional = false) {
  if (optional && latitude == null && longitude == null) return [null, null];
  if (latitude == null || longitude == null || latitude === '' || longitude === '' || typeof latitude === 'boolean' || typeof longitude === 'boolean') fail(400, 'Enter both latitude and longitude.');
  const lat = Number(latitude), lng = Number(longitude);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) fail(400, 'Enter valid location coordinates.');
  return [lat, lng];
}
const lock = db => db.isPostgres ? ' FOR UPDATE' : '';
function createMobilityService({ db = DB, now = Date.now, provider = require('./telebirr'), uploadRoot = process.env.UPLOAD_DIR || path.join(process.env.DATA_DIR || path.join(__dirname, '../..', 'data'), 'uploads'), sendSms = process.env.NODE_ENV === 'test' ? async () => ({ sent: false }) : (...args) => require('./afro-sms').sendSms(...args) } = {}) {
  const instant = () => new Date(now()).toISOString();
  const root = path.resolve(uploadRoot, 'mobility');
  const write = fn => db.transaction(fn);
  async function getTrip(key, tx = db, locked = false) {
    const row = await tx.prepare('SELECT * FROM mobility_trips WHERE id=?' + (locked ? lock(tx) : '')).get(key);
    if (!row) fail(404, 'Group trip unavailable.');
    return row;
  }
  async function serviceType(driverId, tx = db) {
    return (await tx.prepare("SELECT service_type FROM onboarding_accounts WHERE account_id=? AND role='DRIVER'").get(driverId))?.service_type || 'DAILY_COMMUTE';
  }
  async function vehicleDriver(vehicleId, driverId, kind, tx = db, locked = false) {
    const vehicle = await tx.prepare('SELECT * FROM vehicles WHERE id=?' + (locked ? lock(tx) : '')).get(vehicleId);
    const driver = await tx.prepare("SELECT * FROM users WHERE id=? AND role='DRIVER'" + (locked ? lock(tx) : '')).get(driverId);
    const type = await serviceType(driverId, tx);
    if (!vehicle || !driver || vehicle.driverId !== driver.id || driver.status !== 'ACTIVE' || !['IN_SERVICE','AVAILABLE'].includes(vehicle.status) || await isAccountClosed(driverId, 'DRIVER', tx) || !await driverEligible(driverId, tx) || type !== (kind === 'GROUP' ? 'GROUP_TRAVEL' : 'CARGO')) fail(409, 'Select an approved driver and their active vehicle for this service.');
    if (!await getVehicleType(vehicle.vehicleType, tx)) fail(409, 'The assigned vehicle type is unavailable.');
    return { vehicle, driver };
  }
  async function eligible(trip, tx = db) {
    try { const { vehicle } = await vehicleDriver(trip.vehicle_id, trip.driver_id, 'GROUP', tx); return Number(trip.seats) <= Number(vehicle.capacityLimit); }
    catch (error) { if (error instanceof MobilityError) return false; throw error; }
  }
  async function tripView(row, tx = db, privateView = false) {
    const vehicle = await tx.prepare('SELECT * FROM vehicles WHERE id=?').get(row.vehicle_id);
    const driver = await tx.prepare('SELECT fullName FROM users WHERE id=?').get(row.driver_id);
    const count = await tx.prepare("SELECT COUNT(*) AS count FROM mobility_seats s JOIN mobility_bookings b ON b.id=s.booking_id WHERE s.trip_id=? AND (b.payment_status='PAID' OR b.expires_at>?)").get(row.id, instant());
    return { id: row.id, origin: row.origin, destination: row.destination, departureAt: row.departure_at,
      fareEtb: Number(row.fare_etb), seats: Number(row.seats), availableSeats: Number(row.seats) - Number(count.count), status: row.status,
      vehicleId: row.vehicle_id, vehicleType: vehicle?.vehicleType || '', vehicleModel: vehicle?.model || '', vehiclePlate: vehicle?.plateNumber || '', driverName: driver?.fullName || '',
      ...(privateView ? { driverId: row.driver_id, latitude: Number(row.latitude), longitude: Number(row.longitude), reviewNote: row.review_note } : {}) };
  }
  async function catalog() { return { vehicleTypes: (await listVehicleTypes(db)).filter(x => x.active).map(x => ({ id: x.code, name: x.name, capacity: x.capacity })) }; }
  async function trips(query = {}, administrative = false) {
    const clauses = administrative ? [] : ["status='APPROVED'", 'departure_at>?'], args = administrative ? [] : [instant()];
    for (const field of ['origin','destination']) if (query[field]) { clauses.push('LOWER(' + field + ') LIKE ?'); args.push('%' + text(query[field], field).toLowerCase().replace(/[%_]/g, '') + '%'); }
    if (query.date) {
      const date = validDate(query.date); const start = Date.parse(date + 'T00:00:00+03:00');
      clauses.push('departure_at>=? AND departure_at<?'); args.push(new Date(start).toISOString(), new Date(start + 86400000).toISOString());
    }
    const rows = await db.prepare('SELECT * FROM mobility_trips' + (clauses.length ? ' WHERE ' + clauses.join(' AND ') : '') + ' ORDER BY departure_at ASC LIMIT 200').all(...args);
    const visible = administrative ? rows : (await Promise.all(rows.map(async r => await eligible(r) ? r : null))).filter(Boolean);
    return { trips: await Promise.all(visible.map(r => tripView(r, db, administrative))) };
  }
  async function createTrip(driverId, input) {
    const origin = text(input.origin, 'departure'), destination = text(input.destination, 'destination');
    if (origin.toLowerCase() === destination.toLowerCase()) fail(400, 'Departure and destination must differ.');
    const time = Date.parse(input.departureAt); if (!Number.isFinite(time) || time <= now() || time > now() + 366 * 86400000) fail(400, 'Choose a future departure within one year.');
    const [lat, lng] = coordinates(input.latitude, input.longitude), fare = money(input.fareEtb, 100000), seats = Number(input.seats);
    const row = await write(async tx => {
      const { vehicle } = await vehicleDriver(input.vehicleId, driverId, 'GROUP', tx, true);
      if (!Number.isInteger(seats) || seats < 1 || seats > Math.min(100, Number(vehicle.capacityLimit))) fail(400, 'Choose seats within the approved vehicle capacity.');
      const key = id('grp_');
      await tx.prepare('INSERT INTO mobility_trips(id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,seats,fare_etb,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)')
        .run(key, driverId, vehicle.id, origin, destination, new Date(time).toISOString(), lat, lng, seats, fare, instant());
      return getTrip(key, tx);
    });
    return { trip: await tripView(row, db, true) };
  }
  async function reviewTrip(actor, key, input) {
    if (!['APPROVE','REJECT','CANCEL'].includes(input.decision)) fail(400, 'Choose approve, reject or cancel.');
    const note = text(input.note, 'review note', 1000, true);
    const row = await write(async tx => {
      const before = await getTrip(key, tx);
      if (input.decision === 'APPROVE') {
        const { vehicle } = await vehicleDriver(before.vehicle_id, before.driver_id, 'GROUP', tx, true);
        if (Number(before.seats) > Number(vehicle.capacityLimit)) fail(409, 'The vehicle no longer has enough approved passenger capacity.');
      }
      const current = await getTrip(key, tx, true);
      if (input.decision === 'CANCEL') {
        if (!['PENDING_REVIEW','APPROVED'].includes(current.status)) fail(409, 'Only an unopened trip can be cancelled.');
        const committed = await tx.prepare("SELECT id FROM mobility_bookings WHERE trip_id=? AND (payment_status='PAID' OR (order_id IS NOT NULL AND payment_status<>'FAILED')) LIMIT 1").get(key);
        if (committed) fail(409, 'Resolve paid or pending provider payments with support before cancelling this trip.');
        await tx.prepare("UPDATE mobility_bookings SET status='CANCELLED',checkout_url=NULL WHERE trip_id=? AND payment_status<>'PAID'").run(key);
        await tx.prepare('DELETE FROM mobility_seats WHERE trip_id=?').run(key);
        await tx.prepare("UPDATE mobility_trips SET status='CANCELLED',review_note=?,reviewed_by=?,reviewed_at=? WHERE id=?").run(note,actor,instant(),key);
        await audit(tx,actor,'GROUP_TRIP_CANCEL',key);
        return getTrip(key,tx);
      }
      if (current.status !== 'PENDING_REVIEW') fail(409, 'This trip was already reviewed. Refresh the queue.');
      if (input.decision === 'APPROVE' && Date.parse(current.departure_at) <= now()) fail(409, 'The departure is already past. Ask the driver to propose a future trip.');
      await tx.prepare('UPDATE mobility_trips SET status=?,review_note=?,reviewed_by=?,reviewed_at=? WHERE id=?').run(input.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED', note, actor, instant(), key);
      await audit(tx, actor, 'GROUP_TRIP_' + input.decision, key);
      return getTrip(key, tx);
    });
    return { trip: await tripView(row, db, true) };
  }
  async function expire(tx, key) {
    // Every seat writer serializes on the same trip, including expiry workers.
    await getTrip(key, tx, true);
    await tx.prepare("DELETE FROM mobility_seats WHERE trip_id=? AND booking_id IN (SELECT id FROM mobility_bookings WHERE trip_id=? AND payment_status<>'PAID' AND expires_at<=?)").run(key, key, instant());
    await tx.prepare("UPDATE mobility_bookings SET status='EXPIRED' WHERE trip_id=? AND payment_status<>'PAID' AND expires_at<=?").run(key, instant());
  }
  async function requireBookable(row, tx) {
    if (row.status !== 'APPROVED' || Date.parse(row.departure_at) <= now() || !await eligible(row, tx)) fail(409, 'This trip is not currently open for booking.');
  }
  async function seats(key) {
    const trip = await getTrip(key); await requireBookable(trip, db);
    const occupied = await db.prepare("SELECT s.seat_number FROM mobility_seats s JOIN mobility_bookings b ON b.id=s.booking_id WHERE s.trip_id=? AND (b.payment_status='PAID' OR b.expires_at>?) ORDER BY s.seat_number").all(key, instant());
    return { trip: await tripView(trip), occupiedSeats: occupied.map(x => Number(x.seat_number)) };
  }
  async function createBooking(key, input) {
    if (input.adultConfirmed !== true) fail(400, 'Confirm that you are at least 18 years old.');
    const buyer = person(input.buyerName, input.buyerPhone);
    if (!Array.isArray(input.travelers) || input.travelers.length < 1 || input.travelers.length > 20) fail(400, 'Book between one and twenty travelers at a time.');
    const people = input.travelers.map(p => ({ ...person(p.name, p.phone), seat: Number(p.seatNumber) }));
    if (new Set(people.map(p => p.seat)).size !== people.length) fail(400, 'Choose a different seat for every traveler.');
    const capability = secret(), keyBooking = id('gbk_');
    await write(async tx => {
      const trip = await getTrip(key, tx, true); await requireBookable(trip, tx); await expire(tx, key);
      if (people.some(p => !Number.isInteger(p.seat) || p.seat < 1 || p.seat > Number(trip.seats))) fail(400, 'Choose valid seats for this vehicle.');
      for (const p of people) if (await tx.prepare('SELECT traveler_id FROM mobility_seats WHERE trip_id=? AND seat_number=?').get(key, p.seat)) fail(409, 'A selected seat was just reserved. Refresh the seat map.');
      await tx.prepare('INSERT INTO mobility_bookings(id,trip_id,secret_hash,buyer_name,buyer_phone,amount_etb,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?)')
        .run(keyBooking, key, hash(capability), buyer.name, buyer.phone, Math.round(Number(trip.fare_etb) * 100) * people.length / 100, new Date(now() + 10 * 60000).toISOString(), instant());
      for (const p of people) {
        const traveler = id('gtr_'), ticket = 'PG-' + crypto.randomBytes(8).toString('hex').toUpperCase();
        await tx.prepare('INSERT INTO mobility_travelers(id,booking_id,trip_id,name,phone,seat_number,ticket_number) VALUES(?,?,?,?,?,?,?)').run(traveler, keyBooking, key, p.name, p.phone, p.seat, ticket);
        await tx.prepare('INSERT INTO mobility_seats(trip_id,seat_number,traveler_id,booking_id) VALUES(?,?,?,?)').run(key, p.seat, traveler, keyBooking);
      }
    });
    return { bookingSecret: capability, booking: await bookingView(await getBooking(keyBooking, capability)) };
  }
  async function getBooking(key, capability, tx = db) {
    const row = await tx.prepare('SELECT * FROM mobility_bookings WHERE id=?').get(key);
    if (!row || typeof capability !== 'string' || capability.length > 200 || !crypto.timingSafeEqual(Buffer.from(row.secret_hash), Buffer.from(hash(capability)))) fail(404, 'Use the private booking on the device that made it.');
    return row;
  }
  function ticketToken(traveler, trip) {
    return jwt.sign({ kind: 'GROUP_TICKET', travelerId: traveler.id, tripId: trip.id }, JWT_SECRET, { audience: 'passo-group-boarding', issuer: 'routepass', expiresIn: Math.max(3600, Math.ceil((Date.parse(trip.departure_at) + 48 * 3600000 - now()) / 1000)) });
  }
  async function bookingView(row, tx = db) {
    const trip = await getTrip(row.trip_id, tx), travelers = await tx.prepare('SELECT * FROM mobility_travelers WHERE booking_id=? ORDER BY seat_number').all(row.id);
    return { id: row.id, tripId: row.trip_id, buyerName: row.buyer_name, buyerPhone: row.buyer_phone, status: row.status, paymentStatus: row.payment_status,
      amountEtb: Number(row.amount_etb), expiresAt: row.expires_at, trip: await tripView(trip, tx),
      travelers: travelers.map(p => ({ id: p.id, name: p.name, phone: p.phone, seatNumber: Number(p.seat_number), status: p.status,
        ...(row.payment_status === 'PAID' ? { ticketNumber: p.ticket_number, qrToken: ticketToken(p, trip), boardingPassUrl: publicBaseUrl() + '/api/mobility/tickets/' + encodeURIComponent(p.id) + '?token=' + encodeURIComponent(ticketToken(p, trip)) } : {}) })) };
  }
  async function booking(key, capability, reconcilePayment = true) {
    let row = await getBooking(key, capability);
    if (reconcilePayment && row.order_id && row.payment_status !== 'PAID' && !(row.checkout_phase === 'PREPARING' && Date.parse(row.checkout_lease_until) > now()) && !provider.missingConfiguration().length) await reconcile(row);
    if (row.payment_status !== 'PAID' && Date.parse(row.expires_at) <= now()) await write(tx => expire(tx, row.trip_id));
    row = await getBooking(key, capability);
    return { booking: await bookingView(row) };
  }
  async function queueTickets(tx, row, trip) {
    const travelers = await tx.prepare('SELECT * FROM mobility_travelers WHERE booking_id=?').all(row.id);
    for (const p of travelers) {
      const url = publicBaseUrl() + '/api/mobility/tickets/' + encodeURIComponent(p.id) + '?token=' + encodeURIComponent(ticketToken(p, trip));
      const message = `PASSO ticket ${p.ticket_number}: ${p.name}, seat ${p.seat_number}. ${trip.origin} to ${trip.destination}. ${new Date(trip.departure_at).toLocaleString('en-GB', { timeZone: 'Africa/Addis_Ababa', dateStyle: 'medium', timeStyle: 'short' })}. Boarding pass: ${url}`;
      for (const phone of new Set([row.buyer_phone, p.phone])) await queueSms(tx, row.id, 'ticket:' + p.id + ':' + phone, phone, message);
    }
  }
  async function queueSms(tx, bookingId, event, phone, message) {
    await tx.prepare("INSERT INTO mobility_sms_outbox(id,event_key,booking_id,phone,message,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(event_key) DO NOTHING").run(id('gsms_'), event, bookingId, phone, message, instant(), instant());
  }
  async function reconcile(row) {
    if (!row.order_id || row.payment_status === 'PAID') return;
    const result = await provider.queryOrderStatus(row.order_id, row.prepay_id);
    if (result.merchantOrderId !== row.order_id || String(result.currency).toUpperCase() !== 'ETB' || Math.round(Number(result.amount) * 100) !== Math.round(Number(row.amount_etb) * 100)) fail(502, 'The payment amount or currency could not be verified.');
    if (!result.paid && !result.failed) return;
    await write(async tx => {
      const trip = await getTrip(row.trip_id, tx, true), current = await tx.prepare('SELECT * FROM mobility_bookings WHERE id=?').get(row.id);
      if (current.payment_status === 'PAID') return;
      const reserved = await tx.prepare('SELECT COUNT(*) AS count FROM mobility_seats WHERE booking_id=?').get(row.id);
      const count = await tx.prepare('SELECT COUNT(*) AS count FROM mobility_travelers WHERE booking_id=?').get(row.id);
      if (result.paid && (Number(reserved.count) !== Number(count.count) || current.status === 'EXPIRED' || current.status === 'CANCELLED')) fail(409, 'Payment arrived after the reservation closed. Contact info@passo.live with your payment reference.', 'LATE_PAYMENT_REVIEW');
      if (result.paid) {
        await tx.prepare("UPDATE mobility_bookings SET payment_status='PAID',status='CONFIRMED',payment_reference=? WHERE id=?").run(result.transactionId || result.paymentOrderId || row.order_id, row.id);
        await tx.prepare("UPDATE mobility_travelers SET status='CONFIRMED' WHERE booking_id=?").run(row.id);
        await queueTickets(tx, current, trip);
      } else {
        await tx.prepare("UPDATE mobility_bookings SET payment_status='FAILED',status='CANCELLED' WHERE id=?").run(row.id);
        await tx.prepare('DELETE FROM mobility_seats WHERE booking_id=?').run(row.id);
      }
    });
    void flushSms();
  }
  async function checkout(key, capability) {
    let row = await getBooking(key, capability);
    if (row.payment_status === 'PAID') return { paid: true, booking: await bookingView(row) };
    if (provider.missingConfiguration().length || process.env.NODE_ENV === 'production' && provider.config().environment !== 'production') fail(503, 'Online payments are not configured yet. No payment or ticket has been issued.');
    const prepared = await write(async tx => {
      const trip = await getTrip(row.trip_id, tx, true); await requireBookable(trip, tx);
      const current = await tx.prepare('SELECT * FROM mobility_bookings WHERE id=?').get(row.id);
      if (current.payment_status === 'PAID') return { row: current, create: false };
      if (['EXPIRED','CANCELLED'].includes(current.status) || Date.parse(current.expires_at) <= now()) fail(409, 'The reservation expired. Choose seats again.');
      if (current.checkout_url) return { row: current, create: false };
      if (current.order_id && current.checkout_phase === 'PREPARING' && Date.parse(current.checkout_lease_until) > now()) fail(409, 'Checkout is being prepared. Try again shortly.');
      if (current.order_id && current.checkout_phase !== 'SAFE_RETRY') return { row: current, create: false };
      const order = current.order_id || provider.merchantOrderId();
      await tx.prepare("UPDATE mobility_bookings SET order_id=?,status='PAYMENT_PENDING',checkout_phase='PREPARING',checkout_lease_until=?,expires_at=? WHERE id=?")
        .run(order, new Date(now() + 60000).toISOString(), current.order_id ? current.expires_at : new Date(now() + 125 * 60000).toISOString(), row.id);
      return { row: { ...current, order_id: order }, create: true };
    });
    row = prepared.row;
    if (row.payment_status === 'PAID') return { paid: true, booking: await bookingView(row) };
    if (row.checkout_url) return { checkoutUrl: row.checkout_url };
    if (!prepared.create) {
      await reconcile(row); row = await getBooking(key, capability);
      if (row.payment_status === 'PAID') return { paid: true, booking: await bookingView(row) };
      if (row.payment_status === 'FAILED') fail(409, 'Payment failed. Make a new reservation to try again.');
      const result = await provider.queryOrderStatus(row.order_id, row.prepay_id), prepay = row.prepay_id || result.prepayId;
      if (!prepay) fail(503, 'The payment order is still being confirmed. Check again before paying.');
      const url = provider.checkoutUrlForPrepay(prepay);
      await db.prepare("UPDATE mobility_bookings SET prepay_id=?,checkout_url=?,checkout_phase='READY',checkout_lease_until=NULL WHERE id=?").run(prepay, url, row.id);
      return { checkoutUrl: url };
    }
    try {
      const base = publicBaseUrl();
      const payment = await provider.createCheckout(row.order_id, row.amount_etb, 'Passo group travel tickets', { notifyUrl: base + '/api/mobility/payments/webhook', redirectUrl: base + '/travel/?groupBooking=' + encodeURIComponent(row.id) });
      await db.prepare("UPDATE mobility_bookings SET prepay_id=?,checkout_url=?,checkout_phase='READY',checkout_lease_until=NULL WHERE id=? AND order_id=?").run(payment.prepayId, payment.checkoutUrl, row.id, row.order_id);
      return { checkoutUrl: payment.checkoutUrl };
    } catch (error) {
      await db.prepare('UPDATE mobility_bookings SET checkout_phase=?,checkout_lease_until=NULL WHERE id=? AND order_id=?').run(error.checkoutRetrySafe === true ? 'SAFE_RETRY' : 'UNCERTAIN', row.id, row.order_id);
      fail(502, error.checkoutRetrySafe === true ? 'Checkout could not start. Retry this reservation.' : 'Payment confirmation is pending. Check this booking before paying again.');
    }
  }
  async function webhook(body) {
    let note; try { note = provider.parseNotification(body); } catch (_) { fail(400, 'Invalid payment notice.'); }
    if (!provider.verifyNotification(note)) fail(401, 'Invalid payment signature.');
    const order = String(note.merch_order_id || note.biz_content?.merch_order_id || '');
    const row = await db.prepare('SELECT * FROM mobility_bookings WHERE order_id=?').get(order);
    if (row) await reconcile(row);
    return {};
  }
  function validDate(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || !Number.isFinite(Date.parse(value)) || new Date(value + 'T12:00:00Z').toISOString().slice(0,10) !== value) fail(400, 'Choose a valid travel date.');
    return value;
  }
  async function requestView(row) {
    const driver = row.driver_id ? await db.prepare('SELECT fullName,phone FROM users WHERE id=?').get(row.driver_id) : null;
    const vehicle = row.vehicle_id ? await db.prepare('SELECT plateNumber,model FROM vehicles WHERE id=?').get(row.vehicle_id) : null;
    return { id: row.id, kind: row.kind, origin: row.origin, destination: row.destination, travelDate: row.travel_date, people: row.people == null ? null : Number(row.people),
      vehicleType: row.vehicle_type, customerName: row.customer_name, customerPhone: row.customer_phone, details: row.details, cargoDetails: row.kind === 'CARGO' ? row.details : null, status: row.status,
      originLatitude: row.origin_latitude, originLongitude: row.origin_longitude, destinationLatitude: row.destination_latitude, destinationLongitude: row.destination_longitude,
      driverId: row.driver_id, vehicleId: row.vehicle_id, quoteEtb: row.quote_etb == null ? null : Number(row.quote_etb), reviewNote: row.review_note,
      driverName: driver?.fullName || null, driverPhone: driver?.phone || null, vehiclePlate: vehicle?.plateNumber || null, vehicleModel: vehicle?.model || null,
      photoUrl: row.photo_storage ? '/api/mobility/requests/' + encodeURIComponent(row.id) + '/photo' : null };
  }
  async function getRequest(key, capability, tx = db, privileged = false) {
    const row = await tx.prepare('SELECT * FROM mobility_requests WHERE id=?').get(key);
    if (!row || !privileged && (typeof capability !== 'string' || capability.length > 200 || !crypto.timingSafeEqual(Buffer.from(row.secret_hash), Buffer.from(hash(capability))))) fail(404, 'Transport request unavailable. Use its private reference on this device.');
    return row;
  }
  async function createRequest(input) {
    if (input.adultConfirmed !== true) fail(400, 'Confirm that you are at least 18 years old.');
    if (!['GROUP','CARGO'].includes(input.kind)) fail(400, 'Choose group travel or cargo.');
    const customer = person(input.customerName, input.customerPhone), origin = text(input.origin, 'departure'), destination = text(input.destination, 'destination');
    if (origin.toLowerCase() === destination.toLowerCase()) fail(400, 'Departure and destination must differ.');
    const date = validDate(input.travelDate), today = new Date(now() + 3 * 3600000).toISOString().slice(0,10);
    if (date < today || Date.parse(date) > now() + 366 * 86400000) fail(400, 'Choose today or a future date within one year.');
    if (!await getVehicleType(input.vehicleType, db)) fail(400, 'Choose an active vehicle type.');
    const people = input.kind === 'GROUP' ? Number(input.people) : null;
    if (input.kind === 'GROUP' && (!Number.isInteger(people) || people < 1 || people > 100)) fail(400, 'Enter a group size between one and one hundred.');
    const details = input.kind === 'CARGO' ? text(input.cargoDetails, 'cargo description', 2000) : text(input.details, 'request details', 2000, true);
    const [lat, lng] = coordinates(input.originLatitude, input.originLongitude, true), [toLat, toLng] = coordinates(input.destinationLatitude, input.destinationLongitude, true);
    const key = id('mrq_'), capability = secret();
    await db.prepare('INSERT INTO mobility_requests(id,kind,secret_hash,origin,destination,travel_date,people,vehicle_type,customer_name,customer_phone,details,origin_latitude,origin_longitude,destination_latitude,destination_longitude,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)')
      .run(key, input.kind, hash(capability), origin, destination, date, people, input.vehicleType, customer.name, customer.phone, details, lat, lng, toLat, toLng, input.kind === 'CARGO' ? 'DRAFT' : 'REQUESTED', instant());
    return { requestSecret: capability, request: await requestView(await getRequest(key, capability)) };
  }
  async function request(key, capability) { return { request: await requestView(await getRequest(key, capability)) }; }
  async function uploadPhoto(key, capability, bytes, contentType) {
    const row = await getRequest(key, capability);
    if (row.kind !== 'CARGO' || row.status !== 'DRAFT') fail(409, 'Photos can be added to a draft cargo request.');
    const allowed = { 'image/jpeg': bytes?.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255,
      'image/png': bytes?.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])),
      'image/webp': bytes?.subarray(0,4).toString() === 'RIFF' && bytes?.subarray(8,12).toString() === 'WEBP' };
    if (!Buffer.isBuffer(bytes) || bytes.length < 20 || bytes.length > 5 * 1024 * 1024 || !allowed[contentType]) fail(400, 'Upload a JPEG, PNG or WebP cargo photo of 5 MB or smaller.');
    await fs.mkdir(root, { recursive: true, mode: 0o700 });
    const filename = crypto.randomUUID() + '.blob', target = path.join(root, filename);
    await fs.writeFile(target, bytes, { flag: 'wx', mode: 0o600 });
    let old;
    try {
      await write(async tx => {
        const current = await tx.prepare('SELECT * FROM mobility_requests WHERE id=?' + lock(tx)).get(key);
        if (!current || current.status !== 'DRAFT') fail(409, 'This request has already been submitted or expired.');
        old = current.photo_storage;
        await tx.prepare('UPDATE mobility_requests SET photo_storage=?,photo_type=?,photo_size=? WHERE id=?').run(filename, contentType, bytes.length, key);
      });
    } catch (error) { await fs.unlink(target).catch(() => {}); throw error; }
    if (old && /^[a-f0-9-]{36}\.blob$/.test(old)) await fs.unlink(path.join(root, old)).catch(() => {});
    return request(key, capability);
  }
  async function photo(key, capability, actor) {
    const row = await getRequest(key, capability, db, actor?.role === 'ADMIN' || actor?.role === 'DRIVER');
    if (actor?.role === 'DRIVER' && row.driver_id !== actor.id) fail(404, 'Photo unavailable.');
    if (row.status === 'EXPIRED' || !row.photo_storage || !/^[a-f0-9-]{36}\.blob$/.test(row.photo_storage)) fail(404, 'Photo unavailable.');
    return { bytes: await fs.readFile(path.join(root, row.photo_storage)), contentType: row.photo_type };
  }
  async function submitRequest(key, capability) {
    await write(async tx => {
      const row = await getRequest(key, capability, tx);
      if (row.status === 'REQUESTED') return;
      if (row.kind !== 'CARGO' || row.status !== 'DRAFT' || !row.photo_storage) fail(409, 'Add a cargo photo before submitting.');
      await tx.prepare("UPDATE mobility_requests SET status='REQUESTED' WHERE id=? AND status='DRAFT'").run(key);
    });
    return request(key, capability);
  }
  async function requests(kind) {
    if (kind && !['GROUP','CARGO'].includes(kind)) fail(400, 'Choose a valid service.');
    const rows = await db.prepare("SELECT * FROM mobility_requests WHERE status NOT IN ('DRAFT','EXPIRED')" + (kind ? ' AND kind=?' : '') + ' ORDER BY created_at DESC LIMIT 200').all(...(kind ? [kind] : []));
    return { requests: await Promise.all(rows.map(requestView)) };
  }
  async function options(kind) {
    if (!['GROUP','CARGO'].includes(kind)) fail(400, 'Choose a valid service.');
    const rows = await db.prepare("SELECT u.id,u.fullName,u.phone FROM users u JOIN onboarding_accounts o ON o.account_id=u.id WHERE u.role='DRIVER' AND u.status='ACTIVE' AND o.service_type=? ORDER BY u.fullName").all(kind === 'GROUP' ? 'GROUP_TRAVEL' : 'CARGO');
    const drivers = [], vehicles = [];
    for (const driver of rows) {
      const fleet = await db.prepare('SELECT * FROM vehicles WHERE driverId=? ORDER BY plateNumber').all(driver.id);
      for (const vehicle of fleet) {
        try { await vehicleDriver(vehicle.id, driver.id, kind); }
        catch (error) { if (error instanceof MobilityError) continue; throw error; }
        vehicles.push(vehicleView(vehicle));
        drivers.push({ id: driver.id, fullName: driver.fullName, phone: driver.phone, serviceType: kind === 'GROUP' ? 'GROUP_TRAVEL' : 'CARGO', vehicleId: vehicle.id });
      }
    }
    return { drivers, vehicles };
  }
  function vehicleView(v) { return { id: v.id, vehicleType: v.vehicleType, plateNumber: v.plateNumber, model: v.model, capacityLimit: Number(v.capacityLimit), driverId: v.driverId }; }
  async function assignRequest(actor, key, input) {
    const row = await write(async tx => {
      const before = await getRequest(key, null, tx, true);
      const { vehicle } = await vehicleDriver(input.vehicleId, input.driverId, before.kind, tx, true);
      const current = await tx.prepare('SELECT * FROM mobility_requests WHERE id=?' + lock(tx)).get(key);
      if (!['REQUESTED','ASSIGNED'].includes(current.status)) fail(409, 'This request is closed or not submitted.');
      if (vehicle.vehicleType !== current.vehicle_type || current.kind === 'GROUP' && Number(vehicle.capacityLimit) < Number(current.people)) fail(400, 'Choose the requested vehicle type with enough passenger capacity.');
      const quote = input.quoteEtb == null || input.quoteEtb === '' ? null : money(input.quoteEtb);
      const note = text(input.note, 'assignment note', 1000, true);
      await tx.prepare("UPDATE mobility_requests SET status='ASSIGNED',driver_id=?,vehicle_id=?,quote_etb=?,review_note=?,assigned_by=?,assigned_at=? WHERE id=?")
        .run(input.driverId, input.vehicleId, quote, note, actor, instant(), key);
      await audit(tx, actor, 'MOBILITY_REQUEST_ASSIGNED', key);
      const driver = await tx.prepare('SELECT fullName,phone FROM users WHERE id=?').get(input.driverId);
      const fingerprint = [input.driverId,input.vehicleId,quote == null ? '' : quote].join(':');
      const customerMessage = `PASSO ${current.kind === 'GROUP' ? 'group travel' : 'cargo'} request assigned: ${current.origin} to ${current.destination}, ${current.travel_date}. Vehicle ${vehicle.plateNumber}. Driver ${driver.fullName}, ${driver.phone}.${quote == null ? '' : ' Quoted ETB ' + quote + '; this is not a payment receipt.'} Open your request in the Passo app for details.`;
      const driverMessage = `PASSO new ${current.kind === 'GROUP' ? 'group travel' : 'cargo'} assignment: ${current.origin} to ${current.destination}, ${current.travel_date}. Vehicle ${vehicle.plateNumber}. Customer ${current.customer_name}, ${current.customer_phone}. Open the Passo driver portal for details.`;
      for (const [phone,message] of [[current.customer_phone,customerMessage],[normalizePhone(driver.phone),driverMessage]]) {
        if (phone) await tx.prepare('INSERT INTO mobility_sms_outbox(id,event_key,request_id,assignment_key,phone,message,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(event_key) DO NOTHING')
          .run(id('rsms_'),'assignment:'+key+':'+fingerprint+':'+phone,key,fingerprint,phone,message,instant(),instant());
      }
      return getRequest(key, null, tx, true);
    });
    void flushSms();
    return { request: await requestView(row) };
  }
  async function requestStatus(actor, key, input) {
    if (!['REJECTED','COMPLETED'].includes(input.status)) fail(400, 'Choose a valid request status.');
    const row = await write(async tx => {
      const current = await tx.prepare('SELECT * FROM mobility_requests WHERE id=?' + lock(tx)).get(key);
      if (!current || !['REQUESTED','ASSIGNED'].includes(current.status) || input.status === 'COMPLETED' && current.status !== 'ASSIGNED') fail(409, 'Only an assigned request can be completed. Closed requests cannot be changed.');
      await tx.prepare('UPDATE mobility_requests SET status=?,review_note=? WHERE id=?').run(input.status, text(input.note, 'review note', 1000, true), key);
      await audit(tx, actor, 'MOBILITY_REQUEST_' + input.status, key);
      return getRequest(key, null, tx, true);
    });
    return { request: await requestView(row) };
  }
  async function dashboard(driverId) {
    const type = await serviceType(driverId), fleet = await db.prepare('SELECT * FROM vehicles WHERE driverId=? ORDER BY plateNumber').all(driverId);
    const rows = ['GROUP_TRAVEL','CARGO'].includes(type) ? await db.prepare('SELECT * FROM mobility_trips WHERE driver_id=? ORDER BY departure_at DESC LIMIT 100').all(driverId) : [];
    const assigned = ['GROUP_TRAVEL','CARGO'].includes(type) ? await db.prepare("SELECT * FROM mobility_requests WHERE driver_id=? AND status IN ('ASSIGNED','COMPLETED') ORDER BY travel_date DESC LIMIT 100").all(driverId) : [];
    return { serviceType: type, vehicles: fleet.filter(v => ['IN_SERVICE','AVAILABLE'].includes(v.status)).map(vehicleView), trips: await Promise.all(rows.map(r => tripView(r, db, true))), requests: await Promise.all(assigned.map(requestView)) };
  }
  async function ownedTrip(driverId, key, tx = db, locked = false) {
    const trip = await getTrip(key, tx, locked);
    if (trip.driver_id !== driverId) fail(404, 'Trip unavailable.');
    await vehicleDriver(trip.vehicle_id, driverId, 'GROUP', tx);
    return trip;
  }
  async function roster(driverId, key) {
    const trip = await ownedTrip(driverId, key);
    const rows = await db.prepare("SELECT t.* FROM mobility_travelers t JOIN mobility_bookings b ON b.id=t.booking_id WHERE t.trip_id=? AND b.payment_status='PAID' ORDER BY t.seat_number").all(key);
    return { trip: await tripView(trip, db, true), travelers: rows.map(p => ({ id: p.id, name: p.name, phone: p.phone, seatNumber: Number(p.seat_number), ticketNumber: p.ticket_number, status: p.status })) };
  }
  async function checkLocation(trip, input, tx) {
    const location = input.location || input, [lat, lng] = coordinates(location.latitude, location.longitude);
    const age = now() - Date.parse(location.timestamp), accuracy = Number(location.accuracyMeters);
    if (!Number.isFinite(age) || age < -30000 || age > 120000 || !Number.isFinite(accuracy) || accuracy <= 0 || accuracy > 100) fail(403, 'Use a fresh, precise device location to start boarding.');
    const rad = n => n * Math.PI / 180, dlat = rad(lat - Number(trip.latitude)), dlng = rad(lng - Number(trip.longitude));
    const a = Math.sin(dlat / 2) ** 2 + Math.cos(rad(lat)) * Math.cos(rad(Number(trip.latitude))) * Math.sin(dlng / 2) ** 2;
    const distance = 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)), radius = await effectiveBoardingRadius(tx);
    if (distance > radius) fail(403, 'Move within the configured boarding radius of the departure point.');
    if (now() < Date.parse(trip.departure_at) - 60 * 60000 || now() > Date.parse(trip.departure_at) + 30 * 60000) fail(409, 'Boarding opens one hour before departure and closes thirty minutes after it.');
  }
  async function startBoarding(driverId, key, input) {
    const row = await write(async tx => {
      const before = await getTrip(key, tx);
      if (before.driver_id !== driverId) fail(404, 'Trip unavailable.');
      await vehicleDriver(before.vehicle_id, driverId, 'GROUP', tx, true);
      const trip = await ownedTrip(driverId, key, tx, true);
      if (!['APPROVED','BOARDING'].includes(trip.status)) fail(409, 'Only approved trips can start boarding.');
      await checkLocation(trip, input, tx);
      if (trip.status === 'BOARDING') return trip;
      const competing = await tx.prepare("SELECT id FROM mobility_trips WHERE id<>? AND status='BOARDING' AND (driver_id=? OR vehicle_id=?) LIMIT 1").get(key, driverId, trip.vehicle_id);
      if (competing) fail(409, 'Complete your current group journey before opening another for boarding.');
      await tx.prepare("UPDATE mobility_trips SET status='BOARDING' WHERE id=?").run(key);
      const vehicle = await tx.prepare('SELECT plateNumber FROM vehicles WHERE id=?').get(trip.vehicle_id);
      const driver = await tx.prepare('SELECT phone FROM users WHERE id=?').get(driverId);
      const bookings = await tx.prepare("SELECT * FROM mobility_bookings WHERE trip_id=? AND payment_status='PAID'").all(key);
      for (const b of bookings) {
        const travelers = await tx.prepare('SELECT phone FROM mobility_travelers WHERE booking_id=?').all(b.id);
        for (const phone of new Set([b.buyer_phone, ...travelers.map(p => p.phone)])) await queueSms(tx, b.id, 'boarding:' + key + ':' + b.id + ':' + phone, phone, `PASSO group travel is ready for boarding: ${trip.origin} to ${trip.destination}. Vehicle ${vehicle?.plateNumber || ''}. Driver ${driver?.phone || ''}. Bring your ticket number or boarding pass.`);
      }
      return getTrip(key, tx);
    });
    void flushSms(); return { trip: await tripView(row, db, true) };
  }
  function verifyTicket(token, travelerId) {
    let decoded; try { decoded = jwt.verify(String(token || ''), JWT_SECRET, { audience: 'passo-group-boarding', issuer: 'routepass' }); } catch (_) { fail(401, 'Boarding pass invalid or expired.'); }
    if (decoded.kind !== 'GROUP_TICKET' || travelerId && decoded.travelerId !== travelerId) fail(401, 'Invalid boarding pass.');
    return decoded;
  }
  async function ticket(key, token) {
    const decoded = verifyTicket(token, key), p = await db.prepare("SELECT t.* FROM mobility_travelers t JOIN mobility_bookings b ON b.id=t.booking_id WHERE t.id=? AND b.payment_status='PAID'").get(key);
    if (!p || decoded.tripId !== p.trip_id) fail(404, 'Boarding pass unavailable.');
    return { traveler: { name: p.name, seatNumber: Number(p.seat_number), ticketNumber: p.ticket_number, status: p.status }, trip: await tripView(await getTrip(p.trip_id)), qrToken: token };
  }
  async function board(driverId, key, input) {
    const result = await write(async tx => {
      const trip = await ownedTrip(driverId, key, tx, true);
      if (trip.status !== 'BOARDING') fail(409, 'Start boarding for this trip first.');
      await checkLocation(trip, input, tx);
      let traveler;
      if (input.qrToken) {
        const decoded = verifyTicket(input.qrToken);
        if (decoded.tripId !== key) fail(400, 'This ticket belongs to a different trip.');
        traveler = await tx.prepare('SELECT * FROM mobility_travelers WHERE id=? AND trip_id=?').get(decoded.travelerId, key);
      } else traveler = await tx.prepare('SELECT * FROM mobility_travelers WHERE ticket_number=? AND trip_id=?').get(text(input.ticketNumber, 'ticket number', 30).toUpperCase(), key);
      if (!traveler) fail(404, 'Ticket unavailable for this trip.');
      const booking = await tx.prepare('SELECT payment_status FROM mobility_bookings WHERE id=?').get(traveler.booking_id);
      if (booking?.payment_status !== 'PAID' || traveler.status !== 'CONFIRMED') fail(409, 'This ticket is unpaid or was already boarded.');
      await tx.prepare("UPDATE mobility_travelers SET status='BOARDED',boarded_at=? WHERE id=?").run(instant(), traveler.id);
      return { id: traveler.id, name: traveler.name, seatNumber: Number(traveler.seat_number), status: 'BOARDED' };
    });
    return { traveler: result };
  }
  async function completeTrip(driverId, key) {
    await write(async tx => {
      const trip = await ownedTrip(driverId, key, tx, true);
      if (trip.status !== 'BOARDING') fail(409, 'Only a boarding trip can be completed.');
      await tx.prepare("UPDATE mobility_trips SET status='COMPLETED' WHERE id=?").run(key);
    });
    return { trip: await tripView(await getTrip(key), db, true) };
  }
  async function audit(tx, actor, action, resource) {
    await tx.prepare('INSERT INTO audit_logs(action,userId,role,details) VALUES(?,?,?,?)').run(action, actor, 'ADMIN', resource);
  }
  let flushing = null;
  function flushSms(options = {}) {
    if (flushing) return flushing;
    flushing = runSmsBatch(options).finally(() => { flushing = null; });
    return flushing;
  }
  async function runSmsBatch({ limit = 20 } = {}) {
    const result = { sent: 0, failed: 0 };
    try {
      for (let index = 0; index < Math.min(100, limit); index++) {
        const row = await write(async tx => {
          const current = await tx.prepare("SELECT * FROM mobility_sms_outbox WHERE attempts<10 AND ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_until<=?)) ORDER BY created_at,id LIMIT 1" + (tx.isPostgres ? ' FOR UPDATE SKIP LOCKED' : '')).get(instant(), instant());
          if (!current) return null;
          let deliverable = false;
          if (current.booking_id) {
            const booking = await tx.prepare('SELECT payment_status FROM mobility_bookings WHERE id=?').get(current.booking_id);
            deliverable = booking?.payment_status === 'PAID';
          } else if (current.request_id) {
            const request = await tx.prepare('SELECT * FROM mobility_requests WHERE id=?').get(current.request_id);
            const fingerprint = request && [request.driver_id,request.vehicle_id,request.quote_etb == null ? '' : Number(request.quote_etb)].join(':');
            const driver = request?.driver_id ? await tx.prepare('SELECT phone FROM users WHERE id=?').get(request.driver_id) : null;
            deliverable = request?.status === 'ASSIGNED' && fingerprint === current.assignment_key && [request.customer_phone,normalizePhone(driver?.phone)].includes(current.phone);
          }
          if (!deliverable) { await tx.prepare("UPDATE mobility_sms_outbox SET status='CANCELLED' WHERE id=?").run(current.id); return { cancelled: true }; }
          const lease = crypto.randomUUID();
          await tx.prepare("UPDATE mobility_sms_outbox SET status='SENDING',attempts=attempts+1,lease_token=?,lease_until=? WHERE id=?").run(lease, new Date(now() + 5 * 60000).toISOString(), current.id);
          return { ...current, lease, attempts: Number(current.attempts) + 1 };
        });
        if (!row) break; if (row.cancelled) continue;
        let sent = false; try { sent = (await sendSms(row.phone, row.message))?.sent === true; } catch (_) {}
        await write(tx => tx.prepare('UPDATE mobility_sms_outbox SET status=?,sent_at=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?')
          .run(sent ? 'SENT' : row.attempts >= 10 ? 'FAILED' : 'PENDING', sent ? instant() : null, new Date(now() + Math.min(3600000, 60000 * 2 ** Math.min(row.attempts - 1, 6))).toISOString(), row.id, row.lease));
        result[sent ? 'sent' : 'failed']++;
      }
    } catch (_) { result.failed++; }
    return result;
  }
  async function cleanDrafts() {
    const cutoff = new Date(now() - 7 * 86400000).toISOString();
    const rows = await write(async tx => {
      const items = await tx.prepare("SELECT id,photo_storage FROM mobility_requests WHERE (status='DRAFT' AND created_at<?) OR (status='EXPIRED' AND photo_storage IS NOT NULL) ORDER BY created_at LIMIT 20" + lock(tx)).all(cutoff);
      for (const row of items) await tx.prepare("UPDATE mobility_requests SET status='EXPIRED' WHERE id=?").run(row.id);
      return items;
    });
    for (const row of rows) {
      try {
        if (row.photo_storage && /^[a-f0-9-]{36}\.blob$/.test(row.photo_storage)) await fs.unlink(path.join(root,row.photo_storage)).catch(error => { if (error.code !== 'ENOENT') throw error; });
        await db.prepare("UPDATE mobility_requests SET photo_storage=NULL,photo_size=NULL,photo_type=NULL,customer_name='',customer_phone='',details='',origin_latitude=NULL,origin_longitude=NULL,destination_latitude=NULL,destination_longitude=NULL WHERE id=? AND status='EXPIRED'").run(row.id);
      } catch (_) { /* Keep the filename so the next cleanup retries a failed physical deletion. */ }
    }
    return { expired: rows.length };
  }
  return { catalog, trips, createTrip, reviewTrip, seats, createBooking, booking, checkout, webhook, createRequest, request, uploadPhoto, photo, submitRequest, requests, options, assignRequest, requestStatus, dashboard, roster, startBoarding, board, completeTrip, ticket, flushSms, cleanDrafts, init: () => initMobilitySchema(db) };
}
let defaultService;
const getMobilityService = () => defaultService ||= createMobilityService();
function startMobilityWorker() {
  if (process.env.NODE_ENV === 'test') return () => {};
  const timer = setInterval(() => { void getMobilityService().flushSms(); }, 30000); timer.unref?.();
  const cleanup = setInterval(() => { void getMobilityService().cleanDrafts().catch(() => {}); }, 3600000); cleanup.unref?.();
  return () => { clearInterval(timer); clearInterval(cleanup); };
}
module.exports = { MobilityError, createMobilityService, getMobilityService, startMobilityWorker };
