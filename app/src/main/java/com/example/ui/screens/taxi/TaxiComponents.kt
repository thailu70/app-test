package com.example.ui.screens.taxi

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.core.localization.AppLanguage
import com.example.data.api.*
import com.example.ui.components.MobilityRoadMap
import com.example.ui.components.PassoMobilityPalette
import com.example.ui.components.replaceRoadOverlays
import com.example.ui.screens.intercity.MobilityLocationPicker
import com.example.ui.screens.passenger.*
import com.example.core.time.EthiopianDateTime
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.text.NumberFormat
import java.util.*

internal fun taxiText(language: AppLanguage, english: String, amharic: String, oromo: String): String = when (language) {
    AppLanguage.ENGLISH -> english
    AppLanguage.AMHARIC -> amharic
    AppLanguage.OROMO -> oromo
}

internal data class TaxiPendingIntent(val scope: com.example.core.environment.ApiSessionScope,
    val route: TaxiRouteInput? = null, val sharedId: String? = null, val sharedStart: Boolean = false,
    val phone: String? = null, val loginOtpSent: Boolean = false, val requestAfterSignIn: Boolean = false)
/** Process-only sign-in return context; no location draft or bearer is written to disk. */
internal object TaxiPendingNavigation {
    private var pending: TaxiPendingIntent? = null
    fun route(value: TaxiRouteInput, requestAfterSignIn: Boolean = false) { pending = (peek() ?: TaxiPendingIntent(ApiClient.sessionScope())).copy(route = value, requestAfterSignIn = requestAfterSignIn) }
    fun login(phone: String, otpSent: Boolean) { pending = (peek() ?: TaxiPendingIntent(ApiClient.sessionScope())).copy(phone = phone, loginOtpSent = otpSent) }
    fun shared(id: String? = null, start: Boolean = false) { pending = TaxiPendingIntent(ApiClient.sessionScope(), sharedId = id, sharedStart = start) }
    fun peek(): TaxiPendingIntent? = pending?.takeIf { ApiClient.isCurrent(it.scope) }
    fun take(): TaxiPendingIntent? = pending.also { pending = null }?.takeIf { ApiClient.isCurrent(it.scope) }
    fun clear() { pending = null }
}

internal fun taxiMoney(value: Double) = "ETB " + NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 2 }.format(value)
internal val TaxiBrandEmerald = Color(0xFF00A86B)
internal val TaxiBrandDeepEmerald = PassoMobilityPalette.brand
internal val TaxiBrandNavy = PassoMobilityPalette.text
internal val TaxiCanvas = PassoMobilityPalette.canvas
internal fun taxiDuration(value: Int): String {
    val seconds = value.coerceAtLeast(0)
    val minutes = seconds / 60
    return if (minutes >= 60) {
        "%dh %02dm".format(Locale.US, minutes / 60, minutes % 60)
    } else {
        "%02d:%02d".format(Locale.US, minutes, seconds % 60)
    }
}
internal fun taxiTime(value: String): String = EthiopianDateTime.formatTimestamp(value)
internal fun taxiActive(status: String) = status in setOf("ACCEPTED", "AT_PICKUP", "IN_PROGRESS")
internal fun sharedActive(status: String) = status in setOf("BOARDING", "IN_PROGRESS")
internal fun taxiStopSummary(ride: TaxiRide, freeSeconds: Int? = ride.waitingFreeRemainingSeconds): String = when {
    ride.waitingState == "GPS_PAUSED" -> "Waiting clock paused · fresh GPS needed"
    ride.waitingState == "LIMIT_REACHED" -> "Waiting limit reached · distance paused"
    ride.distancePaused && freeSeconds != null && freeSeconds > 0 -> "Distance paused · free waiting ${taxiDuration(freeSeconds)}"
    ride.distancePaused && ride.waitingRateEtbPerMinute != null -> "Distance paused · waiting ${taxiMoney(ride.waitingRateEtbPerMinute)}/min"
    else -> "Passenger stop approved · waiting charges may apply"
}
@Composable internal fun taxiFreeWaitingSeconds(ride: TaxiRide, tick: Boolean = true): Int? {
    var remaining by remember(ride.id, ride.serverTime, ride.waitingFreeRemainingSeconds) { mutableStateOf(ride.waitingFreeRemainingSeconds) }
    LaunchedEffect(ride.id, ride.serverTime, ride.waitingFreeRemainingSeconds, ride.waitingState, tick) {
        if (!tick || ride.waitingState != "FREE_ALLOWANCE") return@LaunchedEffect
        // Interpolate this display briefly; the next server snapshot remains authoritative.
        repeat(10) { delay(1_000); remaining = remaining?.let { (it - 1).coerceAtLeast(0) }; if (remaining == 0) return@LaunchedEffect }
    }
    return remaining
}

