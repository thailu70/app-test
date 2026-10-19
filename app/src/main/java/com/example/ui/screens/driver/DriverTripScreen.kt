package com.example.ui.screens.driver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.localization.AppLanguage
import com.example.core.localization.AppStrings
import com.example.core.qr.QrValidationResult
import com.example.data.entity.RouteStopEntity
import com.example.ui.components.MiniVehicleMap
import com.example.ui.components.PassoBrandHeader
import com.example.ui.theme.*
import com.example.ui.viewmodel.DriverTripState
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.NavigationStepState
import com.example.ui.viewmodel.NetworkStatus

private fun <T> driverBody(response: retrofit2.Response<T>): T {
    if (!response.isSuccessful) {
        val error = response.errorBody()?.string().orEmpty()
        val message = runCatching { org.json.JSONObject(error).optString("error") }.getOrNull()
        throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "The request failed. Refresh and try again.")
    }
    return response.body() ?: throw IllegalStateException("The server returned an empty response.")
}

@Composable
fun DriverTripScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val currentUser by viewModel.currentUser.collectAsState()
    val api = remember { com.example.data.api.ApiClient.createFeatureService(com.example.data.api.DriverOperationsApi::class.java) }
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf("Home") }
    var documentsOpen by remember { mutableStateOf(false) }
    var vehicles by remember { mutableStateOf(emptyList<com.example.data.api.DriverVehicleAssignment>()) }
    var journeys by remember { mutableStateOf(emptyList<com.example.data.api.DriverIntercityDeparture>()) }
    var activeTrip by remember { mutableStateOf<com.example.data.api.TripDto?>(null) }
    var selectedKey by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("OUTBOUND") }
    var roster by remember { mutableStateOf(emptyList<com.example.data.api.DriverRosterPassenger>()) }
    var statusFilter by remember { mutableStateOf("ALL") }
    var freshFix by remember { mutableStateOf<com.example.data.api.DriverGpsFix?>(null) }
    var gpsStatus by remember { mutableStateOf("Waiting for a precise GPS fix") }
    var validation by remember { mutableStateOf<com.example.data.api.DriverDepartureValidation?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scanOpen by remember { mutableStateOf(false) }
    var scanResult by remember { mutableStateOf<QrValidationResult?>(null) }
    var boardingNotice by remember { mutableStateOf<String?>(null) }
    var rosterVersion by remember { mutableIntStateOf(0) }
    val vehicle = vehicles.firstOrNull { selectedKey == "commute:${it.id}" }
    val journey = journeys.firstOrNull { selectedKey == "journey:${it.id}" }
    val commuteTrip = activeTrip?.takeIf { it.vehicleId == vehicle?.id }
    val boardingEnabled = commuteTrip != null || journey?.status == "BOARDING"

    suspend fun refreshAssignments() {
        val assignments = driverBody(api.assignments())
        vehicles = assignments.vehicles
        activeTrip = driverBody(api.activeTrip()).trip
        journeys = driverBody(api.departures()).departures
        val possibleKeys = vehicles.map { "commute:${it.id}" } + journeys.map { "journey:${it.id}" }
        if (selectedKey !in possibleKeys) {
            selectedKey = activeTrip?.let { "commute:${it.vehicleId}" }?.takeIf { it in possibleKeys }
                ?: possibleKeys.firstOrNull().orEmpty()
        }
        rosterVersion++
    }
    fun runAction(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { notice = error.message ?: "Could not connect. Try again." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            try { refreshAssignments() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { notice = error.message ?: "Could not load driver assignments." }
            kotlinx.coroutines.delay(20000)
        }
    }
    LaunchedEffect(selectedKey, rosterVersion) {
        roster = emptyList()
        validation = null
        try {
            roster = when {
                vehicle != null -> driverBody(api.commuteRoster(vehicle.id)).roster
                journey != null -> driverBody(api.intercityRoster(journey.id)).roster
                else -> emptyList()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { notice = error.message ?: "Could not load passenger roster." }
    }
    if (documentsOpen) {
        com.example.ui.screens.profile.ProfileCompletionScreen(role = "DRIVER", onBack = { documentsOpen = false })
        return
    }
    Box(modifier.fillMaxSize()) {
        DriverLocationReporter(
            viewModel,
            commuteTrip?.currentStop ?: journey?.takeIf { it.status == "BOARDING" }?.origin.orEmpty(),
            { gpsStatus = it },
            onFix = { freshFix = it },
            uploadEnabled = activeTrip != null || journeys.any { it.status == "BOARDING" }
        )
        Column(Modifier.fillMaxSize()) {
            PassoBrandHeader(
                portalLabel = "DRIVER PORTAL",
                subtitle = "Tap. Move. Clear.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Let's get moving", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(currentUser?.fullName ?: "Driver", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { runAction { refreshAssignments(); notice = "Assignments and roster refreshed." } }, enabled = !busy) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh assignments")
                }
            }
            ScrollableTabRow(listOf("Home", "My Trips", "Passengers", "More").indexOf(section).coerceAtLeast(0), edgePadding = 16.dp) {
                listOf("Home", "My Trips", "Passengers", "More").forEach { label ->
                    Tab(selected = section == label, onClick = { section = label }, text = { Text(label) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                notice?.let { message -> item {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { notice = null }) { Icon(Icons.Default.Close, "Dismiss message") }
                        }
                    }
                } }
                if (section == "More") {
                    item { DriverMenuRow("Documents & vehicle", "Upload licence, registration, insurance and photo", Icons.Default.Description) { documentsOpen = true } }
                    item { DriverSectionCard("Emergency contact", "Keep your details up to date", Icons.Default.ContactPhone) {
                        Text("Add your emergency contact name and phone in Documents & vehicle after signing in.")
                        OutlinedButton(onClick = { documentsOpen = true }) { Text("Update registration") }
                    } }
                } else {
                    item {
                        DriverSectionCard("Your assignments", "Only your assigned vehicles and journeys", Icons.Default.Route) {
                            if (vehicles.isEmpty() && journeys.isEmpty()) Text("No assignment yet. Your administrator or transport company will assign a route and vehicle.", color = Slate600)
                            vehicles.forEach { assignment ->
                                FilterChip(selected = selectedKey == "commute:${assignment.id}", onClick = { selectedKey = "commute:${assignment.id}" }, label = { Text("Commute · ${assignment.routeName} · ${assignment.plateNumber}") })
                            }
                            journeys.forEach { assignment ->
                                FilterChip(selected = selectedKey == "journey:${assignment.id}", onClick = { selectedKey = "journey:${assignment.id}" }, label = { Text("Intercity · ${assignment.origin} → ${assignment.destination}") })
                            }
                        }
                    }
                    if (section != "Passengers" && selectedKey.isNotBlank()) {
                        item {
                            DriverSectionCard(vehicle?.routeName ?: "${journey?.origin.orEmpty()} → ${journey?.destination.orEmpty()}", if (journey != null) "Intercity journey" else "Daily commute", Icons.Default.DirectionsBus) {
                                Text(if (vehicle != null) "${vehicle.plateNumber} · ${vehicle.vehicleType.replace('_', ' ')}" else journey?.status.orEmpty().replace('_', ' '), fontWeight = FontWeight.SemiBold)
                                if (vehicle != null && commuteTrip == null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        FilterChip(direction == "OUTBOUND", { direction = "OUTBOUND" }, label = { Text("Outbound") })
                                        FilterChip(direction == "INBOUND", { direction = "INBOUND" }, label = { Text("Return") })
                                    }
                                }
                                val scheduled = if (vehicle != null) { if (direction == "OUTBOUND") vehicle.morningDeparture else vehicle.eveningDeparture } else journey?.departure_at.orEmpty()
                                Text("Departure: ${driverLocalTime(scheduled)} · Addis Ababa time", color = Slate600)
                                Surface(color = TransportGold.copy(alpha = .14f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                    Text(departureReadinessNotice(scheduled), modifier = Modifier.padding(10.dp), color = Slate900, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Confirm arrival within 30 minutes before or after departure, inside the configured radius.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                                Text(gpsStatus, style = MaterialTheme.typography.bodySmall, color = Slate600)
                                validation?.let { gate ->
                                    Text(gate.message, color = if (gate.allowed) TransportGreenPrimary else MaterialTheme.colorScheme.error)
                                    gate.distanceMeters?.let { Text("Distance ${it.toInt()} m · Allowed radius ${gate.radiusMeters} m", style = MaterialTheme.typography.bodySmall) }
                                }
                                if (boardingEnabled) {
                                    Surface(color = TransportGreenPrimary.copy(alpha = .12f), shape = RoundedCornerShape(10.dp)) {
                                        Text("Arrival confirmed · Boarding open", Modifier.padding(12.dp), color = TransportGreenPrimary, fontWeight = FontWeight.Bold)
                                    }
                                    Button(onClick = { scanResult = null; boardingNotice = null; scanOpen = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                        Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan passenger ticket")
                                    }
                                    OutlinedButton(onClick = { runAction {
                                        if (commuteTrip != null) {
                                            val result = driverBody(api.completeCommute(commuteTrip.id))
                                            check(result.success) { result.error ?: "Route could not be completed." }
                                        } else if (journey != null) {
                                            val result = driverBody(api.completeIntercity(journey.id))
                                            check(result.success) { result.error ?: "Journey could not be completed." }
                                        }
                                        notice = "Route completed. Boarding is now closed."
                                        refreshAssignments()
                                    } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Complete route") }
                                    Text("You can complete the route without a destination GPS check.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                                } else if (journey?.status != "COMPLETED") {
                                    if (vehicle != null) OutlinedButton(onClick = { runAction {
                                        validation = driverBody(api.checkDeparture(com.example.data.api.DriverDepartureRequest(vehicle.assignedRouteId, vehicle.id, direction, freshFix))).validation
                                    } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Check departure readiness") }
                                    Button(onClick = { runAction {
                                        if (vehicle != null) {
                                            val result = driverBody(api.confirmCommuteArrival(com.example.data.api.DriverDepartureRequest(vehicle.assignedRouteId, vehicle.id, direction, freshFix)))
                                            check(result.success) { result.error ?: "Could not confirm arrival." }
                                            activeTrip = result.trip
                                        } else if (journey != null) {
                                            val result = driverBody(api.confirmIntercityArrival(journey.id, com.example.data.api.DriverArrivalRequest(freshFix)))
                                            check(result.success) { result.error ?: "Could not confirm arrival." }
                                        }
                                        notice = "Departure confirmed. You can now board passengers."
                                        refreshAssignments()
                                    } }, enabled = !busy && freshFix != null, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Confirm arrival & open boarding") }
                                }
                            }
                        }
                    }
                    if (section == "Passengers" || section == "Home") {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Passenger roster", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("${roster.count { it.status == "BOARDED" }} boarded · ${roster.size} assigned", color = Slate600)
                                }
                                IconButton(onClick = { rosterVersion++ }) { Icon(Icons.Default.Refresh, "Refresh passenger roster") }
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("ALL", "ASSIGNED", "BOARDED", "PENDING", "INACTIVE").forEach { status ->
                                    FilterChip(statusFilter == status, { statusFilter = status }, label = { Text(status.lowercase().replaceFirstChar { it.uppercase() }) })
                                }
                            }
                        }
                        val visibleRoster = roster.filter { statusFilter == "ALL" || it.status == statusFilter }
                        if (visibleRoster.isEmpty()) item { Text(if (roster.isEmpty()) "No passengers are assigned to this vehicle or journey yet." else "No passengers with this status.", color = Slate600) }
                        items(visibleRoster, key = { it.id }) { passenger ->
                            DriverPassengerRosterRow(passenger)
                        }
                    }
                }
            }
        }
        if (scanOpen) {
            val scannerState = DriverTripState(tripId = commuteTrip?.id ?: journey?.id.orEmpty(), vehicleId = vehicle?.id ?: journey?.vehicle_id.orEmpty(), routeId = vehicle?.assignedRouteId.orEmpty(), vehiclePlate = vehicle?.plateNumber ?: journey?.plateNumber.orEmpty(), vehicleCapacity = vehicle?.capacityLimit ?: journey?.seats ?: 0, checkedInCount = roster.count { it.status == "BOARDED" })
            DriverQrScannerDialog(scanResult, commuteTrip?.currentStop ?: journey?.origin.orEmpty(), scannerState,
                onScanToken = { token ->
                    scope.launch {
                        try {
                            if (commuteTrip != null) {
                                val result = driverBody(api.boardCommute(com.example.data.api.ScanCheckinRequest(qrToken = token, tripId = commuteTrip.id, vehicleId = commuteTrip.vehicleId, currentStop = commuteTrip.currentStop.orEmpty())))
                                check(result.success) { result.error ?: "Passenger could not be boarded." }
                                boardingNotice = "${result.passenger?.name ?: "Passenger"} boarded successfully."
                            } else if (journey != null) {
                                val result = driverBody(api.boardIntercity(journey.id, com.example.data.api.DriverBoardRequest(token)))
                                check(result.success) { result.error ?: "Passenger could not be boarded." }
                                boardingNotice = "${result.booking?.passengerName ?: "Passenger"} boarded successfully."
                            } else throw IllegalStateException("Confirm departure before scanning.")
                            rosterVersion++
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (error: Exception) { scanResult = QrValidationResult.Invalid("Boarding declined", error.message.orEmpty()) }
                    }
                }, onDismissResult = { scanResult = null; boardingNotice = null }, onCloseScanner = { scanOpen = false }, boardingNotice = boardingNotice)
        }
        NavigationBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            containerColor = Color(0xFF102F4D),
            contentColor = Color.White,
            tonalElevation = 8.dp
        ) {
            listOf(
                "Home" to Icons.Default.Home,
                "Passengers" to Icons.Default.People,
                "Documents" to Icons.Default.Description,
                "More" to Icons.Default.MoreHoriz
            ).forEach { (label, icon) ->
                val selected = when (label) {
                    "Home" -> section == "Home" || section == "My Trips"
                    "Passengers" -> section == "Passengers"
                    "Documents" -> documentsOpen
                    else -> section == "More"
                }
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        when (label) {
                            "Documents" -> documentsOpen = true
                            else -> section = label
                        }
                    },
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(if (label == "Home") "Today" else label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF38D9B0),
                        selectedTextColor = Color(0xFF38D9B0),
                        unselectedIconColor = Color.White.copy(alpha = .82f),
                        unselectedTextColor = Color.White.copy(alpha = .82f),
                        indicatorColor = Color.White.copy(alpha = .12f)
                    )
                )
            }
        }
    }
}

private fun departureReadinessNotice(value: String): String = runCatching {
    val parts = value.trim().split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull() ?: return@runCatching "Start boarding opens within 30 minutes of departure when your GPS is inside the departure area."
    val minute = parts.getOrNull(1)?.take(2)?.toIntOrNull() ?: 0
    val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Addis_Ababa"))
    val departure = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    val minutes = java.time.Duration.between(now, departure).toMinutes()
    when {
        minutes > 30 -> "Boarding opens in about $minutes minutes. Move to the departure area before confirming arrival."
        minutes >= -30 -> "Boarding window is open. Confirm arrival only inside the configured GPS radius."
        else -> "The scheduled departure window has passed. Contact your administrator before starting boarding."
    }
}.getOrElse { "Start boarding opens within 30 minutes of departure when your GPS is inside the departure area." }

private fun driverLocalTime(value: String): String = runCatching {
    val parser = java.text.SimpleDateFormat(if (value.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSSX" else "yyyy-MM-dd'T'HH:mm:ssX", java.util.Locale.US)
    val date = parser.parse(value) ?: return@runCatching value
    java.text.SimpleDateFormat("EEE, dd MMM · HH:mm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Africa/Addis_Ababa") }.format(date)
}.getOrDefault(value.ifBlank { "Not configured" })

@Composable
private fun DriverPassengerRosterRow(passenger: com.example.data.api.DriverRosterPassenger) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val personId = passenger.passengerId
            if (!personId.isNullOrBlank()) com.example.ui.screens.profile.AuthenticatedProfilePhoto(personId, Modifier.size(54.dp).clip(CircleShape))
            else Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(54.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null) }
            }
            Column(Modifier.weight(1f)) {
                Text(passenger.passengerName, fontWeight = FontWeight.Bold)
                Text(passenger.passengerPhone, color = Slate600, style = MaterialTheme.typography.bodySmall)
                val details = passenger.seatNumber?.let { "Seat $it" } ?: passenger.pickupStop
                if (details.isNotBlank()) Text(details, color = Slate600, style = MaterialTheme.typography.bodySmall)
            }
            val statusColor = if (passenger.status == "BOARDED") TransportGreenPrimary else if (passenger.status == "INACTIVE") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(color = statusColor.copy(alpha = .1f), shape = RoundedCornerShape(8.dp)) {
                Text(passenger.status.lowercase().replaceFirstChar { it.uppercase() }, Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = statusColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Requests foreground location permission and reports fresh phone GPS fixes to the VPS.
 * Reporting runs only while the driver screen is open; background tracking is not claimed.
 */
@Composable
private fun DriverLocationReporter(
    viewModel: MainViewModel,
    currentStop: String,
    onStatusChanged: (String) -> Unit,
    onFix: (com.example.data.api.DriverGpsFix) -> Unit = {},
    uploadEnabled: Boolean = false
) {
    val context = LocalContext.current
    val locationManager = remember(context) {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    val latestStop = rememberUpdatedState(currentStop)
    val latestUploadEnabled = rememberUpdatedState(uploadEnabled)
    val latestOnFix = rememberUpdatedState(onFix)
    val latestOnStatusChanged = rememberUpdatedState(onStatusChanged)
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasPermission = grants.values.any { it }
        onStatusChanged(if (hasPermission) "Location permission granted. Waiting for GPS fix…" else "Location permission was denied. Allow location to share live vehicle position.")
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    DisposableEffect(hasPermission, context) {
        if (!hasPermission) {
            onStatusChanged("Allow location permission to share live vehicle position.")
            onDispose { }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (location.isFromMockProvider) {
                        latestOnStatusChanged.value("Use your phone's precise GPS location to confirm departure.")
                        return
                    }
                    // A last-known fix may be hours old. Do not stamp it as a new
                    // live server report when the screen first opens.
                    if (System.currentTimeMillis() - location.time !in 0L..120000L) return
                    if (location.hasAccuracy()) {
                        latestOnFix.value(com.example.data.api.DriverGpsFix(location.latitude, location.longitude, location.accuracy.toDouble(), location.time))
                    }
                    val coordinates = String.format(java.util.Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
                    if (!latestUploadEnabled.value) {
                        latestOnStatusChanged.value("GPS ready · accuracy ${location.accuracy.toInt()} m · $coordinates")
                        return
                    }
                    latestOnStatusChanged.value("Phone GPS fix received; uploading to RoutePass server… · $coordinates")
                    viewModel.submitDriverLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        speed = (location.speed.toDouble() * 3.6).coerceAtLeast(0.0),
                        currentStop = latestStop.value,
                        onResult = { accepted, message ->
                            latestOnStatusChanged.value(
                                if (accepted) "GPS uploaded successfully to RoutePass server · $coordinates"
                                else "Phone GPS works, but server upload failed: $message · $coordinates"
                            )
                        }
                    )
                }
            }
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }
            if (providers.isEmpty()) {
                onStatusChanged("Turn on Location on your phone to share the vehicle position.")
            } else {
                providers.forEach { provider ->
                    try {
                        locationManager.requestLocationUpdates(provider, 5000L, 0f, listener, Looper.getMainLooper())
                        locationManager.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
                    } catch (_: SecurityException) {
                        onStatusChanged("Location access was denied. Re-enable permission in Android settings.")
                    } catch (_: IllegalArgumentException) {
                        // This provider is not available on the device.
                    }
                }
            }
            onDispose {
                runCatching { locationManager.removeUpdates(listener) }
            }
        }
    }
}

@Composable
private fun DriverSectionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(10.dp), color = TransportGreenPrimary.copy(alpha = 0.12f)) {
                    Icon(icon, contentDescription = null, tint = TransportGreenPrimary, modifier = Modifier.padding(10.dp).size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Slate900)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Slate600)
                }
            }
            content()
        }
    }
}

