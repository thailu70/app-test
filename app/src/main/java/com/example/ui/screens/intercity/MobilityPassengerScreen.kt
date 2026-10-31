package com.example.ui.screens.intercity

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.core.localization.AppLanguage
import com.example.data.api.*
import com.example.data.repository.AndroidEncryptedIntercityBookingStore
import com.example.ui.components.QrCodeCanvas
import com.example.ui.screens.passenger.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal fun MobilityPassengerScreen(kind: String, language: AppLanguage, onBack: () -> Unit,
    modifier: Modifier = Modifier, providedModel: MobilityViewModel? = null, initialRequestId: String? = null) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = MobilityViewModel(
            ApiClient.createFeatureService(MobilityApi::class.java), AndroidEncryptedIntercityBookingStore(context.applicationContext, "mobility"),
            guestPush = com.example.core.notifications.AndroidMobilityGuestPushRegistrar(context.applicationContext)) as T
    } }
    val model = providedModel ?: viewModel<MobilityViewModel>(key = initialRequestId?.let { "mobility-notification-$it" } ?: "mobility-passenger", factory = factory)
    val state by model.state.collectAsState()
    LaunchedEffect(model, initialRequestId) { initialRequestId?.let(model::openSavedRequest) }
    var origin by remember { mutableStateOf("") }; var destination by remember { mutableStateOf("") }
    var searchDate by remember { mutableStateOf("") }
    fun t(en: String, am: String, om: String) = intercityText(language, en, am, om)
    fun back() { if (initialRequestId != null || state.phase == MobilityPhase.BROWSE) onBack() else model.home() }
    BackHandler { back() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestPhase by rememberUpdatedState(state.phase)
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && latestPhase == MobilityPhase.BOOKING) model.checkPayment()
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.phase, state.request?.id) {
        while (state.phase == MobilityPhase.REQUEST_STATUS) { delay(15000); if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.refreshRequest() }
    }
    LaunchedEffect(state.checkoutUrl) {
        state.checkoutUrl?.let { url ->
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
            catch (_: Exception) { /* Private booking remains available for checking or retrying payment. */ }
            model.checkoutOpened()
        }
    }
    Column(modifier.fillMaxSize().background(CommuteCream).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { back() }, modifier = Modifier.testTag("mobility_back")) { Icon(Icons.Default.ArrowBack, "Back", tint = CommuteInk) }
            Column(Modifier.weight(1f)) {
                Text("PASSO", color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                Text("Go Further, Together.", color = CommuteTeal, fontSize = 10.sp)
            }
            IconButton(onClick = model::history, enabled = !state.busy, modifier = Modifier.testTag("mobility_history")) { Icon(Icons.Default.ConfirmationNumber, "My bookings and requests", tint = CommuteTeal) }
            IconButton(onClick = model::refresh, enabled = !state.busy) { Icon(Icons.Default.Refresh, "Refresh", tint = CommuteTeal) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("mobility_content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.message?.let { message -> item {
                MobilityCard { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = CommuteInk, fontSize = 13.sp)
                    IconButton(onClick = model::clearMessage) { Icon(Icons.Default.Close, "Dismiss") }
                } }
            } }
            when (state.phase) {
                MobilityPhase.BROWSE -> {
                    item {
                        Surface(color = CommuteTeal, shape = RoundedCornerShape(25.dp)) {
                            Row(Modifier.fillMaxWidth().padding(21.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (kind == "GROUP") Icons.Default.Groups else Icons.Default.LocalShipping, null, tint = Color.White, modifier = Modifier.size(42.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (kind == "GROUP") t("Go together.", "አብረን እንጓዝ።", "Waliin haa imallu.") else t("Cargo, made simple.", "ቀላል የጭነት መጓጓዣ።", "Fe'umsa salphaa."), color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                                    Text(if (kind == "GROUP") t("Choose a trip or plan your own group journey.", "ጉዞ ይምረጡ ወይም የቡድን ጉዞ ይጠይቁ።", "Imala filadhu yookaan imala garee gaafadhu.") else t("Tell us what you need to move and where.", "ጭነቱን እና መንገዱን ይግለጹ።", "Fe'umsa kee fi karaa isaa nuuf ibsi."), color = Color.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (kind == "GROUP") {
                        item { MobilityCard {
                            MobilityField(origin, { origin = it }, t("Departure", "መነሻ", "Ka'umsa"), "mobility_origin")
                            MobilityField(destination, { destination = it }, t("Destination", "መድረሻ", "Ga'umsa"), "mobility_destination")
                            MobilityDateField(searchDate, { searchDate = it }, allowAny = true)
                            MobilityButton(t("Find group trips", "የቡድን ጉዞ ፈልግ", "Imala garee barbaadi"), !state.busy, "mobility_search") { model.search(origin, destination, searchDate.ifBlank { null }) }
                        } }
                        item { Text(t("Available group trips", "ያሉ የቡድን ጉዞዎች", "Imala garee jiran"), color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                        if (state.trips.isEmpty() && !state.busy) item { MobilityCard {
                            Text(t("No approved trips available yet.", "የተፈቀደ ጉዞ ገና የለም።", "Imalli hayyamame ammaaf hin jiru."), color = CommuteInk)
                            Text(t("Request a route and our team will review it.", "መስመር ይጠይቁ፤ ቡድናችን ይመለከተዋል።", "Karaa gaafadhu; gareen keenya ni ilaala."), fontSize = 12.sp)
                        } }
                        items(state.trips, key = { it.id }) { trip -> MobilityTripCard(trip, state.busy) { model.choose(trip) } }
                    }
                    item { MobilityButton(if (kind == "GROUP") t("Request a private group route", "የግል ቡድን መስመር ይጠይቁ", "Karaa garee dhuunfaa gaafadhu") else t("Create cargo request", "የጭነት ጥያቄ ይፍጠሩ", "Gaaffii fe'umsaa uumi"), !state.busy, "mobility_new_request", model::request) }
                    val requests = state.requests.filter { it.kind == kind }
                    if (requests.isNotEmpty()) item { Text(t("Your requests", "ጥያቄዎችዎ", "Gaaffilee kee"), color = CommuteInk, fontWeight = FontWeight.Bold) }
                    items(requests, key = { "request:${it.id}" }) { request -> MobilityRequestCard(request) { model.openRequest(request.id) } }
                }
                MobilityPhase.SEATS -> item { state.selected?.let { trip -> GroupSeatForm(trip, state, language, model::toggleSeat, model::setAdult, model::book) } }
                MobilityPhase.BOOKING -> item { state.booking?.let { GroupBookingCard(it, state.busy, state.adultConfirmed, model::setAdult, model::checkout, model::checkPayment) } }
                MobilityPhase.REQUEST -> item {
                    if (kind == "CARGO") CargoRequestForm(state, language, model::setAdult, model::sendRequest)
                    else MobilityRequestForm(kind, state, language, model::setAdult, model::sendRequest)
                }
                MobilityPhase.REQUEST_STATUS -> item { state.request?.let { request ->
                    com.example.core.notifications.MobilityNotificationOptIn(driver = false, language = language)
                    MobilityRequestStatus(request, state.busy, { model.openRequest(request.id) }, model::retryCargo)
                    RequestMarketplacePanel(state, model::selectQuote, model::confirmRequest, model::reviewRequest)
                } }
                MobilityPhase.HISTORY -> {
                    item { Text(t("Your tickets & requests", "ቲኬቶች እና ጥያቄዎችዎ", "Tikkeettii fi gaaffilee kee"), color = CommuteInk, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
                    if (state.bookings.isEmpty() && state.requests.isEmpty()) item { Text(t("Bookings and requests made on this device appear here.", "በዚህ ስልክ የተደረጉ ጥያቄዎች እዚህ ይታያሉ።", "Ajajnii fi gaaffileen bilbila kana irratti taasifaman as mul'atu."), color = CommuteInk) }
                    items(state.bookings, key = { "booking:${it.id}" }) { booking -> MobilityCard {
                        Text("${booking.trip.origin} → ${booking.trip.destination}", fontWeight = FontWeight.Bold, color = CommuteInk)
                        Text("${booking.travelers.size} travelers · ${booking.paymentStatus} · ETB ${formatIntercityFare(booking.amountEtb)}", fontSize = 12.sp)
                        TextButton(onClick = { model.openBooking(booking.id) }) { Text("Open booking", color = CommuteTeal) }
                    } }
                    items(state.requests, key = { "request:${it.id}" }) { request -> MobilityRequestCard(request) { model.openRequest(request.id) } }
                }
            }
        }
    }
}

@Composable internal fun MobilityCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Color(0xFFE7E3DB)), modifier = modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp), content = content)
    }
}
@Composable internal fun MobilityField(value: String, onValue: (String) -> Unit, label: String, tag: String,
    number: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        shape = RoundedCornerShape(14.dp), modifier = modifier.fillMaxWidth().testTag(tag))
}
@Composable internal fun MobilityButton(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(tag)) { Text(label, fontWeight = FontWeight.SemiBold) }
}
@Composable internal fun MobilityAdult(checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChecked(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onChecked(it) }, enabled = enabled)
        Text("I am 18 or older. I have permission to provide the travelers’ contact details.", fontSize = 12.sp, color = CommuteInk, modifier = Modifier.weight(1f))
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun MobilityDateField(value: String, onValue: (String) -> Unit, allowAny: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text(value.ifBlank { if (allowAny) "Any upcoming date" else "Choose travel date" })
    }
    if (open) {
        val formatter = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } }
        val today = formatter.parse(intercityToday())!!.time
        val picker = rememberDatePickerState(selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today })
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { onValue(formatter.format(java.util.Date(it))) }; open = false }) { Text("Choose") } },
            dismissButton = { TextButton(onClick = { if (allowAny) onValue(""); open = false }) { Text(if (allowAny) "Any date" else "Cancel") } }) { DatePicker(picker) }
    }
}
@Composable private fun MobilityTripCard(trip: MobilityTrip, busy: Boolean, onSelect: () -> Unit) {
    MobilityCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CommuteMint, shape = CircleShape) { Icon(Icons.Default.Groups, null, tint = CommuteTeal, modifier = Modifier.padding(12.dp)) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("${trip.origin} → ${trip.destination}", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(formatIntercityTime(trip.departureAt), fontSize = 12.sp, color = CommuteTeal)
            }
        }
        Text("${trip.vehicleType} · ${trip.availableSeats} seats available", fontSize = 12.sp, color = CommuteInk)
        Text("ETB ${formatIntercityFare(trip.fareEtb)} / person", fontWeight = FontWeight.Bold, color = CommuteTeal, fontSize = 22.sp)
        MobilityButton("Choose seats", !busy && trip.availableSeats > 0, "group_trip_${trip.id}", onSelect)
    }
}
@Composable private fun GroupSeatForm(trip: MobilityTrip, state: MobilityUiState, language: AppLanguage,
    toggle: (Int) -> Unit, adult: (Boolean) -> Unit, onBook: (String, String, List<MobilityTraveler>) -> Unit) {
    var buyerName by remember(trip.id) { mutableStateOf("") }; var buyerPhone by remember(trip.id) { mutableStateOf("") }
    val names = remember(trip.id) { mutableStateMapOf<Int, String>() }; val phones = remember(trip.id) { mutableStateMapOf<Int, String>() }
    MobilityCard {
        Text(intercityText(language, "Choose your seats", "መቀመጫዎችን ይምረጡ", "Teessoo filadhu"), color = CommuteInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("${trip.origin} → ${trip.destination}\n${formatIntercityTime(trip.departureAt)}", color = CommuteTeal)
        Text("Select up to 20 seats. Grey seats are occupied. Numbers identify seats and do not represent the vehicle’s physical seating layout.", fontSize = 12.sp)
        (1..trip.seats.coerceAtMost(100)).chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { index, seat ->
                    if (index == 2) Spacer(Modifier.width(10.dp))
                    val selected = seat in state.seats; val occupied = seat in state.occupied
                    Surface(onClick = { toggle(seat) }, enabled = !state.busy && !occupied,
                        color = if (selected) CommuteTeal else if (occupied) Color(0xFFE6E7E8) else CommuteMint,
                        shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).height(43.dp).testTag("group_seat_$seat")) {
                        Box(contentAlignment = Alignment.Center) { Text(seat.toString(), color = if (selected) Color.White else CommuteInk, fontWeight = FontWeight.Bold) }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        MobilityField(buyerName, { buyerName = it }, "Buyer’s full name", "group_buyer_name")
        MobilityField(buyerPhone, { buyerPhone = it }, "Buyer’s phone number", "group_buyer_phone", number = true)
        state.seats.forEach { seat ->
            Text("Traveler · Seat $seat", fontWeight = FontWeight.Bold, color = CommuteInk)
            MobilityField(names[seat].orEmpty(), { names[seat] = it }, "Traveler’s full name", "group_traveler_name_$seat")
            MobilityField(phones[seat].orEmpty(), { phones[seat] = it }, "Traveler’s phone number", "group_traveler_phone_$seat", number = true)
            if (state.seats.firstOrNull() == seat) TextButton(onClick = { names[seat] = buyerName; phones[seat] = buyerPhone }) { Text("Use buyer details for this traveler", color = CommuteTeal) }
        }
        Text("Total · ETB ${formatIntercityFare(trip.fareEtb * state.seats.size)}", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
        MobilityAdult(state.adultConfirmed, !state.busy, adult)
        Text("Use each traveler's contact details with their permission. After confirmed payment, the buyer and each traveler receive ticket numbers and boarding-pass links by SMS.", fontSize = 12.sp, color = CommuteInk)
        MobilityButton("Reserve & continue", !state.busy && state.adultConfirmed && state.seats.isNotEmpty() && buyerName.isNotBlank() && buyerPhone.isNotBlank() && state.seats.all { !names[it].isNullOrBlank() && !phones[it].isNullOrBlank() }, "group_reserve") {
            onBook(buyerName, buyerPhone, state.seats.map { MobilityTraveler(name = names[it].orEmpty().trim(), phone = phones[it].orEmpty().trim(), seatNumber = it) })
        }
    }
}
@Composable private fun GroupBookingCard(booking: MobilityBooking, busy: Boolean, adult: Boolean,
    onAdult: (Boolean) -> Unit, checkout: () -> Unit, refresh: () -> Unit) {
    val context = LocalContext.current
    val paid = booking.paymentStatus == "PAID" && booking.status == "CONFIRMED"
    MobilityCard {
        Text(if (paid) "Your group boarding passes" else "Complete your booking", color = CommuteInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("${booking.trip.origin} → ${booking.trip.destination}", fontWeight = FontWeight.Bold)
        Text(formatIntercityTime(booking.trip.departureAt), color = CommuteTeal)
        Text("${booking.travelers.size} travelers · ETB ${formatIntercityFare(booking.amountEtb)}", color = CommuteInk)
        Text("Payment: ${booking.paymentStatus} · ${booking.status}", color = CommuteTeal, fontSize = 12.sp)
        booking.travelers.forEach { traveler ->
            HorizontalDivider(color = CommuteMint)
            Text("${traveler.name} · Seat ${traveler.seatNumber}", fontWeight = FontWeight.SemiBold)
            if (canShowGroupTicket(booking, traveler)) {
                Text(traveler.ticketNumber.orEmpty(), color = CommuteTeal, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                traveler.qrToken?.takeIf { it.isNotBlank() }?.let { token ->
                    QrCodeCanvas(token, Modifier.align(Alignment.CenterHorizontally).size(210.dp).testTag("group_ticket_qr_${traveler.id}"), moduleColor = Color.Black)
                }
                traveler.boardingPassUrl?.let { link ->
                    if (trustedMobilityPassLink(link)) OutlinedButton(onClick = {
                        val resolved = ApiClient.portalUrl(link)
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resolved))) }
                    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(8.dp)); Text("Open boarding pass") }
                }
            }
        }
        if (!paid && booking.status !in setOf("EXPIRED", "CANCELLED")) {
            Text("Seats remain reserved until ${formatIntercityTime(booking.expiresAt)}. Boarding passes appear only after the provider confirms payment.", fontSize = 12.sp)
            MobilityAdult(adult, !busy, onAdult)
            MobilityButton("Pay with Telebirr", !busy && adult, "group_checkout", checkout)
        }
        OutlinedButton(onClick = refresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Check payment & tickets") }
    }
}
internal fun canShowGroupTicket(booking: MobilityBooking, traveler: MobilityTraveler): Boolean =
    booking.paymentStatus == "PAID" && booking.status == "CONFIRMED" && !traveler.ticketNumber.isNullOrBlank()
internal fun trustedMobilityPassLink(value: String): Boolean = runCatching {
    val resolved = java.net.URI(ApiClient.portalUrl(value))
    resolved.scheme == "https" && resolved.path.startsWith(java.net.URI(ApiClient.getBaseUrl()).path + "api/mobility/")
}.getOrDefault(false)

@Composable private fun MobilityRequestForm(kind: String, state: MobilityUiState, language: AppLanguage,
    onAdult: (Boolean) -> Unit, send: (MobilityRequestInput, ByteArray?, String?) -> Unit) {
    var name by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }
    var origin by remember { mutableStateOf("") }; var destination by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }; var travelTime by remember { mutableStateOf("08:00") }
    var date by remember { mutableStateOf(intercityToday()) }; var people by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }; var details by remember { mutableStateOf("") }
    var fromPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }; var toPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var roadDistance by remember { mutableStateOf<String?>(null) }
    val locationApi = remember { ApiClient.createFeatureService(MobilityApi::class.java) }
    LaunchedEffect(fromPoint, toPoint) {
        roadDistance = null
        val from = fromPoint; val to = toPoint
        if (from != null && to != null) {
            roadDistance = "Calculating road distance…"
            roadDistance = try { val result = mobilityBody(locationApi.road(from.first, from.second, to.first, to.second)); result.distanceKm?.let { "Road distance: ${formatIntercityFare(it)} km" } ?: "Road distance unavailable. Drivers can still quote." }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { "Road distance unavailable. Drivers can still quote." }
        }
    }
    val photo = rememberCargoPhoto()
    if (picking != null) MobilityLocationPicker(if (picking == "origin") "Departure location" else "Destination location", if (picking == "origin") fromPoint else toPoint,
        { picking = null }, { if (picking == "origin") fromPoint = it else toPoint = it; picking = null })
    MobilityCard {
        Text(if (kind == "GROUP") intercityText(language, "Plan a group journey", "የቡድን ጉዞ ይጠይቁ", "Imala garee karoorsi") else intercityText(language, "Tell us about your cargo", "ስለ ጭነትዎ ይግለጹ", "Waa'ee fe'umsa kee ibsi"), color = CommuteInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Nearby available drivers can send quotes. Compare their offers and choose your driver. Sending a request does not collect payment.", color = CommuteInk, fontSize = 12.sp)
        MobilityField(name, { name = it }, "Your full name", "request_name")
        MobilityField(phone, { phone = it }, "Phone number", "request_phone", number = true)
        MobilityField(origin, { origin = it }, "Departure / pickup address", "request_origin")
        MobilityField(destination, { destination = it }, "Destination / delivery address", "request_destination")
        run {
            OutlinedButton(onClick = { picking = "origin" }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.LocationOn, null); Text(if (fromPoint == null) "Choose departure location on map" else "Departure location selected ✓") }
            OutlinedButton(onClick = { picking = "destination" }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.LocationOn, null); Text(if (toPoint == null) "Choose destination location on map" else "Destination location selected ✓") }
        }
        MobilityDateField(date, { date = it })
        roadDistance?.let { Text(it, color = CommuteTeal, fontSize = 12.sp) }
        MobilityField(travelTime, { travelTime = it.take(5) }, "Pickup time · HH:mm", "request_time")
        if (kind == "GROUP") MobilityField(people, { people = it.filter(Char::isDigit).take(4) }, "Number of people", "request_people", number = true)
        MobilityVehicleChoice(type, state.types, { type = it }, !state.busy)
        if (kind == "CARGO") {
            MobilityField(weight, { weight = it.filter { char -> char.isDigit() || char == '.' }.take(12) }, "Estimated cargo weight · kg", "cargo_weight", number = true)
            OutlinedTextField(details, { details = it.take(4000) }, label = { Text("Cargo details, weight, dimensions and handling needs") }, minLines = 3, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("cargo_details"))
            CargoPhotoChoice(photo, state.busy)
        }
        MobilityAdult(state.adultConfirmed, !state.busy, onAdult)
        val validCargo = kind != "CARGO" || (details.isNotBlank() && (weight.toDoubleOrNull() ?: 0.0) > 0 && photo.bytes != null && fromPoint != null && toPoint != null)
        MobilityButton("Send request", !state.busy && !photo.busy && state.adultConfirmed && name.isNotBlank() && phone.isNotBlank() && origin.isNotBlank() && destination.isNotBlank() && type.isNotBlank() && validCargo && (kind != "GROUP" || (people.toIntOrNull() ?: 0) > 0), "mobility_send_request") {
            send(MobilityRequestInput(kind, origin.trim(), destination.trim(), date, people.toIntOrNull(), type, details.trim().takeIf { kind == "CARGO" }, name.trim(), phone.trim(), state.adultConfirmed,
                fromPoint?.first, fromPoint?.second, toPoint?.first, toPoint?.second, weight.toDoubleOrNull().takeIf { kind == "CARGO" }, travelTime), photo.bytes, photo.type)
        }
    }
}
@Composable internal fun MobilityVehicleChoice(value: String, types: List<MobilityVehicleType>, onChoose: (String) -> Unit, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }, enabled = enabled && types.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.LocalShipping, null); Spacer(Modifier.width(8.dp)); Text(types.find { it.id == value }?.name ?: "Choose vehicle type"); Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(menu, { menu = false }) { types.forEach { type -> DropdownMenuItem(text = { Text(type.name) }, onClick = { onChoose(type.id); menu = false }) } }
    }
}
@Composable private fun MobilityRequestCard(request: MobilityRequest, onClick: () -> Unit) {
    MobilityCard {
        Text("${request.origin} → ${request.destination}", color = CommuteInk, fontWeight = FontWeight.Bold)
        Text("${request.kind} · ${request.status.replace('_', ' ')}", color = CommuteTeal, fontSize = 12.sp)
        TextButton(onClick = onClick) { Text("View request", color = CommuteTeal) }
    }
}
@Composable private fun MobilityRequestStatus(request: MobilityRequest, busy: Boolean, refresh: () -> Unit,
    retry: (ByteArray, String) -> Unit) {
    val photo = rememberCargoPhoto()
    MobilityCard {
        Icon(if (request.kind == "CARGO") Icons.Default.LocalShipping else Icons.Default.Groups, null, tint = CommuteTeal, modifier = Modifier.size(38.dp))
        Text("${request.origin} → ${request.destination}", color = CommuteInk, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(request.status.replace('_', ' '), color = CommuteTeal, fontWeight = FontWeight.Bold)
        Text("${request.travelDate} · ${request.vehicleType}", fontSize = 13.sp)
        request.people?.let { Text("$it people") }; request.cargoDetails?.let { Text(it) }
        request.estimatedWeightKg?.let { Text("Estimated weight: $it kg") }
        request.routeDistanceKm?.let { Text("Road distance: ${formatIntercityFare(it)} km") }
        request.driverName?.let { Text("Driver: $it") }; request.driverPhone?.let { Text("Driver phone: $it") }
        request.vehiclePlate?.let { Text("Vehicle: $it") }
        request.quoteEtb?.let { Text("Quoted amount: ETB ${formatIntercityFare(it)}", color = CommuteTeal, fontWeight = FontWeight.Bold) }
        if (request.reviewNote.isNotBlank()) Text(request.reviewNote, color = CommuteInk)
        if (request.status == "DRAFT" && request.kind == "CARGO") {
            Text("Finish uploading your cargo photo to send this draft for review. Unsubmitted drafts expire after 7 days.", fontSize = 12.sp)
            CargoPhotoChoice(photo, busy)
            MobilityButton("Upload photo & submit", !busy && !photo.busy && photo.bytes != null, "cargo_retry") { photo.bytes?.let { retry(it, photo.type ?: "image/jpeg") } }
        }
        OutlinedButton(onClick = refresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Refresh request status") }
    }
}
internal class CargoPhotoState {
    var bytes by mutableStateOf<ByteArray?>(null); var type by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); var error by mutableStateOf<String?>(null); var choose: () -> Unit = {}
}
@Composable internal fun rememberCargoPhoto(): CargoPhotoState {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val state = remember { CargoPhotoState() }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            state.busy = true; state.error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val type = context.contentResolver.getType(uri)
                    require(type in setOf("image/jpeg", "image/png", "image/webp")) { "Choose a JPEG, PNG or WebP photo." }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val size = input.read(buffer); if (size < 0) break; require(output.size() + size <= 5 * 1024 * 1024) { "Choose a photo no larger than 5 MB." }; output.write(buffer, 0, size) }
                        output.toByteArray()
                    } ?: error("The photo could not be opened.")
                    require(bytes.isNotEmpty()) { "Choose a photo with image content." }; bytes to type
                }
                state.bytes = result.first; state.type = result.second
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { state.error = e.message ?: "Could not read this photo."; state.bytes = null; state.type = null }
            finally { state.busy = false }
        }
    }
    state.choose = { chooser.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }
    return state
}
@Composable internal fun CargoPhotoChoice(photo: CargoPhotoState, busy: Boolean) {
    OutlinedButton(onClick = photo.choose, enabled = !busy && !photo.busy, modifier = Modifier.fillMaxWidth().testTag("cargo_photo")) {
        Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text(if (photo.busy) "Opening photo…" else if (photo.bytes == null) "Choose cargo photo · up to 5 MB" else "Cargo photo selected ✓ · change")
    }
    photo.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
}
