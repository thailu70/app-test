(() => {
  "use strict";
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const TOKEN_KEY = "routepass_admin_token";
  const state = { token: sessionStorage.getItem(TOKEN_KEY) || "", user: null, view: "overview", cache: {} };
  const labels = {
    overview: ["Overview", "A live snapshot of RoutePass service operations."],
    drivers: ["Drivers & routes", "Review driver-owned vehicles and assign approved operating routes."],
    routes: ["Manage routes", "Create, edit, activate and deactivate RoutePass transit routes."],
    vehicles: ["Vehicles", "Fleet availability and current assignments."],
    subscriptions: ["Subscriptions", "Passenger subscription records and payment state."],
    payments: ["Payments", "Transaction history for operational review."],
    checkins: ["Check-ins", "Recent passenger boarding records."],
    complaints: ["Complaints", "Review passenger reports and update case status."],
    "audit-logs": ["Audit log", "Recent administrative and operational changes."]
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
    return pageHeader("Operations at a glance", "Use the left menu to manage the fleet and operations.") +
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
      section("Create a route", "New routes are active by default.",
        '<form id="create-route-form" class="form-card"><div class="driver-form-grid">' +
        '<div class="field"><label for="route-name">Route name (English) *</label><input id="route-name" name="name" required maxlength="120" placeholder="Bole - Merkato Express"></div>' +
        '<div class="field"><label for="route-name-am">Route name (Amharic) *</label><input id="route-name-am" name="nameAm" required maxlength="120" placeholder="ቦሌ - መርካቶ"></div>' +
        '<div class="field"><label for="route-description">Description</label><input id="route-description" name="description" maxlength="400"></div>' +
        '<div class="field"><label for="route-distance">Distance (km)</label><input id="route-distance" name="distanceKm" type="number" min="0.1" step="0.1" value="10"></div>' +
        '<div class="field"><label for="route-morning">Morning departure</label><input id="route-morning" name="morningDeparture" type="time" value="06:30" required></div>' +
        '<div class="field"><label for="route-evening">Evening departure</label><input id="route-evening" name="eveningDeparture" type="time" value="17:30" required></div>' +
        '<div class="field"><label for="route-price">Monthly tariff (ETB)</label><input id="route-price" name="basePriceEtb" type="number" min="0" step="1" value="2500" required></div>' +
        '</div><div class="field" style="margin-top:14px"><label for="route-stops">Ordered route stops *</label><textarea id="route-stops" name="stopsText" rows="5" required placeholder="Bole Medhanialem | ቦሌ መድኃኔዓለም | 8.995000 | 38.788000&#10;Bole Atlas | ቦሌ አትላስ | 9.006000 | 38.780000&#10;Merkato Bus Terminal | መርካቶ ተርሚናል | 9.031000 | 38.736000"></textarea><span class="hint">One stop per line: English name | Amharic name | latitude | longitude. Keep stops in travel order and use real coordinates from a map.</span></div>' +
        '<div class="form-actions"><button class="btn btn-primary" type="submit">Create route</button></div><p id="route-form-message" class="form-message" hidden role="status"></p></form>') +
      section("Existing routes", routes.length + " route(s)",
        table([
          { label: "Route", render: r => "<strong>" + esc(value(r, "name")) + "</strong><br><span class=\"muted\">" + esc(value(r, "nameAm", "name_am")) + "</span>" },
          { label: "Morning", keys: ["morningDeparture", "morning_departure"] },
          { label: "Evening", keys: ["eveningDeparture", "evening_departure"] },
          { label: "Distance", render: r => esc(value(r, "distanceKm", "distance_km")) + " km" },
          { label: "Monthly tariff", render: r => esc(money(value(r, "basePriceEtb", "base_price_etb"))) },
          { label: "Status", render: r => pill(value(r, "active") === false || value(r, "active") === 0 ? "INACTIVE" : "ACTIVE") },
          { label: "Actions", render: r => '<div class="inline-actions"><button class="btn btn-secondary" data-edit-route="' + esc(r.id) + '">Edit</button><button class="btn btn-secondary" data-toggle-route="' + esc(r.id) + '">' + (r.active === false || r.active === 0 ? "Activate" : "Deactivate") + '</button></div>' }
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
    const data = asArray(await api("/api/admin/subscriptions"), "subscriptions");
    const eligibleVehicles = state.cache.vehicles || [];
    return pageHeader("Subscriptions & passenger assignments", "Assign vehicles, record verified offline payments, and use the separate test-only recharge only in staging.") +
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
          { label: "Payment entry", render: r => '<div class="inline-actions"><button class="btn btn-primary" data-manual-payment="' + esc(value(r, "id")) + '">Record payment</button><button class="btn btn-secondary" data-recharge="' + esc(value(r, "id")) + '">Test only</button></div>' }
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
  async function renderView() {
    const title = labels[state.view] || labels.overview;
    $("#page-title").textContent = title[0];
    $$(".nav-item").forEach(button => button.classList.toggle("active", button.dataset.view === state.view));
    $("#page-content").innerHTML = '<div class="loading">Loading ' + esc(title[0].toLowerCase()) + "…</div>";
    try {
      let html;
      switch (state.view) {
        case "drivers": html = await renderDrivers(); break;
        case "routes": html = await renderRoutes(); break;
        case "vehicles": html = await renderVehicles(); break;
        case "subscriptions": html = await renderSubscriptions(); break;
        case "payments": html = await renderPayments(); break;
        case "checkins": html = await renderCheckins(); break;
        case "complaints": html = await renderComplaints(); break;
        case "audit-logs": html = await renderAuditLogs(); break;
        default: html = await renderOverview();
      }
      $("#page-content").innerHTML = html;
      const routeForm = $("#create-route-form");
      if (routeForm) routeForm.addEventListener("submit", submitRoute);
    } catch (error) {
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
      await api("/api/routes", { method: "POST", body: JSON.stringify(data) });
      toast("Route created.");
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

  async function recordManualPayment(button) {
    const id = button.dataset.manualPayment;
    const amountEtb = Number(window.prompt("Amount received in ETB (must equal the subscription price):", ""));
    if (!Number.isFinite(amountEtb) || amountEtb <= 0) return toast("Enter a valid positive payment amount.", true);
    const referenceNumber = window.prompt("Receipt / bank reference number:", "");
    if (referenceNumber === null || !referenceNumber.trim()) return toast("A unique receipt/reference number is required.", true);
    const method = window.prompt("Payment method: CASH, BANK_TRANSFER, or OTHER", "CASH");
    if (method === null) return;
    const notes = window.prompt("Optional notes (cash receipt, teller, etc.):", "") || "";
    if (!window.confirm("Record ETB " + amountEtb + " as a real offline payment and activate this passenger pass? Only continue after funds are actually received and verified.")) return;
    button.disabled = true;
    try {
      const result = await api("/api/admin/subscriptions/" + encodeURIComponent(id) + "/manual-payment", {
        method: "POST", body: JSON.stringify({ amountEtb, referenceNumber: referenceNumber.trim(), method: method.trim().toUpperCase(), notes })
      });
      toast(result.message || "Manual payment recorded.");
      await renderView();
    } catch (error) { toast(error.message, true); button.disabled = false; }
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
      const name = window.prompt("Route name (English):", value(route, "name"));
      if (name === null) return;
      const nameAm = window.prompt("Route name (Amharic):", value(route, "nameAm", "name_am"));
      if (nameAm === null) return;
      const description = window.prompt("Description:", value(route, "description") === "—" ? "" : value(route, "description"));
      if (description === null) return;
      const morningDeparture = window.prompt("Morning departure (HH:MM):", value(route, "morningDeparture", "morning_departure"));
      if (morningDeparture === null) return;
      const eveningDeparture = window.prompt("Evening departure (HH:MM):", value(route, "eveningDeparture", "evening_departure"));
      if (eveningDeparture === null) return;
      const basePriceEtb = Number(window.prompt("Monthly tariff in ETB:", value(route, "basePriceEtb", "base_price_etb")));
      if (!Number.isFinite(basePriceEtb) || basePriceEtb < 0) return toast("Tariff must be a valid non-negative number.", true);
      const editedStopsText = window.prompt(
        "Stops, one per line: English | Amharic | latitude | longitude. Edit the existing list as needed:",
        stopsTextDefault
      );
      if (editedStopsText === null) return;
      const stops = parseStopsText(editedStopsText);
      await api("/api/routes/" + encodeURIComponent(id), {
        method: "PUT",
        body: JSON.stringify({ ...route, name, nameAm, description, morningDeparture, eveningDeparture, basePriceEtb, stops })
      });
      toast("Route and stops updated.");
      await renderView();
    } catch (error) {
      toast(error.message, true);
    } finally {
      button.disabled = false;
    }
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
    const manualPaymentButton = event.target.closest("[data-manual-payment]");
    if (manualPaymentButton) { recordManualPayment(manualPaymentButton); return; }
    const rechargeButton = event.target.closest("[data-recharge]");
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
