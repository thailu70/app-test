package com.example.ui.screens.intercity

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.api.*
import com.example.core.localization.AppLanguage
import com.example.core.notifications.MobilityNotificationNavigation
import com.example.core.notifications.MobilityNotificationOptIn
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

internal fun mobilityDriverStatus(status: String): String = when (status) {
    "ASSIGNED" -> "Ready for pickup"
    "AT_PICKUP" -> "At the pickup location"
    "LOADED" -> "Ready to start the trip"
    "IN_TRANSIT" -> "Trip in progress"
    "AT_DESTINATION" -> "Arrived at destination"
    "AWAITING_CONFIRMATION" -> "Waiting for customer confirmation"
    "DISPUTED" -> "Passo is reviewing a reported issue"
    "COMPLETED" -> "Service completed"
    "CANCELLED" -> "Booking cancelled"
    "REJECTED" -> "Request declined"
    "EXPIRED" -> "Request expired"
    else -> status.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable internal fun DriverMarketplacePanel(api: MobilityApi, dashboard: MobilityDriverDashboard, onChanged: () -> Unit,
    onPhoto: (String) -> Unit = {}, language: AppLanguage = AppLanguage.ENGLISH) {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var result by remember { mutableStateOf<MobilityOpportunitiesResponse?>(null) }
    var message by remember { mutableStateOf<String?>(null) }; var working by remember { mutableStateOf(false) }
    var quoting by remember { mutableStateOf<MobilityOpportunity?>(null) }
    var tab by remember { mutableStateOf("Current") }
    var creditOpen by remember { mutableStateOf(false) }
    val notificationFocus by MobilityNotificationNavigation.driverFocus.collectAsState()
    var permission by remember { mutableStateOf(hasMobilityLocationPermission(context)) }
    var nearbyLatitude by remember { mutableStateOf<Double?>(null) }; var nearbyLongitude by remember { mutableStateOf<Double?>(null) }
    var knownRequests by remember { mutableStateOf<Set<String>?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permission = hasMobilityLocationPermission(context) }
    val active = dashboard.requests.firstOrNull { mobilityNextAction(it.status) != null }
    val pending = dashboard.requests.filter { it.status in setOf("AWAITING_CONFIRMATION", "DISPUTED") }
    val current = active ?: pending.firstOrNull()
    val history = dashboard.requests.filter { it.status in setOf("COMPLETED", "CANCELLED", "REJECTED", "EXPIRED") }
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
    LaunchedEffect(notificationFocus, dashboard.serviceType) {
        val target = notificationFocus ?: return@LaunchedEffect
        val serviceKind = if (dashboard.serviceType == "CARGO") "CARGO" else "GROUP"
        if (target.kind == serviceKind) {
            tab = if (target.eventType == "NEW_REQUEST") "Requests" else "Current"
            try { reload(); onChanged() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "Could not refresh this update." }
        }
        MobilityNotificationNavigation.consumeDriverFocus()
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
        val matchingVehicles = dashboard.vehicles.filter { it.vehicleType == job.vehicleType && (job.people == null || it.capacityLimit >= job.people) }
        var amount by remember(job.id) { mutableStateOf(job.myQuote?.priceEtb?.toString().orEmpty()) }
        var note by remember(job.id) { mutableStateOf(job.myQuote?.note.orEmpty()) }
        var eta by remember(job.id) { mutableStateOf("") }
        var vehicle by remember(job.id) { mutableStateOf(matchingVehicles.firstOrNull()?.id.orEmpty()) }
        var menu by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { quoting = null }, title = { Text("Your quote") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${job.origin} → ${job.destination}")
                job.estimatedCargoPriceEtb?.let { Text("Suggested: ETB ${formatIntercityFare(it)}", color = CommuteTeal) }
                Box { OutlinedButton(onClick = { menu = true }) { Text(matchingVehicles.find { it.id == vehicle }?.plateNumber ?: "Choose vehicle") }; DropdownMenu(menu, { menu = false }) { matchingVehicles.forEach { v -> DropdownMenuItem(text = { Text("${v.plateNumber} · ${v.model}") }, onClick = { vehicle = v.id; menu = false }) } } }
                MobilityField(amount, { amount = it }, "Total quote · ETB", "market_quote", number = true)
                MobilityField(eta, { eta = it.filter(Char::isDigit).take(4) }, "Minutes to pickup", "market_eta", number = true)
                MobilityField(note, { note = it.take(1000) }, "Included services / note", "market_note")
                Text("Quotes are free. Passo's configured commission is held on selection and charged after completion.", fontSize = 12.sp)
            }
        }, confirmButton = { TextButton(enabled = !working && vehicle.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0, onClick = {
            act { val response = mobilityBody(api.quote(job.id, MobilityQuoteInput(vehicle, amount.toDouble(), note, eta.toIntOrNull()))); check(response.success) { response.error ?: "Quote declined." }; quoting = null; message = "Quote sent to the customer." }
        }) { Text("Send quote") } }, dismissButton = { TextButton(onClick = { quoting = null }) { Text("Cancel") } })
    }
    if (creditOpen) AlertDialog(onDismissRequest = { creditOpen = false }, title = { Text("Driver credit") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            result?.wallet?.let { wallet ->
                Text("Available: ETB ${formatIntercityFare(wallet.availableEtb)}", fontWeight = FontWeight.Bold, color = CommuteInk)
                Text("Held: ETB ${formatIntercityFare(wallet.heldEtb)}", color = CommuteTeal)
            }
            Text("The Passo fee is held when a customer selects your quote. It is collected from your credit after completion is confirmed. A cancellation before pickup releases the hold.", fontSize = 14.sp)
            Text("To add credit, contact Passo for payment instructions. Your balance is updated after the actual payment is verified.", fontSize = 14.sp)
            OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:info@passo.live?subject=Driver%20credit"))) }.onFailure { message = "Contact info@passo.live for driver credit." } }) { Icon(Icons.Default.Email, null); Spacer(Modifier.width(8.dp)); Text("Contact Passo") }
        }
    }, confirmButton = { TextButton(onClick = { creditOpen = false }) { Text("Done") } })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFFE7E3DB)), modifier = Modifier.fillMaxWidth().testTag("market_credit_strip")) {
            Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountBalanceWallet, null, tint = CommuteTeal, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(start = 8.dp, end = 5.dp)) {
                    Text("Available", fontSize = 11.sp, color = CommuteInk)
                    Text(result?.wallet?.let { "ETB ${formatIntercityFare(it.availableEtb)}" } ?: "—", fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                VerticalDivider(Modifier.height(34.dp), color = CommuteMint)
                Icon(Icons.Default.Lock, null, tint = CommuteTeal, modifier = Modifier.padding(start = 12.dp).size(21.dp))
                Column(Modifier.weight(1f).padding(start = 8.dp, end = 5.dp)) {
                    Text("Held", fontSize = 11.sp, color = CommuteInk)
                    Text(result?.wallet?.let { "ETB ${formatIntercityFare(it.heldEtb)}" } ?: "—", fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { creditOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.testTag("market_credit")) { Text("Credit", fontSize = 13.sp); Icon(Icons.Default.ArrowForward, null, Modifier.padding(start = 3.dp).size(16.dp)) }
            }
        }
        Surface(color = Color(0xFFEDF3F5), shape = RoundedCornerShape(28.dp), border = BorderStroke(1.dp, Color(0xFFDFE8EA))) {
            Row(Modifier.fillMaxWidth().padding(2.dp)) { listOf("Current", "Requests", "History").forEach { label ->
                TextButton(onClick = { tab = label }, shape = RoundedCornerShape(25.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (tab == label) CommuteTeal else Color.Transparent, contentColor = if (tab == label) Color.White else CommuteTeal), modifier = Modifier.weight(1f).testTag("market_tab_${label.lowercase()}")) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            } }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
        if (active == null) MobilityNotificationOptIn(driver = true, language = language)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
        when (tab) {
            "Current" -> {
                current?.let { job -> DriverJobCard(api, job, working, permission, onPhoto,
                    enableLocation = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                    progress = { action -> act { val response = mobilityBody(api.progress(job.id, MobilityProgressInput(action))); check(response.success) { response.error ?: "Could not update this task." } } }) }
                    ?: MobilityCard { Icon(Icons.Default.LocalShipping, null, tint = CommuteTeal); Text("Ready for your next job", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("Choose a request and send your quote. The customer chooses their driver.", color = CommuteInk, fontSize = 13.sp) }
                if (active == null && result?.busy != true) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocalShipping, null, tint = CommuteTeal, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
                        Text("You can now quote on new requests.", fontSize = 12.sp, color = CommuteInk, modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Available requests", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CommuteInk, modifier = Modifier.weight(1f)); TextButton(onClick = { tab = "Requests" }) { Text("View all"); Icon(Icons.Default.ArrowForward, null, Modifier.size(16.dp)) } }
                    result?.requests?.take(2)?.forEach { job -> DriverOpportunityCard(job, !working && dashboard.vehicles.isNotEmpty(), compact = true) { quoting = job } }
                    if (result == null) Text("Finding available requests…", fontSize = 13.sp, color = CommuteInk)
                    else if (result?.requests?.isEmpty() == true) Text("No matching requests yet. We’ll refresh this list automatically.", fontSize = 13.sp, color = CommuteInk)
                }
                val otherPending = pending.size - if (current?.status in setOf("AWAITING_CONFIRMATION", "DISPUTED")) 1 else 0
                if (otherPending > 0) TextButton(onClick = { tab = "History" }) { Text("$otherPending more awaiting resolution") }
            }
            "Requests" -> {
                if (!permission) TextButton(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Icon(Icons.Default.MyLocation, null, Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text("Enable location for nearby requests") }
                if (active != null || result?.busy == true) MobilityCard { Text("Finish your current task first", fontWeight = FontWeight.Bold, color = CommuteInk); Text("New request alerts pause while you are on a job.", fontSize = 13.sp, color = CommuteInk) }
                else {
                    if (result == null) Text("Finding available requests…", color = CommuteInk)
                    else if (result?.requests?.isEmpty() == true) MobilityCard { Text("No matching requests yet", fontWeight = FontWeight.Bold, color = CommuteInk); Text("Your matching requests will appear here automatically.", fontSize = 13.sp, color = CommuteInk) }
                    result?.requests?.forEach { job -> DriverOpportunityCard(job, !working && dashboard.vehicles.isNotEmpty()) { quoting = job } }
                }
                TextButton(enabled = !working, onClick = { act {} }) { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh requests") }
            }
            "History" -> {
                pending.forEach { job -> DriverJobCard(api, job, working, permission, onPhoto, {}, {}, compact = true) }
                history.forEach { job -> DriverJobCard(api, job, working, permission, onPhoto, {}, {}, compact = true) }
                if (pending.isEmpty() && history.isEmpty()) MobilityCard { Text("Your completed jobs will appear here", color = CommuteInk, fontWeight = FontWeight.Bold); Text("You can open any job to see its customer, agreed price and service details.", fontSize = 13.sp, color = CommuteInk) }
            }
        }
    }
}

@Composable private fun DriverOpportunityCard(job: MobilityOpportunity, enabled: Boolean, compact: Boolean = false, quote: () -> Unit) {
    var expanded by remember(job.id) { mutableStateOf(!compact) }
    Surface(color = Color.White, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Color(0xFFE7E3DB)), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, tint = CommuteTeal, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) { Text("${job.origin} → ${job.destination}", fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = 15.sp); Text(listOf(job.travelDate, job.travelTime).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = CommuteInk) }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight, if (expanded) "Collapse request" else "View request", tint = CommuteTeal)
            }
            if (expanded) {
                Text(listOfNotNull(job.vehicleType.replace('_', ' '), job.people?.let { "$it people" }, job.estimatedWeightKg?.let { "${formatIntercityFare(it)} kg" }).joinToString(" · "), color = CommuteInk, fontSize = 13.sp)
                job.details?.takeIf { it.isNotBlank() }?.let { Text(it, color = CommuteInk, fontSize = 13.sp) }
                val distances = listOfNotNull(job.pickupDistanceKm?.let { "${formatIntercityFare(it)} km to pickup" }, job.routeDistanceKm?.let { "${formatIntercityFare(it)} km journey" })
                if (distances.isNotEmpty()) Text(distances.joinToString(" · "), fontSize = 12.sp, color = CommuteTeal)
                job.estimatedCargoPriceEtb?.let { Text("Suggested quote: ETB ${formatIntercityFare(it)}", color = CommuteTeal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                job.myQuote?.let { Text("Your quote: ETB ${formatIntercityFare(it.priceEtb)} · Waiting for customer", fontSize = 12.sp, color = CommuteTeal) }
                OutlinedButton(onClick = quote, enabled = enabled, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("market_quote_${job.id}")) { Text(if (job.myQuote == null) "Quote a price" else "Update your quote"); Spacer(Modifier.width(8.dp)); Icon(Icons.Default.ArrowForward, null, Modifier.size(17.dp)) }
            }
        }
    }
}

@Composable private fun DriverJobCard(api: MobilityApi, job: MobilityRequest, working: Boolean, permission: Boolean,
    onPhoto: (String) -> Unit, enableLocation: () -> Unit, progress: (String) -> Unit, compact: Boolean = false) {
    val context = LocalContext.current
    var expanded by remember(job.id) { mutableStateOf(!compact) }
    var detailsOpen by remember(job.id) { mutableStateOf(false) }
    val awaiting = job.status == "AWAITING_CONFIRMATION"
    val terminal = job.status in setOf("COMPLETED", "CANCELLED", "REJECTED", "EXPIRED")
    val inProgress = mobilityNextAction(job.status) != null
    MobilityCard(Modifier.testTag("market_job_${job.id}")) {
        if (!compact || expanded) Surface(color = if (awaiting || job.status == "DISPUTED") Color(0xFFFFEDCB) else CommuteMint, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (awaiting) Icons.Default.Schedule else if (job.status == "COMPLETED") Icons.Default.CheckCircle else Icons.Default.LocalShipping, null, tint = if (awaiting) Color(0xFFAC6200) else CommuteTeal, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(8.dp)); Text(mobilityDriverStatus(job.status), fontWeight = FontWeight.SemiBold, color = if (awaiting) Color(0xFF995400) else CommuteTeal, fontSize = 13.sp) }
        }
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("${job.origin} → ${job.destination}", fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = if (compact) 16.sp else 19.sp); if (compact && !expanded) Text("${job.travelDate} · ${mobilityDriverStatus(job.status)}", color = CommuteTeal, fontSize = 12.sp) }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ChevronRight, if (expanded) "Collapse job" else "View job", tint = CommuteTeal)
        }
        if (expanded) {
            if (awaiting) Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = CommuteTeal, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)); Text(if (job.kind == "CARGO") "Cargo delivered" else "Journey completed", color = CommuteInk, fontSize = 13.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.RadioButtonUnchecked, null, tint = Color(0xFF9AAAB5), modifier = Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)); Text("Customer confirmation pending", color = Color(0xFF61768A), fontSize = 13.sp) }
            }
            HorizontalDivider(color = Color(0xFFE2E7E8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (job.kind == "CARGO" && job.photoUrl != null) { MobilityCargoPhotoPreview(api, job.id) { onPhoto(job.id) }; Spacer(Modifier.width(12.dp)) }
                else { Icon(if (job.kind == "CARGO") Icons.Default.Inventory2 else Icons.Default.Groups, null, tint = CommuteTeal, modifier = Modifier.size(35.dp)); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(listOfNotNull(job.cargoDetails?.takeIf { it.isNotBlank() }, job.estimatedWeightKg?.let { "${formatIntercityFare(it)} kg" }, job.people?.let { "$it travelers" }).joinToString(" · ").ifBlank { job.vehicleType.replace('_', ' ') }, maxLines = 3, overflow = TextOverflow.Ellipsis, color = CommuteInk, fontSize = 13.sp)
                    job.quoteEtb?.let { Text("Agreed total  ETB ${formatIntercityFare(it)}", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                }
            }
            HorizontalDivider(color = Color(0xFFE2E7E8))
            Row(verticalAlignment = Alignment.Top) { Icon(Icons.Default.Payments, null, tint = CommuteInk, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth()) { Text("Passo fee", color = CommuteInk, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text("ETB ${formatIntercityFare(job.commissionEtb ?: 0.0)}" + if (!terminal && (job.commissionEtb ?: 0.0) > 0) " · Held" else "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CommuteInk) }
                Text(when { (job.commissionEtb ?: 0.0) <= 0 -> "No Passo fee is applied to this booking."; job.status == "COMPLETED" -> "Collected from credit after confirmation."; job.status == "CANCELLED" -> "Any unused fee hold has been released."; else -> "Collected from credit after confirmation." }, color = Color(0xFF61768A), fontSize = 11.sp)
            } }
            HorizontalDivider(color = Color(0xFFE2E7E8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PersonOutline, null, tint = CommuteInk, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text("Customer", color = Color(0xFF61768A), fontSize = 11.sp); Text(job.customerName, color = CommuteInk, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
                if (job.customerPhone.isNotBlank()) OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${job.customerPhone}"))) } }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), modifier = Modifier.testTag("market_call_customer")) { Icon(Icons.Default.Call, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Call customer", fontSize = 12.sp) }
            }
            if (inProgress || detailsOpen) Text(listOf(job.travelDate, job.travelTime, job.vehiclePlate.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), color = Color(0xFF61768A), fontSize = 12.sp)
            TextButton(onClick = { detailsOpen = !detailsOpen }, contentPadding = PaddingValues(0.dp), modifier = Modifier.testTag("market_job_details")) { Icon(Icons.Default.Description, null, Modifier.size(19.dp)); Spacer(Modifier.width(7.dp)); Text(if (detailsOpen) "Hide details" else if (job.kind == "CARGO") "View delivery details" else "View journey details", fontSize = 13.sp); Spacer(Modifier.width(7.dp)); Icon(if (detailsOpen) Icons.Default.ExpandLess else Icons.Default.ArrowForward, null, Modifier.size(16.dp)) }
            if (detailsOpen) {
                job.cargoDetails?.takeIf { it.isNotBlank() }?.let { Text(it, color = CommuteInk, fontSize = 13.sp) }
                Text("${job.vehicleType.replace('_', ' ')} · ${job.vehiclePlate.orEmpty()}", color = CommuteInk, fontSize = 12.sp)
                if (job.customerPhone.isNotBlank()) Text(job.customerPhone, color = CommuteInk, fontSize = 13.sp)
                if (job.reviewNote.isNotBlank()) Text(job.reviewNote, color = CommuteInk, fontSize = 12.sp)
                Text("Booking ${job.id}", fontSize = 10.sp, color = Color(0xFF61768A))
            }
            if (detailsOpen) job.routeDistanceKm?.let { Text("Road distance: ${formatIntercityFare(it)} km", color = Color(0xFF61768A), fontSize = 12.sp) }
            if (inProgress || detailsOpen) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (job.originLatitude != null && job.originLongitude != null) TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${job.originLatitude},${job.originLongitude}?q=${job.originLatitude},${job.originLongitude}"))) } }, contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Default.LocationOn, null, Modifier.size(17.dp)); Spacer(Modifier.width(3.dp)); Text("Pickup", fontSize = 12.sp) }
                if (job.destinationLatitude != null && job.destinationLongitude != null) TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${job.destinationLatitude},${job.destinationLongitude}?q=${job.destinationLatitude},${job.destinationLongitude}"))) } }, contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Default.Flag, null, Modifier.size(17.dp)); Spacer(Modifier.width(3.dp)); Text("Destination", fontSize = 12.sp) }
            }
            if (job.status == "DISPUTED") Text("The service is finished. Passo will review the issue before settling the fee.", fontSize = 12.sp, color = CommuteInk)
            if (inProgress) {
                if (!permission) MobilityButton("Enable live location", !working, "market_location", enableLocation)
                else Text("Live location is shared while this screen is open. Keep Passo open during the service.", fontSize = 11.sp, color = CommuteTeal)
                mobilityNextAction(job.status)?.let { (action, label) ->
                    val actionLabel = if (action == "LOADED") { if (job.kind == "CARGO") "Confirm cargo loaded" else "Confirm passengers boarded" } else label
                    MobilityButton(actionLabel, !working, "market_progress") { progress(action) }
                }
                Text("New request alerts pause until this task is finished.", fontSize = 11.sp, color = Color(0xFF61768A))
            }
        }
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
