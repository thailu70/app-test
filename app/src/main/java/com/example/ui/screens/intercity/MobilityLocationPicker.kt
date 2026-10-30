package com.example.ui.screens.intercity

import androidx.compose.foundation.layout.*
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.data.api.ApiClient
import com.example.data.api.MobilityApi
import com.example.data.api.MobilityPlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val api = remember { ApiClient.createFeatureService(MobilityApi::class.java) }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<MobilityPlace>>(emptyList()) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
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
    fun currentLocation() { scope.launch {
        searching = true
        try { val fix = freshMobilityLocation(context); selected = fix.latitude to fix.longitude; latitude = fix.latitude.toString(); longitude = fix.longitude.toString(); map.controller.setCenter(GeoPoint(fix.latitude, fix.longitude)); searchMessage = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { searchMessage = e.message }
        finally { searching = false }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasMobilityLocationPermission(context)) currentLocation() else searchMessage = "Allow location permission or search for your place."
    }
    LaunchedEffect(query) {
        suggestions = emptyList()
        if (query.trim().length >= 2) {
            delay(450); searching = true
            try { val result = mobilityBody(api.locations(query.trim())); check(result.success) { result.error ?: "Search unavailable." }; suggestions = result.places; searchMessage = if (result.places.isEmpty()) "No matching place. Try a nearby landmark or place a map pin." else null }
            catch (e: CancellationException) { throw e } catch (_: Exception) { searchMessage = "Search is temporarily unavailable. Place a map pin instead." }
            finally { searching = false }
        }
    }
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
            OutlinedTextField(query, { query = it.take(160) }, label = { Text("Search city, area or landmark") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
            suggestions.take(4).forEach { place -> TextButton(onClick = {
                selected = place.latitude to place.longitude; latitude = place.latitude.toString(); longitude = place.longitude.toString(); suggestions = emptyList(); map.controller.setCenter(GeoPoint(place.latitude, place.longitude))
            }, modifier = Modifier.fillMaxWidth()) { Text(place.name, maxLines = 2) } }
            TextButton(onClick = { if (hasMobilityLocationPermission(context)) currentLocation() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Use my current location", color = CommuteTeal) }
            searchMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Tap the map to confirm the exact pickup or destination.", style = MaterialTheme.typography.bodySmall)
            AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().height(230.dp), update = {
                marker.isEnabled = selected != null
                selected?.let { marker.position = GeoPoint(it.first, it.second) }; map.invalidate()
            })
            Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
        }
    }, confirmButton = { TextButton(enabled = selected != null, onClick = { selected?.let(onChoose) }) { Text("Use this location", color = CommuteTeal) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

internal fun validMobilityPoint(latitude: String, longitude: String): Pair<Double, Double>? {
    val lat = latitude.toDoubleOrNull() ?: return null; val lon = longitude.toDoubleOrNull() ?: return null
    return if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
}
