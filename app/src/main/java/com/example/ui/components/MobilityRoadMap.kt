package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.Instant
import kotlin.math.*

internal fun validRoadPoint(point: Pair<Double, Double>?): Boolean = point != null &&
    point.first.isFinite() && point.second.isFinite() && point.first in -90.0..90.0 && point.second in -180.0..180.0

/** GeoJSON is longitude/latitude; application points are latitude/longitude. */
internal fun roadPoints(geometry: MobilityRoadGeometry?): List<Pair<Double, Double>> {
    if (geometry?.type != "LineString" || geometry.coordinates.size !in 2..20_000) return emptyList()
    val points = geometry.coordinates.map { coordinate ->
        if (coordinate.size < 2) return emptyList()
        (coordinate[1] to coordinate[0]).takeIf(::validRoadPoint) ?: return emptyList()
    }
    return points.takeIf { it.distinct().size >= 2 }.orEmpty()
}

internal fun roadLocationFresh(location: MobilityLocation?, now: Long): Boolean {
    if (location == null || location.stale || !validRoadPoint(location.latitude to location.longitude)) return false
    val time = runCatching { Instant.parse(location.timestamp).toEpochMilli() }.getOrNull() ?: return false
    return time in (now - 120_000)..(now + 5_000)
}

internal fun roadToDestination(stage: String) = stage in setOf("LOADED", "IN_TRANSIT", "IN_PROGRESS", "AT_DESTINATION")
internal fun roadTrackingStage(stage: String) = stage in setOf("ASSIGNED", "AT_PICKUP", "LOADED", "IN_TRANSIT", "IN_PROGRESS", "AT_DESTINATION")
internal fun roadDistanceMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val lat = Math.toRadians(b.first - a.first); val lon = Math.toRadians(b.second - a.second)
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(lon / 2).pow(2)
    return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
internal fun shouldRerouteRoad(previous: Pair<Double, Double>?, current: Pair<Double, Double>, lastAttempt: Long, now: Long): Boolean =
    previous == null || (now - lastAttempt >= 30_000 && roadDistanceMeters(previous, current) >= 100)

