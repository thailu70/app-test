package com.example.ui.screens.intercity

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.data.api.ApiClient
import com.example.data.api.IntercityPassengerApi
import com.example.data.repository.AndroidEncryptedIntercityBookingStore
import com.example.ui.screens.passenger.CommuteCream
import com.example.ui.screens.passenger.CommuteInk
import com.example.ui.screens.passenger.CommuteMint
import com.example.ui.screens.passenger.CommuteTeal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IntercityPassengerScreen(
    language: AppLanguage, onLanguage: (AppLanguage) -> Unit, onExit: () -> Unit,
    modifier: Modifier = Modifier, providedViewModel: IntercityPassengerViewModel? = null
) {
    val context = LocalContext.current
    val factory = remember(context.applicationContext) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = IntercityPassengerViewModel(
                ApiClient.createFeatureService(IntercityPassengerApi::class.java), AndroidEncryptedIntercityBookingStore(context.applicationContext)
            ) as T
        }
    }
    val model = providedViewModel ?: viewModel<IntercityPassengerViewModel>(key = "intercity-passenger", factory = factory)
    val state by model.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var datePickerOpen by remember { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val favoritesPreferences = remember(context.applicationContext) { context.applicationContext.getSharedPreferences("intercity_favorites", android.content.Context.MODE_PRIVATE) }
    var saved by remember { mutableStateOf(favoritesPreferences.getStringSet("departures", emptySet())?.toSet().orEmpty()) }
    fun back() { if (state.phase == IntercityPhase.BROWSE) onExit() else model.home() }
    BackHandler { back() }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refreshOnResume() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.checkoutUrl) {
        state.checkoutUrl?.let { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                model.checkoutOpened()
            } catch (_: android.content.ActivityNotFoundException) { model.checkoutLaunchFailed() }
            catch (_: SecurityException) { model.checkoutLaunchFailed() }
        }
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) model.savePdf { context.contentResolver.openOutputStream(uri) }
    }
    if (datePickerOpen) {
        val iso = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") } }
        val today = iso.parse(intercityToday())!!.time
        val picker = rememberDatePickerState(initialSelectedDateMillis = runCatching { iso.parse(state.date)?.time }.getOrNull(),
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today })
        DatePickerDialog(onDismissRequest = { datePickerOpen = false },
            confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { model.setDate(iso.format(Date(it))) }; datePickerOpen = false }) { Text(intercityText(language, "Choose date", "ቀን ይምረጡ", "Guyyaa filadhu")) } },
            dismissButton = { TextButton(onClick = { datePickerOpen = false }) { Text(intercityText(language, "Cancel", "ሰርዝ", "Dhiisi")) } }) { DatePicker(picker) }
    }
    Column(modifier.fillMaxSize().background(CommuteCream).windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (state.phase != IntercityPhase.BROWSE) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back() }, modifier = Modifier.testTag("intercity_flow_back")) { Icon(Icons.Default.ArrowBack, intercityText(language, "Back", "ተመለስ", "Deebi'i"), tint = CommuteInk) }
                Column(Modifier.weight(1f)) {
                    Text("PASSO", color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    Text(intercityText(language, "Intercity travel", "የከተማ መካከል ጉዞ", "Imala magaalotaa"), color = CommuteTeal, fontSize = 11.sp)
                }
                if (state.phase == IntercityPhase.BOOKINGS) IconButton(onClick = { model.showBookings() }, enabled = !state.busy) { Icon(Icons.Default.Refresh, "Refresh", tint = CommuteTeal) }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("intercity_content"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.phase == IntercityPhase.BROWSE) {
                item { IntercityHero(language, onLanguage, onExit) }
                item {
                    IntercitySearchPanel(state.origin, state.destination, intercityDateLabel(state.date),
                        state.directory.flatMap { listOf(it.origin, it.destination) }.distinct().sorted(), state.serviceTab, language,
                        model::setOrigin, model::setDestination, model::swapCities, { datePickerOpen = true }, model::search, model::setTab,
                        state.busy, Modifier.padding(horizontal = 12.dp).offset(y = (-20).dp))
                }
            }
            state.message?.let { message -> item {
                Surface(color = CommuteMint, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = CommuteInk, fontSize = 12.sp)
                        IconButton(onClick = model::clearMessage) { Icon(Icons.Default.Close, "Dismiss message", tint = CommuteTeal) }
                    }
                }
            } }
            when (state.phase) {
                IntercityPhase.BROWSE -> {
                    if (state.serviceTab == 0) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(intercityText(language, "Available routes", "ያሉ መስመሮች", "Karaalee jiran"), Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
                                if (state.departures.size > 3) TextButton(onClick = { showAll = !showAll }) { Text(intercityText(language, if (showAll) "Show fewer" else "See all", "ሁሉንም እይ", "Hunda ilaali"), color = CommuteInk) }
                                IconButton(onClick = model::refresh, enabled = !state.busy, modifier = Modifier.testTag("intercity_refresh")) { Icon(Icons.Default.Refresh, "Refresh routes", tint = CommuteTeal) }
                            }
                        }
                        if (state.departures.isEmpty() && !state.busy) item {
                            Surface(color = Color.White, shape = RoundedCornerShape(22.dp), modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Search, null, tint = CommuteTeal)
                                    Text(intercityText(language, "No available routes", "ያሉ መስመሮች የሉም", "Karaaleen hin jiran"), fontWeight = FontWeight.Bold, color = CommuteInk)
                                    Text(intercityText(language, "Try another date or city, or request a route.", "ሌላ ቀን ወይም ከተማ ይሞክሩ፣ ወይም መስመር ይጠይቁ።", "Guyyaa ykn magaalaa biraa filadhu, yookaan karaa gaafadhu."), fontSize = 12.sp, color = CommuteInk)
                                }
                            }
                        }
                        item {
                            if (showAll) Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.departures.forEachIndexed { index, departure ->
                                    IntercityDepartureCard(departure, index % 3, departure.id in saved, language,
                                        { saved = toggleIntercityFavorite(saved, departure.id); favoritesPreferences.edit().putStringSet("departures", saved).apply() }, { model.selectDeparture(departure) }, Modifier.fillMaxWidth())
                                }
                            } else LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.departures.size, key = { state.departures[it].id }) { index ->
                                    val departure = state.departures[index]
                                    IntercityDepartureCard(departure, index % 3, departure.id in saved, language,
                                        { saved = toggleIntercityFavorite(saved, departure.id); favoritesPreferences.edit().putStringSet("departures", saved).apply() }, { model.selectDeparture(departure) })
                                }
                            }
                        }
                        item {
                            OutlinedButton(onClick = model::requestRoute, enabled = !state.busy, shape = CircleShape, modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth().testTag("intercity_request_route")) {
                                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(intercityText(language, "Request a route", "መስመር ይጠይቁ", "Karaa gaafadhu"))
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(intercityText(language, "Your recent ticket", "የቅርብ ቲኬትዎ", "Tikkeettii kee dhihoo"), Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
                            TextButton(onClick = model::showBookings, enabled = !state.busy, modifier = Modifier.testTag("intercity_my_tickets")) { Text(intercityText(language, "See all", "ሁሉንም እይ", "Hunda ilaali"), color = CommuteInk) }
                        }
                    }
                    val recent = state.recent.firstOrNull { it.paymentStatus == "PAID" } ?: state.recent.firstOrNull()
                    item {
                        Box(Modifier.padding(horizontal = 14.dp)) {
                            if (recent != null) IntercityTicketCard(recent, language, { model.openBooking(recent.id) }, compact = true)
                            else Surface(color = CommuteMint, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Default.ConfirmationNumber, null, tint = CommuteTeal)
                                    Text(intercityText(language, "Your bookings on this phone will appear here.", "በዚህ ስልክ የተያዙ ቲኬቶች እዚህ ይታያሉ።", "Ajajni bilbila kana irratti taasifame asitti mul'ata."), fontSize = 12.sp, color = CommuteInk)
                                }
                            }
                        }
                    }
                    item { IntercityCommunityBanner(language, Modifier.padding(horizontal = 14.dp)) }
                }
                IntercityPhase.SEATS -> item {
                    state.selected?.let { departure -> Box(Modifier.padding(horizontal = 14.dp)) {
                        IntercitySeatSelection(departure, state.inventory, state.seat, state.busy, language, model::selectSeat, model::holdSeat, { model.selectDeparture(departure) })
                    } }
                }
                IntercityPhase.DETAILS -> item { state.booking?.let { Box(Modifier.padding(horizontal = 14.dp)) { IntercityCheckoutForm(it, state.busy, language, model::checkout, model::checkPayment) } } }
                IntercityPhase.TICKET -> item { state.booking?.let { booking -> Box(Modifier.padding(horizontal = 14.dp)) {
                    IntercityTicketCard(booking, language, {}, onSavePdf = { savePdf.launch("PASSO-${booking.ticketNumber ?: booking.id}.pdf") })
                } } }
                IntercityPhase.BOOKINGS -> {
                    if (state.recent.isEmpty() && !state.busy) item { Text(intercityText(language, "No bookings saved on this phone yet.", "በዚህ ስልክ ላይ የተቀመጡ ቲኬቶች የሉም።", "Bilbila kana irratti ajajni hin jiru."), Modifier.padding(20.dp), color = CommuteInk) }
                    items(state.recent.size, key = { state.recent[it].id }) { index ->
                        val booking = state.recent[index]
                        Box(Modifier.padding(horizontal = 14.dp)) { IntercityTicketCard(booking, language, { model.openBooking(booking.id) }, compact = true) }
                    }
                }
                IntercityPhase.REQUEST -> item {
                    IntercityRequestForm(state, language, model::setOrigin, model::setDestination, { datePickerOpen = true }, model::sendRequest)
                }
            }
        }
    }
}

