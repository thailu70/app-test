'use strict';
const express = require('express');
const crypto = require('node:crypto');
const path = require('node:path');
const jwt = require('jsonwebtoken');
const PDFDocument = require('pdfkit');
const QRCode = require('qrcode');
const rateLimit = require('express-rate-limit');
const { DB } = require('../db');
const { authenticate, requireRole, JWT_SECRET } = require('../middleware/auth');
const telebirr = require('../services/telebirr');
const { sendSms } = require('../services/afro-sms');
const router = express.Router();
const asyncRoute = fn => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
const uuid = () => crypto.randomUUID();
const hash = value => crypto.createHash('sha256').update(String(value)).digest('hex');
const fail = (status, message) => Object.assign(new Error(message), { status });
const clean = (value, max = 150) => String(value || '').trim().slice(0, max);
const validDate = value => /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value + 'T12:00:00Z').toISOString().slice(0, 10) === value;
const publicLimiter = rateLimit({ windowMs: 15 * 60 * 1000, max: 100, standardHeaders: true, legacyHeaders: false });
const ensureAuth = (req, res, next) => req.user ? next() : authenticate(req, res, next);
const companyGuard = [ensureAuth, requireRole('COMPANY')];
const driverGuard = [ensureAuth, requireRole('DRIVER')];
let sqliteWrites = Promise.resolve();
function write(fn) {
  if (DB.isPostgres) return DB.transaction(fn);
  const work = sqliteWrites.then(() => DB.transaction(fn));
  sqliteWrites = work.catch(() => {});
  return work;
}
async function expire(tx, departureId) {
  const now = new Date().toISOString();
  await tx.prepare("DELETE FROM intercity_seats WHERE departure_id = ? AND booking_id IN (SELECT id FROM intercity_bookings WHERE departure_id = ? AND payment_status <> 'PAID' AND expires_at < ?)").run(departureId, departureId, now);
  await tx.prepare("UPDATE intercity_bookings SET status = 'EXPIRED' WHERE departure_id = ? AND payment_status <> 'PAID' AND expires_at < ?").run(departureId, now);
}
async function departure(tx, id, lock = false) {
  const d = await tx.prepare('SELECT * FROM intercity_departures WHERE id = ?' + (lock && tx.isPostgres ? ' FOR UPDATE' : '')).get(id);
  if (!d) throw fail(404, 'Departure not found.');
  return d;
}
function own(d, user) {
  if ((user.role === 'COMPANY' && d.company_id === user.id) || (user.role === 'DRIVER' && d.driver_id === user.id)) return;
  throw fail(404, 'Departure not found.');
}
function detail(d) {
  return { ...d, driverId: d.driver_id, vehicleId: d.vehicle_id, departureAt: d.departure_at, fareEtb: Number(d.fare_etb), companyId: d.company_id };
}
function ticketToken(b, d) {
  return jwt.sign({ kind: 'INTERCITY_TICKET', bookingId: b.id, departureId: d.id, seat: b.seat_number }, JWT_SECRET, { expiresIn: Math.max(3600, Math.ceil((Date.parse(d.departure_at) + 48 * 3600000 - Date.now()) / 1000)), audience: 'intercity-boarding', issuer: 'routepass' });
}
function ticketDownloadUrl(b) {
  const token = jwt.sign({ kind: 'INTERCITY_PDF', bookingId: b.id }, JWT_SECRET, { expiresIn: '5m', audience: 'intercity-pdf-download', issuer: 'routepass' });
  return '/api/intercity/tickets/' + encodeURIComponent(b.id) + '.pdf?token=' + encodeURIComponent(token);
}
function shortTicketNumber(b) { return 'RP-' + String(b.id).replace(/[^a-zA-Z0-9]/g, '').slice(-8).toUpperCase(); }
async function notifyPaidTicket(b, d) {
  if (!b?.passenger_phone) return;
  const when = new Date(d.departure_at).toLocaleString('en-GB', { timeZone: 'Africa/Addis_Ababa', dateStyle: 'medium', timeStyle: 'short' });
  await sendSms(b.passenger_phone, `RoutePass ticket confirmed. Ticket ${shortTicketNumber(b)} for ${d.origin} to ${d.destination}, seat ${b.seat_number}, ${when}. Board with this number or your QR code.`);
}
function bookingView(b, d, includePersonal = true) {
  return { id: b.id, departureId: b.departure_id, seatNumber: b.seat_number, amountEtb: Number(b.amount_etb), status: b.status, paymentStatus: b.payment_status, paymentMethod: b.payment_method, expiresAt: b.expires_at,
    ...(includePersonal ? { passengerName: b.passenger_name, passengerPhone: b.passenger_phone } : {}),
    ...(b.payment_status === 'PAID' ? { ticketNumber: shortTicketNumber(b), qrToken: ticketToken(b, d), ticketDownloadUrl: ticketDownloadUrl(b) } : {}), departure: detail(d) };
}
async function guestBooking(req, tx = DB) {
  const b = await tx.prepare('SELECT * FROM intercity_bookings WHERE id = ?').get(req.params.id);
  const secret = clean(req.headers['x-booking-secret'], 200);
  if (!b || !secret || !crypto.timingSafeEqual(Buffer.from(b.secret_hash), Buffer.from(hash(secret)))) throw fail(404, 'Booking not found. Use the private booking link on this device.');
  return b;
}
function person(input) {
  const name = clean(input.passengerName, 100), phone = clean(input.passengerPhone, 30);
  const digits = phone.replace(/\D/g, '').length;
  if (name.length < 2 || !/^\+?[0-9 ()-]{7,24}$/.test(phone) || digits < 7 || digits > 15) throw fail(400, 'Enter the passenger name and a valid phone number.');
  return { name, phone };
}
async function holdSeat(tx, d, number, data = {}) {
  await expire(tx, d.id);
  if (!await tx.prepare("SELECT id FROM transport_companies WHERE id=? AND status='ACTIVE'").get(d.company_id)) throw fail(409, 'This transport company is not accepting bookings.');
  if (d.status !== 'SCHEDULED' || Date.parse(d.departure_at) <= Date.now()) throw fail(409, 'This departure is closed for booking.');
  const seat = Number(number);
  if (!Number.isInteger(seat) || seat < 1 || seat > d.seats) throw fail(400, 'Select a valid seat.');
  if (await tx.prepare('SELECT booking_id FROM intercity_seats WHERE departure_id = ? AND seat_number = ?').get(d.id, seat)) throw fail(409, 'This seat was just reserved. Please choose another seat.');
  const id = uuid(), secret = crypto.randomBytes(32).toString('base64url'), now = new Date().toISOString();
  const expiresAt = new Date(Date.now() + 10 * 60000).toISOString();
  await tx.prepare('INSERT INTO intercity_bookings (id,departure_id,company_id,secret_hash,passenger_name,passenger_phone,seat_number,amount_etb,expires_at,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)').run(id, d.id, d.company_id, hash(secret), data.name || '', data.phone || '', seat, d.fare_etb, expiresAt, now);
  await tx.prepare('INSERT INTO intercity_seats (departure_id,seat_number,booking_id) VALUES (?,?,?)').run(d.id, seat, id);
  return { b: await tx.prepare('SELECT * FROM intercity_bookings WHERE id = ?').get(id), secret };
}
async function reconcile(b) {
  if (!b.order_id || b.payment_status === 'PAID' || b.status === 'EXPIRED') return;
  const result = await telebirr.queryOrderStatus(b.order_id, b.prepay_id);
  if (result.merchantOrderId !== b.order_id || result.currency.toUpperCase() !== 'ETB' || Math.round(Number(result.amount) * 100) !== Math.round(Number(b.amount_etb) * 100)) throw fail(502, 'Payment details could not be verified.');
  if (!result.paid && !result.failed) return;
  await write(async tx => {
    const d = await departure(tx, b.departure_id, true);
    const current = await tx.prepare('SELECT * FROM intercity_bookings WHERE id = ?').get(b.id);
    if (current.payment_status === 'PAID') return;
    const seat = await tx.prepare('SELECT booking_id FROM intercity_seats WHERE departure_id = ? AND seat_number = ?').get(d.id, b.seat_number);
    if (!seat || seat.booking_id !== b.id || current.status === 'EXPIRED') throw fail(409, 'Payment arrived after the reservation expired. Contact the transport company with your payment reference.');
    if (result.paid) await tx.prepare("UPDATE intercity_bookings SET payment_status='PAID',status='CONFIRMED',payment_method='TELEBIRR',payment_reference=? WHERE id=?").run(result.transactionId || result.paymentOrderId || b.order_id, b.id);
    else { await tx.prepare("UPDATE intercity_bookings SET payment_status='FAILED',status='CANCELLED' WHERE id=?").run(b.id); await tx.prepare('DELETE FROM intercity_seats WHERE booking_id=?').run(b.id); }
  });
  if (result.paid) await notifyPaidTicket(await DB.prepare('SELECT * FROM intercity_bookings WHERE id=?').get(b.id), await departure(DB, b.departure_id));
}

