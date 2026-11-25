package com.example.ui.screens.taxi

import android.Manifest
import android.location.Location
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.example.data.api.*
import com.example.R
import com.example.data.entity.RouteEntity
import com.example.data.entity.monthlyFareForClass
import com.example.ui.components.PassoMobilityPalette
import com.example.ui.screens.driver.DriverQrScannerDialog
import com.example.ui.screens.intercity.freshMobilityLocation
import com.example.ui.screens.passenger.*
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.DriverTripState
import com.example.smoothPortalMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Approved taxi and shared-seat work is available alongside the driver's existing services. */
@Composable fun TaxiDriverScreen(main: MainViewModel, onExit: () -> Unit,
    onCargoPortal: (() -> Unit)? = null, onSignOut: () -> Unit = {}) {
    val user by main.currentUser.collectAsState()
    val language by main.currentLanguage.collectAsState()
    val model = taxiViewModel(true, true)
    TaxiDriverContent(user?.id.orEmpty(), model, onExit, main = main,
        language = language,
        showDailyCommute = user?.driverServiceType == "DAILY_COMMUTE",
        onCargoPortal = onCargoPortal?.takeIf { user?.driverServiceType in setOf("CARGO", "GROUP_TRAVEL") },
        cargoPortalLabel = if (user?.driverServiceType == "GROUP_TRAVEL") "Group travel portal" else "Cargo portal",
        onSignOut = onSignOut)
}