/** Road geometry always comes from the routing service. Missing roads never become straight lines. */
@Composable
fun MobilityRoadMap(pickup: Pair<Double, Double>?, destination: Pair<Double, Double>?,
    driverLocation: MobilityLocation? = null, stage: String = "ASSIGNED",
    pickupLabel: String = "Pickup / loading", destinationLabel: String = "Destination",
    modifier: Modifier = Modifier, mapHeight: Int = 260, providedApi: MobilityApi? = null,
    tileDownloadsEnabled: Boolean = true, trackingActive: Boolean = true, statusMessage: String? = null) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val api = remember(providedApi) { providedApi ?: ApiClient.createFeatureService(MobilityApi::class.java) }
    val latestLocation by rememberUpdatedState(driverLocation)
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val from = pickup?.takeIf(::validRoadPoint); val to = destination?.takeIf(::validRoadPoint)
    var planned by remember(from, to) { mutableStateOf<MobilityRoadResponse?>(null) }
    var moving by remember(from, to, stage, trackingActive) { mutableStateOf<MobilityRoadResponse?>(null) }
    var plannedError by remember(from, to) { mutableStateOf(false) }
    var movingError by remember(from, to, stage) { mutableStateOf(false) }
    val map = remember(context) {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            tag = "mobility_road_native_map"
            setTileSource(TileSourceFactory.MAPNIK); setUseDataConnection(tileDownloadsEnabled)
            setMultiTouchControls(true); setBuiltInZoomControls(false)
            clipChildren = true; clipToPadding = true
            controller.setZoom(13.0)
        }
    }
    DisposableEffect(map, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { resumed = true; map.onResume() }
            if (event == Lifecycle.Event.ON_STOP) { resumed = false; map.onPause() }
        }
        owner.lifecycle.addObserver(observer)
        if (resumed) map.onResume()
        onDispose { owner.lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
    }
    suspend fun route(a: Pair<Double, Double>, b: Pair<Double, Double>): MobilityRoadResponse {
        val response = api.road(a.first, a.second, b.first, b.second)
        val value = response.body()
        check(response.isSuccessful && value?.success == true && roadPoints(value.geometry).isNotEmpty())
        return value!!
    }
    LaunchedEffect(from, to, api, resumed) {
        if (resumed && from != null && to != null && planned == null) {
            try { planned = route(from, to); plannedError = false }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { plannedError = true }
        }
    }
    LaunchedEffect(from, to, stage, trackingActive, resumed, api) {
        if (!resumed || !trackingActive || !roadTrackingStage(stage)) return@LaunchedEffect
        val target = if (roadToDestination(stage)) to else from
        var previous: Pair<Double, Double>? = null
        var lastAttempt = 0L
        while (true) {
            now = System.currentTimeMillis()
            val location = latestLocation
            if (target != null && roadLocationFresh(location, now)) {
                val point = location!!.latitude to location.longitude
                if (shouldRerouteRoad(previous, point, lastAttempt, now) && now - lastAttempt >= 30_000) {
                    lastAttempt = now
                    try { moving = route(point, target); previous = point; movingError = false }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { movingError = true }
                }
            }
            delay(5_000)
        }
    }
    LaunchedEffect(driverLocation) { now = System.currentTimeMillis() }
    val fresh = trackingActive && roadTrackingStage(stage) && roadLocationFresh(driverLocation, now)
    val plannedPoints = roadPoints(planned?.geometry)
    val movingPoints = if (fresh) roadPoints(moving?.geometry) else emptyList()
    var framed by remember(map, from, to) { mutableStateOf(false) }
    var fittedMoving by remember(map, from, to) { mutableStateOf(false) }
    fun fitMap() {
        val points = (plannedPoints + movingPoints + listOfNotNull(from, to,
            driverLocation?.let { (it.latitude to it.longitude).takeIf(::validRoadPoint) })).map { GeoPoint(it.first, it.second) }
        if (points.size > 1 && points.distinct().size > 1)
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, (52 * context.resources.displayMetrics.density).toInt())
        else points.firstOrNull()?.let { map.controller.setCenter(it); map.controller.setZoom(15.0) }
    }
    Column(modifier.fillMaxWidth().testTag("mobility_road_map"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFE7EEE9)) {
            Box(Modifier.fillMaxWidth().height(mapHeight.coerceIn(140, 420).dp).clipToBounds()) {
                AndroidView(factory = { ctx -> FrameLayout(ctx).apply {
                    clipChildren = true; clipToPadding = true
                    (map.parent as? ViewGroup)?.removeView(map)
                    addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                } }, modifier = Modifier.matchParentSize().clipToBounds(), update = {
                    replaceRoadOverlays(map, plannedPoints, movingPoints, from, to, driverLocation, fresh,
                        roadToDestination(stage), pickupLabel, destinationLabel)
                    if (!framed || (!fittedMoving && movingPoints.isNotEmpty())) map.post {
                        fitMap(); framed = true; fittedMoving = movingPoints.isNotEmpty(); map.invalidate()
                    }
                    map.invalidate()
                })
                Surface(color = Color.White.copy(alpha = 0.95f), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.TopStart).padding(9.dp)) {
                    Text(if (fresh) "Live driver" else if (driverLocation != null) "Last known location" else "Journey route",
                        Modifier.padding(horizontal = 9.dp, vertical = 6.dp), fontSize = 11.sp, color = Color(0xFF00685C))
                }
                FilledIconButton(onClick = { fitMap(); map.invalidate() }, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(36.dp).testTag("road_map_recenter"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color(0xFF00685C))) {
                    Icon(Icons.Default.MyLocation, "Show whole journey", Modifier.size(20.dp))
                }
                Text("© OpenStreetMap contributors", Modifier.align(Alignment.BottomStart).background(Color.White.copy(alpha = 0.9f)).padding(4.dp), fontSize = 9.sp, color = Color(0xFF092C3E))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!roadToDestination(stage) && movingPoints.isNotEmpty()) Text("┄ To pickup", fontSize = 11.sp, color = Color(0xFF248B9A))
            if (plannedPoints.isNotEmpty() || movingPoints.isNotEmpty()) Text("━ To destination", fontSize = 11.sp, color = Color(0xFF00685C))
        }
        val estimate = if (fresh && movingPoints.isNotEmpty()) moving else null
        if (estimate?.distanceKm != null && estimate.durationMinutes != null) Text(
            "${"%.1f".format(java.util.Locale.US, estimate.distanceKm)} km · about ${estimate.durationMinutes.toInt()} min ${if (roadToDestination(stage)) "to destination" else "to pickup"}",
            fontSize = 12.sp, color = Color(0xFF00685C), modifier = Modifier.testTag("road_map_estimate"))
        if (plannedError || movingError) Text("Road route unavailable. Your selected locations are still shown.", fontSize = 11.sp, color = Color(0xFF617285), modifier = Modifier.testTag("road_map_unavailable"))
        else if (from == null || to == null) Text("Waiting for the assigned pickup and destination locations.", fontSize = 11.sp, color = Color(0xFF617285))
        statusMessage?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, color = Color(0xFF617285)) }
    }
}

internal fun replaceRoadOverlays(map: MapView, planned: List<Pair<Double, Double>>, moving: List<Pair<Double, Double>>,
    pickup: Pair<Double, Double>?, destination: Pair<Double, Double>?, driver: MobilityLocation?, fresh: Boolean,
    inTransit: Boolean, pickupLabel: String, destinationLabel: String) {
    map.overlays.clear()
    fun line(points: List<Pair<Double, Double>>, color: Int, dashed: Boolean) {
        if (points.size < 2) return
        map.overlays.add(Polyline(map).apply { setPoints(points.map { GeoPoint(it.first, it.second) }); outlinePaint.color = color; outlinePaint.strokeWidth = 8f
            if (dashed) outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f) })
    }
    line(planned, if (inTransit) 0xFF9BC8BC.toInt() else 0xFF00685C.toInt(), false)
    line(moving, if (inTransit) 0xFF00685C.toInt() else 0xFF248B9A.toInt(), !inTransit)
    fun marker(point: Pair<Double, Double>?, title: String, letter: String, color: Int) {
        if (!validRoadPoint(point)) return
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(point!!.first, point.second); this.title = title; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            val size = (36 * map.resources.displayMetrics.density).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = android.graphics.Color.WHITE; canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.color = color; canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3, paint)
            paint.color = android.graphics.Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = size * 0.44f; paint.isFakeBoldText = true
            canvas.drawText(letter, size / 2f, size / 2f - (paint.ascent() + paint.descent()) / 2, paint)
            icon = BitmapDrawable(map.resources, bitmap)
        })
    }
    marker(pickup, pickupLabel, "P", 0xFF00685C.toInt())
    marker(destination, destinationLabel, "F", 0xFF092C3E.toInt())
    driver?.let { marker(it.latitude to it.longitude, if (fresh) "Driver · live" else "Driver · last known", "D", if (fresh) 0xFF1977E6.toInt() else 0xFF71808A.toInt()) }
    map.contentDescription = "Road journey map. $pickupLabel to $destinationLabel. ${if (fresh) "Live driver" else "No fresh driver location"}"
}