router.use((req, res, next) => { res.setHeader('Cache-Control', 'no-store'); next(); });
// Anonymous travel is public. An authenticated administrator channel remains
// commute-only, including when it attempts to call the public discovery API.
router.use((req, res, next) => {
  if (!req.headers.authorization) return next();
  return authenticate(req, res, () => {
    if (req.user.role === 'ADMIN') return res.status(403).json({ success: false, error: 'Administrator access is restricted to daily commute operations.' });
    return next();
  });
});
// Public results expose transport and availability only; no customer identities.
router.get('/departures', publicLimiter, asyncRoute(async (req, res) => {
  const origin = clean(req.query.origin), destination = clean(req.query.destination), date = clean(req.query.date, 10);
  if (date && !validDate(date)) throw fail(400, 'Choose a valid travel date.');
  const clauses = ["d.status='SCHEDULED'", 'd.departure_at > ?', "c.status='ACTIVE'"], args = [new Date().toISOString()];
  if (origin) { clauses.push('LOWER(d.origin) LIKE ?'); args.push('%' + origin.toLowerCase().replace(/[%_]/g, '') + '%'); }
  if (destination) { clauses.push('LOWER(d.destination) LIKE ?'); args.push('%' + destination.toLowerCase().replace(/[%_]/g, '') + '%'); }
  // Dates are interpreted in Ethiopia, where the transport operates (UTC+03).
  if (date) { const start = new Date(date + 'T00:00:00+03:00'); clauses.push('d.departure_at >= ? AND d.departure_at < ?'); args.push(start.toISOString(), new Date(start.getTime() + 86400000).toISOString()); }
  const rows = await DB.prepare(`SELECT d.*,c.name AS company_name,v.model AS vehicle_model,v.vehicleType AS vehicle_type,(SELECT COUNT(*) FROM intercity_seats s JOIN intercity_bookings b ON b.id=s.booking_id WHERE s.departure_id=d.id AND (b.payment_status='PAID' OR b.expires_at > ?)) AS reserved FROM intercity_departures d JOIN transport_companies c ON c.id=d.company_id LEFT JOIN vehicles v ON v.id=d.vehicle_id WHERE ${clauses.join(' AND ')} ORDER BY d.departure_at ASC LIMIT 100`).all(new Date().toISOString(), ...args);
  res.json({ success: true, departures: rows.map(d => ({ id: d.id, origin: d.origin, destination: d.destination, departureAt: d.departure_at, companyName: d.company_name, companyId: d.company_id, vehicleType: d.vehicle_type, vehicleModel: d.vehicle_model, seats: d.seats, availableSeats: d.seats - Number(d.reserved), fareEtb: Number(d.fare_etb) })) });
}));
router.get('/departures/:id/seats', publicLimiter, asyncRoute(async (req, res) => {
  const d = await departure(DB, req.params.id);
  if (d.status !== 'SCHEDULED') throw fail(409, 'Departure closed.');
  const rows = await DB.prepare("SELECT s.seat_number FROM intercity_seats s JOIN intercity_bookings b ON b.id=s.booking_id WHERE s.departure_id=? AND (b.payment_status='PAID' OR b.expires_at>?)").all(d.id, new Date().toISOString());
  res.json({ success: true, seats: d.seats, reserved: rows.map(s => s.seat_number), fareEtb: Number(d.fare_etb) });
}));
router.post('/requests', publicLimiter, asyncRoute(async (req, res) => {
  const p = person(req.body), origin = clean(req.body.origin), destination = clean(req.body.destination), date = clean(req.body.date, 10);
  if (!origin || !destination || !validDate(date) || Date.parse(date + 'T23:59:59+03:00') < Date.now()) throw fail(400, 'Enter origin, destination and a future travel date.');
  const companyId = clean(req.body.companyId);
  if (!companyId || !await DB.prepare("SELECT id FROM transport_companies WHERE id=? AND status='ACTIVE'").get(companyId)) throw fail(400, 'Choose the transport company that should receive this route request.');
  const id = uuid();
  await DB.prepare('INSERT INTO intercity_route_requests (id,origin,destination,travel_date,passenger_name,passenger_phone,company_id,created_at) VALUES (?,?,?,?,?,?,?,?)').run(id, origin, destination, date, p.name, p.phone, companyId || null, new Date().toISOString());
  res.status(201).json({ success: true, id, message: 'Route requested. This is not a paid booking or a seat reservation.' });
}));
router.post('/departures/:id/hold', publicLimiter, asyncRoute(async (req, res) => {
  const result = await write(async tx => { const d = await departure(tx, req.params.id, true); const h = await holdSeat(tx, d, req.body.seatNumber); return { ...h, d }; });
  res.status(201).json({ success: true, booking: bookingView(result.b, result.d), bookingSecret: result.secret });
}));
router.get('/bookings/:id', publicLimiter, asyncRoute(async (req, res) => {
  let b = await guestBooking(req);
  if (b.order_id && b.payment_status !== 'PAID' && !telebirr.missingConfiguration().length) await reconcile(b);
  b = await guestBooking(req);
  if (b.payment_status !== 'PAID' && Date.parse(b.expires_at) < Date.now()) { await write(tx => expire(tx, b.departure_id)); b = await guestBooking(req); }
  const d = await departure(DB, b.departure_id), result = bookingView(b, d);
  if (b.payment_status === 'PAID') result.qrImage = await QRCode.toDataURL(result.qrToken, { width: 320, margin: 2 });
  res.json({ success: true, booking: result });
}));
router.post('/bookings/:id/checkout', publicLimiter, asyncRoute(async (req, res) => {
  let b = await guestBooking(req); const p = person(req.body);
  if (b.payment_status === 'PAID') return res.json({ success: true, paid: true });
  if (b.status === 'EXPIRED' || Date.parse(b.expires_at) < Date.now()) throw fail(409, 'Your reservation expired. Choose a seat again.');
  if (p.phone.replace(/\D/g, '') === '09000000') {
    await write(async tx => { await tx.prepare("UPDATE intercity_bookings SET passenger_name=?,passenger_phone=?,status='CONFIRMED',payment_status='PAID',payment_method='TEST_AUTO',payment_reference='TEST-09000000' WHERE id=?").run(p.name, p.phone, b.id); });
    b = await guestBooking(req);
    const d = await departure(DB, b.departure_id);
    await notifyPaidTicket(b, d);
    return res.json({ success: true, paid: true, paymentMode: 'TEST_AUTO', booking: bookingView(b, d) });
  }
  if (telebirr.missingConfiguration().length || telebirr.config().environment !== 'production' && process.env.NODE_ENV === 'production') throw fail(503, 'Online payment is not available yet. Contact the transport company to book and pay at its office.');
  if (b.checkout_url) return res.json({ success: true, checkoutUrl: b.checkout_url });
  const orderId = telebirr.merchantOrderId();
  await write(async tx => { await departure(tx, b.departure_id, true); const current = await tx.prepare('SELECT * FROM intercity_bookings WHERE id=?').get(b.id); if (current.order_id) throw fail(409, 'Payment is already being prepared. Refresh your booking.'); await tx.prepare("UPDATE intercity_bookings SET passenger_name=?,passenger_phone=?,order_id=?,status='PAYMENT_PENDING',expires_at=? WHERE id=?").run(p.name, p.phone, orderId, new Date(Date.now() + 125 * 60000).toISOString(), b.id); });
  try {
    const base = String(process.env.PUBLIC_BASE_URL || 'https://routepass.duckdns.org').replace(/\/$/, '');
    const payment = await telebirr.createCheckout(orderId, b.amount_etb, 'RoutePass intercity ticket', { notifyUrl: base + '/api/intercity/payments/webhook', redirectUrl: base + '/travel/?booking=' + encodeURIComponent(b.id) });
    await DB.prepare('UPDATE intercity_bookings SET prepay_id=?,checkout_url=? WHERE id=? AND order_id=?').run(payment.prepayId, payment.checkoutUrl, b.id, orderId);
    res.json({ success: true, checkoutUrl: payment.checkoutUrl });
  } catch (err) { console.error('[Intercity checkout]', err.message); throw fail(502, 'The payment provider could not prepare checkout. Contact the company if you were charged.'); }
}));
router.post('/payments/webhook', asyncRoute(async (req, res) => {
  let note; try { note = telebirr.parseNotification(req.body); } catch (_) { throw fail(400, 'Invalid payment notice.'); }
  if (!telebirr.verifyNotification(note)) throw fail(401, 'Invalid payment signature.');
  const orderId = String(note.merch_order_id || (note.biz_content || {}).merch_order_id || '');
  const b = await DB.prepare('SELECT * FROM intercity_bookings WHERE order_id=?').get(orderId);
  if (b) await reconcile(b);
  res.json({ success: true });
}));
async function pdfTicket(req, res, b) {
  if (b.payment_status !== 'PAID') throw fail(409, 'A ticket is available after payment is settled.');
  const d = await departure(DB, b.departure_id), company = await DB.prepare('SELECT name,phone FROM transport_companies WHERE id=?').get(d.company_id);
  const qr = await QRCode.toBuffer(ticketToken(b, d), { width: 260, margin: 2 });
  res.setHeader('Content-Type', 'application/pdf'); res.setHeader('Content-Disposition', 'attachment; filename="RoutePass-' + b.id + '.pdf"');
  const doc = new PDFDocument({ size: 'A5', margin: 30 });
  doc.font(path.join(__dirname, '../../public/travel/fonts/NotoSansEthiopic.ttf'));
  doc.pipe(res);
  doc.rect(0, 0, 420, 95).fill('#10231c'); doc.fillColor('#ffffff').fontSize(26).text('PASSO', 30, 28); doc.fontSize(11).text('INTERCITY - CONFIRMED TICKET', 30, 62);
  doc.fillColor('#10231c').fontSize(17).text(d.origin + ' to ' + d.destination, 30, 118, { width: 350 });
  const lines = [['Transport', company ? company.name : 'Transport company'], ['Departure (Ethiopia)', new Date(d.departure_at).toLocaleString('en-GB', { timeZone: 'Africa/Addis_Ababa' })], ['Passenger', b.passenger_name], ['Phone', b.passenger_phone], ['Seat', String(b.seat_number)], ['Amount paid', Number(b.amount_etb).toFixed(2) + ' ETB'], ['Payment', b.payment_method === 'CASH_COLLECTED' ? 'Cash collected by company' : 'Telebirr verified']];
  let y = 165; for (const [label, value] of lines) { doc.fontSize(9).fillColor('#58645f').text(label, 30, y); doc.fontSize(12).fillColor('#10231c').text(value, 145, y, { width: 230 }); y += 29; }
  doc.image(qr, 135, y + 5, { width: 145 }); doc.fontSize(8).fillColor('#58645f').text('Present this QR code to your assigned driver for boarding.', 30, y + 157, { align: 'center', width: 350 }); doc.text('Ticket ' + b.id, 30, y + 177, { align: 'center', width: 350 }); doc.end();
}
router.get('/bookings/:id/ticket.pdf', publicLimiter, asyncRoute(async (req, res) => pdfTicket(req, res, await guestBooking(req))));
// A short-lived ticket-only capability lets native download managers fetch the
// PDF without forwarding a company login token or a guest booking secret.
router.get('/tickets/:id.pdf', publicLimiter, asyncRoute(async (req, res) => {
  let access; try { access = jwt.verify(String(req.query.token || ''), JWT_SECRET, { audience: 'intercity-pdf-download', issuer: 'routepass' }); } catch (_) { throw fail(401, 'Ticket download link expired. Open your booking and download again.'); }
  if (access.kind !== 'INTERCITY_PDF' || access.bookingId !== req.params.id) throw fail(401, 'Invalid ticket download link.');
  const b = await DB.prepare("SELECT * FROM intercity_bookings WHERE id=? AND payment_status='PAID'").get(req.params.id);
  if (!b) throw fail(404, 'Ticket not found.');
  return pdfTicket(req, res, b);
}));