@Composable internal fun TaxiDriverContent(userId: String, model: TaxiViewModel, onExit: () -> Unit,
    trackingEnabled: Boolean = true, mapTiles: Boolean = true, mapApi: MobilityApi? = null,
    main: MainViewModel? = null, showDailyCommute: Boolean = true, onCargoPortal: (() -> Unit)? = null,
    cargoPortalLabel: String = "Cargo portal", onSignOut: () -> Unit = {},
    language: com.example.core.localization.AppLanguage = com.example.core.localization.AppLanguage.ENGLISH) {
    val state by model.state.collectAsState()
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("calls") }
    var vehicleId by remember { mutableStateOf("") }
    var vehicleMenu by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf<SharedRideTrip?>(null) }
    var driverLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var driverFix by remember { mutableStateOf<MobilityLocation?>(null) }
    var formPickup by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var formPickupName by remember { mutableStateOf(taxiPickupResolvingLabel(language)) }
    var driverAreaName by remember { mutableStateOf(taxiPickupResolvingLabel(language)) }
    var requestPool by remember { mutableStateOf("TAXI") }
    var driverMenu by remember { mutableStateOf(false) }
    val pushFocus by com.example.core.notifications.RideNotificationNavigation.focus.collectAsState()
    LaunchedEffect(pushFocus, model) {
        pushFocus?.takeIf { it.scope == "DRIVER" }?.let {
            page = "calls"
            when { it.eventType == "NEW_REQUEST" -> model.refresh(); it.kind == "SHARED" -> model.openNotifiedTrip(it.requestId, true); else -> model.openNotifiedTrip(it.requestId, false) }
            com.example.core.notifications.RideNotificationNavigation.consumeFocus()
        }
    }
    val trackingStatus by TaxiTracking.status.collectAsState()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (com.example.core.notifications.MobilityPush.notificationsGranted(context)) com.example.core.notifications.MobilityPush.onPermissionGranted(context)
        notice = if (taxiLocationPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()) "Permissions ready. Choose your action again." else "Precise location and notifications are required while you are online or on a trip."
    }
    fun allowed(): Boolean {
        if (taxiLocationPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
        val items = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) items.add(Manifest.permission.POST_NOTIFICATIONS)
        permissions.launch(items.toTypedArray()); return false
    }
    fun withFreshFix(resolveArea: Boolean, action: (Map<String, Any>) -> Unit) {
        if (locating || !allowed()) return
        locating = true; scope.launch {
            try {
                val location = freshMobilityLocation(context)
                check(usableTaxiFix(location)) { "Use a fresh, precise, non-simulated GPS location." }
                driverLocation = location.latitude to location.longitude
                driverFix = MobilityLocation(location.latitude, location.longitude,
                    java.time.Instant.ofEpochMilli(location.time).toString(),
                    if (location.hasAccuracy()) location.accuracy.toDouble() else null)
                if (resolveArea) {
                    val point = location.latitude to location.longitude
                    driverAreaName = nearestTaxiPickupArea(mapApi, context, point, language)
                        ?: taxiPickupPinLabel(point, language)
                }
                action(location.taxiBody())
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "A fresh GPS location is required." }
            finally { locating = false }
        }
    }
    fun withFix(action: (Map<String, Any>) -> Unit) = withFreshFix(false, action)
    fun withFixAndArea(action: (Map<String, Any>) -> Unit) = withFreshFix(true, action)
    LaunchedEffect(state.dashboard?.vehicles) { if (state.dashboard?.vehicles?.none { it.id == vehicleId } != false) vehicleId = state.dashboard?.vehicles?.firstOrNull()?.id.orEmpty() }
    val tripToTrack = state.selectedTrip?.takeIf { sharedActive(it.status) && it.driverId == userId }
        ?: state.myTrips.firstOrNull { sharedActive(it.status) }
    val activeRide = state.ride?.takeIf { taxiActive(it.status) }
    val target = when {
        activeRide != null -> TaxiTrackingTarget(userId, activeRide.vehicleId.orEmpty(), activeRide.id, sequence = activeRide.lastSequence, uploadSeconds = state.settings.gpsUploadSeconds)
        tripToTrack != null -> TaxiTrackingTarget(userId, tripToTrack.vehicleId.orEmpty(), tripToTrack.id, shared = true, uploadSeconds = state.settings.gpsUploadSeconds)
        state.dashboard?.online == true && vehicleId.isNotBlank() -> TaxiTrackingTarget(userId, vehicleId, uploadSeconds = state.settings.gpsUploadSeconds)
        else -> null
    }
    LaunchedEffect(target) {
        if (!trackingEnabled) return@LaunchedEffect
        if (target != null && target.driverId.isNotBlank() && target.vehicleId.isNotBlank()) TaxiTracking.start(context, target)
        else if (TaxiTracking.session?.target?.driverId == userId) TaxiTracking.stop()
    }
    BackHandler(enabled = driverMenu || state.selectedTrip != null || page != "calls" || onCargoPortal != null) {
        when { driverMenu -> driverMenu = false; state.selectedTrip != null -> model.closeTrip(); page != "calls" -> { page = "calls"; requested = null }; else -> onExit() }
    }
    Column(Modifier.fillMaxSize().background(CommuteCream)) {
        TaxiHeader(trailing = {
            TaxiDriverTopMenu(expanded = driverMenu, onExpandedChange = { driverMenu = it },
                onHome = { page = "calls"; model.closeTrip() },
                onTrips = { page = "trips"; model.closeTrip(); model.refresh() },
                onCredit = { page = "credit"; model.closeTrip() },
                onAlertSound = { page = "settings"; model.closeTrip() },
                onDailyCommute = if (showDailyCommute) ({ page = "commute"; model.closeTrip() }) else null,
                onCargoPortal = onCargoPortal,
                cargoPortalLabel = cargoPortalLabel,
                onSignOut = onSignOut)
        })
        TaxiNotice(notice ?: state.message) { notice = null; model.clearMessage() }
        if (state.busy || locating) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
        Box(Modifier.weight(1f).smoothPortalMotion("$page:${state.ride?.status}:${state.selectedTrip?.status}")) {
            when {
                state.selectedTrip != null -> SharedDriverTrip(model, state, ::withFix)
                state.ride != null -> DriverCurrentRide(model, state, ::withFix, mapTiles, mapApi)
                page == "commute" -> DriverDailyCommuteBrowse(main = main, onBack = { page = "calls" })
                page in setOf("street", "offer") -> TaxiTripForm(state = state, driver = true, street = page == "street", model = model,
                    onClose = { page = "calls"; requested = null }, acceptRequest = requested,
                    initialPickup = formPickup, initialOrigin = formPickupName, mapTiles = mapTiles, mapApi = mapApi, language = language,
                    onAvailabilityChange = { online, selectedVehicle ->
                        if (online) withFix { model.online(true, selectedVehicle, it) }
                        else model.online(false, selectedVehicle, emptyMap())
                    }, onCreditClick = { page = "credit" }) { input ->
                    if (allowed()) when { page == "street" -> withFix { model.street(input, it) }; requested != null -> model.acceptShared(requested!!.id, input.vehicleId.orEmpty(), input.seats ?: 1, input.farePerSeatEtb ?: 0.0); else -> model.offer(input) }
                }
                page == "settings" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Settings", color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 27.sp)
                    com.example.core.notifications.MobilityAlertToneSettings(driver = true, language = language)
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 15.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val dashboard = state.dashboard
                    if (dashboard != null) {
                        if (page == "credit") {
                            Surface(color = PassoMobilityPalette.card, contentColor = PassoMobilityPalette.text, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Available credit", color = PassoMobilityPalette.muted, fontSize = 12.sp)
                                    Text(taxiMoney(dashboard.wallet.availableEtb), fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, color = PassoMobilityPalette.brand)
                                    Text("Reserved ${taxiMoney(dashboard.wallet.heldEtb)}", color = PassoMobilityPalette.muted, fontSize = 12.sp)
                                }
                            }
                            Text("Driver credit", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
                            Text("Contact info@passo.live to top up. Only verified payments are credited. Your available balance updates as requests and trips are completed.", color = CommuteInk, fontSize = 14.sp)
                        } else if (page == "trips") {
                            Text("Your trips", fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, color = CommuteInk)
                            dashboard.sharedTrips.forEach { trip -> SharedTripRow(trip) { model.chooseTrip(trip.id) } }
                            if (dashboard.sharedTrips.isEmpty()) Text("Shared trips you publish or accept appear here.", color = Color(0xFF627385))
                        } else {
                            val requestMarkers = dashboard.calls.map { TaxiNearbyCar(it.originLatitude, it.originLongitude, it.tier) } +
                                dashboard.sharedRequests.map { TaxiNearbyCar(it.originLatitude, it.originLongitude, "GROUP") }
                            Box(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(22.dp))) {
                            TaxiMap(null, null, driver = driverFix, height = 340, pickupLabel = driverAreaName, destinationLabel = "",
                                    tileDownloadsEnabled = mapTiles, providedApi = mapApi, nearbyCars = requestMarkers,
                                    driverIcon = R.drawable.taxi_car_top_available)
                                Surface(Modifier.align(Alignment.TopStart).padding(10.dp), color = PassoMobilityPalette.card.copy(alpha = .96f),
                                    shape = RoundedCornerShape(18.dp), shadowElevation = 3.dp) {
                                    Row(Modifier.padding(start = 8.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (dashboard.online) "Online" else "Offline", color = PassoMobilityPalette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Switch(checked = dashboard.online, onCheckedChange = { online ->
                                            if (online) withFix { model.online(true, vehicleId, it) } else model.online(false, vehicleId, emptyMap())
                                        }, enabled = !state.busy && !locating && dashboard.eligible && vehicleId.isNotBlank() && (state.settings.enabled || state.settings.sharedEnabled),
                                            modifier = Modifier.scale(.82f))
                                    }
                                }
                                Surface(Modifier.align(Alignment.TopEnd).padding(10.dp).clickable { page = "credit" }, color = PassoMobilityPalette.card.copy(alpha = .96f),
                                    shape = RoundedCornerShape(18.dp), shadowElevation = 3.dp) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.AccountBalanceWallet, null, tint = PassoMobilityPalette.brand, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(5.dp)); Text(taxiMoney(dashboard.wallet.availableEtb), color = PassoMobilityPalette.brand, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                                if (locating || (target != null && trackingEnabled && trackingStatus.contains("Waiting for precise GPS", ignoreCase = true)))
                                    Surface(Modifier.align(Alignment.BottomStart).padding(10.dp), color = PassoMobilityPalette.text.copy(alpha = .9f), shape = RoundedCornerShape(12.dp)) {
                                        Text("LOADING...", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                            }
                            if (!dashboard.eligible) Text("Taxi and shared-ride access needs driver and vehicle approval.", color = CommuteInk, fontSize = 14.sp)
                            else if (dashboard.vehicles.isNotEmpty()) Box {
                                OutlinedButton(onClick = { vehicleMenu = true }, enabled = !state.busy && !dashboard.online, modifier = Modifier.fillMaxWidth()) {
                                    Text(dashboard.vehicles.find { it.id == vehicleId }?.let { "${it.model} · ${it.plateNumber}" } ?: "Choose approved vehicle")
                                    Icon(Icons.Default.ArrowDropDown, null)
                                }
                                DropdownMenu(vehicleMenu, { vehicleMenu = false }) { dashboard.vehicles.forEach { item -> DropdownMenuItem(text = { Text("${item.model} · ${item.plateNumber}") }, onClick = {
                                    if (dashboard.online) notice = "Go offline before changing your vehicle." else vehicleId = item.id
                                    vehicleMenu = false
                                }) } }
                            }
                            com.example.core.notifications.MobilityNotificationOptIn(driver = true, taxi = true)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { withFixAndArea { point ->
                                    formPickup = (point["latitude"] as Number).toDouble() to (point["longitude"] as Number).toDouble()
                                    formPickupName = driverAreaName; page = "street"; requested = null; model.clearEstimate()
                                } }, enabled = !state.busy && !locating && dashboard.eligible && state.settings.enabled,
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = CommuteMint, contentColor = CommuteTeal),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, CommuteTeal)) {
                                    Icon(Icons.Default.PersonPinCircle, null, Modifier.size(18.dp)); Text("Street pickup", Modifier.padding(start = 5.dp), fontSize = 12.sp)
                                }
                                OutlinedButton(onClick = { withFixAndArea { point ->
                                    formPickup = (point["latitude"] as Number).toDouble() to (point["longitude"] as Number).toDouble()
                                    formPickupName = driverAreaName; page = "offer"; requested = null; model.clearEstimate()
                                } }, enabled = !state.busy && !locating && dashboard.eligible && state.settings.sharedEnabled,
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = CommuteMint, contentColor = CommuteTeal),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, CommuteTeal)) {
                                    Icon(Icons.Default.Groups, null, Modifier.size(18.dp)); Text("Offer group ride", Modifier.padding(start = 5.dp), fontSize = 12.sp)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("TAXI" to "Taxi calls (${dashboard.calls.size})", "GROUP" to "Group calls (${dashboard.sharedRequests.size})").forEach { (key, label) ->
                                    val active = requestPool == key
                                    FilterChip(selected = active, onClick = { requestPool = key }, label = { Text(label, maxLines = 1) },
                                        leadingIcon = { Icon(if (key == "TAXI") Icons.Default.LocalTaxi else Icons.Default.Groups, null, Modifier.size(17.dp)) },
                                        modifier = Modifier.weight(1f).testTag("taxi_driver_pool_$key"),
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = CommuteMint,
                                            labelColor = PassoMobilityPalette.text,
                                            iconColor = CommuteTeal,
                                            selectedContainerColor = CommuteTeal,
                                            selectedLabelColor = Color.White,
                                            selectedLeadingIconColor = Color.White
                                        ))
                                }
                            }
                            if (requestPool == "TAXI") {
                                Text("Nearby requests", color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                if (dashboard.calls.isEmpty()) Text(if (dashboard.online) "No nearby calls" else "You're offline", color = PassoMobilityPalette.muted, fontSize = 14.sp)
                                dashboard.calls.forEach { call -> Surface(color = PassoMobilityPalette.card, contentColor = PassoMobilityPalette.text, shape = RoundedCornerShape(21.dp), border = androidx.compose.foundation.BorderStroke(1.dp, PassoMobilityPalette.border), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${call.origin} → ${call.destination}", color = PassoMobilityPalette.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                        Text("Approx. pickup ${pickupDistance(driverLocation, call.originLatitude to call.originLongitude)} km away · ${call.tier.replace('_', ' ')}", color = PassoMobilityPalette.muted, fontSize = 13.sp)
                                        Text("Estimated fare", color = PassoMobilityPalette.muted, fontSize = 12.sp)
                                        Text(taxiMoney(call.estimatedFareEtb), color = PassoMobilityPalette.brand, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                                        TaxiPrimary("Accept call", !state.busy && dashboard.online && vehicleId.isNotBlank(), "taxi_accept_${call.id}") { if (allowed()) model.accept(call.id, vehicleId) }
                                    }
                                } }
                            } else {
                                Text("Nearby group calls", color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                                if (dashboard.sharedRequests.isEmpty()) Text(if (dashboard.online) "No nearby group calls right now." else "Go online to receive nearby group calls.", color = PassoMobilityPalette.muted, fontSize = 14.sp)
                                dashboard.sharedRequests.forEach { request -> Surface(color = PassoMobilityPalette.card, contentColor = PassoMobilityPalette.text, shape = RoundedCornerShape(21.dp), border = androidx.compose.foundation.BorderStroke(1.dp, PassoMobilityPalette.border), modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Group call", color = PassoMobilityPalette.brand, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text("${request.origin} → ${request.destination}", color = PassoMobilityPalette.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                        Text("${request.seats.coerceAtLeast(1)} passengers · approx. pickup ${pickupDistance(driverLocation, request.originLatitude to request.originLongitude)} km away", color = PassoMobilityPalette.muted, fontSize = 13.sp)
                                        Text("Quote your fare per passenger, then send your offer.", color = PassoMobilityPalette.muted, fontSize = 12.sp)
                                        TaxiPrimary("Quote group call", !state.busy && dashboard.online && vehicleId.isNotBlank(), "taxi_group_quote_${request.id}") { requested = request; page = "offer" }
                                    }
                                } }
                            }
                        }
                    } else { CircularProgressIndicator(color = CommuteTeal); TextButton(onClick = { model.refresh() }) { Text("Refresh driver access") } }
                }
            }
        }
    }
}

