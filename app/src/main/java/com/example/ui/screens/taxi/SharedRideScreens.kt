package com.example.ui.screens.taxi

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.api.*
import com.example.R
import com.example.ui.screens.intercity.freshMobilityLocation
import com.example.ui.screens.intercity.mobilityDepartureIso
import com.example.ui.screens.passenger.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable internal fun SharedPassengerContent(model: TaxiViewModel, state: TaxiUiState, authenticated: Boolean, onSignIn: () -> Unit, onBack: () -> Unit = {}, initialCreate: Boolean = false) {
    var creating by remember { mutableStateOf(initialCreate) }
    val trip = state.selectedTrip
    Column(Modifier.fillMaxSize()) {
        TaxiHeader("Group Ride", onBack = { when { trip != null -> model.closeTrip(); creating -> creating = false; else -> onBack() } })
        when {
            trip != null -> SharedTripDetail(model, state, authenticated, onSignIn)
            creating -> TaxiTripForm(state, driver = false, street = false, model = model, onClose = { creating = false }) { input -> if (authenticated) model.sharedRequest(input) else onSignIn() }
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
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("shared_trip_${trip.id}"), color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xFFE0E7E3))) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Groups, null, tint = CommuteTeal); Text("${trip.origin} → ${trip.destination}", Modifier.weight(1f).padding(start = 9.dp), fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = 17.sp); Icon(Icons.Default.ChevronRight, null, tint = CommuteTeal) }
            Text(taxiTime(trip.departureAt), color = Color(0xFF627385), fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (trip.status == "REQUESTED") "${trip.seats} seats requested" else "${trip.availableSeats} seats available", color = CommuteTeal, fontSize = 13.sp)
                Text(trip.farePerSeatEtb?.let { "${taxiMoney(it)} / seat" } ?: "Awaiting a driver offer", fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = 13.sp)
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
            if (trip.status == "IN_PROGRESS") "IN_PROGRESS" else "ASSIGNED", height = 210, pickupLabel = trip.origin, destinationLabel = trip.destination, tileDownloadsEnabled = mapTiles, providedApi = mapApi)
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
                        if (booking.status == "CONFIRMED") booking.boardingPin?.let { Text("Boarding PIN: $it", fontSize = 24.sp, color = CommuteTeal, fontWeight = FontWeight.ExtraBold); Text("Share the PIN only after boarding.", fontSize = 12.sp) }
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
                if (selected.isNotEmpty()) Text("Total: ${taxiMoney((trip.farePerSeatEtb ?: 0.0) * selected.size)} for ${selected.size} seat(s)", color = CommuteInk, fontWeight = FontWeight.Bold)
                TaxiPrimary(if (authenticated) "Book ${selected.size} seat(s)" else "Sign in to book", !state.busy && selected.isNotEmpty(), "shared_book_seats") { if (authenticated) model.book(selected.sorted()) else { TaxiPendingNavigation.shared(trip.id); onSignIn() } }
            } else Text(trip.status.replace('_', ' '), color = CommuteTeal, fontWeight = FontWeight.Bold)
        }
        if (authenticated && trip.status in setOf("REQUESTED", "OPEN") && state.myTrips.any { it.id == trip.id && it.initiatorRole == "PASSENGER" })
            TextButton(onClick = { cancelRequest = true }, enabled = !state.busy) { Text("Cancel my group request") }
    }
}

