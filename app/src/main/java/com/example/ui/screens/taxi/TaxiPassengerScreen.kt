package com.example.ui.screens.taxi

import android.Manifest
import android.location.Geocoder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.api.*
import com.example.R
import com.example.core.localization.AppLanguage
import com.example.ui.components.PassoMobilityPalette
import com.example.ui.components.QrCodeCanvas
import com.example.ui.screens.intercity.freshMobilityLocation
import com.example.ui.screens.passenger.*
import com.example.ui.viewmodel.AppRole
import com.example.ui.viewmodel.MainViewModel
import com.example.smoothPortalMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private suspend fun nearestTaxiPickupArea(context: android.content.Context, point: Pair<Double, Double>, language: AppLanguage): String? {
    if (!Geocoder.isPresent()) return null
    val locale = when (language) {
        AppLanguage.AMHARIC -> Locale("am", "ET")
        AppLanguage.OROMO -> Locale("om", "ET")
        AppLanguage.ENGLISH -> Locale("en", "ET")
    }
    return try {
        withTimeoutOrNull(3_500) {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                val address = Geocoder(context, locale).getFromLocation(point.first, point.second, 5)?.firstOrNull()
                    ?: return@withContext null
                val locality = address.locality?.trim()?.takeIf(String::isNotEmpty)
                val admin = address.adminArea?.trim()?.takeIf(String::isNotEmpty)
                val district = address.subAdminArea?.trim()?.takeIf { it.isNotEmpty() && !it.equals(locality, true) && !it.equals(admin, true) }
                    ?: address.subLocality?.trim()?.takeIf { it.isNotEmpty() && !it.equals(locality, true) && !it.equals(admin, true) }
                val landmark = address.featureName?.trim()?.takeIf {
                    it.isNotEmpty() && !it.matches(Regex("\\d+")) &&
                        !it.equals(locality, true) && !it.equals(admin, true) && !it.equals(district, true)
                }
                val area = landmark ?: district ?: locality ?: admin
                val region = listOfNotNull(locality, admin).firstOrNull { !it.equals(area, true) }
                when {
                    district != null && region != null -> "$district, $region"
                    area != null -> area
                    else -> null
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun isGenericTaxiPickupLabel(value: String): Boolean =
    value.isBlank() || value.contains("pickup location", ignoreCase = true) ||
        value.contains("current location", ignoreCase = true) || value.contains("selected map point", ignoreCase = true) ||
        value.startsWith("አሁን") || value.startsWith("Bakka amma", ignoreCase = true) || value.startsWith("Bakka kaartaa", ignoreCase = true)

/** Public destination-first discovery; only requesting a ride or booking a seat requires sign-in. */
@Composable fun TaxiPassengerScreen(main: MainViewModel, onDailyCommute: () -> Unit,
    onSignIn: () -> Unit, onBack: (() -> Unit)?, onPrivacy: () -> Unit = {},
    onDriverSignIn: () -> Unit = {},
    onProfile: () -> Unit = {}, onSignOut: () -> Unit = {}, onOpenMobility: (String) -> Unit = {},
    language: AppLanguage = AppLanguage.ENGLISH, onLanguage: (AppLanguage) -> Unit = {},
    onNotifications: () -> Unit = {}) {
    val authenticated by main.isAuthenticated.collectAsState()
    val model = taxiViewModel(authenticated, false)
    TaxiPassengerContent(main, authenticated, model, onDailyCommute, onSignIn, onBack, onPrivacy, onDriverSignIn, onProfile, onSignOut, onOpenMobility, language, onLanguage, onNotifications)
}

@Composable internal fun TaxiPassengerContent(main: MainViewModel, authenticated: Boolean, model: TaxiViewModel,
    onDailyCommute: () -> Unit, onSignIn: () -> Unit, onBack: (() -> Unit)?, onPrivacy: () -> Unit = {},
    onDriverSignIn: () -> Unit = {},
    onProfile: () -> Unit = {}, onSignOut: () -> Unit = {}, onOpenMobility: (String) -> Unit = {},
    language: AppLanguage = AppLanguage.ENGLISH, onLanguage: (AppLanguage) -> Unit = {},
    onNotifications: () -> Unit = {}, mapTiles: Boolean = true) {
    val authBusy by main.authBusy.collectAsState()
    val authError by main.authError.collectAsState()
    val newPassengerPhone by main.newPassengerPhone.collectAsState()
    val currentUser by main.currentUser.collectAsState()
    val state by model.state.collectAsState()
    val pending = remember(authenticated) { if (authenticated) TaxiPendingNavigation.take() else TaxiPendingNavigation.peek() }
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(if (pending?.sharedId != null || pending?.sharedStart == true) "shared" else "home") }
    var startShared by remember { mutableStateOf(authenticated && pending?.sharedStart == true) }
    var pickup by remember { mutableStateOf(pending?.route?.let { it.originLatitude to it.originLongitude }) }
    var destination by remember { mutableStateOf(pending?.route?.let { it.destinationLatitude to it.destinationLongitude }) }
    var pickupName by remember { mutableStateOf(pending?.route?.origin ?: taxiText(language, "Current location", "አሁን ያሉበት ቦታ", "Bakka amma jirtan")) }
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
    var phoneInput by remember { mutableStateOf("") }
    val recentDestinations = remember { mutableStateListOf<MobilityPlace>() }
    var signInCode by remember { mutableStateOf("") }
    var signInMessage by remember { mutableStateOf<String?>(null) }
    var sendingSignInCode by remember { mutableStateOf(false) }
    var resendSeconds by remember { mutableStateOf(45) }
    LaunchedEffect(showCodePrompt, resendSeconds) {
        if (showCodePrompt && resendSeconds > 0) { kotlinx.coroutines.delay(1_000); resendSeconds -= 1 }
    }
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
        scope.launch { try {
            val fix = freshMobilityLocation(context)
            check(usableTaxiFix(fix)) { "Use a fresh, non-simulated GPS location." }
            val point = fix.latitude to fix.longitude
            pickup = point
            pickupName = taxiText(language, "Current location", "አሁን ያሉበት ቦታ", "Bakka amma jirtan")
            nearestTaxiPickupArea(context, point, language)?.let { if (pickup == point) pickupName = it }
        }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { localNotice = e.message }
            finally { locating = false } }
    }
    fun resolveGenericPickupName(point: Pair<Double, Double>) {
        scope.launch {
            nearestTaxiPickupArea(context, point, language)?.let { if (pickup == point && isGenericTaxiPickupLabel(pickupName)) pickupName = it }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locatePickup()
        else localNotice = "Turn on location to estimate your fare."
    }
    fun route(): TaxiRouteInput? {
        val from = pickup ?: return null; val to = destination ?: return null
        return TaxiRouteInput(pickupName, destinationName, from.first, from.second, to.first, to.second, tier)
    }
    val clearDestination: () -> Unit = {
        destination = null
        destinationName = ""
        destinationQuery = ""
        model.clearEstimate()
    }
    LaunchedEffect(Unit) {
        pickup?.takeIf { isGenericTaxiPickupLabel(pickupName) }?.let(::resolveGenericPickupName)
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
    LaunchedEffect(pickup, destination) {
        if (pickup == null || destination == null) model.clearEstimate()
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
    // The guest city map still needs a real search origin before a passenger chooses pickup.
    // Use the map's Addis overview center; never draw fabricated driver coordinates.
    val nearbyPoint = state.ride?.takeIf { it.status == "REQUESTED" }?.let { it.originLatitude to it.originLongitude }
        ?: if (state.ride == null) pickup ?: ADDIS_MAP_CENTER else null
    val nearbyTier = if (destination == null && state.ride == null) "" else state.ride?.tier ?: tier
    LaunchedEffect(nearbyPoint, nearbyTier, page) {
        if (nearbyPoint != null && page == "home") {
            model.nearby(nearbyPoint, nearbyTier)
            while (true) {
                kotlinx.coroutines.delay(30_000)
                model.nearby(nearbyPoint, nearbyTier, force = true)
            }
        } else model.nearby(nearbyPoint, nearbyTier)
    }
    picker?.let { type -> TaxiLocationDialog(type == "destination", if (type == "destination") destination else pickup,
        if (type == "destination") destinationName else pickupName, { picker = null }) { name, point ->
        if (type == "destination") { destination = point; destinationName = name; if (pickup == null) locatePickup() }
        else {
            pickup = point
            pickupName = name.ifBlank { taxiText(language, "Current location", "አሁን ያሉበት ቦታ", "Bakka amma jirtan") }
            if (isGenericTaxiPickupLabel(name)) resolveGenericPickupName(point)
        }
        picker = null; model.clearEstimate()
    } }
    val selectDestination: (MobilityPlace) -> Unit = { place ->
        destination = place.latitude to place.longitude
        destinationName = place.name
        destinationQuery = place.name
        recentDestinations.removeAll { it.name == place.name }
        recentDestinations.add(0, place)
        while (recentDestinations.size > 5) recentDestinations.removeAt(recentDestinations.lastIndex)
        showDestinationSearch = false
        model.clearEstimate()
        if (pickup == null) localNotice = "Turn on location to calculate your fare."
    }
    if (showDestinationSearch) TaxiDestinationSearchDialog(
        query = destinationQuery,
        onQueryChange = { destinationQuery = it },
        onDismiss = { showDestinationSearch = false },
        onChooseMap = { showDestinationSearch = false; picker = "destination" },
        onSelect = selectDestination, recentDestinations = recentDestinations.toList(), onClearRecent = { recentDestinations.clear() }, language = language,
        languageLabel = when (language) { AppLanguage.ENGLISH -> "EN"; AppLanguage.AMHARIC -> "አማ"; AppLanguage.OROMO -> "OR" },
        onLanguage = { onLanguage(when (language) { AppLanguage.ENGLISH -> AppLanguage.AMHARIC; AppLanguage.AMHARIC -> AppLanguage.OROMO; AppLanguage.OROMO -> AppLanguage.ENGLISH }) }
    )
    BackHandler(enabled = page != "home" || destination != null || onBack != null) {
        if (state.selectedTrip != null) model.closeTrip()
        else if (page != "home") page = "home"
        else if (destination != null) clearDestination()
        else onBack?.invoke()
    }
    Column(Modifier.fillMaxSize().background(TaxiCanvas).statusBarsPadding().navigationBarsPadding()) {
        TaxiHeader(
            onBack = if (!authenticated) onBack?.let { { TaxiPendingNavigation.clear(); it() } } else null,
            onMenu = null,
            onSignIn = if (!authenticated) ({ showSignInChooser = true }) else null,
            languageLabel = when (language) { AppLanguage.ENGLISH -> "EN"; AppLanguage.AMHARIC -> "አማ"; AppLanguage.OROMO -> "OR" },
            onLanguage = { onLanguage(when (language) { AppLanguage.ENGLISH -> AppLanguage.AMHARIC; AppLanguage.AMHARIC -> AppLanguage.OROMO; AppLanguage.OROMO -> AppLanguage.ENGLISH }) },
            signInLabel = taxiText(language, "Sign in", "ግባ", "Seeni"),
            containerColor = Color.White,
            brandColor = TaxiBrandDeepEmerald,
            trailing = {
                if (authenticated) TaxiPassengerTopMenu(
                    expanded = more,
                    onExpandedChange = { more = it },
                    onHome = { page = "home"; model.closeTrip() },
                    onTrips = { page = "trips"; model.closeTrip() },
                    onPayments = { page = "payments"; model.closeTrip() },
                    onDailyCommute = onDailyCommute,
                    onAlertSound = { page = "alert_sound"; model.closeTrip() },
                    onNotifications = onNotifications,
                    onProfile = onProfile,
                    onPrivacy = onPrivacy,
                    onSharedTaxi = { page = "shared" },
                    onSignOut = onSignOut,
                    language = language
                ) else TaxiGuestTopMenu(
                    expanded = more,
                    onExpandedChange = { more = it },
                    onSignIn = { showSignInChooser = true },
                    onSharedTaxi = { page = "shared" },
                    onAlertSound = { page = "alert_sound" },
                    onPrivacy = onPrivacy,
                    language = language
                )
            })
        TaxiNotice(localNotice ?: state.message) { localNotice = null; model.clearMessage() }
        if (state.busy || locating) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
        Box(Modifier.weight(1f).smoothPortalMotion("$page:${state.ride?.status}:${state.selectedTrip?.id}")) {
            when {
                page == "shared" -> { SharedPassengerContent(model, state, authenticated, chooseSignIn, { page = "home" }, startShared,
                    passengerPickup = pickup, passengerPickupName = pickupName)
                    LaunchedEffect(Unit) { startShared = false }
                }
                page == "payments" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Text("Ride fares", color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp)
                    Text("Taxi and Group Ride fares are paid directly to your driver. Passo has not collected these amounts.", color = Color(0xFF627385), fontSize = 14.sp)
                    state.rides.filter { it.status == "COMPLETED" }.forEach { ride -> Text("${ride.origin} → ${ride.destination}\n${taxiMoney(ride.fareEtb ?: 0.0)}", color = CommuteInk, fontSize = 15.sp); HorizontalDivider() }
                    TextButton(onClick = onDailyCommute) { Text("Open Daily Commute for pass payments") }
                }
                page == "alert_sound" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(taxiText(language, "Settings", "ቅንብሮች", "Qindaa'ina"), color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 25.sp)
                    com.example.core.notifications.MobilityAlertToneSettings(driver = false, language = language)
                }
                page == "trips" -> TaxiPassengerHistory(model, state, authenticated, chooseSignIn, { page = "home" }) { page = "shared" }
                state.ride != null -> PassengerCurrentRide(state.ride!!, state.busy, model, nearbyCars = state.nearbyCars)
                else -> TaxiPassengerHome(
                    recentDestinations = recentDestinations.toList(), onRecentDestination = selectDestination,
                    destinationName = destinationName, hasDestination = destination != null,
                    hasPickup = pickup != null, state = state, tier = tier,
                    language = language,
                    pickup = pickup, destination = destination, pickupName = pickupName,
                    onDestination = { showDestinationSearch = true },
                    onClearDestination = clearDestination,
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
                    // This banner promotes shared city taxi rides, not the separate intercity group-travel portal.
                    onTravelTogether = { page = "shared" },
                    onEstimate = { route()?.let(model::estimate) },
                    onNotificationOptIn = if (authenticated) ({ com.example.core.notifications.MobilityNotificationOptIn(driver = false, taxi = true) }) else null,
                    mapTiles = mapTiles
                )
            }
        }
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
    if (showPhonePrompt) Dialog(
        onDismissRequest = { if (!sendingSignInCode) { showPhonePrompt = false; continueRequestAfterSignIn = false; TaxiPendingNavigation.clear() } },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Color.White, shape = RoundedCornerShape(30.dp), shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(.91f).testTag("taxi_phone_dialog")) {
            Column(Modifier.padding(horizontal = 21.dp, vertical = 19.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CommuteMint, shape = RoundedCornerShape(17.dp), modifier = Modifier.size(54.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.PhoneAndroid, null, tint = Color(0xFF087C42), modifier = Modifier.size(29.dp)) }
                    }
                    Text("Verify your phone number", Modifier.weight(1f).padding(start = 13.dp), color = CommuteInk, fontSize = 22.sp, lineHeight = 25.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = { if (!sendingSignInCode) { showPhonePrompt = false; continueRequestAfterSignIn = false; TaxiPendingNavigation.clear() } }) {
                        Icon(Icons.Default.Close, "Close", tint = Color(0xFF55656C))
                    }
                }
                Text("We’ll send a one-time verification code (OTP) to your phone to confirm your account.", color = Color(0xFF586870), fontSize = 14.sp, lineHeight = 20.sp)
                Surface(color = Color.White, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.7.dp, Color(0xFF079653)), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.height(57.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🇪🇹", fontSize = 21.sp)
                        Text("  +251", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        VerticalDivider(Modifier.padding(horizontal = 12.dp).height(36.dp), color = Color(0xFFD9E0E3))
                        BasicTextField(value = phoneInput, onValueChange = { phoneInput = it.filter { ch -> ch.isDigit() }.take(10) },
                            enabled = !sendingSignInCode, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            cursorBrush = SolidColor(CommuteTeal), modifier = Modifier.weight(1f).testTag("taxi_phone_prompt"), textStyle = androidx.compose.ui.text.TextStyle(color = CommuteInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                            decorationBox = { inner -> Box { if (phoneInput.isBlank()) Text("9XX XXX XXX", color = Color(0xFF89959C), fontSize = 17.sp); inner() } })
                    }
                }
                signInMessage?.let { Text(it, color = if (it.contains("fail", true) || it.contains("invalid", true)) Color(0xFFB13B42) else CommuteTeal, fontSize = 12.sp) }
                Button(onClick = {
                    val phone = canonicalAccountPhone(phoneInput)
                    if (phone == null) signInMessage = "Enter a valid Ethiopian mobile number."
                    else if (!sendingSignInCode) {
                        phoneNumber = phone
                        sendingSignInCode = true
                        main.requestLoginOtp(phone) { message, error ->
                            sendingSignInCode = false
                            if (error != null) signInMessage = error
                            else { signInMessage = message; signInCode = ""; resendSeconds = 45; showPhonePrompt = false; showCodePrompt = true }
                        }
                    }
                }, enabled = !sendingSignInCode, modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF079653))) {
                    Text(if (sendingSignInCode) "Sending code…" else "Send OTP", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp)); Icon(Icons.Default.ArrowForward, null)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerifiedUser, null, tint = Color(0xFF07864E), modifier = Modifier.size(22.dp))
                    VerticalDivider(Modifier.padding(horizontal = 10.dp).height(35.dp), color = Color(0xFFDDE3E4))
                    Text("We use your number only for account verification and ride updates.", color = Color(0xFF617078), fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
    }
    if (showCodePrompt) Dialog(onDismissRequest = { if (!authBusy) showCodePrompt = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color(0xFFF8FBFC), shape = RoundedCornerShape(0.dp), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (!authBusy) { showCodePrompt = false; showPhonePrompt = true } }) { Icon(Icons.Default.ArrowBack, "Back", tint = CommuteInk) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { if (!sendingSignInCode && resendSeconds == 0) {
                        sendingSignInCode = true
                        main.requestLoginOtp(phoneNumber) { sent, error -> sendingSignInCode = false; signInMessage = error ?: sent; if (error == null) { signInCode = ""; resendSeconds = 45 } }
                    } }, enabled = !sendingSignInCode && !authBusy && resendSeconds == 0) {
                        Text(if (sendingSignInCode) "Sending…" else if (resendSeconds > 0) "Resend in 00:%02d".format(resendSeconds) else "Resend code", color = CommuteTeal, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(15.dp))
                Surface(color = CommuteMint, shape = CircleShape, modifier = Modifier.size(190.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Smartphone, null, tint = Color(0xFF13356D), modifier = Modifier.size(102.dp))
                        Surface(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(72.dp), color = Color(0xFF079653), shape = CircleShape) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.VerifiedUser, null, tint = Color.White, modifier = Modifier.size(42.dp)) }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("Verify your number", color = CommuteInk, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(7.dp))
                Text("Enter the 6-digit code sent to", color = Color(0xFF596871), fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(phoneNumber, color = CommuteInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(25.dp))
                TaxiOtpCodeField(signInCode, enabled = !authBusy, modifier = Modifier.fillMaxWidth().testTag("taxi_otp_prompt")) { signInCode = it }
                (authError ?: signInMessage)?.let { Text(it, Modifier.padding(top = 9.dp), color = if (authError != null) Color(0xFFAC2F2F) else CommuteTeal, fontSize = 12.sp) }
                Spacer(Modifier.height(25.dp))
                Button(onClick = {
                    if (signInCode.matches(Regex("\\d{6}"))) { signInMessage = null; main.loginWithOtp(phoneNumber, signInCode, AppRole.PASSENGER) }
                    else signInMessage = "Enter the six-digit code."
                }, enabled = !authBusy, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF079653)),
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("taxi_verify_otp")) {
                    Text(if (authBusy) "Verifying…" else "Verify & Continue", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.width(9.dp)); Icon(Icons.Default.ArrowForward, null)
                }
                Spacer(Modifier.height(20.dp))
                Text("Didn’t receive the code?", color = CommuteInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { if (!sendingSignInCode && resendSeconds == 0) {
                    sendingSignInCode = true
                    main.requestLoginOtp(phoneNumber) { sent, error -> sendingSignInCode = false; signInMessage = error ?: sent; if (error == null) { signInCode = ""; resendSeconds = 45 } }
                } }, enabled = !sendingSignInCode && !authBusy && resendSeconds == 0) { Text("⟳  Resend Code", color = if (resendSeconds == 0) CommuteTeal else Color(0xFF96A1A6), fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(7.dp))
                Surface(color = CommuteMint.copy(alpha = .76f), shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = CommuteInk); Text("  Code expires in 5 minutes", color = CommuteInk, fontSize = 12.sp)
                    }
                }
            }
        }
    }
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
    language: AppLanguage,
    recentDestinations: List<MobilityPlace>,
    onRecentDestination: (MobilityPlace) -> Unit,
    destinationName: String,
    hasDestination: Boolean,
    hasPickup: Boolean,
    state: TaxiUiState,
    tier: String,
    pickup: Pair<Double, Double>?,
    destination: Pair<Double, Double>?,
    pickupName: String,
    onDestination: () -> Unit,
    onClearDestination: () -> Unit,
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
    val routePreview = hasDestination && hasPickup && pickup != null && destination != null
    val estimate = state.estimate.takeIf { routePreview }
    var nearbyLayerVisible by remember { mutableStateOf(true) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableHeight = maxHeight
        if (!hasDestination) {
            Box(Modifier.fillMaxSize().testTag("taxi_home_map_canvas")) {
                TaxiMap(pickup, null, height = availableHeight.value.toInt(), pickupLabel = pickupName,
                    tileDownloadsEnabled = mapTiles, nearbyCars = if (nearbyLayerVisible) state.nearbyCars else emptyList(), showPickupMarker = false,
                    attributionOnTop = true, attributionBottomPadding = availableHeight * .38f, modifier = Modifier.fillMaxSize())
                val nearbySummary = when (state.nearbyCarsStatus) {
                    NearbyCarsStatus.LOADING -> taxiText(language, "Finding nearby drivers…", "አቅራቢያ ያሉ አሽከርካሪዎችን በመፈለግ ላይ…", "Konkolaachistoota dhihoo barbaadaa…")
                    NearbyCarsStatus.AVAILABLE -> taxiText(language, "${state.nearbyCars.size} drivers nearby", "${state.nearbyCars.size} አሽከርካሪዎች አቅራቢያ", "Konkolaachistoota ${state.nearbyCars.size} dhihoo")
                    NearbyCarsStatus.EMPTY -> taxiText(language, "No drivers nearby", "አቅራቢያ አሽከርካሪ የለም", "Konkolaachisaan dhihoo hin jiru")
                    NearbyCarsStatus.UNAVAILABLE -> taxiText(language, "Live driver locations unavailable", "የቀጥታ የአሽከርካሪ ቦታ አልተገኘም", "Bakka konkolaachisaa yeroo ammaa hin jiru")
                    NearbyCarsStatus.IDLE -> taxiText(language, "City map", "የከተማ ካርታ", "Kaartaa magaalaa")
                }
                val nearbyColor = if (state.nearbyCarsStatus == NearbyCarsStatus.AVAILABLE) TaxiBrandEmerald else Color(0xFF65747D)
                Surface(Modifier.align(Alignment.TopStart).padding(12.dp), color = Color.White.copy(alpha = .91f),
                    shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = .9f)), shadowElevation = 0.dp) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationCity, null, tint = TaxiBrandEmerald, modifier = Modifier.size(19.dp))
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(taxiText(language, "Addis Ababa", "አዲስ አበባ", "Finfinnee"), color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(nearbyColor, CircleShape))
                                Text(nearbySummary, Modifier.padding(start = 5.dp), color = nearbyColor, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, maxLines = 1)
                            }
                        }
                    }
                }
                Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(color = Color.White.copy(alpha = .93f), shape = RoundedCornerShape(16.dp), shadowElevation = 0.dp,
                        border = BorderStroke(1.dp, Color.White), modifier = Modifier.size(43.dp)) {
                        IconButton(onClick = onUseLocation, modifier = Modifier.testTag("taxi_map_recenter")) {
                            Icon(Icons.Default.MyLocation, taxiText(language, "Center map on my location", "ካርታውን በቦታዬ ላይ አስቀምጥ", "Kaartaa bakka koo irratti qindeessi"), tint = TaxiBrandDeepEmerald, modifier = Modifier.size(21.dp))
                        }
                    }
                    Surface(color = Color.White.copy(alpha = .93f), shape = RoundedCornerShape(16.dp), shadowElevation = 0.dp,
                        border = BorderStroke(1.dp, Color.White), modifier = Modifier.size(43.dp)) {
                        IconButton(onClick = { nearbyLayerVisible = !nearbyLayerVisible }, modifier = Modifier.testTag("taxi_nearby_layer_toggle")) {
                            Icon(Icons.Default.DirectionsCar, taxiText(language, "Toggle nearby drivers on map", "በካርታው ላይ ያሉ አሽከርካሪዎችን ቀይር", "Konkolaachistoota kaartaa irratti jijjiiri"),
                                tint = if (nearbyLayerVisible) TaxiBrandDeepEmerald else Color(0xFF718087), modifier = Modifier.size(21.dp))
                        }
                    }
                }
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = availableHeight * .82f)
                    .testTag("taxi_bottom_sheet"), color = Color.White.copy(alpha = .97f),
                    shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                    border = BorderStroke(1.dp, PassoMobilityPalette.border), shadowElevation = 5.dp) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 13.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(38.dp).height(4.dp).clip(CircleShape).background(PassoMobilityPalette.border))
                        }
                        if (recentDestinations.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                recentDestinations.take(4).forEach { place ->
                                    Surface(onClick = { onRecentDestination(place) }, color = PassoMobilityPalette.cardRaised,
                                        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, PassoMobilityPalette.border), shadowElevation = 0.dp) {
                                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.LocationOn, null, tint = PassoMobilityPalette.brand, modifier = Modifier.size(15.dp))
                                            Text(place.name.substringBefore(","), Modifier.padding(start = 5.dp), color = PassoMobilityPalette.text,
                                                fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        TaxiDestinationCard(destinationName, false, onDestination, onChooseMap, language, compact = true, floating = true)
                        val groupRideEntry = remember { Animatable(0f) }
                        LaunchedEffect(Unit) {
                            groupRideEntry.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
                        }
                        val groupRideAdMotion = rememberInfiniteTransition(label = "groupTaxiAdMotion")
                        val saveRoll by groupRideAdMotion.animateFloat(
                            initialValue = 0f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(650, delayMillis = 3_200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                            label = "groupTaxiSaveRoll"
                        )
                        val groupShape = RoundedCornerShape(17.dp)
                        Surface(onClick = onTravelTogether, color = Color(0xFFE7F5EC), shape = groupShape,
                            border = BorderStroke(1.dp, Color(0xFFB7DCC5)),
                            modifier = Modifier.fillMaxWidth().height(64.dp).clip(groupShape).graphicsLayer {
                                alpha = groupRideEntry.value
                                translationY = (1f - groupRideEntry.value) * 10.dp.toPx()
                            }.testTag("taxi_travel_together"), shadowElevation = 0.dp) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).background(Color(0xFFB9F2D1), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Groups,
                                        taxiText(language, "Group taxi rides", "የጋራ ታክሲ ጉዞ", "Imala taaksii garee"),
                                        tint = Color(0xFF08744F), modifier = Modifier.size(25.dp))
                                }
                                Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.Center) {
                                    Text(taxiText(language, "Group Taxi Rides", "የጋራ ታክሲ ጉዞ", "Imala taaksii garee"), color = PassoMobilityPalette.text,
                                        fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(taxiText(language, "Save up to 50%", "እስከ 50% ይቆጥቡ", "Hanga 50% qusadhaa"), color = PassoMobilityPalette.brand,
                                        fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.graphicsLayer { translationY = (1f - saveRoll) * 2.dp.toPx(); alpha = .96f + saveRoll * .04f })
                                }
                                Icon(Icons.Default.ArrowForward, null, Modifier.size(19.dp), tint = PassoMobilityPalette.brand)
                            }
                        }
                        Surface(color = Color(0xFF006B4E), shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().height(68.dp).testTag("taxi_services_footer"), shadowElevation = 0.dp) {
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                listOf(
                                    Triple(taxiText(language, "Cargo", "ጭነት", "Fe'umsaa"), Icons.Default.Inventory2, "CARGO"),
                                    Triple(taxiText(language, "Daily\nCommute", "ዕለታዊ\nጉዞ", "Guyyaa\nGuyyaatti"), Icons.Default.DirectionsBus, "COMMUTE"),
                                    Triple(taxiText(language, "Intercity", "ከተማ መካከል", "Magaalota"), Icons.Default.Route, "INTERCITY")
                                ).forEachIndexed { index, (label, icon, service) ->
                                    if (index > 0) VerticalDivider(Modifier.width(1.dp).height(38.dp), color = Color.White.copy(alpha = .28f))
                                    Column(Modifier.weight(1f).fillMaxHeight().clickable { onService(service) }
                                        .testTag("taxi_service_$service").padding(horizontal = 4.dp, vertical = 7.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Icon(icon, contentDescription = label.replace('\n', ' '), tint = Color.White, modifier = Modifier.size(22.dp))
                                        Spacer(Modifier.height(4.dp))
                                        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                                            lineHeight = 11.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        onNotificationOptIn?.invoke()
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        } else {
            val cityMapHeight = 390.dp
            Column(Modifier.fillMaxWidth().heightIn(min = availableHeight).verticalScroll(rememberScrollState()).animateContentSize().background(TaxiCanvas).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (routePreview) {
                    Box(Modifier.fillMaxWidth().height(390.dp).clip(RoundedCornerShape(24.dp))) {
                        TaxiMap(pickup, destination, height = 390, pickupLabel = pickupName, destinationLabel = destinationName,
                            tileDownloadsEnabled = mapTiles, routeGeometry = estimate?.routeGeometry, routeGeometryQuality = estimate?.routeGeometryQuality,
                            nearbyCars = state.nearbyCars, modifier = Modifier.fillMaxSize())
                        Surface(Modifier.align(Alignment.TopStart).padding(11.dp), color = Color.White.copy(alpha = .94f), shape = CircleShape) {
                            IconButton(onClick = onClearDestination, modifier = Modifier.size(42.dp).testTag("taxi_clear_destination")) { Icon(Icons.Default.ArrowBack, "Back to city map", tint = CommuteInk) }
                        }
                    }
                    Surface(color = PassoMobilityPalette.card.copy(alpha = .97f), contentColor = PassoMobilityPalette.text, shape = RoundedCornerShape(21.dp), border = BorderStroke(1.dp, PassoMobilityPalette.border), modifier = Modifier.fillMaxWidth(), shadowElevation = 1.dp) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            TaxiCompactRouteRow("Pickup", pickupName, pickup = true, onClick = onUseLocation)
                            HorizontalDivider(color = PassoMobilityPalette.border)
                            TaxiCompactRouteRow("Destination", destinationName, pickup = false, onClick = onDestination)
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(cityMapHeight).clip(RoundedCornerShape(23.dp)).testTag("taxi_city_map")) {
                        TaxiMap(pickup, destination, height = cityMapHeight.value.toInt(), pickupLabel = pickupName,
                            destinationLabel = destinationName, tileDownloadsEnabled = mapTiles, nearbyCars = state.nearbyCars,
                            showPickupMarker = false, modifier = Modifier.fillMaxSize())
                    }
                    TaxiDestinationCard(destinationName, true, onDestination, onChooseMap, language, compact = true)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(taxiText(language, "Choose your ride", "ጉዞዎን ይምረጡ", "Imala filadhaa"), Modifier.weight(1f), color = CommuteInk, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        TextButton(onClick = onEstimate, contentPadding = PaddingValues(horizontal = 3.dp)) {
                            Text(taxiText(language, "Fare estimate", "የታሪፍ ግምት", "Tilmaama gatii"), color = CommuteInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Default.Info, null, Modifier.padding(start = 4.dp).size(18.dp), tint = CommuteTeal)
                        }
                    }
                    TaxiTierTiles(state.tiers, tier, state.settings, !state.busy && configuredRates && state.settings.enabled, onTier, language, estimate)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Route, null, tint = CommuteTeal, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(estimate?.let { "${"%.1f".format(it.distanceKm)} km · about ${it.durationMinutes.toInt()} min" }
                        ?: taxiText(language, "Calculating route…", "መንገዱ በማስላት ላይ…", "Karaa shallagamaa jira…"), color = Color(0xFF627385), fontSize = 12.sp, maxLines = 1)
                }
                TaxiRequestButton(enabled = estimate != null && estimate.estimatedFareEtb > 0.0 && !state.busy, language = language, onClick = onRequest)
                onNotificationOptIn?.invoke()
                Spacer(Modifier.height(9.dp))
            }
        }
    }
}

