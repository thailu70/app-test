"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
process.env.NODE_ENV = "test";
process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "passo-rides-"));
if (process.env.MOBILITY_TEST_POSTGRES === "true") {
  if (process.env.CI !== "true" || !process.env.DATABASE_URL)
    throw Error("Disposable CI PostgreSQL required.");
} else delete process.env.DATABASE_URL;
const { DB } = require("../src/db");
const {
  createRidesService,
  assertNoRideJob,
} = require("../src/services/rides");
const at = new Date().toISOString();
let clock = Date.now();
const route = {
  origin: "Fixture Pickup",
  destination: "Fixture Destination",
  originLatitude: 9,
  originLongitude: 38.75,
  destinationLatitude: 9.01,
  destinationLongitude: 38.76,
  tier: "ECONOMY",
};
const geo = {
  route: async () => ({
    distanceKm: 2,
    durationMinutes: 10,
    geometry: {
      type: "LineString",
      coordinates: [
        [38.75, 9],
        [38.76, 9.01],
      ],
    },
  }),
};
const service = createRidesService({
  db: DB,
  now: () => clock,
  geo,
  secret: "fixture-secret-that-is-not-a-live-credential",
});
const passenger = { id: "ride-passenger", role: "PASSENGER" },
  otherPassenger = { id: "ride-passenger-2", role: "PASSENGER" },
  admin = { id: "ride-admin", role: "ADMIN" };