@Composable private fun DriverDailyCommuteBrowse(main: MainViewModel?, onBack: () -> Unit) {
    if (main == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("driver_daily_commute_routes"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Daily commute routes", color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            Text("Route listings are unavailable right now.", color = CommuteInk)
        }
        return
    }
    val routes by main.activeRoutes.collectAsState()
    val loading by main.publicRoutesLoading.collectAsState()
    val error by main.publicRoutesError.collectAsState()
    LaunchedEffect(main) { main.refreshPublicRoutes() }
    val available = routes.filter { it.active }
    Column(Modifier.fillMaxSize().background(CommuteCream).testTag("driver_daily_commute_routes")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("driver_commute_back")) {
                Icon(Icons.Default.ArrowBack, "Back to taxi calls", tint = CommuteInk)
            }
            Column(Modifier.weight(1f)) {
                Text("Daily commute routes", color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
                Text("Available routes", color = CommuteTeal, fontSize = 12.sp)
            }
            IconButton(onClick = main::refreshPublicRoutes, enabled = !loading, modifier = Modifier.testTag("driver_commute_refresh")) {
                Icon(Icons.Default.Refresh, "Refresh routes", tint = CommuteTeal)
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 15.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { message -> item {
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(message, color = CommuteInk)
                        TextButton(onClick = main::refreshPublicRoutes, enabled = !loading) { Text("Try again", color = CommuteTeal) }
                    }
                }
            } }
            if (!loading && error == null && available.isEmpty()) item {
                Surface(color = Color.White, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("No daily commute routes are available yet.", Modifier.padding(16.dp), color = CommuteInk)
                }
            }
            items(available, key = { it.id }) { route -> DriverCommuteRouteCard(route) }
        }
    }
}

