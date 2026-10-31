package com.example.ui.screens.intercity

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.core.localization.AppLanguage
import com.example.core.qr.QrValidationResult
import com.example.data.api.*
import com.example.ui.screens.driver.DriverQrScannerDialog
import com.example.ui.screens.passenger.*
import com.example.ui.viewmodel.DriverTripState
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Resolve service once per authenticated session; daily and company intercity retain their existing portal. */
@Composable internal fun MobilityDriverPortal(viewModel: MainViewModel, onPrivacy: () -> Unit,
    fallback: @Composable () -> Unit) {
    val user by viewModel.currentUser.collectAsState(); val language by viewModel.currentLanguage.collectAsState()
    val api = remember(user?.id) { ApiClient.getAuthToken()?.takeIf { it.isNotBlank() }?.let { ApiClient.createSessionService(MobilityApi::class.java, it) } ?: ApiClient.createFeatureService(MobilityApi::class.java) }
    var dashboard by remember(user?.id) { mutableStateOf<MobilityDriverDashboard?>(null) }
    var error by remember(user?.id) { mutableStateOf<String?>(null) }
    var refresh by remember(user?.id) { mutableIntStateOf(0) }
    LaunchedEffect(user?.id, refresh) {
        error = null
        try { val result = mobilityBody(api.driverDashboard()); check(result.success) { result.error ?: "Driver portal is unavailable." }; dashboard = result }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not open the driver portal." }
    }
    val result = dashboard
    if (result != null && result.serviceType !in setOf("GROUP_TRAVEL", "CARGO")) { fallback(); return }
    if (result != null) {
        MobilityDriverScreen(api, result, language, { refresh++ }, onPrivacy)
    } else Column(Modifier.fillMaxSize().background(CommuteCream).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (error == null) { CircularProgressIndicator(color = CommuteTeal); Text("Opening your driver portal…", Modifier.padding(top = 14.dp), color = CommuteInk) }
        else { Text(error.orEmpty(), color = CommuteInk); TextButton(onClick = { refresh++ }) { Text("Try again") } }
    }
}