private fun toggleIntercityFavorite(saved: Set<String>, id: String): Set<String> = if (id in saved) saved - id else saved + id

internal fun intercityDateLabel(iso: String): String = runCatching {
    val utc = TimeZone.getTimeZone("UTC")
    val value = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = utc; isLenient = false }.parse(iso)!!
    SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH).apply { timeZone = utc }.format(value)
}.getOrDefault(iso)

@Composable
private fun IntercityRequestForm(state: IntercityUiState, language: AppLanguage, onOrigin: (String) -> Unit,
    onDestination: (String) -> Unit, onDate: () -> Unit, onSend: (String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var company by rememberSaveable { mutableStateOf("") }
    var companyMenu by remember { mutableStateOf(false) }
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(intercityText(language, "Request your route", "መስመርዎን ይጠይቁ", "Karaa kee gaafadhu"), fontWeight = FontWeight.Bold, fontSize = 23.sp, color = CommuteInk)
            Text(intercityText(language, "A request does not reserve seats or collect payment.", "ጥያቄ መቀመጫ አይይዝም፣ ክፍያም አይጠይቅም።", "Gaaffiin teessoo hin qabatu, kaffaltii hin gaafatu."), fontSize = 12.sp, color = CommuteInk)
            OutlinedTextField(state.origin, onOrigin, label = { Text(intercityText(language, "From", "መነሻ", "Irraa")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(state.destination, onDestination, label = { Text(intercityText(language, "To", "መድረሻ", "Gara")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedButton(onClick = onDate, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text(intercityDateLabel(state.date)) }
            Box {
                OutlinedButton(onClick = { companyMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(state.companies.find { it.id == company }?.name ?: intercityText(language, "Choose a company", "ኩባንያ ይምረጡ", "Dhaabbata filadhu")); Icon(Icons.Default.ArrowDropDown, null) }
                DropdownMenu(companyMenu, { companyMenu = false }) { state.companies.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { company = item.id; companyMenu = false }) } }
            }
            if (state.companies.isEmpty() && !state.busy) Text(intercityText(language, "No companies available. Return and try again.", "ኩባንያዎች የሉም። ተመልሰው እንደገና ይሞክሩ።", "Dhaabbanni hin jiru. Deebi'ii yaali."), fontSize = 12.sp, color = CommuteInk)
            OutlinedTextField(name, { name = it }, label = { Text(intercityText(language, "Your name", "ስምዎ", "Maqaa kee")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(phone, { phone = it }, label = { Text(intercityText(language, "Phone number", "ስልክ ቁጥር", "Lakkoofsa bilbilaa")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { onSend(company, name, phone) }, enabled = !state.busy && company.isNotBlank() && name.isNotBlank() && phone.isNotBlank(), modifier = Modifier.fillMaxWidth(), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal)) {
                Text(intercityText(language, "Send route request", "የመስመር ጥያቄ ላክ", "Gaaffii karaa ergi"))
            }
        }
    }
}