@Composable private fun DriverCommuteRouteCard(route: RouteEntity) {
    Surface(color = PassoMobilityPalette.card, contentColor = PassoMobilityPalette.text, shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PassoMobilityPalette.border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DirectionsBus, null, tint = PassoMobilityPalette.brand)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(route.name, color = PassoMobilityPalette.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (route.nameAm.isNotBlank()) Text(route.nameAm, color = PassoMobilityPalette.muted, fontSize = 13.sp)
                }
                Surface(color = CommuteMint, shape = RoundedCornerShape(20.dp)) {
                    Text("Active", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = PassoMobilityPalette.brand, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider(color = PassoMobilityPalette.border)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Morning pickup", color = PassoMobilityPalette.muted, fontSize = 11.sp)
                    Text(route.morningDeparture.ifBlank { "Not set" }, color = PassoMobilityPalette.text, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Evening return", color = PassoMobilityPalette.muted, fontSize = 11.sp)
                    Text(route.eveningDeparture.ifBlank { "Not set" }, color = PassoMobilityPalette.text, fontWeight = FontWeight.SemiBold)
                }
            }
            val fares = listOf("Economy", "Deluxe", "VIP").mapNotNull { tier -> route.monthlyFareForClass(tier)?.let { tier to it } }
            if (fares.isNotEmpty()) {
                Text("Passenger monthly fares", color = PassoMobilityPalette.muted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fares.forEach { (tier, fare) ->
                        Surface(color = CommuteMint, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(8.dp)) {
                                Text(tier, color = PassoMobilityPalette.muted, fontSize = 10.sp)
                                Text(taxiMoney(fare), color = PassoMobilityPalette.brand, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun pickupDistance(from: Pair<Double, Double>?, to: Pair<Double, Double>): String {
    if (from == null) return "—"
    val meters = FloatArray(1)
    Location.distanceBetween(from.first, from.second, to.first, to.second, meters)
    return String.format(java.util.Locale.US, "%.1f", meters[0] / 1000.0)
}

@Composable internal fun DriverCurrentRide(model: TaxiViewModel, state: TaxiUiState, withFix: ((Map<String, Any>) -> Unit) -> Unit,
    mapTiles: Boolean = true, mapApi: MobilityApi? = null) {
    val ride = state.ride ?: return; val context = LocalContext.current
    var scanOpen by remember(ride.id) { mutableStateOf(false) }
    var confirmComplete by remember { mutableStateOf(false) }
    if (confirmComplete) AlertDialog(onDismissRequest = { confirmComplete = false }, title = { Text("Complete this ride?") },
        text = { Text("Complete only after arriving at the destination. Your GPS location and final fare are confirmed once.") },
        confirmButton = { TextButton(onClick = { confirmComplete = false; withFix(model::complete) }) { Text("Complete ride") } }, dismissButton = { TextButton(onClick = { confirmComplete = false }) { Text("Keep driving") } })
    if (scanOpen) DriverQrScannerDialog(
        scanResult = null,
        currentStop = ride.origin,
        tripState = DriverTripState(tripId = ride.id, vehiclePlate = ride.vehiclePlate.orEmpty()),
        onScanToken = { token -> scanOpen = false; withFix { model.start(token, it) } },
        onDismissResult = {},
        onCloseScanner = { scanOpen = false }
    )
    if (ride.status == "IN_PROGRESS") {
        DriverActiveMeter(ride, state.busy, model, mapTiles, mapApi, withFix, onComplete = { confirmComplete = true })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TaxiMap(ride.originLatitude to ride.originLongitude, ride.destinationLatitude to ride.destinationLongitude, ride.location,
            if (ride.status == "IN_PROGRESS") "IN_PROGRESS" else "ASSIGNED", height = 320, pickupLabel = ride.origin, destinationLabel = ride.destination, tileDownloadsEnabled = mapTiles, providedApi = mapApi, routeGeometry = ride.routeGeometry, routeGeometryQuality = ride.routeGeometryQuality, freezeVehicle = ride.distancePaused,
            driverIcon = if (ride.driverId != null) R.drawable.taxi_car_top_busy else R.drawable.taxi_car_top_available)
        Text(when (ride.status) { "ACCEPTED" -> "Go to pickup"; "AT_PICKUP" -> "Ready to board"; "IN_PROGRESS" -> "Trip in progress"; "COMPLETED" -> "Trip completed"; else -> "Ride cancelled" }, color = CommuteInk, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
        ride.passengerName?.let { Text(it, color = CommuteInk, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        TaxiRouteRow("Pickup", ride.origin, true); TaxiRouteRow("Destination", ride.destination, false)
        if (!ride.passengerPhone.isNullOrBlank()) OutlinedButton(onClick = { callTaxiContact(context, ride.passengerPhone) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Phone, null); Spacer(Modifier.width(7.dp)); Text("Call passenger") }
        Text(if (ride.status in setOf("IN_PROGRESS", "COMPLETED")) "Meter fare" else "Estimated fare", color = Color(0xFF627385), fontSize = 13.sp)
        Text(taxiMoney(ride.fareEtb ?: ride.estimatedFareEtb), color = CommuteTeal, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp)
        if (ride.status in setOf("IN_PROGRESS", "COMPLETED")) {
            Text("Distance ${"%.2f".format(ride.meterDistanceKm)} km · Billable waiting ${taxiDuration(ride.billableWaitingSeconds)}", color = CommuteInk, fontSize = 14.sp)
            Text("Trip duration ${taxiDuration(ride.tripDurationSeconds)} · GPS-detected stops use the free allowance, then ${ride.waitingRateEtbPerMinute?.let(::taxiMoney) ?: "the configured rate"}/min", color = Color(0xFF627385), fontSize = 12.sp)
        }
        Text("Passenger pays you directly. The app does not collect the fare.", color = Color(0xFF627385), fontSize = 12.sp)
        when (ride.status) {
            "ACCEPTED" -> TaxiPrimary("Arrived at pickup", !state.busy, "taxi_arrive") { withFix(model::arrive) }
            "AT_PICKUP" -> {
                TaxiPrimary("Scan passenger QR to start", !state.busy, "taxi_start_meter") { scanOpen = true }
            }
            "IN_PROGRESS" -> {
                TaxiPrimary("End trip", !state.busy, "taxi_end_trip") { confirmComplete = true }
            }
            "COMPLETED", "CANCELLED" -> TaxiPrimary("Back to calls", !state.busy) { model.dismissRide() }
        }
        if (ride.status in setOf("ACCEPTED", "AT_PICKUP")) TextButton(onClick = { withFix { model.cancelRide(location = it) } }, enabled = !state.busy) { Text("Cancel ride") }
    }
}

/** Keep the meter and safety-critical completion control in view on a compact phone. */
@Composable private fun DriverActiveMeter(ride: TaxiRide, busy: Boolean, model: TaxiViewModel,
    mapTiles: Boolean, mapApi: MobilityApi?, withFix: ((Map<String, Any>) -> Unit) -> Unit, onComplete: () -> Unit) {
    val context = LocalContext.current
    val freeWaiting = taxiFreeWaitingSeconds(ride, mapTiles)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 620.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Trip in progress", Modifier.weight(1f), color = CommuteInk, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Surface(color = CommuteMint, shape = RoundedCornerShape(20.dp)) { Text("On a job", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = CommuteTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
            TaxiMap(ride.originLatitude to ride.originLongitude, ride.destinationLatitude to ride.destinationLongitude, ride.location,
                "IN_PROGRESS", height = if (compact) 220 else 260, pickupLabel = ride.origin, destinationLabel = ride.destination,
                tileDownloadsEnabled = mapTiles, providedApi = mapApi, routeGeometry = ride.routeGeometry, routeGeometryQuality = ride.routeGeometryQuality, freezeVehicle = ride.distancePaused,
                driverIcon = R.drawable.taxi_car_top_busy)
            Surface(Modifier.weight(1f).fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(color = CommuteMint, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = CommuteTeal, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (taxiGpsWaiting(ride)) "Traffic stop · timed automatically by GPS" else if (ride.source == "STREET") "Street pickup · passenger agreed" else "Passenger QR verified", color = CommuteTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(if (ride.distancePaused) "Fare so far · distance paused" else "Fare so far", color = Color(0xFF627385), fontSize = 11.sp)
                    Text(ride.fareEtb?.let { String.format(java.util.Locale.US, "ETB %,.2f", it) } ?: "Updating meter…", color = CommuteInk,
                        fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.testTag("taxi_meter_fare"))
                    HorizontalDivider(color = Color(0xFFE0E7E3))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { Text("Distance", color = Color(0xFF627385), fontSize = 11.sp)
                            Text("${"%.2f".format(java.util.Locale.US, ride.meterDistanceKm)} km", color = CommuteInk, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("taxi_meter_distance")) }
                        Column(Modifier.weight(1f)) { Text("Billable waiting", color = Color(0xFF627385), fontSize = 11.sp)
                            Text(taxiDuration(ride.billableWaitingSeconds), color = CommuteInk, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("taxi_meter_waiting")) }
                    }
                    Text(if (taxiGpsWaiting(ride)) taxiStopSummary(ride, freeWaiting) else "GPS-detected stops: ${taxiDuration(ride.waitingFreeAllowanceSeconds)} free per stop, then ${ride.waitingRateEtbPerMinute?.let(::taxiMoney) ?: "configured rate"}/min · GPS gaps not billed", color = Color(0xFF627385), fontSize = 11.sp, modifier = Modifier.testTag("taxi_meter_stop_state"))
                    HorizontalDivider(color = Color(0xFFE0E7E3))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, null, tint = CommuteTeal, modifier = Modifier.size(19.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                            Text(ride.destination, color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(ride.passengerName ?: "Street passenger", color = Color(0xFF627385), fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        if (!ride.passengerPhone.isNullOrBlank()) IconButton(onClick = { callTaxiContact(context, ride.passengerPhone) }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Phone, "Call passenger", tint = CommuteTeal, modifier = Modifier.size(22.dp)) }
                    }
                    Surface(color = CommuteMint, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    }
                    Text("Passenger pays you directly", color = Color(0xFF627385), fontSize = 11.sp)
                }
            }
            TaxiPrimary("End trip", !busy, "taxi_end_trip", onComplete)
        }
    }
}

@Composable private fun SharedDriverTrip(model: TaxiViewModel, state: TaxiUiState, withFix: ((Map<String, Any>) -> Unit) -> Unit) {
    val trip = state.selectedTrip ?: return
    var scanBooking by remember { mutableStateOf<SharedRideBooking?>(null) }
    var missing by remember { mutableStateOf<SharedRideBooking?>(null) }
    missing?.let { booking -> AlertDialog(onDismissRequest = { missing = null }, title = { Text("Mark this passenger as a no-show?") },
        text = { Text("Do this only after the departure waiting allowance has ended and the passenger has not boarded. Their seat booking will be cancelled.") },
        confirmButton = { TextButton(onClick = { missing = null; model.sharedAction("NO_SHOW", bookingId = booking.id) }) { Text("Confirm no-show") } }, dismissButton = { TextButton(onClick = { missing = null }) { Text("Keep waiting") } }) }
    scanBooking?.let { booking -> DriverQrScannerDialog(
        scanResult = null,
        currentStop = trip.origin,
        tripState = DriverTripState(tripId = trip.id, vehiclePlate = trip.vehiclePlate.orEmpty()),
        onScanToken = { token -> model.sharedAction("BOARD", bookingId = booking.id, qrToken = token); scanBooking = null },
        onDismissResult = {},
        onCloseScanner = { scanBooking = null }
    ) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(15.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        TaxiHeader("Group trip", onBack = { model.closeTrip() })
        TaxiMap(trip.originLatitude to trip.originLongitude, trip.destinationLatitude to trip.destinationLongitude, trip.location,
            if (trip.status == "IN_PROGRESS") "IN_PROGRESS" else "ASSIGNED", height = 210, pickupLabel = trip.origin, destinationLabel = trip.destination, routeGeometry = trip.routeGeometry, routeGeometryQuality = trip.routeGeometryQuality)
        Text("${trip.origin} → ${trip.destination}", color = CommuteInk, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("${taxiTime(trip.departureAt)} · ${trip.status.replace('_', ' ')}", color = CommuteTeal)
        Text("${trip.availableSeats} available seats · ${trip.farePerSeatEtb?.let(::taxiMoney) ?: "Fare pending"} per seat", color = CommuteInk)
        Text("${trip.bookings.count { it.status == "BOARDED" }} boarded · ${trip.bookings.count { it.status == "CONFIRMED" }} awaiting boarding", fontSize = 13.sp, color = Color(0xFF627385))
        when (trip.status) {
            "OPEN" -> TaxiPrimary("Open boarding", !state.busy) { withFix { model.sharedAction("BOARDING", it) } }
            "BOARDING" -> TaxiPrimary("Start shared trip", !state.busy && trip.bookings.any { it.status == "BOARDED" }) { withFix { model.sharedAction("START", it) } }
            "IN_PROGRESS" -> TaxiPrimary("Complete shared trip", !state.busy) { withFix { model.sharedAction("COMPLETE", it) } }
        }
        if (trip.status in setOf("OPEN", "BOARDING")) TextButton(onClick = { withFix { model.sharedAction("CANCEL", it) } }, enabled = !state.busy) { Text("Cancel shared trip") }
        Text("Passenger list", color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        trip.bookings.filter { it.status != "CANCELLED" }.forEach { booking ->
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(booking.passengerName ?: "Passenger", color = CommuteInk, fontWeight = FontWeight.Bold)
                    Text("Seats ${booking.seatNumbers.joinToString()} · ${booking.status} · ${taxiMoney(booking.totalEtb)}", color = Color(0xFF627385), fontSize = 13.sp)
                    if (trip.status == "BOARDING" && booking.status == "CONFIRMED") {
                        OutlinedButton(onClick = { scanBooking = booking }, enabled = !state.busy) { Text("Scan passenger QR") }
                        TextButton(onClick = { missing = booking }, enabled = !state.busy) { Text("Mark no-show after waiting allowance") }
                    }
                }
            }
        }
    }
}
