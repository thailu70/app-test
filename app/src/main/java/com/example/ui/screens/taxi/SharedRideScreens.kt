package com.example.ui.screens.taxi

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.api.*
import com.example.core.localization.AppLanguage
import com.example.core.time.EthiopianDateTime
import com.example.ui.components.EthiopianDatePickerDialog
import com.example.R
import com.example.ui.components.PassoMobilityPalette
import com.example.ui.components.QrCodeCanvas
import com.example.ui.screens.intercity.freshMobilityLocation
import com.example.ui.screens.intercity.mobilityDepartureIso
import com.example.ui.screens.intercity.EthiopianTimeField
import com.example.ui.screens.passenger.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable internal fun SharedPassengerContent(model: TaxiViewModel, state: TaxiUiState, authenticated: Boolean, onSignIn: () -> Unit, onBack: () -> Unit = {}, initialCreate: Boolean = false, passengerPickup: Pair<Double, Double>? = null, passengerPickupName: String = "Finding nearby area…", language: AppLanguage = AppLanguage.ENGLISH) {
    var creating by remember { mutableStateOf(initialCreate) }
    val trip = state.selectedTrip
    Column(Modifier.fillMaxSize().background(PassoMobilityPalette.canvas)) {
        TaxiHeader("Group Ride", onBack = { when { trip != null -> model.closeTrip(); creating -> creating = false; else -> onBack() } })
        when {
            trip != null -> SharedTripDetail(model, state, authenticated, onSignIn)
            creating -> TaxiTripForm(state, driver = false, street = false, model = model, onClose = { creating = false }, passengerGroupRequest = true, initialPickup = passengerPickup, initialOrigin = passengerPickupName, language = language) { input -> if (authenticated) model.sharedRequest(input) else onSignIn() }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("Share the route. Pay per seat.", color = CommuteInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                TaxiPrimary("Start a group ride", !state.busy && state.settings.sharedEnabled, "shared_start_request") { if (authenticated) creating = true else { TaxiPendingNavigation.shared(start = true); onSignIn() } }
                if (!state.settings.sharedEnabled) Text("Shared rides are being prepared. Published trips will appear here when available.", color = Color(0xFF627385))
                Text("Find a seat", color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp)
                if (state.trips.isEmpty()) Text("No shared trips are available yet. You can request your route and let an eligible driver respond.", color = Color(0xFF627385))
                state.trips.forEach { item -> SharedTripRow(item) { model.chooseTrip(item.id) } }
            }
        }
    }
}

@Composable internal fun SharedTripRow(trip: SharedRideTrip, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("shared_trip_${trip.id}"), color = PassoMobilityPalette.card, contentColor = PassoMobilityPalette.text, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, PassoMobilityPalette.border)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Groups, null, tint = PassoMobilityPalette.brand); Text("${trip.origin} → ${trip.destination}", Modifier.weight(1f).padding(start = 9.dp), fontWeight = FontWeight.Bold, color = PassoMobilityPalette.text, fontSize = 17.sp); Icon(Icons.Default.ChevronRight, null, tint = PassoMobilityPalette.brand) }
            Text(taxiTime(trip.departureAt), color = PassoMobilityPalette.muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (trip.status == "REQUESTED") "${trip.seats} seats requested" else "${trip.availableSeats} seats available", color = PassoMobilityPalette.brand, fontSize = 13.sp)
                Text(trip.farePerSeatEtb?.let { "${taxiMoney(it)} / seat" } ?: "Awaiting a driver offer", fontWeight = FontWeight.Bold, color = PassoMobilityPalette.text, fontSize = 13.sp)
            }
        }
    }
}