private val ADDIS_MAP_CENTER = 9.0108 to 38.7613

@Composable
private fun TaxiRequestButton(enabled: Boolean, language: AppLanguage, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Button(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp)
            .background(if (enabled) Brush.horizontalGradient(listOf(TaxiBrandEmerald, TaxiBrandDeepEmerald)) else SolidColor(Color(0xFFB7C4BF)), shape)
            .testTag("taxi_request"),
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White,
            disabledContainerColor = Color.Transparent, disabledContentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
        Text(taxiText(language, "Request Ride", "ጉዞ ይጠይቁ", "Imala gaafadhaa"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp)); Icon(Icons.Default.ArrowForward, null, Modifier.size(21.dp))
    }
}

@Composable
private fun TaxiCompactRouteRow(label: String, value: String, pickup: Boolean, onClick: (() -> Unit)? = null, dark: Boolean = false) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (pickup) Icons.Default.TripOrigin else Icons.Default.LocationOn, null,
            tint = if (pickup) Color(0xFF07864E) else Color(0xFFD2A500), modifier = Modifier.size(19.dp))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(label, color = if (dark) PassoMobilityPalette.muted else Color(0xFF627385), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
            Text(value, color = if (dark) PassoMobilityPalette.text else CommuteInk, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Icon(Icons.Default.EditLocationAlt, "Edit $label", tint = CommuteTeal, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun TaxiDestinationCard(name: String, selected: Boolean, onDestination: () -> Unit, onChooseMap: () -> Unit, language: AppLanguage,
    compact: Boolean, modifier: Modifier = Modifier, floating: Boolean = false) {
    Surface(color = Color.White.copy(alpha = if (floating) .97f else 1f), shape = RoundedCornerShape(23.dp), shadowElevation = if (floating) 2.dp else 0.dp,
        border = BorderStroke(1.dp, PassoMobilityPalette.border), modifier = modifier.fillMaxWidth().testTag("taxi_where_to")) {
        Row(Modifier.clickable(onClick = onDestination).padding(horizontal = 16.dp, vertical = if (compact) 12.dp else 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color(0xFFE5F7ED), shape = RoundedCornerShape(13.dp), modifier = Modifier.size(39.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.LocationOn, null, tint = TaxiBrandEmerald, modifier = Modifier.size(22.dp)) }
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(taxiText(language, "Where to?", "ወዴት?", "Eessa?"), color = PassoMobilityPalette.text, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text(if (selected) name else taxiText(language, "Search destination…", "መድረሻ ይፈልጉ…", "Bakka gahumsaa barbaadaa…"), color = PassoMobilityPalette.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            }
            VerticalDivider(Modifier.height(40.dp), color = PassoMobilityPalette.border)
            IconButton(onClick = onDestination, modifier = Modifier.size(42.dp).testTag("taxi_destination_search_button")) {
                Icon(Icons.Default.ArrowForward, taxiText(language, "Search destination", "መድረሻ ይፈልጉ", "Bakka gahumsaa barbaadaa"), tint = TaxiBrandDeepEmerald, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun TaxiDestinationSearchDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onChooseMap: () -> Unit,
    onSelect: (MobilityPlace) -> Unit,
    recentDestinations: List<MobilityPlace>,
    language: AppLanguage,
    languageLabel: String,
    onLanguage: () -> Unit,
    onClearRecent: () -> Unit
) {
    val api = remember { ApiClient.createFeatureService(MobilityApi::class.java) }
    var places by remember { mutableStateOf<List<MobilityPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(180); runCatching { focusRequester.requestFocus(); keyboard?.show() } }
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color(0xFFF7FAFC), shape = RoundedCornerShape(0.dp), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().background(Color.White).padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("taxi_destination_back")) { Icon(Icons.Default.ArrowBack, "Back", tint = CommuteTeal) }
                    Image(painterResource(R.drawable.auth_passo_wordmark), "PASSO", Modifier.width(140.dp).height(42.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onLanguage, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(Icons.Default.Language, null, Modifier.size(17.dp), tint = CommuteTeal)
                        Text(" $languageLabel⌄", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(taxiText(language, "Where to?", "ወዴት?", "Eessa?"), color = Color(0xFF075940), fontWeight = FontWeight.ExtraBold, fontSize = 25.sp)
                    Text(taxiText(language, "Enter your destination", "መድረሻዎን ያስገቡ", "Bakka gahumsaa galchaa"), color = Color(0xFF66757A), fontSize = 14.sp)
                    Surface(color = Color.White, shape = RoundedCornerShape(30.dp), shadowElevation = 0.dp,
                        border = BorderStroke(1.dp, Color(0xFFE2E7E9)), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocationOn, null, tint = CommuteTeal, modifier = Modifier.size(23.dp))
                            OutlinedTextField(value = query, onValueChange = onQueryChange, modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("taxi_destination_search"),
                                placeholder = { Text(taxiText(language, "Search destination…", "መድረሻ ይፈልጉ…", "Bakka gahumsaa barbaadaa…")) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent),
                                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Close, "Clear", tint = Color(0xFF9AA7AC)) } })
                            IconButton(onClick = { if (query.length >= 2) places.firstOrNull()?.let(onSelect) }, modifier = Modifier.size(42.dp).background(CommuteTeal, CircleShape)) {
                                Icon(Icons.Default.ArrowForward, "Use suggested destination", tint = Color.White)
                            }
                        }
                    }
                    TextButton(onClick = onChooseMap, modifier = Modifier.align(Alignment.Start).testTag("taxi_search_choose_map"), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.Default.Map, null, tint = CommuteTeal, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(taxiText(language, "Choose on map", "በካርታ ላይ ይምረጡ", "Kaartaa irraa filadhaa"), color = Color(0xFF075940), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                    if (recentDestinations.isNotEmpty()) {
                        Surface(color = Color.White, shape = RoundedCornerShape(20.dp), shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, null, tint = CommuteTeal, modifier = Modifier.size(19.dp))
                                    Text(taxiText(language, "Recent destinations", "የቅርብ ጊዜ መድረሻዎች", "Bakkeewwan dhiheenya kana"), Modifier.weight(1f).padding(start = 7.dp), color = Color(0xFF075940), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    TextButton(onClick = onClearRecent, contentPadding = PaddingValues(2.dp)) { Text(taxiText(language, "Clear all", "ሁሉንም አጥፋ", "Hunda qulqulleessi"), color = CommuteTeal, fontSize = 11.sp) }
                                }
                                recentDestinations.forEach { place -> TaxiPlaceRow(place, Icons.Default.LocationOn, onSelect) }
                            }
                        }
                    }
                    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOn, null, tint = CommuteTeal, modifier = Modifier.size(19.dp))
                                Text(if (query.trim().length >= 2) taxiText(language, "Suggestions", "ጥቆማዎች", "Yaada") else taxiText(language, "Search places", "ቦታ ይፈልጉ", "Bakka barbaadaa"), Modifier.padding(start = 7.dp), color = Color(0xFF075940), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 7.dp), color = CommuteTeal)
                            places.take(6).forEach { place -> TaxiPlaceRow(place, Icons.Default.Search, onSelect) }
                            message?.let { Text(it, Modifier.padding(top = 9.dp), color = Color(0xFF627385), fontSize = 12.sp) }
                            if (query.trim().length < 2 && recentDestinations.isEmpty()) Text(taxiText(language, "Type a landmark or address to see matching places.", "ተዛማጅ ቦታዎችን ለማየት ምልክት ወይም አድራሻ ያስገቡ።", "Bakka walfakkaataa argachuuf mallattoo ykn teessoo barreessaa."), Modifier.padding(top = 9.dp), color = Color(0xFF627385), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaxiPlaceRow(place: MobilityPlace, icon: androidx.compose.ui.graphics.vector.ImageVector, onSelect: (MobilityPlace) -> Unit) {
    Surface(onClick = { onSelect(place) }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (icon == Icons.Default.Search) CommuteTeal else Color(0xFF07864E), modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(place.name.substringBefore(","), color = CommuteInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                Text(place.name.substringAfter(",", "Addis Ababa").trim(), color = Color(0xFF748188), fontSize = 11.sp, maxLines = 1)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF839298))
        }
    }
}

@Composable
private fun TaxiPassengerTopMenu(expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    onHome: () -> Unit, onTrips: () -> Unit, onPayments: () -> Unit, onDailyCommute: () -> Unit,
    onAlertSound: () -> Unit, onNotifications: () -> Unit, onProfile: () -> Unit, onPrivacy: () -> Unit, onSharedTaxi: () -> Unit,
    onSignOut: () -> Unit, language: AppLanguage) {
    Box {
        IconButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.testTag("taxi_menu_top")) {
            Icon(Icons.Default.Menu, contentDescription = "Open menu", tint = CommuteInk, modifier = Modifier.size(27.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.background(Color.White).width(238.dp)) {
            listOf(
                Triple(taxiText(language, "Taxi home", "የታክሲ መነሻ", "Mana taaksii"), Icons.Default.Home, onHome),
                Triple(taxiText(language, "My Rides", "ጉዞዎቼ", "Imaloota Koo"), Icons.Default.LocalTaxi, onTrips),
                Triple(taxiText(language, "Wallet & Payments", "ዋሌት እና ክፍያዎች", "Walletii fi Kaffaltii"), Icons.Default.AccountBalanceWallet, onPayments),
                Triple(taxiText(language, "Daily Commute", "ዕለታዊ ጉዞ", "Imala Guyyaa Guyyaa"), Icons.Default.DirectionsBus, onDailyCommute),
                Triple(taxiText(language, "Share a taxi", "ታክሲ ይጋሩ", "Taaksii qoodadhaa"), Icons.Default.Groups, onSharedTaxi),
                Triple(taxiText(language, "Alert sound", "የማሳወቂያ ድምፅ", "Sagalee beeksisaa"), Icons.Default.NotificationsActive, onAlertSound),
                Triple(taxiText(language, "Notifications", "ማሳወቂያዎች", "Beeksisa"), Icons.Default.NotificationsNone, onNotifications),
                Triple(taxiText(language, "My Profile", "መገለጫዬ", "Piroofaayila Koo"), Icons.Default.Person, onProfile),
                Triple(taxiText(language, "Privacy & Account", "ግላዊነት እና መለያ", "Eegumsa dhuunfaa fi Herrega"), Icons.Default.Security, onPrivacy),
                Triple(taxiText(language, "Sign out", "ውጣ", "Ba'i"), Icons.Default.Logout, onSignOut)
            ).forEach { (label, icon, action) ->
                DropdownMenuItem(text = { Text(label, color = CommuteInk, fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(icon, null, tint = CommuteTeal) },
                    onClick = { onExpandedChange(false); action() })
            }
        }
    }
}

@Composable
private fun TaxiGuestTopMenu(expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    onSignIn: () -> Unit, onSharedTaxi: () -> Unit, onAlertSound: () -> Unit, onPrivacy: () -> Unit, language: AppLanguage) {
    Box {
        IconButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.testTag("taxi_menu_top")) {
            Icon(Icons.Default.Menu, contentDescription = "Open menu", tint = CommuteInk, modifier = Modifier.size(25.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.background(Color.White).width(224.dp)) {
            listOf(
                Triple(taxiText(language, "Share a taxi", "ታክሲ ይጋሩ", "Taaksii qoodadhaa"), Icons.Default.Groups, onSharedTaxi),
                Triple(taxiText(language, "Alert sound", "የማሳወቂያ ድምፅ", "Sagalee beeksisaa"), Icons.Default.NotificationsActive, onAlertSound),
                Triple(taxiText(language, "Sign in", "ግባ", "Seeni"), Icons.Default.Person, onSignIn),
                Triple(taxiText(language, "Privacy & account", "ግላዊነት እና መለያ", "Eegumsa dhuunfaa fi Herrega"), Icons.Default.Security, onPrivacy)
            ).forEach { (label, icon, action) ->
                DropdownMenuItem(text = { Text(label, color = CommuteInk, fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(icon, null, tint = CommuteTeal) },
                    onClick = { onExpandedChange(false); action() })
            }
        }
    }
}

@Composable internal fun PassengerCurrentRide(ride: TaxiRide, busy: Boolean, model: TaxiViewModel, mapTiles: Boolean = true, mapApi: MobilityApi? = null, nearbyCars: List<TaxiNearbyCar> = emptyList()) {
    val context = LocalContext.current
    val freeWaiting = taxiFreeWaitingSeconds(ride, mapTiles)
    val connecting = ride.status == "REQUESTED"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (connecting) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(if (connecting) 8.dp else 13.dp)) {
        TaxiMap(ride.originLatitude to ride.originLongitude, ride.destinationLatitude to ride.destinationLongitude, ride.location,
            if (ride.status == "IN_PROGRESS") "IN_PROGRESS" else "ASSIGNED", height = if (connecting) 400 else 265, pickupLabel = ride.origin, destinationLabel = ride.destination, tileDownloadsEnabled = mapTiles, providedApi = mapApi,
            nearbyCars = if (ride.status == "REQUESTED") nearbyCars else emptyList(), routeGeometry = ride.routeGeometry, routeGeometryQuality = ride.routeGeometryQuality, freezeVehicle = ride.distancePaused)
        Surface(shape = RoundedCornerShape(24.dp), color = PassoMobilityPalette.card, contentColor = PassoMobilityPalette.text,
            border = BorderStroke(1.dp, PassoMobilityPalette.border)) { Column(Modifier.padding(if (connecting) 12.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(if (connecting) 7.dp else 12.dp)) {
            if (ride.status == "REQUESTED") TaxiConnectionPulse(animated = mapTiles, compact = true) else Text(when (ride.status) {
                "ACCEPTED" -> "Your driver is on the way"; "AT_PICKUP" -> "Your driver has arrived"; "IN_PROGRESS" -> "Your trip is in progress"; "COMPLETED" -> "You have arrived"; else -> "Ride cancelled"
            }, color = PassoMobilityPalette.text, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            if (connecting) {
                TaxiCompactRouteRow("Pickup", ride.origin, true)
                TaxiCompactRouteRow("Destination", ride.destination, false)
            } else {
                TaxiRouteRow("Pickup", ride.origin, true); TaxiRouteRow("Destination", ride.destination, false)
            }
            if (taxiActive(ride.status)) {
                HorizontalDivider(color = PassoMobilityPalette.border)
                Text(ride.driverName ?: "Your assigned driver", fontWeight = FontWeight.Bold, color = PassoMobilityPalette.text, fontSize = 19.sp)
                Text(listOfNotNull(ride.vehicleModel, ride.vehiclePlate).joinToString(" · "), color = PassoMobilityPalette.muted)
                OutlinedButton(onClick = { callTaxiContact(context, ride.driverPhone) }, enabled = !ride.driverPhone.isNullOrBlank(), modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = PassoMobilityPalette.brand), border = BorderStroke(1.dp, PassoMobilityPalette.brand)) { Icon(Icons.Default.Phone, null); Spacer(Modifier.width(8.dp)); Text("Call driver") }
            }
            if (ride.status in setOf("ACCEPTED", "AT_PICKUP")) (ride.boardingQrToken ?: ride.boardingPin)?.let { token ->
                Surface(color = CommuteMint, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Show your boarding QR to the driver", color = CommuteInk, fontSize = 13.sp)
                    QrCodeCanvas(token, Modifier.size(176.dp).padding(top = 8.dp), moduleColor = Color.Black)
                } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (ride.status in setOf("IN_PROGRESS", "COMPLETED")) "Meter fare" else "Estimated fare", color = PassoMobilityPalette.muted)
                Text(taxiMoney(ride.fareEtb ?: ride.estimatedFareEtb), color = PassoMobilityPalette.brand, fontSize = if (connecting) 20.sp else 24.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (ride.status == "IN_PROGRESS") Text("${"%.2f".format(ride.meterDistanceKm)} km · Billable waiting ${taxiDuration(ride.billableWaitingSeconds)}", color = PassoMobilityPalette.muted, fontSize = 13.sp)
            if (!connecting) Text(if (ride.status == "COMPLETED") "Pay this fare directly to your driver. The app has not collected payment." else "Pay your driver directly. Normal traffic time is not charged as waiting.", color = PassoMobilityPalette.muted, fontSize = 12.sp)
            if (ride.stopWaitingRequested) {
                Text("Your driver requests a passenger stop. Approve only if you requested it. Driven distance pauses; after the free allowance, ${ride.waitingRateEtbPerMinute?.let { "waiting costs ${taxiMoney(it)}/min" } ?: "configured waiting charges apply"}.", color = PassoMobilityPalette.text, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { model.approveWait(true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Approve stop") }
                    OutlinedButton(onClick = { model.approveWait(false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Decline") } }
            }
            if (ride.waiting == "STOP") Surface(color = CommuteMint, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("taxi_approved_stop")) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Passenger stop approved", color = CommuteInk, fontWeight = FontWeight.Bold)
                    Text(taxiStopSummary(ride, freeWaiting), color = CommuteInk, fontSize = 13.sp)
                    if ((freeWaiting ?: 0) > 0) ride.waitingRateEtbPerMinute?.let { Text("After free waiting: ${taxiMoney(it)}/min. Normal traffic is not billed as a stop.", color = CommuteInk, fontSize = 12.sp) }
                }
            }
            if (ride.status in setOf("REQUESTED", "ACCEPTED", "AT_PICKUP")) TextButton(onClick = { model.cancelRide() }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = PassoMobilityPalette.brand)) { Text("Cancel ride") }
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