@Composable internal fun taxiViewModel(authenticated: Boolean, driver: Boolean): TaxiViewModel {
    val token = ApiClient.getAuthToken()
    val identity = "taxi:${ApiClient.sessionScope()}:$token:$driver"
    val api = remember(identity) { token?.takeIf(String::isNotBlank)?.let { ApiClient.createSessionService(TaxiApi::class.java, it) } ?: ApiClient.createFeatureService(TaxiApi::class.java) }
    val model: TaxiViewModel = viewModel(key = identity, factory = remember(identity) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TaxiViewModel(api, authenticated, driver) as T
    } })
    val owner = LocalLifecycleOwner.current
    val state by model.state.collectAsState()
    val interval by rememberUpdatedState(state.settings.pollingSeconds.coerceIn(5, 60))
    LaunchedEffect(owner, model) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(interval * 1000L); model.refresh(quiet = true) } } }
    return model
}

@Composable internal fun TaxiHeader(title: String? = null, onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    onDailyCommute: (() -> Unit)? = null, onSignIn: (() -> Unit)? = null,
    languageLabel: String? = null, onLanguage: (() -> Unit)? = null,
    guestService: String? = null, onGuestService: ((String) -> Unit)? = null,
    signInLabel: String = "Sign in",
    containerColor: Color = Color(0xFFDDF3E6),
    brandColor: Color = CommuteTeal,
    trailing: @Composable RowScope.() -> Unit = {}) {
    val dailyCommuteLabel = if (LocalConfiguration.current.screenWidthDp >= 380) "Daily Commute" else "Daily"
    Column(Modifier.fillMaxWidth().background(containerColor)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack, Modifier.testTag("taxi_back")) { Icon(Icons.Default.ArrowBack, "Back", tint = CommuteInk) }
            else if (onMenu != null) IconButton(onClick = onMenu, Modifier.testTag("taxi_menu")) { Icon(Icons.Default.Menu, "Menu", tint = CommuteInk) }
            if (title == null) Image(painterResource(R.drawable.auth_passo_wordmark), "PASSO", Modifier.width(132.dp).height(44.dp), contentScale = ContentScale.Fit)
            else Text(title, color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, modifier = Modifier.weight(1f), maxLines = 2)
            if (title == null) Spacer(Modifier.weight(1f))
            if (languageLabel != null && onLanguage != null) Surface(onClick = onLanguage, color = Color.White.copy(alpha = .84f),
                shape = RoundedCornerShape(23.dp), border = BorderStroke(1.dp, Color(0xFFD6E5E6)), modifier = Modifier.testTag("taxi_language")) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Language, null, Modifier.size(17.dp), tint = brandColor)
                    Spacer(Modifier.width(5.dp)); Text("$languageLabel ⌄", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }
            if (onDailyCommute != null) OutlinedButton(onClick = onDailyCommute, modifier = Modifier.testTag("taxi_daily_commute"), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 5.dp), border = BorderStroke(1.dp, brandColor)) {
                Icon(Icons.Default.DirectionsBus, null, Modifier.size(16.dp), tint = brandColor); Spacer(Modifier.width(4.dp)); Text(dailyCommuteLabel, color = brandColor, fontSize = 11.sp, maxLines = 1)
            }
            if (onSignIn != null) OutlinedButton(onClick = onSignIn, contentPadding = PaddingValues(horizontal = 15.dp, vertical = 5.dp),
                border = BorderStroke(1.5.dp, brandColor), shape = RoundedCornerShape(24.dp), modifier = Modifier.testTag("taxi_sign_in")) {
                Text(signInLabel, color = brandColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            trailing()
        }
        if (guestService != null && onGuestService != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("TAXI" to ("Taxi" to Icons.Default.LocalTaxi), "CARGO" to ("Cargo" to Icons.Default.Inventory2), "COMMUTE" to ("Commute" to Icons.Default.DirectionsBus), "INTERCITY" to ("Intercity" to Icons.Default.Map)).forEach { (key, pair) ->
                    val active = guestService == key
                    Surface(onClick = { onGuestService(key) }, modifier = Modifier.weight(1f).testTag("guest_service_$key"),
                        color = if (active) CommuteTeal.copy(alpha = .92f) else Color.White.copy(alpha = .8f),
                        shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, if (active) CommuteTeal else Color(0xFFD9DFDE))) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(pair.second, null, Modifier.size(17.dp), tint = if (active) Color.White else CommuteInk)
                            Spacer(Modifier.width(5.dp)); Text(pair.first, color = if (active) Color.White else CommuteInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun TaxiNotice(message: String?, onDismiss: () -> Unit) {
    message?.let { Surface(color = CommuteMint, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(it, Modifier.weight(1f).padding(vertical = 9.dp), color = CommuteInk, fontSize = 13.sp)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Dismiss message", Modifier.size(18.dp)) }
        }
    } }
}

/** Driver actions use the same compact, top-right dropdown used by passengers. */
@Composable internal fun TaxiDriverTopMenu(expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    onHome: () -> Unit, onTrips: () -> Unit, onCredit: () -> Unit, onAlertSound: () -> Unit,
    onDailyCommute: (() -> Unit)? = null, onCargoPortal: (() -> Unit)? = null,
    cargoPortalLabel: String = "Cargo portal", onSignOut: () -> Unit = {}) {
    Box {
        IconButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.testTag("taxi_menu_top")) {
            Icon(Icons.Default.Menu, contentDescription = "Open menu", tint = CommuteInk, modifier = Modifier.size(27.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.background(Color.White).width(238.dp)) {
            listOf(
                Triple("Ride calls", Icons.Default.Home, onHome),
                Triple("My trips", Icons.Default.LocalTaxi, onTrips),
                Triple("Driver credit", Icons.Default.AccountBalanceWallet, onCredit),
                Triple("Alert sound", Icons.Default.NotificationsActive, onAlertSound)
            ).let { items ->
                buildList {
                    addAll(items)
                    onDailyCommute?.let { add(Triple("Daily Commute", Icons.Default.DirectionsBus, it)) }
                    onCargoPortal?.let { add(Triple(cargoPortalLabel, Icons.Default.LocalShipping, it)) }
                    add(Triple("Sign out", Icons.Default.Logout, onSignOut))
                }
            }.forEach { (label, icon, action) ->
                DropdownMenuItem(text = { Text(label, color = CommuteInk, fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(icon, null, tint = CommuteTeal) },
                    onClick = { onExpandedChange(false); action() })
            }
        }
    }
}

@Composable internal fun TaxiPrimary(text: String, enabled: Boolean = true, tag: String = "taxi_primary", onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(tag), enabled = enabled,
        shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = TaxiBrandEmerald, disabledContainerColor = Color(0xFFB7C4BF)), contentPadding = PaddingValues(13.dp)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(10.dp)); Icon(Icons.Default.ArrowForward, null, Modifier.size(21.dp))
    }
}

@Composable internal fun TaxiRouteRow(label: String, value: String, pickup: Boolean, enabled: Boolean = true, dark: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (pickup) Icons.Default.TripOrigin else Icons.Default.LocationOn, null, tint = TaxiBrandEmerald, modifier = Modifier.size(25.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(label, color = if (dark) PassoMobilityPalette.muted else Color(0xFF627385), fontSize = 12.sp)
            Text(value, color = if (dark) PassoMobilityPalette.text else CommuteInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Icon(Icons.Default.EditLocationAlt, "Edit $label", tint = TaxiBrandEmerald, modifier = Modifier.size(21.dp))
    }
}

@Composable internal fun TaxiTierTiles(tiers: List<TaxiTier>, selected: String, settings: TaxiSettings,
    enabled: Boolean, onSelect: (String) -> Unit, language: AppLanguage = AppLanguage.ENGLISH,
    estimate: TaxiEstimate? = null) {
    val fallback = listOf(TaxiTier("ECONOMY", "Economy", 1.0), TaxiTier("COMFORT", "Comfort", 1.25), TaxiTier("MINI_VAN", "Mini-Van", 1.5))
    val carSweep = rememberInfiniteTransition(label = "Taxi car highlight")
    val sweepProgress by carSweep.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart),
        label = "Car light sweep"
    )
    Row(Modifier.fillMaxWidth().animateContentSize(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            (tiers.ifEmpty { fallback }).take(3).forEach { tier ->
                val active = tier.id == selected
                val surface by animateColorAsState(if (active) CommuteMint.copy(alpha = .58f) else Color.Transparent, tween(180), label = "Vehicle tier surface")
                val border by animateColorAsState(if (active) TaxiBrandEmerald else Color(0xFFD9DFDE), tween(180), label = "Vehicle tier border")
                val fare = estimate?.let {
                    val raw = (settings.baseFareEtb + it.distanceKm * settings.perKmEtb) * tier.multiplier
                    kotlin.math.round(kotlin.math.max(settings.minimumFareEtb, raw) * 100.0) / 100.0
                }
                val vehicleArt = when (tier.id) {
                    "COMFORT" -> R.drawable.taxi_comfort_ev
                    "MINI_VAN" -> R.drawable.taxi_minivan
                    else -> R.drawable.taxi_economy_2007
                }
                Surface(onClick = { onSelect(tier.id) }, enabled = enabled && settings.enabled,
                    modifier = Modifier.weight(1f).testTag("taxi_tier_${tier.id}"), color = surface,
                    border = BorderStroke(if (active) 2.dp else 1.dp, border), shape = RoundedCornerShape(16.dp), shadowElevation = 0.dp) {
                    Column(Modifier.fillMaxWidth().height(116.dp).padding(horizontal = 3.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Image(painterResource(vehicleArt), contentDescription = when (tier.id) {
                            "COMFORT" -> taxiText(language, "Modern electric sedan", "ዘመናዊ ኤሌክትሪክ ሴዳን", "Sedaanii elektirikii ammayyaa")
                            "MINI_VAN" -> taxiText(language, "Minivan", "ሚኒ ቫን", "Minivan")
                            else -> taxiText(language, "2007 Toyota compact", "የ2007 ቶዮታ ኮምፓክት", "Toyota xiqqaa bara 2007")
                        }, modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 2.dp).drawWithContent {
                            drawContent()
                            val sweepX = (sweepProgress * 1.8f - .4f) * size.width
                            drawRect(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color.Transparent, Color.White.copy(alpha = .16f), Color.White.copy(alpha = .68f), Color.White.copy(alpha = .16f), Color.Transparent),
                                    start = Offset(sweepX, 0f),
                                    end = Offset(sweepX + size.width * .42f, size.height)
                                ),
                                blendMode = BlendMode.SrcAtop
                            )
                        }, contentScale = ContentScale.Fit)
                        Text(when (tier.id) {
                            "COMFORT" -> taxiText(language, "Comfort", "ምቹ", "Mijataa")
                            "MINI_VAN" -> taxiText(language, "Minivan", "ሚኒ ቫን", "Minivan")
                            else -> taxiText(language, "Economy", "ኢኮኖሚ", "Diinagdee")
                        }, color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                        Surface(color = if (active) Color(0xFFDFF3E7) else Color(0xFFF1F5F3),
                            shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth().height(21.dp)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(fare?.let(::taxiMoney) ?: "—", color = if (active) TaxiBrandDeepEmerald else Color(0xFF627385),
                                    fontWeight = FontWeight.Bold, fontSize = 9.sp, maxLines = 1, softWrap = false,
                                    overflow = TextOverflow.Clip, letterSpacing = (-.2).sp,
                                    modifier = Modifier.testTag("taxi_tier_fare_${tier.id}"))
                            }
                        }
                    }
                }
            }
    }
}