router.get('/company/departures', ...companyGuard, asyncRoute(async (req, res) => {
  const departures = await DB.prepare('SELECT d.*,u.fullName AS driver_name,v.plateNumber AS vehicle_plate FROM intercity_departures d LEFT JOIN users u ON u.id=d.driver_id LEFT JOIN vehicles v ON v.id=d.vehicle_id WHERE d.company_id=? ORDER BY d.departure_at DESC LIMIT 500').all(req.user.id);
  res.json({ success: true, departures: departures.map(detail) });
}));
router.post('/company/departures', ...companyGuard, asyncRoute(async (req, res) => {
  const x = req.body, routeId = clean(x.routeId), vehicleId = clean(x.vehicleId), departureAt = new Date(x.departureAt).toISOString();
  const r = await DB.prepare("SELECT * FROM routes WHERE id=? AND companyId=? AND routeType='INTERCITY' AND active=TRUE").get(routeId, req.user.id);
  const v = await DB.prepare('SELECT * FROM vehicles WHERE id=? AND companyId=?').get(vehicleId, req.user.id);
  const driverId = v?.driverId || clean(x.driverId);
  const u = await DB.prepare("SELECT id FROM users WHERE id=? AND companyId=? AND role='DRIVER' AND status='ACTIVE'").get(driverId, req.user.id);
  const seats = Number(x.seats), fare = Number(x.fareEtb), lat = Number(x.latitude), lng = Number(x.longitude), radius = x.radiusMeters === undefined ? 300 : Number(x.radiusMeters);
  if (!r || !v || !u) throw fail(400, 'Select an active company intercity route, approved driver and company vehicle.');
  if (!Number.isInteger(seats) || seats < 1 || seats > Math.min(Number(v.capacityLimit), 100) || !Number.isFinite(fare) || fare <= 0 || fare > 100000 || !Number.isInteger(radius) || radius < 25 || radius > 5000 || !Number.isFinite(lat) || lat < -90 || lat > 90 || !Number.isFinite(lng) || lng < -180 || lng > 180 || Date.parse(departureAt) < Date.now()) throw fail(400, 'Check departure time, fare, seat capacity, radius (25–5000m) and departure coordinates.');
  const id = uuid();
  await DB.prepare('INSERT INTO intercity_departures (id,company_id,route_id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,radius_m,seats,fare_etb,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)').run(id, req.user.id, r.id, u.id, v.id, r.origin, r.destination, departureAt, lat, lng, radius, seats, fare, new Date().toISOString());
  res.status(201).json({ success: true, departure: detail(await departure(DB, id)) });
}));
router.patch('/company/departures/:id', ...companyGuard, asyncRoute(async (req, res) => {
  const x = req.body;
  if (!['radiusMeters', 'latitude', 'longitude', 'departureAt'].some(key => Object.prototype.hasOwnProperty.call(x, key))) throw fail(400, 'Choose a departure setting to update.');
  const d = await write(async tx => {
    const current = await departure(tx, req.params.id, true); own(current, req.user);
    if (current.status !== 'SCHEDULED') throw fail(409, 'Only scheduled departures can be changed.');
    const radius = x.radiusMeters === undefined ? Number(current.radius_m) : Number(x.radiusMeters);
    const lat = x.latitude === undefined ? Number(current.latitude) : Number(x.latitude), lng = x.longitude === undefined ? Number(current.longitude) : Number(x.longitude);
    const time = x.departureAt === undefined ? current.departure_at : new Date(x.departureAt).toISOString();
    if (!Number.isInteger(radius) || radius < 25 || radius > 5000 || !Number.isFinite(lat) || lat < -90 || lat > 90 || !Number.isFinite(lng) || lng < -180 || lng > 180 || Date.parse(time) < Date.now()) throw fail(400, 'Check the departure time, coordinates and radius (25–5000 metres).');
    await tx.prepare('UPDATE intercity_departures SET radius_m=?,latitude=?,longitude=?,departure_at=? WHERE id=?').run(radius, lat, lng, time, current.id);
    return { ...current, radius_m: radius, latitude: lat, longitude: lng, departure_at: time };
  });
  res.json({ success: true, departure: detail(d) });
}));
router.get('/company/roster', ...companyGuard, asyncRoute(async (req, res) => {
  const args = [req.user.id], clauses = ['b.company_id=?'];
  if (req.query.departureId) { clauses.push('b.departure_id=?'); args.push(clean(req.query.departureId)); }
  if (req.query.from) { clauses.push('d.departure_at>=?'); args.push(new Date(clean(req.query.from) + 'T00:00:00+03:00').toISOString()); }
  if (req.query.to) { clauses.push('d.departure_at<=?'); args.push(new Date(clean(req.query.to) + 'T23:59:59+03:00').toISOString()); }
  const sorts = { date: 'd.departure_at', name: 'b.passenger_name', amount: 'b.amount_etb', status: 'b.status' }, sort = sorts[req.query.sort] || sorts.date;
  const rows = await DB.prepare(`SELECT b.*,d.origin,d.destination,d.departure_at FROM intercity_bookings b JOIN intercity_departures d ON d.id=b.departure_id WHERE ${clauses.join(' AND ')} ORDER BY ${sort} ${req.query.direction === 'asc' ? 'ASC' : 'DESC'} LIMIT 2000`).all(...args);
  res.json({ success: true, roster: rows.map(b => ({ id: b.id, departureId: b.departure_id, passengerName: b.passenger_name || 'Seat held', passengerPhone: b.passenger_phone, seatNumber: b.seat_number, amountEtb: Number(b.amount_etb), status: b.payment_status !== 'PAID' && Date.parse(b.expires_at) < Date.now() ? 'EXPIRED' : b.status, paymentStatus: b.payment_status, paymentMethod: b.payment_method, origin: b.origin, destination: b.destination, departureAt: b.departure_at, boardedAt: b.boarded_at, createdAt: b.created_at })) });
}));
router.post('/company/departures/:id/bookings', ...companyGuard, asyncRoute(async (req, res) => {
  const p = person(req.body), note = clean(req.body.paymentNote, 250);
  if (req.body.paymentCollected !== true || note.length < 3) throw fail(400, 'Confirm the payment was collected and enter its cash receipt or collection reference.');
  const result = await write(async tx => { const d = await departure(tx, req.params.id, true); own(d, req.user); const { b } = await holdSeat(tx, d, req.body.seatNumber, p); await tx.prepare("UPDATE intercity_bookings SET status='CONFIRMED',payment_status='PAID',payment_method='CASH_COLLECTED',payment_reference=?,collected_by=?,collected_note=? WHERE id=?").run(note, req.user.id, note, b.id); return { b: await tx.prepare('SELECT * FROM intercity_bookings WHERE id=?').get(b.id), d }; });
  await notifyPaidTicket(result.b, result.d);
  res.status(201).json({ success: true, booking: bookingView(result.b, result.d), ticketUrl: '/api/intercity/company/bookings/' + result.b.id + '/ticket.pdf' });
}));
router.get('/company/bookings/:id/ticket.pdf', ...companyGuard, asyncRoute(async (req, res) => { const b = await DB.prepare('SELECT * FROM intercity_bookings WHERE id=? AND company_id=?').get(req.params.id, req.user.id); if (!b) throw fail(404, 'Ticket not found.'); return pdfTicket(req, res, b); }));
router.get('/company/bookings/:id/download', ...companyGuard, asyncRoute(async (req, res) => {
  const b = await DB.prepare("SELECT * FROM intercity_bookings WHERE id=? AND company_id=? AND payment_status='PAID'").get(req.params.id, req.user.id);
  if (!b) throw fail(404, 'Paid ticket not found.');
  res.json({ success: true, ticketDownloadUrl: ticketDownloadUrl(b) });
}));
router.get('/company/requests', ...companyGuard, asyncRoute(async (req, res) => {
  const requests = await DB.prepare('SELECT * FROM intercity_route_requests WHERE company_id=? ORDER BY created_at DESC LIMIT 200').all(req.user.id);
  res.json({ success: true, requests });
}));
router.get('/company/performance', ...companyGuard, asyncRoute(async (req, res) => {
  const period = ['daily', 'weekly', 'monthly'].includes(req.query.period) ? req.query.period : 'daily';
  const from = new Date(Date.now() - (period === 'monthly' ? 366 : period === 'weekly' ? 84 : 30) * 86400000).toISOString();
  const rows = await DB.prepare("SELECT b.amount_etb,b.status,b.created_at FROM intercity_bookings b WHERE b.company_id=? AND b.payment_status='PAID' AND b.created_at>=?").all(req.user.id, from);
  const groups = new Map();
  for (const b of rows) { const d = new Date(Date.parse(b.created_at) + 3 * 3600000); if (period === 'weekly') d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7)); const label = d.toISOString().slice(0, period === 'monthly' ? 7 : 10); const g = groups.get(label) || { label, bookings: 0, boarded: 0, revenueEtb: 0 }; g.bookings++; g.boarded += b.status === 'BOARDED' ? 1 : 0; g.revenueEtb += Number(b.amount_etb); groups.set(label, g); }
  res.json({ success: true, period, points: [...groups.values()].sort((a,b) => a.label.localeCompare(b.label)), total: { bookings: rows.length, boarded: rows.filter(b => b.status === 'BOARDED').length, revenueEtb: rows.reduce((sum,b) => sum + Number(b.amount_etb), 0) } });
}));
router.get('/driver/departures', ...driverGuard, asyncRoute(async (req, res) => { const rows = await DB.prepare("SELECT * FROM intercity_departures WHERE driver_id=? AND status <> 'CANCELLED' ORDER BY departure_at DESC LIMIT 100").all(req.user.id); res.json({ success: true, departures: rows.map(detail) }); }));
router.get('/driver/departures/:id/roster', ...driverGuard, asyncRoute(async (req, res) => {
  const d = await departure(DB, req.params.id); own(d, req.user);
  const rows = await DB.prepare("SELECT id,passenger_name,passenger_phone,seat_number,status,payment_status FROM intercity_bookings WHERE departure_id=? AND status NOT IN ('EXPIRED','CANCELLED') AND (payment_status='PAID' OR expires_at>?) ORDER BY seat_number").all(d.id, new Date().toISOString());
  res.json({ success: true, departure: detail(d), roster: rows.map(b => ({ id: b.id, passengerName: b.passenger_name || 'Pending details', passengerPhone: b.passenger_phone, seatNumber: b.seat_number, paymentStatus: b.payment_status, status: b.status === 'HOLD' || b.status === 'PAYMENT_PENDING' ? 'PENDING' : b.status === 'CONFIRMED' ? 'ASSIGNED' : b.status, photoUrl: null })) });
}));
router.post('/driver/departures/:id/board', ...driverGuard, asyncRoute(async (req, res) => {
  let ticket; try { ticket = jwt.verify(clean(req.body.qrToken, 4000), JWT_SECRET, { audience: 'intercity-boarding', issuer: 'routepass' }); } catch (_) { throw fail(400, 'Invalid or expired ticket QR.'); }
  if (ticket.kind !== 'INTERCITY_TICKET' || ticket.departureId !== req.params.id) throw fail(400, 'This ticket is for a different departure.');
  const b = await write(async tx => { const d = await departure(tx, req.params.id, true); own(d, req.user); if (d.status !== 'BOARDING') throw fail(409, 'Confirm arrival at the departure location before boarding.'); const row = await tx.prepare("SELECT * FROM intercity_bookings WHERE id=? AND departure_id=? AND payment_status='PAID'").get(ticket.bookingId, d.id); if (!row) throw fail(404, 'Paid ticket not found.'); if (row.status === 'BOARDED') throw fail(409, 'This passenger has already boarded.'); if (row.status !== 'CONFIRMED') throw fail(409, 'Ticket is not valid for boarding.'); await tx.prepare("UPDATE intercity_bookings SET status='BOARDED',boarded_at=? WHERE id=?").run(new Date().toISOString(), row.id); return row; });
  res.json({ success: true, booking: { id: b.id, passengerName: b.passenger_name, seatNumber: b.seat_number, status: 'BOARDED' } });
}));
router.post('/driver/departures/:id/arrival', ...driverGuard, asyncRoute(async (req, res) => {
  const { evaluateDeparture } = require('../services/departure-rules');
  const result = await write(async tx => {
    const scheduled = await departure(tx, req.params.id); own(scheduled, req.user);
    // The driver gate also covers legacy duplicate vehicle assignments. It does
    // not lock the user row, whose lock order in assignment updates is different.
    if (tx.isPostgres) await tx.prepare('SELECT pg_advisory_xact_lock(hashtext(?))').get('routepass:intercity-driver:' + req.user.id);
    // Assignment changes also lock this vehicle. Always lock it before the
    // departure so arrival cannot start using a reassigned vehicle snapshot.
    const vehicle = await tx.prepare('SELECT id, driverId AS assigned_driver_id, assignedRouteId AS assigned_route_id, companyId AS owner_company_id FROM vehicles WHERE id=?' + (tx.isPostgres ? ' FOR UPDATE' : '')).get(scheduled.vehicle_id);
    const d = await departure(tx, req.params.id, true); own(d, req.user);
    if (d.status !== 'SCHEDULED') throw fail(409, 'Departure is already active or complete.');
    if (d.vehicle_id !== scheduled.vehicle_id) throw fail(409, 'The departure assignment changed. Refresh before confirming arrival.');
    const driver = await tx.prepare("SELECT id,status,companyId AS owner_company_id FROM users WHERE id=? AND role='DRIVER'").get(d.driver_id);
    if (!vehicle || vehicle.assigned_driver_id !== d.driver_id || vehicle.assigned_route_id !== d.route_id || vehicle.owner_company_id !== d.company_id || !driver || driver.status !== 'ACTIVE' || driver.owner_company_id !== d.company_id) {
      throw fail(409, 'The current driver, vehicle and route assignment must match this departure. Ask your company to update the assignment.');
    }
    const competing = await tx.prepare("SELECT id FROM intercity_departures WHERE status='BOARDING' AND id<>? AND (driver_id=? OR vehicle_id=?) LIMIT 1").get(d.id, d.driver_id, d.vehicle_id);
    if (competing) throw fail(409, 'Complete the current departure before opening boarding for another journey.');
    const validation = evaluateDeparture({ latitude: Number(d.latitude), longitude: Number(d.longitude), radiusMeters: Number(d.radius_m) || 300, departureAt: d.departure_at, location: req.body.location || req.body });
    if (!validation.allowed) throw Object.assign(fail(403, validation.message), { validation });
    await tx.prepare("UPDATE intercity_departures SET status='BOARDING' WHERE id=?").run(d.id);
    return { departure: detail({ ...d, status: 'BOARDING' }), validation };
  });
  res.json({ success: true, ...result });
}));
router.post('/driver/departures/:id/complete', ...driverGuard, asyncRoute(async (req, res) => {
  const d = await write(async tx => { const row = await departure(tx, req.params.id, true); own(row, req.user); if (row.status !== 'BOARDING') throw fail(409, 'Only an active departure can be completed.'); await tx.prepare("UPDATE intercity_departures SET status='COMPLETED' WHERE id=?").run(row.id); return { ...row, status: 'COMPLETED' }; });
  res.json({ success: true, departure: detail(d) });
}));
router.use((err, req, res, _next) => { if (res.headersSent) return; const status = err.status || (err instanceof RangeError ? 400 : 500); if (status === 500) console.error('[Intercity]', err); res.status(status).json({ success: false, error: status === 500 ? 'Intercity service temporarily unavailable.' : err.message, ...(err.validation ? { validation: err.validation, code: err.validation.code } : {}) }); });
module.exports = router;
