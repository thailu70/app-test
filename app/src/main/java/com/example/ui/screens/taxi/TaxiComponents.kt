package com.example.ui.screens.taxi

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
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

internal data class TaxiPendingIntent(val scope: com.example.core.environment.ApiSessionScope,
    val route: TaxiRouteInput? = null, val sharedId: String? = null, val sharedStart: Boolean = false)
/** Process-only sign-in return context; no location draft or bearer is written to disk. */
internal object TaxiPendingNavigation {
    private var pending: TaxiPendingIntent? = null
    fun route(value: TaxiRouteInput) { pending = TaxiPendingIntent(ApiClient.sessionScope(), route = value) }
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
    onDailyCommute: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(CommuteCream).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, Modifier.testTag("taxi_back")) { Icon(Icons.Default.ArrowBack, "Back", tint = CommuteInk) }
        if (title == null) Image(painterResource(R.drawable.auth_passo_wordmark), "PASSO", Modifier.width(134.dp).height(45.dp), contentScale = ContentScale.Fit)
        else Text(title, color = CommuteInk, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, modifier = Modifier.weight(1f), maxLines = 2)
        if (title == null) Spacer(Modifier.weight(1f))
        if (onDailyCommute != null) OutlinedButton(onClick = onDailyCommute, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), border = BorderStroke(1.dp, CommuteTeal)) {
            Icon(Icons.Default.DirectionsBus, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Daily Commute", fontSize = 12.sp, maxLines = 1)
        }
        trailing()
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

@Composable internal fun TaxiTierTiles(tiers: List<TaxiTier>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    val fallback = listOf(TaxiTier("ECONOMY", "Economy"), TaxiTier("COMFORT", "Comfort"), TaxiTier("MINI_VAN", "Mini-Van"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        (tiers.ifEmpty { fallback }).forEach { tier ->
            val active = tier.id == selected
            val surface by animateColorAsState(if (active) CommuteMint else Color.White, tween(180), label = "Vehicle tier surface")
            val border by animateColorAsState(if (active) CommuteTeal else Color(0xFFD9DFDE), tween(180), label = "Vehicle tier border")
            val scale by animateFloatAsState(if (active) 1.04f else 1f, tween(180), label = "Vehicle selection")
            Surface(onClick = { onSelect(tier.id) }, enabled = enabled, modifier = Modifier.weight(1f).testTag("taxi_tier_${tier.id}"),
                color = surface, border = BorderStroke(if (active) 2.dp else 1.dp, border), shape = RoundedCornerShape(17.dp)) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(when (tier.id) { "COMFORT" -> R.drawable.taxi_comfort; "MINI_VAN" -> R.drawable.taxi_minivan; else -> R.drawable.taxi_economy }), null,
                        Modifier.fillMaxWidth().height(60.dp).graphicsLayer { scaleX = scale; scaleY = scale }, contentScale = ContentScale.Fit)
                    Text(tier.name, color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

/** A real native base map, not the mockup image. No unassigned driver positions are invented. */
@Composable internal fun TaxiMap(pickup: Pair<Double, Double>?, destination: Pair<Double, Double>?,
    driver: MobilityLocation? = null, stage: String = "ASSIGNED", height: Int = 255,
    pickupLabel: String = "Pickup", destinationLabel: String = "Destination", modifier: Modifier = Modifier, tileDownloadsEnabled: Boolean = true, providedApi: MobilityApi? = null,
    nearbyCars: List<TaxiNearbyCar> = emptyList()) {
    val nearby = nearbyCars.map { it.latitude to it.longitude }
    if (pickup != null && destination != null) {
        MobilityRoadMap(pickup, destination, driver, stage, pickupLabel, destinationLabel,
            modifier, mapHeight = height, trackingActive = driver != null, tileDownloadsEnabled = tileDownloadsEnabled, driverIcon = R.drawable.taxi_car_top, providedApi = providedApi,
            nearbyPoints = nearby, statusMessage = if (nearby.isNotEmpty()) "Nearby cars · approximate" else null)
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
                replaceRoadOverlays(map, emptyList(), emptyList(), null, null, null, false, false, pickupLabel, destinationLabel, R.drawable.taxi_car_top, nearby)
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
