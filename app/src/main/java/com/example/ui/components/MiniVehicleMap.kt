package com.example.ui.components

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.api.TrackedVehicleDto
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate900
import com.example.ui.theme.TransportGreenPrimary
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** A map is shown only for a real, server-reported position. */
@Composable
fun MiniVehicleMap(
    vehicle: TrackedVehicleDto?,
    modifier: Modifier = Modifier,
    mapHeight: Int = 210,
    serverMessage: String? = null
) {
    val lat = vehicle?.latitude
    val lon = vehicle?.longitude
    val hasPosition = vehicle?.hasGpsLocation == true && lat != null && lon != null &&
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    val positionReceivedAt = remember(vehicle?.id, vehicle?.lastGpsAt, vehicle?.gpsAgeSeconds) { SystemClock.elapsedRealtime() }
    var elapsedSeconds by remember(vehicle?.id, vehicle?.lastGpsAt, vehicle?.gpsAgeSeconds) { mutableLongStateOf(0L) }
    LaunchedEffect(vehicle?.id, vehicle?.lastGpsAt, vehicle?.gpsAgeSeconds, hasPosition) {
        while (hasPosition) {
            delay(30_000)
            elapsedSeconds = (SystemClock.elapsedRealtime() - positionReceivedAt) / 1000
        }
    }
    val age = vehicle?.gpsAgeSeconds?.let { it.coerceAtLeast(0) + elapsedSeconds }
    val live = hasPosition && vehicle?.trackingStatus == "LIVE" && age != null && age <= 120
    val updateFailed = vehicle?.trackingStatus == "ERROR"

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE4E8E7))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Vehicle location", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Slate900)
                if (hasPosition) {
                    Surface(shape = RoundedCornerShape(8.dp), color = if (live) Color(0xFFE5F5EC) else Color(0xFFF1F3F4)) {
                        Text(if (live) "Live" else "Last known", modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = if (live) TransportGreenPrimary else Slate600)
                    }
                }
            }
            if (hasPosition && vehicle != null && lat != null && lon != null) {
                VehiclePositionMap(vehicle, lat, lon, mapHeight)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (updateFailed) {
                        Text("Unable to refresh the location. Showing the last update.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                    } else if (!live) {
                        Text(if (vehicle.trackingStatus == "OFFLINE") "Your driver is not sharing a live location right now." else "Waiting for a new location update.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                    }
                    age?.let { Text(locationUpdateLabel(it), style = MaterialTheme.typography.bodySmall, color = Slate600) }
                    vehicle.currentStop?.takeIf { it.isNotBlank() }?.let {
                        Text("Last reported stop: $it", style = MaterialTheme.typography.bodySmall, color = Slate600)
                    }
                }
                val uriHandler = LocalUriHandler.current
                Text(
                    "© OpenStreetMap contributors",
                    modifier = Modifier.clickable { uriHandler.openUri("https://www.openstreetmap.org/copyright") },
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate600
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF0F6F3)) {
                        Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = TransportGreenPrimary, modifier = Modifier.padding(12.dp).size(24.dp))
                    }
                    Text(
                        serverMessage?.takeIf { it.isNotBlank() }
                            ?: if (vehicle == null) "Your vehicle location will appear after assignment and pass activation."
                            else "Your driver’s location will appear when they start sharing it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate600,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun locationUpdateLabel(ageSeconds: Long): String = when {
    ageSeconds < 60 -> "Updated just now"
    ageSeconds < 120 -> "Updated 1 minute ago"
    ageSeconds < 3600 -> "Updated ${ageSeconds / 60} minutes ago"
    ageSeconds < 7200 -> "Updated 1 hour ago"
    ageSeconds < 86_400 -> "Updated ${ageSeconds / 3600} hours ago"
    ageSeconds < 172_800 -> "Updated 1 day ago"
    else -> "Updated ${ageSeconds / 86_400} days ago"
}

@Composable
private fun VehiclePositionMap(vehicle: TrackedVehicleDto, latitude: Double, longitude: Double, mapHeight: Int) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context, vehicle.id) { createMapView(context) }
    val marker = remember(mapView) {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            mapView.overlays.add(this)
        }
    }
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
    // Clip the Android view itself as well as its Compose bounds when the page scrolls.
    Box(Modifier.fillMaxWidth().height(mapHeight.coerceIn(160, 280).dp).clip(RoundedCornerShape(14.dp))) {
        key(mapView) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                update = { view ->
                    val point = GeoPoint(latitude, longitude)
                    marker.position = point
                    marker.title = vehicle.plateNumber
                    marker.snippet = vehicle.currentStop.orEmpty()
                    view.controller.setCenter(point)
                    view.invalidate()
                }
            )
        }
    }
}

private fun createMapView(context: Context): MapView {
    Configuration.getInstance().userAgentValue = context.packageName
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        setBuiltInZoomControls(false)
        clipChildren = true
        clipToPadding = true
        clipToOutline = true
        contentDescription = "Your vehicle location map"
        controller.setZoom(15.0)
    }
}
