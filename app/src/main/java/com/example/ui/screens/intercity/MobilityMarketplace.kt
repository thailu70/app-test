package com.example.ui.screens.intercity

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.api.*
import com.example.ui.screens.passenger.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun mobilityNextAction(status: String): Pair<String, String>? = when (status) {
    "ASSIGNED" -> "ARRIVED_PICKUP" to "Arrived at pickup"
    "AT_PICKUP" -> "LOADED" to "Passengers boarded / Cargo loaded"
    "LOADED" -> "START_TRIP" to "Start trip"
    "IN_TRANSIT" -> "ARRIVED_DESTINATION" to "Arrived at destination"
    "AT_DESTINATION" -> "COMPLETE" to "Complete service"
    else -> null
}

@Composable internal fun DriverMarketplacePanel(api: MobilityApi, dashboard: MobilityDriverDashboard, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var result by remember { mutableStateOf<MobilityOpportunitiesResponse?>(null) }
    var message by remember { mutableStateOf<String?>(null) }; var working by remember { mutableStateOf(false) }
    var quoting by remember { mutableStateOf<MobilityOpportunity?>(null) }
    var permission by remember { mutableStateOf(hasMobilityLocationPermission(context)) }
    var nearbyLatitude by remember { mutableStateOf<Double?>(null) }; var nearbyLongitude by remember { mutableStateOf<Double?>(null) }
    var knownRequests by remember { mutableStateOf<Set<String>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permission = hasMobilityLocationPermission(context) }
    val active = dashboard.requests.firstOrNull { mobilityNextAction(it.status) != null }
    LaunchedEffect(permission) {
        if (permission) try { val fix = freshMobilityLocation(context); nearbyLatitude = fix.latitude; nearbyLongitude = fix.longitude }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* Browse remains usable without a location fix. */ }
    }
    suspend fun reload() {
        val response = mobilityBody(api.opportunities(nearbyLatitude, nearbyLongitude)); check(response.success) { response.error ?: "Requests are unavailable." }
        val ids = response.requests.filter { it.nearby && it.myQuote == null }.map { it.id }.toSet()
        if (!response.busy && knownRequests != null && (ids - knownRequests.orEmpty()).isNotEmpty()) message = "New transport requests are available nearby."
        knownRequests = ids; result = response
    }
    LaunchedEffect(dashboard.serviceType) {
        while (true) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                try { reload(); onChanged() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message }
            }
            delay(30000)
        }
    }
    LaunchedEffect(active?.id, permission, active?.status) {
        if (active != null && permission) while (true) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) try {
                val fix = freshMobilityLocation(context)
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(fix.time))
                val response = mobilityBody(api.updateRequestLocation(active.id, MobilityLocation(fix.latitude, fix.longitude, timestamp, fix.accuracy.toDouble())))
                check(response.success) { response.error ?: "Location update failed." }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message ?: "Location could not be shared." }
            delay(15000)
        }
    }
    fun act(block: suspend () -> Unit) {
        if (working) return
        working = true; message = null
        scope.launch { try { block(); reload(); onChanged() } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message } finally { working = false } }
    }
    quoting?.let { job ->
        var amount by remember(job.id) { mutableStateOf(job.myQuote?.priceEtb?.toString().orEmpty()) }
        var note by remember(job.id) { mutableStateOf(job.myQuote?.note.orEmpty()) }
        var eta by remember(job.id) { mutableStateOf("") }
        var vehicle by remember(job.id) { mutableStateOf(dashboard.vehicles.firstOrNull()?.id.orEmpty()) }
        var menu by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { quoting = null }, title = { Text("Your quote") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${job.origin} → ${job.destination}")
                job.estimatedCargoPriceEtb?.let { Text("Suggested: ETB ${formatIntercityFare(it)}", color = CommuteTeal) }
                Box { OutlinedButton(onClick = { menu = true }) { Text(dashboard.vehicles.find { it.id == vehicle }?.plateNumber ?: "Choose vehicle") }; DropdownMenu(menu, { menu = false }) { dashboard.vehicles.forEach { v -> DropdownMenuItem(text = { Text("${v.plateNumber} · ${v.model}") }, onClick = { vehicle = v.id; menu = false }) } } }
                MobilityField(amount, { amount = it }, "Total quote · ETB", "market_quote", number = true)
                MobilityField(eta, { eta = it.filter(Char::isDigit).take(4) }, "Minutes to pickup", "market_eta", number = true)
                MobilityField(note, { note = it.take(1000) }, "Included services / note", "market_note")
                Text("Quotes are free. Passo's configured commission is held on selection and charged after completion.", fontSize = 12.sp)
            }
        }, confirmButton = { TextButton(enabled = !working && vehicle.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0, onClick = {
            act { val response = mobilityBody(api.quote(job.id, MobilityQuoteInput(vehicle, amount.toDouble(), note, eta.toIntOrNull()))); check(response.success) { response.error ?: "Quote declined." }; quoting = null; message = "Quote sent to the customer." }
        }) { Text("Send quote") } }, dismissButton = { TextButton(onClick = { quoting = null }) { Text("Cancel") } })
    }
    MobilityCard {
        Text("Requests & driver credit", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 21.sp)
        result?.wallet?.let { wallet -> Text("Available ETB ${formatIntercityFare(wallet.availableEtb)} · Held ETB ${formatIntercityFare(wallet.heldEtb)}", color = CommuteTeal, fontSize = 13.sp) }
        Text("Contact Passo to top up your driver credit. Only verified payments are credited.", fontSize = 12.sp)
        if (!permission && active == null) TextButton(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Enable location for nearby requests", color = CommuteTeal) }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
        if (active != null) {
            Text("Current service", fontWeight = FontWeight.Bold, color = CommuteInk)
            Text("${active.origin} → ${active.destination}")
            Text(active.status.replace('_', ' '), color = CommuteTeal)
            Text("${active.customerName} · ${active.customerPhone}")
            Text("New request alerts pause until you finish this task.", fontSize = 12.sp)
            if (!permission) MobilityButton("Enable live location", true, "market_location") { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
            else Text("Live location is shared while this screen is open. Keep Passo open during the service.", fontSize = 12.sp, color = CommuteTeal)
            mobilityNextAction(active.status)?.let { (action, label) -> MobilityButton(label, !working, "market_progress") { act { val response = mobilityBody(api.progress(active.id, MobilityProgressInput(action))); check(response.success) { response.error ?: "Could not update this task." } } } }
        } else if (result?.busy == true) Text("You are busy. New requests will return when your task is finished.")
        else {
            Text("Available requests", fontWeight = FontWeight.Bold, color = CommuteInk)
            if (result?.requests?.isEmpty() == true) Text("No matching requests yet. This list updates automatically.", fontSize = 13.sp)
            result?.requests?.forEach { job ->
                HorizontalDivider(color = CommuteMint)
                Text("${job.origin} → ${job.destination}", fontWeight = FontWeight.Bold)
                Text("${job.travelDate} ${job.travelTime} · ${job.vehicleType}", fontSize = 12.sp)
                job.people?.let { Text("$it people") }; job.estimatedWeightKg?.let { Text("Estimated weight: $it kg") }
                job.details?.let { Text(it, fontSize = 12.sp) }
                job.routeDistanceKm?.let { Text("Road distance: ${formatIntercityFare(it)} km") }
                job.pickupDistanceKm?.let { Text("Pickup: ${formatIntercityFare(it)} km away") }
                job.estimatedCargoPriceEtb?.let { Text("Suggested cargo quote: ETB ${formatIntercityFare(it)}", color = CommuteTeal) }
                TextButton(enabled = !working && dashboard.vehicles.isNotEmpty(), onClick = { quoting = job }) { Text(if (job.myQuote == null) "Quote a price" else "Update your quote", color = CommuteTeal) }
            }
        }
        TextButton(enabled = !working, onClick = { act {} }) { Text("Refresh requests", color = CommuteTeal) }
    }
}

@Composable internal fun RequestMarketplacePanel(state: MobilityUiState, select: (String) -> Unit,
    confirm: (Boolean, String) -> Unit, review: (Int, String) -> Unit) {
    val request = state.request ?: return
    val context = LocalContext.current
    var chosen by remember(request.id) { mutableStateOf<MobilityQuote?>(null) }
    var problem by remember(request.id) { mutableStateOf("") }
    var rating by remember(request.id) { mutableIntStateOf(5) }; var comment by remember(request.id) { mutableStateOf("") }
    chosen?.let { quote -> AlertDialog(onDismissRequest = { chosen = null }, title = { Text("Choose ${quote.driverName}?") }, text = { Text("Confirm the agreed transport price of ETB ${formatIntercityFare(quote.priceEtb)}. Driver and customer contact details will be shared after confirmation.") }, confirmButton = { TextButton(onClick = { chosen = null; select(quote.id) }, enabled = !state.busy) { Text("Confirm driver") } }, dismissButton = { TextButton(onClick = { chosen = null }) { Text("Cancel") } }) }
    MobilityCard {
        if (request.status == "REQUESTED") {
            Text("Driver offers", fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = 21.sp)
            if (state.quotes.isEmpty()) Text("Waiting for available drivers to quote. Offers update automatically.")
            state.quotes.filter { it.status !in setOf("WITHDRAWN", "REJECTED", "EXPIRED") }.forEach { quote ->
                HorizontalDivider(color = CommuteMint)
                Text(quote.driverName, fontWeight = FontWeight.Bold)
                Text("${quote.vehicleType} · ${quote.vehicleModel}", fontSize = 13.sp)
                Text(if (quote.ratingCount > 0) "★ ${quote.ratingAverage ?: 0.0} · ${quote.ratingCount} reviews" else "New driver", color = CommuteTeal)
                Text("ETB ${formatIntercityFare(quote.priceEtb)}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
                quote.etaMinutes?.let { Text("Pickup estimate: $it minutes") }
                if (quote.note.isNotBlank()) Text(quote.note, fontSize = 13.sp)
                MobilityButton("Select this driver", !state.busy, "market_select_${quote.id}") { chosen = quote }
            }
        }
        request.driverPhone?.takeIf { it.isNotBlank() }?.let { phone -> OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) } }, modifier = Modifier.fillMaxWidth()) { Text("Call driver") } }
        if (request.status in setOf("ASSIGNED", "AT_PICKUP", "LOADED", "IN_TRANSIT", "AT_DESTINATION")) {
            Text("Driver location", fontWeight = FontWeight.Bold, color = CommuteInk)
            state.driverLocation?.let { location ->
                MobilityTrackingMap(location)
                Text("Last updated: ${location.timestamp}", fontSize = 12.sp)
            } ?: Text("Waiting for the driver's location. Ask the driver to enable location and keep Passo open.", fontSize = 12.sp)
        }
        if (request.status == "AWAITING_CONFIRMATION") {
            Text("Your driver has completed the service", fontWeight = FontWeight.Bold)
            MobilityButton("Confirm service completed", !state.busy, "market_confirm") { confirm(false, "") }
            MobilityField(problem, { problem = it.take(2000) }, "Describe a problem", "market_dispute_note")
            OutlinedButton(onClick = { confirm(true, problem) }, enabled = !state.busy && problem.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Report a problem") }
        }
        if (request.status == "COMPLETED" && !state.reviewSent && request.review == null) {
            Text("Rate your driver", fontWeight = FontWeight.Bold, color = CommuteInk)
            Row { (1..5).forEach { value -> TextButton(onClick = { rating = value }) { Text(if (value <= rating) "★" else "☆", color = CommuteTeal, fontSize = 23.sp) } } }
            MobilityField(comment, { comment = it.take(2000) }, "Optional comment · avoid private details", "market_review_comment")
            MobilityButton("Send $rating-star review", !state.busy, "market_review") { review(rating, comment) }
        }
    }
}

@Composable private fun MobilityTrackingMap(location: MobilityLocation) {
    val context = LocalContext.current
    val map = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(14.0) }
    }
    val marker = remember(map) { Marker(map).apply { title = "Your driver"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); map.overlays.add(this) } }
    DisposableEffect(map) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().height(210.dp), update = {
        val point = GeoPoint(location.latitude, location.longitude); marker.position = point; map.controller.setCenter(point); map.invalidate()
    })
    Text("© OpenStreetMap contributors", fontSize = 10.sp)
}