/** A real native base map, not the mockup image. No unassigned driver positions are invented. */
@Composable internal fun TaxiMap(pickup: Pair<Double, Double>?, destination: Pair<Double, Double>?,
    driver: MobilityLocation? = null, stage: String = "ASSIGNED", height: Int = 255,
    pickupLabel: String = "Pickup", destinationLabel: String = "Destination", modifier: Modifier = Modifier, tileDownloadsEnabled: Boolean = true, providedApi: MobilityApi? = null,
    nearbyCars: List<TaxiNearbyCar> = emptyList(), routeGeometry: MobilityRoadGeometry? = null, freezeVehicle: Boolean = false, routeGeometryQuality: String? = null,
    showPickupMarker: Boolean = true, attributionAboveSheet: Boolean = false, attributionBottomPadding: Dp = 0.dp) {
    val nearby = nearbyCars.map { it.latitude to it.longitude }
    if (pickup != null && destination != null) {
        MobilityRoadMap(pickup, destination, driver, stage, pickupLabel, destinationLabel,
            modifier, mapHeight = height, trackingActive = driver != null, tileDownloadsEnabled = tileDownloadsEnabled, driverIcon = R.drawable.taxi_car_top, providedApi = providedApi,
            nearbyPoints = nearby, statusMessage = if (nearby.isNotEmpty()) "Nearby cars · approximate" else null,
            providedRoute = routeGeometry, freezeVehicle = freezeVehicle, providedRouteQuality = routeGeometryQuality)
    } else {
        val context = LocalContext.current; val owner = LocalLifecycleOwner.current
        val map = remember(context) { Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                overlayManager.tilesOverlay.setColorFilter(null)
                overlayManager.tilesOverlay.setLoadingBackgroundColor(android.graphics.Color.rgb(231, 238, 233))
                setUseDataConnection(tileDownloadsEnabled); setMultiTouchControls(true); setBuiltInZoomControls(false)
                controller.setZoom(13.0); controller.setCenter(GeoPoint(pickup?.first ?: 9.0108, pickup?.second ?: 38.7613))
            } }
        var lastCenter by remember(map) { mutableStateOf<Pair<Double, Double>?>(null) }
        DisposableEffect(map, owner) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) map.onResume(); if (event == Lifecycle.Event.ON_STOP) map.onPause() }
            owner.lifecycle.addObserver(observer); map.onResume()
            onDispose { owner.lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
        }
        Box(modifier.fillMaxWidth().height(height.dp).clipToBounds()
            .background(if (attributionAboveSheet) PassoMobilityPalette.canvas else Color.Transparent)) {
            AndroidView(factory = { (map.parent as? ViewGroup)?.removeView(map); map }, Modifier.fillMaxSize(), update = {
                map.alpha = 1f
                replaceRoadOverlays(map, emptyList(), emptyList(), if (showPickupMarker) pickup else null, destination, driver, driver != null && !driver.stale,
                    false, pickupLabel, destinationLabel, R.drawable.taxi_car_top, nearby)
                val targetCenter = pickup ?: driver?.let { it.latitude to it.longitude } ?: (9.0108 to 38.7613)
                if (lastCenter != targetCenter) {
                    map.controller.setCenter(GeoPoint(targetCenter.first, targetCenter.second))
                    lastCenter = targetCenter
                }
                map.invalidate()
            })
            val attributionPosition = if (attributionAboveSheet) Modifier.align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = attributionBottomPadding) else Modifier.align(Alignment.BottomEnd)
            Text("© OpenStreetMap contributors", attributionPosition.background(Color.White.copy(alpha = .80f))
                .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) } }
                .padding(horizontal = 4.dp, vertical = 2.dp), color = CommuteInk, fontSize = 8.sp)
        }
    }
}

