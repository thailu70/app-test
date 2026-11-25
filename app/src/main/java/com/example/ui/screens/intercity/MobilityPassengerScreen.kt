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
import com.example.core.time.EthiopianDateTime
import com.example.data.api.*
import com.example.data.repository.AndroidEncryptedIntercityBookingStore
import com.example.ui.components.QrCodeCanvas
import com.example.ui.components.PassoMobilityPalette
import com.example.ui.components.EthiopianDatePickerDialog
import com.example.ui.screens.passenger.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal fun MobilityPassengerScreen(kind: String, language: AppLanguage, onBack: () -> Unit,
    modifier: Modifier = Modifier, providedModel: MobilityViewModel? = null, initialRequestId: String? = null,
    startCargoRequest: Boolean = false, accountName: String = "", accountPhone: String = "") {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = MobilityViewModel(
            ApiClient.createFeatureService(MobilityApi::class.java), AndroidEncryptedIntercityBookingStore(context.applicationContext, "mobility"),
            guestPush = com.example.core.notifications.AndroidMobilityGuestPushRegistrar(context.applicationContext)) as T
    } }
    val model = providedModel ?: viewModel<MobilityViewModel>(key = initialRequestId?.let { "mobility-notification-$it" } ?: "mobility-passenger", factory = factory)
    val state by model.state.collectAsState()
    LaunchedEffect(model, initialRequestId) { initialRequestId?.let(model::openSavedRequest) }
    LaunchedEffect(model, startCargoRequest, initialRequestId) {
        if (startCargoRequest && kind == "CARGO" && initialRequestId == null) model.request()
    }
    fun t(en: String, am: String, om: String) = intercityText(language, en, am, om)
    fun back() {
        if (initialRequestId != null || state.phase == MobilityPhase.BROWSE || (startCargoRequest && kind == "CARGO" && state.phase == MobilityPhase.REQUEST)) onBack()
        else model.home()
    }
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
                    Text(message, Modifier.weight(1f), color = PassoMobilityPalette.text, fontSize = 13.sp)
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
                        item { Text(t("Upcoming group trips", "የሚመጡ የቡድን ጉዞዎች", "Imala garee dhufu"), color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                        if (state.trips.isEmpty() && !state.busy) item { MobilityCard {
                            Text(t("No upcoming trips yet.", "ገና የሚመጣ ጉዞ የለም።", "Ammaaf imalli dhufu hin jiru."), color = PassoMobilityPalette.text)
                        } }
                        items(state.trips, key = { it.id }) { trip -> MobilityTripCard(trip, state.busy) { model.choose(trip) } }
                    }
                    item { MobilityButton(if (kind == "GROUP") t("Request group travel", "የቡድን ጉዞ ይጠይቁ", "Imala garee gaafadhu") else t("Create cargo request", "የጭነት ጥያቄ ይፍጠሩ", "Gaaffii fe'umsaa uumi"), !state.busy, "mobility_new_request", model::request) }
                    val requests = state.requests.filter { it.kind == kind }
                    if (requests.isNotEmpty()) item { Text(t("Your requests", "ጥያቄዎችዎ", "Gaaffilee kee"), color = CommuteInk, fontWeight = FontWeight.Bold) }
                    items(requests, key = { "request:${it.id}" }) { request -> MobilityRequestCard(request) { model.openRequest(request.id) } }
                }
                MobilityPhase.SEATS -> item { state.selected?.let { trip -> GroupSeatForm(trip, state, language,
                    accountName, accountPhone, model::toggleSeat, model::setAdult, model::book) } }
                MobilityPhase.BOOKING -> item { state.booking?.let { GroupBookingCard(it, state.busy, state.adultConfirmed, model::setAdult, model::checkout, model::checkPayment) } }
                MobilityPhase.REQUEST -> item {
                     if (kind == "CARGO") CargoRequestForm(state, language, model::setAdult, model::sendRequest,
                         initialDraft = CargoRequestDraft(name = accountName, phone = accountPhone), hideContactDetails = accountName.isNotBlank() && accountPhone.isNotBlank())
                    else MobilityRequestForm(kind, state, language, model::setAdult, model::sendRequest,
                        accountName = accountName, accountPhone = accountPhone)
                }
                MobilityPhase.REQUEST_STATUS -> item { state.request?.let { request ->
                    com.example.core.notifications.MobilityNotificationOptIn(driver = false, language = language)
                    MobilityRequestStatus(request, state.busy, language, { model.openRequest(request.id) }, model::retryCargo)
                    RequestMarketplacePanel(state, model::selectQuote, model::confirmRequest, model::reviewRequest)
                } }
                MobilityPhase.HISTORY -> {
                    item { Text(t("Your tickets & requests", "ቲኬቶች እና ጥያቄዎችዎ", "Tikkeettii fi gaaffilee kee"), color = CommuteInk, fontSize = 23.sp, fontWeight = FontWeight.Bold) }
                    if (state.bookings.isEmpty() && state.requests.isEmpty()) item { Text(t("Bookings and requests made on this device appear here.", "በዚህ ስልክ የተደረጉ ጥያቄዎች እዚህ ይታያሉ።", "Ajajnii fi gaaffileen bilbila kana irratti taasifaman as mul'atu."), color = CommuteInk) }
                    items(state.bookings, key = { "booking:${it.id}" }) { booking -> MobilityCard {
                        Text("${booking.trip.origin} → ${booking.trip.destination}", fontWeight = FontWeight.Bold, color = PassoMobilityPalette.text)
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
    Surface(color = PassoMobilityPalette.card, contentColor = PassoMobilityPalette.text, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, PassoMobilityPalette.border), modifier = modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp), content = content)
    }
}
@Composable internal fun MobilityField(value: String, onValue: (String) -> Unit, label: String, tag: String,
    number: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
            focusedTextColor = CommuteInk, unfocusedTextColor = CommuteInk, focusedLabelColor = CommuteTeal, unfocusedLabelColor = Color(0xFF596D7B)),
        modifier = modifier.fillMaxWidth().testTag(tag))
}
@Composable internal fun MobilityButton(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(tag)) { Text(label, fontWeight = FontWeight.SemiBold) }
}
@Composable internal fun MobilityAdult(checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChecked(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onChecked(it) }, enabled = enabled)
        Text("I am 18 or older. I have permission to provide the travelers’ contact details.", fontSize = 12.sp, color = PassoMobilityPalette.text, modifier = Modifier.weight(1f))
    }
}
@Composable internal fun MobilityDateField(value: String, onValue: (String) -> Unit, allowAny: Boolean = false, language: AppLanguage = AppLanguage.ENGLISH) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, PassoMobilityPalette.border), colors = ButtonDefaults.outlinedButtonColors(contentColor = PassoMobilityPalette.text)) {
        Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text(value.takeIf(String::isNotBlank)?.let { EthiopianDateTime.formatDate(it, language) } ?: if (allowAny) "Any upcoming date" else "Choose travel date")
    }
    if (open) EthiopianDatePickerDialog(value.ifBlank { null }, EthiopianDateTime.todayIso(), language,
        onDismiss = { if (allowAny && value.isBlank()) onValue(""); open = false }, onSelect = { onValue(it); open = false })
}
@Composable private fun MobilityTripCard(trip: MobilityTrip, busy: Boolean, onSelect: () -> Unit) {
    MobilityCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = CommuteMint, shape = CircleShape) { Icon(Icons.Default.Groups, null, tint = CommuteTeal, modifier = Modifier.padding(12.dp)) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("${trip.origin} → ${trip.destination}", color = PassoMobilityPalette.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(formatIntercityTime(trip.departureAt), fontSize = 12.sp, color = CommuteTeal)
            }
        }
        Text("${trip.vehicleType} · ${trip.availableSeats} seats available", fontSize = 12.sp, color = PassoMobilityPalette.muted)
        Text("ETB ${formatIntercityFare(trip.fareEtb)} / person", fontWeight = FontWeight.Bold, color = CommuteTeal, fontSize = 22.sp)
        MobilityButton("Choose seats", !busy && trip.availableSeats > 0, "group_trip_${trip.id}", onSelect)
    }
}
@Composable private fun GroupSeatForm(trip: MobilityTrip, state: MobilityUiState, language: AppLanguage,
    accountName: String, accountPhone: String, toggle: (Int) -> Unit, adult: (Boolean) -> Unit,
    onBook: (String, String, List<MobilityTraveler>) -> Unit) {
    val signedIn = accountName.isNotBlank() && accountPhone.isNotBlank()
    var buyerName by remember(trip.id, accountName) { mutableStateOf(accountName) }
    var buyerPhone by remember(trip.id, accountPhone) { mutableStateOf(accountPhone) }
    val names = remember(trip.id) { mutableStateMapOf<Int, String>() }; val phones = remember(trip.id) { mutableStateMapOf<Int, String>() }
    var useAccountForFirstTraveler by remember(trip.id, accountName, accountPhone) { mutableStateOf(signedIn) }
    LaunchedEffect(state.seats, accountName, accountPhone, useAccountForFirstTraveler) {
        val firstSeat = state.seats.firstOrNull()
        if (signedIn && useAccountForFirstTraveler && firstSeat != null &&
            names[firstSeat].isNullOrBlank() && phones[firstSeat].isNullOrBlank()) {
            names[firstSeat] = accountName
            phones[firstSeat] = accountPhone
        }
    }
    MobilityCard {
        Text(intercityText(language, "Choose your seats", "መቀመጫዎችን ይምረጡ", "Teessoo filadhu"), color = PassoMobilityPalette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
                    Box(contentAlignment = Alignment.Center) { Text(seat.toString(), color = if (selected) Color.White else PassoMobilityPalette.text, fontWeight = FontWeight.Bold) }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (signedIn) {
            Surface(color = CommuteMint, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Booking with your Passo account", color = CommuteTeal, fontWeight = FontWeight.Bold)
                    Text("$accountName · $accountPhone", color = PassoMobilityPalette.text, fontSize = 13.sp)
                }
            }
        } else {
            MobilityField(buyerName, { buyerName = it }, "Buyer’s full name", "group_buyer_name")
            MobilityField(buyerPhone, { buyerPhone = it }, "Buyer’s phone number", "group_buyer_phone", number = true)
        }
        state.seats.forEachIndexed { index, seat ->
            val accountTraveler = signedIn && index == 0 && useAccountForFirstTraveler
            Text(if (accountTraveler) "You · Seat $seat" else "Traveler · Seat $seat", fontWeight = FontWeight.Bold, color = PassoMobilityPalette.text)
            if (accountTraveler) {
                TextButton(onClick = { useAccountForFirstTraveler = false; names[seat] = ""; phones[seat] = "" }) {
                    Text("Use another traveler for this seat", color = CommuteTeal)
                }
            } else {
                MobilityField(names[seat].orEmpty(), { names[seat] = it }, "Traveler’s full name", "group_traveler_name_$seat")
                MobilityField(phones[seat].orEmpty(), { phones[seat] = it }, "Traveler’s phone number", "group_traveler_phone_$seat", number = true)
                if (signedIn && index == 0) TextButton(onClick = {
                    useAccountForFirstTraveler = true; names[seat] = accountName; phones[seat] = accountPhone
                }) { Text("Use my Passo details", color = CommuteTeal) }
                else if (!signedIn && index == 0) TextButton(onClick = { names[seat] = buyerName; phones[seat] = buyerPhone }) {
                    Text("Use buyer details for this traveler", color = CommuteTeal)
                }
            }
        }
        Text("Total · ETB ${formatIntercityFare(trip.fareEtb * state.seats.size)}", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = PassoMobilityPalette.text)
        MobilityAdult(state.adultConfirmed, !state.busy, adult)
        Text("Use each traveler's contact details with their permission. After confirmed payment, the buyer and each traveler receive ticket numbers and boarding-pass links by SMS.", fontSize = 12.sp, color = PassoMobilityPalette.muted)
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
        Text(if (paid) "Your group boarding passes" else "Complete your booking", color = PassoMobilityPalette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("${booking.trip.origin} → ${booking.trip.destination}", fontWeight = FontWeight.Bold)
        Text(formatIntercityTime(booking.trip.departureAt), color = CommuteTeal)
        Text("${booking.travelers.size} travelers · ETB ${formatIntercityFare(booking.amountEtb)}", color = PassoMobilityPalette.muted)
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

@Composable internal fun MobilityRequestForm(kind: String, state: MobilityUiState, language: AppLanguage,
    onAdult: (Boolean) -> Unit, send: (MobilityRequestInput, ByteArray?, String?) -> Unit,
    providedApi: MobilityApi? = null, pickerTileDownloadsEnabled: Boolean = true,
    accountName: String = "", accountPhone: String = "") {
    val signedIn = accountName.isNotBlank() && accountPhone.isNotBlank()
    var name by remember(accountName) { mutableStateOf(accountName) }; var phone by remember(accountPhone) { mutableStateOf(accountPhone) }
    var origin by remember { mutableStateOf("") }; var destination by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }; var travelTime by remember { mutableStateOf(EthiopianDateTime.clockValueFromEAT(8, 0)) }
    var date by remember { mutableStateOf(intercityToday()) }; var people by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }; var details by remember { mutableStateOf("") }
    var fromPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }; var toPoint by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var roadDistance by remember { mutableStateOf<String?>(null) }
    val locationApi = providedApi ?: remember { ApiClient.createFeatureService(MobilityApi::class.java) }
    var showPhoneVerification by remember { mutableStateOf(false) }
    var otpSent by remember { mutableStateOf(false) }
    var phoneCode by remember { mutableStateOf("") }
    var otpBusy by remember { mutableStateOf(false) }
    var otpMessage by remember { mutableStateOf<String?>(null) }
    val otpScope = rememberCoroutineScope()
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
    fun requestInput(verificationToken: String? = null): MobilityRequestInput {
        val (apiDate, apiTime) = EthiopianDateTime.toApiDateTime(date, travelTime)
            ?: error("Choose a valid Ethiopian date and time.")
        return MobilityRequestInput(kind, origin.trim(), destination.trim(), apiDate,
            people.toIntOrNull(), type, details.trim().takeIf { kind == "CARGO" },
            (if (signedIn) accountName else name).trim(), (if (signedIn) accountPhone else phone).trim(), state.adultConfirmed,
            fromPoint?.first, fromPoint?.second, toPoint?.first, toPoint?.second,
            travelTime = apiTime, otpVerificationToken = verificationToken,
            estimatedWeightQuintals = weight.toDoubleOrNull().takeIf { kind == "CARGO" })
    }
    if (picking != null && kind != "CARGO") {
        val pickup = picking == "origin"
        MobilityLocationPicker(
            intercityText(language, if (pickup) "Choose pickup location" else "Choose destination location",
                if (pickup) "መነሻ ቦታ ይምረጡ" else "መድረሻ ቦታ ይምረጡ", if (pickup) "Bakka ka'umsaa filadhu" else "Bakka ga'umsaa filadhu"),
            if (pickup) fromPoint else toPoint, { picking = null },
            { point -> if (pickup) fromPoint = point else toPoint = point; picking = null },
            onPlaceName = { label -> if (pickup) origin = label else destination = label }, language = language,
            initialLabel = if (pickup) origin else destination, providedApi = locationApi,
            tileDownloadsEnabled = pickerTileDownloadsEnabled)
    }
    if (showPhoneVerification) AlertDialog(
        onDismissRequest = { if (!otpBusy) showPhoneVerification = false },
        title = { Text("Confirm your phone", color = CommuteInk, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("We’ll send a one-time code to confirm this contact. No account or password needed.", color = Color(0xFF627385), fontSize = 14.sp)
                Text(phone, color = CommuteInk, fontWeight = FontWeight.Bold)
                if (otpSent) OutlinedTextField(phoneCode, { phoneCode = it.filter(Char::isDigit).take(8) },
                    label = { Text("Verification code") }, singleLine = true, enabled = !otpBusy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth().testTag("mobility_guest_otp"))
                otpMessage?.let { Text(it, color = if (it.startsWith("Code sent")) CommuteTeal else MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (otpBusy) return@Button
                otpBusy = true
                val purpose = if (kind == "CARGO") "CARGO_REQUEST" else "GROUP_REQUEST"
                otpScope.launch {
                    try {
                        if (!otpSent) {
                            val result = mobilityBody(locationApi.requestContactOtp(mapOf("phone" to phone.trim(), "purpose" to purpose)))
                            check(result.success && result.delivery == "SENT") { result.error ?: "The code could not be sent. Try again." }
                            otpSent = true; otpMessage = "Code sent by SMS."
                        } else {
                            val result = mobilityBody(locationApi.verifyContactOtp(mapOf("phone" to phone.trim(), "purpose" to purpose, "code" to phoneCode.trim())))
                            check(result.success) { result.error ?: "That code is incorrect or expired." }
                            val token = result.verificationToken?.takeIf { it.length >= 20 } ?: error("The code could not be confirmed. Try again.")
                            val input = requestInput(token)
                            showPhoneVerification = false
                            send(input, photo.bytes, photo.type)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { otpMessage = failure.message ?: "Phone verification failed. Try again." }
                    finally { otpBusy = false }
                }
            }, enabled = !otpBusy && (!otpSent || phoneCode.length >= 4), modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal.copy(alpha = .86f))) {
                Text(if (otpBusy) "Please wait…" else if (otpSent) "Verify & send request" else "Send verification code")
            }
        },
        dismissButton = { TextButton(onClick = { if (!otpBusy) showPhoneVerification = false }) { Text("Not now", color = CommuteTeal) } },
        containerColor = Color.White.copy(alpha = .93f), tonalElevation = 0.dp, shape = RoundedCornerShape(25.dp)
    )
    MobilityCard {
        Text(if (kind == "GROUP") intercityText(language, "Plan a group journey", "የቡድን ጉዞ ይጠይቁ", "Imala garee karoorsi") else intercityText(language, "Tell us about your cargo", "ስለ ጭነትዎ ይግለጹ", "Waa'ee fe'umsa kee ibsi"), color = PassoMobilityPalette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Nearby available drivers can send quotes. Compare their offers and choose your driver. Sending a request does not collect payment.", color = PassoMobilityPalette.muted, fontSize = 12.sp)
        if (signedIn) {
            Surface(color = CommuteMint, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Using your Passo account", color = CommuteTeal, fontWeight = FontWeight.Bold)
                    Text("$accountName · $accountPhone", color = PassoMobilityPalette.text, fontSize = 13.sp)
                }
            }
        } else {
            MobilityField(name, { name = it }, "Your full name", "request_name")
            MobilityField(phone, { phone = it }, "Phone number", "request_phone", number = true)
        }
        if (kind == "CARGO") {
            CargoAddressSearchField("Pickup location", origin, locationApi, !state.busy,
                onValue = { origin = it; fromPoint = null }, onSelect = { origin = it.name; fromPoint = it.latitude to it.longitude }, tag = "request_origin")
            CargoAddressSearchField("Delivery destination", destination, locationApi, !state.busy,
                onValue = { destination = it; toPoint = null }, onSelect = { destination = it.name; toPoint = it.latitude to it.longitude }, tag = "request_destination")
        } else {
            Box(Modifier.testTag("request_pickup_location")) { CargoLocationField("Pickup location", origin, false, "request_origin", !state.busy) { picking = "origin" } }
            Box(Modifier.testTag("request_delivery_location")) { CargoLocationField("Destination", destination, false, "request_destination", !state.busy) { picking = "destination" } }
        }
        MobilityDateField(date, { date = it }, language = language)
        roadDistance?.let { Text(it, color = CommuteTeal, fontSize = 12.sp) }
        EthiopianTimeField(travelTime, { travelTime = it }, intercityText(language, "Pickup time · Ethiopia time", "የመነሻ ሰዓት · የኢትዮጵያ ሰዓት", "Yeroo ka'umsaa · yeroo Itoophiyaa"), "request_time", language = language)
        if (kind == "GROUP") MobilityField(people, { people = it.filter(Char::isDigit).take(4) }, "Number of people", "request_people", number = true)
        MobilityVehicleChoice(type, state.types, { type = it }, !state.busy)
        if (kind == "CARGO") {
            MobilityField(weight, { weight = it.filter { char -> char.isDigit() || char == '.' }.take(12) }, "Estimated cargo weight · quintals", "cargo_weight", number = true)
            OutlinedTextField(details, { details = it.take(4000) }, label = { Text("Cargo details, weight, dimensions and handling needs") }, minLines = 3, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("cargo_details"))
            CargoPhotoChoice(photo, state.busy)
        }
        MobilityAdult(state.adultConfirmed, !state.busy, onAdult)
        val validCargo = kind != "CARGO" || (details.isNotBlank() && (weight.toDoubleOrNull() ?: 0.0) > 0 && photo.bytes != null && fromPoint != null && toPoint != null)
        MobilityButton("Send request", !state.busy && !photo.busy && state.adultConfirmed && EthiopianDateTime.toApiDateTime(date, travelTime) != null && (signedIn || (name.isNotBlank() && phone.isNotBlank())) && origin.isNotBlank() && destination.isNotBlank() && type.isNotBlank() && validCargo && (kind != "GROUP" || ((people.toIntOrNull() ?: 0) > 0 && fromPoint != null && toPoint != null && fromPoint != toPoint)), "mobility_send_request") {
            if (signedIn) send(requestInput(), photo.bytes, photo.type)
            else { showPhoneVerification = true; otpSent = false; phoneCode = ""; otpMessage = null }
        }
    }
}

@Composable private fun CargoAddressSearchField(label: String, value: String, api: MobilityApi, enabled: Boolean,
    onValue: (String) -> Unit, onSelect: (MobilityPlace) -> Unit, tag: String) {
    var suggestions by remember { mutableStateOf<List<MobilityPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var selectedLabel by remember { mutableStateOf("") }
    LaunchedEffect(value) {
        if (value.trim().length < 2 || value == selectedLabel) { suggestions = emptyList(); searching = false; return@LaunchedEffect }
        delay(350); searching = true
        try {
            val result = mobilityBody(api.locations(value.trim()))
            if (result.success && value.trim() != selectedLabel) suggestions = result.places.take(5)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { suggestions = emptyList() }
        finally { searching = false }
    }
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        OutlinedTextField(value, { selectedLabel = ""; onValue(it) }, label = { Text(label) }, singleLine = true,
            enabled = enabled, leadingIcon = { Icon(Icons.Default.Search, null, tint = CommuteTeal) },
            trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
            shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().testTag("${tag}_search"))
        if (suggestions.isNotEmpty()) Surface(color = Color.White.copy(alpha = .97f), shape = RoundedCornerShape(15.dp),
            border = BorderStroke(1.dp, Color(0xFFE1E8E5)), modifier = Modifier.fillMaxWidth()) {
            Column { suggestions.forEach { place ->
                TextButton(onClick = { selectedLabel = place.name; suggestions = emptyList(); onSelect(place) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.LocationOn, null, tint = CommuteTeal); Spacer(Modifier.width(7.dp)); Text(place.name, color = CommuteInk, modifier = Modifier.weight(1f))
                }
            } }
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
        Text("${request.origin} → ${request.destination}", color = PassoMobilityPalette.text, fontWeight = FontWeight.Bold)
        Text("${request.kind} · ${request.status.replace('_', ' ')}", color = CommuteTeal, fontSize = 12.sp)
        TextButton(onClick = onClick) { Text("View request", color = CommuteTeal) }
    }
}
@Composable private fun MobilityRequestStatus(request: MobilityRequest, busy: Boolean, language: AppLanguage, refresh: () -> Unit,
    retry: (ByteArray, String) -> Unit) {
    val photo = rememberCargoPhoto()
    MobilityCard {
        Icon(if (request.kind == "CARGO") Icons.Default.LocalShipping else Icons.Default.Groups, null, tint = CommuteTeal, modifier = Modifier.size(38.dp))
        Text("${request.origin} → ${request.destination}", color = PassoMobilityPalette.text, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(request.status.replace('_', ' '), color = CommuteTeal, fontWeight = FontWeight.Bold)
        Text("${formatMobilityDateTime(request.travelDate, "", language)} · ${request.vehicleType}", fontSize = 13.sp)
        request.people?.let { Text("$it people") }; request.cargoDetails?.let { Text(it) }
        (request.estimatedWeightQuintals ?: request.estimatedWeightKg?.div(100.0))?.let { Text("Estimated weight: ${formatIntercityFare(it)} quintals") }
        request.routeDistanceKm?.let { Text("Road distance: ${formatIntercityFare(it)} km") }
        request.driverName?.let { Text("Driver: $it") }; request.driverPhone?.let { Text("Driver phone: $it") }
        request.vehiclePlate?.let { Text("Vehicle: $it") }
        request.quoteEtb?.let { Text("Quoted amount: ETB ${formatIntercityFare(it)}", color = CommuteTeal, fontWeight = FontWeight.Bold) }
        if (request.reviewNote.isNotBlank()) Text(request.reviewNote, color = PassoMobilityPalette.muted)
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
