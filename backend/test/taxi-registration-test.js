"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs"),
  os = require("node:os"),
  path = require("node:path");
process.env.NODE_ENV = "test";
process.env.DATA_DIR = fs.mkdtempSync(
  path.join(os.tmpdir(), "passo-taxi-registration-"),
);
process.env.UPLOAD_DIR = path.join(process.env.DATA_DIR, "private-uploads");
process.env.JWT_SECRET = "isolated-taxi-registration-fixture-secret";
process.env.ADMIN_REGISTRATION_SECRET =
  "isolated-taxi-registration-admin-secret";
process.env.PASSO_TEST_OTP_ENABLED = "true";
process.env.PASSO_TEST_OTP_CODE = "123456";
process.env.PASSO_TEST_OTP_PASSENGER_PHONE = "0912423002";
process.env.PASSO_TEST_OTP_DRIVER_PHONE = "0912423001";
process.env.PASSO_TEST_OTP_EXPIRES_AT = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
process.env.PASSO_TEST_OTP_DELIVERY = "AFRO_SMS";
process.env.AFRO_SMS_API_TOKEN = "isolated-test-token";
const networkFetch = global.fetch;
global.fetch = async (input, init) => String(input).startsWith("https://api.afromessage.com/api/send")
  ? { ok: true, status: 200, json: async () => ({ acknowledge: "success" }) }
  : networkFetch(input, init);