@Composable internal fun TaxiConnectionPulse(animated: Boolean = true, compact: Boolean = false) {
    val phase = if (animated) {
        val transition = rememberInfiniteTransition(label = "Nearby driver connection")
        val value by transition.animateFloat(0.8f, 1.3f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "Pickup pulse")
        value
    } else 1f
    Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 3.dp else 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 38.dp else 55.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.size(if (compact) 30.dp else 40.dp).graphicsLayer { scaleX = phase; scaleY = phase; alpha = (1.4f - phase).coerceIn(.1f, .6f) }, shape = CircleShape, color = CommuteTeal) {}
            Icon(Icons.Default.LocationOn, null, tint = CommuteTeal, modifier = Modifier.size(if (compact) 23.dp else 28.dp))
        }
        Column(Modifier.weight(1f)) { Text("Connecting to nearby drivers…", fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = if (compact) 14.sp else 17.sp)
            Text("Eligible drivers can select your request.", color = Color(0xFF627385), fontSize = if (compact) 10.sp else 12.sp) }
    }
}

@Composable internal fun TaxiLocationDialog(destination: Boolean, current: Pair<Double, Double>?, label: String,
    onDismiss: () -> Unit, onSelect: (String, Pair<Double, Double>) -> Unit) {
    var selectedLabel by remember { mutableStateOf(label) }
    MobilityLocationPicker(if (destination) "Where to?" else "Choose pickup", current, onDismiss,
        onChoose = { onSelect(selectedLabel.ifBlank { if (destination) "Selected destination" else "Selected pickup" }, it) },
        onPlaceName = { selectedLabel = it }, initialLabel = label)
}

internal fun callTaxiContact(context: android.content.Context, number: String?) {
    number?.takeIf { Regex("[+0-9 ()-]{5,25}").matches(it) }?.let { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", it, null))) }
}