@Composable
private fun DriverMenuRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = TransportGreenPrimary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Slate900)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Slate600)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Open", tint = Slate600)
        }
    }
}

@Composable
private fun DriverHomeAction(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(12.dp), color = Slate100, border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = TransportGreenPrimary, modifier = Modifier.size(22.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Slate900)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Slate600)
        }
    }
}
@Composable
fun DriverCockpitHeaderCard(
    driverName: String,
    vehiclePlate: String,
    routeName: String,
    tripState: DriverTripState,
    networkStatus: NetworkStatus,
    lang: AppLanguage,
    onToggleNetwork: () -> Unit,
    onSetVehicleType: (String, Int) -> Unit = { _, _ -> }
) {
    fun t(key: String) = AppStrings.get(key, lang)

    val isFull = tripState.vehicleCapacity > 0 && tripState.checkedInCount >= tripState.vehicleCapacity
    val fillFraction = if (tripState.vehicleCapacity > 0) (tripState.checkedInCount.toFloat() / tripState.vehicleCapacity.toFloat()).coerceIn(0f, 1f) else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("driver_cockpit_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = driverName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "${tripState.vehicleType} • Plate: ${vehiclePlate.ifBlank { "Not assigned" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TransportGold
                    )
                }

                // Interactive Network Mode Switcher
                Surface(
                    color = when (networkStatus) {
                        NetworkStatus.ONLINE -> StatusActiveGreen.copy(alpha = 0.2f)
                        NetworkStatus.OFFLINE -> StatusWarningOrange.copy(alpha = 0.2f)
                        NetworkStatus.SYNCING -> StatusInfoBlue.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        when (networkStatus) {
                            NetworkStatus.ONLINE -> StatusActiveGreen
                            NetworkStatus.OFFLINE -> StatusWarningOrange
                            NetworkStatus.SYNCING -> StatusInfoBlue
                        }
                    ),
                    modifier = Modifier.clickable { onToggleNetwork() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (networkStatus) {
                                        NetworkStatus.ONLINE -> StatusActiveGreen
                                        NetworkStatus.OFFLINE -> StatusWarningOrange
                                        NetworkStatus.SYNCING -> StatusInfoBlue
                                    }
                                )
                        )
                        Text(
                            text = when (networkStatus) {
                                NetworkStatus.ONLINE -> t("online_status")
                                NetworkStatus.OFFLINE -> t("offline_status")
                                NetworkStatus.SYNCING -> t("syncing_status")
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Vehicle class and seating limit are read from the vehicle that the driver registered.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Slate800)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Registered vehicle class",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate400,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (tripState.vehicleCapacity > 0) tripState.vehicleType.replace('_', ' ') else "Awaiting company vehicle assignment",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (tripState.vehicleCapacity > 0) "Maximum ${tripState.vehicleCapacity} seats" else "Capacity pending",
                        style = MaterialTheme.typography.labelMedium,
                        color = TransportGold,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Vehicle ownership and capacity cannot be changed from the trip screen.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate400
                )
            }

            HorizontalDivider(color = Slate800)

            // Trips and Passenger Limit Meter
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = t("todays_trips"), style = MaterialTheme.typography.labelSmall, color = Slate400)
                    Text(text = if (tripState.routeId.isBlank() || tripState.vehicleId.isBlank()) "No trip assigned" else "Scheduled trip (${tripState.departureTime})", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = TransportGold)
                    Text(text = routeName, style = MaterialTheme.typography.bodySmall, color = Color.White)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (lang == AppLanguage.AMHARIC) "የተሳፈሩ / ገደብ" else "Boarded / Capacity Limit",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate400
                    )
                    Text(
                        text = if (tripState.vehicleCapacity > 0) "${tripState.checkedInCount} / ${tripState.vehicleCapacity}" else "Not assigned",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isFull) StatusErrorRed else StatusActiveGreen
                    )
                    Text(
                        text = if (isFull)
                            "0 seats available (FULL)"
                        else
                            if (tripState.vehicleCapacity > 0) "${tripState.vehicleCapacity - tripState.checkedInCount} seats available" else "Vehicle assignment pending",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isFull) StatusErrorRed else TransportGold,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Visual Capacity Limit Progress Bar
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { fillFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (isFull) StatusErrorRed else if (fillFraction > 0.8f) StatusWarningOrange else StatusActiveGreen,
                    trackColor = Slate800,
                )

                if (isFull) {
                    Surface(
                        color = StatusErrorRed.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusErrorRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, tint = StatusErrorRed, modifier = Modifier.size(16.dp))
                            Text(
                                text = if (lang == AppLanguage.AMHARIC)
                                    "የተሽከርካሪው የመንገደኛ ገደብ ሞልቷል! ለዚህ ተሽከርካሪ ዓይነት ተጨማሪ መንገደኛ መጫን አይቻልም።"
                                else
                                    "PASSENGER LIMIT REACHED: Vehicle is full for this vehicle type (${tripState.vehicleCapacity} max seats).",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DriverNavigationHudCard(
    tripState: DriverTripState,
    currentStop: RouteStopEntity?,
    totalStops: Int = 0,
    lang: AppLanguage,
    onStartNavigation: () -> Unit,
    onArrived: () -> Unit,
    onScanQr: () -> Unit,
    onSkip: () -> Unit,
    onNextStop: () -> Unit,
    isLastStop: Boolean = false
) {
    fun t(key: String) = AppStrings.get(key, lang)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("driver_navigation_hud_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = TransportGreenPrimary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "NEXT PICKUP STOP #${tripState.currentStopIndex + 1}",
                        color = TransportGreenPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    color = if (tripState.isArrivedAtStop) StatusActiveGreen else TransportGold,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (tripState.isArrivedAtStop) { if (tripState.currentStopIndex == 0) "AT DEPARTURE" else "ARRIVED AT STOP" } else "EN ROUTE",
                        color = if (tripState.isArrivedAtStop) Color.White else Slate900,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Target Stop Name & Scheduled Time
            Column {
                Text(
                    text = currentStop?.stopName ?: "No assigned pickup",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Scheduled Pickup: ${currentStop?.scheduledMorningTime ?: "Not assigned"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate600
                )
            }

            // Big Telemetry Gauges (Distance, ETA, Passengers Waiting)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Slate100)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "DISTANCE", style = MaterialTheme.typography.labelSmall, color = Slate600)
                    Text(
                        text = if (tripState.isArrivedAtStop) "0.0 km" else "${tripState.currentDistanceKm} km",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TransportGreenPrimary
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "ETA", style = MaterialTheme.typography.labelSmall, color = Slate600)
                    Text(
                        text = if (tripState.isArrivedAtStop) "0 min" else "${tripState.currentEtaMins} min",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB45309)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "GROUP PASSENGERS", style = MaterialTheme.typography.labelSmall, color = Slate600)
                    Text(
                        text = currentStop?.maxCapacity?.let { "$it Persons" } ?: "Not assigned",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                }
            }

            // Big Hands-Free / Driver-Safe Action Buttons
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!tripState.isArrivedAtStop) {
                    Button(
                        onClick = onArrived,
                        enabled = currentStop != null && tripState.routeId.isNotBlank() && tripState.vehicleId.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
                        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("driver_arrived_button")
                    ) {
                        Icon(Icons.Default.PinDrop, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (currentStop == null || tripState.routeId.isBlank() || tripState.vehicleId.isBlank()) "WAITING FOR ASSIGNMENT" else if (tripState.currentStopIndex == 0) "ARRIVE AT DEPARTURE" else "ARRIVE AT STOP",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = onScanQr,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TelebirrBlue),
                        modifier = Modifier.fillMaxWidth().height(56.dp).testTag("driver_scan_qr_button")
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "SCAN PASSENGERS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onSkip,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(48.dp).testTag("driver_skip_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusExpiredRed)
                        ) {
                            Text(text = t("skip_noshow"), fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onNextStop,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TransportGreenDark),
                            modifier = Modifier.weight(1f).height(48.dp).testTag("driver_next_stop_button")
                        ) {
                            Text(text = if (isLastStop) "COMPLETE ROUTE" else "NEXT STOP", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StopSequenceRow(
    index: Int,
    stop: RouteStopEntity,
    isCurrent: Boolean,
    stepState: NavigationStepState,
    onScanClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stop_row_$index"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) TransportGreenPrimary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        border = if (isCurrent) androidx.compose.foundation.BorderStroke(2.dp, TransportGreenPrimary) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = when (stepState) {
                        NavigationStepState.COMPLETED -> StatusActiveGreen
                        NavigationStepState.SKIPPED -> StatusExpiredRed
                        else -> if (isCurrent) TransportGreenPrimary else Slate400
                    },
                    shape = CircleShape,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (stepState == NavigationStepState.COMPLETED) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        } else {
                            Text(
                                text = "${index + 1}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                Column {
                    Text(
                        text = stop.stopName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                    )
                    Text(
                        text = "ETA: ${stop.scheduledMorningTime} • ${stop.maxCapacity} passengers",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600
                    )
                }
            }

            if (isCurrent) {
                Button(
                    onClick = onScanClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TelebirrBlue),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("row_scan_button_$index")
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(text = "Scan", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// Real camera QR scanner with server-side verification results.
@Composable
fun DriverQrScannerDialog(
    scanResult: QrValidationResult?,
    currentStop: String,
    tripState: DriverTripState,
    onScanToken: (String) -> Unit,
    onDismissResult: () -> Unit,
    onCloseScanner: () -> Unit,
    boardingNotice: String? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewRef = remember { mutableStateOf<PreviewView?>(null) }
    val scanSubmitted = remember { AtomicBoolean(false) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(scanner, cameraExecutor) {
        onDispose {
            runCatching { scanner.close() }
            cameraExecutor.shutdown()
        }
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasScanned by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) cameraError = "Camera permission is required to scan passenger QR passes."
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(previewRef.value, hasCameraPermission, hasScanned, lifecycleOwner) {
        val previewView = previewRef.value
        if (!hasCameraPermission || hasScanned || previewView == null || scanResult != null) {
            onDispose { }
        } else {
            var disposed = false
            var boundProvider: ProcessCameraProvider? = null
            var boundPreview: Preview? = null
            var boundAnalysis: ImageAnalysis? = null
            val providerFuture = ProcessCameraProvider.getInstance(context)

            providerFuture.addListener({
                if (!disposed) {
                    try {
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage == null || scanSubmitted.get()) {
                                imageProxy.close()
                            } else {
                                val inputImage = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                scanner.process(inputImage)
                                    .addOnSuccessListener { barcodes ->
                                        val decoded = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                                        if (!decoded.isNullOrBlank() && scanSubmitted.compareAndSet(false, true)) {
                                            hasScanned = true
                                            cameraError = null
                                            onScanToken(decoded)
                                        }
                                    }
                                    .addOnFailureListener { error ->
                                        cameraError = "Could not read QR image: " + (error.localizedMessage ?: "try again")
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                        boundProvider = provider
                        boundPreview = preview
                        boundAnalysis = analysis
                        cameraError = null
                    } catch (error: Exception) {
                        cameraError = "Could not start camera: " + (error.localizedMessage ?: "camera unavailable")
                    }
                }
            }, ContextCompat.getMainExecutor(context))

            onDispose {
                disposed = true
                try {
                    if (boundProvider != null) {
                        boundPreview?.let { boundProvider?.unbind(it) }
                        boundAnalysis?.let { boundProvider?.unbind(it) }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onCloseScanner,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("driver_qr_scanner_dialog"),
            color = Color(0xFF101820)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "SCAN PASSENGER QR",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "Stop: $currentStop · Vehicle: " + tripState.vehiclePlate.ifBlank { "Not registered" },
                            color = TransportGold,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Trip: " + if (tripState.tripId.isBlank()) "Not started" else tripState.tripId,
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    IconButton(onClick = onCloseScanner, modifier = Modifier.testTag("close_scanner_button")) {
                        Icon(Icons.Default.Close, contentDescription = "Close scanner", tint = Color.White)
                    }
                }

                if (tripState.checkedInCount >= tripState.vehicleCapacity) {
                    Surface(color = StatusErrorRed.copy(alpha = 0.2f), shape = RoundedCornerShape(10.dp)) {
                        Text(
                            "Vehicle capacity reached (${tripState.checkedInCount}/${tripState.vehicleCapacity}). The server will reject further boarding.",
                            color = Color.White,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (boardingNotice != null) {
                    Surface(color = TransportGreenPrimary.copy(alpha = .15f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CheckCircle, null, tint = TransportGreenPrimary, modifier = Modifier.size(64.dp))
                            Text("BOARDING CONFIRMED", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            Text(boardingNotice, color = Color.White)
                        }
                    }
                    Button(onClick = { onDismissResult(); scanSubmitted.set(false); hasScanned = false }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("SCAN NEXT PASSENGER") }
                } else when (val result = scanResult) {
                    is QrValidationResult.Valid -> {
                        Surface(
                            color = StatusActiveGreen.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusActiveGreen, modifier = Modifier.size(54.dp))
                                Text("PASSENGER VERIFIED", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                                Text(result.passenger.fullName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                Text("Subscription: " + result.subscription.subscriptionStatus, color = Color.White)
                                Text("Occupancy: ${result.currentOccupancy}/${result.capacityLimit}", color = Color.White)
                                Text("Verified by RoutePass server.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(
                            onClick = {
                                onDismissResult()
                                scanSubmitted.set(false)
                                hasScanned = false
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) { Text("SCAN NEXT PASSENGER") }
                    }
                    is QrValidationResult.Invalid -> {
                        Surface(
                            color = StatusExpiredRed.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusExpiredRed, modifier = Modifier.size(54.dp))
                                Text("PASS NOT ACCEPTED", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                                Text(AppStrings.get(result.reason, AppLanguage.ENGLISH), color = StatusExpiredRed, style = MaterialTheme.typography.titleMedium)
                                if (result.details.isNotBlank()) {
                                    Text(result.details, color = Color.White, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Button(
                            onClick = {
                                onDismissResult()
                                scanSubmitted.set(false)
                                hasScanned = false
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) { Text("SCAN AGAIN") }
                    }
                    null -> {
                        if (!hasCameraPermission) {
                            Column(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
                                Text("Allow camera access to scan a passenger's live QR pass.", color = Color.White, textAlign = TextAlign.Center)
                                Button(onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                    Text("Allow camera")
                                }
                            }
                        } else if (hasScanned) {
                            Column(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = TransportGold)
                                Spacer(Modifier.height(12.dp))
                                Text("Verifying QR with the RoutePass server…", color = Color.White, textAlign = TextAlign.Center)
                                Text("If verification fails, boarding is not accepted.", color = Color.LightGray, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                AndroidView(
                                    factory = { ctx ->
                                        PreviewView(ctx).apply {
                                            scaleType = PreviewView.ScaleType.FILL_CENTER
                                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                            previewRef.value = this
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp))
                                )
                                Text(
                                    "Hold the passenger's RoutePass QR steady inside the camera view. Demo/example codes are not accepted.",
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        cameraError?.let {
                            Text(it, color = StatusExpiredRed, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                OutlinedButton(
                    onClick = onCloseScanner,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Close scanner")
                }
            }
        }
    }
}
