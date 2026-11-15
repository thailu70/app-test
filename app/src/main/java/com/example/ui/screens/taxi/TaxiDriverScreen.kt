package com.example.ui.screens.taxi

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.example.data.api.*
import com.example.ui.screens.intercity.freshMobilityLocation
import com.example.ui.screens.passenger.*
import com.example.ui.viewmodel.MainViewModel
import com.example.smoothPortalMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Approved taxi and shared-seat work is available alongside the driver's existing services. */
@Composable fun TaxiDriverScreen(main: MainViewModel, onExit: () -> Unit) {
    val user by main.currentUser.collectAsState()
    val model = taxiViewModel(true, true)
    TaxiDriverContent(user?.id.orEmpty(), model, onExit)
}

@Composable internal fun TaxiDriverContent(userId: String, model: TaxiViewModel, onExit: () -> Unit,
    trackingEnabled: Boolean = true, mapTiles: Boolean = true, mapApi: MobilityApi? = null) {
    val state by model.state.collectAsState()
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("calls") }
    var vehicleId by remember { mutableStateOf("") }
    var vehicleMenu by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf<SharedRideTrip?>(null) }
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
    fun withFix(action: (Map<String, Any>) -> Unit) {
        if (locating || !allowed()) return
        locating = true; scope.launch {
            try { val location = freshMobilityLocation(context); check(usableTaxiFix(location)) { "Use a fresh, precise, non-simulated GPS location." }; action(location.taxiBody()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "A fresh GPS location is required." }
            finally { locating = false }
        }
    }
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
    BackHandler { when { state.selectedTrip != null -> model.closeTrip(); page != "calls" -> { page = "calls"; requested = null }; else -> onExit() } }
    Column(Modifier.fillMaxSize().background(CommuteCream)) {
        TaxiHeader(onBack = onExit, onDailyCommute = onExit)
        TaxiNotice(notice ?: state.message) { notice = null; model.clearMessage() }
        if (state.busy || locating) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
        if (target != null && trackingEnabled) Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(trackingStatus, Modifier.weight(1f), color = Color(0xFF627385), fontSize = 12.sp)
            if (TaxiTracking.session == null) TextButton(onClick = { if (allowed()) TaxiTracking.start(context, target) }) { Text("Resume sharing") }
        }
        Box(Modifier.weight(1f).smoothPortalMotion("$page:${state.ride?.status}:${state.selectedTrip?.status}")) {
            when {
                state.selectedTrip != null -> SharedDriverTrip(model, state, ::withFix)
                state.ride != null -> DriverCurrentRide(model, state, ::withFix, mapTiles, mapApi)
                page in setOf("street", "offer") -> TaxiTripForm(state, true, page == "street", model, { page = "calls"; requested = null }, acceptRequest = requested) { input ->
                    if (allowed()) when { page == "street" -> withFix { model.street(input, it) }; requested != null -> model.acceptShared(requested!!.id, input.vehicleId.orEmpty(), input.seats ?: 1, input.farePerSeatEtb ?: 0.0); else -> model.offer(input) }
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(15.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (page == "trips") "Your trips" else "Ride calls", Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = CommuteInk)
                        Text(if (state.dashboard?.online == true) "Online" else "Offline", color = CommuteTeal, fontSize = 13.sp)
                        Switch(state.dashboard?.online == true, onCheckedChange = { online ->
                            if (online) withFix { model.online(true, vehicleId, it) } else model.online(false, vehicleId, emptyMap())
                        }, enabled = !state.busy && !locating && state.dashboard?.eligible == true && vehicleId.isNotBlank() && (state.settings.enabled || state.settings.sharedEnabled))
                    }
                    val dashboard = state.dashboard
                    if (dashboard != null) {
                        Text("Your approximate location is visible while online. Live trip location is shared with your passenger.", color = Color(0xFF627385), fontSize = 12.sp)
                        Surface(color = CommuteMint, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AccountBalanceWallet, null, tint = CommuteTeal)
                                Column(Modifier.weight(1f).padding(start = 11.dp)) { Text("Available credit", color = CommuteInk, fontSize = 12.sp); Text(taxiMoney(dashboard.wallet.availableEtb), fontWeight = FontWeight.ExtraBold, fontSize = 25.sp, color = CommuteTeal); Text("Reserved ${taxiMoney(dashboard.wallet.heldEtb)}", color = Color(0xFF627385), fontSize = 12.sp) }
                            }
                        }
                        if (!dashboard.eligible) Text("Taxi and shared-ride access needs separate driver and vehicle approval. Contact Passo after completing your driver documents.", color = CommuteInk, fontSize = 14.sp)
                        else if (dashboard.vehicles.isNotEmpty()) Box {
                            OutlinedButton(onClick = { vehicleMenu = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(dashboard.vehicles.find { it.id == vehicleId }?.let { "${it.model} · ${it.plateNumber}" } ?: "Choose approved vehicle"); Icon(Icons.Default.ArrowDropDown, null) }
                            DropdownMenu(vehicleMenu, { vehicleMenu = false }) { dashboard.vehicles.forEach { item -> DropdownMenuItem(text = { Text("${item.model} · ${item.plateNumber}") }, onClick = {
                                if (dashboard.online) notice = "Go offline before changing your vehicle." else vehicleId = item.id
                                vehicleMenu = false
                            }) } }
                        }
                        com.example.core.notifications.MobilityNotificationOptIn(driver = true, taxi = true)
                        if (page == "credit") {
                            Text("Driver credit", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
                            Text("Contact info@passo.live to top up. Only verified payments are credited. Passo reserves the disclosed service fee and settles it once the journey is completed.", color = CommuteInk, fontSize = 14.sp)
                        } else if (page == "trips") {
                            dashboard.sharedTrips.forEach { trip -> SharedTripRow(trip) { model.chooseTrip(trip.id) } }
                            if (dashboard.sharedTrips.isEmpty()) Text("Shared trips you publish or accept appear here.", color = Color(0xFF627385))
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { page = "street"; model.clearEstimate() }, enabled = !state.busy && dashboard.eligible && state.settings.enabled, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PersonPinCircle, null, Modifier.size(18.dp)); Text("Street pickup", Modifier.padding(start = 5.dp), fontSize = 12.sp) }
                                OutlinedButton(onClick = { page = "offer"; requested = null }, enabled = !state.busy && dashboard.eligible && state.settings.sharedEnabled, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Groups, null, Modifier.size(18.dp)); Text("Offer group ride", Modifier.padding(start = 5.dp), fontSize = 12.sp) }
                            }
                            Text("Nearby taxi calls", color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            if (dashboard.calls.isEmpty()) Text(if (dashboard.online) "No matching nearby calls right now. This list updates automatically." else "Go online with a fresh location to view nearby calls.", color = Color(0xFF627385), fontSize = 14.sp)
                            dashboard.calls.forEach { call -> Surface(color = Color.White, shape = RoundedCornerShape(21.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Text("${call.origin} → ${call.destination}", color = CommuteInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text("${call.tier.replace('_', ' ')} · ${"%.1f".format(call.distanceKm)} km · about ${call.durationMinutes.toInt()} min", color = Color(0xFF627385), fontSize = 13.sp)
                                    Text(taxiMoney(call.estimatedFareEtb), color = CommuteTeal, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                                    Text("Passo fee: ${taxiMoney(call.commissionEtb)} · reserved when accepted", fontSize = 12.sp, color = Color(0xFF627385))
                                    TaxiPrimary("Accept call", !state.busy && dashboard.online && vehicleId.isNotBlank(), "taxi_accept_${call.id}") { if (allowed()) model.accept(call.id, vehicleId) }
                                }
                            } }
                            Text("Requested group routes", color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                            if (dashboard.sharedRequests.isEmpty()) Text("Passenger group requests will appear here.", color = Color(0xFF627385), fontSize = 14.sp)
                            dashboard.sharedRequests.forEach { request -> SharedTripRow(request) { requested = request; page = "offer" } }
                        }
                    } else { CircularProgressIndicator(color = CommuteTeal); TextButton(onClick = { model.refresh() }) { Text("Refresh driver access") } }
                }
            }
        }
        NavigationBar(containerColor = Color.White) {
            listOf("Calls" to Icons.Default.DirectionsCar, "Trips" to Icons.Default.ReceiptLong, "Credit" to Icons.Default.AccountBalanceWallet, "More" to Icons.Default.MoreHoriz).forEachIndexed { index, pair ->
                NavigationBarItem(selected = page == listOf("calls", "trips", "credit", "more")[index], onClick = {
                    if (index == 3) onExit() else { model.closeTrip(); page = listOf("calls", "trips", "credit")[index] }
                }, icon = { Icon(pair.second, pair.first) }, label = { Text(pair.first, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = CommuteTeal, indicatorColor = CommuteMint))
            }
        }
    }
}

