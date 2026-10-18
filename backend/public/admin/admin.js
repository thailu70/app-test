(() => {
  "use strict";
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const TOKEN_KEY = "routepass_admin_token";
  const state = { token: sessionStorage.getItem(TOKEN_KEY) || "", user: null, view: "overview", cache: {} };
  let fleetMapCleanup = null;
  let renderRevision = 0;
  const labels = {
    overview: ["Overview", "A live snapshot of RoutePass service operations."],
    reports: ["Performance reports", "Daily commute performance in Addis Ababa time."],
    drivers: ["Drivers & routes", "Review driver-owned vehicles and assign approved operating routes."],
    gps: ["Driver GPS", "Live and last reported GPS locations for daily commute drivers."],
    companies: ["Company access", "Invite transport companies and issue one-time registration codes."],
    routes: ["Manage routes", "Create, edit, activate and deactivate RoutePass transit routes."],
    vehicles: ["Vehicles", "Fleet availability and current assignments."],
    subscriptions: ["Subscriptions", "Passenger subscription records and payment state."],
    payments: ["Payments", "Transaction history for operational review."],
    checkins: ["Check-ins", "Recent passenger boarding records."],
    complaints: ["Complaints", "Review passenger reports and update case status."],
    "audit-logs": ["Audit log", "Recent administrative and operational changes."],
    settings: ["Settings", "Configure integrations used by RoutePass operations."]
  };

  function esc(value) {
    return String(value ?? "—").replace(/[&<>"']/g, (char) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    })[char]);
  }
  function value(row, ...keys) {
    for (const key of keys) if (row && row[key] !== undefined && row[key] !== null && row[key] !== "") return row[key];
    return "—";
  }
  function asArray(response, key) { return Array.isArray(response?.[key]) ? response[key] : []; }
  function money(number) {
    const parsed = Number(number);
    return Number.isFinite(parsed) ? "ETB " + parsed.toLocaleString("en-ET", { maximumFractionDigits: 2 }) : "ETB 0";
  }
  function dateText(v) {
    if (!v || v === "—") return "—";
    const d = new Date(v);
    return Number.isNaN(d.getTime()) ? String(v) : d.toLocaleString();
  }
  function pill(status) {
    const text = String(status ?? "UNKNOWN");
    const cls = text.toLowerCase().replace(/[^a-z0-9_-]/g, "_");
    return '<span class="pill ' + esc(cls) + '">' + esc(text.replace(/_/g, " ")) + "</span>";
  }
  async function api(path, options = {}) {
    const headers = { Accept: "application/json", ...(options.headers || {}) };
    if (state.token) headers.Authorization = "Bearer " + state.token;
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(path, { ...options, headers, credentials: "same-origin", cache: "no-store" });
    let body = {};
    try { body = await response.json(); } catch (_) {}
    if (!response.ok) {
      if (response.status === 401 && state.token) logout(false);
      throw new Error(body.error || body.message || ("Request failed (" + response.status + ")"));
    }
    return body;
  }
  function showLogin(error = "") {
    $("#login-view").hidden = false;
    $("#app-view").hidden = true;
    $("#login-error").hidden = !error;
    $("#login-error").textContent = error;
  }
  function showApp() {
    $("#login-view").hidden = true;
    $("#app-view").hidden = false;
    $("#admin-name").textContent = state.user?.fullName || state.user?.phone || "Administrator";
    $("#avatar").textContent = (state.user?.fullName || "A").slice(0, 1).toUpperCase();
  }
  function toast(message, isError = false) {
    const el = $("#toast");
    el.textContent = message;
    el.classList.toggle("error", isError);
    el.hidden = false;
    window.clearTimeout(toast.timer);
    toast.timer = window.setTimeout(() => { el.hidden = true; }, 4500);
  }
  function logout(show = true) {
    fleetMapCleanup?.();
    fleetMapCleanup = null;
    renderRevision++;
    sessionStorage.removeItem(TOKEN_KEY);
    state.token = "";
    state.user = null;
    state.cache = {};
    if (show) showLogin();
    else showLogin("Your session expired. Sign in again.");
  }
  function table(headers, rows, empty = "No records found.") {
    const head = "<thead><tr>" + headers.map(h => "<th>" + esc(h.label) + "</th>").join("") + "</tr></thead>";
    const body = rows.length
      ? "<tbody>" + rows.map(row => "<tr>" + headers.map(h => "<td>" + (h.render ? h.render(row) : esc(value(row, ...(h.keys || [h.key])))) + "</td>").join("") + "</tr>").join("") + "</tbody>"
      : '<tbody><tr><td class="empty-cell" colspan="' + headers.length + '">' + esc(empty) + "</td></tr></tbody>";
    return '<div class="table-wrap"><table>' + head + body + "</table></div>";
  }
  function section(title, description, body, action = "") {
    return '<section class="card section-card"><div class="section-title"><div><h3>' + esc(title) + '</h3><p>' + esc(description) + "</p></div>" + action + "</div>" + body + "</section>";
  }
  function pageHeader(title, description, action = "") {
    return '<div class="page-header"><div><h2>' + esc(title) + '</h2><p>' + esc(description) + "</p></div>" + action + "</div>";
  }
  function statCard(title, number, sub) {
    return '<div class="card stat-card"><div class="stat-label">' + esc(title) + '</div><div class="stat-value">' + esc(number) + '</div><div class="stat-foot">' + esc(sub) + "</div></div>";
  }
  async function loadLookups() {
    const [routeResult, vehicleResult] = await Promise.all([api("/api/admin/routes"), api("/api/vehicles")]);
    state.cache.routes = asArray(routeResult, "routes");
    state.cache.vehicles = asArray(vehicleResult, "vehicles");
  }
  async function renderOverview() {
    const result = await api("/api/admin/stats");
    const s = result.stats || {};
    const drivers = asArray(await api("/api/admin/drivers"), "drivers");
    return pageHeader("Operations at a glance", "Use the left menu to manage the fleet and operations.", '<button class="btn btn-secondary" data-go="gps">View driver GPS</button>') +
      '<div class="stats-grid">' +
      statCard("Passengers", value(s, "totalPassengers"), "Registered commuter accounts") +
      statCard("Drivers", value(s, "totalDrivers"), "Provisioned driver accounts") +
      statCard("Active routes", value(s, "activeRoutes"), "Routes available for service") +
      statCard("Available fleet", value(s, "activeVehicles"), "Vehicles not marked for maintenance") +
      statCard("Active subscriptions", value(s, "activeSubscriptions"), "Currently active commuter passes") +
      statCard("Recorded revenue", money(s.totalRevenueEtb), "Completed transaction records only") +
      statCard("Boardings recorded", value(s, "todayCheckins"), "Check-in records marked boarded") +
      statCard("Open complaints", value(s, "openComplaints"), "Items needing attention") +
      "</div>" +
      section("Recently created driver accounts", "Latest driver records currently on the server.",
        table([
          { label: "Driver", render: r => "<strong>" + esc(value(r, "fullName", "full_name")) + "</strong>" },
          { label: "Phone / username", keys: ["phone"] },
          { label: "Vehicle", keys: ["assignedVehiclePlate", "assigned_vehicle_plate"] },
          { label: "Route", keys: ["appliedRouteName", "applied_route_name"] },
          { label: "Status", render: r => pill(value(r, "status")) }
        ], drivers.slice(0, 5)),
        '<button class="btn btn-secondary" data-go="drivers">View all drivers</button>');
  }
  async function renderSettings() {
    const result = await api("/api/admin/integrations/afro-sms");
    const configured = result.configured || {};
    return pageHeader("Settings", "Manage the SMS provider used for passenger arrival notifications.") +
      section("Afro SMS", "Enter the AfroMessage token and Identifier ID. The token is stored securely and is never displayed after saving.",
        '<form id="afro-sms-form" class="form-card" style="padding:0;border:0;box-shadow:none">' +
        '<div class="driver-form-grid">' +
        '<div class="field"><label for="afro-sms-token">API token</label><input id="afro-sms-token" name="apiToken" type="password" autocomplete="new-password" placeholder="Paste Afro SMS API token" required><span class="hint">' + (configured.AFRO_SMS_API_TOKEN ? 'A token is already saved. Enter a new token only when rotating it.' : 'Required before RoutePass can send SMS notifications.') + '</span></div>' +
        '<div class="field"><label for="afro-sms-identifier">Identifier ID (from, optional)</label><input id="afro-sms-identifier" name="identifier" maxlength="120" placeholder="Leave blank to use your default identifier"><span class="hint">Use an Identifier ID only when your AfroMessage account has multiple short codes. Otherwise leave it blank.</span></div>' +
        '<div class="field"><label for="afro-sms-sender">Approved sender name (optional)</label><input id="afro-sms-sender" name="sender" maxlength="40" value="" placeholder="Leave blank for AfroMessage default"><span class="hint">Only enter a sender approved for this Identifier ID. Leave blank to use AfroMessage’s default sender.</span></div>' +
        '<div class="field"><label for="afro-sms-url">API URL</label><input id="afro-sms-url" name="url" type="url" value="https://api.afromessage.com/api/send"><span class="hint">Leave the default Afro SMS endpoint unless your account uses another URL.</span></div>' +
        '</div><div class="form-actions"><button class="btn btn-primary" type="submit">Save Afro SMS settings</button></div><div class="driver-form-grid" style="margin-top:16px"><div class="field"><label for="afro-sms-test-phone">Test phone number</label><input id="afro-sms-test-phone" type="tel" placeholder="09XXXXXXXX"></div><div class="field"><label for="afro-sms-test-message">Test message</label><input id="afro-sms-test-message" maxlength="320" value="RoutePass test SMS: Afro SMS is connected."></div></div><div class="form-actions"><button class="btn btn-secondary" type="button" id="afro-sms-test">Send test message</button></div><p id="afro-sms-message" class="form-message" hidden></p></form>') ;
  }
  async function saveAfroSms(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const button = form.querySelector('button[type="submit"]');
    const message = $("#afro-sms-message");
    button.disabled = true;
    message.hidden = true;
    try {
      const data = Object.fromEntries(new FormData(form).entries());
      await api("/api/admin/integrations/afro-sms", { method: "PUT", body: JSON.stringify(data) });
      form.reset();
      $("#afro-sms-sender").value = "";
      $("#afro-sms-url").value = "https://api.afromessage.com/api/send";
      message.textContent = "Afro SMS settings saved securely.";
      message.className = "form-message";
      message.hidden = false;
      toast("Afro SMS settings saved.");
    } catch (error) {
      message.textContent = error.message;
      message.className = "form-message error";
      message.hidden = false;
    } finally { button.disabled = false; }
  }
  async function renderDrivers() {
    await loadLookups();
    const data = asArray(await api("/api/admin/drivers"), "drivers");
    const routes = (state.cache.routes || []).filter(r => r.active !== false && r.active !== 0);
    return pageHeader("Driver approval & routes", "Review new driver registrations, approve verified drivers, then assign their operating route. Drivers retain ownership of their vehicles.",
      '<button class="btn btn-secondary" data-go="routes">Manage routes</button>') +
      section("Registered drivers", data.length + " account(s)",
        table([
          { label: "Driver", render: r => "<strong>" + esc(value(r, "fullName", "full_name")) + "</strong>" },
          { label: "Phone / username", keys: ["phone"] },
          { label: "Company", keys: ["companyName", "company_name"] },
          { label: "Licence", keys: ["licenseNumber", "license_number"] },
          { label: "Owner vehicle", keys: ["assignedVehiclePlate", "assigned_vehicle_plate"] },
          { label: "Approval", render: r => {
            const id = esc(value(r, "id"));
            const status = String(r.status || "").toUpperCase();
            if (status === "PENDING") return '<div class="inline-actions"><button class="btn btn-primary" data-driver-id="' + id + '" data-driver-approval="ACTIVE">Approve</button><button class="btn btn-secondary" data-driver-id="' + id + '" data-driver-approval="SUSPENDED">Reject</button></div>';
            if (status === "SUSPENDED") return '<div class="inline-actions">' + pill(status) + '<button class="btn btn-secondary" data-driver-id="' + id + '" data-driver-approval="ACTIVE">Approve</button></div>';
            return pill(status);
          }},
          { label: "Assigned route", keys: ["appliedRouteName", "applied_route_name"] },
          { label: "Route action", render: r => {
            const id = esc(value(r, "id"));
            if (String(r.status || "").toUpperCase() !== "ACTIVE") return '<span class="muted">Approve driver first</span>';
            const selected = value(r, "appliedRouteId", "applied_route_id");
            const options = '<option value="">Choose route…</option>' + routes.map(route =>
              '<option value="' + esc(route.id) + '" ' + (route.id === selected ? "selected" : "") + '>' + esc(value(route, "name")) + '</option>'
            ).join("");
            return '<div class="inline-actions"><select data-route-for="' + id + '" aria-label="Route for ' + esc(value(r, "fullName")) + '" style="min-width:170px;padding:7px;border:1px solid #d5deea;border-radius:8px">' + options + '</select><button class="btn btn-primary" data-assign-route="' + id + '">Assign</button></div>';
          }}
        ], data));
  }

  async function renderRoutes() {
    const result = await api("/api/admin/routes");
    const routes = asArray(result, "routes");
    state.cache.routes = routes;
    return pageHeader("Manage routes", "Create a route, update its timetable and tariff, or deactivate it without deleting history.") +
      section("Create or edit a route", "Route changes are reviewed here before saving. New routes are active by default.",
        '<form id="create-route-form" class="form-card"><div class="driver-form-grid">' +
        '<div class="field"><label for="route-name">Route name (English) *</label><input id="route-name" name="name" required maxlength="120" placeholder="Bole - Merkato Express"></div>' +
        '<div class="field"><label for="route-name-am">Route name (Amharic) *</label><input id="route-name-am" name="nameAm" required maxlength="120" placeholder="ቦሌ - መርካቶ"></div>' +
        '<div class="field"><label for="route-description">Description</label><input id="route-description" name="description" maxlength="400"></div>' +
        '<div class="field"><label for="route-distance">Distance (km)</label><input id="route-distance" name="distanceKm" type="number" min="0.1" step="0.1" value="10"></div>' +
        '<div class="field"><label for="route-morning">Morning departure</label><input id="route-morning" name="morningDeparture" type="time" value="06:30" required></div>' +
        '<div class="field"><label for="route-evening">Evening departure</label><input id="route-evening" name="eveningDeparture" type="time" value="17:30" required></div>' +
        '<div class="field"><label for="route-price">Monthly tariff (ETB)</label><input id="route-price" name="basePriceEtb" type="number" min="0" step="1" value="2500" required></div>' +
        '</div><div class="field" style="margin-top:14px"><label for="route-stops">Ordered route stops *</label><textarea id="route-stops" name="stopsText" rows="5" required placeholder="Bole Medhanialem | ቦሌ መድኃኔዓለም | 8.995000 | 38.788000&#10;Bole Atlas | ቦሌ አትላስ | 9.006000 | 38.780000&#10;Merkato Bus Terminal | መርካቶ ተርሚናል | 9.031000 | 38.736000"></textarea><span class="hint">One stop per line: English name | Amharic name | latitude | longitude. Keep stops in travel order and use real coordinates from a map.</span></div>' +
        '<input name="routeId" type="hidden"><div class="form-actions"><button class="btn btn-primary" type="submit">Create route</button><button class="btn btn-secondary" id="route-cancel" type="button" hidden>Cancel edit</button></div><p id="route-form-message" class="form-message" hidden role="status"></p></form>') +
      section("Existing routes", routes.length + " route(s)",
        table([
          { label: "Route", render: r => "<strong>" + esc(value(r, "name")) + "</strong><br><span class=\"muted\">" + esc(value(r, "nameAm", "name_am")) + "</span>" },
          { label: "Morning", keys: ["morningDeparture", "morning_departure"] },
          { label: "Evening", keys: ["eveningDeparture", "evening_departure"] },
          { label: "Distance", render: r => esc(value(r, "distanceKm", "distance_km")) + " km" },
          { label: "Monthly tariff", render: r => esc(money(value(r, "basePriceEtb", "base_price_etb"))) },
          { label: "Status", render: r => pill(value(r, "active") === false || value(r, "active") === 0 ? "INACTIVE" : "ACTIVE") },
          { label: "Actions", render: r => '<div class="inline-actions"><button class="btn btn-secondary" data-edit-route="' + esc(r.id) + '">Edit</button><button class="btn btn-secondary" data-geofence-route="' + esc(r.id) + '">Departure area</button><button class="btn btn-secondary" data-toggle-route="' + esc(r.id) + '">' + (r.active === false || r.active === 0 ? "Activate" : "Deactivate") + '</button></div>' }
        ], routes));
  }

  async function renderVehicles() {
    const data = asArray(await api("/api/vehicles"), "vehicles");
    return pageHeader("Fleet", "Vehicle capacity, route and driver assignment information.") +
      section("Vehicle register", data.length + " vehicle(s)",
        table([
          { label: "Plate", render: r => "<strong>" + esc(value(r, "plateNumber", "plate_number")) + "</strong>" },
          { label: "Model", keys: ["model"] },
          { label: "Type", keys: ["vehicleType", "vehicle_type"] },
          { label: "Capacity", keys: ["capacityLimit", "capacity_limit"] },
          { label: "Occupancy", render: r => esc(value(r, "currentOccupancy", "current_occupancy")) + " / " + esc(value(r, "capacityLimit", "capacity_limit")) },
          { label: "Driver", keys: ["driverName", "driver_name"] },
          { label: "Route", keys: ["routeName", "route_name"] },
          { label: "Status", render: r => pill(value(r, "status")) }
        ], data));
  }
  async function renderSubscriptions() {
    await loadLookups();
    const [subscriptions, features] = await Promise.all([api("/api/admin/subscriptions"), api("/api/admin/features")]);
    const data = asArray(subscriptions, "subscriptions");
    const testRechargeEnabled = features.manualTestRechargeEnabled === true;
    const adminActivationEnabled = features.adminPassActivationEnabled === true;
    const eligibleVehicles = state.cache.vehicles || [];
    return pageHeader("Subscriptions & passenger assignments", adminActivationEnabled
      ? "Temporary admin activation is enabled. Activate a passenger pass for 30 days with a reason. No payment is collected or added to revenue."
      : testRechargeEnabled
      ? "Assign a driver-owned vehicle on the same route. Test recharges are available only in the isolated test environment and are not real payments."
      : "Assign a driver-owned vehicle on the same route. Payment activation is unavailable until the payment provider integration is enabled.") +
      section("Passenger subscriptions", data.length + " record(s)",
        table([
          { label: "Passenger", render: r => "<strong>" + esc(value(r, "passengerName", "passenger_name")) + "</strong>" },
          { label: "Phone", keys: ["passengerPhone", "passenger_phone"] },
          { label: "Route", keys: ["routeName", "route_name"] },
          { label: "Owner vehicle / driver", render: r => esc(value(r, "vehiclePlate", "vehicle_plate")) + "<br><span class=\"muted\">" + esc(value(r, "driverName", "driver_name")) + "</span>" },
          { label: "Assign vehicle", render: r => {
            const subId = esc(value(r, "id"));
            const routeId = value(r, "routeId", "route_id");
            const currentVehicle = value(r, "vehicleId", "vehicle_id");
            const choices = eligibleVehicles.filter(v =>
              value(v, "assignedRouteId", "assigned_route_id") === routeId &&
              value(v, "driverId", "driver_id") !== "—" &&
              value(v, "driverId", "driver_id") !== ""
            );
            return '<div class="inline-actions"><select data-vehicle-for="' + subId + '" aria-label="Passenger vehicle assignment" style="min-width:175px;padding:7px;border:1px solid #d5deea;border-radius:8px"><option value="">Not assigned</option>' +
              choices.map(v => '<option value="' + esc(v.id) + '" ' + (v.id === currentVehicle ? "selected" : "") + '>' + esc(value(v, "plateNumber", "plate_number")) + ' · ' + esc(value(v, "driverName", "driver_name")) + '</option>').join("") +
              '</select><button class="btn btn-secondary" data-assign-vehicle="' + subId + '">Save</button></div>';
          }},
          { label: "Price", render: r => esc(money(value(r, "priceEtb", "price_etb"))) },
          { label: "Payment", render: r => pill(value(r, "paymentStatus", "payment_status")) },
          { label: "Subscription", render: r => pill(value(r, "subscriptionStatus", "subscription_status")) },
          { label: "End date", keys: ["endDate", "end_date"] },
          { label: "Pass activation", render: r => adminActivationEnabled
            ? (value(r, "subscriptionStatus", "subscription_status") === "ACTIVE" && value(r, "paymentStatus", "payment_status") === "PAID" && new Date(value(r, "endDate", "end_date")).getTime() > Date.now()
              ? pill("ACTIVE")
              : '<button class="btn btn-primary" data-activate-pass="' + esc(value(r, "id")) + '">Activate 30 days</button>')
            : testRechargeEnabled
            ? '<button class="btn btn-primary" data-recharge="' + esc(value(r, "id")) + '">Recharge 30 days (test)</button>'
            : pill("DISABLED") }
        ], data));
  }
  async function renderPayments() {
    const data = asArray(await api("/api/admin/payments"), "payments");
    return pageHeader("Payments", "Read-only transaction history. Live Telebirr checkout is not enabled.") +
      section("Transaction ledger", data.length + " record(s)",
        table([
          { label: "Date", render: r => esc(dateText(value(r, "date", "createdAt", "created_at"))) },
          { label: "Passenger", keys: ["passengerName", "passenger_name"] },
          { label: "Phone", keys: ["passengerPhone", "passenger_phone"] },
          { label: "Reference", keys: ["referenceNumber", "reference_number"] },
          { label: "Amount", render: r => esc(money(value(r, "amountEtb", "amount_etb"))) },
          { label: "Provider", keys: ["provider"] },
          { label: "Status", render: r => pill(value(r, "status")) }
        ], data));
  }
  async function renderCheckins() {
    const data = asArray(await api("/api/admin/checkins"), "checkins");
    return pageHeader("Boarding check-ins", "Latest passenger boarding records returned by the server.") +
      section("Check-in history", data.length + " record(s)",
        table([
          { label: "Timestamp", render: r => esc(dateText(value(r, "timestamp", "createdAt", "created_at"))) },
          { label: "Passenger", keys: ["passengerName", "passenger_name"] },
          { label: "Stop", keys: ["stopName", "stop_name"] },
          { label: "Vehicle", keys: ["vehiclePlate", "vehicle_plate"] },
          { label: "Route", keys: ["routeName", "route_name"] },
          { label: "Status", render: r => pill(value(r, "status")) }
        ], data));
  }
  async function renderComplaints() {
    const data = asArray(await api("/api/admin/complaints"), "complaints");
    return pageHeader("Complaints", "Review reports and update the case status.") +
      section("Passenger reports", data.length + " case(s)",
        table([
          { label: "Created", render: r => esc(dateText(value(r, "createdAt", "created_at"))) },
          { label: "Passenger", keys: ["passengerName", "passenger_name"] },
          { label: "Phone", keys: ["passengerPhone", "passenger_phone"] },
          { label: "Category", keys: ["category"] },
          { label: "Description", render: r => '<span class="description-cell">' + esc(value(r, "description")) + "</span>" },
          { label: "Status", render: r => pill(value(r, "status")) },
          { label: "Update", render: r => {
            const id = esc(value(r, "id"));
            const status = String(value(r, "status"));
            return '<div class="inline-actions">' +
              (status !== "INVESTIGATING" ? '<button class="btn btn-secondary" data-complaint-id="' + id + '" data-status="INVESTIGATING">Investigate</button>' : "") +
              (status !== "RESOLVED" ? '<button class="btn btn-primary" data-complaint-id="' + id + '" data-status="RESOLVED">Resolve</button>' : "") +
              "</div>";
          }}
        ], data));
  }
  async function renderAuditLogs() {
    const data = asArray(await api("/api/admin/audit-logs"), "logs");
    return pageHeader("Audit log", "Recent administrative and operational events.") +
      section("Recorded activity", data.length + " event(s)",
        table([
          { label: "Time", render: r => esc(dateText(value(r, "timestamp", "createdAt", "created_at"))) },
          { label: "Action", keys: ["action"] },
          { label: "Actor role", keys: ["role"] },
          { label: "Actor ID", keys: ["userId", "user_id"] },
          { label: "Details", keys: ["details"] }
        ], data));
  }
  async function renderCompanyAccess() {
    return pageHeader("Company access", "Create a one-time company invitation. The company representative must register separately at the Company Portal; system-admin credentials are not company credentials.",
      '<a class="btn btn-secondary" href="/company" target="_blank" rel="noopener">Open Company Portal</a>') +
      section("Invite a transport company", "The code is displayed only once. Share it securely with the company's authorized representative.",
        '<form id="company-invite-form" class="form-stack" style="max-width:560px">' +
        '<label for="company-invite-name">Registered company name</label>' +
        '<input id="company-invite-name" name="name" minlength="2" maxlength="120" required placeholder="Company legal name">' +
        '<button class="btn btn-primary" type="submit">Generate one-time registration code</button>' +
        '</form><div id="company-invite-result" class="notice" hidden style="margin-top:16px"></div>');
  }

  async function createCompanyInvite(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const button = form.querySelector('button[type="submit"]');
    const output = $("#company-invite-result");
    button.disabled = true;
    output.hidden = true;
    try {
      const name = String(new FormData(form).get("name") || "").trim();
      const result = await api("/api/admin/companies/invite", {
        method: "POST", body: JSON.stringify({ name })
      });
      output.hidden = false;
      output.innerHTML = '<strong>Invitation created for ' + esc(result.company?.name || name) + '.</strong>' +
        '<p>One-time registration code:</p><p><code id="company-invite-code" style="font-size:1.25rem;font-weight:800;letter-spacing:.12em">' +
        esc(result.registrationCode) + '</code></p><p>Give this code to the company representative. They must open <a href="/company" target="_blank" rel="noopener">/company</a>, register using this code, and create a separate company password. This code cannot be viewed again.</p>' +
        '<button class="btn btn-secondary" type="button" id="copy-company-code">Copy code</button>';
      $("#copy-company-code")?.addEventListener("click", async () => {
        try { await navigator.clipboard.writeText(result.registrationCode); toast("Registration code copied."); }
        catch (_) { toast("Copy unavailable. Select and copy the code manually.", true); }
      });
      form.reset();
    } catch (error) {
      output.hidden = false;
      output.textContent = error.message;
      output.classList.add("error");
    } finally { button.disabled = false; }
  }

  async function renderView() {
    const revision = ++renderRevision;
    const view = state.view;
    fleetMapCleanup?.();
    fleetMapCleanup = null;
    const title = labels[state.view] || labels.overview;
    $("#page-title").textContent = title[0];
    $$(".nav-item").forEach(button => button.classList.toggle("active", button.dataset.view === state.view));
    $("#page-content").innerHTML = '<div class="loading">Loading ' + esc(title[0].toLowerCase()) + "…</div>";
    try {
      let html;
      switch (view) {
        case "gps": html = pageHeader("Driver GPS", "Track your daily commute fleet. Company intercity vehicles are visible only to their company.") + '<section class="card section-card"><div id="admin-fleet-map"></div></section>'; break;
        case "reports": html = pageHeader("Performance reports", "Daily, weekly and monthly commute performance. Admin grants are excluded from revenue."); break;
        case "drivers": html = await renderDrivers(); break;
        case "companies": html = await renderCompanyAccess(); break;
        case "routes": html = await renderRoutes(); break;
        case "vehicles": html = await renderVehicles(); break;
        case "subscriptions": html = await renderSubscriptions(); break;
        case "payments": html = await renderPayments(); break;
        case "checkins": html = await renderCheckins(); break;
        case "complaints": html = await renderComplaints(); break;
        case "audit-logs": html = await renderAuditLogs(); break;
        case "settings": html = await renderSettings(); break;
        default: html = await renderOverview();
      }
      if (revision !== renderRevision) return;
      $("#page-content").innerHTML = html;
      if (view === "gps" && window.RoutepassFleetMap) {
        fleetMapCleanup = window.RoutepassFleetMap.mount({ container: $("#admin-fleet-map"), apiUrl: "/api/fleet/tracking", token: state.token });
      }
      if ((state.view === "overview" || state.view === "reports") && window.RoutepassReports) {
        $("#page-content").insertAdjacentHTML('beforeend', window.RoutepassReports.markup());
        window.RoutepassReports.load(api);
      }
      const routeForm = $("#create-route-form");
      if (routeForm) {
        routeForm.addEventListener("submit", submitRoute);
        const cancelButton = $("#route-cancel");
        if (cancelButton) cancelButton.addEventListener("click", cancelRouteEdit);
      }
      const companyInviteForm = $("#company-invite-form");
      if (companyInviteForm) companyInviteForm.addEventListener("submit", createCompanyInvite);
      const afroSmsForm = $("#afro-sms-form");
      if (afroSmsForm) afroSmsForm.addEventListener("submit", saveAfroSms);
      $("#afro-sms-test")?.addEventListener("click", async event => {
        const button = event.currentTarget;
        const message = $("#afro-sms-message");
        button.disabled = true;
        try {
          await api("/api/admin/integrations/afro-sms/test", { method: "POST", body: JSON.stringify({ phone: $("#afro-sms-test-phone").value, message: $("#afro-sms-test-message").value }) });
          message.textContent = "Test SMS sent."; message.className = "form-message"; message.hidden = false; toast("Test SMS sent.");
        } catch (error) { message.textContent = error.message; message.className = "form-message error"; message.hidden = false; }
        finally { button.disabled = false; }
      });
    } catch (error) {
      if (revision !== renderRevision) return;
      $("#page-content").innerHTML = '<div class="card empty-note"><strong>Could not load this page.</strong><br>' + esc(error.message) +
        '<div style="margin-top:12px"><button class="btn btn-secondary" id="retry-button">Try again</button></div></div>';
      $("#retry-button")?.addEventListener("click", renderView);
    }
  }
  function parseStopsText(value) {
    const lines = String(value || "").split(/\r?\n/).map(line => line.trim()).filter(Boolean);
    if (lines.length < 2) throw new Error("Add at least two ordered route stops.");
    return lines.map((line, index) => {
      const parts = line.split("|").map(part => part.trim());
      if (parts.length < 4 || !parts[0] || !parts[1]) {
        throw new Error("Stop line " + (index + 1) + " must include English name | Amharic name | latitude | longitude.");
      }
      const latitude = Number(parts[2]);
      const longitude = Number(parts[3]);
      if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 ||
          !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
        throw new Error("Stop line " + (index + 1) + " has invalid coordinates.");
      }
      return { stopName: parts[0], stopNameAm: parts[1], latitude, longitude };
    });
  }

  async function submitRoute(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const button = form.querySelector('button[type="submit"]');
    const message = $("#route-form-message");
    const data = Object.fromEntries(new FormData(form).entries());
    const routeId = data.routeId;
    delete data.routeId;
    try {
      data.stops = parseStopsText(data.stopsText);
    } catch (error) {
      message.textContent = error.message;
      message.className = "form-message error";
      message.hidden = false;
      return;
    }
    delete data.stopsText;
    data.distanceKm = Number(data.distanceKm);
    data.basePriceEtb = Number(data.basePriceEtb);
    button.disabled = true;
    message.hidden = true;
    try {
      await api(routeId ? "/api/routes/" + encodeURIComponent(routeId) : "/api/routes", {
        method: routeId ? "PUT" : "POST", body: JSON.stringify(data)
      });
      toast(routeId ? "Route updated." : "Route created.");
      form.reset();
      await renderView();
    } catch (error) {
      message.textContent = error.message;
      message.className = "form-message error";
      message.hidden = false;
    } finally { button.disabled = false; }
  }

  async function updateDriverApproval(button) {
    const driverId = button.dataset.driverId;
    const status = button.dataset.driverApproval;
    if (status === "SUSPENDED" && !window.confirm("Reject/suspend this driver registration? The driver will not be able to sign in.")) return;
    button.disabled = true;
    try {
      const result = await api("/api/admin/drivers/" + encodeURIComponent(driverId) + "/approval", {
        method: "PATCH", body: JSON.stringify({ status })
      });
      toast(result.message || "Driver approval updated.");
      await renderView();
    } catch (error) { toast(error.message, true); button.disabled = false; }
  }

  async function assignDriverRoute(button) {
    const driverId = button.dataset.assignRoute;
    const select = $$("[data-route-for]").find(item => item.dataset.routeFor === driverId);
    if (!select?.value) return toast("Choose an active route first.", true);
    button.disabled = true;
    try {
      await api("/api/admin/drivers/" + encodeURIComponent(driverId) + "/route", {
        method: "PATCH", body: JSON.stringify({ routeId: select.value })
      });
      toast("Route assigned. The driver retains ownership of the vehicle.");
      await renderView();
    } catch (error) { toast(error.message, true); button.disabled = false; }
  }

  async function assignSubscriptionVehicle(button) {
    const subId = button.dataset.assignVehicle;
    const select = $$("[data-vehicle-for]").find(item => item.dataset.vehicleFor === subId);
    if (!select) return;
    button.disabled = true;
    try {
      const response = await api("/api/admin/subscriptions/" + encodeURIComponent(subId) + "/assignment", {
        method: "PATCH", body: JSON.stringify({ vehicleId: select.value })
      });
      toast(response.vehicle ? "Passenger vehicle assignment saved." : "Passenger vehicle assignment cleared.");
      await renderView();
    } catch (error) { toast(error.message, true); button.disabled = false; }
  }

  async function activatePass(button) {
    const reason = window.prompt("Reason for this 30-day admin activation (3–200 characters). No payment will be collected:");
    if (reason === null) return;
    if (reason.trim().length < 3 || reason.trim().length > 200) return toast("Enter a reason of 3 to 200 characters.", true);
    button.disabled = true;
    try {
      const result = await api("/api/admin/subscriptions/" + encodeURIComponent(button.dataset.activatePass) + "/activate", {
        method: "POST", body: JSON.stringify({ days: 30, reason: reason.trim() })
      });
      toast(result.message || "Passenger pass activated.");
      await renderView();
    } catch (error) { toast(error.message, true); button.disabled = false; }
  }

  async function configureDeparture(button) {
    button.disabled = true;
    try {
      const result = await api('/api/admin/routes/' + encodeURIComponent(button.dataset.geofenceRoute) + '/departure-settings');
      const settings = result.settings;
      const latitude = window.prompt('Departure latitude (-90 to 90)', settings.latitude ?? '');
      if (latitude === null) return;
      const longitude = window.prompt('Departure longitude (-180 to 180)', settings.longitude ?? '');
      if (longitude === null) return;
      const radius = window.prompt('Allowed radius in metres (25–5000). Diameter is twice the radius. Drivers must also be within 30 minutes of departure.', settings.radius_m || 300);
      if (radius === null) return;
      if (![latitude,longitude,radius].every(v => v.trim() !== '' && Number.isFinite(Number(v)))) throw Error('Enter numeric coordinates and a radius.');
      const saved = await api('/api/admin/routes/' + encodeURIComponent(button.dataset.geofenceRoute) + '/departure-settings', {method:'PUT',body:JSON.stringify({latitude:Number(latitude),longitude:Number(longitude),radiusMeters:Number(radius)})});
      toast(saved.message);
    } catch(e) { toast(e.message,true); } finally { button.disabled=false; }
  }

  async function rechargeSubscription(button) {
    const id = button.dataset.recharge;
    if (!window.confirm("Activate this passenger subscription for 30 test days? This is an ADMIN_TEST entry, not a real Telebirr payment.")) return;
    button.disabled = true;
    try {
      const result = await api("/api/admin/subscriptions/" + encodeURIComponent(id) + "/recharge", {
        method: "POST", body: JSON.stringify({ days: 30 })
      });
      toast(result.message || "Manual test recharge complete.");
      await renderView();
    } catch (error) { toast(error.message, true); button.disabled = false; }
  }

  async function editRoute(button) {
    const id = button.dataset.editRoute;
    const summary = (state.cache.routes || []).find(item => item.id === id);
    if (!summary) return toast("Route data is stale. Refresh and try again.", true);
    button.disabled = true;
    try {
      const details = await api("/api/routes/" + encodeURIComponent(id));
      const route = details.route || summary;
      const currentStops = asArray(details, "stops");
      const stopsTextDefault = currentStops.map(stop =>
        [value(stop, "stopName", "stop_name"), value(stop, "stopNameAm", "stop_name_am"),
          value(stop, "latitude"), value(stop, "longitude")].join(" | ")
      ).join("\n");
      await renderRoutesIntoEditForm(route, stopsTextDefault, id);
    } catch (error) {
      toast(error.message, true);
    } finally {
      button.disabled = false;
    }
  }

  async function renderRoutesIntoEditForm(route, stopsText, routeId) {
    const form = $("#create-route-form");
    if (!form) return;
    for (const [name, keys] of Object.entries({
      name: ["name"], nameAm: ["nameAm", "name_am"], description: ["description"],
      distanceKm: ["distanceKm", "distance_km"], morningDeparture: ["morningDeparture", "morning_departure"],
      eveningDeparture: ["eveningDeparture", "evening_departure"], basePriceEtb: ["basePriceEtb", "base_price_etb"]
    })) {
      const field = form.elements.namedItem(name);
      if (field) field.value = value(route, ...keys) === "—" ? "" : value(route, ...keys);
    }
    form.elements.namedItem("routeId").value = routeId;
    form.elements.namedItem("stopsText").value = stopsText;
    form.querySelector('button[type="submit"]').textContent = "Save route changes";
    $("#route-cancel").hidden = false;
    form.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function cancelRouteEdit() {
    const form = $("#create-route-form");
    if (!form) return;
    form.reset();
    form.elements.namedItem("routeId").value = "";
    form.querySelector('button[type="submit"]').textContent = "Create route";
    $("#route-cancel").hidden = true;
    $("#route-form-message").hidden = true;
  }

  async function toggleRoute(button) {
    const id = button.dataset.toggleRoute;
    const route = (state.cache.routes || []).find(item => item.id === id);
    if (!route) return toast("Route data is stale. Refresh and try again.", true);
    const active = route.active === false || route.active === 0;
    if (!window.confirm((active ? "Activate" : "Deactivate") + " route " + value(route, "name") + "?")) return;
    button.disabled = true;
    try {
      await api("/api/routes/" + encodeURIComponent(id), {
        method: "PUT",
        body: JSON.stringify({ ...route, active })
      });
      toast(active ? "Route activated." : "Route deactivated.");
      await renderView();
    } catch (error) { toast(error.message, true); button.disabled = false; }
  }

  async function changeComplaint(button) {
    button.disabled = true;
    try {
      await api("/api/admin/complaints/" + encodeURIComponent(button.dataset.complaintId), {
        method: "PATCH", body: JSON.stringify({ status: button.dataset.status })
      });
      toast("Complaint status updated.");
      await renderView();
    } catch (error) { toast(error.message, true); button.disabled = false; }
  }
  async function signIn(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true; button.textContent = "Signing in…";
    $("#login-error").hidden = true;
    try {
      const formData = new FormData(form);
      const result = await api("/api/auth/login", {
        method: "POST",
        body: JSON.stringify({ phone: String(formData.get("phone") || "").trim(), password: formData.get("password"), role: "ADMIN" })
      });
      if (!result.user || result.user.role !== "ADMIN") throw new Error("This account does not have administrator access.");
      state.token = result.token;
      state.user = result.user;
      sessionStorage.setItem(TOKEN_KEY, state.token);
      showApp();
      await renderView();
    } catch (error) { showLogin(error.message); }
    finally { button.disabled = false; button.textContent = "Sign in to admin portal"; }
  }
  document.addEventListener("click", event => {
    const navButton = event.target.closest("[data-view]");
    if (navButton) { state.view = navButton.dataset.view; renderView(); return; }
    const goButton = event.target.closest("[data-go]");
    if (goButton) { state.view = goButton.dataset.go; renderView(); return; }
    const approvalButton = event.target.closest("[data-driver-approval]");
    if (approvalButton) { updateDriverApproval(approvalButton); return; }
    const assignRouteButton = event.target.closest("[data-assign-route]");
    if (assignRouteButton) { assignDriverRoute(assignRouteButton); return; }
    const assignVehicleButton = event.target.closest("[data-assign-vehicle]");
    if (assignVehicleButton) { assignSubscriptionVehicle(assignVehicleButton); return; }
    const rechargeButton = event.target.closest("[data-recharge]");
    const geofenceButton = event.target.closest('[data-geofence-route]');
    if (geofenceButton) { configureDeparture(geofenceButton); return; }
    const activateButton = event.target.closest("[data-activate-pass]");
    if (activateButton) { activatePass(activateButton); return; }
    if (rechargeButton) { rechargeSubscription(rechargeButton); return; }
    const editRouteButton = event.target.closest("[data-edit-route]");
    if (editRouteButton) { editRoute(editRouteButton); return; }
    const toggleRouteButton = event.target.closest("[data-toggle-route]");
    if (toggleRouteButton) { toggleRoute(toggleRouteButton); return; }
    const complaintButton = event.target.closest("[data-complaint-id]");
    if (complaintButton) changeComplaint(complaintButton);
  });
  $("#login-form").addEventListener("submit", signIn);
  $("#logout-button").addEventListener("click", () => logout());
  async function boot() {
    if (!state.token) return showLogin();
    try {
      const response = await api("/api/auth/me");
      if (response.user?.role !== "ADMIN") return logout(false);
      state.user = response.user;
      showApp();
      await renderView();
    } catch (_) { logout(false); }
  }
  boot();
})();
