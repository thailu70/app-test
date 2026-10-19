package com.example.ui.screens.driver

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.core.localization.AppLanguage
import com.example.data.api.DriverGpsFix
import com.example.data.entity.RouteStopEntity
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Displays saved stop coordinates and a fresh phone fix, without implying server upload. */
@Composable
fun DriverRouteMap(
    stops: List<RouteStopEntity>,
    fix: DriverGpsFix?,
    modifier: Modifier = Modifier,
    language: AppLanguage = AppLanguage.ENGLISH
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(fix) {
        now = System.currentTimeMillis()
        while (fix != null) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }
    val locatedStops = stops.filter { routeCoordinateIsValid(it.latitude, it.longitude) }
        .sortedBy { it.stopOrder }
    val freshFix = fix?.takeIf {
        routeCoordinateIsValid(it.latitude, it.longitude) &&
            it.accuracyMeters.isFinite() && it.accuracyMeters >= 0 &&
            it.timestamp > 0 && it.timestamp in (now - 120_000)..(now + 5_000)
    }
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 155.dp, max = 240.dp),
        shape = shape,
        color = Color(0xFFF0F6F2),
        border = BorderStroke(1.dp, Color(0xFFE0E9E3))
    ) {
        if (locatedStops.isEmpty() && freshFix == null) {
            Column(
                Modifier.fillMaxSize().padding(10.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Outlined.LocationOn,
                    contentDescription = null,
                    tint = Color(0xFF00685C),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    driverText(language, "Awaiting location", "ቦታ በመጠባበቅ ላይ", "Bakka eeggachaa"),
                    modifier = Modifier.padding(top = 5.dp, bottom = 4.dp),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF092C3E)
                )
                Text(
                    driverText(language,
                        "Route stops or a fresh phone location will appear here.",
                        "የመስመር ማቆሚያዎች ወይም የስልክዎ አዲስ ቦታ እዚህ ይታያሉ።",
                        "Buufatawwan karaa yookaan bakki bilbilaa haaraan asitti mul'ata."),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = Color(0xFF586A72)
                )
            }
        } else {
            Box(Modifier.fillMaxSize().clip(shape)) {
                DriverStopsMap(locatedStops, freshFix, language, Modifier.fillMaxSize())
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = Color.White.copy(alpha = 0.95f)
                ) {
                    Text(
                        if (freshFix != null) driverText(language, "Phone location", "የስልክ ቦታ", "Bakka bilbilaa")
                        else driverText(language, "Route stops", "የመስመር ማቆሚያዎች", "Buufatawwan karaa"),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF00685C)
                    )
                }
                val uriHandler = LocalUriHandler.current
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).clickable {
                        uriHandler.openUri("https://www.openstreetmap.org/copyright")
                    },
                    color = Color.White.copy(alpha = 0.93f)
                ) {
                    Text(
                        "© OpenStreetMap contributors",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        color = Color(0xFF092C3E),
                        fontSize = 9.sp,
                        lineHeight = 11.sp
                    )
                }
            }
        }
    }
}

private fun routeCoordinateIsValid(latitude: Double, longitude: Double): Boolean =
    latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        !(latitude == 0.0 && longitude == 0.0)

@Composable
private fun DriverStopsMap(stops: List<RouteStopEntity>, fix: DriverGpsFix?, language: AppLanguage, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context) { createDriverMap(context) }
    val overlays = remember(mapView) { DriverMapOverlays() }
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }
    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.contentDescription = driverText(language,
                "Assigned route stops and phone location map",
                "የተመደቡ ማቆሚያዎች እና የስልክ ቦታ ካርታ",
                "Kaartaa buufatawwan ramadamanii fi bakka bilbilaa")
            val signature = stops.map { listOf(it.id, it.stopName, it.stopNameAm, it.latitude, it.longitude) } +
                listOf(listOf(fix?.latitude, fix?.longitude, language))
            if (overlays.signature != signature) {
                overlays.signature = signature
                map.overlays.clear()
                val stopPoints = stops.map { GeoPoint(it.latitude, it.longitude) }
                if (stopPoints.size > 1) {
                    // This connects recorded stops; it is not a turn-by-turn road route.
                    map.overlays.add(Polyline().apply {
                        setPoints(stopPoints)
                        outlinePaint.color = AndroidColor.rgb(0, 137, 113)
                        outlinePaint.strokeWidth = 7f
                        title = driverText(language, "Route stops", "የመስመር ማቆሚያዎች", "Buufatawwan karaa")
                    })
                }
                stops.forEach { stop ->
                    map.overlays.add(Marker(map).apply {
                        position = GeoPoint(stop.latitude, stop.longitude)
                        title = if (language == AppLanguage.AMHARIC) stop.stopNameAm.ifBlank { stop.stopName } else stop.stopName
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = mapDot(context, AndroidColor.rgb(0, 119, 98), 17)
                    })
                }
                val phonePoint = fix?.let { GeoPoint(it.latitude, it.longitude) }
                if (phonePoint != null) {
                    map.overlays.add(Marker(map).apply {
                        position = phonePoint
                        title = driverText(language, "Phone location", "የስልክ ቦታ", "Bakka bilbilaa")
                        snippet = driverText(language, "This device’s GPS position", "የዚህ መሣሪያ የGPS ቦታ", "Bakka GPS meeshaa kanaa")
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = mapDot(context, AndroidColor.rgb(26, 119, 230), 21)
                    })
                }
                val visiblePoints = (stopPoints + listOfNotNull(phonePoint))
                    .distinctBy { it.latitude to it.longitude }
                map.post {
                    if (visiblePoints.size > 1) {
                        map.zoomToBoundingBox(BoundingBox.fromGeoPoints(visiblePoints), false, 42)
                    } else {
                        visiblePoints.firstOrNull()?.let { map.controller.setCenter(it) }
                        map.controller.setZoom(15.0)
                    }
                    map.invalidate()
                }
            }
        }
    )
}

private class DriverMapOverlays {
    var signature: List<List<Any?>>? = null
}

private fun createDriverMap(context: Context): MapView {
    Configuration.getInstance().userAgentValue = context.packageName
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        setBuiltInZoomControls(false)
        clipChildren = true
        clipToPadding = true
        clipToOutline = true
        contentDescription = "Assigned route stops and phone location map"
    }
}

private fun mapDot(context: Context, color: Int, diameterDp: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        val density = context.resources.displayMetrics.density
        setSize((diameterDp * density).toInt(), (diameterDp * density).toInt())
        setStroke((3 * density).toInt(), AndroidColor.WHITE)
    }
