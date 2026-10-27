package com.example.ui.screens.intercity

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.screens.passenger.CommuteTeal
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.util.Locale

/** Native, movable map pin. A default viewport is never treated as a selected customer location. */
@Composable
internal fun MobilityLocationPicker(title: String, initial: Pair<Double, Double>?, onDismiss: () -> Unit,
    onChoose: (Pair<Double, Double>) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(initial) }
    var latitude by remember { mutableStateOf(initial?.first?.toString().orEmpty()) }
    var longitude by remember { mutableStateOf(initial?.second?.toString().orEmpty()) }
    val map = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); setBuiltInZoomControls(false)
            controller.setZoom(12.0)
            controller.setCenter(initial?.let { GeoPoint(it.first, it.second) } ?: GeoPoint(9.03, 38.75))
        }
    }
    val marker = remember(map) { Marker(map).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); map.overlays.add(this) } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(map, owner) {
        val overlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                selected = p.latitude to p.longitude
                latitude = String.format(Locale.US, "%.6f", p.latitude); longitude = String.format(Locale.US, "%.6f", p.longitude)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = singleTapConfirmedHelper(p)
        })
        map.overlays.add(0, overlay)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) map.onResume()
            if (event == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        owner.lifecycle.addObserver(observer); map.onResume()
        onDispose { owner.lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Tap the map to place a pin. You can also enter coordinates.", style = MaterialTheme.typography.bodySmall)
            AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().height(230.dp), update = {
                marker.isEnabled = selected != null
                selected?.let { marker.position = GeoPoint(it.first, it.second) }; map.invalidate()
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(latitude, { latitude = it; selected = validMobilityPoint(latitude, longitude) }, label = { Text("Latitude") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(longitude, { longitude = it; selected = validMobilityPoint(latitude, longitude) }, label = { Text("Longitude") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
        }
    }, confirmButton = { TextButton(enabled = selected != null, onClick = { selected?.let(onChoose) }) { Text("Use this location", color = CommuteTeal) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

internal fun validMobilityPoint(latitude: String, longitude: String): Pair<Double, Double>? {
    val lat = latitude.toDoubleOrNull() ?: return null; val lon = longitude.toDoubleOrNull() ?: return null
    return if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
}