/** Same location search/map controls for street pickup and both shared-trip initiation paths. */
@Composable internal fun TaxiTripForm(state: TaxiUiState, driver: Boolean, street: Boolean, model: TaxiViewModel,
    onClose: () -> Unit, acceptRequest: SharedRideTrip? = null, onSubmit: (TaxiRouteInput) -> Unit) {
    val context = LocalContext.current
    var origin by remember(acceptRequest?.id) { mutableStateOf(acceptRequest?.origin.orEmpty()) }
    var destination by remember(acceptRequest?.id) { mutableStateOf(acceptRequest?.destination.orEmpty()) }
    var pickup by remember(acceptRequest?.id) { mutableStateOf(acceptRequest?.let { it.originLatitude to it.originLongitude }) }
    var dropoff by remember(acceptRequest?.id) { mutableStateOf(acceptRequest?.let { it.destinationLatitude to it.destinationLongitude }) }
    var picker by remember { mutableStateOf<String?>(null) }
    var vehicleId by remember { mutableStateOf(state.dashboard?.vehicles?.firstOrNull()?.id.orEmpty()) }
    var tier by remember { mutableStateOf("ECONOMY") }
    var vehicleMenu by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(acceptRequest?.seats ?: 1) }
    var fare by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    val calendar = remember { Calendar.getInstance(TimeZone.getTimeZone("Africa/Addis_Ababa")).apply { add(Calendar.MINUTE, 30) } }
    var date by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = calendar.timeZone }.format(calendar.time)) }
    var time by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = calendar.timeZone }.format(calendar.time)) }
    val vehicles = state.dashboard?.vehicles.orEmpty()
    val vehicle = vehicles.find { it.id == vehicleId }
    val capacity = if (driver) vehicle?.capacityLimit?.coerceIn(0, 100) ?: 0 else 100
    val departure = acceptRequest?.departureAt ?: mobilityDepartureIso(date, time)
    val from = pickup; val to = dropoff
    val base = if (from != null && to != null && from != to && origin.isNotBlank() && destination.isNotBlank())
        TaxiRouteInput(origin, destination, from.first, from.second, to.first, to.second, if (driver) vehicle?.tier ?: tier else tier,
            vehicleId = vehicleId.takeIf { driver }, passengerConsent = if (street) consent else null,
            departureAt = if (!street) departure else null, seats = if (!street) count else null,
            farePerSeatEtb = if (driver && !street) fare.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } else null) else null
    picker?.let { type -> TaxiLocationDialog(type == "destination", if (type == "destination") dropoff else pickup,
        if (type == "destination") destination else origin, { picker = null }) { name, point ->
        if (type == "destination") { destination = name; dropoff = point } else { origin = name; pickup = point }
        model.clearEstimate(); consent = false; picker = null
    } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(when { street -> "Start a street pickup"; acceptRequest != null -> "Offer this requested route"; driver -> "Offer a group ride"; else -> "Start a group ride" }, Modifier.weight(1f), color = CommuteInk, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold); IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close form") } }
        Text(if (street) "Confirm the route and estimate before starting the meter. Start only while safely stopped." else "Separate passengers can join this route and pay per seat.", color = Color(0xFF627385), fontSize = 13.sp)
        if (pickup != null && dropoff != null) TaxiMap(pickup, dropoff, height = 180, pickupLabel = origin, destinationLabel = destination)
        TaxiRouteRow("Pickup", origin.ifBlank { "Choose pickup" }, true, !state.busy && acceptRequest == null) { if (acceptRequest == null) picker = "pickup" }
        TaxiRouteRow("Destination", destination.ifBlank { "Where to?" }, false, !state.busy && acceptRequest == null) { if (acceptRequest == null) picker = "destination" }
        if (driver) Box {
            Surface(onClick = { vehicleMenu = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), color = CommuteMint, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(when (vehicle?.tier) { "MINI_VAN" -> R.drawable.taxi_minivan; "COMFORT" -> R.drawable.taxi_comfort; else -> R.drawable.taxi_economy }), null, Modifier.width(100.dp).height(65.dp), contentScale = ContentScale.Fit)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) { Text(vehicle?.model ?: "Choose approved vehicle", color = CommuteInk, fontWeight = FontWeight.Bold, maxLines = 2)
                        vehicle?.let { Text(it.plateNumber, color = CommuteTeal, fontSize = 12.sp); Text("${it.capacityLimit} approved passenger seats", color = Color(0xFF627385), fontSize = 12.sp) } }
                    Icon(Icons.Default.ArrowDropDown, "Choose vehicle", tint = CommuteTeal)
                }
            }
            DropdownMenu(vehicleMenu, { vehicleMenu = false }) { vehicles.forEach { item -> DropdownMenuItem(text = { Text("${item.model} · ${item.plateNumber} · ${item.capacityLimit} seats") }, onClick = { vehicleId = item.id; vehicleMenu = false; count = count.coerceAtMost(item.capacityLimit.coerceAtLeast(1)); model.clearEstimate(); consent = false }) } }
        }
        if (!street) {
            if (acceptRequest == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(onClick = { DatePickerDialog(context, { _, y, m, d -> date = "%04d-%02d-%02d".format(Locale.US, y, m + 1, d) }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(date) }
                OutlinedButton(onClick = { val parts = time.split(':'); TimePickerDialog(context, { _, h, m -> time = "%02d:%02d".format(Locale.US, h, m) }, parts[0].toInt(), parts[1].toInt(), true).show() }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text(time + " · Ethiopia") }
            } else Text(taxiTime(acceptRequest.departureAt), color = CommuteTeal)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(if (driver) "Seats to offer" else "Seats requested", fontWeight = FontWeight.Bold, color = CommuteInk); Text(if (driver) "Up to $capacity approved passenger seats" else "You can select actual seats after a driver accepts", color = Color(0xFF627385), fontSize = 12.sp) }
                IconButton(onClick = { count = (count - 1).coerceAtLeast(1) }, enabled = !state.busy && count > 1) { Icon(Icons.Default.Remove, "Fewer seats") }; Text(count.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold); IconButton(onClick = { count = (count + 1).coerceAtMost(capacity) }, enabled = !state.busy && count < capacity) { Icon(Icons.Default.Add, "More seats") }
            }
            if (driver) OutlinedTextField(fare, { fare = it.take(12) }, label = { Text("Proposed fare per seat · ETB") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("shared_fare"))
            Text(if (driver) "Confirmed passengers keep their booked fare, even if other seats remain empty." else "A driver will propose a per-seat fare. You must confirm that fare before booking.", color = Color(0xFF627385), fontSize = 13.sp)
            if (driver) Text("Passo fee: ${taxiMoney(state.settings.sharedFixedCommissionEtb)} per vehicle journey. Reserved when the first passenger books; settled once after completion.", color = CommuteTeal, fontSize = 13.sp)
            TaxiPrimary(if (acceptRequest != null) "Offer route and fare" else if (driver) "Publish group ride" else "Request group ride",
                !state.busy && state.settings.sharedEnabled && base != null && departure != null && count in 1..capacity && (!driver || (vehicle != null && base.farePerSeatEtb != null)), "shared_submit") { base?.let(onSubmit) }
        } else {
            state.estimate?.let { estimate ->
                Text("Estimated fare", color = Color(0xFF627385)); Text(taxiMoney(estimate.estimatedFareEtb), color = CommuteTeal, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
                Text("Final fare follows the meter. Passenger pays you directly.", color = Color(0xFF627385), fontSize = 13.sp)
                Text("Passo fee reserved on start: ${taxiMoney(state.settings.fixedCommissionEtb)}", color = CommuteTeal, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }, enabled = !state.busy); Text("Passenger agrees to this estimate", color = CommuteInk, fontSize = 14.sp) }
                TaxiPrimary("Start meter", !state.busy && consent && base != null && vehicle != null && state.settings.enabled, "street_start_meter") { base?.let(onSubmit) }
            } ?: TaxiPrimary("Show estimated fare", !state.busy && base != null && vehicle != null && state.settings.enabled, "street_estimate") { base?.let(model::estimate) }
        }
    }
}