@Composable internal fun MobilityDriverScreen(api: MobilityApi, initial: MobilityDriverDashboard,
    language: AppLanguage, refreshParent: () -> Unit, onPrivacy: () -> Unit) {
    var dashboard by remember(initial) { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }; var notice by remember { mutableStateOf<String?>(null) }
    var proposing by remember { mutableStateOf(false) }; var selectedTrip by remember { mutableStateOf<MobilityTrip?>(null) }
    var roster by remember { mutableStateOf<List<MobilityTraveler>>(emptyList()) }
    var ticket by remember { mutableStateOf("") }; var scanner by remember { mutableStateOf(false) }
    var scannerError by remember { mutableStateOf<QrValidationResult?>(null) }; var boardedNotice by remember { mutableStateOf<String?>(null) }
    var confirmComplete by remember { mutableStateOf(false) }
    var photoRequest by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var locationAllowed by remember { mutableStateOf(hasMobilityLocationPermission(context)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationAllowed = hasMobilityLocationPermission(context)
        if (!locationAllowed) notice = "Location permission is needed to verify boarding at the approved departure point."
    }
    val group = dashboard.serviceType == "GROUP_TRAVEL"
    var journeysExpanded by remember(group) { mutableStateOf(dashboard.trips.any { it.status == "BOARDING" }) }
    photoRequest?.let { id -> MobilityCargoPhotoDialog(api, id) { photoRequest = null } }
    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        busy = true; notice = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Could not complete this action." }
            finally { busy = false }
        }
    }
    suspend fun refresh() {
        val result = mobilityBody(api.driverDashboard()); check(result.success) { result.error ?: "Could not refresh assignments." }; dashboard = result
        selectedTrip?.let { selected ->
            val manifest = mobilityBody(api.roster(selected.id)); check(manifest.success) { manifest.error ?: "Could not refresh travelers." }
            roster = manifest.travelers; selectedTrip = manifest.trip ?: selected
        }
    }
    fun board(value: String, qr: Boolean) = runAction {
        val trip = selectedTrip ?: error("Choose a trip.")
        val fix = freshMobilityLocation(context)
        val input = fix.boardingInput(if (qr) null else value.trim(), if (qr) value else null)
        val result = mobilityBody(api.board(trip.id, input)); check(result.success) { result.error ?: "Boarding declined." }
        ticket = ""; boardedNotice = "Traveler boarded successfully."; refresh()
    }
    BackHandler(enabled = proposing || selectedTrip != null) { proposing = false; selectedTrip = null; roster = emptyList() }
    if (scanner && selectedTrip != null) {
        val trip = selectedTrip!!
        DriverQrScannerDialog(scannerError, trip.origin, DriverTripState(tripId = trip.id, vehicleId = trip.vehicleId,
            vehiclePlate = trip.vehiclePlate, vehicleCapacity = trip.seats, checkedInCount = roster.count { it.status == "BOARDED" }),
            onScanToken = { token ->
                if (!busy) {
                    busy = true; scope.launch {
                        try {
                            val result = mobilityBody(api.board(trip.id, freshMobilityLocation(context).boardingInput(qrToken = token)))
                            check(result.success) { result.error ?: "Boarding declined." }; boardedNotice = "Traveler boarded successfully."; refresh()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { scannerError = QrValidationResult.Invalid("Boarding declined", e.message.orEmpty()) }
                        finally { busy = false }
                    }
                }
            }, onDismissResult = { scannerError = null; boardedNotice = null }, onCloseScanner = { scanner = false }, boardingNotice = boardedNotice)
    }
    if (confirmComplete) AlertDialog(onDismissRequest = { confirmComplete = false }, title = { Text("Complete this trip?") },
        text = { Text("Boarding will close for this trip. Complete it after the journey has finished.") },
        confirmButton = { TextButton(onClick = { confirmComplete = false; runAction { val trip = selectedTrip ?: return@runAction; val result = mobilityBody(api.complete(trip.id)); check(result.success) { result.error ?: "Could not complete trip." }; refresh() } }) { Text("Complete trip") } },
        dismissButton = { TextButton(onClick = { confirmComplete = false }) { Text("Cancel") } })
    Column(Modifier.fillMaxSize().background(CommuteCream)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (proposing || selectedTrip != null) IconButton(onClick = { proposing = false; selectedTrip = null }) { Icon(Icons.Default.ArrowBack, "Back", tint = CommuteInk) }
            Text(when { proposing -> "New group journey"; selectedTrip != null -> "Trip & travelers"; group -> intercityText(language, "Your group jobs", "የቡድን ጉዞ ሥራዎችዎ", "Hojii imala garee kee"); else -> intercityText(language, "Your cargo jobs", "የጭነት ሥራዎችዎ", "Hojii fe'umsaa kee") }, fontWeight = FontWeight.Bold, fontSize = 25.sp, color = CommuteInk, modifier = Modifier.weight(1f).padding(start = if (proposing || selectedTrip != null) 0.dp else 4.dp))
            IconButton(onClick = { runAction { refresh(); refreshParent() } }, enabled = !busy) { Icon(Icons.Default.Refresh, "Refresh", tint = CommuteTeal) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = CommuteTeal)
        LazyColumn(Modifier.weight(1f).testTag("mobility_driver_content"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            notice?.let { item { MobilityCard { Text(it, color = CommuteInk) } } }
            if (proposing) item {
                GroupTripProposal(dashboard.vehicles, busy) { input -> runAction {
                    val result = mobilityBody(api.proposeTrip(input)); check(result.success) { result.error ?: "Could not propose this trip." }
                    proposing = false; notice = "Trip sent for admin review. It becomes visible to travelers only after approval."; refresh()
                } }
            } else if (selectedTrip != null) {
                val trip = selectedTrip!!
                item { MobilityCard {
                    Text("${trip.origin} → ${trip.destination}", color = CommuteInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(formatIntercityTime(trip.departureAt), color = CommuteTeal)
                    Text("${trip.status.replace('_', ' ')} · ${trip.vehiclePlate}", color = CommuteInk)
                    Text("${roster.count { it.status == "BOARDED" }} boarded · ${roster.size} paid travelers", color = CommuteTeal, fontWeight = FontWeight.Bold)
                    if (trip.status in setOf("APPROVED", "BOARDING")) {
                        Text("Passo uses a fresh device location once for each boarding action to verify you are within the approved departure radius. Boarding opens 1 hour before departure and closes 30 minutes after.", color = CommuteInk, fontSize = 12.sp)
                        if (!locationAllowed) MobilityButton("Enable location for boarding", !busy, "group_enable_location") { permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
                        else if (trip.status == "APPROVED") MobilityButton("Start boarding", !busy, "group_start_boarding") { runAction {
                            val result = mobilityBody(api.startBoarding(trip.id, freshMobilityLocation(context).boardingInput())); check(result.success) { result.error ?: "Could not start boarding." }; refresh()
                        } }
                        else {
                            MobilityField(ticket, { ticket = it.uppercase() }, "Ticket number", "group_board_ticket")
                            MobilityButton("Board by ticket number", !busy && ticket.isNotBlank(), "group_board_by_number") { board(ticket, false) }
                            OutlinedButton(onClick = { scannerError = null; boardedNotice = null; scanner = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan boarding QR") }
                            TextButton(onClick = { confirmComplete = true }, enabled = !busy) { Text("Complete trip", color = CommuteTeal) }
                        }
                    }
                } }
                if (roster.isEmpty()) item { Text("Paid travelers will appear here after booking confirmation.", color = CommuteInk) }
                items(roster, key = { it.id }) { traveler -> MobilityCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (traveler.status == "BOARDED") Icons.Default.CheckCircle else Icons.Default.Person, null, tint = CommuteTeal)
                        Column(Modifier.weight(1f).padding(start = 11.dp)) {
                            Text(traveler.name, fontWeight = FontWeight.Bold, color = CommuteInk)
                            Text("Seat ${traveler.seatNumber} · ${traveler.phone}", fontSize = 12.sp)
                            Text(traveler.status.orEmpty(), color = CommuteTeal, fontSize = 12.sp)
                        }
                    }
                } }
            } else {
                item { DriverMarketplacePanel(api, dashboard, { scope.launch { try { refresh() } catch (e: CancellationException) { throw e } catch (e: Exception) { notice = e.message ?: "Could not refresh assignments." } } }, onPhoto = { photoRequest = it }, language = language) }
                if (group) item { MobilityCard {
                    Row(Modifier.fillMaxWidth().clickable { journeysExpanded = !journeysExpanded }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Groups, null, tint = CommuteTeal, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) { Text("Scheduled group journeys", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text("${dashboard.trips.size} journeys · seat bookings & boarding", color = CommuteTeal, fontSize = 12.sp) }
                        Icon(if (journeysExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (journeysExpanded) "Collapse journeys" else "Open journeys", tint = CommuteTeal)
                    }
                    if (journeysExpanded) {
                        if (dashboard.vehicles.isEmpty()) Text("No approved vehicle is available. Contact Passo.", color = CommuteInk, fontSize = 13.sp)
                        MobilityButton("Propose a group trip", !busy && dashboard.vehicles.isNotEmpty(), "group_propose_trip") { proposing = true }
                        if (dashboard.trips.isEmpty()) Text("Propose a journey and price per seat for admin approval.", color = CommuteInk, fontSize = 13.sp)
                        dashboard.trips.forEach { trip ->
                            HorizontalDivider(color = CommuteMint)
                            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { runAction { val result = mobilityBody(api.roster(trip.id)); check(result.success) { result.error ?: "Could not load travelers." }; selectedTrip = result.trip ?: trip; roster = result.travelers } }, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${trip.origin} → ${trip.destination}", fontWeight = FontWeight.Bold, color = CommuteInk, fontSize = 15.sp)
                                    Text(formatIntercityTime(trip.departureAt), color = CommuteTeal, fontSize = 12.sp)
                                    Text("${mobilityDriverStatus(trip.status)} · ETB ${formatIntercityFare(trip.fareEtb)} / seat", color = CommuteInk, fontSize = 12.sp)
                                    if (trip.reviewNote.isNotBlank()) Text(trip.reviewNote, color = CommuteInk, fontSize = 12.sp)
                                }
                                Icon(Icons.Default.ChevronRight, "Open trip & travelers", tint = CommuteTeal)
                            }
                        }
                    }
                } }
                item { TextButton(onClick = onPrivacy) { Icon(Icons.Default.PrivacyTip, null); Spacer(Modifier.width(8.dp)); Text("Privacy & account") } }
            }
        }
    }
}

private suspend fun loadMobilityCargoPhoto(api: MobilityApi, requestId: String): android.graphics.Bitmap = withContext(Dispatchers.IO) {
    val response = mobilityBody(api.requestPhoto(requestId))
    val bytes = response.use { body ->
        require(body.contentLength() <= 5 * 1024 * 1024) { "Cargo photo is too large." }
        body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 5 * 1024 * 1024) { "Cargo photo is too large." }; output.write(buffer, 0, count) }; output.toByteArray()
        }
    }
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = 1
        while (bounds.outWidth / inSampleSize > 1600 || bounds.outHeight / inSampleSize > 1600) inSampleSize *= 2
    }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("The cargo photo could not be displayed.")
}