@Composable internal fun DriverCurrentRide(model: TaxiViewModel, state: TaxiUiState, withFix: ((Map<String, Any>) -> Unit) -> Unit,
    mapTiles: Boolean = true, mapApi: MobilityApi? = null) {
    val ride = state.ride ?: return; val context = LocalContext.current
    var pin by remember(ride.id) { mutableStateOf("") }
    var confirmComplete by remember { mutableStateOf(false) }
    if (confirmComplete) AlertDialog(onDismissRequest = { confirmComplete = false }, title = { Text("Complete this ride?") },
        text = { Text("Complete only after arriving at the destination. The server confirms your GPS location, final fare and Passo fee once.") },
        confirmButton = { TextButton(onClick = { confirmComplete = false; withFix(model::complete) }) { Text("Complete ride") } }, dismissButton = { TextButton(onClick = { confirmComplete = false }) { Text("Keep driving") } })
    if (ride.status == "IN_PROGRESS") {
        DriverActiveMeter(ride, state.busy, model, mapTiles, mapApi, onComplete = { confirmComplete = true })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TaxiMap(ride.originLatitude to ride.originLongitude, ride.destinationLatitude to ride.destinationLongitude, ride.location,
            if (ride.status == "IN_PROGRESS") "IN_PROGRESS" else "ASSIGNED", height = 240, pickupLabel = ride.origin, destinationLabel = ride.destination, tileDownloadsEnabled = mapTiles, providedApi = mapApi)
        Text(when (ride.status) { "ACCEPTED" -> "Go to pickup"; "AT_PICKUP" -> "Ready to board"; "IN_PROGRESS" -> "Trip in progress"; "COMPLETED" -> "Trip completed"; else -> "Ride cancelled" }, color = CommuteInk, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
        ride.passengerName?.let { Text(it, color = CommuteInk, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        TaxiRouteRow("Pickup", ride.origin, true); TaxiRouteRow("Destination", ride.destination, false)
        if (!ride.passengerPhone.isNullOrBlank()) OutlinedButton(onClick = { callTaxiContact(context, ride.passengerPhone) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Phone, null); Spacer(Modifier.width(7.dp)); Text("Call passenger") }
        Text(if (ride.status in setOf("IN_PROGRESS", "COMPLETED")) "Meter fare" else "Estimated fare", color = Color(0xFF627385), fontSize = 13.sp)
        Text(taxiMoney(ride.fareEtb ?: ride.estimatedFareEtb), color = CommuteTeal, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp)
        if (ride.status in setOf("IN_PROGRESS", "COMPLETED")) {
            Text("Distance ${"%.2f".format(ride.meterDistanceKm)} km · Billable waiting ${taxiDuration(ride.billableWaitingSeconds)}", color = CommuteInk, fontSize = 14.sp)
            Text("Trip duration ${taxiDuration(ride.tripDurationSeconds)} · normal traffic time is not charged", color = Color(0xFF627385), fontSize = 12.sp)
        }
        Text("Passo fee ${taxiMoney(ride.commissionEtb)} · ${if (ride.status == "COMPLETED") "settled once" else "reserved"}", color = CommuteTeal, fontSize = 13.sp)
        Text("Passenger pays you directly. The app does not collect the fare.", color = Color(0xFF627385), fontSize = 12.sp)
        when (ride.status) {
            "ACCEPTED" -> TaxiPrimary("Arrived at pickup", !state.busy, "taxi_arrive") { withFix(model::arrive) }
            "AT_PICKUP" -> {
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("Passenger's trip PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("taxi_boarding_pin"))
                TaxiPrimary("Start meter", !state.busy && pin.length >= 4, "taxi_start_meter") { withFix { model.start(pin, it) } }
            }
            "IN_PROGRESS" -> {
                if (ride.source != "STREET") OutlinedButton(onClick = { model.wait(if (ride.waiting == "STOP") "END_STOP" else "REQUEST_STOP") }, enabled = !state.busy && !ride.stopWaitingRequested, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PauseCircle, null); Spacer(Modifier.width(8.dp)); Text(if (ride.waiting == "STOP") "End passenger stop" else if (ride.stopWaitingRequested) "Waiting for passenger approval" else "Request passenger stop") }
                TaxiPrimary("End trip", !state.busy, "taxi_end_trip") { confirmComplete = true }
            }
            "COMPLETED", "CANCELLED" -> TaxiPrimary("Back to calls", !state.busy) { model.dismissRide() }
        }
        if (ride.status in setOf("ACCEPTED", "AT_PICKUP")) TextButton(onClick = { model.cancelRide() }, enabled = !state.busy) { Text("Cancel ride") }
    }
}

/** Keep the meter and safety-critical completion control in view on a compact phone. */
@Composable private fun DriverActiveMeter(ride: TaxiRide, busy: Boolean, model: TaxiViewModel,
    mapTiles: Boolean, mapApi: MobilityApi?, onComplete: () -> Unit) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 620.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Trip in progress", Modifier.weight(1f), color = CommuteInk, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Surface(color = CommuteMint, shape = RoundedCornerShape(20.dp)) { Text("On a job", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = CommuteTeal, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
            TaxiMap(ride.originLatitude to ride.originLongitude, ride.destinationLatitude to ride.destinationLongitude, ride.location,
                "IN_PROGRESS", height = if (compact) 140 else 160, pickupLabel = ride.origin, destinationLabel = ride.destination,
                tileDownloadsEnabled = mapTiles, providedApi = mapApi)
            Surface(Modifier.weight(1f).fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(color = CommuteMint, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = CommuteTeal, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (ride.source == "STREET") "Street pickup · passenger agreed" else "Passenger PIN verified", color = CommuteTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text("Fare so far", color = Color(0xFF627385), fontSize = 11.sp)
                    Text(ride.fareEtb?.let { String.format(java.util.Locale.US, "ETB %,.2f", it) } ?: "Updating meter…", color = CommuteInk,
                        fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.testTag("taxi_meter_fare"))
                    HorizontalDivider(color = Color(0xFFE0E7E3))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { Text("Distance", color = Color(0xFF627385), fontSize = 11.sp)
                            Text("${"%.2f".format(java.util.Locale.US, ride.meterDistanceKm)} km", color = CommuteInk, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("taxi_meter_distance")) }
                        Column(Modifier.weight(1f)) { Text("Billable waiting", color = Color(0xFF627385), fontSize = 11.sp)
                            Text(taxiDuration(ride.billableWaitingSeconds), color = CommuteInk, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("taxi_meter_waiting")) }
                    }
                    Text("Trip duration ${taxiDuration(ride.tripDurationSeconds)} · traffic time not billed", color = Color(0xFF627385), fontSize = 11.sp)
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
                        Text("Passo fee: ${taxiMoney(ride.commissionEtb)} reserved", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = CommuteTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Passenger pays you directly", color = Color(0xFF627385), fontSize = 11.sp)
                }
            }
            if (ride.source != "STREET") OutlinedButton(onClick = { model.wait(if (ride.waiting == "STOP") "END_STOP" else "REQUEST_STOP") }, enabled = !busy && !ride.stopWaitingRequested,
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).testTag("taxi_passenger_stop"), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.PauseCircle, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(7.dp))
                Text(if (ride.waiting == "STOP") "End passenger stop" else if (ride.stopWaitingRequested) "Waiting for passenger approval" else "Request passenger stop", fontSize = 13.sp, maxLines = 2)
            }
            TaxiPrimary("End trip", !busy, "taxi_end_trip", onComplete)
        }
    }
}

