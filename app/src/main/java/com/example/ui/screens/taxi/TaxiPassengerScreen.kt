package com.example.ui.screens.taxi

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.api.*
import com.example.ui.screens.intercity.freshMobilityLocation
import com.example.ui.screens.passenger.*
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Public destination-first discovery; only requesting a ride or booking a seat requires sign-in. */
@Composable fun TaxiPassengerScreen(main: MainViewModel, onDailyCommute: () -> Unit,
    onSignIn: () -> Unit, onBack: () -> Unit, onPrivacy: () -> Unit = {},
    onProfile: () -> Unit = {}, onSignOut: () -> Unit = {}) {
    val authenticated by main.isAuthenticated.collectAsState()
    val model = taxiViewModel(authenticated, false)
    TaxiPassengerContent(authenticated, model, onDailyCommute, onSignIn, onBack, onPrivacy, onProfile, onSignOut)
}

@Composable internal fun TaxiPassengerContent(authenticated: Boolean, model: TaxiViewModel,
    onDailyCommute: () -> Unit, onSignIn: () -> Unit, onBack: () -> Unit, onPrivacy: () -> Unit = {},
    onProfile: () -> Unit = {}, onSignOut: () -> Unit = {}, mapTiles: Boolean = true) {
    val state by model.state.collectAsState()
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("home") }
    var pickup by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var destination by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var pickupName by remember { mutableStateOf("Pickup location") }
    var destinationName by remember { mutableStateOf("") }
    var tier by remember { mutableStateOf("ECONOMY") }
    var picker by remember { mutableStateOf<String?>(null) }
    var localNotice by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val pushFocus by com.example.core.notifications.RideNotificationNavigation.focus.collectAsState()
    LaunchedEffect(pushFocus, model) {
        pushFocus?.takeIf { it.scope == "PASSENGER" }?.let {
            if (it.kind == "SHARED") { page = "shared"; model.chooseTrip(it.requestId) }
            else { page = "home"; model.openRide(it.requestId) }
            com.example.core.notifications.RideNotificationNavigation.consumeFocus()
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] != true) localNotice = "Choose your pickup on the map, or allow precise location."
    }
    fun locatePickup() {
        if (locating) return
        if (!taxiLocationPermission(context)) { permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)); picker = "pickup"; return }
        locating = true
        scope.launch { try { val fix = freshMobilityLocation(context); check(usableTaxiFix(fix)) { "Use a fresh, non-simulated GPS location." }; pickup = fix.latitude to fix.longitude }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { localNotice = e.message; picker = "pickup" }
            finally { locating = false } }
    }
    fun route(): TaxiRouteInput? {
        val from = pickup ?: return null; val to = destination ?: return null
        return TaxiRouteInput(pickupName, destinationName, from.first, from.second, to.first, to.second, tier)
    }
    picker?.let { type -> TaxiLocationDialog(type == "destination", if (type == "destination") destination else pickup,
        if (type == "destination") destinationName else pickupName, { picker = null }) { name, point ->
        if (type == "destination") { destination = point; destinationName = name; if (pickup == null) locatePickup() }
        else { pickup = point; pickupName = name }
        picker = null; model.clearEstimate()
    } }
    if (more) AlertDialog(onDismissRequest = { more = false }, title = { Text("Passo Mobility") }, text = {
        Column { TextButton(onClick = { more = false; onDailyCommute() }) { Text("Daily Commute") }
            if (authenticated) TextButton(onClick = { more = false; onProfile() }) { Text("Profile & documents") }
            TextButton(onClick = { more = false; onPrivacy() }) { Text("Privacy & account") }
            if (authenticated) TextButton(onClick = { more = false; onSignOut() }) { Text("Sign out") }
            Text("Support: info@passo.live", fontSize = 14.sp) }
    }, confirmButton = { TextButton(onClick = { more = false }) { Text("Close") } })
    BackHandler { if (state.selectedTrip != null) model.closeTrip() else if (page != "home") page = "home" else onBack() }
    Column(Modifier.fillMaxSize().background(CommuteCream)) {
        TaxiHeader(onDailyCommute = onDailyCommute, onBack = if (!authenticated) onBack else null)
        TaxiNotice(localNotice ?: state.message) { localNotice = null; model.clearMessage() }
        if (state.busy || locating) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
        Box(Modifier.weight(1f)) {
            when {
                page == "shared" -> SharedPassengerContent(model, state, authenticated, onSignIn)
                page == "trips" -> TaxiPassengerHistory(model, state, authenticated, onSignIn, { page = "home" }) { page = "shared" }
                state.ride != null -> PassengerCurrentRide(state.ride!!, state.busy, model)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TaxiMap(pickup, destination, height = if (state.estimate == null) 300 else 210,
                        pickupLabel = pickupName, destinationLabel = destinationName, modifier = Modifier.padding(horizontal = 10.dp), tileDownloadsEnabled = mapTiles)
                    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(onClick = { picker = "destination" }, shape = RoundedCornerShape(19.dp), color = CommuteMint, modifier = Modifier.fillMaxWidth().testTag("taxi_where_to")) {
                                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Search, null, tint = CommuteTeal)
                                    Text(destinationName.ifBlank { "Where to?" }, Modifier.weight(1f).padding(horizontal = 11.dp), fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = 21.sp)
                                    Icon(Icons.Default.ArrowForward, null, tint = CommuteTeal) }
                            }
                            TaxiTierTiles(state.tiers, tier, !state.busy) { tier = it; model.clearEstimate() }
                            if (destination != null) {
                                TaxiRouteRow("Pickup", if (pickup == null) "Choose pickup on map" else pickupName, true, !state.busy) { picker = "pickup" }
                                state.estimate?.let { estimate ->
                                    HorizontalDivider()
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Estimated fare", color = CommuteInk); Text(taxiMoney(estimate.estimatedFareEtb), fontWeight = FontWeight.ExtraBold, color = CommuteTeal, fontSize = 22.sp) }
                                    Text("${"%.1f".format(estimate.distanceKm)} km · about ${estimate.durationMinutes.toInt()} min", color = Color(0xFF627385), fontSize = 13.sp)
                                    Text("Final fare follows the meter. Pay the driver directly.", fontSize = 12.sp, color = Color(0xFF627385))
                                    TaxiPrimary(if (authenticated) "Request ${state.tiers.find { it.id == tier }?.name ?: tier.replace('_', ' ')}" else "Sign in to request", !state.busy && state.settings.enabled, "taxi_request") {
                                        if (!authenticated) onSignIn() else route()?.let(model::request)
                                    }
                                } ?: TaxiPrimary("Show estimated fare", !state.busy && !locating && route() != null && state.settings.enabled, "taxi_estimate") { route()?.let(model::estimate) }
                            }
                            if (!state.settings.enabled) Text("Taxi rides are being prepared. Daily Commute is available above.", color = Color(0xFF627385), fontSize = 13.sp)
                            if (authenticated) com.example.core.notifications.MobilityNotificationOptIn(driver = false, taxi = true)
                            TextButton(onClick = { page = "shared" }, Modifier.align(Alignment.CenterHorizontally).testTag("taxi_group_ride")) { Icon(Icons.Default.Groups, null); Spacer(Modifier.width(8.dp)); Text("Group Ride · pay per seat", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
        NavigationBar(containerColor = Color.White) {
            listOf("Home" to Icons.Default.Home, "Trips" to Icons.Default.ReceiptLong, "Payments" to Icons.Default.CreditCard, "More" to Icons.Default.MoreHoriz).forEachIndexed { index, item ->
                NavigationBarItem(selected = (index == 0 && page == "home") || (index == 1 && page in setOf("trips", "shared")), onClick = {
                    when (index) { 0 -> { model.closeTrip(); page = "home" }; 1 -> page = "trips"; 2 -> onDailyCommute(); else -> more = true }
                }, icon = { Icon(item.second, item.first) }, label = { Text(item.first, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = CommuteTeal, indicatorColor = CommuteMint))
            }
        }
    }
}

@Composable private fun PassengerCurrentRide(ride: TaxiRide, busy: Boolean, model: TaxiViewModel) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        TaxiMap(ride.originLatitude to ride.originLongitude, ride.destinationLatitude to ride.destinationLongitude, ride.location,
            if (ride.status == "IN_PROGRESS") "IN_PROGRESS" else "ASSIGNED", height = 265, pickupLabel = ride.origin, destinationLabel = ride.destination)
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ride.status == "REQUESTED") TaxiConnectionPulse() else Text(when (ride.status) {
                "ACCEPTED" -> "Your driver is on the way"; "AT_PICKUP" -> "Your driver has arrived"; "IN_PROGRESS" -> "Your trip is in progress"; "COMPLETED" -> "You have arrived"; else -> "Ride cancelled"
            }, color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            TaxiRouteRow("Pickup", ride.origin, true); TaxiRouteRow("Destination", ride.destination, false)
            if (taxiActive(ride.status)) {
                HorizontalDivider()
                Text(ride.driverName ?: "Your assigned driver", fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = 19.sp)
                Text(listOfNotNull(ride.vehicleModel, ride.vehiclePlate).joinToString(" · "), color = Color(0xFF627385))
                OutlinedButton(onClick = { callTaxiContact(context, ride.driverPhone) }, enabled = !ride.driverPhone.isNullOrBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Phone, null); Spacer(Modifier.width(8.dp)); Text("Call driver") }
            }
            if (ride.status in setOf("ACCEPTED", "AT_PICKUP")) ride.boardingPin?.let { pin ->
                Surface(color = CommuteMint, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Share your trip PIN after you get in", color = CommuteInk, fontSize = 13.sp); Text(pin, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = 7.sp, color = CommuteTeal)
                } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (ride.status in setOf("IN_PROGRESS", "COMPLETED")) "Meter fare" else "Estimated fare", color = CommuteInk)
                Text(taxiMoney(ride.fareEtb ?: ride.estimatedFareEtb), color = CommuteTeal, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (ride.status == "IN_PROGRESS") Text("${"%.2f".format(ride.meterDistanceKm)} km · Billable waiting ${taxiDuration(ride.billableWaitingSeconds)}", color = Color(0xFF627385), fontSize = 13.sp)
            Text(if (ride.status == "COMPLETED") "Pay this fare directly to your driver. The app has not collected payment." else "Pay your driver directly. Normal traffic time is not charged as waiting.", color = Color(0xFF627385), fontSize = 12.sp)
            if (ride.stopWaitingRequested) {
                Text("Your driver requests a passenger stop. Approve only if you requested this stop; waiting charges may apply after the allowance.", color = CommuteInk, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { model.approveWait(true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Approve stop") }
                    OutlinedButton(onClick = { model.approveWait(false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Decline") } }
            }
            if (ride.status in setOf("REQUESTED", "ACCEPTED", "AT_PICKUP")) TextButton(onClick = { model.cancelRide() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel ride") }
            if (ride.status in setOf("COMPLETED", "CANCELLED")) TaxiPrimary("Done", !busy) { model.dismissRide() }
        } }
    }
}

@Composable private fun TaxiPassengerHistory(model: TaxiViewModel, state: TaxiUiState, authenticated: Boolean, onSignIn: () -> Unit, onOpenTaxi: () -> Unit, onShared: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("Your trips", color = CommuteInk, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        if (!authenticated) TaxiPrimary("Sign in to see your trips", onClick = onSignIn)
        else {
            state.myTrips.filter { it.initiatorRole == "PASSENGER" }.forEach { trip ->
                Surface(onClick = { model.chooseTrip(trip.id); onShared() }, shape = RoundedCornerShape(18.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp)) { Text("${trip.origin} → ${trip.destination}", fontWeight = FontWeight.Bold, color = CommuteInk); Text("Group request · ${trip.status.replace('_', ' ')}", color = CommuteTeal, fontSize = 13.sp) }
                }
            }
            state.bookings.forEach { booking ->
                Surface(onClick = { model.chooseTrip(booking.tripId); onShared() }, color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp)) { Text("Group Ride · seats ${booking.seatNumbers.joinToString()}", fontWeight = FontWeight.Bold, color = CommuteInk); Text("${booking.status} · ${taxiMoney(booking.totalEtb)}", color = CommuteTeal) }
                }
            }
            state.rides.forEach { ride ->
                Surface(onClick = { model.openRide(ride.id); onOpenTaxi() }, color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(15.dp)) { Text("${ride.origin} → ${ride.destination}", fontWeight = FontWeight.Bold, color = CommuteInk); Text("${ride.status.replace('_', ' ')} · ${taxiMoney(ride.fareEtb ?: ride.estimatedFareEtb)}", color = CommuteTeal) }
                }
            }
            if (state.rides.isEmpty() && state.bookings.isEmpty() && state.myTrips.isEmpty()) Text("Your taxi and shared-seat trips will appear here.", color = Color(0xFF627385))
        }
        TextButton(onClick = onShared) { Text("Find a Group Ride") }
    }
}
