package com.example.ui.screens.passenger

import android.net.Uri
import android.graphics.BitmapFactory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.core.localization.AppLanguage
import com.example.core.localization.AppStrings
import com.example.data.entity.PaymentTransactionEntity
import com.example.ui.components.RoutePassHeroHeader
import com.example.ui.components.PrivateMediaUploadButton
import com.example.ui.components.QrCodeCanvas
import com.example.ui.components.MiniVehicleMap
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

@Composable
fun PassengerDashboardScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val lang by viewModel.currentLanguage.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val routes by viewModel.allRoutes.collectAsState()
    val telebirrCheckoutUrl by viewModel.telebirrCheckoutUrl.collectAsState()
    val uriHandler = LocalUriHandler.current
    val trackedVehicle by viewModel.trackedVehicle.collectAsState()
    val trackingMessage by viewModel.trackingMessage.collectAsState()
    val subscription by viewModel.activeSubscription.collectAsState()
    val payments by viewModel.passengerPayments.collectAsState()
    val checkIns by viewModel.passengerCheckIns.collectAsState()
    val complaints by viewModel.passengerComplaints.collectAsState()
    val isTelebirrOpen by viewModel.isTelebirrDialogOpen.collectAsState()
    val isProcessingPayment by viewModel.isProcessingPayment.collectAsState()
    val paymentMessage by viewModel.paymentMessage.collectAsState()
    val selectedReceipt by viewModel.selectedReceipt.collectAsState()
    val isComplaintOpen by viewModel.isComplaintDialogOpen.collectAsState()
    val myRoster by viewModel.myRoster.collectAsState()
    val mediaUploadMessage by viewModel.mediaUploadMessage.collectAsState()
    val driverPhotoBytes by viewModel.assignedDriverPhotoBytes.collectAsState()
    val driverPhotoBitmap = remember(driverPhotoBytes) { driverPhotoBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    val passengerRouteStops by viewModel.passengerRouteStops.collectAsState()

    LaunchedEffect(currentUser?.id) { if (currentUser != null) viewModel.refreshMyRoster() }
    val assignment = myRoster?.get("assignment") as? Map<*, *>
    val assignedDriver = assignment?.get("driver") as? Map<*, *>

    fun t(key: String): String = AppStrings.get(key, lang)

    val selectedRoute = routes.find { it.id == currentUser?.appliedRouteId }
    // Do not expose a tariff or allow checkout until an administrator assigns an official route.
    val hasAdminAssignedRoute = !currentUser?.appliedRouteId.isNullOrBlank() && selectedRoute != null
    val checkoutAmount = if (hasAdminAssignedRoute) selectedRoute?.basePriceEtb ?: subscription?.priceEtb ?: 0.0 else 0.0
    val checkoutRouteName = selectedRoute?.name
        ?: currentUser?.appliedRouteName?.takeIf { it.isNotBlank() }
        ?: "Monthly commuter pass"

    LaunchedEffect(telebirrCheckoutUrl) {
        val url = telebirrCheckoutUrl ?: return@LaunchedEffect
        val uri = Uri.parse(url)
        val allowedHost = uri.host == "developerportal.ethiotelebirr.et" ||
            uri.host == "superapp.ethiomobilemoney.et"
        val validCheckout = uri.scheme == "https" && uri.port == 38443 &&
            uri.path == "/payment/web/paygate" && allowedHost
        if (validCheckout) {
            try {
                uriHandler.openUri(url)
            } catch (_: Exception) {
                viewModel.reportTelebirrCheckoutOpenError()
            }
        } else {
            viewModel.reportTelebirrCheckoutOpenError()
        }
        viewModel.consumeTelebirrCheckoutUrl()
    }

    val assignedVehicle = assignment?.get("vehicle") as? Map<*, *>
    val driverName = assignedDriver?.get("fullName")?.toString()?.takeIf { it.isNotBlank() } ?: "Driver not assigned yet"
    val driverPhone = assignedDriver?.get("phone")?.toString()?.takeIf { it.isNotBlank() }
    val vehicleName = assignedVehicle?.get("model")?.toString()?.takeIf { it.isNotBlank() }
        ?: assignedVehicle?.get("vehicleName")?.toString()?.takeIf { it.isNotBlank() }
        ?: assignedVehicle?.get("type")?.toString()?.takeIf { it.isNotBlank() }
    val vehiclePlate = assignedVehicle?.get("plateNumber")?.toString()?.takeIf { it.isNotBlank() }
        ?: assignedVehicle?.get("plate")?.toString()?.takeIf { it.isNotBlank() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFF7FAF9), Color(0xFFEAF3F1))))
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)
    ) {
        // Hero is the first major content card, matching the reference design.
        item {
            RoutePassHeroHeader(
                title = "Your everyday, upgraded.",
                subtitle = "Your pass, your driver and your journey — beautifully connected.",
                icon = Icons.Default.QrCode
            )
        }

        // Dedicated driver card; passenger dashboard intentionally has no passenger roster.
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("passenger_assigned_driver_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = Color(0xFF0B8A70), modifier = Modifier.size(22.dp))
                        Text("Your driver", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF173B35))
                        Spacer(Modifier.weight(1f))
                        Surface(color = Color(0xFFE0F5E9), shape = RoundedCornerShape(8.dp)) {
                            Text("ASSIGNED", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = Color(0xFF16734B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (driverPhotoBitmap != null) {
                            Image(
                                bitmap = driverPhotoBitmap!!.asImageBitmap(),
                                contentDescription = "Assigned driver photo",
                                modifier = Modifier.size(88.dp).clip(CircleShape).border(2.dp, Color(0xFFE1EBE7), CircleShape)
                            )
                        } else {
                            Surface(modifier = Modifier.size(88.dp), shape = CircleShape, color = Color(0xFFEAF2EF)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF668078), modifier = Modifier.size(42.dp))
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(driverName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF182F2A))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Default.Verified, contentDescription = null, tint = Color(0xFF0D9B68), modifier = Modifier.size(16.dp))
                                Text(if (driverPhotoBitmap != null) "Verified driver profile" else "Driver profile", style = MaterialTheme.typography.bodySmall, color = Color(0xFF71817C))
                            }
                            if (driverPhone != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Call, contentDescription = null, tint = Color(0xFF70837D), modifier = Modifier.size(17.dp))
                                    Text(driverPhone, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF253C36))
                                }
                            }
                        }
                    }
                    if (vehicleName != null || vehiclePlate != null) {
                        HorizontalDivider(color = Color(0xFFE8EFEC))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = Color(0xFF0B8A70), modifier = Modifier.size(20.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(vehicleName ?: "Assigned vehicle", fontWeight = FontWeight.SemiBold, color = Color(0xFF253C36))
                                vehiclePlate?.let { Text("Plate: $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFF71817C)) }
                            }
                        }
                    }
                    mediaUploadMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF55716A)) }
                }
            }
        }

        // 1. Subscription Overview Card
        item {
            SubscriptionStatusCard(
                subscription = subscription,
                userAppliedRoute = currentUser?.appliedRouteName,
                assignedDriverName = assignedDriver?.get("fullName")?.toString()?.takeIf { it.isNotBlank() },
                assignedVehicleName = vehicleName,
                assignedVehiclePlate = vehiclePlate,
                routeStops = passengerRouteStops,
                isRouteAssigned = hasAdminAssignedRoute,
                lang = lang,
                onRenewClick = { viewModel.openTelebirrDialog() },
                onCancelClick = { viewModel.cancelSubscription() }
            )
        }

        // 2. Dynamic Boarding QR Code Card
        item {
            PassengerQrCard(
                isSubscribed = subscription != null && subscription?.subscriptionStatus == "ACTIVE",
                token = subscription?.qrToken ?: "",
                status = subscription?.subscriptionStatus ?: "NOT_SUBSCRIBED",
                lang = lang,
                isRouteAssigned = hasAdminAssignedRoute,
                onSubscribeClick = { if (hasAdminAssignedRoute) viewModel.openTelebirrDialog() }
            )
        }

        // 3. Server-reported driver location. No demo coordinates are shown as live.
        item {
            MiniVehicleMap(vehicle = trackedVehicle, modifier = Modifier.testTag("passenger_live_vehicle_map"), serverMessage = trackingMessage)
        }

        // 4. Passenger Financial Account & Balance
        item {
            FinancialBalanceCard(
                lang = lang,
                amountEtb = checkoutAmount,
                isRouteAssigned = hasAdminAssignedRoute,
                onPayClick = { if (hasAdminAssignedRoute) viewModel.openTelebirrDialog() }
            )
        }

        // 5. Telebirr Payment History
        item {
            PaymentHistoryCard(
                payments = payments,
                lang = lang,
                onReceiptClick = { viewModel.openReceipt(it) }
            )
        }

        // 6. Trip Attendance History
        item {
            TripAttendanceCard(checkIns = checkIns, lang = lang)
        }

        // 7. Complaints & Support
        item {
            ComplaintsCard(
                complaints = complaints,
                lang = lang,
                onSubmitClick = { viewModel.openComplaintDialog() }
            )
        }
    }

    // Telebirr Payment Dialog
    if (isTelebirrOpen && hasAdminAssignedRoute) {
        TelebirrCheckoutDialog(
            amountEtb = checkoutAmount,
            routeName = checkoutRouteName,
            isProcessing = isProcessingPayment,
            statusMessage = paymentMessage,
            lang = lang,
            onDismiss = { viewModel.closeTelebirrDialog() },
            onConfirm = { viewModel.processTelebirrPayment() }
        )
    }

    // Receipt Dialog
    selectedReceipt?.let { receipt ->
        ReceiptDetailsDialog(
            receipt = receipt,
            lang = lang,
            onDismiss = { viewModel.closeReceipt() }
        )
    }

    // Complaint Dialog
    if (isComplaintOpen) {
        ComplaintSubmissionDialog(
            lang = lang,
            onDismiss = { viewModel.closeComplaintDialog() },
            onSubmit = { category, text -> viewModel.submitComplaint(category, text) }
        )
    }
}

