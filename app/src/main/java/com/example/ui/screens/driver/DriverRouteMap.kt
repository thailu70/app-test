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
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** The driver's current device location only. Assigned endpoints remain in the trip text. */
@Composable
fun DriverRouteMap(
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
    val freshFix = driverMapFreshFix(fix, now)
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 155.dp, max = 240.dp).testTag("driver_current_location_map"),
        shape = shape,
        color = Color(0xFFF0F6F2),
        border = BorderStroke(1.dp, Color(0xFFE0E9E3))
    ) {
        if (freshFix == null) {
            Column(
                Modifier.fillMaxSize().padding(10.dp).testTag("driver_location_unavailable"),
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
                        "Your current GPS location will appear here.",
                        "የእርስዎ ወቅታዊ የGPS ቦታ እዚህ ይታያል።",
                        "Bakki GPS kee yeroo ammaa asitti mul'ata."),
                    textAlign = TextAlign.Center,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = Color(0xFF586A72)
                )
            }
        } else {
            Box(Modifier.fillMaxSize().clip(shape)) {
                DriverCurrentLocationMap(freshFix, language, Modifier.fillMaxSize())
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = Color.White.copy(alpha = 0.95f)
                ) {
                    Text(
                        driverText(language, "Your location", "የእርስዎ ቦታ", "Bakka kee"),
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

internal fun driverMapFreshFix(fix: DriverGpsFix?, now: Long): DriverGpsFix? = fix?.takeIf {
    it.latitude.isFinite() && it.longitude.isFinite() &&
        it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
        !(it.latitude == 0.0 && it.longitude == 0.0) &&
        it.accuracyMeters.isFinite() && it.accuracyMeters >= 0 &&
        it.timestamp > 0 && it.timestamp in (now - 120_000)..(now + 5_000)
}

@Composable
private fun DriverCurrentLocationMap(fix: DriverGpsFix, language: AppLanguage, modifier: Modifier) {
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
            val signature = listOf(fix.latitude, fix.longitude, language)
            if (overlays.signature != signature) {
                overlays.signature = signature
                val phonePoint = replaceDriverLocationOverlay(map, fix, language, context)
                map.post {
                    map.controller.setCenter(phonePoint)
                    map.controller.setZoom(15.0)
                    map.invalidate()
                }
            }
        }
    )
}

private class DriverMapOverlays {
    var signature: List<Any?>? = null
}

/** Replace previous overlays, leaving one current-location marker and no route line. */
internal fun replaceDriverLocationOverlay(map: MapView, fix: DriverGpsFix, language: AppLanguage, context: Context): GeoPoint {
    map.contentDescription = driverText(language, "Driver current location map", "የሹፌሩ ወቅታዊ ቦታ ካርታ", "Kaartaa bakka konkolaachisaa yeroo ammaa")
    map.overlays.clear()
    val point = GeoPoint(fix.latitude, fix.longitude)
    map.overlays.add(Marker(map).apply {
        position = point
        title = driverText(language, "Your location", "የእርስዎ ቦታ", "Bakka kee")
        snippet = driverText(language, "This device’s GPS position", "የዚህ መሣሪያ የGPS ቦታ", "Bakka GPS meeshaa kanaa")
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = mapDot(context, AndroidColor.rgb(26, 119, 230), 21)
    })
    return point
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
        contentDescription = "Driver current location map"
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
