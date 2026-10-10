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

    LaunchedEffect(currentUser?.id) { if (currentUser != null) viewModel.refreshMyRoster() }
    val assignment = myRoster?.get("assignment") as? Map<*, *>
    val assignedDriver = assignment?.get("driver") as? Map<*, *>

    fun t(key: String): String = AppStrings.get(key, lang)

    val selectedRoute = routes.find { it.id == currentUser?.appliedRouteId }
    val checkoutAmount = selectedRoute?.basePriceEtb ?: subscription?.priceEtb ?: 2500.0
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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Passenger profile", fontWeight = FontWeight.Bold)
                    PrivateMediaUploadButton(viewModel, "PROFILE_PHOTO", "Upload / update profile photo", imagesOnly = true)
                    mediaUploadMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Divider()
                    Text("Assigned driver", fontWeight = FontWeight.Bold)
                    if (driverPhotoBitmap != null) {
                        Image(
                            bitmap = driverPhotoBitmap!!.asImageBitmap(),
                            contentDescription = "Assigned driver photo",
                            modifier = Modifier.size(88.dp).clip(CircleShape)
                        )
                    } else {
                        Text("Driver photo not uploaded yet", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(assignedDriver?.get("fullName")?.toString() ?: "Driver not assigned yet")
                }
            }
        }

        // 1. Subscription Overview Card
        item {
            SubscriptionStatusCard(
                subscription = subscription,
                userAppliedRoute = currentUser?.appliedRouteName,
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
                onSubscribeClick = { viewModel.openTelebirrDialog() }
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
                onPayClick = { viewModel.openTelebirrDialog() }
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
    if (isTelebirrOpen) {
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
    lang: AppLanguage,
    onRenewClick: () -> Unit,
    onCancelClick: () -> Unit = {}
) {
    fun t(key: String) = AppStrings.get(key, lang)

    val isSubscribed = subscription != null && subscription.subscriptionStatus == "ACTIVE"
    val routeDisplayName = if (!userAppliedRoute.isNullOrBlank()) userAppliedRoute else "Bole → Merkato"
    val days = if (isSubscribed) (subscription?.daysRemaining ?: 30) else 0
    val priceVal = (subscription?.priceEtb ?: if (routeDisplayName.contains("CMC")) 3000.0 else if (routeDisplayName.contains("Saris")) 2800.0 else 2500.0).toInt()
    val pickupName = if (routeDisplayName.contains("CMC")) "CMC Station" else if (routeDisplayName.contains("Saris")) "Saris Abo" else "Bole Atlas"
    val destinationName = if (routeDisplayName.contains("CMC")) "Bole Medhanialem" else if (routeDisplayName.contains("Kazanchis")) "Kazanchis UNECA" else "Merkato"

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
                            text = if (isSubscribed) "Toyota Coaster • Plate: AA-12345" else "Selected Transit Route",
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

                // Route pickup & destination details
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = t("pickup"), style = MaterialTheme.typography.labelMedium, color = Slate400)
                        Text(text = pickupName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(text = "06:30 AM Pickup", style = MaterialTheme.typography.labelSmall, color = TransportGold)
                    }
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = Slate400,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(horizontal = 8.dp)
                    )
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(text = t("destination"), style = MaterialTheme.typography.labelMedium, color = Slate400)
                        Text(text = destinationName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text(text = "17:30 PM Return", style = MaterialTheme.typography.labelSmall, color = TransportGold)
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
                            text = if (isSubscribed) "Abebe Alemu" else "Pending Payment",
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
                            text = if (isSubscribed) "PAID (ETB $priceVal)" else "UNPAID (ETB $priceVal)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSubscribed) StatusActiveGreen else StatusErrorRed
                        )
                    }
                }

                // Action button: Pay & Subscribe vs Renew
                if (!isSubscribed) {
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
                                "The dynamic QR boarding pass is locked because you do not have an active monthly subscription. Pay with Telebirr to activate your pass.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate600,
                            textAlign = TextAlign.Center
                        )

                        Button(
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
                    Text(text = "ETB ${String.format(java.util.Locale.US, "%,.2f", amountEtb)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
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
                Button(
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