@Composable
fun SubscriptionStatusCard(
    subscription: com.example.data.entity.SubscriptionEntity?,
    userAppliedRoute: String?,
    assignedDriverName: String? = null,
    assignedVehicleName: String? = null,
    assignedVehiclePlate: String? = null,
    routeStops: List<com.example.data.entity.RouteStopEntity> = emptyList(),
    isRouteAssigned: Boolean = true,
    lang: AppLanguage,
    onRenewClick: () -> Unit,
    onCancelClick: () -> Unit = {}
) {
    fun t(key: String) = AppStrings.get(key, lang)

    val isSubscribed = subscription != null && subscription.subscriptionStatus == "ACTIVE"
    fun parseRequestedEndpoints(value: String): Pair<String, String>? {
        val separators = listOf(
            Regex("\\s+(?:to|→|->)\\s+", RegexOption.IGNORE_CASE),
            Regex("\\s+[–—-]\\s+")
        )
        for (separator in separators) {
            val parts = separator.split(value.trim(), limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                return parts[0].trim() to parts[1].trim()
            }
        }
        return null
    }
    fun formatScheduleTime(value: String?): String {
        val parts = value?.take(5)?.split(":") ?: return "Time not set"
        if (parts.size != 2) return value ?: "Time not set"
        val hour = parts[0].toIntOrNull() ?: return value
        val minute = parts[1].toIntOrNull() ?: return value
        if (hour !in 0..23 || minute !in 0..59) return value
        val suffix = if (hour < 12) "AM" else "PM"
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        return "$hour12:${minute.toString().padStart(2, '0')} $suffix"
    }
    val requestedEndpoints = parseRequestedEndpoints(subscription?.requestedRoute.orEmpty())
    val pickupStopFromRoute = routeStops.firstOrNull { it.id == subscription?.pickupStopId }
    val destinationStopFromRoute = routeStops.firstOrNull { it.id == subscription?.destinationStopId }
    val pickupName = subscription?.pickupStopName?.takeIf { it.isNotBlank() }
        ?: pickupStopFromRoute?.stopName?.takeIf { it.isNotBlank() }
        ?: requestedEndpoints?.first
        ?: "Pickup not assigned — contact admin"
    val destinationName = subscription?.destinationStopName?.takeIf { it.isNotBlank() }
        ?: destinationStopFromRoute?.stopName?.takeIf { it.isNotBlank() }
        ?: requestedEndpoints?.second
        ?: "Destination not assigned — contact admin"
    val hasRealEndpoints = pickupName != "Pickup not set" && destinationName != "Destination not set"
    val routeDisplayName = if (hasRealEndpoints) "$pickupName - $destinationName"
        else subscription?.requestedRoute?.takeIf { it.isNotBlank() }
            ?: userAppliedRoute?.takeIf { it.isNotBlank() }
            ?: "Monthly commuter pass"
    val morningTime = formatScheduleTime(subscription?.morningSchedule)
    val eveningTime = formatScheduleTime(subscription?.eveningSchedule)
    val days = if (isSubscribed) (subscription?.daysRemaining ?: 30) else 0
    val priceVal = (subscription?.priceEtb ?: 2500.0).toInt()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("subscription_status_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        if (isSubscribed)
                            listOf(TransportGreenDark, Slate900)
                        else
                            listOf(Color(0xFF3B1510), Slate900)
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = routeDisplayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = listOfNotNull(assignedVehicleName, assignedVehiclePlate?.let { "Plate: $it" })
                                .joinToString(" • ")
                                .ifBlank { if (isSubscribed) "Vehicle assignment pending" else "Selected Transit Route" },
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400
                        )
                    }

                    Surface(
                        color = if (isSubscribed) StatusActiveGreen.copy(alpha = 0.2f) else StatusErrorRed.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSubscribed) StatusActiveGreen else StatusErrorRed
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isSubscribed) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                contentDescription = null,
                                tint = if (isSubscribed) StatusActiveGreen else StatusErrorRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (isSubscribed) "ACTIVE ✓" else "NOT SUBSCRIBED ✕",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // If NOT subscribed, show prominent notice banner
                if (!isSubscribed) {
                    Surface(
                        color = Color(0xFFFEF3C7).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(24.dp)
                            )
                            Column {
                                Text(
                                    text = if (lang == AppLanguage.AMHARIC)
                                        "ይህ ተሳፋሪ እስካሁን አልተመዘገበም!"
                                    else
                                        "Passenger is not subscribed yet!",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFDE68A)
                                )
                                Text(
                                    text = if (lang == AppLanguage.AMHARIC)
                                        "እባክዎ ለተመረጠው የጉዞ መስመር ($routeDisplayName) ክፍያ ፈጽመው ይመዝገቡ። ከዚያ በኋላ ደንበኝነቱ ንቁ (Active) ይሆናል።"
                                    else
                                        "The passenger is not subscribed. Please pay and subscribe for the selected route ($routeDisplayName). After payment, your monthly subscription will be ACTIVE.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Slate700)

                // Passenger's requested endpoints define the route title. Return service reverses the same endpoints.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Morning • Home → Work / School",
                        style = MaterialTheme.typography.labelMedium,
                        color = TransportGold,
                        fontWeight = FontWeight.Bold
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = t("pickup"), style = MaterialTheme.typography.labelMedium, color = Slate400)
                            Text(text = pickupName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(text = "$morningTime Pickup", style = MaterialTheme.typography.labelSmall, color = TransportGold)
                        }
                        Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Slate400,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp))
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text(text = t("destination"), style = MaterialTheme.typography.labelMedium, color = Slate400)
                            Text(text = destinationName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                    if (!subscription?.serviceType.equals("ONE_WAY", ignoreCase = true)) {
                        HorizontalDivider(color = Slate700.copy(alpha = 0.7f))
                        Text(
                            text = "Return • Work / School → Home",
                            style = MaterialTheme.typography.labelMedium,
                            color = TransportGold,
                            fontWeight = FontWeight.Bold
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = t("pickup"), style = MaterialTheme.typography.labelMedium, color = Slate400)
                                Text(text = destinationName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text(text = "$eveningTime Return", style = MaterialTheme.typography.labelSmall, color = TransportGold)
                            }
                            Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Slate400,
                                modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp))
                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Text(text = t("destination"), style = MaterialTheme.typography.labelMedium, color = Slate400)
                                Text(text = pickupName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                        }
                    }
                }

                // Grid of stats
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Slate800.copy(alpha = 0.7f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = t("driver_label"), style = MaterialTheme.typography.labelSmall, color = Slate400)
                        Text(
                            text = if (isSubscribed) assignedDriverName?.takeIf { it.isNotBlank() } ?: "Driver assignment pending" else "Pending Payment",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column {
                        Text(text = t("days_remaining"), style = MaterialTheme.typography.labelSmall, color = Slate400)
                        Text(
                            text = if (isSubscribed) "$days Days" else "0 Days",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSubscribed) TransportGold else Slate400
                        )
                    }
                    Column {
                        Text(text = t("payment"), style = MaterialTheme.typography.labelSmall, color = Slate400)
                        Text(
                            text = if (!isRouteAssigned) {
                                if (lang == AppLanguage.AMHARIC) "መስመር እየተመደበ ነው" else "PENDING ROUTE ASSIGNMENT"
                            } else if (isSubscribed) "PAID (ETB $priceVal)" else "UNPAID (ETB $priceVal)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSubscribed) StatusActiveGreen else StatusErrorRed
                        )
                    }
                }

                // Keep tariff and payment controls hidden until an administrator assigns an official route.
                if (!isRouteAssigned) {
                    Surface(
                        color = Color(0xFFFEF3C7).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B))
                    ) {
                        Text(
                            text = if (lang == AppLanguage.AMHARIC)
                                "የመስመር ምደባ በአስተዳዳሪ እስኪጸድቅ ድረስ የወርሃዊ ክፍያ መጠን አይታይም።"
                            else
                                "Route assignment pending. Your monthly price will be shown after an administrator assigns your route.",
                            modifier = Modifier.padding(12.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else if (!isSubscribed) {
                    Button(
                        onClick = onRenewClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pay_and_subscribe_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TelebirrBlue)
                    ) {
                        Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (lang == AppLanguage.AMHARIC)
                                "በቴሌብር ይክፈሉ እና ይመዝገቡ (ETB $priceVal)"
                            else
                                "Pay & Subscribe with Telebirr (ETB $priceVal)",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRenewClick,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("renew_subscription_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, TelebirrBlue)
                        ) {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = TelebirrBlue)
                            Spacer(Modifier.width(6.dp))
                            Text(text = t("renew_subscription"), fontWeight = FontWeight.SemiBold)
                        }

                        TextButton(
                            onClick = onCancelClick,
                            modifier = Modifier.testTag("cancel_sub_button")
                        ) {
                            Text(
                                text = "Cancel Pass",
                                color = Slate400,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PassengerQrCard(
    isSubscribed: Boolean,
    token: String,
    status: String,
    lang: AppLanguage,
    isRouteAssigned: Boolean = true,
    onSubscribeClick: () -> Unit = {}
) {
    fun t(key: String) = AppStrings.get(key, lang)

    // Pulsing animation for the QR border
    val infiniteTransition = rememberInfiniteTransition(label = "qr_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("passenger_qr_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = t("my_qr_code"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isSubscribed) t("qr_token_rotates") else "Subscription required to generate pass",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600
                    )
                }
                Surface(
                    color = if (isSubscribed) StatusActiveGreen.copy(alpha = 0.15f) else StatusErrorRed.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isSubscribed) "ACTIVE ✓" else "NOT SUBSCRIBED ✕",
                        color = if (isSubscribed) StatusActiveGreen else StatusErrorRed,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (isSubscribed && token.isNotBlank()) {
                // High-resolution Canvas QR Code with Glowing Animated Border
                Box(
                    modifier = Modifier
                        .size(230.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .border(3.dp, TransportGreenPrimary.copy(alpha = pulseAlpha), RoundedCornerShape(20.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    QrCodeCanvas(
                        token = token,
                        modifier = Modifier.fillMaxSize(),
                        moduleColor = Slate900,
                        backgroundColor = Color.White
                    )
                }

                // Secure Token text representation
                Surface(
                    color = Slate100,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "ID: ${token.take(18)}...",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = Slate700,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Text(
                    text = t("qr_instruction"),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = Slate600,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            } else {
                // Locked / Not Subscribed Placeholder
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Slate100,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate300)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = StatusWarningOrange.copy(alpha = 0.2f),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = StatusWarningOrange,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        Text(
                            text = if (lang == AppLanguage.AMHARIC) "የመሳፈሪያ QR ኮድ አልነቃም" else "QR Boarding Pass Inactive",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )

                        Text(
                            text = if (lang == AppLanguage.AMHARIC)
                                "የዲጂታል መሳፈሪያ QR ኮድ ለማግኘት እባክዎ አስቀድመው ለተመረጠው መስመር በቴሌብር ክፍያ ፈጽመው ይመዝገቡ።"
                            else
                                if (isRouteAssigned) "The dynamic QR boarding pass is locked because you do not have an active monthly subscription. Follow the payment instructions for your assigned route."
                                else "Your route is awaiting administrator assignment. The price and payment option will appear after your route is assigned.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600,
                            textAlign = TextAlign.Center
                        )

                        if (isRouteAssigned) Button(
                            onClick = onSubscribeClick,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TelebirrBlue),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (lang == AppLanguage.AMHARIC) "በቴሌብር ይክፈሉ" else "Pay & Subscribe via Telebirr",
                                color = Color.White,
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
fun LiveBusTrackingCard(lang: AppLanguage) {
    fun t(key: String) = AppStrings.get(key, lang)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_bus_tracking_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(StatusActiveGreen)
                    )
                    Text(
                        text = t("track_bus"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    color = TransportGold.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "ETA: 4 min",
                        color = Color(0xFFB45309),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Live progress telemetry
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Slate100)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = t("bus_location"), style = MaterialTheme.typography.labelSmall, color = Slate600)
                    Text(text = "Approaching Atlas", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = t("distance"), style = MaterialTheme.typography.labelSmall, color = Slate600)
                    Text(text = "1.2 km", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TransportGreenPrimary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = t("vehicle"), style = MaterialTheme.typography.labelSmall, color = Slate600)
                    Text(text = "AA-12345", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }

            // Route Progress dots
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StopPoint(name = "Atlas", isActive = true, isCurrent = true)
                LinearProgressIndicator(
                    progress = { 0.4f },
                    modifier = Modifier.weight(1f),
                    color = TransportGreenPrimary,
                    trackColor = Slate200,
                )
                StopPoint(name = "Medhanialem", isActive = false, isCurrent = false)
                LinearProgressIndicator(
                    progress = { 0.0f },
                    modifier = Modifier.weight(1f),
                    color = TransportGreenPrimary,
                    trackColor = Slate200,
                )
                StopPoint(name = "Merkato", isActive = false, isCurrent = false)
            }
        }
    }
}

@Composable
private fun StopPoint(name: String, isActive: Boolean, isCurrent: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(if (isCurrent) 16.dp else 12.dp)
                .clip(CircleShape)
                .background(if (isActive) TransportGreenPrimary else Slate400)
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = if (isCurrent) TransportGreenPrimary else Slate600
        )
    }
}

@Composable
fun FinancialBalanceCard(
    lang: AppLanguage,
    amountEtb: Double,
    isRouteAssigned: Boolean = true,
    onPayClick: () -> Unit
) {
    fun t(key: String) = AppStrings.get(key, lang)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("financial_balance_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = t("financial_balance"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    Icons.Default.AccountBalance,
                    contentDescription = null,
                    tint = TransportGreenPrimary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = t("current_balance"), style = MaterialTheme.typography.labelSmall, color = Slate600)
                    Text(text = "ETB 0.00", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = t("monthly_fee"), style = MaterialTheme.typography.labelSmall, color = Slate600)
                    Text(
                        text = if (isRouteAssigned) "ETB ${String.format(java.util.Locale.US, "%,.2f", amountEtb)}"
                        else if (lang == AppLanguage.AMHARIC) "በመጠባበቅ ላይ" else "Pending assignment",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HorizontalDivider(color = Slate200)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = t("next_payment"), style = MaterialTheme.typography.labelSmall, color = Slate600)
                    Text(text = "01 Nov 2026", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                if (isRouteAssigned) Button(
                    onClick = onPayClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TelebirrBlue),
                    modifier = Modifier.testTag("telebirr_pay_quick_button")
                ) {
                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(text = t("pay_telebirr"), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun PaymentHistoryCard(
    payments: List<PaymentTransactionEntity>,
    lang: AppLanguage,
    onReceiptClick: (PaymentTransactionEntity) -> Unit
) {
    fun t(key: String) = AppStrings.get(key, lang)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("payment_history_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = t("payment_history"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (payments.isEmpty()) {
                Text(
                    text = "No recorded payments yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
            } else {
                payments.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Slate50)
                            .clickable { onReceiptClick(p) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                color = TelebirrBlue.copy(alpha = 0.15f),
                                shape = CircleShape,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = TelebirrBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Monthly Subscription",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = p.timestamp,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Slate600
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "ETB ${p.amountEtb.toInt()}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = StatusActiveGreen
                            )
                            Text(
                                text = "Receipt ↗",
                                style = MaterialTheme.typography.labelSmall,
                                color = TelebirrBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TripAttendanceCard(
    checkIns: List<com.example.data.entity.CheckInRecordEntity>,
    lang: AppLanguage
) {
    fun t(key: String) = AppStrings.get(key, lang)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("trip_attendance_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = t("trip_history"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (checkIns.isEmpty()) {
                Text(
                    text = "No boardings recorded yet for this period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
            } else {
                checkIns.take(4).forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = item.stopName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = item.checkInTimestamp,
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }
                        Surface(
                            color = StatusActiveGreen.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "PRESENT",
                                color = StatusActiveGreen,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ComplaintsCard(
    complaints: List<com.example.data.entity.ComplaintEntity>,
    lang: AppLanguage,
    onSubmitClick: () -> Unit
) {
    fun t(key: String) = AppStrings.get(key, lang)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("complaints_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = t("complaints"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = onSubmitClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("submit_complaint_button")
                ) {
                    Icon(Icons.Default.Feedback, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(text = "New Ticket", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (complaints.isEmpty()) {
                Text(
                    text = "No open support tickets. Everything is smooth.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
            } else {
                complaints.forEach { c ->
                    Surface(
                        color = Slate50,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "[${c.category}] ${c.text}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(text = c.date, style = MaterialTheme.typography.labelSmall, color = Slate400)
                            }
                            Surface(
                                color = TransportGold.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = c.status,
                                    color = Color(0xFFB45309),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Telebirr Checkout Dialog
@Composable
fun TelebirrCheckoutDialog(
    amountEtb: Double,
    routeName: String,
    isProcessing: Boolean,
    statusMessage: String?,
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .testTag("telebirr_checkout_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with official Telebirr banner
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = TelebirrBlue,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "tb",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Telebirr Gateway",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Secure Official Integration",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate600
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_telebirr_dialog")) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Surface(
                    color = Color(0xFFEFF6FF),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "Amount to Pay", style = MaterialTheme.typography.labelSmall, color = Slate600)
                        Text(
                            text = "ETB 2,500.00",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = TelebirrDark
                        )
                        Text(
                            text = "$routeName Monthly Subscription",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate700
                        )
                    }
                }

                Surface(
                    color = Slate100,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = TelebirrBlue, modifier = Modifier.size(18.dp))
                        Text(
                            text = if (lang == AppLanguage.AMHARIC)
                                "የቴሌብር ደህንነት፡ ክፍያ በቴሌብር አገልጋይ በኩል ይረጋገጣል። ፒን ቁጥርዎን ለማንም አይስጡ።"
                            else
                                "You will finish authorization on the official Telebirr checkout page. RoutePass never asks for your PIN.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate700
                        )
                    }
                }

                statusMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = if (msg.contains("success", ignoreCase = true) || msg.contains("verified", ignoreCase = true)) StatusActiveGreen else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = onConfirm,
                    enabled = !isProcessing,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TelebirrBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("telebirr_confirm_pay_button")
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Verifying with Telebirr...")
                    } else {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("CONTINUE TO TELEBIRR · ETB ${String.format(java.util.Locale.US, "%,.2f", amountEtb)}", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Receipt Details Modal
@Composable
fun ReceiptDetailsDialog(
    receipt: PaymentTransactionEntity,
    lang: AppLanguage,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .testTag("receipt_details_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = StatusActiveGreen.copy(alpha = 0.15f),
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Verified, contentDescription = null, tint = StatusActiveGreen, modifier = Modifier.size(28.dp))
                    }
                }

                Text(
                    text = "Official Telebirr Receipt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Payment Verified & Credential Active",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )

                HorizontalDivider(color = Slate200)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReceiptRow(label = "Transaction Ref:", value = receipt.transactionRef)
                    ReceiptRow(label = "Receipt Number:", value = receipt.receiptNumber)
                    ReceiptRow(label = "Amount Paid:", value = "ETB ${receipt.amountEtb} (Paid)")
                    ReceiptRow(label = "Payer Phone:", value = receipt.paymentPhone)
                    ReceiptRow(label = "Date & Time:", value = receipt.timestamp)
                    ReceiptRow(label = "Payment Gateway:", value = "Telebirr API")
                }

                HorizontalDivider(color = Slate200)

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("close_receipt_button")
                ) {
                    Text("DONE")
                }
            }
        }
    }
}

@Composable
private fun ReceiptRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Slate600)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

// Complaint Dialog
@Composable
fun ComplaintSubmissionDialog(
    lang: AppLanguage,
    onDismiss: () -> Unit,
    onSubmit: (category: String, message: String) -> Unit
) {
    var category by remember { mutableStateOf("Driver Service") }
    var text by remember { mutableStateOf("") }

    val categories = listOf("Driver Service", "Vehicle Condition", "Punctuality", "Route Dispute", "Other")

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .testTag("complaint_submission_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Submit Support Ticket",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Text(text = "Category", style = MaterialTheme.typography.labelSmall, color = Slate600)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.take(3).forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat, fontSize = 11.sp) }
                        )
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Describe the issue") },
                    placeholder = { Text("E.g., Shuttle was 10 mins late at Bole Atlas...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .testTag("complaint_text_input")
                )

                Button(
                    onClick = { onSubmit(category, text) },
                    enabled = text.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("submit_ticket_confirm_button")
                ) {
                    Text("SUBMIT TICKET")
                }
            }
        }
    }
}
