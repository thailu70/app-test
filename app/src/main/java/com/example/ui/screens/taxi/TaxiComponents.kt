package com.example.ui.screens.taxi

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.data.api.*
import com.example.ui.components.MobilityRoadMap
import com.example.ui.components.replaceRoadOverlays
import com.example.ui.screens.intercity.MobilityLocationPicker
import com.example.ui.screens.passenger.*
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.roundToInt

internal data class TaxiPendingIntent(val scope: com.example.core.environment.ApiSessionScope,
    val route: TaxiRouteInput? = null, val sharedId: String? = null, val sharedStart: Boolean = false,
    val phone: String? = null, val loginOtpSent: Boolean = false)
/** Process-only sign-in return context; no location draft or bearer is written to disk. */
internal object TaxiPendingNavigation {
    private var pending: TaxiPendingIntent? = null
    fun route(value: TaxiRouteInput) { pending = (peek() ?: TaxiPendingIntent(ApiClient.sessionScope())).copy(route = value) }
    fun login(phone: String, otpSent: Boolean) { pending = (peek() ?: TaxiPendingIntent(ApiClient.sessionScope())).copy(phone = phone, loginOtpSent = otpSent) }
    fun shared(id: String? = null, start: Boolean = false) { pending = TaxiPendingIntent(ApiClient.sessionScope(), sharedId = id, sharedStart = start) }
    fun peek(): TaxiPendingIntent? = pending?.takeIf { ApiClient.isCurrent(it.scope) }
    fun take(): TaxiPendingIntent? = pending.also { pending = null }?.takeIf { ApiClient.isCurrent(it.scope) }
    fun clear() { pending = null }
}

internal fun taxiMoney(value: Double) = "ETB " + NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 2 }.format(value)
internal fun taxiDuration(value: Int) = "%02d:%02d".format(Locale.US, value / 60, value % 60)
internal fun taxiTime(value: String): String = runCatching {
    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }.parse(value) ?: error("Invalid trip date")
    SimpleDateFormat("EEE, d MMM · h:mm a", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("Africa/Addis_Ababa") }.format(date)
}.getOrDefault(value)
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
    guestService: String? = null, onGuestService: ((String) -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}) {
    val dailyCommuteLabel = if (LocalConfiguration.current.screenWidthDp >= 380) "Daily Commute" else "Daily"
    Column(Modifier.fillMaxWidth().background(CommuteCream)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack, Modifier.testTag("taxi_back")) { Icon(Icons.Default.ArrowBack, "Back", tint = CommuteInk) }
            else if (onMenu != null) IconButton(onClick = onMenu, Modifier.testTag("taxi_menu")) { Icon(Icons.Default.Menu, "Menu", tint = CommuteInk) }
            if (title == null) Image(painterResource(R.drawable.auth_passo_wordmark), "PASSO", Modifier.width(126.dp).height(42.dp), contentScale = ContentScale.Fit)
            else Text(title, color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, modifier = Modifier.weight(1f), maxLines = 2)
            if (title == null) Spacer(Modifier.weight(1f))
            if (onDailyCommute != null) OutlinedButton(onClick = onDailyCommute, modifier = Modifier.testTag("taxi_daily_commute"), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 5.dp), border = BorderStroke(1.dp, CommuteTeal)) {
                Icon(Icons.Default.DirectionsBus, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(dailyCommuteLabel, fontSize = 11.sp, maxLines = 1)
            }
            if (onSignIn != null) TextButton(onClick = onSignIn, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 6.dp), modifier = Modifier.testTag("taxi_sign_in")) {
                Text("Sign in", color = CommuteTeal, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            trailing()
        }
        if (guestService != null && onGuestService != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("TAXI" to ("Taxi" to Icons.Default.LocalTaxi), "CARGO" to ("Cargo" to Icons.Default.Inventory2), "GROUP" to ("Group" to Icons.Default.Groups)).forEach { (key, pair) ->
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

@Composable internal fun TaxiPrimary(text: String, enabled: Boolean = true, tag: String = "taxi_primary", onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(tag), enabled = enabled,
        shape = RoundedCornerShape(19.dp), colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal), contentPadding = PaddingValues(13.dp)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(10.dp)); Icon(Icons.Default.ArrowForward, null, Modifier.size(21.dp))
    }
}

