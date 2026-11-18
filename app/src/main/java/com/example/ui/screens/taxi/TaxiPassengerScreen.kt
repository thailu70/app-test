package com.example.ui.screens.taxi

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.api.*
import com.example.R
import com.example.core.localization.AppLanguage
import com.example.ui.screens.intercity.freshMobilityLocation
import com.example.ui.screens.passenger.*
import com.example.ui.viewmodel.AppRole
import com.example.ui.viewmodel.MainViewModel
import com.example.smoothPortalMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Public destination-first discovery; only requesting a ride or booking a seat requires sign-in. */
@Composable fun TaxiPassengerScreen(main: MainViewModel, onDailyCommute: () -> Unit,
    onSignIn: () -> Unit, onBack: (() -> Unit)?, onPrivacy: () -> Unit = {},
    onDriverSignIn: () -> Unit = {},
    onProfile: () -> Unit = {}, onSignOut: () -> Unit = {}, onOpenMobility: (String) -> Unit = {},
    language: AppLanguage = AppLanguage.ENGLISH, onLanguage: (AppLanguage) -> Unit = {}) {
    val authenticated by main.isAuthenticated.collectAsState()
    val model = taxiViewModel(authenticated, false)
    TaxiPassengerContent(main, authenticated, model, onDailyCommute, onSignIn, onBack, onPrivacy, onDriverSignIn, onProfile, onSignOut, onOpenMobility, language, onLanguage)
}

