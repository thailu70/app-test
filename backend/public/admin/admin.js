(() => {
  "use strict";
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const TOKEN_KEY = "routepass_admin_token";
  const state = { token: sessionStorage.getItem(TOKEN_KEY) || "", user: null, view: "overview", cache: {} };
  const labels = {
    overview: ["Overview", "A live snapshot of RoutePass service operations."],
    drivers: ["Drivers", "View active and inactive driver accounts."],
    "create-driver": ["Create driver", "Create a driver account and assign an available vehicle and active route."],
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
    const [routeResult, vehicleResult] = await Promise.all([api("/api/routes"), api("/api/vehicles")]);
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
    const data = asArray(await api("/api/admin/drivers"), "drivers");
    return pageHeader("Driver accounts", "Drivers are created by an administrator and assigned a vehicle and route.",
      '<button class="btn btn-primary" data-go="create-driver">＋ Create driver</button>') +
      section("Registered drivers", data.length + " account(s)",
        table([
          { label: "Full name", render: r => "<strong>" + esc(value(r, "fullName", "full_name")) + "</strong>" },
          { label: "Phone / username", keys: ["phone"] },
          { label: "Company", keys: ["companyName", "company_name"] },
          { label: "License", keys: ["licenseNumber", "license_number"] },
          { label: "Vehicle", keys: ["assignedVehiclePlate", "assigned_vehicle_plate"] },
          { label: "Route", keys: ["appliedRouteName", "applied_route_name"] },
          { label: "Status", render: r => pill(value(r, "status")) },
          { label: "Created", render: r => esc(dateText(value(r, "createdAt", "created_at"))) }
        ], data));
  }
  async function renderCreateDriver() {
    await loadLookups();
    const routes = state.cache.routes || [];
    const vehicles = (state.cache.vehicles || []).filter(v => !value(v, "driverId", "driver_id") || value(v, "driverId", "driver_id") === "—");
    const options = (items, label, placeholder) => '<option value="">' + esc(placeholder) + "</option>" +
      items.map(item => '<option value="' + esc(item.id) + '">' + esc(label(item)) + "</option>").join("");
    const vehicleHint = vehicles.length ? "Only currently unassigned vehicles are shown." : "There are no unassigned vehicles. Open the Vehicles page and check the current assignments.";
    return pageHeader("Create a driver", "Create credentials for the driver and assign their vehicle and active route.") +
      '<div class="card form-card"><form id="create-driver-form">' +
      '<div class="driver-form-grid">' +
      '<div class="field"><label for="driver-name">Full legal name *</label><input id="driver-name" name="fullName" autocomplete="name" required maxlength="120"></div>' +
      '<div class="field"><label for="driver-phone">Phone number / login username *</label><input id="driver-phone" name="phone" type="tel" placeholder="+2519XXXXXXXX" autocomplete="tel" required maxlength="32"><span class="hint">The driver uses this phone number as the username in the app.</span></div>' +
      '<div class="field"><label for="driver-email">Email (optional)</label><input id="driver-email" name="email" type="email" autocomplete="email" maxlength="180"></div>' +
      '<div class="field"><label for="driver-password">Initial password *</label><input id="driver-password" name="password" type="password" autocomplete="new-password" minlength="12" required><span class="hint">Use at least 12 characters. Give the password to the driver privately.</span></div>' +
      '<div class="field"><label for="driver-license">Commercial driving licence number *</label><input id="driver-license" name="licenseNumber" required maxlength="80"></div>' +
      '<div class="field"><label for="driver-company">Transport company *</label><input id="driver-company" name="companyName" required maxlength="160"></div>' +
      '<div class="field"><label for="driver-vehicle">Assign vehicle *</label><select id="driver-vehicle" name="vehicleId" required>' + options(vehicles, v => value(v, "plateNumber", "plate_number") + " · " + value(v, "vehicleType", "vehicle_type"), "Select an available vehicle") + '</select><span class="hint">' + esc(vehicleHint) + '</span></div>' +
      '<div class="field"><label for="driver-route">Assign route *</label><select id="driver-route" name="routeId" required>' + options(routes, r => value(r, "name", "nameAm", "name_am"), "Select an active route") + "</select></div>" +
      "</div><div class=\"form-actions\"><button class=\"btn btn-primary\" type=\"submit\" " + (!vehicles.length || !routes.length ? "disabled" : "") + ">Create driver account</button><button class=\"btn btn-secondary\" type=\"reset\">Clear form</button></div>" +
      '<p id="driver-form-message" class="form-message" hidden role="status"></p></form></div>' +
      '<p class="muted">New driver accounts are active after creation. Keep the password private and test the driver login before dispatch.</p>';
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
    const data = asArray(await api("/api/admin/subscriptions"), "subscriptions");
    return pageHeader("Subscriptions", "Subscription records and current payment state.") +
      section("Passenger subscriptions", data.length + " record(s)",
        table([
          { label: "Passenger", keys: ["passengerName", "passenger_name"] },
          { label: "Phone", keys: ["passengerPhone", "passenger_phone"] },
          { label: "Route", keys: ["routeName", "route_name"] },
          { label: "Price", render: r => esc(money(value(r, "priceEtb", "price_etb"))) },
          { label: "Payment", render: r => pill(value(r, "paymentStatus", "payment_status")) },
          { label: "Subscription", render: r => pill(value(r, "subscriptionStatus", "subscription_status")) },
          { label: "End date", keys: ["endDate", "end_date"] }
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
        case "create-driver": html = await renderCreateDriver(); break;
        case "vehicles": html = await renderVehicles(); break;
        case "subscriptions": html = await renderSubscriptions(); break;
        case "payments": html = await renderPayments(); break;
        case "checkins": html = await renderCheckins(); break;
        case "complaints": html = await renderComplaints(); break;
        case "audit-logs": html = await renderAuditLogs(); break;
        default: html = await renderOverview();
      }
      $("#page-content").innerHTML = html;
      const form = $("#create-driver-form");
      if (form) form.addEventListener("submit", submitDriver);
    } catch (error) {
      $("#page-content").innerHTML = '<div class="card empty-note"><strong>Could not load this page.</strong><br>' + esc(error.message) +
        '<div style="margin-top:12px"><button class="btn btn-secondary" id="retry-button">Try again</button></div></div>';
      $("#retry-button")?.addEventListener("click", renderView);
    }
  }
  async function submitDriver(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const button = form.querySelector('button[type="submit"]');
    const message = $("#driver-form-message");
    const data = Object.fromEntries(new FormData(form).entries());
    if (String(data.password || "").length < 12) {
      message.textContent = "Please choose a password with at least 12 characters.";
      message.className = "form-message error"; message.hidden = false; return;
    }
    button.disabled = true; button.textContent = "Creating account…";
    message.hidden = true;
    try {
      const result = await api("/api/admin/drivers", { method: "POST", body: JSON.stringify(data) });
      const driver = result.driver || {};
      message.textContent = "Driver account created for " + value(driver, "fullName") + ". Login username: " + value(driver, "phone") + ". Share the password privately with the driver.";
      message.className = "form-message"; message.hidden = false;
      form.reset();
      toast("Driver created and assigned successfully.");
      state.view = "drivers";
      await renderView();
    } catch (error) {
      message.textContent = error.message;
      message.className = "form-message error"; message.hidden = false;
    } finally {
      if (button.isConnected) { button.disabled = false; button.textContent = "Create driver account"; }
    }
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