@Composable internal fun SharedTripDetail(model: TaxiViewModel, state: TaxiUiState, authenticated: Boolean, onSignIn: () -> Unit, mapTiles: Boolean = true, mapApi: MobilityApi? = null) {
    val trip = state.selectedTrip ?: return
    val booking = state.selectedBooking
    var selected by remember(trip.id, state.occupiedSeats) { mutableStateOf<List<Int>>(emptyList()) }
    var cancelRequest by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (cancelRequest) AlertDialog(onDismissRequest = { cancelRequest = false }, title = { Text("Cancel this group request?") },
        text = { Text("You can cancel your requested route before any passenger booking is active.") },
        confirmButton = { TextButton(onClick = { cancelRequest = false; model.cancelSharedRequest() }) { Text("Cancel request") } }, dismissButton = { TextButton(onClick = { cancelRequest = false }) { Text("Keep request") } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        TaxiMap(trip.originLatitude to trip.originLongitude, trip.destinationLatitude to trip.destinationLongitude, trip.location,
            if (trip.status == "IN_PROGRESS") "IN_PROGRESS" else "ASSIGNED", height = 210, pickupLabel = trip.origin, destinationLabel = trip.destination, tileDownloadsEnabled = mapTiles, providedApi = mapApi, routeGeometry = trip.routeGeometry, routeGeometryQuality = trip.routeGeometryQuality)
        Text("${trip.origin} → ${trip.destination}", color = CommuteInk, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
        Text(taxiTime(trip.departureAt), color = CommuteTeal)
        if (trip.status == "REQUESTED") {
            Text("Waiting for an eligible driver", color = CommuteInk, fontWeight = FontWeight.Bold)
            Text("A driver will propose the per-seat fare and vehicle. You choose whether to book after the offer is available.", color = Color(0xFF627385), fontSize = 14.sp)
        } else {
            Text(listOfNotNull(trip.driverName, trip.vehicleModel).joinToString(" · "), color = CommuteInk, fontWeight = FontWeight.SemiBold)
            trip.farePerSeatEtb?.let { Text("${taxiMoney(it)} per seat", color = CommuteTeal, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp) }
            if (booking != null && booking.status != "CANCELLED") {
                Surface(color = CommuteMint, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Your booking · ${booking.status.replace('_', ' ')}", fontWeight = FontWeight.Bold, color = CommuteInk)
                        Text("Seats ${booking.seatNumbers.joinToString()} · ${taxiMoney(booking.totalEtb)}", color = CommuteInk)
                        if (booking.status == "CONFIRMED") booking.boardingQrToken?.let { token ->
                            Text("Show this QR to the driver when boarding", fontSize = 12.sp, color = CommuteInk)
                            QrCodeCanvas(token, Modifier.align(Alignment.CenterHorizontally).size(176.dp).padding(top = 6.dp), moduleColor = Color.Black)
                        }
                        Text("Pay your driver directly. This booking does not collect payment.", fontSize = 12.sp, color = CommuteInk)
                    }
                }
                trip.driverPhone?.let { OutlinedButton(onClick = { callTaxiContact(context, it) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Phone, null); Spacer(Modifier.width(7.dp)); Text("Call driver") } }
                if (trip.status == "OPEN" && booking.status == "CONFIRMED") TextButton(onClick = { model.cancelBooking(booking.id) }, enabled = !state.busy) { Text("Cancel my booking") }
            } else if (trip.status == "OPEN") {
                Text("Choose your seats", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text("Shared with other passengers. Your fare is locked when you book.", color = Color(0xFF627385), fontSize = 13.sp)
                for (row in (1..trip.seats.coerceIn(0, 100)).chunked(4)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { number ->
                        val occupied = number in state.occupiedSeats; val chosen = number in selected
                        OutlinedButton(onClick = { selected = if (chosen) selected - number else selected + number }, enabled = !occupied && !state.busy,
                            modifier = Modifier.weight(1f).heightIn(min = 49.dp).testTag("shared_seat_$number"), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (chosen) CommuteMint else Color.Transparent)) {
                            Icon(Icons.Default.EventSeat, null, Modifier.size(17.dp)); Text(number.toString(), Modifier.padding(start = 3.dp))
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                val seatLabel = if (selected.size == 1) "seat" else "seats"
                if (selected.isNotEmpty()) Text("Total: ${taxiMoney((trip.farePerSeatEtb ?: 0.0) * selected.size)} for ${selected.size} $seatLabel", color = CommuteInk, fontWeight = FontWeight.Bold)
                TaxiPrimary(if (authenticated) "Book ${selected.size} $seatLabel" else "Sign in to book", !state.busy && selected.isNotEmpty(), "shared_book_seats") { if (authenticated) model.book(selected.sorted()) else { TaxiPendingNavigation.shared(trip.id); onSignIn() } }
            } else Text(trip.status.replace('_', ' '), color = CommuteTeal, fontWeight = FontWeight.Bold)
        }
        if (authenticated && trip.status in setOf("REQUESTED", "OPEN") && state.myTrips.any { it.id == trip.id && it.initiatorRole == "PASSENGER" })
            TextButton(onClick = { cancelRequest = true }, enabled = !state.busy) { Text("Cancel my group request") }
    }
}

/** Same location search/map controls for street pickup and both shared-trip initiation paths. */
@Composable internal fun TaxiTripForm(state: TaxiUiState, driver: Boolean, street: Boolean, model: TaxiViewModel,
    onClose: () -> Unit, acceptRequest: SharedRideTrip? = null, passengerGroupRequest: Boolean = false,
    initialPickup: Pair<Double, Double>? = null, initialOrigin: String = "Finding nearby area…",
    mapTiles: Boolean = true, mapApi: MobilityApi? = null,
    onAvailabilityChange: (Boolean, String) -> Unit = { _, _ -> }, onCreditClick: () -> Unit = {},
    language: AppLanguage = AppLanguage.ENGLISH,
    onSubmit: (TaxiRouteInput) -> Unit) {
    val context = LocalContext.current
    var origin by remember(acceptRequest?.id, initialOrigin, street, passengerGroupRequest) {
        mutableStateOf(acceptRequest?.origin ?: if (passengerGroupRequest || street) initialOrigin else "")
    }
    var destination by remember(acceptRequest?.id) { mutableStateOf(acceptRequest?.destination.orEmpty()) }
    var pickup by remember(acceptRequest?.id, initialPickup) { mutableStateOf(acceptRequest?.let { it.originLatitude to it.originLongitude } ?: initialPickup) }
    var dropoff by remember(acceptRequest?.id) { mutableStateOf(acceptRequest?.let { it.destinationLatitude to it.destinationLongitude }) }
    var picker by remember { mutableStateOf<String?>(null) }
    var vehicleId by remember { mutableStateOf(state.dashboard?.vehicles?.firstOrNull()?.id.orEmpty()) }
    var tier by remember { mutableStateOf("ECONOMY") }
    var vehicleMenu by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(acceptRequest?.seats ?: 1) }
    var fare by remember { mutableStateOf("") }
    var streetPassengerName by remember { mutableStateOf("") }
    var streetPassengerPhone by remember { mutableStateOf("") }
    var lastEstimatedRouteKey by remember(acceptRequest?.id) { mutableStateOf<String?>(null) }
    val calendar = remember { Calendar.getInstance(TimeZone.getTimeZone("Africa/Addis_Ababa")).apply { add(Calendar.MINUTE, 30) } }
    var date by remember { mutableStateOf(EthiopianDateTime.clockDateIso(calendar.timeInMillis)) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf(EthiopianDateTime.clockValueFromEAT(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))) }
    val vehicles = state.dashboard?.vehicles.orEmpty()
    val vehicle = vehicles.find { it.id == vehicleId }
    val capacity = if (driver) vehicle?.capacityLimit?.coerceIn(0, 100) ?: 0 else 100
    val streetContactReady = streetPassengerPhone.filter(Char::isDigit).length >= 9
    val departure = acceptRequest?.departureAt ?: mobilityDepartureIso(date, time)
    val from = pickup; val to = dropoff
    val base = if (from != null && to != null && from != to && origin.isNotBlank() && destination.isNotBlank())
        TaxiRouteInput(origin, destination, from.first, from.second, to.first, to.second, if (driver) vehicle?.tier ?: tier else tier,
        vehicleId = vehicleId.takeIf { driver }, passengerConsent = if (street) true else null,
            departureAt = if (!street) departure else null, seats = if (!street) count else null,
            farePerSeatEtb = if (driver && !street) fare.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } else null,
            passengerName = null, passengerPhone = streetPassengerPhone.takeIf { street }) else null
    val estimateRouteKey = base?.let { route ->
        listOf(route.origin, route.destination, route.originLatitude, route.originLongitude, route.destinationLatitude,
            route.destinationLongitude, route.tier, route.vehicleId.orEmpty()).joinToString("|")
    }
    LaunchedEffect(driver, street, passengerGroupRequest, estimateRouteKey, acceptRequest?.id) {
        val route = base
        if ((driver || passengerGroupRequest) && !street && route != null && !isGenericTaxiPickupLabel(route.origin) && (!driver || vehicle != null) &&
            estimateRouteKey != null && lastEstimatedRouteKey != estimateRouteKey) {
            lastEstimatedRouteKey = estimateRouteKey
            model.estimate(route.copy(farePerSeatEtb = null))
        }
    }
    LaunchedEffect(driver, passengerGroupRequest, pickup, origin, mapApi, language) {
        val point = pickup
        if ((driver || passengerGroupRequest) && point != null && isGenericTaxiPickupLabel(origin)) {
            repeat(3) { attempt ->
                val resolved = nearestTaxiPickupArea(mapApi, context, point, language)
                if (!resolved.isNullOrBlank() && pickup == point && isGenericTaxiPickupLabel(origin)) {
                    origin = resolved
                    return@LaunchedEffect
                }
                if (attempt < 2) kotlinx.coroutines.delay(700L * (attempt + 1))
            }
            if (pickup == point && isGenericTaxiPickupLabel(origin)) origin = taxiPickupPinLabel(point, language)
        }
    }
    LaunchedEffect(state.estimate?.estimatedFareEtb, count, driver, street) {
        val estimate = state.estimate?.estimatedFareEtb
        if (driver && !street && estimate != null && estimate > 0 && fare.isBlank() && count > 0)
            fare = String.format(Locale.US, "%.2f", estimate / count)
    }
    picker?.let { type -> TaxiLocationDialog(type == "destination", if (type == "destination") dropoff else pickup,
        if (type == "destination") destination else origin, { picker = null }) { name, point ->
        if (type == "destination") { destination = name; dropoff = point } else { origin = name; pickup = point }
        model.clearEstimate(); picker = null
    } }
    if (datePickerOpen) EthiopianDatePickerDialog(
        initialIsoDate = date,
        minimumIsoDate = EthiopianDateTime.clockDateIso(),
        language = language,
        onDismiss = { datePickerOpen = false },
        onSelect = { date = it }
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(when { street -> "Start a street pickup"; acceptRequest != null -> "Offer this requested route"; driver -> "Offer a group ride"; else -> "Start a group ride" }, Modifier.weight(1f), color = CommuteInk, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold); IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close form") } }
        if (!driver) Text(if (passengerGroupRequest) "Choose your destination. Your current location is used as the pickup." else "Separate passengers can join this route and pay per seat.", color = Color(0xFF627385), fontSize = 13.sp)
        if (street) {
            Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(23.dp))) {
                val driverMarker = pickup?.let { MobilityLocation(it.first, it.second, stale = false) }
                TaxiMap(pickup, dropoff, driver = driverMarker, height = 300, pickupLabel = origin.ifBlank { taxiPickupResolvingLabel(language) }, destinationLabel = destination,
                    tileDownloadsEnabled = mapTiles, providedApi = mapApi, routeGeometry = state.estimate?.routeGeometry,
                    routeGeometryQuality = state.estimate?.routeGeometryQuality, showPickupMarker = false, modifier = Modifier.fillMaxSize())
                state.dashboard?.let { dashboard ->
                    Surface(Modifier.align(Alignment.TopStart).padding(10.dp), color = PassoMobilityPalette.card.copy(alpha = .97f),
                        shape = RoundedCornerShape(18.dp), shadowElevation = 3.dp) {
                        Row(Modifier.padding(start = 8.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (dashboard.online) "Online" else "Offline", color = PassoMobilityPalette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Switch(checked = dashboard.online, onCheckedChange = { onAvailabilityChange(it, vehicleId) },
                                enabled = !state.busy && vehicleId.isNotBlank(), modifier = Modifier.size(width = 46.dp, height = 32.dp))
                        }
                    }
                    Surface(Modifier.align(Alignment.TopEnd).padding(10.dp).clickable(onClick = onCreditClick), color = PassoMobilityPalette.card.copy(alpha = .97f),
                        shape = RoundedCornerShape(18.dp), shadowElevation = 3.dp) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccountBalanceWallet, null, tint = PassoMobilityPalette.brand, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp)); Text(taxiMoney(dashboard.wallet.availableEtb), color = PassoMobilityPalette.brand, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
            Surface(color = PassoMobilityPalette.card.copy(alpha = .97f), contentColor = PassoMobilityPalette.text,
                shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, PassoMobilityPalette.border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) {
                    TaxiRouteRow("Pickup location", origin.ifBlank { taxiPickupResolvingLabel(language) }, true)
                    HorizontalDivider(color = PassoMobilityPalette.border)
                    TaxiRouteRow("Destination", destination.ifBlank { "Choose destination" }, false, !state.busy) { picker = "destination" }
                }
            }
        } else {
            if (pickup != null && dropoff != null) TaxiMap(pickup, dropoff, height = 180, pickupLabel = origin, destinationLabel = destination, routeGeometry = if (street) state.estimate?.routeGeometry else null, routeGeometryQuality = if (street) state.estimate?.routeGeometryQuality else null)
            if (!passengerGroupRequest) TaxiRouteRow("Pickup", origin.ifBlank { taxiPickupResolvingLabel(language) }, true, !state.busy && !driver && !street && acceptRequest == null) { if (!driver && !street && acceptRequest == null) picker = "pickup" }
            TaxiRouteRow("Destination", destination.ifBlank { "Where to?" }, false, !state.busy && acceptRequest == null) { if (acceptRequest == null) picker = "destination" }
        }
        if (street) {
            OutlinedTextField(streetPassengerPhone, { streetPassengerPhone = it.filter { ch -> ch.isDigit() || ch == '+' }.take(16) }, label = { Text("Passenger phone") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        }
        if (driver) Box {
            Surface(onClick = { vehicleMenu = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), color = CommuteMint, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(when (vehicle?.tier) { "MINI_VAN" -> R.drawable.taxi_minivan; "COMFORT" -> R.drawable.taxi_comfort; else -> R.drawable.taxi_economy }), null, Modifier.width(100.dp).height(65.dp), contentScale = ContentScale.Fit)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { Text(vehicle?.model ?: "Choose approved vehicle", color = CommuteInk, fontWeight = FontWeight.Bold, maxLines = 2)
                        vehicle?.let { Text(it.plateNumber, color = CommuteTeal, fontSize = 12.sp); Text("${it.capacityLimit} approved passenger seats", color = Color(0xFF627385), fontSize = 12.sp) } }
                    Icon(Icons.Default.ArrowDropDown, "Choose vehicle", tint = CommuteTeal)
                }
            }
            DropdownMenu(vehicleMenu, { vehicleMenu = false }) { vehicles.forEach { item -> DropdownMenuItem(text = { Text("${item.model} · ${item.plateNumber} · ${item.capacityLimit} seats") }, onClick = { vehicleId = item.id; vehicleMenu = false; count = count.coerceAtMost(item.capacityLimit.coerceAtLeast(1)); model.clearEstimate() }) } }
        }
        if (!street) {
            if (!passengerGroupRequest && acceptRequest == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(onClick = { datePickerOpen = true }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(EthiopianDateTime.formatDate(date, language), maxLines = 1) }
                EthiopianTimeField(time, { time = it }, "Ethiopia time", "shared_time", !state.busy, language, Modifier.weight(1f))
            } else if (!passengerGroupRequest && acceptRequest != null) Text(taxiTime(acceptRequest.departureAt), color = CommuteTeal)
            if (!passengerGroupRequest) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(if (driver) "Seats to offer" else "Seats requested", fontWeight = FontWeight.Bold, color = CommuteInk); Text(if (driver) "Up to $capacity approved passenger seats" else "You can select actual seats after a driver accepts", color = Color(0xFF627385), fontSize = 12.sp) }
                IconButton(onClick = { count = (count - 1).coerceAtLeast(1) }, enabled = !state.busy && count > 1) { Icon(Icons.Default.Remove, "Fewer seats") }; Text(count.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold); IconButton(onClick = { count = (count + 1).coerceAtMost(capacity) }, enabled = !state.busy && count < capacity) { Icon(Icons.Default.Add, "More seats") }
            }
            if (driver) {
                OutlinedTextField(fare, { fare = it.take(12) }, label = { Text("Proposed fare per seat · ETB") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("shared_fare"))
                state.estimate?.let { estimate -> Text("Route estimate ${taxiMoney(estimate.estimatedFareEtb)} · Suggested per seat ${taxiMoney(estimate.estimatedFareEtb / count.coerceAtLeast(1))}", color = CommuteTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            } else {
                state.estimate?.let { estimate ->
                    Surface(color = CommuteMint, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Estimated route fare", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${"%.1f".format(estimate.distanceKm)} km · about ${estimate.durationMinutes.toInt()} min", color = Color(0xFF627385), fontSize = 12.sp)
                            }
                            Text(taxiMoney(estimate.estimatedFareEtb), color = TaxiBrandDeepEmerald, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        }
                    }
                    Text("Drivers set the final fare per seat. Confirm the offer before booking.", color = Color(0xFF627385), fontSize = 12.sp)
                } ?: run {
                    if (state.busy) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CommuteTeal)
                        Spacer(Modifier.width(8.dp)); Text("Calculating group fare…", color = CommuteTeal, fontWeight = FontWeight.SemiBold)
                    } else if (base != null) {
                        Text(if (state.message != null) "Fare estimate unavailable. Check your connection and retry." else "A fare estimate will appear for this route. Drivers set the final price per seat.", color = Color(0xFF627385), fontSize = 12.sp)
                        TaxiPrimary(if (state.message != null) "Retry fare estimate" else "Calculate fare estimate",
                            !state.busy, "shared_estimate") { base?.let { model.estimate(it.copy(farePerSeatEtb = null)) } }
                    }
                }
                Text("A driver will propose a per-seat fare. You must confirm that fare before booking.", color = Color(0xFF627385), fontSize = 13.sp)
            }
            TaxiPrimary(if (acceptRequest != null) "Offer route and fare" else if (driver) "Publish group ride" else "Request group ride",
                !state.busy && state.settings.sharedEnabled && base != null && departure != null && count in 1..capacity && (!driver || (vehicle != null && base.farePerSeatEtb != null)), "shared_submit") { base?.let(onSubmit) }
        } else {
            state.estimate?.let { estimate ->
                Text("Estimated fare", color = Color(0xFF627385)); Text(taxiMoney(estimate.estimatedFareEtb), color = CommuteTeal, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
                Text("Confirm this fare with the passenger. Passo will text this number and start the meter.", color = Color(0xFF627385), fontSize = 13.sp)
                Text("Passo fee: ${taxiMoney(state.settings.fixedCommissionEtb)}", color = CommuteTeal, fontSize = 13.sp)
                TaxiPrimary("Text passenger & start · ${taxiMoney(estimate.estimatedFareEtb)}",
                    !state.busy && streetContactReady && base != null && vehicle != null && state.settings.enabled,
                    "street_start_meter") { base?.let(onSubmit) }
            } ?: run {
                if (state.busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = CommuteTeal)
                    Spacer(Modifier.width(8.dp)); Text("Calculating estimated fare…", color = CommuteTeal, fontWeight = FontWeight.SemiBold)
                }
                TaxiPrimary(if (state.busy) "Calculating fare…" else "Get fare estimate",
                    !state.busy && base != null && vehicle != null && state.settings.enabled, "street_estimate") { base?.let { model.estimate(it.copy(farePerSeatEtb = null)) } }
            }
        }
    }
}