@Composable internal fun TaxiPassengerContent(main: MainViewModel, authenticated: Boolean, model: TaxiViewModel,
    onDailyCommute: () -> Unit, onSignIn: () -> Unit, onBack: (() -> Unit)?, onPrivacy: () -> Unit = {},
    onDriverSignIn: () -> Unit = {},
    onProfile: () -> Unit = {}, onSignOut: () -> Unit = {}, onOpenMobility: (String) -> Unit = {},
    language: AppLanguage = AppLanguage.ENGLISH, onLanguage: (AppLanguage) -> Unit = {}, mapTiles: Boolean = true) {
    val authBusy by main.authBusy.collectAsState()
    val authError by main.authError.collectAsState()
    val newPassengerPhone by main.newPassengerPhone.collectAsState()
    val state by model.state.collectAsState()
    val pending = remember(authenticated) { if (authenticated) TaxiPendingNavigation.take() else TaxiPendingNavigation.peek() }
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(if (pending?.sharedId != null || pending?.sharedStart == true) "shared" else "home") }
    var startShared by remember { mutableStateOf(authenticated && pending?.sharedStart == true) }
    var pickup by remember { mutableStateOf(pending?.route?.let { it.originLatitude to it.originLongitude }) }
    var destination by remember { mutableStateOf(pending?.route?.let { it.destinationLatitude to it.destinationLongitude }) }
    var pickupName by remember { mutableStateOf(pending?.route?.origin ?: "Pickup location") }
    var destinationName by remember { mutableStateOf(pending?.route?.destination.orEmpty()) }
    var tier by remember { mutableStateOf(pending?.route?.tier ?: "ECONOMY") }
    LaunchedEffect(pending, model) { pending?.sharedId?.let(model::chooseTrip) }
    var picker by remember { mutableStateOf<String?>(null) }
    var localNotice by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var showPhonePrompt by remember { mutableStateOf(false) }
    var showCodePrompt by remember { mutableStateOf(false) }
    var showNamePrompt by remember { mutableStateOf(false) }
    var showDestinationSearch by remember { mutableStateOf(false) }
    var showSignInChooser by remember { mutableStateOf(false) }
    var destinationQuery by remember { mutableStateOf("") }
    var continueRequestAfterSignIn by remember { mutableStateOf(pending?.requestAfterSignIn == true) }
    var requestSubmittedAfterSignIn by remember { mutableStateOf(false) }
    var passengerName by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var signInCode by remember { mutableStateOf("") }
    var signInMessage by remember { mutableStateOf<String?>(null) }
    var sendingSignInCode by remember { mutableStateOf(false) }
    val pushFocus by com.example.core.notifications.RideNotificationNavigation.focus.collectAsState()
    LaunchedEffect(pushFocus, model) {
        pushFocus?.takeIf { it.scope == "PASSENGER" }?.let {
            if (it.kind == "SHARED") { page = "shared"; model.openNotifiedTrip(it.requestId, true) }
            else { page = "home"; model.openNotifiedTrip(it.requestId, false) }
            com.example.core.notifications.RideNotificationNavigation.consumeFocus()
        }
    }
    val chooseSignIn: () -> Unit = { showSignInChooser = true }
    fun locatePickup() {
        if (locating) return
        if (!taxiLocationPermission(context)) { localNotice = "Turn on location to estimate your fare."; return }
        locating = true
        scope.launch { try { val fix = freshMobilityLocation(context); check(usableTaxiFix(fix)) { "Use a fresh, non-simulated GPS location." }; pickup = fix.latitude to fix.longitude }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { localNotice = e.message }
            finally { locating = false } }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locatePickup()
        else localNotice = "Turn on location to estimate your fare."
    }
    fun route(): TaxiRouteInput? {
        val from = pickup ?: return null; val to = destination ?: return null
        return TaxiRouteInput(pickupName, destinationName, from.first, from.second, to.first, to.second, tier)
    }
    LaunchedEffect(mapTiles) {
        if (mapTiles && pickup == null) {
            if (taxiLocationPermission(context)) locatePickup()
            else permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    LaunchedEffect(pickup, destination, state.settings.enabled, state.estimate, state.busy) {
        if (pickup != null && destination != null && state.settings.enabled && state.estimate == null && !state.busy) route()?.let(model::estimate)
    }
    LaunchedEffect(newPassengerPhone) {
        if (!newPassengerPhone.isNullOrBlank()) {
            showCodePrompt = false
            passengerName = ""
            showNamePrompt = true
        }
    }
    LaunchedEffect(authenticated) {
        if (authenticated) {
            showPhonePrompt = false; showCodePrompt = false; showNamePrompt = false; showSignInChooser = false
        }
    }
    LaunchedEffect(authenticated, pending?.route, state.estimate, state.busy, continueRequestAfterSignIn) {
        val savedRoute = pending?.route ?: return@LaunchedEffect
        if (state.estimate == null && !state.busy) {
            model.estimate(savedRoute)
            return@LaunchedEffect
        }
        if (authenticated && continueRequestAfterSignIn && !requestSubmittedAfterSignIn && state.estimate != null && !state.busy) {
            requestSubmittedAfterSignIn = true
            continueRequestAfterSignIn = false
            model.request(savedRoute)
        }
    }
    val nearbyPoint = state.ride?.takeIf { it.status == "REQUESTED" }?.let { it.originLatitude to it.originLongitude }
        ?: pickup.takeIf { state.ride == null }
    LaunchedEffect(nearbyPoint, tier, state.ride?.tier) { model.nearby(nearbyPoint, state.ride?.tier ?: tier) }
    picker?.let { type -> TaxiLocationDialog(type == "destination", if (type == "destination") destination else pickup,
        if (type == "destination") destinationName else pickupName, { picker = null }) { name, point ->
        if (type == "destination") { destination = point; destinationName = name; if (pickup == null) locatePickup() }
        else { pickup = point; pickupName = name }
        picker = null; model.clearEstimate()
    } }
    if (showDestinationSearch) TaxiDestinationSearchDialog(
        query = destinationQuery,
        onQueryChange = { destinationQuery = it },
        onDismiss = { showDestinationSearch = false },
        onChooseMap = { showDestinationSearch = false; picker = "destination" },
        onSelect = { place ->
            destination = place.latitude to place.longitude
            destinationName = place.name
            destinationQuery = place.name
            showDestinationSearch = false
            model.clearEstimate()
            if (pickup == null) localNotice = "Turn on location to calculate your fare."
        }
    )
    if (more) AlertDialog(onDismissRequest = { more = false }, title = { Text("Passo Mobility") }, text = {
        Column {
            TextButton(onClick = { more = false; page = "home"; model.closeTrip() }) { Text("Taxi home") }
            TextButton(onClick = { more = false; page = "trips" }) { Text("My trips") }
            TextButton(onClick = { more = false; page = "payments" }) { Text("Payments") }
            TextButton(onClick = { more = false; onDailyCommute() }) { Text("Daily Commute") }
            if (authenticated) TextButton(onClick = { more = false; onProfile() }) { Text("Profile & documents") }
            TextButton(onClick = { more = false; onPrivacy() }) { Text("Privacy & account") }
            if (authenticated) TextButton(onClick = { more = false; onSignOut() }) { Text("Sign out") }
            Text("Support: info@passo.live", fontSize = 14.sp) }
    }, confirmButton = { TextButton(onClick = { more = false }) { Text("Close") } })
    BackHandler(enabled = page != "home" || onBack != null) { if (state.selectedTrip != null) model.closeTrip() else if (page != "home") page = "home" else onBack?.invoke() }
    Column(Modifier.fillMaxSize().background(CommuteCream)) {
        TaxiHeader(
            onBack = if (!authenticated) onBack?.let { { TaxiPendingNavigation.clear(); it() } } else null,
            onMenu = if (authenticated) ({ more = true }) else null,
            onSignIn = if (!authenticated) ({ showSignInChooser = true }) else null,
            languageLabel = when (language) { AppLanguage.ENGLISH -> "EN"; AppLanguage.AMHARIC -> "አማ"; AppLanguage.OROMO -> "OR" },
            onLanguage = { onLanguage(when (language) { AppLanguage.ENGLISH -> AppLanguage.AMHARIC; AppLanguage.AMHARIC -> AppLanguage.OROMO; AppLanguage.OROMO -> AppLanguage.ENGLISH }) })
        TaxiNotice(localNotice ?: state.message) { localNotice = null; model.clearMessage() }
        if (state.busy || locating) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
        Box(Modifier.weight(1f).smoothPortalMotion("$page:${state.ride?.status}:${state.selectedTrip?.id}")) {
            when {
                page == "shared" -> { SharedPassengerContent(model, state, authenticated, chooseSignIn, { page = "home" }, startShared)
                    LaunchedEffect(Unit) { startShared = false }
                }
                page == "payments" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("Ride fares", color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp)
                    Text("Taxi and Group Ride fares are paid directly to your driver. Passo has not collected these amounts.", color = Color(0xFF627385), fontSize = 14.sp)
                    state.rides.filter { it.status == "COMPLETED" }.forEach { ride -> Text("${ride.origin} → ${ride.destination}\n${taxiMoney(ride.fareEtb ?: 0.0)}", color = CommuteInk, fontSize = 15.sp); HorizontalDivider() }
                    TextButton(onClick = onDailyCommute) { Text("Open Daily Commute for pass payments") }
                }
                page == "trips" -> TaxiPassengerHistory(model, state, authenticated, chooseSignIn, { page = "home" }) { page = "shared" }
                state.ride != null -> PassengerCurrentRide(state.ride!!, state.busy, model, nearbyCars = state.nearbyCars)
                else -> TaxiPassengerHome(
                    authenticated = authenticated, destinationName = destinationName, hasDestination = destination != null,
                    hasPickup = pickup != null, state = state, tier = tier,
                    onDestination = { showDestinationSearch = true },
                    onChooseMap = { picker = "destination" },
                    onUseLocation = {
                        if (!taxiLocationPermission(context)) permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        else locatePickup()
                    },
                    onTier = { tier = it; model.selectTier(it) },
                    onRequest = {
                        if (authenticated) route()?.let(model::request)
                        else route()?.let { TaxiPendingNavigation.route(it, requestAfterSignIn = true); requestSubmittedAfterSignIn = false; continueRequestAfterSignIn = true; showPhonePrompt = true }
                    },
                    onService = { service -> if (service == "COMMUTE") onDailyCommute() else if (service != "TAXI") onOpenMobility(service) },
                    onTravelTogether = { page = "shared" },
                    onEstimate = { route()?.let(model::estimate) },
                    onNotificationOptIn = if (authenticated) ({ com.example.core.notifications.MobilityNotificationOptIn(driver = false, taxi = true) }) else null,
                    mapTiles = mapTiles
                )
            }
        }
        if (authenticated) TaxiPassengerBottomNavigation(page,
            onHome = { page = "home"; model.closeTrip() },
            onBookings = { page = "trips"; model.closeTrip() },
            onWallet = { page = "payments"; model.closeTrip() },
            onProfile = onProfile)
    }
    if (showSignInChooser) AlertDialog(
        onDismissRequest = { showSignInChooser = false },
        title = { Text("Sign in as", color = CommuteInk, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose your Passo account type.", color = Color(0xFF627385), fontSize = 14.sp)
                OutlinedButton(onClick = { showSignInChooser = false; showPhonePrompt = true }, modifier = Modifier.fillMaxWidth().testTag("taxi_sign_in_passenger")) {
                    Icon(Icons.Default.DirectionsBus, null, tint = CommuteTeal); Spacer(Modifier.width(9.dp)); Text("Passenger", color = CommuteInk, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { showSignInChooser = false; onDriverSignIn() }, modifier = Modifier.fillMaxWidth().testTag("taxi_sign_in_driver")) {
                    Icon(Icons.Default.Person, null, tint = CommuteTeal); Spacer(Modifier.width(9.dp)); Text("Driver", color = CommuteInk, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = { TextButton(onClick = { showSignInChooser = false }) { Text("Cancel", color = CommuteTeal) } },
        containerColor = Color.White.copy(alpha = .95f), tonalElevation = 0.dp, shape = RoundedCornerShape(26.dp)
    )
    if (showPhonePrompt) AlertDialog(
        onDismissRequest = { if (!sendingSignInCode) { showPhonePrompt = false; continueRequestAfterSignIn = false; TaxiPendingNavigation.clear() } },
        title = { Text("Continue with phone", color = CommuteInk, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter your number. We’ll text you a one-time sign-in code.", color = Color(0xFF627385), fontSize = 14.sp)
                OutlinedTextField(value = phoneNumber, onValueChange = { phoneNumber = it.take(20) },
                    label = { Text("Mobile number (+251)") }, singleLine = true, enabled = !sendingSignInCode,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth().testTag("taxi_phone_prompt"))
                Text("Your ride details will be kept while you verify.", color = Color(0xFF627385), fontSize = 12.sp)
                signInMessage?.let { Text(it, color = CommuteTeal, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val phone = phoneNumber.trim()
                if (!Regex("^\\+?[0-9][0-9 ()-]{7,19}$").matches(phone)) localNotice = "Enter a valid mobile number."
                else if (!sendingSignInCode) {
                    sendingSignInCode = true
                    main.requestLoginOtp(phone) { message, error ->
                        sendingSignInCode = false
                        if (error != null) signInMessage = error
                        else {
                            signInMessage = message
                            signInCode = ""
                            showPhonePrompt = false
                            showCodePrompt = true
                        }
                    }
                }
            }, enabled = !sendingSignInCode, colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal.copy(alpha = .84f)),
                modifier = Modifier.fillMaxWidth()) { Text(if (sendingSignInCode) "Sending code…" else "Send sign-in code") }
        },
        dismissButton = { TextButton(onClick = { if (!sendingSignInCode) { showPhonePrompt = false; continueRequestAfterSignIn = false; TaxiPendingNavigation.clear() } }) { Text("Not now", color = CommuteTeal) } },
        containerColor = Color.White.copy(alpha = .93f), tonalElevation = 0.dp, shape = RoundedCornerShape(26.dp)
    )
    if (showCodePrompt) AlertDialog(
        onDismissRequest = { if (!authBusy) showCodePrompt = false },
        title = { Text("Enter your sign-in code", color = CommuteInk, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("We sent a six-digit code to $phoneNumber.", color = Color(0xFF627385), fontSize = 14.sp)
                Text("SMS verification code", color = CommuteInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                TaxiOtpCodeField(signInCode, enabled = !authBusy, modifier = Modifier.fillMaxWidth().testTag("taxi_otp_prompt")) { signInCode = it }
                (authError ?: signInMessage)?.let { Text(it, color = if (authError != null) Color(0xFFAC2F2F) else CommuteTeal, fontSize = 12.sp) }
                TextButton(onClick = {
                    if (!sendingSignInCode) {
                        sendingSignInCode = true
                        main.requestLoginOtp(phoneNumber) { sent, error ->
                            sendingSignInCode = false
                            signInMessage = error ?: sent
                            if (error == null) signInCode = ""
                        }
                    }
                }, enabled = !sendingSignInCode && !authBusy, modifier = Modifier.align(Alignment.End)) { Text(if (sendingSignInCode) "Sending…" else "Resend code", color = CommuteTeal) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (signInCode.matches(Regex("\\d{6}"))) {
                    signInMessage = null
                    main.loginWithOtp(phoneNumber, signInCode, AppRole.PASSENGER)
                } else signInMessage = "Enter the six-digit code."
            }, enabled = !authBusy, colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal.copy(alpha = .9f)), modifier = Modifier.fillMaxWidth().testTag("taxi_verify_otp")) {
                Text(if (authBusy) "Verifying…" else "Verify and continue")
            }
        },
        dismissButton = { TextButton(onClick = { if (!authBusy) { showCodePrompt = false; showPhonePrompt = true } }) { Text("Change number", color = CommuteTeal) } },
        containerColor = Color.White.copy(alpha = .95f), tonalElevation = 0.dp, shape = RoundedCornerShape(26.dp)
    )
    if (showNamePrompt) AlertDialog(
        onDismissRequest = { if (!authBusy) { showNamePrompt = false; showPhonePrompt = true } },
        title = { Text("Welcome to Passo", color = CommuteInk, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What should we call you?", color = Color(0xFF627385), fontSize = 14.sp)
                OutlinedTextField(value = passengerName, onValueChange = { passengerName = it.take(100) },
                    label = { Text("Your full name") }, singleLine = true, enabled = !authBusy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth().testTag("taxi_signup_name"))
                authError?.let { Text(it, color = Color(0xFFAC2F2F), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = { main.completePassengerSignup(passengerName) }, enabled = !authBusy && passengerName.trim().length >= 2,
                colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal), modifier = Modifier.fillMaxWidth().testTag("taxi_create_account")) {
                Text(if (authBusy) "Creating account…" else "Continue")
            }
        },
        dismissButton = { TextButton(onClick = { if (!authBusy) { showNamePrompt = false; showPhonePrompt = true } }) { Text("Back", color = CommuteTeal) } },
        containerColor = Color.White.copy(alpha = .95f), tonalElevation = 0.dp, shape = RoundedCornerShape(26.dp)
    )
}

@Composable
private fun TaxiOtpCodeField(value: String, enabled: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Box(modifier) {
        BasicTextField(value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
            enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(CommuteTeal), modifier = Modifier.size(1.dp).alpha(0.01f).focusRequester(focusRequester))
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { focusRequester.requestFocus() }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(6) { index ->
                val focused = index == value.length && value.length < 6
                Surface(color = Color.White, shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) CommuteTeal else Color(0xFFD9E1E4)),
                    modifier = Modifier.weight(1f).height(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(value.getOrNull(index)?.toString().orEmpty(), color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun TaxiPassengerHome(
    authenticated: Boolean,
    destinationName: String,
    hasDestination: Boolean,
    hasPickup: Boolean,
    state: TaxiUiState,
    tier: String,
    onDestination: () -> Unit,
    onChooseMap: () -> Unit,
    onUseLocation: () -> Unit,
    onTier: (String) -> Unit,
    onRequest: () -> Unit,
    onService: (String) -> Unit,
    onTravelTogether: () -> Unit,
    onEstimate: () -> Unit,
    onNotificationOptIn: (@Composable () -> Unit)?,
    mapTiles: Boolean
) {
    val configuredRates = state.settings.baseFareEtb > 0.0 || state.settings.perKmEtb > 0.0 || state.settings.minimumFareEtb > 0.0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Box(Modifier.fillMaxWidth().height(235.dp).background(CommuteMint, RoundedCornerShape(25.dp))) {
            Image(painterResource(R.drawable.passo_hero_background), "Passo passengers and bus in Addis Ababa",
                Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = Alignment.Center)
            Surface(Modifier.align(Alignment.BottomStart).padding(13.dp), color = CommuteInk.copy(alpha = .84f),
                shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Your ride, anytime, anywhere", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("Safe · Reliable · Affordable", color = Color.White.copy(alpha = .9f), fontSize = 12.sp)
                }
            }
        }

        Surface(color = Color.White.copy(alpha = .96f), shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Color(0xFFE5E5DF)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Where to?", color = CommuteInk, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Surface(onClick = onDestination, color = Color(0xFFF8FAF8), shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(1.dp, Color(0xFFDCE5E0)), modifier = Modifier.fillMaxWidth().testTag("taxi_where_to")) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = CommuteTeal, modifier = Modifier.size(23.dp))
                        Text(if (hasDestination) destinationName else "Search destination…", Modifier.weight(1f).padding(horizontal = 11.dp),
                            color = if (hasDestination) CommuteInk else Color(0xFF68747A), fontWeight = if (hasDestination) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 16.sp, maxLines = 2)
                        Icon(Icons.Default.ArrowForward, null, tint = CommuteInk)
                    }
                }
                OutlinedButton(onClick = onChooseMap, modifier = Modifier.align(Alignment.Start).testTag("taxi_choose_destination_map"),
                    shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, CommuteTeal.copy(alpha = .6f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) {
                    Icon(Icons.Default.LocationOn, null, tint = CommuteTeal, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp)); Text("Choose on map", color = CommuteTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose your ride", color = CommuteInk, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            TaxiTierTiles(state.tiers, tier, state.settings, state.estimate?.distanceKm,
                !state.busy && configuredRates && state.settings.enabled, onTier)
        }

        if (!hasPickup) {
            Surface(color = CommuteMint.copy(alpha = .7f), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MyLocation, null, tint = CommuteTeal, modifier = Modifier.size(19.dp))
                    Text("Enable location to calculate your fare", Modifier.weight(1f).padding(start = 8.dp), color = CommuteInk, fontSize = 12.sp)
                    TextButton(onClick = onUseLocation) { Text("Enable", color = CommuteTeal, fontWeight = FontWeight.Bold) }
                }
            }
        }

        if (hasDestination) {
            Surface(color = Color.White.copy(alpha = .96f), shape = RoundedCornerShape(21.dp),
                border = BorderStroke(1.dp, Color(0xFFE6E8E3)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    when {
                        !state.settings.enabled || !configuredRates -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(Modifier.size(8.dp), shape = androidx.compose.foundation.shape.CircleShape, color = Color(0xFFD85151)) {}
                                Text("Fare unavailable", Modifier.padding(start = 8.dp), color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                        }
                        state.estimate != null -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Estimated fare", color = CommuteInk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(taxiMoney(state.estimate.estimatedFareEtb), color = CommuteTeal, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                            }
                            Text("${"%.1f".format(state.estimate.distanceKm)} km · about ${state.estimate.durationMinutes.toInt()} min", color = Color(0xFF627385), fontSize = 12.sp)
                            TaxiPrimary(if (authenticated) "Request ${state.tiers.find { it.id == tier }?.name ?: tier.replace('_', ' ')}" else "Request ride",
                                enabled = !state.busy && state.estimate.estimatedFareEtb > 0.0, tag = "taxi_request", onClick = onRequest)
                        }
                        state.busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = CommuteTeal, strokeWidth = 2.dp)
                            Text("Calculating your fare…", color = CommuteInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        else -> {
                            Text("We couldn’t calculate a fare.", color = CommuteInk, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = onEstimate) { Text("Try again", color = CommuteTeal) }
                        }
                    }
                }
            }
        } else {
            Text("Choose a destination to see available cars and fares before booking.", color = Color(0xFF627385), fontSize = 12.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Our services", color = CommuteInk, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("Taxi", Icons.Default.LocalTaxi, "TAXI"),
                    Triple("Cargo", Icons.Default.Inventory2, "CARGO"),
                    Triple("Daily Commute", Icons.Default.DirectionsBus, "COMMUTE"),
                    Triple("Intercity", Icons.Default.Map, "INTERCITY")
                ).forEach { (label, icon, service) ->
                    Surface(onClick = { onService(service) }, color = Color.White.copy(alpha = .94f),
                        border = BorderStroke(1.dp, Color(0xFFE1E6E1)), shape = RoundedCornerShape(17.dp),
                        modifier = Modifier.weight(1f).height(76.dp).testTag("taxi_service_$service")) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(icon, null, tint = CommuteTeal, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(label, color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        Surface(onClick = onTravelTogether, color = CommuteTeal, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().testTag("taxi_travel_together")) {
            Row(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null, tint = Color.White, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("Travel together, save more", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    Text("Find a shared ride", color = Color.White.copy(alpha = .86f), fontSize = 11.sp)
                }
                Icon(Icons.Default.ArrowForward, null, tint = Color.White)
            }
        }
        onNotificationOptIn?.invoke()
        Spacer(Modifier.height(if (mapTiles) 8.dp else 14.dp))
    }
}

@Composable
private fun TaxiDestinationSearchDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onChooseMap: () -> Unit,
    onSelect: (MobilityPlace) -> Unit
) {
    val api = remember { ApiClient.createFeatureService(MobilityApi::class.java) }
    var places by remember { mutableStateOf<List<MobilityPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        val search = query.trim()
        if (search.length < 2) { places = emptyList(); message = null; searching = false; return@LaunchedEffect }
        searching = true; message = null
        try {
            kotlinx.coroutines.delay(350)
            val response = api.locations(search)
            val body = response.body()
            if (!response.isSuccessful || body?.success != true) error(body?.error ?: "Search is unavailable.")
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (query.trim() == search) {
                places = body.places
                if (body.places.isEmpty()) message = "No matching place. Try a nearby landmark."
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (query.trim() == search) { places = emptyList(); message = "Search is unavailable. Choose the place on the map." } }
        finally { if (query.trim() == search) searching = false }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = CommuteCream, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp).heightIn(min = 300.dp, max = 620.dp)) {
            Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Where to?", Modifier.weight(1f), color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close destination search", tint = CommuteInk) }
                }
                OutlinedTextField(value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth().testTag("taxi_destination_search"),
                    placeholder = { Text("Search a place or landmark") }, leadingIcon = { Icon(Icons.Default.Search, null, tint = CommuteTeal) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), shape = RoundedCornerShape(16.dp))
                Text("Suggestions", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    places.take(6).forEach { place ->
                        Surface(onClick = { onSelect(place) }, color = Color.White, shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFE3E7E2)), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOn, null, tint = CommuteTeal)
                                Text(place.name, Modifier.padding(start = 9.dp), color = CommuteInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                        }
                    }
                    message?.let { Text(it, color = Color(0xFF627385), fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                    if (query.trim().length < 2) Text("Start typing a place name to see suggestions.", color = Color(0xFF627385), fontSize = 12.sp)
                }
                OutlinedButton(onClick = onChooseMap, modifier = Modifier.fillMaxWidth().testTag("taxi_search_choose_map"),
                    shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, CommuteTeal), contentPadding = PaddingValues(12.dp)) {
                    Icon(Icons.Default.LocationOn, null, tint = CommuteTeal); Spacer(Modifier.width(7.dp))
                    Text("Choose on map", color = CommuteTeal, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TaxiPassengerBottomNavigation(page: String, onHome: () -> Unit, onBookings: () -> Unit, onWallet: () -> Unit, onProfile: () -> Unit) {
    NavigationBar(containerColor = Color.White.copy(alpha = .96f), tonalElevation = 4.dp) {
        NavigationBarItem(selected = page == "home", onClick = onHome, icon = { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
        NavigationBarItem(selected = page == "trips", onClick = onBookings, icon = { Icon(Icons.Default.ConfirmationNumber, null) }, label = { Text("Bookings") })
        NavigationBarItem(selected = page == "payments", onClick = onWallet, icon = { Icon(Icons.Default.AccountBalanceWallet, null) }, label = { Text("Wallet") })
        NavigationBarItem(selected = false, onClick = onProfile, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Profile") })
    }
}

@Composable internal fun PassengerCurrentRide(ride: TaxiRide, busy: Boolean, model: TaxiViewModel, mapTiles: Boolean = true, mapApi: MobilityApi? = null, nearbyCars: List<TaxiNearbyCar> = emptyList()) {
    val context = LocalContext.current
    val freeWaiting = taxiFreeWaitingSeconds(ride, mapTiles)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        TaxiMap(ride.originLatitude to ride.originLongitude, ride.destinationLatitude to ride.destinationLongitude, ride.location,
            if (ride.status == "IN_PROGRESS") "IN_PROGRESS" else "ASSIGNED", height = 265, pickupLabel = ride.origin, destinationLabel = ride.destination, tileDownloadsEnabled = mapTiles, providedApi = mapApi,
            nearbyCars = if (ride.status == "REQUESTED") nearbyCars else emptyList(), routeGeometry = ride.routeGeometry, routeGeometryQuality = ride.routeGeometryQuality, freezeVehicle = ride.distancePaused)
        Surface(shape = RoundedCornerShape(24.dp), color = Color.White) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ride.status == "REQUESTED") TaxiConnectionPulse(animated = mapTiles) else Text(when (ride.status) {
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
                Text("Your driver requests a passenger stop. Approve only if you requested it. Driven distance pauses; after the free allowance, ${ride.waitingRateEtbPerMinute?.let { "waiting costs ${taxiMoney(it)}/min" } ?: "configured waiting charges apply"}.", color = CommuteInk, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { model.approveWait(true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Approve stop") }
                    OutlinedButton(onClick = { model.approveWait(false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Decline") } }
            }
            if (ride.waiting == "STOP") Surface(color = CommuteMint, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("taxi_approved_stop")) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Passenger stop approved", color = CommuteTeal, fontWeight = FontWeight.Bold)
                    Text(taxiStopSummary(ride, freeWaiting), color = CommuteInk, fontSize = 13.sp)
                    if ((freeWaiting ?: 0) > 0) ride.waitingRateEtbPerMinute?.let { Text("After free waiting: ${taxiMoney(it)}/min. Normal traffic is not billed as a stop.", color = CommuteInk, fontSize = 12.sp) }
                }
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