@Composable internal fun MobilityCargoPhotoPreview(api: MobilityApi, requestId: String, open: () -> Unit) {
    var bitmap by remember(requestId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(requestId) { mutableStateOf(false) }
    LaunchedEffect(requestId) {
        try { bitmap = loadMobilityCargoPhoto(api, requestId) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Surface(color = CommuteMint, shape = RoundedCornerShape(9.dp), modifier = Modifier.size(62.dp).clickable(onClick = open).testTag("market_cargo_photo")) {
        Box(contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) Image(image.asImageBitmap(), "View cargo photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)))
            else if (failed) Icon(Icons.Default.Photo, "Open cargo photo", tint = CommuteTeal)
            else CircularProgressIndicator(Modifier.size(20.dp), color = CommuteTeal, strokeWidth = 2.dp)
        }
    }
}

@Composable private fun MobilityCargoPhotoDialog(api: MobilityApi, requestId: String, onDismiss: () -> Unit) {
    var bitmap by remember(requestId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var photoError by remember(requestId) { mutableStateOf<String?>(null) }
    LaunchedEffect(requestId) {
        try {
            bitmap = loadMobilityCargoPhoto(api, requestId)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { photoError = e.message ?: "Could not open the cargo photo." }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Cargo photo") }, text = {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), "Cargo photo", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
        else if (photoError != null) Text(photoError.orEmpty()) else CircularProgressIndicator(color = CommuteTeal)
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable private fun GroupTripProposal(vehicles: List<MobilityVehicle>, busy: Boolean, propose: (MobilityTripInput) -> Unit) {
    var vehicle by remember { mutableStateOf(vehicles.firstOrNull()?.id.orEmpty()) }
    var vehicleMenu by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf("") }; var destination by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(intercityToday()) }; var time by remember { mutableStateOf("08:00") }
    var seats by remember { mutableStateOf("") }; var fare by remember { mutableStateOf("") }
    var point by remember { mutableStateOf<Pair<Double, Double>?>(null) }; var mapOpen by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    if (mapOpen) MobilityLocationPicker("Approved departure / boarding point", point, { mapOpen = false }, { point = it; mapOpen = false })
    MobilityCard {
        Text("Propose your group trip", fontWeight = FontWeight.Bold, fontSize = 25.sp, color = CommuteInk)
        Text("Your trip and per-seat price require admin approval before travelers can see or book it.", fontSize = 12.sp, color = CommuteInk)
        Box {
            OutlinedButton(onClick = { vehicleMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(vehicles.find { it.id == vehicle }?.let { "${it.plateNumber} · ${it.model}" } ?: "Choose your vehicle"); Icon(Icons.Default.ArrowDropDown, null) }
            DropdownMenu(vehicleMenu, { vehicleMenu = false }) { vehicles.forEach { item -> DropdownMenuItem(text = { Text("${item.plateNumber} · ${item.capacityLimit} seats") }, onClick = { vehicle = item.id; vehicleMenu = false; seats = "" }) } }
        }
        MobilityField(origin, { origin = it }, "Departure", "group_proposal_origin")
        MobilityField(destination, { destination = it }, "Destination", "group_proposal_destination")
        MobilityDateField(date, { date = it })
        MobilityField(time, { time = it.take(5) }, "Departure time · HH:mm · Ethiopia time", "group_proposal_time")
        OutlinedButton(onClick = { mapOpen = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.LocationOn, null); Spacer(Modifier.width(5.dp)); Text(if (point == null) "Choose boarding point on map" else "Boarding point selected ✓") }
        MobilityField(seats, { seats = it.filter(Char::isDigit).take(3) }, "Bookable seats · vehicle limit ${vehicles.find { it.id == vehicle }?.capacityLimit ?: 0}", "group_proposal_seats", number = true)
        MobilityField(fare, { fare = it }, "Price per seat · ETB", "group_proposal_fare", number = true)
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        MobilityButton("Send trip for approval", !busy && point != null && origin.isNotBlank() && destination.isNotBlank() && vehicle.isNotBlank(), "group_proposal_submit") {
            try {
                val count = seats.toIntOrNull(); val price = fare.toDoubleOrNull(); val capacity = vehicles.find { it.id == vehicle }?.capacityLimit ?: 0
                require(count != null && count in 1..capacity.coerceAtMost(100)) { "Choose a seat count within your vehicle capacity." }
                require(price != null && price.isFinite() && price > 0) { "Enter a valid positive price per seat." }
                val iso = mobilityDepartureIso(date, time) ?: error("Enter a valid departure time, for example 08:30.")
                require(origin.trim().lowercase() != destination.trim().lowercase()) { "Choose different departure and destination locations." }
                propose(MobilityTripInput(vehicle, origin.trim(), destination.trim(), iso, count, price, point!!.first, point!!.second))
            } catch (e: IllegalArgumentException) { problem = e.message }
            catch (e: IllegalStateException) { problem = e.message }
        }
    }
}
internal fun mobilityDepartureIso(date: String, time: String): String? = runCatching {
    require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date) && Regex("\\d{2}:\\d{2}").matches(time))
    val source = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false; timeZone = TimeZone.getTimeZone("Africa/Addis_Ababa") }
    val parsed = source.parse("$date $time") ?: return null
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(parsed)
}.getOrNull()

internal fun hasMobilityLocationPermission(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
@Suppress("MissingPermission", "DEPRECATION")
internal suspend fun freshMobilityLocation(context: Context): Location {
    check(hasMobilityLocationPermission(context)) { "Enable location permission first." }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return withTimeoutOrNull(30_000) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
                    if (age !in 0..60_000 || !location.hasAccuracy() || location.accuracy <= 0 || location.accuracy > 100) return
                    manager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            try {
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
                check(providers.isNotEmpty()) { "Turn on device location and try again." }
                providers.forEach { manager.requestLocationUpdates(it, 1000L, 0f, listener, Looper.getMainLooper()) }
            } catch (e: Exception) { manager.removeUpdates(listener); if (continuation.isActive) continuation.resumeWithException(e) }
        }
    } ?: error("A precise location could not be found. Move outdoors, turn on device location and try again.")
}
private fun Location.boardingInput(ticketNumber: String? = null, qrToken: String? = null): MobilityBoardingInput = MobilityBoardingInput(latitude, longitude, accuracy,
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time)), ticketNumber, qrToken)