const drivers = [
  { id: "ride-driver", role: "DRIVER" },
  { id: "ride-driver-2", role: "DRIVER" },
];
const position = (latitude = 9, longitude = 38.75) => ({
  latitude,
  longitude,
  accuracyMeters: 8,
  timestamp: new Date(clock).toISOString(),
});
async function setup() {
  await require("../src/services/master-data").initMasterDataSchema();
  await require("../src/services/onboarding").initOnboardingSchema();
  await service.init();
  for (const u of [admin, passenger, otherPassenger, ...drivers])
    await DB.prepare(
      "INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
    ).run(
      u.id,
      u.role,
      u.id,
      "+25190000" +
        String(
          [admin, passenger, otherPassenger, ...drivers].indexOf(u),
        ).padStart(4, "0"),
      "unused",
      "ACTIVE",
    );
  for (const d of drivers) {
    await DB.prepare(
      "INSERT INTO onboarding_accounts(account_id,role,normalized_phone,service_type,created_at,updated_at,acceptance_status,accepted_at,accepted_by,acceptance_grandfathered,submitted_at,review_status) VALUES(?,'DRIVER',?,'DAILY_COMMUTE',?,?,'ACCEPTED',?,'ride-admin',TRUE,?,'APPROVED')",
    ).run(d.id, "+25190000" + d.id.slice(-1), at, at, at, at);
    await DB.prepare(
      "INSERT INTO user_profiles(user_id,emergency_contact_name,emergency_contact_phone,vehicle_color,updated_at) VALUES(?,'Fixture Emergency','+251900001234','white',?)",
    ).run(d.id, at);
    for (const kind of [
      "LICENSE",
      "VEHICLE_REGISTRATION",
      "INSURANCE",
      "PHOTO",
    ])
      await DB.prepare(
        "INSERT INTO user_profile_documents(user_id,kind,storage_name,content_type,byte_size,uploaded_at) VALUES(?,?,'fixture','image/jpeg',20,?)",
      ).run(d.id, kind, at);
    await DB.prepare(
      "INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,status) VALUES(?,?,?,'MINIVAN_8',7,?,'IN_SERVICE')",
    ).run(d.id + "vehicle", "FIXTURE-" + d.id, "Fixture Minivan", d.id);
    await service.updateEligibility(admin.id, d.id, {
      approved: true,
      vehicles: [
        { vehicleId: d.id + "vehicle", approved: true, tier: "ECONOMY" },
      ],
    });
    await DB.prepare(
      "INSERT INTO mobility_wallets(driver_id,balance_cents,held_cents) VALUES(?,100000,0) ON CONFLICT(driver_id) DO UPDATE SET balance_cents=100000,held_cents=0",
    ).run(d.id);
  }
}
async function available(d) {
  await service.availability(d, {
    online: true,
    vehicleId: d.id + "vehicle",
    ...position(),
  });
}
async function appTrip(key, driver = drivers[0]) {
  await available(driver);
  const requested = (
    await service.createRequest(passenger, { ...route, idempotencyKey: key })
  ).ride;
  await service.accept(driver, requested.id, { vehicleId: driver.id + "vehicle" });
  const ride = (await service.get(passenger, requested.id)).ride;
  await service.arrive(driver, ride.id, position());
  await service.start(driver, ride.id, { qrToken: ride.boardingQrToken });
  return ride;
}
test("ride lifecycle, metering, shared seats, privacy and credit are transactional", async (t) => {
  await setup();
  await t.test(
    "real fares and explicit driver/vehicle approval are required",
    async () => {
      assert.equal((await service.catalog()).settings.enabled, false);
      await assert.rejects(
        service.estimate(route),
        (e) => e.code === "SERVICE_NOT_ENABLED",
      );
      await assert.rejects(
        service.updateSettings(admin.id, { enabled: true }),
        (e) => e.status === 400,
      );
      await service.updateSettings(admin.id, {
        enabled: true,
        sharedEnabled: true,
        baseFareEtb: 20,
        perKmEtb: 10,
        minimumFareEtb: 30,
        waitingPerMinuteEtb: 2,
        pickupGraceMinutes: 1,
        stopGraceMinutes: 1,
        fixedCommissionEtb: 5,
        sharedFixedCommissionEtb: 7,
      });
      await available(drivers[0]);
      await DB.prepare("UPDATE ride_availability SET recorded_at=?,updated_at=? WHERE driver_id=?")
        .run(new Date(Date.now() - 180000).toISOString(), new Date().toISOString(), drivers[0].id);
      await DB.prepare("UPDATE ride_availability SET online=0 WHERE driver_id=?").run(drivers[0].id);
      const heartbeat = await service.availability(drivers[0], {
        online: true, heartbeatOnly: true, vehicleId: drivers[0].id + "vehicle",
      });
      assert.equal(heartbeat.online, true, "a background heartbeat restores online intent after stale cleanup");
      assert.equal(heartbeat.calls.length, 0, "a stale GPS fix is never used to match new calls");
      await assert.rejects(service.availability(drivers[1], {
        online: true, heartbeatOnly: true, vehicleId: drivers[1].id + "vehicle",
      }), (e) => e.status === 409, "a heartbeat cannot start an offline session without GPS");
      assert.equal(
        (await service.estimate(route)).estimate.estimatedFareEtb,
        40,
      );
      await assert.rejects(
        service.createRequest(passenger, {
          ...route,
          expectedFareEtb: 30,
          idempotencyKey: "changed-estimate",
        }),
        (e) => e.code === "ESTIMATE_CHANGED",
      );
      await assert.rejects(
        service.street(drivers[0], {
          ...route,
          ...position(),
          expectedFareEtb: 40,
          expectedCommissionEtb: 1,
          vehicleId: drivers[0].id + "vehicle",
          passengerPhone: "+251900000001",
          passengerConsent: true,
          idempotencyKey: "changed-street-fee",
        }),
        (e) => e.code === "ESTIMATE_CHANGED",
      );
      await assert.rejects(
        service.updateSettings(admin.id, { gpsUploadSeconds: 1.5 }),
        (e) => e.status === 400,
      );
      await service.updateEligibility(admin.id, drivers[1].id, {
        approved: false,
        vehicles: [
          {
            vehicleId: drivers[1].id + "vehicle",
            approved: true,
            tier: "ECONOMY",
          },
        ],
      });
      await assert.rejects(available(drivers[1]), (e) => e.status === 403);
      await service.updateEligibility(admin.id, drivers[1].id, {
        approved: true,
        vehicles: [
          {
            vehicleId: drivers[1].id + "vehicle",
            approved: true,
            tier: "ECONOMY",
          },
        ],
      });
    },
  );
  let ride, acceptedDriver;
  await t.test(
    "call pool redacts identities and first atomic acceptance wins",
    async () => {
      await Promise.all(drivers.map(available));
      ride = (
        await service.createRequest(passenger, {
          ...route,
          idempotencyKey: "first-request",
        })
      ).ride;
      assert.equal(ride.boardingQrToken, undefined, "The boarding QR stays private until a driver accepts the request.");
      assert.equal(
        (
          await service.createRequest(passenger, {
            ...route,
            idempotencyKey: "first-request",
          })
        ).ride.id,
        ride.id,
      );
      const pool = (await service.dashboard(drivers[0])).calls;
      assert.equal(pool.length, 1);
      assert.equal(pool[0].boardingQrToken, undefined);
      assert.equal(pool[0].passengerPhone, undefined);
      assert.equal(pool[0].passengerId, null);
      const results = await Promise.allSettled(
        drivers.map((d) =>
          service.accept(d, ride.id, { vehicleId: d.id + "vehicle" }),
        ),
      );
      assert.equal(
        results.filter((r) => r.status === "fulfilled").length,
        1,
        JSON.stringify(
          results
            .filter((r) => r.status === "rejected")
            .map((r) => ({
              message: r.reason?.message,
              code: r.reason?.code,
              stack: r.reason?.stack,
            })),
        ),
      );
      assert.equal(
        results.find((r) => r.status === "rejected").reason.status,
        409,
      );
      acceptedDriver = drivers.find(
        (d) =>
          d.id ===
          results.find((r) => r.status === "fulfilled").value.ride.driverId,
      );
      ride = (await service.get(passenger, ride.id)).ride;
      assert.match(ride.boardingQrToken, /^[A-Za-z0-9_-]{43}$/);
      const driverRide = (await service.get(acceptedDriver, ride.id)).ride;
      assert.equal(driverRide.boardingQrToken, undefined);
      assert.equal(driverRide.passengerName, passenger.id);
      await assert.rejects(
        service.get(otherPassenger, ride.id),
        (e) => e.status === 404,
      );
      await assert.rejects(
        assertNoRideJob(acceptedDriver.id, acceptedDriver.id + "vehicle", DB),
        (e) => e.code === "DRIVER_BUSY",
      );
      const w = await DB.prepare(
        "SELECT * FROM mobility_wallets WHERE driver_id=?",
      ).get(acceptedDriver.id);
      assert.equal(Number(w.held_cents), 500);
    },
  );
  await t.test(
    "arrival is geofenced and QR scan attempts are limited without exposing the token",
    async () => {
      await assert.rejects(
        service.arrive(acceptedDriver, ride.id, position(10, 39)),
        (e) => e.code === "OUTSIDE_ARRIVAL_RADIUS",
      );
      await service.arrive(acceptedDriver, ride.id, position());
      await service.samples(acceptedDriver, ride.id, {
        points: [{ ...position(), sequence: 1 }],
      });
      for (let n = 0; n < 5; n++)
        await assert.rejects(
          service.start(acceptedDriver, ride.id, n === 0
            ? { pin: ride.boardingQrToken }
            : { qrToken: "0000" }),
          (e) => e.code === "INVALID_BOARDING_QR",
        );
      await assert.rejects(
        service.start(acceptedDriver, ride.id, { qrToken: ride.boardingQrToken }),
        (e) => e.status === 429,
      );
      clock += 16 * 60000;
      assert.equal(
        (await service.get(passenger, ride.id)).ride.billableWaitingSeconds,
        60,
        "Offline pickup waiting stops when GPS coverage expires.",
      );
      await service.samples(acceptedDriver, ride.id, {
        points: [{ ...position(), sequence: 2 }],
      });
      const started = (
        await service.start(acceptedDriver, ride.id, { qrToken: ride.boardingQrToken })
      ).ride;
      assert.equal(started.status, "IN_PROGRESS");
      assert.equal(started.billableWaitingSeconds, 60);
      const raw = await DB.prepare("SELECT * FROM ride_jobs WHERE id=?").get(
        ride.id,
      );
      assert.equal(
        Number(raw.last_latitude),
        9,
        "Meter starts from a verified pickup position.",
      );
    },
  );
  await t.test(
    "GPS retries, jitter, jumps and normal traffic never inflate the fare",
    async () => {
      let sequence = 3;
      await service.samples(acceptedDriver, ride.id, {
        points: [{ ...position(), sequence: sequence++ }],
      });
      clock += 10000;
      const jitter = { ...position(9.0000001, 38.75), sequence: sequence++ };
      await service.samples(acceptedDriver, ride.id, { points: [jitter] });
      await service.samples(acceptedDriver, ride.id, { points: [jitter] });
      assert.equal(
        (await service.get(acceptedDriver, ride.id)).ride.meterDistanceKm,
        0,
      );
      clock += 10000;
      await service.samples(acceptedDriver, ride.id, {
        points: [{ ...position(10, 39), sequence: sequence++ }],
      });
      assert.equal(
        (await service.get(acceptedDriver, ride.id)).ride.meterDistanceKm,
        0,
      );
      clock += 60000;
      await service.samples(acceptedDriver, ride.id, {
        points: [{ ...position(9.005, 38.755), sequence: sequence++ }],
      });
      clock += 60000;
      await service.samples(acceptedDriver, ride.id, {
        points: [{ ...position(9.01, 38.76), sequence: sequence++ }],
      });
      const before = (await service.get(passenger, ride.id)).ride;
      assert.ok(before.meterDistanceKm > 1.5 && before.meterDistanceKm < 1.7);
      clock += 120000;
      const after = (await service.get(passenger, ride.id)).ride;
      assert.equal(
        after.fareEtb,
        before.fareEtb,
        "Normal traffic time is informational only.",
      );
      await assert.rejects(
        service.samples(acceptedDriver, ride.id, {
          points: [{ ...position(), sequence: sequence++, isMock: true }],
        }),
        (e) => e.status === 400,
      );
    },
  );
  await t.test(
    "passenger confirms billable stop and completion captures fixed fee once",
    async () => {
      await service.wait(acceptedDriver, ride.id, { action: "REQUEST_STOP" });
      const before = (await service.get(passenger, ride.id)).ride
        .billableWaitingSeconds;
      clock += 120000;
      await service.samples(acceptedDriver, ride.id, {
        points: [
          {
            ...position(9.01, 38.76),
            sequence:
              (await service.get(passenger, ride.id)).ride.lastSequence + 1,
          },
        ],
      });
      assert.equal(
        (await service.get(passenger, ride.id)).ride.billableWaitingSeconds,
        before,
      );
      await service.stopWait(passenger, ride.id, { approved: true });
      clock += 120000;
      assert.equal(
        (await service.get(passenger, ride.id)).ride.billableWaitingSeconds,
        before + 60,
      );
      await service.wait(acceptedDriver, ride.id, { action: "END_STOP" });
      const done = (
        await service.complete(acceptedDriver, ride.id, position(9.01, 38.76))
      ).ride;
      assert.equal(done.status, "COMPLETED");
      assert.equal(done.settlement, "CAPTURED");
      assert.equal(done.location, undefined);
      await service.complete(acceptedDriver, ride.id, position(9.01, 38.76));
      const w = await DB.prepare(
        "SELECT * FROM mobility_wallets WHERE driver_id=?",
      ).get(acceptedDriver.id);
      assert.equal(Number(w.balance_cents), 99500);
      assert.equal(Number(w.held_cents), 0);
      assert.equal(
        Number(
          (
            await DB.prepare(
              "SELECT COUNT(*) AS count FROM mobility_credit_ledger WHERE event_key=?",
            ).get("ride:capture:" + ride.id)
          ).count,
        ),
        1,
      );
    },
  );
  await t.test(
    "cancellation releases credit and street pickup requires explicit consent",
    async () => {
      await available(acceptedDriver);
      const r = (
        await service.createRequest(passenger, {
          ...route,
          idempotencyKey: "cancel-request",
        })
      ).ride;
      await service.accept(acceptedDriver, r.id, {
        vehicleId: acceptedDriver.id + "vehicle",
      });
      await service.cancel(passenger, r.id, {});
      await service.cancel(passenger, r.id, {});
      const w = await DB.prepare(
        "SELECT * FROM mobility_wallets WHERE driver_id=?",
      ).get(acceptedDriver.id);
      assert.equal(Number(w.held_cents), 0);
      await assert.rejects(
        service.street(acceptedDriver, {
          ...route,
          vehicleId: acceptedDriver.id + "vehicle",
          passengerPhone: "+251900000001",
          idempotencyKey: "street",
        }),
        (e) => e.status === 400,
      );
      const street = (
        await service.street(acceptedDriver, {
          ...route,
          ...position(),
          vehicleId: acceptedDriver.id + "vehicle",
          passengerPhone: "+251900000001",
          idempotencyKey: "street",
          passengerConsent: true,
        })
      ).ride;
      assert.equal(street.source, "STREET");
      assert.equal(street.boardingQrToken, undefined);
      assert.equal(street.status, "IN_PROGRESS");
      await service.resolve(admin, street.id, {
        action: "CANCEL",
        note: "Fixture street cancellation",
      });
    },
  );
  let shared, booking;
  await t.test(
    "shared requests need driver offer and explicit passenger fare acceptance",
    async () => {
      const request = (
        await service.createShared(
          passenger,
          {
            ...route,
            departureAt: new Date(clock + 30 * 60000).toISOString(),
            seats: 2,
            idempotencyKey: "shared-request",
          },
          false,
        )
      ).trip;
      assert.equal(request.status, "REQUESTED");
      assert.equal(request.farePerSeatEtb, null);
      await assert.rejects(
        service.bookShared(passenger, request.id, {
          seatNumbers: [1],
          idempotencyKey: "early",
        }),
        (e) => e.status === 409,
      );
      shared = (
        await service.acceptShared(acceptedDriver, request.id, {
          vehicleId: acceptedDriver.id + "vehicle",
          seats: 4,
          farePerSeatEtb: 80,
        })
      ).trip;
      assert.equal(shared.status, "OPEN");
      assert.equal(
        (await service.sharedTrip(shared.id, passenger)).booking,
        null,
      );
      assert.equal((await service.sharedTrips()).trips.length, 1);
      const publicTrip = (await service.sharedTrip(shared.id)).trip;
      assert.equal(publicTrip.driverPhone, undefined);
      assert.equal(publicTrip.bookings, undefined);
    },
  );
  await t.test(
    "seat races cannot oversell and booked fare survives later setting changes",
    async () => {
      const race = await Promise.allSettled([
        service.bookShared(passenger, shared.id, {
          seatNumbers: [1, 2],
          idempotencyKey: "shared-book-1",
        }),
        service.bookShared(otherPassenger, shared.id, {
          seatNumbers: [2],
          idempotencyKey: "shared-book-2",
        }),
      ]);
      assert.equal(race.filter((r) => r.status === "fulfilled").length, 1);
      const won = race.find((r) => r.status === "fulfilled").value.booking;
      const winner =
        won.passengerId === passenger.id ? passenger : otherPassenger;
      const loser = winner.id === passenger.id ? otherPassenger : passenger;
      booking = won;
      const other = (
        await service.bookShared(loser, shared.id, {
          seatNumbers: [3],
          idempotencyKey: "shared-book-3",
        })
      ).booking;
      const driverTrip = (await service.sharedTrip(shared.id, acceptedDriver))
        .trip;
      assert.equal(
        driverTrip.bookings[0].boardingQrToken,
        undefined,
        "Driver roster cannot expose passenger PINs.",
      );
      const privateOther = (await service.sharedTrip(shared.id, winner)).trip;
      if (privateOther.bookings)
        assert.equal(
          privateOther.bookings.find((b) => b.id === other.id)?.passengerPhone,
          undefined,
          "Passengers cannot read fellow riders contact details.",
        );
      await service.updateSettings(admin.id, { sharedFixedCommissionEtb: 50 });
      assert.equal(
        (await service.sharedTrip(shared.id, winner)).booking.farePerSeatEtb,
        80,
      );
      await service.cancelBooking(loser, other.id);
      assert.ok((await service.sharedTrip(shared.id)).trip.availableSeats >= 2);
    },
  );
  await t.test(
    "shared fee reserves and captures once per vehicle journey",
    async () => {
      const before = await DB.prepare(
        "SELECT * FROM mobility_wallets WHERE driver_id=?",
      ).get(acceptedDriver.id);
      await service.sharedActions(acceptedDriver, shared.id, {
        action: "BOARDING",
      });
      await service.sharedActions(acceptedDriver, shared.id, {
        action: "BOARDING",
      });
      assert.equal(
        Number(
          (
            await DB.prepare(
              "SELECT * FROM mobility_wallets WHERE driver_id=?",
            ).get(acceptedDriver.id)
          ).held_cents,
        ),
        700,
        "Snapshot fee is per journey, not per booking or seat.",
      );
      const owner =
        booking.passengerId === passenger.id ? passenger : otherPassenger;
      const qrToken = (await service.sharedTrip(shared.id, owner)).booking
        .boardingQrToken;
      await service.sharedActions(acceptedDriver, shared.id, {
        action: "BOARD",
        bookingId: booking.id,
        qrToken,
      });
      await service.sharedActions(acceptedDriver, shared.id, {
        action: "START",
        ...position(),
      });
      await service.sharedLocation(
        acceptedDriver,
        shared.id,
        position(9.005, 38.755),
      );
      assert.ok((await service.sharedTrip(shared.id, owner)).trip.location);
      await assert.rejects(
        service.sharedTrip(shared.id, { id: "stranger", role: "PASSENGER" }),
        (e) => e.status === 404,
      );
      await service.sharedActions(acceptedDriver, shared.id, {
        action: "COMPLETE",
        ...position(9.01, 38.76),
      });
      await service.sharedActions(acceptedDriver, shared.id, {
        action: "COMPLETE",
        ...position(9.01, 38.76),
      });
      const after = await DB.prepare(
        "SELECT * FROM mobility_wallets WHERE driver_id=?",
      ).get(acceptedDriver.id);
      assert.equal(
        Number(after.balance_cents),
        Number(before.balance_cents) - 700,
      );
      assert.equal(Number(after.held_cents), 0);
      assert.equal(
        (await service.sharedTrip(shared.id, owner)).trip.location,
        undefined,
      );
    },
  );
  await t.test(
    "completion rejects missing samples and a disconnected beginning of the meter",
    async () => {
      const r = await appTrip("coverage-required");
      await assert.rejects(
        service.complete(drivers[0], r.id, {
          ...position(9.01, 38.76),
          finalSequence: 3,
        }),
        (e) => e.code === "GPS_UPLOAD_REQUIRED",
      );
      await assert.rejects(
        service.complete(drivers[0], r.id, position(9.01, 38.76)),
        (e) => e.code === "METER_REVIEW_REQUIRED",
      );
      clock += 6 * 60000;
      await service.samples(drivers[0], r.id, {
        points: [{ ...position(9.005, 38.755), sequence: 1 }],
      });
      clock += 60000;
      await service.samples(drivers[0], r.id, {
        points: [{ ...position(9.01, 38.76), sequence: 2 }],
      });
      await assert.rejects(
        service.complete(drivers[0], r.id, position(9.01, 38.76)),
        (e) => e.code === "METER_REVIEW_REQUIRED",
      );
      await service.resolve(admin, r.id, {
        action: "CANCEL",
        note: "Fixture missing meter evidence",
      });
    },
  );
  await t.test(
    "moving resumes distance and automatically stops passenger-approved waiting",
    async () => {
      const r = await appTrip("movement-stops-wait");
      await service.wait(drivers[0], r.id, { action: "REQUEST_STOP" });
      await service.stopWait(passenger, r.id, { approved: true });
      clock += 120000;
      await service.samples(drivers[0], r.id, {
        points: [{ ...position(), sequence: 1 }],
      });
      assert.equal(
        (await service.get(passenger, r.id)).ride.billableWaitingSeconds,
        60,
      );
      clock += 10000;
      await service.samples(drivers[0], r.id, {
        points: [{ ...position(9.001, 38.751), sequence: 2 }],
      });
      const moving = (await service.get(passenger, r.id)).ride;
      assert.equal(moving.waiting, null);
      assert.equal(
        moving.billableWaitingSeconds,
        60,
        "The movement interval is not billed as waiting.",
      );
      clock += 10 * 60000;
      assert.equal(
        (await service.get(passenger, r.id)).ride.billableWaitingSeconds,
        60,
      );
      await service.resolve(admin, r.id, {
        action: "CANCEL",
        note: "Fixture waiting meter resolved",
      });
    },
  );
  await t.test(
    "approved stationary stops pause GPS drift distance and disclose free allowance and snapshotted waiting charges",
    async () => {
      await service.updateSettings(admin.id, {
        minimumFareEtb: 10,
        stopGraceMinutes: 1,
        waitingPerMinuteEtb: 2,
      });
      const r = await appTrip("approved-stop-jitter");
      await service.wait(drivers[0], r.id, { action: "REQUEST_STOP" });
      let view = (await service.get(passenger, r.id)).ride;
      assert.equal(view.distancePaused, false);
      assert.equal(view.waitingState, "AWAITING_PASSENGER_APPROVAL");
      assert.equal(view.waitingFreeRemainingSeconds, 0);
      await service.stopWait(passenger, r.id, { approved: true });
      view = (await service.get(passenger, r.id)).ride;
      const before = view.fareEtb;
      assert.equal(view.distancePaused, true);
      assert.equal(view.waitingFreeRemainingSeconds, 60);
      assert.equal(view.waitingRateEtbPerMinute, 2);
      assert.equal(view.waitingState, "FREE_ALLOWANCE");
      clock += 30000;
      await service.samples(drivers[0], r.id, {
        points: [{ ...position(9.00012, 38.75002), sequence: 1 }],
      });
      view = (await service.get(passenger, r.id)).ride;
      assert.equal(view.meterDistanceKm, 0);
      assert.equal(view.fareEtb, before);
      assert.equal(view.waitingFreeRemainingSeconds, 30);
      assert.equal(view.waitingFareEtb, 0);
      const sample = await DB.prepare(
        "SELECT * FROM ride_samples WHERE ride_id=? AND sequence=1",
      ).get(r.id);
      assert.equal(
        Number(sample.latitude),
        9.00012,
        "Raw GPS remains available as evidence.",
      );
      assert.equal(sample.reason, "APPROVED_STOP");
      assert.equal(Number(sample.distance_m), 0);
      await service.updateSettings(admin.id, { waitingPerMinuteEtb: 99 });
      clock += 60000;
      await service.samples(drivers[0], r.id, {
        points: [{ ...position(9.00011, 38.75002), sequence: 2 }],
      });
      view = (await service.get(passenger, r.id)).ride;
      assert.equal(view.distancePaused, true);
      assert.equal(view.meterDistanceKm, 0);
      assert.equal(view.waitingFreeRemainingSeconds, 0);
      assert.equal(view.billableWaitingSeconds, 30);
      assert.equal(view.waitingRateEtbPerMinute, 2);
      assert.equal(view.waitingFareEtb, 1);
      assert.equal(view.fareEtb, before + 1);
      assert.equal(view.waitingState, "BILLABLE_WAITING");
      clock += 5000;
      await service.samples(drivers[0], r.id, {
        points: [{ ...position(10, 39), sequence: 3 }],
      });
      view = (await service.get(passenger, r.id)).ride;
      assert.equal(view.distancePaused, true);
      assert.equal(view.waiting, "STOP");
      assert.equal(
        view.location.latitude,
        9.00011,
        "An impossible jump cannot replace the last trusted tracking point.",
      );
      await service.wait(drivers[0], r.id, { action: "END_STOP" });
      const ended = (await service.get(passenger, r.id)).ride;
      assert.equal(ended.distancePaused, false);
      clock += 5000;
      await service.samples(drivers[0], r.id, {
        points: [{ ...position(9.00011, 38.75002), sequence: 4 }],
      });
      view = (await service.get(passenger, r.id)).ride;
      assert.equal(
        view.meterDistanceKm,
        0,
        "Known stop drift is not charged retrospectively after ending the stop.",
      );
      assert.equal(view.fareEtb, ended.fareEtb);
      clock += 10000;
      await service.samples(drivers[0], r.id, {
        points: [{ ...position(9.001, 38.751), sequence: 5 }],
      });
      assert.ok((await service.get(passenger, r.id)).ride.meterDistanceKm > 0);
      await service.resolve(admin, r.id, {
        action: "CANCEL",
        note: "Fixture stationary stop checked",
      });
      await service.updateSettings(admin.id, {
        minimumFareEtb: 30,
        waitingPerMinuteEtb: 2,
      });
    },
  );
  await t.test(
    "delayed GPS from before stop approval cannot clear a stop or become stationary waiting evidence",
    async () => {
      const r = await appTrip("queued-before-stop"),
        started = clock;
      await service.wait(drivers[0], r.id, { action: "REQUEST_STOP" });
      clock += 10000;
      await service.stopWait(passenger, r.id, { approved: true });
      const before = await DB.prepare("SELECT * FROM ride_jobs WHERE id=?").get(
        r.id,
      );
      clock += 10000;
      await service.samples(drivers[0], r.id, {
        points: [
          {
            ...position(9.001, 38.751),
            timestamp: new Date(started + 5000).toISOString(),
            sequence: 1,
          },
        ],
      });
      const after = await DB.prepare("SELECT * FROM ride_jobs WHERE id=?").get(
        r.id,
      );
      assert.equal(after.waiting_kind, "STOP");
      assert.equal(after.waiting_since, before.waiting_since);
      assert.equal(after.waiting_verified_until, before.waiting_verified_until);
      assert.equal(
        (await service.get(passenger, r.id)).ride.distancePaused,
        true,
      );
      await service.resolve(admin, r.id, {
        action: "CANCEL",
        note: "Fixture queued pre-stop point checked",
      });
    },
  );
  await t.test(
    "fractional-minute allowances produce whole billable seconds and GPS gaps pause the displayed countdown",
    async () => {
      await service.updateSettings(admin.id, { stopGraceMinutes: 0.025 });
      const r = await appTrip("fractional-stop-grace");
      await service.wait(drivers[0], r.id, { action: "REQUEST_STOP" });
      await service.stopWait(passenger, r.id, { approved: true });
      assert.equal(
        (await service.get(passenger, r.id)).ride.waitingFreeRemainingSeconds,
        2,
      );
      clock += 1000;
      assert.equal(
        (await service.get(passenger, r.id)).ride.waitingFreeRemainingSeconds,
        1,
      );
      clock += 2000;
      let view = (await service.get(passenger, r.id)).ride;
      assert.equal(view.billableWaitingSeconds, 1);
      assert.equal(Number.isInteger(view.billableWaitingSeconds), true);
      clock += 10 * 60000;
      view = (await service.get(passenger, r.id)).ride;
      assert.equal(view.waitingState, "GPS_PAUSED");
      assert.equal(view.billableWaitingSeconds, 118);
      await service.stopWait(passenger, r.id, { approved: false });
      assert.equal(
        (await service.get(passenger, r.id)).ride.distancePaused,
        false,
      );
      await service.resolve(admin, r.id, {
        action: "CANCEL",
        note: "Fixture fractional allowance checked",
      });
      await service.updateSettings(admin.id, { stopGraceMinutes: 1 });
    },
  );
  await t.test(
    "future shared seats require funded credit and release/re-hold with an auditable version",
    async () => {
      const driver = drivers[0],
        v = driver.id + "vehicle",
        before = await DB.prepare(
          "SELECT * FROM mobility_wallets WHERE driver_id=?",
        ).get(driver.id);
      const offered = (
        await service.createShared(
          driver,
          {
            ...route,
            departureAt: new Date(clock + 30 * 60000).toISOString(),
            seats: 3,
            farePerSeatEtb: 80,
            vehicleId: v,
            idempotencyKey: "funded-shared",
          },
          true,
        )
      ).trip;
      await DB.prepare(
        "UPDATE mobility_wallets SET balance_cents=1000 WHERE driver_id=?",
      ).run(driver.id);
      await assert.rejects(
        service.bookShared(passenger, offered.id, {
          seatNumbers: [1],
          idempotencyKey: "funding-short",
        }),
        (e) => e.code === "DRIVER_CREDIT_REQUIRED",
      );
      assert.equal(
        (await service.sharedTrip(offered.id)).occupiedSeats.length,
        0,
      );
      await DB.prepare(
        "UPDATE mobility_wallets SET balance_cents=? WHERE driver_id=?",
      ).run(before.balance_cents, driver.id);
      const first = (
        await service.bookShared(passenger, offered.id, {
          seatNumbers: [1],
          idempotencyKey: "funded-seat-1",
        })
      ).booking;
      assert.equal(
        Number(
          (
            await DB.prepare(
              "SELECT held_cents FROM mobility_wallets WHERE driver_id=?",
            ).get(driver.id)
          ).held_cents,
        ),
        5000,
      );
      await service.cancelBooking(passenger, first.id);
      assert.equal(
        Number(
          (
            await DB.prepare(
              "SELECT held_cents FROM mobility_wallets WHERE driver_id=?",
            ).get(driver.id)
          ).held_cents,
        ),
        0,
      );
      const second = (
        await service.bookShared(passenger, offered.id, {
          seatNumbers: [1],
          idempotencyKey: "funded-seat-2",
        })
      ).booking;
      assert.equal(
        Number(
          (
            await DB.prepare(
              "SELECT held_cents FROM mobility_wallets WHERE driver_id=?",
            ).get(driver.id)
          ).held_cents,
        ),
        5000,
      );
      await service.cancelBooking(passenger, second.id);
      assert.equal(
        Number(
          (
            await DB.prepare(
              "SELECT COUNT(*) AS count FROM mobility_credit_ledger WHERE request_id=? AND kind='HOLD'",
            ).get(offered.id)
          ).count,
        ),
        2,
      );
      await service.sharedActions(driver, offered.id, { action: "CANCEL" });
    },
  );
  await t.test(
    "shared no-shows release seats after grace and revocation does not trap an active journey",
    async () => {
      const driver = drivers[0],
        v = driver.id + "vehicle";
      const offered = (
        await service.createShared(
          driver,
          {
            ...route,
            departureAt: new Date(clock + 2 * 60000).toISOString(),
            seats: 3,
            farePerSeatEtb: 80,
            vehicleId: v,
            idempotencyKey: "no-show-shared",
          },
          true,
        )
      ).trip;
      const a = (
        await service.bookShared(passenger, offered.id, {
          seatNumbers: [1],
          idempotencyKey: "boarded-seat",
        })
      ).booking;
      const b = (
        await service.bookShared(otherPassenger, offered.id, {
          seatNumbers: [2],
          idempotencyKey: "no-show-seat",
        })
      ).booking;
      await service.sharedActions(driver, offered.id, { action: "BOARDING" });
      await service.sharedActions(driver, offered.id, {
        action: "BOARD",
        bookingId: a.id,
        qrToken: a.boardingQrToken,
      });
      await assert.rejects(
        service.sharedActions(driver, offered.id, {
          action: "NO_SHOW",
          bookingId: b.id,
        }),
        (e) => e.status === 409,
      );
      clock += 13 * 60000;
      await service.sharedActions(driver, offered.id, {
        action: "NO_SHOW",
        bookingId: b.id,
      });
      assert.equal(
        (await service.sharedTrip(offered.id, otherPassenger)).booking.status,
        "CANCELLED",
      );
      await service.sharedActions(driver, offered.id, {
        action: "START",
        ...position(),
      });
      await service.updateEligibility(admin.id, driver.id, {
        approved: false,
        vehicles: [{ vehicleId: v, approved: false, tier: "ECONOMY" }],
      });
      await service.sharedActions(driver, offered.id, {
        action: "COMPLETE",
        ...position(9.01, 38.76),
      });
      assert.equal(
        (await service.sharedTrip(offered.id, passenger)).trip.status,
        "COMPLETED",
      );
      await service.updateEligibility(admin.id, driver.id, {
        approved: true,
        vehicles: [{ vehicleId: v, approved: true, tier: "ECONOMY" }],
      });
    },
  );
  await t.test(
    "overlapping future shared offers are rejected even before boarding",
    async () => {
      const driver = drivers[0],
        input = {
          ...route,
          departureAt: new Date(clock + 30 * 60000).toISOString(),
          seats: 3,
          farePerSeatEtb: 80,
          vehicleId: driver.id + "vehicle",
        };
      const first = (
        await service.createShared(
          driver,
          { ...input, idempotencyKey: "overlap-first" },
          true,
        )
      ).trip;
      await assert.rejects(
        service.createShared(
          driver,
          {
            ...input,
            departureAt: new Date(clock + 50 * 60000).toISOString(),
            idempotencyKey: "overlap-second",
          },
          true,
        ),
        (e) => e.code === "SCHEDULE_CONFLICT",
      );
      await service.sharedActions(driver, first.id, { action: "CANCEL" });
    },
  );
  await t.test(
    "driver duties on another vehicle and the following local day block taxi acceptance",
    async () => {
      const driver = drivers[0],
        at = new Date(clock).toISOString();
      await DB.prepare(
        "INSERT INTO routes(id,name,nameAm,morningDeparture,eveningDeparture,active) VALUES('ride-duty-route','Fixture Duty','Fixture Duty','06:30','17:30',?)",
      ).run(DB.isPostgres ? true : 1);
      await DB.prepare(
        "INSERT INTO vehicles(id,plateNumber,model,vehicleType,capacityLimit,driverId,assignedRouteId,status) VALUES('ride-duty-vehicle','FIXTURE-DUTY','Fixture duty','MINIVAN_8',7,?,'ride-duty-route','IN_SERVICE')",
      ).run(driver.id);
      clock = Date.parse("2026-11-16T03:00:00Z");
      await assert.rejects(
        service.street(driver, {
          ...route,
          ...position(),
          vehicleId: driver.id + "vehicle",
          passengerPhone: "+251900000001",
          idempotencyKey: "other-vehicle-duty",
          passengerConsent: true,
        }),
        (e) => e.code === "SCHEDULE_CONFLICT",
      );
      clock = Date.parse("2026-11-16T20:50:00Z");
      const longService = createRidesService({
        db: DB,
        now: () => clock,
        geo: {
          route: async () => ({
            distanceKm: 100,
            durationMinutes: 180,
            geometry: null,
          }),
        },
        secret: "fixture-long-service",
      });
      await assert.rejects(
        longService.street(driver, {
          ...route,
          ...position(),
          vehicleId: driver.id + "vehicle",
          passengerPhone: "+251900000001",
          idempotencyKey: "next-day-duty",
          passengerConsent: true,
        }),
        (e) => e.code === "SCHEDULE_CONFLICT",
      );
      await DB.prepare(
        "DELETE FROM vehicles WHERE id='ride-duty-vehicle'",
      ).run();
      await DB.prepare("DELETE FROM routes WHERE id='ride-duty-route'").run();
    },
  );
  await t.test(
    "a stale principal cannot recreate requests, seats or live GPS after account closure",
    async () => {
      const closed = { id: "ride-closed-passenger", role: "PASSENGER" };
      await DB.prepare(
        "INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES(?,'PASSENGER','Closed fixture','+251900004444','unused','SUSPENDED')",
      ).run(closed.id);
      await assert.rejects(
        service.createRequest(closed, {
          ...route,
          idempotencyKey: "closed-request",
        }),
        (e) => e.status === 401,
      );
      await assert.rejects(
        service.createShared(
          closed,
          {
            ...route,
            departureAt: new Date(clock + 30 * 60000).toISOString(),
            seats: 1,
            idempotencyKey: "closed-shared",
          },
          false,
        ),
        (e) => e.status === 401,
      );
      const offered = (
        await service.createShared(
          drivers[0],
          {
            ...route,
            departureAt: new Date(clock + 30 * 60000).toISOString(),
            seats: 3,
            farePerSeatEtb: 80,
            vehicleId: drivers[0].id + "vehicle",
            idempotencyKey: "closed-booking-offer",
          },
          true,
        )
      ).trip;
      await assert.rejects(
        service.bookShared(closed, offered.id, {
          seatNumbers: [1],
          idempotencyKey: "closed-booking",
        }),
        (e) => e.status === 401,
      );
      assert.equal(
        (await service.sharedTrip(offered.id)).occupiedSeats.length,
        0,
      );
      await service.sharedActions(drivers[0], offered.id, { action: "CANCEL" });
      await DB.prepare("UPDATE users SET status='SUSPENDED' WHERE id=?").run(
        drivers[1].id,
      );
      await assert.rejects(
        service.availability(drivers[1], {
          online: true,
          vehicleId: drivers[1].id + "vehicle",
          ...position(),
        }),
        (e) => e.status === 401,
      );
      await DB.prepare("UPDATE users SET status='ACTIVE' WHERE id=?").run(
        drivers[1].id,
      );
    },
  );
  await t.test(
    "HTTP routes preserve public estimates, authenticated booking and admin isolation",
    async () => {
      const express = require("express"),
        http = require("node:http");
      const { signToken } = require("../src/middleware/auth");
      const { createRidesRouter } = require("../src/routes/rides");
      const app = express();
      app.use(express.json());
      app.use("/api/rides", createRidesRouter({ service }));
      app.use((err, _req, res, _next) =>
        res.status(500).json({ success: false, error: err.message }),
      );
      const server = http.createServer(app);
      await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
      try {
        const url = "http://127.0.0.1:" + server.address().port + "/api/rides";
        const request = async (path, method = "GET", body, who) => {
          const response = await fetch(url + path, {
            method,
            headers: {
              "Content-Type": "application/json",
              ...(who ? { Authorization: "Bearer " + signToken(who) } : {}),
            },
            body: body ? JSON.stringify(body) : undefined,
          });
          return { status: response.status, body: await response.json() };
        };
        assert.equal((await request("/catalog")).status, 200);
        assert.equal((await request("/estimate", "POST", route)).status, 200);
        assert.equal(
          (
            await request("/requests", "POST", {
              ...route,
              idempotencyKey: "anonymous-block",
            })
          ).status,
          401,
        );
        assert.equal(
          (await request("/admin/settings", "GET", undefined, passenger))
            .status,
          403,
        );
        assert.equal(
          (await request("/admin/settings", "GET", undefined, admin)).status,
          200,
        );
        const created = await request(
          "/requests",
          "POST",
          { ...route, idempotencyKey: "http-request" },
          passenger,
        );
        assert.equal(created.status, 201);
        assert.equal(created.body.ride.boardingQrToken, undefined, "A QR is issued only after a driver accepts.");
        assert.equal(
          (
            await request(
              "/requests/" + created.body.ride.id,
              "GET",
              undefined,
              otherPassenger,
            )
          ).status,
          404,
        );
        assert.equal(
          (
            await request(
              "/requests/" + created.body.ride.id + "/cancel",
              "POST",
              {},
              passenger,
            )
          ).status,
          200,
        );
      } finally {
        await new Promise((resolve) => server.close(resolve));
      }
    },
  );
  await t.test(
    "early shared boarding and delayed departure both respect actual commute occupancy windows",
    async () => {
      const driver = drivers[0],
        v = driver.id + "vehicle";
      await DB.prepare(
        "INSERT INTO routes(id,name,nameAm,morningDeparture,eveningDeparture,active) VALUES('ride-actual-duty','Fixture actual duty','Fixture actual duty','06:25','23:59',?)",
      ).run(DB.isPostgres ? true : 1);
      await DB.prepare(
        "UPDATE vehicles SET assignedRouteId='ride-actual-duty' WHERE id=?",
      ).run(v);
      clock = Date.parse("2026-11-17T03:10:00Z");
      const early = (
        await service.createShared(
          driver,
          {
            ...route,
            departureAt: "2026-11-17T04:20:00Z",
            seats: 3,
            farePerSeatEtb: 80,
            vehicleId: v,
            idempotencyKey: "early-boarding-duty",
          },
          true,
        )
      ).trip;
      await service.bookShared(passenger, early.id, {
        seatNumbers: [1],
        idempotencyKey: "early-boarding-seat",
      });
      clock = Date.parse("2026-11-17T03:20:00Z");
      await assert.rejects(
        service.sharedActions(driver, early.id, { action: "BOARDING" }),
        (e) => e.code === "SCHEDULE_CONFLICT",
      );
      await service.sharedActions(driver, early.id, { action: "CANCEL" });
      await DB.prepare(
        "UPDATE routes SET morningDeparture='00:00',eveningDeparture='08:00' WHERE id='ride-actual-duty'",
      ).run();
      const delayed = (
        await service.createShared(
          driver,
          {
            ...route,
            departureAt: "2026-11-17T03:30:00Z",
            seats: 3,
            farePerSeatEtb: 80,
            vehicleId: v,
            idempotencyKey: "delayed-start-duty",
          },
          true,
        )
      ).trip;
      const b = (
        await service.bookShared(passenger, delayed.id, {
          seatNumbers: [1],
          idempotencyKey: "delayed-boarding-seat",
        })
      ).booking;
      await service.sharedActions(driver, delayed.id, { action: "BOARDING" });
      await service.sharedActions(driver, delayed.id, {
        action: "BOARD",
        bookingId: b.id,
        qrToken: b.boardingQrToken,
      });
      clock = Date.parse("2026-11-17T04:20:00Z");
      await assert.rejects(
        service.sharedActions(driver, delayed.id, {
          action: "START",
          ...position(),
        }),
        (e) => e.code === "SCHEDULE_CONFLICT",
      );
      await service.resolveShared(admin, delayed.id, {
        action: "CANCEL",
        note: "Fixture missed shared departure conflicts with duty",
      });
      await DB.prepare(
        "UPDATE vehicles SET assignedRouteId=NULL WHERE id=?",
      ).run(v);
      await DB.prepare("DELETE FROM routes WHERE id='ride-actual-duty'").run();
    },
  );
  await t.test(
    "an intercity journey remains exclusive even hours after scheduled departure",
    async () => {
      await require("../src/services/intercity-schema").initIntercitySchema();
      const driver = drivers[0],
        v = driver.id + "vehicle";
      await DB.prepare(
        "INSERT INTO intercity_departures(id,company_id,route_id,driver_id,vehicle_id,origin,destination,departure_at,latitude,longitude,seats,fare_etb,status,created_at) VALUES('ride-intercity-busy','fixture-company','fixture-route',?,?,'Fixture start','Fixture end',?,9,38.75,7,80,'IN_TRANSIT',?)",
      ).run(
        driver.id,
        v,
        new Date(clock - 5 * 3600000).toISOString(),
        new Date(clock - 5 * 3600000).toISOString(),
      );
      await assert.rejects(available(driver), (e) => e.status === 409);
      await assert.rejects(
        service.street(driver, {
          ...route,
          ...position(),
          vehicleId: v,
          passengerPhone: "+251900000001",
          idempotencyKey: "intercity-active-street",
          passengerConsent: true,
        }),
        (e) => e.status === 409,
      );
      await DB.prepare(
        "UPDATE intercity_departures SET status='COMPLETED' WHERE id='ride-intercity-busy'",
      ).run();
      await available(driver);
    },
  );
  await t.test(
    "unstarted departures expire, release held fee and retire terminal PIN and raw GPS evidence",
    async () => {
      const driver = drivers[0],
        v = driver.id + "vehicle";
      const requested = (
        await service.createShared(
          passenger,
          {
            ...route,
            departureAt: new Date(clock + 2 * 60000).toISOString(),
            seats: 1,
            idempotencyKey: "cancel-own-pending",
          },
          false,
        )
      ).trip;
      assert.equal(
        (await service.cancelSharedRequest(passenger, requested.id)).trip
          .status,
        "CANCELLED",
      );
      const offer = (
        await service.createShared(
          driver,
          {
            ...route,
            departureAt: new Date(clock + 2 * 60000).toISOString(),
            seats: 3,
            farePerSeatEtb: 80,
            vehicleId: v,
            idempotencyKey: "expire-unstarted",
          },
          true,
        )
      ).trip;
      await service.bookShared(passenger, offer.id, {
        seatNumbers: [1],
        idempotencyKey: "expire-held-seat",
      });
      assert.equal(
        Number(
          (
            await DB.prepare(
              "SELECT held_cents FROM mobility_wallets WHERE driver_id=?",
            ).get(driver.id)
          ).held_cents,
        ),
        5000,
      );
      clock += 63 * 60000;
      await service.cleanup();
      assert.equal(
        (await service.sharedTrip(offer.id, passenger)).trip.status,
        "CANCELLED",
      );
      assert.equal(
        Number(
          (
            await DB.prepare(
              "SELECT held_cents FROM mobility_wallets WHERE driver_id=?",
            ).get(driver.id)
          ).held_cents,
        ),
        0,
      );
      assert.equal(
        Number(
          (
            await DB.prepare(
              "SELECT COUNT(*) AS count FROM ride_shared_bookings WHERE status IN ('COMPLETED','CANCELLED') AND pin_cipher<>''",
            ).get()
          ).count,
        ),
        0,
      );
      const oldRideCount = Number(
        (await DB.prepare("SELECT COUNT(*) AS count FROM ride_jobs").get())
          .count,
      );
      clock = Math.max(clock, Date.now()) + 31 * 86400000;
      await service.cleanup();
      const gpsCutoff = new Date(clock - 30 * 86400000).toISOString();
      assert.equal(
        Number(
          (
            await DB.prepare(
              "SELECT COUNT(*) AS count FROM ride_samples s JOIN ride_jobs r ON r.id=s.ride_id WHERE r.idempotency_owner IN ('ride-passenger','ride-driver','ride-driver-2') AND r.status IN ('COMPLETED','CANCELLED') AND r.completed_at<?",
            ).get(gpsCutoff)
          ).count,
        ),
        0,
      );
      assert.equal(
        Number(
          (await DB.prepare("SELECT COUNT(*) AS count FROM ride_jobs").get())
            .count,
        ),
        oldRideCount,
        "Fare and journey audit records remain after raw GPS retirement.",
      );
    },
  );
  await t.test(
    "nearby car presence is approximate, anonymous, fresh, approved and excludes busy drivers",
    async () => {
      const driver = drivers[0];
      await service.availability(driver, {
        online: true,
        vehicleId: driver.id + "vehicle",
        ...position(9.00031, 38.75071),
      });
      await service.availability(drivers[1], {
        online: true,
        vehicleId: drivers[1].id + "vehicle",
        ...position(13, 40),
      });
      const query = { latitude: 9, longitude: 38.75, tier: "ECONOMY" },
        near = await service.nearby(query);
      assert.equal(near.approximate, true);
      assert.equal(near.vehicles.length, 1);
      assert.deepEqual(Object.keys(near.vehicles[0]).sort(), [
        "latitude",
        "longitude",
        "tier",
      ]);
      assert.equal(near.vehicles[0].latitude, 9);
      assert.equal(near.vehicles[0].longitude, 38.75);
      assert.notEqual(near.vehicles[0].longitude, 38.75071);
      assert.equal(
        (await service.nearby({ ...query, tier: "COMFORT" })).vehicles.length,
        0,
      );
      await DB.prepare("UPDATE users SET status='SUSPENDED' WHERE id=?").run(
        driver.id,
      );
      assert.equal((await service.nearby(query)).vehicles.length, 0);
      await DB.prepare("UPDATE users SET status='ACTIVE' WHERE id=?").run(
        driver.id,
      );
      clock += 3 * 60000;
      assert.equal((await service.nearby(query)).vehicles.length, 0);
      const active = await appTrip("nearby-busy-exclusion", driver);
      await DB.prepare(
        "UPDATE ride_availability SET online=1 WHERE driver_id=?",
      ).run(driver.id);
      assert.equal(
        (await service.nearby(query)).vehicles.length,
        0,
        "A stale online flag never exposes a driver already on a job.",
      );
      await service.resolve(admin, active.id, {
        action: "CANCEL",
        note: "Fixture nearby check complete",
      });
      await service.updateSettings(admin.id, { enabled: false });
      assert.equal((await service.nearby(query)).vehicles.length, 0);
    },
  );
});