if (process.env.MOBILITY_TEST_POSTGRES === "true") {
  if (process.env.CI !== "true" || !process.env.DATABASE_URL)
    throw Error("Disposable CI PostgreSQL required.");
} else delete process.env.DATABASE_URL;
const { DB } = require("../src/db");
const {
  createOnboardingService,
  initOnboardingSchema,
} = require("../src/services/onboarding");
const { createRidesService } = require("../src/services/rides");
const {
  dailyDriverEligible,
  assertDailyDriverEligible,
} = require("../src/services/operational-onboarding");
test("Meter Taxi registration uploads documents before final approval and remains outside daily commute assignments", async (t) => {
  await initOnboardingSchema();
  await require("../src/services/master-data").initMasterDataSchema();
  await DB.prepare("CREATE TABLE IF NOT EXISTS auth_otps (id TEXT PRIMARY KEY, phone TEXT NOT NULL, purpose TEXT NOT NULL, code_hash TEXT NOT NULL, verification_token_hash TEXT, expires_at TIMESTAMP NOT NULL, verified_at TIMESTAMP, attempts INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)").run();
  await DB.prepare(
    "INSERT INTO vehicle_types(code,name,capacity,active) VALUES('TAXI_SEDAN','Fixture sedan',4,TRUE) ON CONFLICT(code) DO NOTHING",
  ).run();
  await DB.prepare(
    "INSERT INTO users(id,role,fullName,phone,passwordHash,status) VALUES('taxi-registration-admin','ADMIN','Fixture reviewer','+251900023001','unused','ACTIVE') ON CONFLICT(id) DO NOTHING",
  ).run();
  const express = require("express"),
    { signToken } = require("../src/middleware/auth");
  const onboarding = createOnboardingService({
      sendSms: async () => ({ sent: false }),
    }),
    rides = createRidesService();
  await rides.init();
  const initialSettings = JSON.parse(
    JSON.stringify((await rides.catalog()).settings),
  );
  const app = express();
  app.use(express.json());
  app.use(
    "/api/onboarding",
    require("../src/routes/onboarding").createOnboardingRouter({
      service: onboarding,
      rateLimitMax: 100,
    }),
  );
  app.use("/api/auth", require("../src/routes/auth"));
  app.use(
    "/api/rides",
    require("../src/routes/rides").createRidesRouter({ service: rides }),
  );
  const server = app.listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  t.after(
    () =>
      new Promise((resolve) => {
        server.closeAllConnections();
        server.close(resolve);
      }),
  );
  const base = "http://127.0.0.1:" + server.address().port,
    admin = signToken({ id: "taxi-registration-admin", role: "ADMIN" });
  const request = async (url, method = "GET", token, body, mime) => {
    const r = await fetch(base + url, {
      method,
      headers: {
        ...(token ? { Authorization: "Bearer " + token } : {}),
        ...(body === undefined
          ? {}
          : { "Content-Type": mime || "application/json" }),
      },
      body:
        body === undefined
          ? undefined
          : Buffer.isBuffer(body)
            ? body
            : JSON.stringify(body),
    });
    return { status: r.status, body: await r.json() };
  };
  const signInWithOtp = async (phone, role) => {
    const sent = await request("/api/auth/request-otp", "POST", null, { phone, purpose: "LOGIN" });
    assert.equal(sent.status, 200, JSON.stringify(sent.body));
    const verified = await request("/api/auth/verify-otp", "POST", null, { phone, purpose: "LOGIN", code: "123456" });
    assert.equal(verified.status, 200, JSON.stringify(verified.body));
    return request("/api/auth/login-otp", "POST", null, { phone, role, verificationToken: verified.body.verificationToken });
  };
  const input = {
    role: "DRIVER",
    serviceType: "TAXI",
    fullName: "Fixture Meter Taxi Driver",
    phone: "0912423001",
    password: "Fixture taxi driver password",
    licenseNumber: "FIXTURE-TAXI-LICENSE",
    assignedVehiclePlate: "FIXTURE-TAXI-01",
    vehicleModel: "Fixture sedan",
    vehicleType: "TAXI_SEDAN",
  };
  let registered, driverToken, vehicleId;
  await t.test(
    "web signup needs vehicle details but no route, monthly quote or OTP",
    async () => {
      const r = await request("/api/onboarding/register", "POST", null, input);
      assert.equal(r.status, 201, JSON.stringify(r.body));
      registered = r.body;
      assert.equal(registered.account.serviceType, "TAXI");
      assert.equal(registered.account.driverServiceType, "TAXI");
      assert.equal(registered.account.status, "PENDING");
      assert.equal(registered.onboarding.canVerifyAndUpload, true);
      assert.equal(registered.onboarding.phoneVerified, false);
      assert.equal(registered.onboarding.initialApplicationComplete, true);
      assert.equal(
        registered.onboarding.routePreference.mode,
        "NOT_APPLICABLE",
      );
      assert.equal(
        registered.onboarding.routePreference.proposedMonthlyFeeEtb,
        null,
      );
      assert.equal(registered.onboarding.routePreference.departure, "");
      const v = await DB.prepare("SELECT * FROM vehicles WHERE driverId=?").get(
        registered.account.id,
      );
      vehicleId = v.id;
      assert.equal(v.assignedRouteId, null);
      assert.equal(v.status, "OFF_DUTY");
      assert.equal(
        (await request("/api/auth/me", "GET", registered.token)).status,
        401,
        "Application credential cannot enter operations.",
      );
      const legacyLogin = await request("/api/auth/login", "POST", null, {
        role: "DRIVER", phone: input.phone, password: input.password,
      });
      assert.equal(legacyLogin.status, 410);
      assert.equal(legacyLogin.body.code, "OTP_REQUIRED");
      assert.equal(
        (
          await request(
            "/api/onboarding/applications/DRIVER/" +
              registered.account.id +
              "/review",
            "POST",
            admin,
            { decision: "APPROVE", note: "Too early" },
          )
        ).status,
        428,
      );
    },
  );
  await t.test(
    "files upload immediately, final document review activates only the registered vehicle",
    async () => {
      const png = Buffer.from(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aB9sAAAAASUVORK5CYII=",
        "base64",
      );
      for (const kind of [
        "LICENSE",
        "VEHICLE_REGISTRATION",
        "INSURANCE",
        "PHOTO",
      ])
        assert.equal(
          (
            await request(
              "/api/onboarding/documents/" + kind,
              "PUT",
              registered.token,
              png,
              "image/png",
            )
          ).status,
          200,
        );
      assert.equal(
        (
          await request("/api/onboarding/profile", "PATCH", registered.token, {
            emergencyContactName: "Fixture Contact",
            emergencyContactPhone: "0912423010",
            vehicleColor: "White",
          })
        ).status,
        200,
      );
      const submitted = await request(
        "/api/onboarding/submit",
        "POST",
        registered.token,
        {},
      );
      assert.equal(submitted.status, 200, JSON.stringify(submitted.body));
      assert.equal(submitted.body.onboarding.phoneVerified, false);
      const review = await request(
        "/api/onboarding/applications/DRIVER/" +
          registered.account.id +
          "/review",
        "POST",
        admin,
        {
          decision: "APPROVE",
          note: "Fixture licence, registration and insurance checked",
        },
      );
      assert.equal(review.status, 200, JSON.stringify(review.body));
      assert.equal(review.body.account.status, "ACTIVE");
      const v = await DB.prepare("SELECT * FROM vehicles WHERE id=?").get(
        vehicleId,
      );
      assert.equal(v.status, "IN_SERVICE");
      assert.equal(v.assignedRouteId, null);
      const login = await signInWithOtp(input.phone, "DRIVER");
      assert.equal(login.status, 200, JSON.stringify(login.body));
      assert.equal(login.body.user.driverServiceType, "TAXI");
      driverToken = login.body.token;
      assert.equal(
        (await request("/api/auth/me", "GET", driverToken)).body.user
          .driverServiceType,
        "TAXI",
      );
    },
  );
  await t.test(
    "taxi onboarding cannot bypass daily allocation or separate taxi/vehicle approval",
    async () => {
      assert.equal(await dailyDriverEligible(registered.account.id), false);
      await assert.rejects(
        assertDailyDriverEligible(registered.account.id),
        (e) => e.code === "DAILY_COMMUTE_DRIVER_REQUIRED",
      );
      const before = await request(
        "/api/rides/driver/dashboard",
        "GET",
        driverToken,
      );
      assert.equal(before.status, 200, JSON.stringify(before.body));
      assert.equal(before.body.eligible, false);
      const approval = await request(
        "/api/rides/admin/eligibility/" + registered.account.id,
        "PUT",
        admin,
        {
          approved: true,
          vehicles: [{ vehicleId, approved: true, tier: "ECONOMY" }],
        },
      );
      assert.equal(approval.status, 200, JSON.stringify(approval.body));
      const after = await request(
        "/api/rides/driver/dashboard",
        "GET",
        driverToken,
      );
      assert.equal(after.status, 200);
      assert.equal(after.body.eligible, true);
      assert.equal(after.body.vehicles[0].id, vehicleId);
      assert.equal(after.body.online, false);
      assert.deepEqual(
        (await request("/api/rides/catalog")).body.settings,
        initialSettings,
        "Real tariffs remain under administrator control.",
      );
    },
  );
  await t.test(
    "native signup shares the TAXI contract and stale commute fields never create a route preference",
    async () => {
      const r = await request("/api/auth/register", "POST", null, {
        ...input,
        phone: "0912423002",
        assignedVehiclePlate: "FIXTURE-TAXI-02",
        proposedDeparture: "Stale commute origin",
        proposedDestination: "Stale commute end",
        proposedMonthlyFeeEtb: 999,
        routeChoice: "PROPOSED",
      });
      assert.equal(r.status, 201, JSON.stringify(r.body));
      assert.equal(r.body.token, null);
      assert.equal(r.body.user.driverServiceType, "TAXI");
      assert.equal(r.body.onboarding.initialApplicationComplete, true);
      assert.equal(r.body.onboarding.routePreference.departure, "");
      assert.equal(
        r.body.onboarding.routePreference.proposedMonthlyFeeEtb,
        null,
      );
      assert.equal(
        (
          await DB.prepare("SELECT appliedRouteId FROM users WHERE id=?").get(
            r.body.user.id,
          )
        ).appliedRouteId,
        "",
      );
    },
  );
  await t.test(
    "a taxi passenger can register without a daily route or invented commuter subscription",
    async () => {
      const r = await request("/api/auth/register", "POST", null, {
        role: "PASSENGER",
        serviceType: "TAXI",
        fullName: "Fixture taxi passenger",
        phone: "0912423003",
        password: "Fixture taxi passenger password",
      });
      assert.equal(r.status, 201, JSON.stringify(r.body));
      assert.ok(r.body.token);
      assert.equal(r.body.subscription, null);
      assert.equal(
        Number(
          (
            await DB.prepare(
              "SELECT COUNT(*) AS count FROM subscriptions WHERE passengerId=?",
            ).get(r.body.user.id)
          ).count,
        ),
        0,
      );
    },
  );
});