@Composable internal fun TaxiRouteRow(label: String, value: String, pickup: Boolean, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (pickup) Icons.Default.TripOrigin else Icons.Default.LocationOn, null, tint = CommuteTeal, modifier = Modifier.size(25.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(label, color = Color(0xFF627385), fontSize = 12.sp)
            Text(value, color = CommuteInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Icon(Icons.Default.EditLocationAlt, "Edit $label", tint = CommuteTeal, modifier = Modifier.size(21.dp))
    }
}

@Composable internal fun TaxiTierTiles(tiers: List<TaxiTier>, selected: String, settings: TaxiSettings,
    distanceKm: Double?, enabled: Boolean, onSelect: (String) -> Unit) {
    val fallback = listOf(TaxiTier("ECONOMY", "Economy"), TaxiTier("COMFORT", "Comfort"), TaxiTier("MINI_VAN", "Mini-Van"))
    var previewTier by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(previewTier) {
        val shown = previewTier ?: return@LaunchedEffect
        delay(1400)
        if (previewTier == shown) previewTier = null
    }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AnimatedVisibility(visible = previewTier != null) {
            val item = (tiers.ifEmpty { fallback }).firstOrNull { it.id == previewTier }
            item?.let { chosen ->
                val previewFare = distanceKm?.let { taxiFareForTier(it, settings, chosen) }
                Surface(Modifier.align(Alignment.CenterHorizontally), color = CommuteTeal.copy(alpha = .88f), shape = RoundedCornerShape(16.dp)) {
                    Text("${if (chosen.id == "MINI_VAN") "Minivan" else chosen.name}  ·  ${previewFare?.let(::taxiMoney) ?: "Fare estimate"}",
                        Modifier.padding(horizontal = 14.dp, vertical = 7.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            (tiers.ifEmpty { fallback }).take(3).forEach { tier ->
                val active = tier.id == selected
                val surface by animateColorAsState(if (active) CommuteMint else Color.White.copy(alpha = .84f), tween(180), label = "Vehicle tier surface")
                val border by animateColorAsState(if (active) CommuteTeal else Color(0xFFD9DFDE), tween(180), label = "Vehicle tier border")
                val scale by animateFloatAsState(if (active) 1.04f else 1f, tween(180), label = "Vehicle selection")
                val fare = distanceKm?.let { taxiFareForTier(it, settings, tier) }
                Surface(onClick = { previewTier = tier.id; onSelect(tier.id) }, enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("taxi_tier_${tier.id}"), color = surface,
                    border = BorderStroke(if (active) 2.dp else 1.dp, border), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(painterResource(when (tier.id) { "COMFORT" -> R.drawable.taxi_comfort; "MINI_VAN" -> R.drawable.taxi_minivan; else -> R.drawable.taxi_economy }), null,
                            Modifier.fillMaxWidth().height(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }, contentScale = ContentScale.Fit)
                        Text(if (tier.id == "MINI_VAN") "Minivan" else tier.name, color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                        Text(fare?.let(::taxiMoney) ?: "—", color = if (active) CommuteTeal else Color(0xFF627385), fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun taxiFareForTier(distanceKm: Double, settings: TaxiSettings, tier: TaxiTier): Double {
    val value = max(settings.minimumFareEtb, (settings.baseFareEtb + distanceKm * settings.perKmEtb) * tier.multiplier)
    return (value * 100.0).roundToInt() / 100.0
}

/** A real native base map, not the mockup image. No unassigned driver positions are invented. */
@Composable internal fun TaxiMap(pickup: Pair<Double, Double>?, destination: Pair<Double, Double>?,
    driver: MobilityLocation? = null, stage: String = "ASSIGNED", height: Int = 255,
    pickupLabel: String = "Pickup", destinationLabel: String = "Destination", modifier: Modifier = Modifier, tileDownloadsEnabled: Boolean = true, providedApi: MobilityApi? = null,
    nearbyCars: List<TaxiNearbyCar> = emptyList(), routeGeometry: MobilityRoadGeometry? = null, freezeVehicle: Boolean = false, routeGeometryQuality: String? = null) {
    val nearby = nearbyCars.map { it.latitude to it.longitude }
    if (pickup != null && destination != null) {
        MobilityRoadMap(pickup, destination, driver, stage, pickupLabel, destinationLabel,
            modifier, mapHeight = height, trackingActive = driver != null, tileDownloadsEnabled = tileDownloadsEnabled, driverIcon = R.drawable.taxi_car_top, providedApi = providedApi,
            nearbyPoints = nearby, statusMessage = if (nearby.isNotEmpty()) "Nearby cars · approximate" else null,
            providedRoute = routeGeometry, freezeVehicle = freezeVehicle, providedRouteQuality = routeGeometryQuality)
    } else {
        val context = LocalContext.current; val owner = LocalLifecycleOwner.current
        val map = remember(context) { Configuration.getInstance().userAgentValue = context.packageName
            MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); setUseDataConnection(tileDownloadsEnabled); setMultiTouchControls(true); setBuiltInZoomControls(false); controller.setZoom(13.0); controller.setCenter(GeoPoint(pickup?.first ?: 9.0108, pickup?.second ?: 38.7613)) } }
        var centered by remember(map) { mutableStateOf(false) }
        DisposableEffect(map, owner) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) map.onResume(); if (event == Lifecycle.Event.ON_STOP) map.onPause() }
            owner.lifecycle.addObserver(observer); map.onResume()
            onDispose { owner.lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
        }
        Box(modifier.fillMaxWidth().height(height.dp).clipToBounds()) {
            AndroidView(factory = { (map.parent as? ViewGroup)?.removeView(map); map }, Modifier.fillMaxSize(), update = {
                replaceRoadOverlays(map, emptyList(), emptyList(), pickup, destination, driver, driver != null && !driver.stale,
                    false, pickupLabel, destinationLabel, R.drawable.taxi_car_top, nearby)
                if (!centered && pickup != null) { map.controller.setCenter(GeoPoint(pickup.first, pickup.second)); centered = true }
                map.invalidate()
            })
            if (nearby.isNotEmpty()) Text("Nearby cars · approximate", Modifier.align(Alignment.TopStart).background(Color.White.copy(alpha = .93f)).padding(7.dp), color = CommuteInk, fontSize = 11.sp)
            Text("© OpenStreetMap contributors", Modifier.align(Alignment.BottomStart).background(Color.White.copy(alpha = .9f)).padding(4.dp), color = CommuteInk, fontSize = 10.sp)
        }
    }
}

@Composable internal fun TaxiConnectionPulse(animated: Boolean = true) {
    val phase = if (animated) {
        val transition = rememberInfiniteTransition(label = "Nearby driver connection")
        val value by transition.animateFloat(0.8f, 1.3f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "Pickup pulse")
        value
    } else 1f
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(55.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.size(40.dp).graphicsLayer { scaleX = phase; scaleY = phase; alpha = (1.4f - phase).coerceIn(.1f, .6f) }, shape = CircleShape, color = CommuteTeal) {}
            Icon(Icons.Default.LocationOn, null, tint = CommuteTeal, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f)) { Text("Connecting to nearby drivers…", fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = 17.sp)
            Text("Eligible drivers can select your request.", color = Color(0xFF627385), fontSize = 12.sp) }
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

