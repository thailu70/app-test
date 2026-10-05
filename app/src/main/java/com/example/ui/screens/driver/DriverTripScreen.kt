package com.example.ui.screens.driver

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
    val networkStatus by viewModel.networkStatus.collectAsState()
    val isScannerOpen by viewModel.isScannerOpen.collectAsState()
    val scanResult by viewModel.scanResult.collectAsState()

    fun t(key: String) = AppStrings.get(key, lang)

    Box(modifier = modifier.fillMaxSize()) {
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
                    onToggleNetwork = { viewModel.toggleNetworkMode() }
                )
            }

            // 2. High-Visibility Big Navigation HUD
            item {
                DriverNavigationHudCard(
                    tripState = tripState,
                    currentStop = stops.getOrNull(tripState.currentStopIndex),
                    lang = lang,
                    onStartNavigation = { viewModel.startNavigation() },
                    onArrived = { viewModel.arriveAtCurrentStop() },
                    onScanQr = { viewModel.openScanner() },
                    onSkip = { viewModel.skipCurrentStop() },
                    onNextStop = { viewModel.nextStop() }
                )
            }

            // 3. Stop Sequence & Passenger Attendance
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

        // Scanner Dialog with Live Camera Viewfinder Simulation
        if (isScannerOpen) {
            DriverQrScannerDialog(
                scanResult = scanResult,
                currentStop = stops.getOrNull(tripState.currentStopIndex)?.stopName ?: "Bole Atlas",
                onScanToken = { token -> viewModel.verifyQrToken(token) },
                onDismissResult = { viewModel.dismissScanResult() },
                onCloseScanner = { viewModel.closeScanner() }
            )
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
    onToggleNetwork: () -> Unit
) {
    fun t(key: String) = AppStrings.get(key, lang)

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
                        text = "Toyota Coaster • Plate: $vehiclePlate",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400
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

            HorizontalDivider(color = Slate800)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = t("todays_trips"), style = MaterialTheme.typography.labelSmall, color = Slate400)
                    Text(text = "Morning Trip (06:30)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = TransportGold)
                    Text(text = routeName, style = MaterialTheme.typography.bodySmall, color = Color.White)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = t("passengers_onboard"), style = MaterialTheme.typography.labelSmall, color = Slate400)
                    Text(
                        text = "${tripState.checkedInCount} / ${tripState.totalPassengers}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = StatusActiveGreen
                    )
                    Text(
                        text = "${tripState.totalPassengers - tripState.checkedInCount} remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate400
                    )
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

// Driver QR Scanner & Full-screen Verification Result Screen
@Composable
fun DriverQrScannerDialog(
    scanResult: QrValidationResult?,
    currentStop: String,
    onScanToken: (String) -> Unit,
    onDismissResult: () -> Unit,
    onCloseScanner: () -> Unit
) {
    Dialog(
        onDismissRequest = onCloseScanner,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("driver_qr_scanner_dialog")
        ) {
            // Viewfinder Camera Simulation
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "QR PASSENGER VERIFICATION",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Current Stop: $currentStop",
                            color = TransportGold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = onCloseScanner, modifier = Modifier.testTag("close_scanner_button")) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Center Reticle
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .border(3.dp, StatusActiveGreen, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Align passenger QR inside frame",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Quick Scan Test Triggers for testing in emulator
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Instant Scanner Triggers (for Testing & Demonstration):",
                        color = Slate400,
                        style = MaterialTheme.typography.labelSmall
                    )

                    Button(
                        onClick = { onScanToken("ET-NAV-2026-BOLE-AK7899") },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("scan_valid_passenger_button")
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("SCAN PASSENGER ABEBE (VALID)")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onScanToken("ET-EXPIRED-SUB-2025") },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("scan_expired_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("Test Expired", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { onScanToken("ET-WRONG-ROUTE-CMC") },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("scan_wrong_route_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("Wrong Route", fontSize = 12.sp)
                        }
                    }
                }
            }

            // FULLSCREEN VERIFICATION RESULT OVERLAY (Green Screen / Red Screen)
            scanResult?.let { result ->
                when (result) {
                    is QrValidationResult.Valid -> {
                        // Section 7 GREEN SCREEN: PASSENGER VERIFIED
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(StatusActiveGreen)
                                .clickable { onDismissResult() }
                                .padding(24.dp)
                                .testTag("green_verified_screen"),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Surface(
                                    color = Color.White,
                                    shape = CircleShape,
                                    modifier = Modifier.size(80.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = StatusActiveGreen,
                                            modifier = Modifier.size(54.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = "✓ PASSENGER VERIFIED",
                                    color = Color.White,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center
                                )

                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = "Passenger:", color = Slate600, style = MaterialTheme.typography.bodyMedium)
                                            Text(text = result.passenger.fullName, fontWeight = FontWeight.Bold, color = Slate900)
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = "Subscription:", color = Slate600, style = MaterialTheme.typography.bodyMedium)
                                            Text(text = result.subscription.subscriptionStatus, fontWeight = FontWeight.Bold, color = StatusActiveGreen)
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = "Expiry:", color = Slate600, style = MaterialTheme.typography.bodyMedium)
                                            Text(text = result.subscription.endDate, fontWeight = FontWeight.Bold, color = Slate900)
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = "Pickup Stop:", color = Slate600, style = MaterialTheme.typography.bodyMedium)
                                            Text(text = result.stopName, fontWeight = FontWeight.Bold, color = Slate900)
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = "Destination:", color = Slate600, style = MaterialTheme.typography.bodyMedium)
                                            Text(text = result.destinationName, fontWeight = FontWeight.Bold, color = Slate900)
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = "Vehicle Plate:", color = Slate600, style = MaterialTheme.typography.bodyMedium)
                                            Text(text = result.vehiclePlate, fontWeight = FontWeight.Bold, color = Slate900)
                                        }
                                    }
                                }

                                Text(
                                    text = "Attendance recorded with GPS & timestamp.",
                                    color = Color.White.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Button(
                                    onClick = onDismissResult,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("dismiss_verified_button")
                                ) {
                                    Text("TAP TO CONTINUE", color = StatusActiveGreen, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }

                    is QrValidationResult.Invalid -> {
                        // Section 7 RED SCREEN: NOT VALID
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(StatusExpiredRed)
                                .clickable { onDismissResult() }
                                .padding(24.dp)
                                .testTag("red_invalid_screen"),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Surface(
                                    color = Color.White,
                                    shape = CircleShape,
                                    modifier = Modifier.size(80.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Clear,
                                            contentDescription = null,
                                            tint = StatusExpiredRed,
                                            modifier = Modifier.size(54.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = "✕ NOT VALID",
                                    color = Color.White,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center
                                )

                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = "Reason for Rejection:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Slate600
                                        )
                                        Text(
                                            text = AppStrings.get(result.reason, AppLanguage.ENGLISH),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = StatusExpiredRed
                                        )
                                        if (result.details.isNotBlank()) {
                                            Text(
                                                text = result.details,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Slate700
                                            )
                                        }
                                    }
                                }

                                Button(
                                    onClick = onDismissResult,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("dismiss_invalid_button")
                                ) {
                                    Text("DISMISS ALERT", color = StatusExpiredRed, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
