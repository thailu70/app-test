package com.example.ui.screens.driver

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
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
import com.example.ui.components.PrivateMediaUploadButton
import com.example.ui.components.MiniVehicleMap
import com.example.ui.theme.*
import com.example.ui.viewmodel.DriverTripState
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.NavigationStepState
import com.example.ui.viewmodel.NetworkStatus

@Composable
fun DriverTripScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val lang by viewModel.currentLanguage.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val tripState by viewModel.driverTrip.collectAsState()
    val stops by viewModel.routeStops.collectAsState()
    val trackedVehicle by viewModel.trackedVehicle.collectAsState()
    val trackingMessage by viewModel.trackingMessage.collectAsState()
    val driverActionMessage by viewModel.driverActionMessage.collectAsState()
    var gpsStatus by remember { mutableStateOf("Waiting for GPS permission.") }
    var routeDirection by remember { mutableStateOf("OUTBOUND") }
    var showDepartureConfirmation by remember { mutableStateOf(false) }
    val networkStatus by viewModel.networkStatus.collectAsState()
    val isScannerOpen by viewModel.isScannerOpen.collectAsState()
    val scanResult by viewModel.scanResult.collectAsState()
    val myRoster by viewModel.myRoster.collectAsState()
    val mediaUploadMessage by viewModel.mediaUploadMessage.collectAsState()
    val driverPhotoBytes by viewModel.assignedDriverPhotoBytes.collectAsState()
    val driverPhotoBitmap = remember(driverPhotoBytes) { driverPhotoBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    LaunchedEffect(currentUser?.id) { if (currentUser != null) viewModel.refreshMyRoster() }
    val rosterPassengers = myRoster?.get("passengers") as? List<*> ?: emptyList<Any>()

    fun t(key: String) = AppStrings.get(key, lang)

    Box(modifier = modifier.fillMaxSize()) {
        DriverLocationReporter(
            viewModel = viewModel,
            currentStop = stops.getOrNull(tripState.currentStopIndex)?.stopName.orEmpty(),
            onStatusChanged = { gpsStatus = it }
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp)
        ) {
            // 1. Driver Console Cockpit Card
            item {
                DriverCockpitHeaderCard(
                    driverName = currentUser?.fullName ?: "Driver",
                    vehiclePlate = currentUser?.assignedVehiclePlate?.ifBlank { tripState.vehiclePlate } ?: tripState.vehiclePlate,
                    routeName = currentUser?.appliedRouteName?.ifBlank { tripState.routeName } ?: tripState.routeName,
                    tripState = tripState,
                    networkStatus = networkStatus,
                    lang = lang,
                    onToggleNetwork = { viewModel.toggleNetworkMode() },
                    onSetVehicleType = { type, cap -> viewModel.setVehicleType(type, cap) }
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (driverPhotoBitmap != null) {
                                Image(
                                    bitmap = driverPhotoBitmap.asImageBitmap(),
                                    contentDescription = "Driver profile photo",
                                    modifier = Modifier.size(76.dp).clip(CircleShape)
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(76.dp).clip(CircleShape).background(Slate200),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(currentUser?.fullName?.split(" ")?.mapNotNull { it.firstOrNull() }?.take(2)?.joinToString("") ?: "DR",
                                        fontWeight = FontWeight.Bold, color = Slate700)
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Driver profile", fontWeight = FontWeight.Bold)
                                Text(if (driverPhotoBitmap != null) "Profile photo loaded" else "Upload your photo below; it will appear here after refresh.",
                                    style = MaterialTheme.typography.bodySmall, color = Slate600)
                            }
                        }
                        Text("Driver and vehicle documents", fontWeight = FontWeight.Bold)
                        Text("Upload clear, current documents. Each file must be 5 MB or smaller.")
                        PrivateMediaUploadButton(viewModel, "DRIVER_PROFILE_PHOTO", "Upload driver profile photo", imagesOnly = true)
                        PrivateMediaUploadButton(viewModel, "DRIVER_LICENSE", "Upload driver's licence")
                        PrivateMediaUploadButton(viewModel, "VEHICLE_PHOTO", "Upload vehicle photo", imagesOnly = true)
                        PrivateMediaUploadButton(viewModel, "TRADE_LICENSE", "Upload trade licence")
                        mediaUploadMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Passenger roster & attendance", fontWeight = FontWeight.Bold)
                        Text("Attendance changes to PRESENT after a successful QR scan for this active trip.")
                        Text("Passengers: ${rosterPassengers.size}", style = MaterialTheme.typography.bodySmall)
                        val rosterMessage = myRoster?.get("rosterMessage")?.toString()
                        if (!rosterMessage.isNullOrBlank()) {
                            Text(rosterMessage, style = MaterialTheme.typography.bodySmall, color = if (rosterPassengers.isEmpty()) StatusWarningOrange else Slate600)
                        }
                        rosterPassengers.forEach { row ->
                            val passenger = row as? Map<*, *> ?: return@forEach
                            Divider()
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(passenger["fullName"]?.toString() ?: "Passenger", fontWeight = FontWeight.SemiBold)
                                    Text(passenger["attendance"]?.toString() ?: "NOT_SCANNED", style = MaterialTheme.typography.bodySmall)
                                }
                                Text(if (passenger["attendance"] == "PRESENT") "Present" else "Not scanned", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (rosterPassengers.isEmpty()) Text("No active paid passengers found for the assigned route.")
                        Button(onClick = { viewModel.refreshMyRoster() }) { Text("Refresh roster") }
                    }
                }
            }

            // 2. Actual vehicle position, reported by this driver's phone.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniVehicleMap(vehicle = trackedVehicle, modifier = Modifier.testTag("driver_live_vehicle_map"), mapHeight = 190, serverMessage = trackingMessage)
                    Text(gpsStatus, style = MaterialTheme.typography.bodySmall, color = Slate600)
                    driverActionMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = if (message.contains("not") || message.contains("failed", true) || message.contains("could not", true)) StatusErrorRed else TransportGreenPrimary)
                    }
                }
            }

            // Admin-assigned route supports both commute directions.
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Choose scheduled direction", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { routeDirection = "OUTBOUND" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (routeDirection == "OUTBOUND") TransportGreenPrimary else Slate600)) {
                            Text("Home → Work / School")
                        }
                        Button(onClick = { routeDirection = "INBOUND" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (routeDirection == "INBOUND") TransportGreenPrimary else Slate600)) {
                            Text("Work / School → Home")
                        }
                    }
                    Text(
                        "Scheduled departure: ${if (routeDirection == "INBOUND") "evening" else "morning"} schedule. Arrive at the assigned first stop on time; trip start requires GPS within 50 metres and your confirmation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600
                    )
                }
            }

            // 3. High-Visibility Big Navigation HUD
            item {
                DriverNavigationHudCard(
                    tripState = tripState,
                    currentStop = stops.getOrNull(tripState.currentStopIndex),
                    lang = lang,
                    onStartNavigation = { showDepartureConfirmation = true },
                    onArrived = { viewModel.arriveAtCurrentStop() },
                    onScanQr = { viewModel.openScanner() },
                    onSkip = { viewModel.skipCurrentStop() },
                    onNextStop = { viewModel.nextStop() }
                )
            }

            // 4. Stop Sequence & Passenger Attendance
            item {
                Text(
                    text = t("pickup_sequence"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            itemsIndexed(stops) { index, stop ->
                StopSequenceRow(
                    index = index,
                    stop = stop,
                    isCurrent = index == tripState.currentStopIndex,
                    stepState = tripState.stopStates[index] ?: if (index < tripState.currentStopIndex) NavigationStepState.COMPLETED else NavigationStepState.EN_ROUTE,
                    onScanClick = {
                        viewModel.openScanner()
                    }
                )
            }
        }

        if (showDepartureConfirmation) {
            AlertDialog(
                onDismissRequest = { showDepartureConfirmation = false },
                title = { Text("Confirm vehicle arrival") },
                text = {
                    Text("Confirm that this vehicle has arrived at the administrator-assigned departure location for the selected route. RoutePass will verify that your latest GPS fix is within 50 metres before starting the trip and notifying passengers.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDepartureConfirmation = false
                        viewModel.startNavigation(routeDirection)
                    }) { Text("CONFIRM ARRIVAL") }
                },
                dismissButton = {
                    TextButton(onClick = { showDepartureConfirmation = false }) { Text("NOT YET") }
                }
            )
        }

        // Camera-backed scanner dialog; each decoded QR is checked by the VPS.
        if (isScannerOpen) {
            DriverQrScannerDialog(
                scanResult = scanResult,
                currentStop = stops.getOrNull(tripState.currentStopIndex)?.stopName ?: "Bole Atlas",
                tripState = tripState,
                onScanToken = { token -> viewModel.verifyQrToken(token) },
                onDismissResult = { viewModel.dismissScanResult() },
                onCloseScanner = { viewModel.closeScanner() }
            )
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
    onStatusChanged: (String) -> Unit
) {
    val context = LocalContext.current
    val locationManager = remember(context) {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    val latestStop = rememberUpdatedState(currentStop)
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
                    val coordinates = String.format(java.util.Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
                    onStatusChanged("Phone GPS fix received; uploading to RoutePass server… · $coordinates")
                    viewModel.submitDriverLocation(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        speed = location.speed.toDouble().coerceAtLeast(0.0),
                        currentStop = latestStop.value,
                        onResult = { accepted, message ->
                            onStatusChanged(
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
                        locationManager.requestLocationUpdates(provider, 5000L, 5f, listener, Looper.getMainLooper())
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

    val isFull = tripState.checkedInCount >= tripState.vehicleCapacity
    val fillFraction = (tripState.checkedInCount.toFloat() / tripState.vehicleCapacity.toFloat()).coerceIn(0f, 1f)

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
                        text = "${tripState.vehicleType} • Plate: $vehiclePlate",
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
                        text = tripState.vehicleType.replace('_', ' '),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Maximum ${tripState.vehicleCapacity} seats",
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
                    Text(text = "Scheduled trip (${tripState.departureTime})", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = TransportGold)
                    Text(text = routeName, style = MaterialTheme.typography.bodySmall, color = Color.White)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (lang == AppLanguage.AMHARIC) "የተሳፈሩ / ገደብ" else "Boarded / Capacity Limit",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate400
                    )
                    Text(
                        text = "${tripState.checkedInCount} / ${tripState.vehicleCapacity}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isFull) StatusErrorRed else StatusActiveGreen
                    )
                    Text(
                        text = if (isFull)
                            "0 seats available (FULL)"
                        else
                            "${tripState.vehicleCapacity - tripState.checkedInCount} seats available",
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
    lang: AppLanguage,
    onStartNavigation: () -> Unit,
    onArrived: () -> Unit,
    onScanQr: () -> Unit,
    onSkip: () -> Unit,
    onNextStop: () -> Unit
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
                        text = if (tripState.isArrivedAtStop) "ARRIVED AT STOP" else "EN ROUTE",
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
                    text = currentStop?.stopName ?: "Bole Atlas",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Scheduled Pickup: ${currentStop?.scheduledMorningTime ?: "06:30 AM"}",
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
                        text = "${currentStop?.maxCapacity ?: 4} Persons",
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
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("driver_arrived_button")
                    ) {
                        Icon(Icons.Default.PinDrop, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = t("arrived_stop"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    // When arrived: Big Scan QR button + Next Stop
                    Button(
                        onClick = onScanQr,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TelebirrBlue),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("driver_scan_qr_button")
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = t("passenger_checkin"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onSkip,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("driver_skip_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusExpiredRed)
                        ) {
                            Text(text = t("skip_noshow"), fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = onNextStop,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TransportGreenDark),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("driver_next_stop_button")
                        ) {
                            Text(text = t("next_stop"), fontWeight = FontWeight.Bold)
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
    onCloseScanner: () -> Unit
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

                when (val result = scanResult) {
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
