"use strict";
const crypto = require("node:crypto");
const { DB } = require("../db");
const { initRidesSchema } = require("./rides-schema");
const { normalizePhone, sendSms, sendTransactionalSms } = require("./afro-sms");
const ACTIVE = ["ACCEPTED", "AT_PICKUP", "IN_PROGRESS"];
class RideError extends Error {
  constructor(status, message, code = "RIDE_ERROR") {
    super(message);
    this.status = status;
    this.code = code;
  }
}
const fail = (status, message, code) => {
  throw new RideError(status, message, code);
};
const lock = (tx) => (tx.isPostgres ? " FOR UPDATE" : "");
const id = (prefix) => prefix + crypto.randomUUID();
const clean = (value, label, max = 180) => {
  if (typeof value !== "string" || !value.trim() || value.trim().length > max)
    fail(400, `Enter a valid ${label}.`);
  return value.trim();
};
const number = (value, label, min = 0, max = 100000) => {
  const n = Number(value);
  if (
    value == null ||
    value === "" ||
    !Number.isFinite(n) ||
    n < min ||
    n > max
  )
    fail(400, `Enter a valid ${label}.`);
  return n;
};
const integer = (value, label, min, max) => {
  const n = number(value, label, min, max);
  if (!Number.isInteger(n)) fail(400, `Enter a whole number for ${label}.`);
  return n;
};
const money = (value) => Math.round(number(value, "amount", 0, 1000000) * 100);
const parse = (value) => JSON.parse(value || "{}");
const round = (n) => Math.round(n * 100) / 100;
const km = (a, b, c, d) => {
  const r = (v) => (v * Math.PI) / 180;
  const h =
    Math.sin(r(c - a) / 2) ** 2 +
    Math.cos(r(a)) * Math.cos(r(c)) * Math.sin(r(d - b) / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
};
const defaults = {
  enabled: false,
  sharedEnabled: false,
  baseFareEtb: 0,
  perKmEtb: 0,
  waitingPerMinuteEtb: 0,
  minimumFareEtb: 0,
  pickupGraceMinutes: 5,
  stopGraceMinutes: 2,
  fixedCommissionEtb: 0,
  sharedFixedCommissionEtb: 0,
  notificationRadiusKm: 10,
  arrivalRadiusMeters: 250,
  scheduleBufferMinutes: 45,
  maxTripMinutes: 180,
  pollingSeconds: 8,
  gpsUploadSeconds: 10,
  minimumSharedFareEtb: 1,
  maximumSharedFareEtb: 10000,
  sharedCommissionPolicy: "PER_TRIP",
  noShowGraceMinutes: 10,
  maxWaitingMinutes: 60,
};
const defaultTiers = [
  { id: "ECONOMY", name: "Economy", multiplier: 1 },
  { id: "COMFORT", name: "Comfort", multiplier: 1.25 },
  { id: "MINI_VAN", name: "Mini-Van", multiplier: 1.5 },
];
function routeInput(input) {
  const r = {
    origin: clean(input.origin, "pickup"),
    destination: clean(input.destination, "destination"),
  };
  for (const key of ["originLatitude", "destinationLatitude"])
    r[key] = number(input[key], "latitude", -90, 90);
  for (const key of ["originLongitude", "destinationLongitude"])
    r[key] = number(input[key], "longitude", -180, 180);
  if (
    km(
      r.originLatitude,
      r.originLongitude,
      r.destinationLatitude,
      r.destinationLongitude,
    ) < 0.05
  )
    fail(400, "Choose different pickup and destination locations.");
  return r;
}
async function tableExists(tx, name) {
  return tx.isPostgres
    ? Boolean(
        await tx
          .prepare(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=current_schema() AND table_name=?",
          )
          .get(name),
      )
    : Boolean(
        await tx
          .prepare(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
          )
          .get(name),
      );
}
// Existing services call this after their vehicle lock. The common driver lock
// serializes claims even when the driver has more than one approved vehicle.
async function assertNoRideJob(driverId, vehicleId, tx = DB) {
  await tx.prepare("SELECT id FROM users WHERE id=?" + lock(tx)).get(driverId);
  if (!(await tableExists(tx, "ride_jobs"))) return;
  if (
    (await tx
      .prepare(
        "SELECT id FROM ride_jobs WHERE (driver_id=? OR vehicle_id=?) AND status IN ('ACCEPTED','AT_PICKUP','IN_PROGRESS') LIMIT 1",
      )
      .get(driverId, vehicleId)) ||
    (await tx
      .prepare(
        "SELECT id FROM ride_shared_trips WHERE (driver_id=? OR vehicle_id=?) AND status IN ('BOARDING','IN_PROGRESS') LIMIT 1",
      )
      .get(driverId, vehicleId))
  )
    fail(
      409,
      "Finish the current Passo ride before starting another transport job.",
      "DRIVER_BUSY",
    );
}
function createRidesService({
  db = DB,
  now = Date.now,
  geo = require("./mobility-geo").getGeoService(),
  secret = process.env.JWT_SECRET || "development-rides-key",
} = {}) {
  const instant = () => new Date(now()).toISOString();
  let smsFlush = null;
  async function queueRideSms(tx, rideId, event, phone, message) {
    const recipient = normalizePhone(phone);
    if (!recipient) return;
    const eventKey = `${event}:${rideId}:${recipient}`;
    await tx.prepare("INSERT INTO ride_sms_outbox(id,event_key,ride_id,phone,message,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(event_key) DO NOTHING")
      .run(id("rsms_"), eventKey, rideId, recipient, message, instant(), instant());
  }
  async function queueTaxiSms(tx, ride, event) {
    const route = parse(ride.route_json);
    const account = ride.passenger_id ? await tx.prepare("SELECT phone FROM users WHERE id=?").get(ride.passenger_id) : null;
    const phone = account?.phone || route.streetPassengerPhone;
    const label = event === "start" ? "started" : "completed";
    await queueRideSms(tx, ride.id, `ride-${event}`, phone,
      `PASSO taxi trip ${label}: ${route.origin} to ${route.destination}. Open Passo to view your trip.`);
  }
  async function flushRideSms({ limit = 20 } = {}) {
    if (smsFlush) return smsFlush;
    smsFlush = (async () => {
      const result = { sent: 0, failed: 0 };
      for (let index = 0; index < Math.min(100, limit); index++) {
        const row = await db.transaction(async tx => {
          const current = await tx.prepare("SELECT * FROM ride_sms_outbox WHERE attempts<8 AND ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_until<=?)) ORDER BY created_at,id LIMIT 1" + (tx.isPostgres ? " FOR UPDATE SKIP LOCKED" : ""))
            .get(instant(), instant());
          if (!current) return null;
          const event = current.event_key.split(":", 1)[0];
          let deliverable = false;
          if (event.startsWith("ride-")) {
            const ride = await tx.prepare("SELECT status,passenger_id,source,route_json FROM ride_jobs WHERE id=?").get(current.ride_id);
            const route = parse(ride?.route_json);
            const passenger = ride?.passenger_id ? await tx.prepare("SELECT phone FROM users WHERE id=?").get(ride.passenger_id) : null;
            const intended = normalizePhone(passenger?.phone) || normalizePhone(route.streetPassengerPhone);
            deliverable = event === "ride-start" ? ["IN_PROGRESS", "COMPLETED"].includes(ride?.status) && intended === current.phone
              : event === "ride-complete" && ride?.status === "COMPLETED" && intended === current.phone;
          } else if (event.startsWith("shared-")) {
            const trip = await tx.prepare("SELECT status FROM ride_shared_trips WHERE id=?").get(current.ride_id);
            const passengers = await tx.prepare("SELECT u.phone FROM ride_shared_bookings b JOIN users u ON u.id=b.passenger_id WHERE b.trip_id=? AND b.status IN ('BOARDED','COMPLETED')").all(current.ride_id);
            const intended = passengers.some(passenger => normalizePhone(passenger.phone) === current.phone);
            deliverable = event === "shared-start" ? ["IN_PROGRESS", "COMPLETED"].includes(trip?.status) && intended
              : event === "shared-complete" && trip?.status === "COMPLETED" && intended;
          }
          if (!deliverable) { await tx.prepare("UPDATE ride_sms_outbox SET status='CANCELLED' WHERE id=?").run(current.id); return { cancelled: true }; }
          const lease = crypto.randomUUID();
          await tx.prepare("UPDATE ride_sms_outbox SET status='SENDING',attempts=attempts+1,lease_token=?,lease_until=? WHERE id=?").run(lease, new Date(now() + 5 * 60000).toISOString(), current.id);
          return { ...current, lease, attempts: Number(current.attempts) + 1 };
        });
        if (!row) break;
        if (row.cancelled) continue;
        let sent = false;
        try { sent = (await sendTransactionalSms(row.event_key || row.id, row.phone, row.message, { db, fallbackSendSms: sendSms }))?.sent === true; } catch (_) {}
        await db.prepare("UPDATE ride_sms_outbox SET status=?,sent_at=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL WHERE id=? AND lease_token=?")
          .run(sent ? "SENT" : row.attempts >= 8 ? "FAILED" : "PENDING", sent ? instant() : null, new Date(now() + Math.min(3600000, 60000 * 2 ** Math.min(row.attempts - 1, 6))).toISOString(), row.id, row.lease);
        result[sent ? "sent" : "failed"]++;
      }
      return result;
    })().finally(() => { smsFlush = null; });
    return smsFlush;
  }
  const encryptionKey = crypto
    .createHash("sha256")
    .update(secret + ":passo-ride-pins")
    .digest();
  function encrypt(pin) {
    const iv = crypto.randomBytes(12),
      cipher = crypto.createCipheriv("aes-256-gcm", encryptionKey, iv);
    const bytes = Buffer.concat([cipher.update(pin, "utf8"), cipher.final()]);
    return Buffer.concat([iv, cipher.getAuthTag(), bytes]).toString("base64");
  }
  function decrypt(value) {
    const b = Buffer.from(value, "base64"),
      decipher = crypto.createDecipheriv(
        "aes-256-gcm",
        encryptionKey,
        b.subarray(0, 12),
      );
    decipher.setAuthTag(b.subarray(12, 28));
    return Buffer.concat([
      decipher.update(b.subarray(28)),
      decipher.final(),
    ]).toString();
  }
  const hash = (pin) =>
    crypto.createHmac("sha256", secret).update(pin).digest("hex");
  const pinPair = () => {
    const pin = String(crypto.randomInt(1000, 10000));
    return { hash: hash(pin), cipher: encrypt(pin) };
  };
  async function activeUser(tx, userId, role, locked = false) {
    const user = await tx
      .prepare(
        "SELECT id,role,status FROM users WHERE id=?" +
          (locked ? lock(tx) : ""),
      )
      .get(userId);
    if (!user || user.status !== "ACTIVE" || user.role !== role)
      fail(401, "This account is no longer active.");
    if (
      (await tableExists(tx, "account_deletion_requests")) &&
      (await tx
        .prepare(
          "SELECT id FROM account_deletion_requests WHERE account_id=? AND role=?",
        )
        .get(userId, role))
    )
      fail(401, "This account has been closed.");
    return user;
  }
  async function audit(tx, actor, action, details) {
    await tx
      .prepare(
        "INSERT INTO audit_logs(action,userId,role,details) VALUES(?,?,?,?)",
      )
      .run(action, actor, "ADMIN", details);
  }
  async function catalog() {
    const row = await db
      .prepare("SELECT value FROM ride_settings WHERE id='tariff'")
      .get();
    const saved = row ? parse(row.value) : {};
    return {
      settings: { ...defaults, ...saved, sharedCommissionPolicy: "PER_TRIP" },
      tiers: saved.tiers || defaultTiers,
    };
  }
  async function updateSettings(actor, input) {
    const current = await catalog(),
      cfg = { ...current.settings };
    for (const key of Object.keys(defaults))
      if (input[key] !== undefined && key !== "sharedCommissionPolicy") {
        if (["enabled", "sharedEnabled"].includes(key)) {
          if (typeof input[key] !== "boolean")
            fail(400, "Use true or false for service activation.");
          cfg[key] = input[key];
        } else
          cfg[key] = number(
            input[key],
            key,
            key === "minimumSharedFareEtb" ? 0.01 : 0,
            ["pollingSeconds", "gpsUploadSeconds"].includes(key)
              ? 60
              : key.includes("Minutes")
                ? 1440
                : key === "notificationRadiusKm"
                  ? 200
                  : key === "arrivalRadiusMeters"
                    ? 2000
                    : 1000000,
          );
      }
    if (
      cfg.pollingSeconds < 3 ||
      !Number.isInteger(cfg.pollingSeconds) ||
      !Number.isInteger(cfg.gpsUploadSeconds) ||
      cfg.gpsUploadSeconds < 1 ||
      cfg.notificationRadiusKm < 0.1 ||
      cfg.arrivalRadiusMeters < 30 ||
      cfg.maxTripMinutes < 5 ||
      cfg.maximumSharedFareEtb < cfg.minimumSharedFareEtb
    )
      fail(400, "Choose valid timing, radius and shared fare bounds.");
    if (
      cfg.enabled &&
      !(cfg.baseFareEtb > 0 && cfg.perKmEtb > 0 && cfg.minimumFareEtb > 0)
    )
      fail(
        400,
        "Set the real base fare, distance fare and minimum fare before enabling taxi bookings.",
      );
    if (input.tiers !== undefined) {
      if (!Array.isArray(input.tiers) || input.tiers.length !== 3)
        fail(400, "Configure all three taxi tiers.");
      cfg.tiers = defaultTiers.map((t) => {
        const v = input.tiers.find((v) => v.id === t.id);
        if (!v) fail(400, "Configure all three taxi tiers.");
        return {
          ...t,
          multiplier: number(v.multiplier, "tier multiplier", 0.1, 10),
        };
      });
    } else cfg.tiers = current.tiers;
    await db.transaction(async (tx) => {
      await tx
        .prepare(
          "INSERT INTO ride_settings(id,value) VALUES('tariff',?) ON CONFLICT(id) DO UPDATE SET value=excluded.value",
        )
        .run(JSON.stringify(cfg));
      await audit(tx, actor, "RIDE_SETTINGS_UPDATED", JSON.stringify(cfg));
    });
    return catalog();
  }
  async function requireEnabled(shared = false) {
    const cfg = (await catalog()).settings;
    if (!(shared ? cfg.sharedEnabled : cfg.enabled))
      fail(
        409,
        shared
          ? "Shared Group Ride is not accepting bookings yet."
          : "Taxi bookings will open after Passo configures the fares.",
        "SERVICE_NOT_ENABLED",
      );
    return cfg;
  }
  async function calculate(input, shared = false) {
    const cfg = await requireEnabled(shared),
      r = routeInput(input),
      tier = String(input.tier || "ECONOMY");
    const item = (await catalog()).tiers.find((t) => t.id === tier);
    if (!item) fail(400, "Choose Economy, Comfort or Mini-Van.");
    const road = await geo.route(r);
    if (!(road.distanceKm > 0) || !(road.durationMinutes > 0))
      fail(503, "Road routing is unavailable. Please try again.");
    if (road.durationMinutes > cfg.maxTripMinutes)
      fail(400, "Choose a route within the configured taxi journey duration.");
    return {
      route: r,
      tariff: { ...cfg, multiplier: item.multiplier },
      estimate: {
        distanceKm: road.distanceKm,
        durationMinutes: road.durationMinutes,
        estimatedFareEtb: round(
          Math.max(
            cfg.minimumFareEtb,
            (cfg.baseFareEtb + road.distanceKm * cfg.perKmEtb) *
              item.multiplier,
          ),
        ),
        routeGeometry: road.geometry || null,
        routeGeometryQuality: road.geometryQuality || null,
        routeGeometryToleranceMeters: road.geometryToleranceMeters ?? null,
        tier,
      },
    };
  }
  async function estimate(input) {
    return { estimate: (await calculate(input)).estimate };
  }
  async function nearby(input) {
    const latitude = number(input.latitude, "latitude", -90, 90),
      longitude = number(input.longitude, "longitude", -180, 180),
      tier = input.tier ? String(input.tier) : null;
    if (tier && !defaultTiers.some((t) => t.id === tier))
      fail(400, "Choose a valid taxi tier.");
    const cfg = (await catalog()).settings;
    if (!cfg.enabled) return { vehicles: [], approximate: true };
    const latitudeRange = cfg.notificationRadiusKm / 110,
      longitudeRange =
        cfg.notificationRadiusKm /
        (110 * Math.max(0.1, Math.cos((latitude * Math.PI) / 180)));
    const activeIntercity = (await tableExists(db, "intercity_departures"))
      ? " AND NOT EXISTS (SELECT 1 FROM intercity_departures i WHERE (i.driver_id=a.driver_id OR i.vehicle_id=a.vehicle_id) AND i.status IN ('BOARDING','IN_TRANSIT'))"
      : "";
    const candidates = await db
      .prepare(
        `SELECT a.driver_id,a.vehicle_id,a.latitude,a.longitude,e.tier FROM ride_availability a
      JOIN ride_driver_eligibility d ON d.driver_id=a.driver_id AND d.approved=1
      JOIN ride_vehicle_eligibility e ON e.vehicle_id=a.vehicle_id AND e.driver_id=a.driver_id AND e.approved=1
      JOIN users u ON u.id=a.driver_id AND u.role='DRIVER' AND u.status='ACTIVE'
      JOIN vehicles v ON v.id=a.vehicle_id AND v.driverId=a.driver_id AND v.status IN ('IN_SERVICE','AVAILABLE')
      WHERE a.online=1 AND a.recorded_at>=? AND a.latitude BETWEEN ? AND ? AND a.longitude BETWEEN ? AND ?
      AND NOT EXISTS (SELECT 1 FROM ride_jobs j WHERE (j.driver_id=a.driver_id OR j.vehicle_id=a.vehicle_id) AND j.status IN ('ACCEPTED','AT_PICKUP','IN_PROGRESS'))
      AND NOT EXISTS (SELECT 1 FROM ride_shared_trips s WHERE (s.driver_id=a.driver_id OR s.vehicle_id=a.vehicle_id) AND s.status IN ('BOARDING','IN_PROGRESS'))
      AND NOT EXISTS (SELECT 1 FROM trips t WHERE (t.driverId=a.driver_id OR t.vehicleId=a.vehicle_id) AND t.status='IN_PROGRESS')
      AND NOT EXISTS (SELECT 1 FROM mobility_requests m WHERE (m.driver_id=a.driver_id OR m.vehicle_id=a.vehicle_id) AND m.status IN ('ASSIGNED','AT_PICKUP','LOADED','IN_TRANSIT','AT_DESTINATION','AWAITING_CONFIRMATION'))
      AND NOT EXISTS (SELECT 1 FROM mobility_trips g WHERE (g.driver_id=a.driver_id OR g.vehicle_id=a.vehicle_id) AND g.status='BOARDING')${activeIntercity}
      ORDER BY a.recorded_at DESC LIMIT 60`,
      )
      .all(
        new Date(now() - 120000).toISOString(),
        latitude - latitudeRange,
        latitude + latitudeRange,
        longitude - longitudeRange,
        longitude + longitudeRange,
      );
    const vehicles = [],
      cells = new Set();
    for (const candidate of candidates) {
      if (
        (tier && candidate.tier !== tier) ||
        km(
          latitude,
          longitude,
          Number(candidate.latitude),
          Number(candidate.longitude),
        ) > cfg.notificationRadiusKm
      )
        continue;
      if (
        !(await require("./operational-onboarding").driverEligible(
          candidate.driver_id,
          db,
        ))
      )
        continue;
      // These are deliberately approximate presence cells, never identifiable
      // driver dots. Exact tracking is disclosed only after assignment.
      const point = {
          latitude: Math.round(Number(candidate.latitude) * 500) / 500,
          longitude: Math.round(Number(candidate.longitude) * 500) / 500,
          tier: candidate.tier,
        },
        cell = point.latitude + ":" + point.longitude + ":" + point.tier;
      if (cells.has(cell)) continue;
      cells.add(cell);
      vehicles.push(point);
      if (vehicles.length === 12) break;
    }
    return { vehicles, approximate: true };
  }
  function expectedEstimate(input, data, checkCommission = false) {
    if (
      input.expectedFareEtb !== undefined &&
      money(input.expectedFareEtb) !== money(data.estimate.estimatedFareEtb)
    )
      fail(
        409,
        "The fare estimate changed. Review the updated estimate before requesting this ride.",
        "ESTIMATE_CHANGED",
      );
    if (
      checkCommission &&
      input.expectedCommissionEtb !== undefined &&
      money(input.expectedCommissionEtb) !==
        money(data.tariff.fixedCommissionEtb)
    )
      fail(
        409,
        "The Passo fee changed. Review the updated fee before starting.",
        "ESTIMATE_CHANGED",
      );
  }
  async function eligibility() {
    const rows = await db
      .prepare(
        "SELECT id,fullName,phone FROM users WHERE role='DRIVER' AND status='ACTIVE' ORDER BY fullName LIMIT 1000",
      )
      .all();
    return {
      drivers: await Promise.all(
        rows.map(async (u) => {
          const e = await db
            .prepare(
              "SELECT approved FROM ride_driver_eligibility WHERE driver_id=?",
            )
            .get(u.id);
          const fleet = await db
            .prepare("SELECT * FROM vehicles WHERE driverId=?")
            .all(u.id);
          return {
            id: u.id,
            fullName: u.fullName,
            phone: u.phone,
            approved: Number(e?.approved) === 1,
            vehicles: await Promise.all(
              fleet.map(async (v) => {
                const a = await db
                  .prepare(
                    "SELECT * FROM ride_vehicle_eligibility WHERE vehicle_id=? AND driver_id=?",
                  )
                  .get(v.id, u.id);
                return {
                  id: v.id,
                  model: v.model,
                  plateNumber: v.plateNumber,
                  capacityLimit: Number(v.capacityLimit),
                  tier: a?.tier || "ECONOMY",
                  approved: Number(a?.approved) === 1,
                };
              }),
            ),
          };
        }),
      ),
    };
  }
  async function updateEligibility(actor, key, input) {
    if (typeof input.approved !== "boolean" || !Array.isArray(input.vehicles))
      fail(400, "Provide driver approval and vehicle approvals.");
    await db.transaction(async (tx) => {
      const u = await tx
        .prepare(
          "SELECT id FROM users WHERE id=? AND role='DRIVER' AND status='ACTIVE'" +
            lock(tx),
        )
        .get(key);
      if (!u) fail(404, "Driver unavailable.");
      if (
        input.approved &&
        !(await require("./operational-onboarding").driverEligible(key, tx))
      )
        fail(
          409,
          "Complete the driver application and document approval first.",
        );
      await tx
        .prepare(
          "INSERT INTO ride_driver_eligibility(driver_id,approved,updated_at,actor_id) VALUES(?,?,?,?) ON CONFLICT(driver_id) DO UPDATE SET approved=excluded.approved,updated_at=excluded.updated_at,actor_id=excluded.actor_id",
        )
        .run(key, input.approved ? 1 : 0, instant(), actor);
      for (const item of input.vehicles) {
        const v = await tx
          .prepare("SELECT * FROM vehicles WHERE id=? AND driverId=?")
          .get(item.vehicleId, key);
        if (!v) fail(400, "Choose a vehicle assigned to this driver.");
        if (
          typeof item.approved !== "boolean" ||
          !defaultTiers.some((t) => t.id === item.tier)
        )
          fail(400, "Choose a vehicle tier and approval.");
        await tx
          .prepare(
            "INSERT INTO ride_vehicle_eligibility(vehicle_id,driver_id,approved,tier,updated_at,actor_id) VALUES(?,?,?,?,?,?) ON CONFLICT(vehicle_id) DO UPDATE SET driver_id=excluded.driver_id,approved=excluded.approved,tier=excluded.tier,updated_at=excluded.updated_at,actor_id=excluded.actor_id",
          )
          .run(v.id, key, item.approved ? 1 : 0, item.tier, instant(), actor);
      }
      if (!input.approved)
        await tx
          .prepare("UPDATE ride_availability SET online=0 WHERE driver_id=?")
          .run(key);
      await audit(tx, actor, "RIDE_ELIGIBILITY_UPDATED", key);
    });
    return { updated: true };
  }
  async function approvedVehicle(driverId, vehicleId, tx = db, locked = false) {
    const v = await tx
      .prepare(
        "SELECT * FROM vehicles WHERE id=? AND driverId=?" +
          (locked ? lock(tx) : ""),
      )
      .get(vehicleId, driverId);
    if (!v || !["IN_SERVICE", "AVAILABLE"].includes(v.status))
      fail(409, "Choose an available approved vehicle.");
    const user = await tx
      .prepare(
        "SELECT id FROM users WHERE id=? AND role='DRIVER' AND status='ACTIVE'" +
          (locked ? lock(tx) : ""),
      )
      .get(driverId);
    await activeUser(tx, driverId, "DRIVER");
    const e = await tx
        .prepare(
          "SELECT approved FROM ride_driver_eligibility WHERE driver_id=?",
        )
        .get(driverId),
      a = await tx
        .prepare(
          "SELECT * FROM ride_vehicle_eligibility WHERE vehicle_id=? AND driver_id=?",
        )
        .get(vehicleId, driverId);
    if (
      !user ||
      Number(e?.approved) !== 1 ||
      Number(a?.approved) !== 1 ||
      !(await require("./operational-onboarding").driverEligible(driverId, tx))
    )
      fail(
        403,
        "Passo must approve this driver and vehicle for taxi and shared rides.",
        "RIDE_APPROVAL_REQUIRED",
      );
    return { ...v, tier: a.tier };
  }
  async function busy(driverId, vehicleId, tx = db, except = "") {
    const ride = await tx
      .prepare(
        "SELECT id FROM ride_jobs WHERE id<>? AND (driver_id=? OR vehicle_id=?) AND status IN ('ACCEPTED','AT_PICKUP','IN_PROGRESS') LIMIT 1",
      )
      .get(except, driverId, vehicleId);
    const shared = await tx
      .prepare(
        "SELECT id FROM ride_shared_trips WHERE id<>? AND (driver_id=? OR vehicle_id=?) AND status IN ('BOARDING','IN_PROGRESS') LIMIT 1",
      )
      .get(except, driverId, vehicleId);
    const legacy = await tx
      .prepare(
        "SELECT id FROM trips WHERE (driverId=? OR vehicleId=?) AND status='IN_PROGRESS' LIMIT 1",
      )
      .get(driverId, vehicleId);
    const request = await tx
      .prepare(
        "SELECT id FROM mobility_requests WHERE (driver_id=? OR vehicle_id=?) AND status IN ('ASSIGNED','AT_PICKUP','LOADED','IN_TRANSIT','AT_DESTINATION','AWAITING_CONFIRMATION') LIMIT 1",
      )
      .get(driverId, vehicleId);
    const group = await tx
      .prepare(
        "SELECT id FROM mobility_trips WHERE (driver_id=? OR vehicle_id=?) AND status='BOARDING' LIMIT 1",
      )
      .get(driverId, vehicleId);
    const intercity = (await tableExists(tx, "intercity_departures"))
      ? await tx
          .prepare(
            "SELECT id FROM intercity_departures WHERE (driver_id=? OR vehicle_id=?) AND status IN ('BOARDING','IN_TRANSIT') LIMIT 1",
          )
          .get(driverId, vehicleId)
      : null;
    return Boolean(ride || shared || legacy || request || group || intercity);
  }
  async function scheduleSafe(
    driverId,
    vehicleId,
    minutes,
    tx = db,
    at = now(),
    except = "",
  ) {
    const cfg = (await catalog()).settings,
      end = at + (minutes * 2 + cfg.scheduleBufferMinutes) * 60000;
    const groups = await tx
      .prepare(
        "SELECT departure_at FROM mobility_trips WHERE (driver_id=? OR vehicle_id=?) AND status IN ('APPROVED','BOARDING') AND departure_at>=? AND departure_at<=? LIMIT 1",
      )
      .get(
        driverId,
        vehicleId,
        new Date(at - 3600000).toISOString(),
        new Date(end).toISOString(),
      );
    if (groups)
      fail(
        409,
        "This ride conflicts with a scheduled group departure.",
        "SCHEDULE_CONFLICT",
      );
    if (await tableExists(tx, "intercity_departures")) {
      const inter = await tx
        .prepare(
          "SELECT id FROM intercity_departures WHERE (driver_id=? OR vehicle_id=?) AND status IN ('SCHEDULED','BOARDING','IN_TRANSIT') AND departure_at>=? AND departure_at<=? LIMIT 1",
        )
        .get(
          driverId,
          vehicleId,
          new Date(at - 3600000).toISOString(),
          new Date(end).toISOString(),
        );
      if (inter)
        fail(
          409,
          "This ride conflicts with a scheduled intercity departure.",
          "SCHEDULE_CONFLICT",
        );
    }
    const assigned = await tx
      .prepare(
        "SELECT r.* FROM vehicles v JOIN routes r ON r.id=v.assignedRouteId WHERE (v.id=? OR v.driverId=?) AND r.active=TRUE AND COALESCE(r.routeType,'DAILY_COMMUTE')='DAILY_COMMUTE'",
      )
      .all(vehicleId, driverId);
    for (const duty of assigned) {
      // A journey can cross midnight, and drivers can have another assigned
      // vehicle. Include every local day touched by this proposed journey.
      for (
        let dayAt = at - 86400000;
        dayAt <= end + 86400000;
        dayAt += 86400000
      ) {
        const day = new Date(dayAt + 3 * 3600000).toISOString().slice(0, 10);
        for (const time of [duty.morningDeparture, duty.eveningDeparture]) {
          if (!/^\d{2}:\d{2}$/.test(time || "")) continue;
          const dep = Date.parse(day + "T" + time + ":00+03:00");
          if (dep >= at - cfg.scheduleBufferMinutes * 60000 && dep <= end)
            fail(
              409,
              "Leave enough time for the assigned daily commute departure.",
              "SCHEDULE_CONFLICT",
            );
        }
      }
    }
    const shared = await tx
      .prepare(
        "SELECT id,departure_at,estimate_json FROM ride_shared_trips WHERE id<>? AND (driver_id=? OR vehicle_id=?) AND status IN ('OPEN','BOARDING','IN_PROGRESS') AND departure_at>=? AND departure_at<=?",
      )
      .all(
        except,
        driverId,
        vehicleId,
        new Date(
          at - (cfg.maxTripMinutes * 2 + cfg.scheduleBufferMinutes) * 60000,
        ).toISOString(),
        new Date(end).toISOString(),
      );
    if (
      shared.some(
        (s) =>
          Date.parse(s.departure_at) +
            (Number(
              parse(s.estimate_json).durationMinutes || cfg.maxTripMinutes,
            ) *
              2 +
              cfg.scheduleBufferMinutes) *
              60000 >=
          at,
      )
    )
      fail(
        409,
        "This ride conflicts with your published shared trip.",
        "SCHEDULE_CONFLICT",
      );
  }
  async function wallet(driverId, tx = db, locked = false) {
    await tx
      .prepare(
        "INSERT INTO mobility_wallets(driver_id) VALUES(?) ON CONFLICT(driver_id) DO NOTHING",
      )
      .run(driverId);
    return tx
      .prepare(
        "SELECT * FROM mobility_wallets WHERE driver_id=?" +
          (locked ? lock(tx) : ""),
      )
      .get(driverId);
  }
  const walletView = (w) => ({
    balanceEtb: Number(w.balance_cents) / 100,
    heldEtb: Number(w.held_cents) / 100,
    availableEtb: (Number(w.balance_cents) - Number(w.held_cents)) / 100,
  });
  async function creditEvent(tx, driverId, key, kind, cents, rideId) {
    await tx
      .prepare(
        "INSERT INTO mobility_credit_ledger(id,driver_id,event_key,kind,amount_cents,request_id,created_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(event_key) DO NOTHING",
      )
      .run(id("rcl_"), driverId, key, kind, cents, rideId, instant());
  }
  async function hold(tx, driverId, key, cents, version = 0) {
    const w = await wallet(driverId, tx, true);
    if (Number(w.balance_cents) - Number(w.held_cents) < cents)
      fail(
        409,
        "Add enough verified Passo credit before accepting this ride.",
        "DRIVER_CREDIT_REQUIRED",
      );
    await tx
      .prepare(
        "UPDATE mobility_wallets SET held_cents=held_cents+? WHERE driver_id=?",
      )
      .run(cents, driverId);
    await creditEvent(
      tx,
      driverId,
      "ride:hold:" + key + (version ? ":" + version : ""),
      "HOLD",
      cents,
      key,
    );
  }
  async function settle(tx, row, table, capture) {
    if (row.settlement !== "HELD") return;
    const cents = Number(row.commission_cents);
    await wallet(row.driver_id, tx, true);
    if (capture)
      await tx
        .prepare(
          "UPDATE mobility_wallets SET balance_cents=balance_cents-?,held_cents=held_cents-? WHERE driver_id=?",
        )
        .run(cents, cents, row.driver_id);
    else
      await tx
        .prepare(
          "UPDATE mobility_wallets SET held_cents=held_cents-? WHERE driver_id=?",
        )
        .run(cents, row.driver_id);
    await creditEvent(
      tx,
      row.driver_id,
      "ride:" +
        (capture ? "capture:" : "release:") +
        row.id +
        (row.hold_version ? ":" + row.hold_version : ""),
      capture ? "COMMISSION" : "RELEASE",
      capture ? -cents : cents,
      row.id,
    );
    await tx
      .prepare("UPDATE " + table + " SET settlement=? WHERE id=?")
      .run(capture ? "CAPTURED" : "RELEASED", row.id);
  }
  function liveWaiting(row, until = now()) {
    if (!row.waiting_since || !row.waiting_verified_until) return 0;
    const cfg = parse(row.tariff_json),
      grace = Math.ceil(
        (row.waiting_kind === "PICKUP"
          ? cfg.pickupGraceMinutes
          : cfg.stopGraceMinutes) * 60,
      );
    return Math.max(
      0,
      Math.floor(
        (Math.min(
          until,
          Date.parse(row.waiting_verified_until),
          Date.parse(row.waiting_since) + (cfg.maxWaitingMinutes || 60) * 60000,
        ) -
          Date.parse(row.waiting_since)) /
          1000,
      ) - grace,
    );
  }
  function fare(row) {
    const t = parse(row.tariff_json);
    return round(
      Math.max(
        t.minimumFareEtb,
        (t.baseFareEtb +
          (Number(row.distance_m) / 1000) * t.perKmEtb +
          ((Number(row.waiting_seconds) + liveWaiting(row)) / 60) *
            t.waitingPerMinuteEtb) *
          t.multiplier,
      ),
    );
  }
  function waitingDetails(row) {
    const tariff = parse(row.tariff_json),
      multiplier = Number(tariff.multiplier || 1),
      rate = Number(tariff.waitingPerMinuteEtb || 0) * multiplier;
    const start = Date.parse(row.waiting_since),
      verified = Date.parse(row.waiting_verified_until),
      active = Boolean(
        row.waiting_kind && Number.isFinite(start) && Number.isFinite(verified),
      );
    const grace = Math.ceil(
      Number(
        (row.waiting_kind === "PICKUP"
          ? tariff.pickupGraceMinutes
          : tariff.stopGraceMinutes) || 0,
      ) * 60,
    );
    const elapsed = active
      ? Math.max(
          0,
          Math.floor(
            (Math.min(
              now(),
              verified,
              start + Number(tariff.maxWaitingMinutes || 60) * 60000,
            ) -
              start) /
              1000,
          ),
        )
      : 0;
    const remaining = active ? Math.max(0, grace - elapsed) : 0;
    let state = "NONE";
    if (Number(row.stop_requested) === 1) state = "AWAITING_PASSENGER_APPROVAL";
    else if (active) {
      if (now() > verified) state = "GPS_PAUSED";
      else if (now() >= start + Number(tariff.maxWaitingMinutes || 60) * 60000)
        state = "LIMIT_REACHED";
      else state = remaining > 0 ? "FREE_ALLOWANCE" : "BILLABLE_WAITING";
    }
    return {
      waitingFreeRemainingSeconds: remaining,
      waitingRateEtbPerMinute: round(rate),
      distancePaused:
        row.status === "IN_PROGRESS" && row.waiting_kind === "STOP",
      waitingState: state,
      waitingFareEtb: round(
        ((Number(row.waiting_seconds) + liveWaiting(row)) / 60) * rate,
      ),
      distanceFareEtb: round(
        (Number(row.distance_m) / 1000) *
          Number(tariff.perKmEtb || 0) *
          multiplier,
      ),
      serverTime: instant(),
    };
  }
  async function rideView(row, viewer, tx = db, pool = false) {
    const r = parse(row.route_json),
      e = parse(row.estimate_json);
    const result = {
      id: row.id,
      passengerId: pool ? null : row.passenger_id,
      driverId: row.driver_id,
      vehicleId: row.vehicle_id,
      status: row.status,
      source: row.source,
      tier: row.tier,
      ...r,
      distanceKm: e.distanceKm,
      durationMinutes: e.durationMinutes,
      estimatedFareEtb: e.estimatedFareEtb,
      routeGeometry: e.routeGeometry || null,
      routeGeometryQuality: e.routeGeometryQuality || null,
      routeGeometryToleranceMeters: e.routeGeometryToleranceMeters ?? null,
      fareEtb: row.started_at ? fare(row) : null,
      meterDistanceKm: round(Number(row.distance_m) / 1000),
      billableWaitingSeconds: Number(row.waiting_seconds) + liveWaiting(row),
      tripDurationSeconds: row.started_at
        ? Math.max(
            0,
            Math.floor(
              ((row.completed_at ? Date.parse(row.completed_at) : now()) -
                Date.parse(row.started_at)) /
                1000,
            ),
          )
        : 0,
      waiting: row.waiting_kind,
      stopWaitingRequested: Number(row.stop_requested) === 1,
      ...waitingDetails(row),
      lastSequence: Number(row.last_sequence),
      commissionEtb:
        row.status === "REQUESTED"
          ? Number(parse(row.tariff_json).fixedCommissionEtb)
          : Number(row.commission_cents) / 100,
      settlement: row.settlement,
      createdAt: row.created_at,
      startedAt: row.started_at,
      completedAt: row.completed_at,
      paymentMode: "DIRECT_TO_DRIVER",
      qualityFlags: row.quality_flags || "",
    };
    const participant =
      viewer &&
      (viewer.role === "ADMIN" ||
        viewer.id === row.passenger_id ||
        viewer.id === row.driver_id);
    if (participant && !pool) {
      if (row.passenger_id) {
        const p = await tx
          .prepare("SELECT fullName,phone FROM users WHERE id=?")
          .get(row.passenger_id);
        result.passengerName = p?.fullName || "";
        result.passengerPhone = p?.phone || "";
      }
      if (viewer.id === row.driver_id && row.source === "STREET") {
        result.passengerName = r.streetPassengerName || "Street passenger";
        result.passengerPhone = r.streetPassengerPhone || "";
      }
      if (row.driver_id) {
        const d = await tx
            .prepare("SELECT fullName,phone FROM users WHERE id=?")
            .get(row.driver_id),
          v = await tx
            .prepare("SELECT * FROM vehicles WHERE id=?")
            .get(row.vehicle_id);
        Object.assign(result, {
          driverName: d?.fullName || "",
          driverPhone: d?.phone || "",
          vehicleModel: v?.model || "",
          vehiclePlate: v?.plateNumber || "",
          vehicleType: v?.vehicleType || "",
        });
      }
      if (
        viewer.id === row.passenger_id &&
        row.pin_cipher &&
        ["REQUESTED", ...ACTIVE].includes(row.status)
      ) {
        result.boardingPin = decrypt(row.pin_cipher);
        result.boardingQrToken = result.boardingPin;
      }
      if (ACTIVE.includes(row.status) && row.driver_id) {
        const location = await tx
          .prepare("SELECT * FROM ride_availability WHERE driver_id=?")
          .get(row.driver_id);
        if (location?.recorded_at)
          result.location = {
            latitude: Number(location.latitude),
            longitude: Number(location.longitude),
            accuracyMeters: Number(location.accuracy_meters),
            timestamp: location.recorded_at,
            stale: now() - Date.parse(location.recorded_at) > 120000,
          };
      }
    }
    return result;
  }
  async function getRide(key, viewer, tx = db, locked = false) {
    const row = await tx
      .prepare("SELECT * FROM ride_jobs WHERE id=?" + (locked ? lock(tx) : ""))
      .get(key);
    if (
      !row ||
      !viewer ||
      !(
        viewer.role === "ADMIN" ||
        viewer.id === row.passenger_id ||
        viewer.id === row.driver_id
      )
    )
      fail(404, "Ride unavailable.");
    return row;
  }
  async function expireRequests() {
    await db
      .prepare(
        "UPDATE ride_jobs SET status='CANCELLED',pin_hash=NULL,pin_cipher=NULL,cancel_reason='No driver accepted before request expiry',completed_at=? WHERE status='REQUESTED' AND created_at<?",
      )
      .run(instant(), new Date(now() - 15 * 60000).toISOString());
  }
  async function createRequest(user, input) {
    const key = clean(input.idempotencyKey, "request reference", 100),
      old = await db
        .prepare(
          "SELECT * FROM ride_jobs WHERE idempotency_owner=? AND idempotency_key=?",
        )
        .get(user.id, key);
    if (old) return { ride: await rideView(old, user) };
    const data = await calculate(input);
    expectedEstimate(input, data);
    await expireRequests();
    let row;
    await db.transaction(async (tx) => {
      await activeUser(tx, user.id, "PASSENGER", true);
      const prior = await tx
        .prepare(
          "SELECT * FROM ride_jobs WHERE idempotency_owner=? AND idempotency_key=?",
        )
        .get(user.id, key);
      if (prior) {
        row = prior;
        return;
      }
      if (
        await tx
          .prepare(
            "SELECT id FROM ride_jobs WHERE passenger_id=? AND status IN ('REQUESTED','ACCEPTED','AT_PICKUP','IN_PROGRESS') LIMIT 1",
          )
          .get(user.id)
      )
        fail(409, "Finish or cancel your current ride request first.");
      const pin = pinPair(),
        rid = id("ride_");
      await tx
        .prepare(
          "INSERT INTO ride_jobs(id,passenger_id,status,source,tier,route_json,tariff_json,estimate_json,pin_hash,pin_cipher,idempotency_owner,idempotency_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        )
        .run(
          rid,
          user.id,
          "REQUESTED",
          "APP",
          data.estimate.tier,
          JSON.stringify(data.route),
          JSON.stringify(data.tariff),
          JSON.stringify(data.estimate),
          pin.hash,
          pin.cipher,
          user.id,
          key,
          instant(),
        );
      row = await tx.prepare("SELECT * FROM ride_jobs WHERE id=?").get(rid);
    });
    return { ride: await rideView(row, user) };
  }
  function position(input, { fresh = true } = {}) {
    if (input.mock === true || input.isMock === true)
      fail(400, "Use an authentic device location.");
    const latitude = number(input.latitude, "latitude", -90, 90),
      longitude = number(input.longitude, "longitude", -180, 180),
      accuracyMeters = number(input.accuracyMeters, "GPS accuracy", 0.1, 1000),
      timestamp = Date.parse(input.timestamp);
    if (
      !Number.isFinite(timestamp) ||
      timestamp > now() + 30000 ||
      (fresh && now() - timestamp > 120000)
    )
      fail(400, "Use a fresh device GPS position.");
    return {
      latitude,
      longitude,
      accuracyMeters,
      timestamp: new Date(timestamp).toISOString(),
    };
  }
  async function saveAvailability(tx, driverId, vehicleId, online, p) {
    if (p) {
      const previous = await tx
        .prepare("SELECT recorded_at FROM ride_availability WHERE driver_id=?")
        .get(driverId);
      if (
        previous?.recorded_at &&
        Date.parse(previous.recorded_at) > Date.parse(p.timestamp)
      )
        p = null;
    }
    await tx
      .prepare(
        "INSERT INTO ride_availability(driver_id,vehicle_id,online,latitude,longitude,accuracy_meters,recorded_at,updated_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(driver_id) DO UPDATE SET vehicle_id=excluded.vehicle_id,online=excluded.online,latitude=COALESCE(excluded.latitude,ride_availability.latitude),longitude=COALESCE(excluded.longitude,ride_availability.longitude),accuracy_meters=COALESCE(excluded.accuracy_meters,ride_availability.accuracy_meters),recorded_at=COALESCE(excluded.recorded_at,ride_availability.recorded_at),updated_at=excluded.updated_at",
      )
      .run(
        driverId,
        vehicleId,
        online ? 1 : 0,
        p?.latitude ?? null,
        p?.longitude ?? null,
        p?.accuracyMeters ?? null,
        p?.timestamp ?? null,
        instant(),
      );
  }
  async function availability(user, input) {
    if (typeof input.online !== "boolean")
      fail(400, "Choose online or offline.");
    const cfg = (await catalog()).settings;
    if (input.online && !cfg.enabled && !cfg.sharedEnabled)
      fail(
        409,
        "Taxi and shared ride bookings are not enabled yet.",
        "SERVICE_NOT_ENABLED",
      );
    const p = input.latitude == null ? null : position(input);
    if (input.online && !p)
      fail(400, "Turn on GPS to receive nearby ride requests.");
    await db.transaction(async (tx) => {
      if (input.online) {
        await approvedVehicle(user.id, input.vehicleId, tx, true);
        if (await busy(user.id, input.vehicleId, tx))
          fail(409, "Finish your current job before going online.");
      } else await activeUser(tx, user.id, "DRIVER", true);
      await saveAvailability(
        tx,
        user.id,
        input.vehicleId || null,
        input.online,
        p,
      );
    });
    return dashboard(user);
  }
  async function dashboard(user) {
    await expireRequests();
    const cfg = (await catalog()).settings;
    const fleet = await db
        .prepare(
          "SELECT * FROM vehicles WHERE driverId=? AND status IN ('IN_SERVICE','AVAILABLE')",
        )
        .all(user.id),
      vehicles = [];
    for (const v of fleet)
      try {
        const a = await approvedVehicle(user.id, v.id);
        vehicles.push({
          id: v.id,
          model: v.model,
          plateNumber: v.plateNumber,
          capacityLimit: Number(v.capacityLimit),
          tier: a.tier,
        });
      } catch (error) {
        if (!error.status) throw error;
      }
    const a = await db
      .prepare("SELECT * FROM ride_availability WHERE driver_id=?")
      .get(user.id);
    const active = await db
      .prepare(
        "SELECT * FROM ride_jobs WHERE driver_id=? AND status IN ('ACCEPTED','AT_PICKUP','IN_PROGRESS') ORDER BY created_at DESC LIMIT 1",
      )
      .get(user.id);
    const available =
      (cfg.enabled || cfg.sharedEnabled) &&
      vehicles.some((v) => v.id === a?.vehicle_id) &&
      Number(a?.online) === 1 &&
      a?.recorded_at &&
      now() - Date.parse(a.recorded_at) <= 120000;
    const currentBusy = await busy(
      user.id,
      a?.vehicle_id || vehicles[0]?.id || "",
    );
    const calls = [];
    if (cfg.enabled && available && !currentBusy) {
      const rows = await db
        .prepare(
          "SELECT * FROM ride_jobs WHERE status='REQUESTED' ORDER BY created_at LIMIT 100",
        )
        .all();
      for (const r of rows) {
        const path = parse(r.route_json),
          distance = km(
            Number(a.latitude),
            Number(a.longitude),
            path.originLatitude,
            path.originLongitude,
          );
        if (
          distance <= cfg.notificationRadiusKm &&
          vehicles.some((v) => v.id === a.vehicle_id && v.tier === r.tier)
        ) {
          calls.push({
            ...(await rideView(r, user, db, true)),
            pickupDistanceKm: round(distance),
          });
        }
      }
    }
    const sharedTrips = await db
        .prepare(
          "SELECT * FROM ride_shared_trips WHERE driver_id=? ORDER BY departure_at DESC LIMIT 50",
        )
        .all(user.id),
      requests =
        cfg.sharedEnabled && available && !currentBusy
          ? await db
              .prepare(
                "SELECT * FROM ride_shared_trips WHERE status='REQUESTED' AND departure_at>? ORDER BY departure_at LIMIT 50",
              )
              .all(instant())
          : [];
    return {
      eligible: vehicles.length > 0,
      online: available,
      vehicles,
      wallet: walletView(await wallet(user.id)),
      calls,
      ride: active ? await rideView(active, user) : null,
      sharedTrips: await Promise.all(
        sharedTrips.map((r) => sharedView(r, user)),
      ),
      sharedRequests: await Promise.all(
        requests
          .filter((r) => {
            const path = parse(r.route_json);
            return (
              vehicles.some((v) => v.capacityLimit >= Number(r.seats)) &&
              km(
                Number(a.latitude),
                Number(a.longitude),
                path.originLatitude,
                path.originLongitude,
              ) <= cfg.notificationRadiusKm
            );
          })
          .map((r) => sharedView(r, user)),
      ),
      busy: currentBusy,
    };
  }
  async function accept(user, key, input) {
    await requireEnabled();
    await expireRequests();
    await db.transaction(async (tx) => {
      const v = await approvedVehicle(user.id, input.vehicleId, tx, true);
      const r = await tx
        .prepare("SELECT * FROM ride_jobs WHERE id=?" + lock(tx))
        .get(key);
      if (!r) fail(404, "Ride unavailable.");
      if (r.driver_id === user.id && ACTIVE.includes(r.status)) return;
      if (r.status !== "REQUESTED")
        fail(409, "Another driver already accepted this request.");
      if (v.tier !== r.tier) fail(409, "Use the requested taxi tier.");
      if (await busy(user.id, v.id, tx))
        fail(409, "Finish your current transport job first.", "DRIVER_BUSY");
      const a = await tx
          .prepare("SELECT * FROM ride_availability WHERE driver_id=?")
          .get(user.id),
        route = parse(r.route_json),
        cfg = parse(r.tariff_json);
      if (
        Number(a?.online) !== 1 ||
        a.vehicle_id !== v.id ||
        !a.recorded_at ||
        now() - Date.parse(a.recorded_at) > 120000 ||
        km(
          Number(a.latitude),
          Number(a.longitude),
          route.originLatitude,
          route.originLongitude,
        ) > cfg.notificationRadiusKm
      )
        fail(409, "Go online near the pickup with a fresh GPS position.");
      await scheduleSafe(
        user.id,
        v.id,
        parse(r.estimate_json).durationMinutes,
        tx,
      );
      const cents = money(cfg.fixedCommissionEtb);
      await hold(tx, user.id, r.id, cents);
      await tx
        .prepare(
          "UPDATE ride_jobs SET status='ACCEPTED',driver_id=?,vehicle_id=?,commission_cents=?,settlement='HELD',accepted_at=? WHERE id=?",
        )
        .run(user.id, v.id, cents, instant(), r.id);
      await tx
        .prepare("UPDATE ride_availability SET online=0 WHERE driver_id=?")
        .run(user.id);
    });
    return { ride: await rideView(await getRide(key, user), user) };
  }
  async function street(user, input) {
    if (input.passengerConsent !== true)
      fail(400, "Confirm passenger agreement before starting the meter.");
    const key = clean(input.idempotencyKey, "request reference", 100),
      old = await db
        .prepare(
          "SELECT * FROM ride_jobs WHERE idempotency_owner=? AND idempotency_key=?",
        )
        .get(user.id, key);
    if (old) return { ride: await rideView(old, user) };
    const data = await calculate(input);
    expectedEstimate(input, data, true);
    const streetPassengerName = clean(input.passengerName || "Street passenger", "passenger name", 120);
    const streetPassengerPhone = normalizePhone(input.passengerPhone);
    if (!streetPassengerPhone) fail(400, "Enter a valid passenger phone number.");
    data.route.streetPassengerName = streetPassengerName;
    data.route.streetPassengerPhone = streetPassengerPhone;
    const initialPosition =
      input.latitude == null
        ? await db
            .prepare("SELECT * FROM ride_availability WHERE driver_id=?")
            .get(user.id)
        : null;
    const p =
      input.latitude == null
        ? position({
            latitude: initialPosition?.latitude,
            longitude: initialPosition?.longitude,
            accuracyMeters: initialPosition?.accuracy_meters,
            timestamp: initialPosition?.recorded_at,
          })
        : position(input);
    geofence(
      {
        route_json: JSON.stringify(data.route),
        tariff_json: JSON.stringify(data.tariff),
      },
      p,
      "origin",
    );
    let rid;
    await db.transaction(async (tx) => {
      const v = await approvedVehicle(user.id, input.vehicleId, tx, true);
      const prior = await tx
        .prepare(
          "SELECT * FROM ride_jobs WHERE idempotency_owner=? AND idempotency_key=?",
        )
        .get(user.id, key);
      if (prior) {
        rid = prior.id;
        return;
      }
      if (v.tier !== data.estimate.tier)
        fail(409, "Use the approved vehicle tier.");
      if (await busy(user.id, v.id, tx))
        fail(409, "Finish your current job before starting a street pickup.");
      await scheduleSafe(user.id, v.id, data.estimate.durationMinutes, tx);
      rid = id("ride_");
      const cents = money(data.tariff.fixedCommissionEtb);
      await hold(tx, user.id, rid, cents);
      await tx
        .prepare(
          "INSERT INTO ride_jobs(id,driver_id,vehicle_id,status,source,tier,route_json,tariff_json,estimate_json,idempotency_owner,idempotency_key,commission_cents,settlement,created_at,accepted_at,started_at,last_latitude,last_longitude,last_accuracy,last_point_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        )
        .run(
          rid,
          user.id,
          v.id,
          "IN_PROGRESS",
          "STREET",
          data.estimate.tier,
          JSON.stringify(data.route),
          JSON.stringify(data.tariff),
          JSON.stringify(data.estimate),
          user.id,
          key,
          cents,
          "HELD",
          instant(),
          instant(),
          instant(),
          p.latitude,
          p.longitude,
          p.accuracyMeters,
          instant(),
        );
      await queueTaxiSms(tx, { id: rid, route_json: JSON.stringify(data.route) }, "start");
      await saveAvailability(tx, user.id, v.id, false, p);
    });
    void flushRideSms();
    return { ride: await rideView(await getRide(rid, user), user) };
  }
  async function owned(key, user, tx = db, locked = false) {
    const r = await getRide(key, user, tx, locked);
    if (r.driver_id !== user.id) fail(404, "Assigned ride unavailable.");
    return r;
  }
  function geofence(r, p, target) {
    const route = parse(r.route_json),
      cfg = parse(r.tariff_json);
    if (
      p.accuracyMeters > 100 ||
      km(
        p.latitude,
        p.longitude,
        route[target + "Latitude"],
        route[target + "Longitude"],
      ) *
        1000 >
        cfg.arrivalRadiusMeters
    )
      fail(
        409,
        "Reach the " +
          (target === "origin" ? "pickup" : "destination") +
          " before confirming arrival.",
        "OUTSIDE_ARRIVAL_RADIUS",
      );
  }
  async function arrive(user, key, input) {
    const p = position(input);
    await db.transaction(async (tx) => {
      const r = await owned(key, user, tx, true);
      if (r.status === "AT_PICKUP") return;
      if (r.status !== "ACCEPTED")
        fail(409, "Accept this ride before marking arrival.");
      geofence(r, p, "origin");
      await tx
        .prepare(
          "UPDATE ride_jobs SET status='AT_PICKUP',arrived_at=?,waiting_since=?,waiting_kind='PICKUP',waiting_verified_until=?,waiting_latitude=?,waiting_longitude=? WHERE id=?",
        )
        .run(
          instant(),
          instant(),
          new Date(Date.parse(p.timestamp) + 120000).toISOString(),
          p.latitude,
          p.longitude,
          key,
        );
      await saveAvailability(tx, user.id, r.vehicle_id, false, p);
    });
    void flushRideSms();
    return { ride: await rideView(await getRide(key, user), user) };
  }
  async function verifyPin(tx, key, actor, stored, pin) {
    const attempt = await tx
      .prepare("SELECT * FROM ride_pin_attempts WHERE ride_id=? AND actor_id=?")
      .get(key, actor);
    if (attempt?.blocked_until && Date.parse(attempt.blocked_until) > now())
      fail(429, "Too many incorrect boarding PIN attempts. Try again later.");
    const valid =
      /^\d{4}$/.test(String(pin || "")) &&
      crypto.timingSafeEqual(
        Buffer.from(hash(String(pin))),
        Buffer.from(stored),
      );
    if (!valid) {
      const count = Number(attempt?.attempts || 0) + 1;
      await tx
        .prepare(
          "INSERT INTO ride_pin_attempts(ride_id,actor_id,attempts,blocked_until) VALUES(?,?,?,?) ON CONFLICT(ride_id,actor_id) DO UPDATE SET attempts=excluded.attempts,blocked_until=excluded.blocked_until",
        )
        .run(
          key,
          actor,
          count,
          count >= 5 ? new Date(now() + 15 * 60000).toISOString() : null,
        );
      return false;
    }
    await tx
      .prepare("DELETE FROM ride_pin_attempts WHERE ride_id=? AND actor_id=?")
      .run(key, actor);
    return true;
  }
  async function start(user, key, input) {
    let invalid = false;
    await db.transaction(async (tx) => {
      const r = await owned(key, user, tx, true);
      if (r.status === "IN_PROGRESS") return;
      if (r.status !== "AT_PICKUP") fail(409, "Mark arrival at pickup first.");
      if (!(await verifyPin(tx, key, user.id, r.pin_hash, input.qrToken || input.pin))) {
        invalid = true;
        return;
      }
      const point = await tx
        .prepare("SELECT * FROM ride_availability WHERE driver_id=?")
        .get(user.id);
      const p = position({
        latitude: point?.latitude,
        longitude: point?.longitude,
        accuracyMeters: point?.accuracy_meters,
        timestamp: point?.recorded_at,
      });
      geofence(r, p, "origin");
      await tx
        .prepare(
          "UPDATE ride_jobs SET status='IN_PROGRESS',started_at=?,waiting_seconds=waiting_seconds+?,waiting_since=NULL,waiting_kind=NULL,last_latitude=?,last_longitude=?,last_accuracy=?,last_point_at=? WHERE id=?",
        )
        .run(
          instant(),
          liveWaiting(r),
          p.latitude,
          p.longitude,
          p.accuracyMeters,
          instant(),
          key,
        );
      await queueTaxiSms(tx, r, "start");
    });
    void flushRideSms();
    if (invalid)
      fail(
        400,
        "The passenger boarding PIN is incorrect.",
        "INVALID_BOARDING_PIN",
      );
    return { ride: await rideView(await getRide(key, user), user) };
  }
  async function samples(user, key, input) {
    if (
      !Array.isArray(input.points) ||
      !input.points.length ||
      input.points.length > 120
    )
      fail(400, "Send between one and 120 GPS samples.");
    const points = input.points
      .map((v) => ({
        ...position(v, { fresh: false }),
        sequence: integer(v.sequence, "sample sequence", 1, 2147483647),
      }))
      .sort((a, b) => a.sequence - b.sequence);
    await db.transaction(async (tx) => {
      let r = await owned(key, user, tx, true);
      let trackingPoint = null;
      if (!ACTIVE.includes(r.status)) fail(409, "This ride is not active.");
      for (const p of points) {
        if (p.sequence <= Number(r.last_sequence)) continue;
        if (Date.parse(p.timestamp) < Date.parse(r.started_at || r.accepted_at))
          continue;
        const dt = r.last_point_at
          ? (Date.parse(p.timestamp) - Date.parse(r.last_point_at)) / 1000
          : 0;
        const displacement =
          r.last_latitude == null
            ? 0
            : km(
                Number(r.last_latitude),
                Number(r.last_longitude),
                p.latitude,
                p.longitude,
              ) * 1000;
        const timingValid = !r.last_point_at || dt >= 0;
        const plausible =
          !r.last_point_at ||
          displacement <=
            Math.max(
              5,
              (p.accuracyMeters + Number(r.last_accuracy || p.accuracyMeters)) /
                2,
            ) ||
          (dt > 0 && displacement / dt <= 45);
        const waitingSampleEligible =
          !r.waiting_since ||
          Date.parse(p.timestamp) >= Date.parse(r.waiting_since);
        if (
          r.waiting_kind &&
          p.accuracyMeters <= 50 &&
          timingValid &&
          plausible &&
          waitingSampleEligible
        ) {
          const moved =
            km(
              Number(r.waiting_latitude),
              Number(r.waiting_longitude),
              p.latitude,
              p.longitude,
            ) *
              1000 >
            Math.max(20, p.accuracyMeters * 1.5);
          if (moved) {
            await tx
              .prepare(
                "UPDATE ride_jobs SET waiting_seconds=waiting_seconds+?,waiting_since=NULL,waiting_kind=NULL,waiting_verified_until=NULL,stop_requested=0 WHERE id=?",
              )
              .run(
                liveWaiting(r, Date.parse(r.last_point_at || r.waiting_since)),
                key,
              );
          } else if (
            !r.waiting_verified_until ||
            Date.parse(p.timestamp) > Date.parse(r.waiting_verified_until)
          ) {
            // A disconnected interval cannot become billable just because GPS
            // resumed later. Settle covered time and restart the free allowance.
            await tx
              .prepare(
                "UPDATE ride_jobs SET waiting_seconds=waiting_seconds+?,waiting_since=?,waiting_verified_until=? WHERE id=?",
              )
              .run(
                liveWaiting(r),
                p.timestamp,
                new Date(Date.parse(p.timestamp) + 120000).toISOString(),
                key,
              );
          } else {
            await tx
              .prepare(
                "UPDATE ride_jobs SET waiting_verified_until=? WHERE id=?",
              )
              .run(
                new Date(Date.parse(p.timestamp) + 120000).toISOString(),
                key,
              );
          }
          r = await tx.prepare("SELECT * FROM ride_jobs WHERE id=?").get(key);
        }
        let accepted = 0,
          distance = 0,
          reason = "TRACKING_ONLY";
        if (r.status === "IN_PROGRESS" && p.accuracyMeters <= 50) {
          reason = "FIRST_POINT";
          if (r.last_point_at && dt > 0) {
            if (dt > 300) reason = "GPS_GAP";
            else if (displacement / dt > 45) reason = "GPS_JUMP";
            else if (
              displacement <=
              Math.max(
                5,
                (p.accuracyMeters +
                  Number(r.last_accuracy || p.accuracyMeters)) /
                  2,
              )
            )
              reason = "GPS_JITTER";
            else {
              distance = displacement;
              accepted = 1;
              reason = "DISTANCE";
            }
          }
        } else if (p.accuracyMeters > 50) reason = "LOW_ACCURACY";
        if (
          r.status !== "IN_PROGRESS" &&
          r.last_point_at &&
          dt > 0 &&
          displacement / dt > 45
        )
          reason = "GPS_JUMP";
        if (
          r.status === "IN_PROGRESS" &&
          r.waiting_kind === "STOP" &&
          p.accuracyMeters <= 50 &&
          timingValid &&
          plausible &&
          waitingSampleEligible &&
          reason !== "GPS_GAP"
        ) {
          // Approved stationary stops pause distance. Accuracy-sized movement
          // inside the stop radius is GPS drift, not a travelled segment.
          distance = 0;
          accepted = 0;
          reason = "APPROVED_STOP";
        }
        await tx
          .prepare(
            "INSERT INTO ride_samples(ride_id,sequence,latitude,longitude,accuracy_meters,recorded_at,accepted,reason,distance_m) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(ride_id,sequence) DO NOTHING",
          )
          .run(
            key,
            p.sequence,
            p.latitude,
            p.longitude,
            p.accuracyMeters,
            p.timestamp,
            accepted,
            reason,
            distance,
          );
        const updateAnchor =
          p.accuracyMeters <= 50 &&
          reason !== "GPS_JUMP" &&
          reason !== "GPS_JITTER" &&
          reason !== "APPROVED_STOP" &&
          (!r.last_point_at || dt > 0);
        await tx
          .prepare(
            "UPDATE ride_jobs SET last_sequence=?,distance_m=distance_m+?,quality_flags=? WHERE id=?",
          )
          .run(
            p.sequence,
            distance,
            reason === "GPS_GAP" ? "GPS_GAP" : r.quality_flags || "",
            key,
          );
        if (updateAnchor)
          await tx
            .prepare(
              "UPDATE ride_jobs SET last_latitude=?,last_longitude=?,last_accuracy=?,last_point_at=? WHERE id=?",
            )
            .run(p.latitude, p.longitude, p.accuracyMeters, p.timestamp, key);
        else if (["GPS_JITTER", "APPROVED_STOP"].includes(reason) && dt > 0)
          await tx
            .prepare(
              "UPDATE ride_jobs SET last_point_at=?,last_accuracy=? WHERE id=?",
            )
            .run(p.timestamp, p.accuracyMeters, key);
        r = await tx.prepare("SELECT * FROM ride_jobs WHERE id=?").get(key);
        if (
          reason !== "GPS_JUMP" &&
          timingValid &&
          now() - Date.parse(p.timestamp) <= 120000
        )
          if (
            !trackingPoint ||
            Date.parse(p.timestamp) >= Date.parse(trackingPoint.timestamp)
          )
            trackingPoint = p;
      }
      if (trackingPoint)
        await saveAvailability(tx, user.id, r.vehicle_id, false, trackingPoint);
    });
    return { ride: await rideView(await getRide(key, user), user) };
  }
  async function wait(user, key, input) {
    await db.transaction(async (tx) => {
      const r = await owned(key, user, tx, true);
      if (r.status !== "IN_PROGRESS")
        fail(409, "Stop waiting is only available during a trip.");
      if (input.action === "REQUEST_STOP") {
        if (r.source === "STREET")
          fail(
            409,
            "Street pickup stop waiting cannot start without passenger-app confirmation.",
          );
        if (r.waiting_kind) return;
        await tx
          .prepare("UPDATE ride_jobs SET stop_requested=1 WHERE id=?")
          .run(key);
      } else if (input.action === "END_STOP") {
        await finishWaiting(tx, r);
      } else fail(400, "Choose request stop or end stop.");
    });
    return { ride: await rideView(await getRide(key, user), user) };
  }
  async function finishWaiting(tx, row) {
    await tx
      .prepare(
        "UPDATE ride_jobs SET waiting_seconds=waiting_seconds+?,waiting_since=NULL,waiting_kind=NULL,waiting_verified_until=NULL,stop_requested=0 WHERE id=?",
      )
      .run(liveWaiting(row), row.id);
    if (row.waiting_kind !== "STOP") return;
    const p = await tx
      .prepare("SELECT * FROM ride_availability WHERE driver_id=?")
      .get(row.driver_id);
    const valid =
      p?.recorded_at &&
      now() - Date.parse(p.recorded_at) <= 120000 &&
      Number(p.accuracy_meters) <= 50 &&
      km(
        Number(p.latitude),
        Number(p.longitude),
        Number(row.waiting_latitude),
        Number(row.waiting_longitude),
      ) *
        1000 <=
        Math.max(20, Number(p.accuracy_meters) * 1.5);
    // Known stationary fixes must not reappear as a distance charge on resume.
    await tx
      .prepare(
        "UPDATE ride_jobs SET last_latitude=?,last_longitude=?,last_accuracy=?,last_point_at=? WHERE id=?",
      )
      .run(
        valid ? Number(p.latitude) : row.waiting_latitude,
        valid ? Number(p.longitude) : row.waiting_longitude,
        valid ? Number(p.accuracy_meters) : Number(row.last_accuracy || 50),
        instant(),
        row.id,
      );
  }
  async function stopWait(user, key, input) {
    if (typeof input.approved !== "boolean")
      fail(400, "Confirm or reject the stop waiting charge.");
    await db.transaction(async (tx) => {
      const r = await getRide(key, user, tx, true);
      if (
        r.passenger_id === user.id &&
        r.status === "IN_PROGRESS" &&
        !input.approved &&
        r.waiting_kind === "STOP"
      ) {
        await finishWaiting(tx, r);
        return;
      }
      if (
        r.passenger_id !== user.id ||
        r.status !== "IN_PROGRESS" ||
        !r.stop_requested
      )
        fail(409, "There is no passenger stop waiting request.");
      const last = await tx
        .prepare("SELECT * FROM ride_availability WHERE driver_id=?")
        .get(r.driver_id);
      if (
        input.approved &&
        (!last?.recorded_at ||
          now() - Date.parse(last.recorded_at) > 120000 ||
          Number(last.accuracy_meters) > 50)
      )
        fail(
          409,
          "Wait for a fresh driver GPS position before confirming billable waiting.",
        );
      await tx
        .prepare(
          "UPDATE ride_jobs SET stop_requested=0,waiting_since=?,waiting_kind=?,waiting_verified_until=?,waiting_latitude=?,waiting_longitude=? WHERE id=?",
        )
        .run(
          input.approved ? instant() : null,
          input.approved ? "STOP" : null,
          input.approved
            ? new Date(Date.parse(last.recorded_at) + 120000).toISOString()
            : null,
          input.approved ? Number(last.latitude) : null,
          input.approved ? Number(last.longitude) : null,
          key,
        );
    });
    return { ride: await rideView(await getRide(key, user), user) };
  }
  async function complete(user, key, input) {
    const p = position(input);
    await db.transaction(async (tx) => {
      const r = await owned(key, user, tx, true);
      if (r.status === "COMPLETED") return;
      if (r.status !== "IN_PROGRESS")
        fail(409, "Start the trip before completing it.");
      geofence(r, p, "destination");
      if (
        input.finalSequence !== undefined &&
        Number(r.last_sequence) <
          integer(input.finalSequence, "final GPS sequence", 1, 2147483647)
      )
        fail(
          409,
          "Upload all recorded GPS samples before completing the meter.",
          "GPS_UPLOAD_REQUIRED",
        );
      if (
        r.quality_flags === "GPS_GAP" ||
        !r.last_point_at ||
        now() - Date.parse(r.last_point_at) > 120000 ||
        Number(r.distance_m) < 50 ||
        km(
          Number(r.last_latitude),
          Number(r.last_longitude),
          p.latitude,
          p.longitude,
        ) *
          1000 >
          100
      )
        fail(
          409,
          "A gap in GPS samples needs Passo review before settling this meter.",
          "METER_REVIEW_REQUIRED",
        );
      await tx
        .prepare(
          "UPDATE ride_jobs SET status='COMPLETED',pin_hash=NULL,pin_cipher=NULL,waiting_seconds=waiting_seconds+?,waiting_since=NULL,waiting_kind=NULL,completed_at=? WHERE id=?",
        )
        .run(liveWaiting(r), instant(), key);
      await queueTaxiSms(tx, r, "complete");
      await settle(tx, r, "ride_jobs", true);
      await saveAvailability(tx, user.id, r.vehicle_id, true, p);
    });
    void flushRideSms();
    return { ride: await rideView(await getRide(key, user), user) };
  }
  async function cancel(user, key, input = {}) {
    await db.transaction(async (tx) => {
      const r = await getRide(key, user, tx, true);
      if (r.status === "CANCELLED") return;
      if (!["REQUESTED", "ACCEPTED", "AT_PICKUP"].includes(r.status))
        fail(
          409,
          "Contact Passo support to resolve a trip already in progress.",
        );
      await settle(tx, r, "ride_jobs", false);
      await tx
        .prepare(
          "UPDATE ride_jobs SET status='CANCELLED',pin_hash=NULL,pin_cipher=NULL,waiting_since=NULL,waiting_kind=NULL,completed_at=?,cancel_reason=? WHERE id=?",
        )
        .run(
          instant(),
          String(input.reason || "Cancelled by participant").slice(0, 500),
          key,
        );
      if (r.driver_id) {
        const p = user.id === r.driver_id && input.latitude != null ? position(input) : null;
        await saveAvailability(tx, r.driver_id, r.vehicle_id, true, p);
      }
    });
    return { ride: await rideView(await getRide(key, user), user) };
  }
  async function resolve(user, key, input) {
    const note = clean(input.note, "support resolution note", 1000);
    if (!["CANCEL", "COMPLETE"].includes(input.action))
      fail(400, "Choose complete or cancel.");
    await db.transaction(async (tx) => {
      const r = await getRide(key, user, tx, true);
      if (["COMPLETED", "CANCELLED"].includes(r.status))
        fail(409, "This ride is already settled.");
      if (input.action === "COMPLETE" && r.status !== "IN_PROGRESS")
        fail(409, "Only a started trip can be completed by support.");
      await settle(tx, r, "ride_jobs", input.action === "COMPLETE");
      await tx
        .prepare(
          "UPDATE ride_jobs SET status=?,pin_hash=NULL,pin_cipher=NULL,waiting_seconds=waiting_seconds+?,waiting_since=NULL,waiting_kind=NULL,completed_at=?,cancel_reason=? WHERE id=?",
        )
        .run(
          input.action === "COMPLETE" ? "COMPLETED" : "CANCELLED",
          liveWaiting(r),
          instant(),
          note,
          key,
        );
      await audit(
        tx,
        user.id,
        "RIDE_SUPPORT_" + input.action,
        key + ": " + note,
      );
    });
    return { ride: await rideView(await getRide(key, user), user) };
  }
  async function me(user) {
    await expireRequests();
    const rows = await db
        .prepare(
          "SELECT * FROM ride_jobs WHERE passenger_id=? ORDER BY created_at DESC LIMIT 50",
        )
        .all(user.id),
      rides = await Promise.all(rows.map((r) => rideView(r, user)));
    const shared = await db
        .prepare(
          "SELECT * FROM ride_shared_trips WHERE passenger_id=? ORDER BY departure_at DESC LIMIT 50",
        )
        .all(user.id),
      bookings = await db
        .prepare(
          "SELECT * FROM ride_shared_bookings WHERE passenger_id=? ORDER BY created_at DESC LIMIT 100",
        )
        .all(user.id);
    return {
      ride:
        rides.find(
          (r) => r.status === "REQUESTED" || ACTIVE.includes(r.status),
        ) || null,
      rides,
      sharedTrips: await Promise.all(shared.map((r) => sharedView(r, user))),
      bookings: await Promise.all(bookings.map((b) => bookingView(b, user))),
    };
  }
  async function adminTrips(user) {
    return {
      rides: await Promise.all(
        (
          await db
            .prepare(
              "SELECT * FROM ride_jobs ORDER BY created_at DESC LIMIT 200",
            )
            .all()
        ).map((r) => rideView(r, user)),
      ),
      sharedTrips: await Promise.all(
        (
          await db
            .prepare(
              "SELECT * FROM ride_shared_trips ORDER BY created_at DESC LIMIT 200",
            )
            .all()
        ).map((r) => sharedView(r, user)),
      ),
    };
  }
  // Shared journey implementation is appended below; it uses the same vehicle,
  // driver, wallet and immutable fare conventions as private taxi journeys.
  async function bookingView(b, viewer, tx = db) {
    const own =
        viewer && (viewer.role === "ADMIN" || viewer.id === b.passenger_id),
      trip = await tx
        .prepare("SELECT driver_id FROM ride_shared_trips WHERE id=?")
        .get(b.trip_id),
      driver = viewer && viewer.id === trip?.driver_id;
    const result = {
      id: b.id,
      tripId: b.trip_id,
      passengerId: own || driver ? b.passenger_id : null,
      seats: parse(b.seats_json).length,
      seatNumbers: parse(b.seats_json),
      farePerSeatEtb: Number(b.fare_cents) / 100,
      totalEtb: Number(b.total_cents) / 100,
      status: b.status,
      paymentMode: "DIRECT_TO_DRIVER",
      paymentStatus: "DIRECT_UNVERIFIED",
    };
    if (
      viewer?.id === b.passenger_id &&
      ["CONFIRMED", "BOARDED"].includes(b.status)
    ) {
      result.boardingPin = decrypt(b.pin_cipher);
      result.boardingQrToken = result.boardingPin;
    }
    if (own || driver) {
      const u = await tx
        .prepare("SELECT fullName,phone FROM users WHERE id=?")
        .get(b.passenger_id);
      result.passengerName = u?.fullName || "";
      result.passengerPhone = u?.phone || "";
    }
    return result;
  }
  async function sharedView(r, viewer, tx = db) {
    const occupied = await tx
        .prepare("SELECT seat_number FROM ride_shared_seats WHERE trip_id=?")
        .all(r.id),
      e = parse(r.estimate_json);
    const result = {
      id: r.id,
      initiatorRole: r.initiator_role,
      driverId: r.driver_id,
      vehicleId: r.vehicle_id,
      ...parse(r.route_json),
      departureAt: r.departure_at,
      seats: Number(r.seats),
      availableSeats: Math.max(0, Number(r.seats) - occupied.length),
      farePerSeatEtb: r.fare_cents == null ? null : Number(r.fare_cents) / 100,
      status: r.status,
      routeGeometry: e.routeGeometry || null,
      routeGeometryQuality: e.routeGeometryQuality || null,
      routeGeometryToleranceMeters: e.routeGeometryToleranceMeters ?? null,
      distanceKm: e.distanceKm,
      durationMinutes: e.durationMinutes,
      commissionEtb: Number(r.commission_cents) / 100,
      settlement: r.settlement,
      paymentMode: "DIRECT_TO_DRIVER",
    };
    if (r.driver_id) {
      const d = await tx
          .prepare("SELECT fullName FROM users WHERE id=?")
          .get(r.driver_id),
        v = await tx
          .prepare("SELECT * FROM vehicles WHERE id=?")
          .get(r.vehicle_id);
      Object.assign(result, {
        driverName: d?.fullName || "",
        vehicleModel: v?.model || "",
        vehicleType: v?.vehicleType || "",
      });
    }
    if (
      viewer &&
      (viewer.role === "ADMIN" ||
        viewer.id === r.driver_id ||
        viewer.id === r.passenger_id)
    ) {
      result.passengerId = r.passenger_id;
      result.bookings = await Promise.all(
        (
          await tx
            .prepare(
              "SELECT * FROM ride_shared_bookings WHERE trip_id=? ORDER BY created_at",
            )
            .all(r.id)
        )
          .filter(
            (b) =>
              viewer.role === "ADMIN" ||
              viewer.id === r.driver_id ||
              b.passenger_id === viewer.id,
          )
          .map((b) => bookingView(b, viewer, tx)),
      );
    }
    const ownBooking = viewer
      ? await tx
          .prepare(
            "SELECT id FROM ride_shared_bookings WHERE trip_id=? AND passenger_id=? AND status IN ('CONFIRMED','BOARDED')",
          )
          .get(r.id, viewer.id)
      : null;
    const participant =
      viewer &&
      (viewer.role === "ADMIN" || viewer.id === r.driver_id || ownBooking);
    if (participant && ["BOARDING", "IN_PROGRESS"].includes(r.status)) {
      const p = await tx
        .prepare("SELECT * FROM ride_shared_locations WHERE trip_id=?")
        .get(r.id);
      if (p)
        result.location = {
          latitude: Number(p.latitude),
          longitude: Number(p.longitude),
          accuracyMeters: Number(p.accuracy_meters),
          timestamp: p.recorded_at,
          stale: now() - Date.parse(p.recorded_at) > 120000,
        };
    }
    if (participant && r.driver_id) {
      const d = await tx
          .prepare("SELECT phone FROM users WHERE id=?")
          .get(r.driver_id),
        v = await tx
          .prepare("SELECT plateNumber FROM vehicles WHERE id=?")
          .get(r.vehicle_id);
      result.driverPhone = d?.phone || "";
      result.vehiclePlate = v?.plateNumber || "";
    }
    return result;
  }
  async function sharedTrips(query = {}) {
    const rows = await db
      .prepare(
        "SELECT t.* FROM ride_shared_trips t WHERE t.status='OPEN' AND t.departure_at>? AND t.seats>(SELECT COUNT(*) FROM ride_shared_seats s WHERE s.trip_id=t.id) ORDER BY t.departure_at LIMIT 200",
      )
      .all(instant());
    const o = String(query.origin || "").toLocaleLowerCase(),
      d = String(query.destination || "").toLocaleLowerCase();
    return {
      trips: await Promise.all(
        rows
          .filter((r) => {
            const path = parse(r.route_json);
            return (
              path.origin.toLocaleLowerCase().includes(o) &&
              path.destination.toLocaleLowerCase().includes(d)
            );
          })
          .map((r) => sharedView(r)),
      ),
    };
  }
  async function sharedTrip(key, viewer) {
    const r = await db
      .prepare("SELECT * FROM ride_shared_trips WHERE id=?")
      .get(key);
    const owner =
        viewer &&
        (viewer.role === "ADMIN" ||
          viewer.id === r?.driver_id ||
          viewer.id === r?.passenger_id),
      b = viewer
        ? await db
            .prepare(
              "SELECT * FROM ride_shared_bookings WHERE trip_id=? AND passenger_id=? ORDER BY CASE WHEN status IN ('CONFIRMED','BOARDED') THEN 0 ELSE 1 END,created_at DESC LIMIT 1",
            )
            .get(key, viewer.id)
        : null;
    if (!r || (!owner && !b && r.status !== "OPEN"))
      fail(404, "Shared trip unavailable.");
    return {
      trip: await sharedView(r, viewer),
      occupiedSeats: (
        await db
          .prepare("SELECT seat_number FROM ride_shared_seats WHERE trip_id=?")
          .all(key)
      ).map((s) => Number(s.seat_number)),
      booking: b ? await bookingView(b, viewer) : null,
    };
  }
  function departure(input) {
    const t = Date.parse(input.departureAt);
    if (!Number.isFinite(t) || t < now() + 60000 || t > now() + 30 * 86400000)
      fail(400, "Choose a departure between one minute and 30 days from now.");
    return new Date(t).toISOString();
  }
  async function createShared(user, input, offer = false) {
    const cfg = await requireEnabled(true),
      key = clean(input.idempotencyKey, "request reference", 100),
      previous = await db
        .prepare(
          "SELECT * FROM ride_shared_trips WHERE idempotency_owner=? AND idempotency_key=?",
        )
        .get(user.id, key);
    if (previous) return { trip: await sharedView(previous, user) };
    const data = await calculate(input, true),
      at = departure(input),
      seats = integer(input.seats, "seats", 1, 100),
      fareCents = offer ? money(input.farePerSeatEtb) : null;
    if (
      offer &&
      (fareCents < cfg.minimumSharedFareEtb * 100 ||
        fareCents > cfg.maximumSharedFareEtb * 100)
    )
      fail(400, "Choose a per-seat fare within Passo limits.");
    let rid;
    await db.transaction(async (tx) => {
      if (offer) await approvedVehicle(user.id, input.vehicleId, tx, true);
      await activeUser(tx, user.id, offer ? "DRIVER" : "PASSENGER", true);
      const old = await tx
        .prepare(
          "SELECT * FROM ride_shared_trips WHERE idempotency_owner=? AND idempotency_key=?",
        )
        .get(user.id, key);
      if (old) {
        rid = old.id;
        return;
      }
      if (offer) {
        const v = await approvedVehicle(user.id, input.vehicleId, tx);
        if (seats > Number(v.capacityLimit))
          fail(400, "Offer only approved passenger seats.");
        if (await busy(user.id, v.id, tx))
          fail(409, "Finish your current job before offering a shared trip.");
        await scheduleSafe(
          user.id,
          v.id,
          data.estimate.durationMinutes,
          tx,
          Date.parse(at),
        );
      }
      rid = id("shared_");
      await tx
        .prepare(
          "INSERT INTO ride_shared_trips(id,initiator_role,passenger_id,driver_id,vehicle_id,route_json,estimate_json,departure_at,seats,fare_cents,status,commission_cents,idempotency_owner,idempotency_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        )
        .run(
          rid,
          offer ? "DRIVER" : "PASSENGER",
          offer ? null : user.id,
          offer ? user.id : null,
          offer ? input.vehicleId : null,
          JSON.stringify(data.route),
          JSON.stringify(data.estimate),
          at,
          seats,
          fareCents,
          offer ? "OPEN" : "REQUESTED",
          money(cfg.sharedFixedCommissionEtb),
          user.id,
          key,
          instant(),
        );
    });
    return {
      trip: await sharedView(
        await db.prepare("SELECT * FROM ride_shared_trips WHERE id=?").get(rid),
        user,
      ),
    };
  }
  async function acceptShared(user, key, input) {
    const cfg = await requireEnabled(true),
      seats = integer(input.seats, "seats", 1, 100),
      cents = money(input.farePerSeatEtb);
    if (
      cents < cfg.minimumSharedFareEtb * 100 ||
      cents > cfg.maximumSharedFareEtb * 100
    )
      fail(400, "Choose a per-seat fare within Passo limits.");
    await db.transaction(async (tx) => {
      const v = await approvedVehicle(user.id, input.vehicleId, tx, true),
        r = await tx
          .prepare("SELECT * FROM ride_shared_trips WHERE id=?" + lock(tx))
          .get(key);
      if (r?.driver_id === user.id && r.status === "OPEN") return;
      if (!r || r.status !== "REQUESTED" || Date.parse(r.departure_at) <= now())
        fail(409, "This shared request is no longer available.");
      if (seats < Number(r.seats) || seats > Number(v.capacityLimit))
        fail(400, "Offer enough approved seats for the requesting passengers.");
      if (await busy(user.id, v.id, tx))
        fail(409, "Finish your current job first.");
      await scheduleSafe(
        user.id,
        v.id,
        parse(r.estimate_json).durationMinutes,
        tx,
        Date.parse(r.departure_at),
      );
      await tx
        .prepare(
          "UPDATE ride_shared_trips SET driver_id=?,vehicle_id=?,seats=?,fare_cents=?,status='OPEN',commission_cents=? WHERE id=?",
        )
        .run(
          user.id,
          v.id,
          seats,
          cents,
          money(cfg.sharedFixedCommissionEtb),
          key,
        );
    });
    return {
      trip: await sharedView(
        await db.prepare("SELECT * FROM ride_shared_trips WHERE id=?").get(key),
        user,
      ),
    };
  }
  async function bookShared(user, key, input) {
    await requireEnabled(true);
    const requestKey = clean(input.idempotencyKey, "booking reference", 100);
    if (
      !Array.isArray(input.seatNumbers) ||
      !input.seatNumbers.length ||
      input.seatNumbers.length > 100
    )
      fail(400, "Select passenger seats.");
    const seats = input.seatNumbers.map((n) =>
      integer(n, "seat number", 1, 100),
    );
    if (new Set(seats).size !== seats.length)
      fail(400, "Choose each seat only once.");
    let bid;
    const initial = await db
      .prepare("SELECT * FROM ride_shared_trips WHERE id=?")
      .get(key);
    if (!initial?.driver_id)
      fail(409, "This shared trip is not open for booking.");
    await db.transaction(async (tx) => {
      await approvedVehicle(initial.driver_id, initial.vehicle_id, tx, true);
      await activeUser(tx, user.id, "PASSENGER", true);
      const prior = await tx
        .prepare(
          "SELECT * FROM ride_shared_bookings WHERE passenger_id=? AND idempotency_key=?",
        )
        .get(user.id, requestKey);
      if (prior) {
        if (prior.trip_id !== key)
          fail(409, "Use a new booking reference for a different trip.");
        bid = prior.id;
        return;
      }
      const r = await tx
        .prepare("SELECT * FROM ride_shared_trips WHERE id=?" + lock(tx))
        .get(key);
      if (!r || r.status !== "OPEN" || Date.parse(r.departure_at) <= now())
        fail(409, "This shared trip is not open for booking.");
      if (r.driver_id === user.id)
        fail(400, "Drivers cannot book their own offered seats.");
      const approved = await approvedVehicle(r.driver_id, r.vehicle_id, tx);
      if (Number(r.seats) > Number(approved.capacityLimit))
        fail(
          409,
          "This vehicle no longer has enough approved passenger seats.",
        );
      if (seats.some((n) => n > Number(r.seats)))
        fail(400, "Choose seats in the approved layout.");
      for (const n of seats)
        if (
          await tx
            .prepare(
              "SELECT booking_id FROM ride_shared_seats WHERE trip_id=? AND seat_number=?",
            )
            .get(key, n)
        )
          fail(409, "One of the selected seats was just booked.");
      if (r.settlement !== "HELD") {
        const version = Number(r.hold_version || 0) + 1;
        await hold(tx, r.driver_id, r.id, Number(r.commission_cents), version);
        await tx
          .prepare(
            "UPDATE ride_shared_trips SET settlement='HELD',hold_version=? WHERE id=?",
          )
          .run(version, key);
      }
      const pin = pinPair();
      bid = id("seat_");
      await tx
        .prepare(
          "INSERT INTO ride_shared_bookings(id,trip_id,passenger_id,seats_json,fare_cents,total_cents,status,pin_hash,pin_cipher,idempotency_key,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        )
        .run(
          bid,
          key,
          user.id,
          JSON.stringify(seats),
          r.fare_cents,
          Number(r.fare_cents) * seats.length,
          "CONFIRMED",
          pin.hash,
          pin.cipher,
          requestKey,
          instant(),
        );
      for (const n of seats)
        await tx
          .prepare(
            "INSERT INTO ride_shared_seats(trip_id,seat_number,booking_id) VALUES(?,?,?)",
          )
          .run(key, n, bid);
    });
    return {
      booking: await bookingView(
        await db
          .prepare("SELECT * FROM ride_shared_bookings WHERE id=?")
          .get(bid),
        user,
      ),
      trip: (await sharedTrip(key, user)).trip,
    };
  }
  async function cancelBooking(user, key) {
    let booking;
    await db.transaction(async (tx) => {
      let b = await tx
        .prepare("SELECT * FROM ride_shared_bookings WHERE id=?")
        .get(key);
      if (!b || b.passenger_id !== user.id) fail(404, "Booking unavailable.");
      const r = await tx
        .prepare("SELECT * FROM ride_shared_trips WHERE id=?" + lock(tx))
        .get(b.trip_id);
      b = await tx
        .prepare("SELECT * FROM ride_shared_bookings WHERE id=?")
        .get(key);
      if (b.status === "CANCELLED") {
        booking = b;
        return;
      }
      if (
        !["OPEN", "REQUESTED", "BOARDING"].includes(r.status) ||
        b.status === "BOARDED"
      )
        fail(409, "Contact Passo to cancel after boarding opens.");
      await tx
        .prepare(
          "UPDATE ride_shared_bookings SET status='CANCELLED' WHERE id=?",
        )
        .run(key);
      await tx
        .prepare("DELETE FROM ride_shared_seats WHERE booking_id=?")
        .run(key);
      const remaining = await tx
        .prepare(
          "SELECT COUNT(*) AS count FROM ride_shared_bookings WHERE trip_id=? AND status IN ('CONFIRMED','BOARDED')",
        )
        .get(r.id);
      if (!Number(remaining.count))
        await settle(tx, r, "ride_shared_trips", false);
      booking = { ...b, status: "CANCELLED" };
    });
    return { booking: await bookingView(booking, user) };
  }
  async function cancelSharedRequest(user, key) {
    await db.transaction(async (tx) => {
      const r = await tx
        .prepare("SELECT * FROM ride_shared_trips WHERE id=?" + lock(tx))
        .get(key);
      if (!r || r.passenger_id !== user.id)
        fail(404, "Your shared ride request is unavailable.");
      if (r.status === "CANCELLED") return;
      if (!["REQUESTED", "OPEN"].includes(r.status))
        fail(
          409,
          "Cancel your own seat booking or contact Passo after boarding opens.",
        );
      const booked = await tx
        .prepare(
          "SELECT COUNT(*) AS count FROM ride_shared_bookings WHERE trip_id=? AND status IN ('CONFIRMED','BOARDED')",
        )
        .get(key);
      if (Number(booked.count))
        fail(
          409,
          "This shared trip already has passenger bookings. Cancel your own booking or contact Passo.",
        );
      await settle(tx, r, "ride_shared_trips", false);
      await tx
        .prepare(
          "UPDATE ride_shared_trips SET status='CANCELLED',completed_at=?,cancel_reason='Cancelled by requesting passenger' WHERE id=?",
        )
        .run(instant(), key);
    });
    return {
      trip: await sharedView(
        await db.prepare("SELECT * FROM ride_shared_trips WHERE id=?").get(key),
        user,
      ),
    };
  }
  async function resolveShared(user, key, input) {
    const note = clean(input.note, "support resolution note", 1000);
    if (!["CANCEL", "COMPLETE"].includes(input.action))
      fail(400, "Choose complete or cancel.");
    await db.transaction(async (tx) => {
      const r = await tx
        .prepare("SELECT * FROM ride_shared_trips WHERE id=?" + lock(tx))
        .get(key);
      if (!r) fail(404, "Shared trip unavailable.");
      if (["COMPLETED", "CANCELLED"].includes(r.status))
        fail(409, "This shared trip is already settled.");
      if (input.action === "COMPLETE" && r.status !== "IN_PROGRESS")
        fail(409, "Only a started shared trip can be completed by support.");
      await settle(tx, r, "ride_shared_trips", input.action === "COMPLETE");
      await tx
        .prepare(
          "UPDATE ride_shared_trips SET status=?,completed_at=?,cancel_reason=? WHERE id=?",
        )
        .run(
          input.action === "COMPLETE" ? "COMPLETED" : "CANCELLED",
          instant(),
          note,
          key,
        );
      if (input.action === "COMPLETE")
        await tx
          .prepare(
            "UPDATE ride_shared_bookings SET status='COMPLETED' WHERE trip_id=? AND status='BOARDED'",
          )
          .run(key);
      else {
        await tx
          .prepare(
            "UPDATE ride_shared_bookings SET status='CANCELLED' WHERE trip_id=? AND status IN ('CONFIRMED','BOARDED')",
          )
          .run(key);
        await tx
          .prepare("DELETE FROM ride_shared_seats WHERE trip_id=?")
          .run(key);
      }
      await audit(
        tx,
        user.id,
        "SHARED_SUPPORT_" + input.action,
        key + ": " + note,
      );
    });
    return {
      trip: await sharedView(
        await db.prepare("SELECT * FROM ride_shared_trips WHERE id=?").get(key),
        user,
      ),
    };
  }
  async function sharedActions(user, key, input) {
    let invalid = false;
    await db.transaction(async (tx) => {
      let r = await tx
        .prepare("SELECT * FROM ride_shared_trips WHERE id=?")
        .get(key);
      if (!r || r.driver_id !== user.id)
        fail(404, "Assigned shared trip unavailable.");
      const action = input.action;
      let v;
      if (["BOARDING", "BOARD", "START"].includes(action))
        v = await approvedVehicle(user.id, r.vehicle_id, tx, true);
      else {
        v = await tx
          .prepare("SELECT * FROM vehicles WHERE id=?" + lock(tx))
          .get(r.vehicle_id);
        await tx
          .prepare("SELECT id FROM users WHERE id=?" + lock(tx))
          .get(user.id);
      }
      r = await tx
        .prepare("SELECT * FROM ride_shared_trips WHERE id=?" + lock(tx))
        .get(key);
      if (action === "BOARDING") {
        if (r.status === "BOARDING") return;
        if (r.status !== "OPEN") fail(409, "This trip is not open.");
        if (
          now() < Date.parse(r.departure_at) - 60 * 60000 ||
          now() > Date.parse(r.departure_at) + 60 * 60000
        )
          fail(409, "Boarding opens one hour before departure.");
        if (await busy(user.id, v.id, tx, key))
          fail(409, "Finish your other transport job first.");
        if (Number(r.seats) > Number(v.capacityLimit))
          fail(
            409,
            "This vehicle no longer has enough approved passenger seats.",
          );
        await scheduleSafe(
          user.id,
          v.id,
          Number(parse(r.estimate_json).durationMinutes) +
            Math.max(0, (Date.parse(r.departure_at) - now()) / 120000),
          tx,
          now(),
          key,
        );
        const count = await tx
          .prepare(
            "SELECT COUNT(*) AS count FROM ride_shared_bookings WHERE trip_id=? AND status='CONFIRMED'",
          )
          .get(key);
        if (!Number(count.count))
          fail(409, "At least one passenger booking is required.");
        if (r.settlement !== "HELD") {
          const version = Number(r.hold_version || 0) + 1;
          await hold(tx, user.id, key, Number(r.commission_cents), version);
          await tx
            .prepare("UPDATE ride_shared_trips SET hold_version=? WHERE id=?")
            .run(version, key);
        }
        await tx
          .prepare(
            "UPDATE ride_shared_trips SET status='BOARDING',settlement='HELD' WHERE id=?",
          )
          .run(key);
      } else if (action === "BOARD") {
        if (r.status !== "BOARDING") fail(409, "Open boarding first.");
        const b = await tx
          .prepare(
            "SELECT * FROM ride_shared_bookings WHERE id=? AND trip_id=?",
          )
          .get(input.bookingId, key);
        if (!b) fail(404, "Booking unavailable.");
        if (b.status === "BOARDED") return;
        if (b.status !== "CONFIRMED")
          fail(409, "This booking is not confirmed.");
        if (!(await verifyPin(tx, b.id, user.id, b.pin_hash, input.qrToken || input.pin))) {
          invalid = true;
          return;
        }
        await tx
          .prepare(
            "UPDATE ride_shared_bookings SET status='BOARDED',boarded_at=? WHERE id=?",
          )
          .run(instant(), b.id);
      } else if (action === "NO_SHOW") {
        if (r.status !== "BOARDING")
          fail(409, "Open boarding before marking a no-show.");
        const cfg = (await catalog()).settings;
        if (now() < Date.parse(r.departure_at) + cfg.noShowGraceMinutes * 60000)
          fail(
            409,
            "Wait until the departure grace period ends before marking a no-show.",
          );
        const b = await tx
          .prepare(
            "SELECT * FROM ride_shared_bookings WHERE id=? AND trip_id=?",
          )
          .get(input.bookingId, key);
        if (!b) fail(404, "Booking unavailable.");
        if (b.status === "CANCELLED") return;
        if (b.status !== "CONFIRMED")
          fail(409, "Only an unboarded booking can be marked no-show.");
        await tx
          .prepare(
            "UPDATE ride_shared_bookings SET status='CANCELLED' WHERE id=?",
          )
          .run(b.id);
        await tx
          .prepare("DELETE FROM ride_shared_seats WHERE booking_id=?")
          .run(b.id);
        await audit(tx, user.id, "SHARED_NO_SHOW", key + ": " + b.id);
        const remaining = await tx
          .prepare(
            "SELECT COUNT(*) AS count FROM ride_shared_bookings WHERE trip_id=? AND status IN ('CONFIRMED','BOARDED')",
          )
          .get(key);
        if (!Number(remaining.count))
          await settle(tx, r, "ride_shared_trips", false);
      } else if (action === "START") {
        if (r.status === "IN_PROGRESS") return;
        if (r.status !== "BOARDING") fail(409, "Open boarding first.");
        await scheduleSafe(
          user.id,
          r.vehicle_id,
          Number(parse(r.estimate_json).durationMinutes),
          tx,
          now(),
          key,
        );
        const p = position(input);
        const route = parse(r.route_json),
          cfg = (await catalog()).settings;
        if (
          p.accuracyMeters > 100 ||
          km(
            p.latitude,
            p.longitude,
            route.originLatitude,
            route.originLongitude,
          ) *
            1000 >
            cfg.arrivalRadiusMeters
        )
          fail(409, "Reach the shared pickup before starting.");
        const pending = await tx
          .prepare(
            "SELECT COUNT(*) AS count FROM ride_shared_bookings WHERE trip_id=? AND status='CONFIRMED'",
          )
          .get(key);
        if (Number(pending.count) > 0)
          fail(
            409,
            "Confirm boarding or cancel missing passenger bookings before departure.",
          );
        const onboard = await tx
          .prepare(
            "SELECT COUNT(*) AS count FROM ride_shared_bookings WHERE trip_id=? AND status='BOARDED'",
          )
          .get(key);
        if (!Number(onboard.count))
          fail(409, "Confirm passenger boarding before starting.");
        await tx
          .prepare(
            "UPDATE ride_shared_trips SET status='IN_PROGRESS',started_at=? WHERE id=?",
          )
          .run(instant(), key);
        const onboarded = await tx.prepare("SELECT u.phone FROM ride_shared_bookings b JOIN users u ON u.id=b.passenger_id WHERE b.trip_id=? AND b.status='BOARDED'").all(key);
        const routeLabel = `${route.origin} to ${route.destination}`;
        for (const passenger of onboarded) await queueRideSms(tx, key, "shared-start", passenger.phone, `PASSO group taxi trip started: ${routeLabel}. Open Passo to view your trip.`);
      } else if (action === "COMPLETE") {
        if (r.status === "COMPLETED") return;
        if (r.status !== "IN_PROGRESS")
          fail(409, "Start the shared trip first.");
        const p = position(input),
          route = parse(r.route_json),
          cfg = (await catalog()).settings;
        if (
          p.accuracyMeters > 100 ||
          km(
            p.latitude,
            p.longitude,
            route.destinationLatitude,
            route.destinationLongitude,
          ) *
            1000 >
            cfg.arrivalRadiusMeters
        )
          fail(409, "Reach the shared destination before completion.");
        await settle(tx, r, "ride_shared_trips", true);
        await tx
          .prepare(
            "UPDATE ride_shared_trips SET status='COMPLETED',completed_at=? WHERE id=?",
          )
          .run(instant(), key);
        await tx
          .prepare(
            "UPDATE ride_shared_bookings SET status='COMPLETED' WHERE trip_id=? AND status='BOARDED'",
          )
          .run(key);
        const completed = await tx.prepare("SELECT u.phone FROM ride_shared_bookings b JOIN users u ON u.id=b.passenger_id WHERE b.trip_id=? AND b.status='COMPLETED'").all(key);
        const routeLabel = `${route.origin} to ${route.destination}`;
        for (const passenger of completed) await queueRideSms(tx, key, "shared-complete", passenger.phone, `PASSO group taxi trip completed: ${routeLabel}. Thank you for riding with Passo.`);
        await saveAvailability(tx, user.id, r.vehicle_id, true, p);
      } else if (action === "CANCEL") {
        if (r.status === "CANCELLED") return;
        if (!["REQUESTED", "OPEN", "BOARDING"].includes(r.status))
          fail(
            409,
            "Contact Passo to resolve a shared trip already in progress.",
          );
        await settle(tx, r, "ride_shared_trips", false);
        await tx
          .prepare(
            "UPDATE ride_shared_trips SET status='CANCELLED',completed_at=? WHERE id=?",
          )
          .run(instant(), key);
        await tx
          .prepare(
            "UPDATE ride_shared_bookings SET status='CANCELLED' WHERE trip_id=? AND status IN ('CONFIRMED','BOARDED')",
          )
          .run(key);
        await tx
          .prepare("DELETE FROM ride_shared_seats WHERE trip_id=?")
          .run(key);
        const p = input.latitude != null ? position(input) : null;
        await saveAvailability(tx, user.id, r.vehicle_id, true, p);
      } else fail(400, "Choose a valid shared trip action.");
    });
    void flushRideSms();
    if (invalid)
      fail(
        400,
        "The passenger boarding PIN is incorrect.",
        "INVALID_BOARDING_PIN",
      );
    return {
      trip: await sharedView(
        await db.prepare("SELECT * FROM ride_shared_trips WHERE id=?").get(key),
        user,
      ),
    };
  }
  async function sharedLocation(user, key, input) {
    const p = position(input);
    await db.transaction(async (tx) => {
      const r = await tx
        .prepare("SELECT * FROM ride_shared_trips WHERE id=?" + lock(tx))
        .get(key);
      if (
        !r ||
        r.driver_id !== user.id ||
        !["BOARDING", "IN_PROGRESS"].includes(r.status)
      )
        fail(
          409,
          "Location sharing is available during your assigned shared trip.",
        );
      const old = await tx
        .prepare(
          "SELECT recorded_at FROM ride_shared_locations WHERE trip_id=?",
        )
        .get(key);
      if (old && Date.parse(old.recorded_at) > Date.parse(p.timestamp)) return;
      await tx
        .prepare(
          "INSERT INTO ride_shared_locations(trip_id,driver_id,latitude,longitude,accuracy_meters,recorded_at) VALUES(?,?,?,?,?,?) ON CONFLICT(trip_id) DO UPDATE SET latitude=excluded.latitude,longitude=excluded.longitude,accuracy_meters=excluded.accuracy_meters,recorded_at=excluded.recorded_at",
        )
        .run(
          key,
          user.id,
          p.latitude,
          p.longitude,
          p.accuracyMeters,
          p.timestamp,
        );
    });
    return {
      trip: await sharedView(
        await db.prepare("SELECT * FROM ride_shared_trips WHERE id=?").get(key),
        user,
      ),
    };
  }
  async function cleanup() {
    await expireRequests();
    const expired = await db
      .prepare(
        "SELECT id FROM ride_shared_trips WHERE status IN ('REQUESTED','OPEN') AND departure_at<? ORDER BY departure_at LIMIT 100",
      )
      .all(new Date(now() - 60 * 60000).toISOString());
    for (const candidate of expired)
      await db.transaction(async (tx) => {
        const r = await tx
          .prepare("SELECT * FROM ride_shared_trips WHERE id=?" + lock(tx))
          .get(candidate.id);
        if (
          !r ||
          !["REQUESTED", "OPEN"].includes(r.status) ||
          Date.parse(r.departure_at) >= now() - 60 * 60000
        )
          return;
        await settle(tx, r, "ride_shared_trips", false);
        await tx
          .prepare(
            "UPDATE ride_shared_trips SET status='CANCELLED',completed_at=?,cancel_reason='Departure boarding window expired' WHERE id=?",
          )
          .run(instant(), r.id);
        await tx
          .prepare(
            "UPDATE ride_shared_bookings SET status='CANCELLED',pin_hash='',pin_cipher='' WHERE trip_id=? AND status='CONFIRMED'",
          )
          .run(r.id);
        await tx
          .prepare("DELETE FROM ride_shared_seats WHERE trip_id=?")
          .run(r.id);
      });
    await db
      .prepare(
        "UPDATE ride_shared_bookings SET pin_hash='',pin_cipher='' WHERE status IN ('COMPLETED','CANCELLED') AND pin_cipher<>''",
      )
      .run();
    await db
      .prepare(
        "DELETE FROM ride_shared_locations WHERE trip_id IN (SELECT id FROM ride_shared_trips WHERE status IN ('COMPLETED','CANCELLED'))",
      )
      .run();
    await db
      .prepare(
        "UPDATE ride_jobs SET pin_hash=NULL,pin_cipher=NULL WHERE status IN ('COMPLETED','CANCELLED') AND pin_cipher IS NOT NULL",
      )
      .run();
    // Keep settled fare totals and the audit ledger; raw GPS evidence has a
    // bounded retention period rather than becoming a permanent location log.
    const retentionDays = Math.min(
      90,
      Math.max(7, Number(process.env.RIDE_GPS_RETENTION_DAYS) || 30),
    );
    await db
      .prepare(
        "DELETE FROM ride_samples WHERE ride_id IN (SELECT id FROM ride_jobs WHERE status IN ('COMPLETED','CANCELLED') AND completed_at<?)",
      )
      .run(new Date(now() - retentionDays * 86400000).toISOString());
    await db
      .prepare("UPDATE ride_availability SET online=0 WHERE recorded_at<?")
      .run(new Date(now() - 120000).toISOString());
    await db
      .prepare(
        "UPDATE ride_availability SET latitude=NULL,longitude=NULL,accuracy_meters=NULL,recorded_at=NULL WHERE online=0 AND recorded_at IS NOT NULL AND updated_at<? AND NOT EXISTS (SELECT 1 FROM ride_jobs r WHERE r.driver_id=ride_availability.driver_id AND r.status IN ('ACCEPTED','AT_PICKUP','IN_PROGRESS')) AND NOT EXISTS (SELECT 1 FROM ride_shared_trips s WHERE s.driver_id=ride_availability.driver_id AND s.status IN ('BOARDING','IN_PROGRESS'))",
      )
      .run(new Date(now() - 120000).toISOString());
    return { expiredSharedTrips: expired.length };
  }
  return {
    init: () => initRidesSchema(db),
    catalog,
    updateSettings,
    estimate,
    nearby,
    eligibility,
    updateEligibility,
    createRequest,
    dashboard,
    availability,
    accept,
    street,
    arrive,
    start,
    samples,
    wait,
    stopWait,
    complete,
    cancel,
    resolve,
    me,
    adminTrips,
    sharedTrips,
    sharedTrip,
    createShared,
    acceptShared,
    bookShared,
    cancelBooking,
    cancelSharedRequest,
    resolveShared,
    sharedActions,
    sharedLocation,
    cleanup,
    flushSms: flushRideSms,
    get: async (user, key) => ({
      ride: await rideView(await getRide(key, user), user),
    }),
  };
}
let instance;
const getRidesService = () => (instance ||= createRidesService());
function startRidesWorker({ service = getRidesService() } = {}) {
  let running = false;
  const tick = async () => {
    if (running) return;
    running = true;
    try {
      await service.init();
      await service.cleanup();
      await service.flushSms?.();
    } catch (error) {
      console.error("[Rides] maintenance failed:", error.message);
    } finally {
      running = false;
    }
  };
  const timer = setInterval(() => {
    void tick();
  }, 30000);
  timer.unref?.();
  return () => clearInterval(timer);
}
module.exports = {
  RideError,
  createRidesService,
  getRidesService,
  assertNoRideJob,
  km,
  initRidesSchema,
  startRidesWorker,
};