@Composable private fun SharedDriverTrip(model: TaxiViewModel, state: TaxiUiState, withFix: ((Map<String, Any>) -> Unit) -> Unit) {
    val trip = state.selectedTrip ?: return
    var boarding by remember { mutableStateOf<SharedRideBooking?>(null) }; var pin by remember { mutableStateOf("") }
    var missing by remember { mutableStateOf<SharedRideBooking?>(null) }
    missing?.let { booking -> AlertDialog(onDismissRequest = { missing = null }, title = { Text("Mark this passenger as a no-show?") },
        text = { Text("Do this only after the departure waiting allowance has ended and the passenger has not boarded. Their seat booking will be cancelled.") },
        confirmButton = { TextButton(onClick = { missing = null; model.sharedAction("NO_SHOW", bookingId = booking.id) }) { Text("Confirm no-show") } }, dismissButton = { TextButton(onClick = { missing = null }) { Text("Keep waiting") } }) }
    boarding?.let { booking -> AlertDialog(onDismissRequest = { boarding = null }, title = { Text("Board ${booking.passengerName ?: "passenger"}") },
        text = { OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("Passenger boarding PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)) },
        confirmButton = { TextButton(onClick = { model.sharedAction("BOARD", bookingId = booking.id, pin = pin); boarding = null; pin = "" }, enabled = pin.length >= 4 && !state.busy) { Text("Confirm boarding") } }, dismissButton = { TextButton(onClick = { boarding = null }) { Text("Cancel") } }) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(15.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        TaxiHeader("Group trip", onBack = { model.closeTrip() })
        TaxiMap(trip.originLatitude to trip.originLongitude, trip.destinationLatitude to trip.destinationLongitude, trip.location,
            if (trip.status == "IN_PROGRESS") "IN_PROGRESS" else "ASSIGNED", height = 210, pickupLabel = trip.origin, destinationLabel = trip.destination)
        Text("${trip.origin} → ${trip.destination}", color = CommuteInk, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("${taxiTime(trip.departureAt)} · ${trip.status.replace('_', ' ')}", color = CommuteTeal)
        Text("${trip.availableSeats} available seats · ${trip.farePerSeatEtb?.let(::taxiMoney) ?: "Fare pending"} per seat", color = CommuteInk)
        Text("${trip.bookings.count { it.status == "BOARDED" }} boarded · ${trip.bookings.count { it.status == "CONFIRMED" }} awaiting boarding", fontSize = 13.sp, color = Color(0xFF627385))
        when (trip.status) {
            "OPEN" -> TaxiPrimary("Open boarding", !state.busy) { withFix { model.sharedAction("BOARDING", it) } }
            "BOARDING" -> TaxiPrimary("Start shared trip", !state.busy && trip.bookings.any { it.status == "BOARDED" }) { withFix { model.sharedAction("START", it) } }
            "IN_PROGRESS" -> TaxiPrimary("Complete shared trip", !state.busy) { withFix { model.sharedAction("COMPLETE", it) } }
        }
        if (trip.status in setOf("OPEN", "BOARDING")) TextButton(onClick = { model.sharedAction("CANCEL") }, enabled = !state.busy) { Text("Cancel shared trip") }
        Text("Passenger list", color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        trip.bookings.filter { it.status != "CANCELLED" }.forEach { booking ->
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(booking.passengerName ?: "Passenger", color = CommuteInk, fontWeight = FontWeight.Bold)
                    Text("Seats ${booking.seatNumbers.joinToString()} · ${booking.status} · ${taxiMoney(booking.totalEtb)}", color = Color(0xFF627385), fontSize = 13.sp)
                    if (trip.status == "BOARDING" && booking.status == "CONFIRMED") {
                        OutlinedButton(onClick = { boarding = booking; pin = "" }, enabled = !state.busy) { Text("Board with passenger PIN") }
                        TextButton(onClick = { missing = booking }, enabled = !state.busy) { Text("Mark no-show after waiting allowance") }
                    }
                }
            }
        }
    }
}
