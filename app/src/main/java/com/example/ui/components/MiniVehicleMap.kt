package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
 * Small OpenStreetMap panel for actual server-reported vehicle positions.
 * It deliberately does not draw the seeded coordinates as if they were live.
 */
@Composable
fun MiniVehicleMap(
    vehicle: TrackedVehicleDto?,
    modifier: Modifier = Modifier,
    mapHeight: Int = 210,
    serverMessage: String? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context) { createMapView(context) }
    val markerHolder = remember(mapView) { arrayOfNulls<Marker>(1) }

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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Live vehicle map", style = MaterialTheme.typography.titleSmall)
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
                            view.overlays.add(it)
                            markerHolder[0] = it
                        }
                        marker.position = point
                        marker.title = listOf(vehicle.plateNumber, vehicle.driverName.orEmpty())
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        marker.snippet = vehicle.currentStop.orEmpty()
                        view.controller.setZoom(15.0)
                        view.controller.animateTo(point)
                    } else if (markerHolder[0] == null) {
                        // Addis Ababa is a map starting view only—not a claimed vehicle position.
                        view.controller.setZoom(12.5)
                        view.controller.setCenter(GeoPoint(9.03, 38.74))
                    }
                    view.invalidate()
                }
            )
            val status = when {
                !serverMessage.isNullOrBlank() -> serverMessage
                vehicle == null -> "No eligible vehicle is available. The administrator must approve the driver, assign a route, assign the vehicle to this passenger subscription, and activate the subscription."
                vehicle.hasGpsLocation && !vehicle.lastGpsAt.isNullOrBlank() ->
                    "RoutePass server received the vehicle GPS at " + vehicle.lastGpsAt
                else -> "Vehicle is assigned, but the server has not received a GPS report yet. Keep the driver's screen open and confirm GPS upload succeeds."
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            Text("Map data © OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
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
