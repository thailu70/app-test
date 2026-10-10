package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.api.TrackedVehicleDto
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker

/**
 * Polished live-vehicle map. It only shows server-reported coordinates.
 * Avoids repeatedly animating the camera for unchanged GPS fixes.
 */
@Composable
fun MiniVehicleMap(
    vehicle: TrackedVehicleDto?,
    modifier: Modifier = Modifier,
    mapHeight: Int = 250,
    serverMessage: String? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context) { createMapView(context) }
    val markerHolder = remember(mapView) { arrayOfNulls<Marker>(1) }
    val lastCameraPoint = remember(mapView) { arrayOfNulls<GeoPoint>(1) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1B2A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF163A4A)) {
                        Icon(
                            Icons.Default.DirectionsBus, contentDescription = null,
                            tint = Color(0xFF55E6D0), modifier = Modifier.padding(10.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Your ride, live", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            vehicle?.plateNumber?.takeIf { it.isNotBlank() } ?: "Vehicle tracking",
                            color = Color(0xFF9EB5C8), style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Surface(shape = CircleShape, color = if (vehicle?.hasGpsLocation == true) Color(0xFF123F39) else Color(0xFF354454)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            if (vehicle?.hasGpsLocation == true) Icons.Default.GpsFixed else Icons.Default.LocationSearching,
                            contentDescription = null,
                            tint = if (vehicle?.hasGpsLocation == true) Color(0xFF55E6D0) else Color(0xFFB7C7D5)
                        )
                        Text(
                            if (vehicle?.hasGpsLocation == true) "LIVE" else "WAITING",
                            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().height(mapHeight.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFFE5EEF2))
            ) {
                AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxWidth().height(mapHeight.dp),
                    update = { view ->
                        val lat = vehicle?.latitude
                        val lon = vehicle?.longitude
                        if (vehicle?.hasGpsLocation == true && lat != null && lon != null &&
                            lat in -90.0..90.0 && lon in -180.0..180.0
                        ) {
                            val point = GeoPoint(lat, lon)
                            val marker = markerHolder[0] ?: Marker(view).also {
                                it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                it.icon = createVehicleMarker(context)
                                view.overlays.add(it)
                                markerHolder[0] = it
                            }
                            marker.position = point
                            marker.title = listOf(vehicle.plateNumber, vehicle.driverName.orEmpty())
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            marker.snippet = vehicle.currentStop.orEmpty()

                            val previous = lastCameraPoint[0]
                            if (previous == null) {
                                view.controller.setZoom(16.0)
                                view.controller.setCenter(point)
                                lastCameraPoint[0] = point
                            } else {
                                val movedEnough = kotlin.math.abs(previous.latitude - lat) > 0.00003 ||
                                    kotlin.math.abs(previous.longitude - lon) > 0.00003
                                if (movedEnough) {
                                    view.controller.animateTo(point)
                                    lastCameraPoint[0] = point
                                }
                            }
                        } else if (markerHolder[0] == null && lastCameraPoint[0] == null) {
                            // Starting viewport only. Never presented as the vehicle's actual location.
                            view.controller.setZoom(12.5)
                            view.controller.setCenter(GeoPoint(9.03, 38.74))
                        }
                        view.invalidate()
                    }
                )
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xE6102233)
                ) {
                    Text(
                        if (vehicle?.hasGpsLocation == true) "Vehicle position from GPS" else "Waiting for first GPS signal",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        color = Color.White, style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            val status = when {
                !serverMessage.isNullOrBlank() -> serverMessage
                vehicle == null -> "No assigned vehicle yet. Your administrator must approve a driver, assign a route and activate your subscription."
                vehicle.hasGpsLocation && !vehicle.lastGpsAt.isNullOrBlank() -> "Last GPS update: " + vehicle.lastGpsAt
                else -> "Vehicle assigned. Waiting for the driver's phone to send its first GPS update."
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF172C3E)
            ) {
                Text(
                    status,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = Color(0xFFD7E5EF), style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Map tiles © OpenStreetMap contributors",
                modifier = Modifier.align(Alignment.End),
                color = Color(0xFF91A9BC), style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun createMapView(context: Context): MapView {
    Configuration.getInstance().userAgentValue = context.packageName
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        controller.setZoom(12.5)
        controller.setCenter(GeoPoint(9.03, 38.74))
        overlays.add(CopyrightOverlay(context))
    }
}

private fun createVehicleMarker(context: Context): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val size = (54 * density).toInt().coerceAtLeast(54)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = size / 2f
    paint.color = android.graphics.Color.argb(50, 0, 0, 0)
    canvas.drawCircle(center, center * 0.82f, paint)
    paint.color = android.graphics.Color.rgb(8, 127, 140)
    canvas.drawCircle(center, center * 0.72f, paint)
    paint.color = android.graphics.Color.WHITE
    val bus = RectF(center - size * 0.22f, center - size * 0.25f, center + size * 0.22f, center + size * 0.18f)
    canvas.drawRoundRect(bus, size * 0.06f, size * 0.06f, paint)
    paint.color = android.graphics.Color.rgb(8, 127, 140)
    canvas.drawRect(bus.left + size * 0.05f, bus.top + size * 0.06f, bus.right - size * 0.05f, bus.top + size * 0.19f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center - size * 0.12f, center + size * 0.24f, size * 0.045f, paint)
    canvas.drawCircle(center + size * 0.12f, center + size * 0.24f, size * 0.045f, paint)
    return BitmapDrawable(context.resources, bitmap)
}
