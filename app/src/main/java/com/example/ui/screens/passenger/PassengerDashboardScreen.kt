package com.example.ui.screens.passenger

import com.example.smoothPortalMotion

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.core.localization.AppLanguage
import com.example.data.entity.PaymentTransactionEntity
import com.example.data.entity.RouteEntity
import com.example.data.entity.SubscriptionEntity
import com.example.ui.components.QrCodeCanvas
import com.example.ui.components.MiniVehicleMap
import com.example.ui.components.PassoBrandHeader
import com.example.ui.screens.profile.ProfileCompletionPrompt
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

/** A boarding pass is available only when the server's subscription is eligible. */
fun canShowPassengerBoardingPass(subscription: SubscriptionEntity?): Boolean =
    subscription != null && subscription.subscriptionStatus == "ACTIVE" &&
        subscription.paymentStatus == "PAID" && subscription.daysRemaining > 0

fun passengerBoardingStatus(subscription: SubscriptionEntity?): String = when {
    subscription == null -> "NOT_SUBSCRIBED"
    subscription.subscriptionStatus == "ACTIVE" && subscription.daysRemaining <= 0 -> "EXPIRED"
    subscription.subscriptionStatus == "ACTIVE" && subscription.paymentStatus != "PAID" -> "PENDING"
    else -> subscription.subscriptionStatus
}

private fun passengerText(lang: AppLanguage, english: String, amharic: String): String =
    if (lang == AppLanguage.AMHARIC) amharic else english

private fun etb(amount: Double): String =
    "ETB ${String.format(java.util.Locale.US, "%,.2f", amount)}"

private fun passStatus(status: String, lang: AppLanguage): String = when (status) {
    "ACTIVE" -> passengerText(lang, "Active", "ንቁ")
    "PENDING" -> passengerText(lang, "Awaiting activation", "ማግበር ይጠብቃል")
    "EXPIRING" -> passengerText(lang, "Expiring soon", "በቅርቡ ያበቃል")
    "EXPIRED" -> passengerText(lang, "Expired", "ጊዜው አልፎበታል")
    "CANCELLED" -> passengerText(lang, "Cancelled", "ተሰርዟል")
    else -> passengerText(lang, "No active pass", "ንቁ የጉዞ ፈቃድ የለም")
}

@Composable
fun PassengerDashboardScreen(
    viewModel: MainViewModel,
    onOpenProfile: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lang by viewModel.currentLanguage.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val routes by viewModel.allRoutes.collectAsState()
    val telebirrCheckoutUrl by viewModel.telebirrCheckoutUrl.collectAsState()
    val uriHandler = LocalUriHandler.current
    val trackedVehicle by viewModel.trackedVehicle.collectAsState()
    val trackingMessage by viewModel.trackingMessage.collectAsState()
    val subscriptionSnapshot by viewModel.passengerSubscriptionSnapshot.collectAsState()
    val subscription = subscriptionSnapshot.subscription
    val routeStops by viewModel.passengerRouteStops.collectAsState()
    val subscriptionLoading by viewModel.passengerSubscriptionLoading.collectAsState()
    val subscriptionError by viewModel.passengerSubscriptionError.collectAsState()
    val payments by viewModel.passengerPayments.collectAsState()
    val checkIns by viewModel.passengerCheckIns.collectAsState()
    val complaints by viewModel.passengerComplaints.collectAsState()
    val isTelebirrOpen by viewModel.isTelebirrDialogOpen.collectAsState()
    val isProcessingPayment by viewModel.isProcessingPayment.collectAsState()
    val paymentMessage by viewModel.paymentMessage.collectAsState()
    val selectedReceipt by viewModel.selectedReceipt.collectAsState()
    val isComplaintOpen by viewModel.isComplaintDialogOpen.collectAsState()
    var passengerSection by rememberSaveable(currentUser?.id) { mutableStateOf("Home") }

    val routeId = subscription?.routeId?.takeIf { it.isNotBlank() } ?: currentUser?.appliedRouteId
    val selectedRoute = routes.find { it.id == routeId }
    val checkoutAmount = selectedRoute?.basePriceEtb?.takeIf { it.isFinite() && it > 0 }
        ?: subscription?.priceEtb?.takeIf { it.isFinite() && it > 0 }
    val routeName = selectedRoute?.name?.takeIf { it.isNotBlank() }
        ?: currentUser?.appliedRouteName?.takeIf { it.isNotBlank() }
    val canBoard = canShowPassengerBoardingPass(subscription)
    val canPay = checkoutAmount != null && !subscriptionLoading && subscriptionError == null
    val primarySections = listOf("Home", "My Pass", "Payments", "More")
    val selectedPrimary = if (passengerSection in primarySections) passengerSection else "More"
    val sectionLabel: (String) -> String = { section ->
        when (section) {
            "Home" -> passengerText(lang, "Home", "መነሻ")
            "My Pass" -> passengerText(lang, "My pass", "የጉዞ ፈቃዴ")
            "Payments" -> passengerText(lang, "Payments", "ክፍያዎች")
            "More" -> passengerText(lang, "More", "ተጨማሪ")
            "Live Location" -> passengerText(lang, "Vehicle location", "የተሽከርካሪ ቦታ")
            "Trip Attendance" -> passengerText(lang, "Trip history", "የጉዞ ታሪክ")
            else -> passengerText(lang, "Help & support", "እገዛ")
        }
    }
    BackHandler(enabled = passengerSection != "Home") {
        passengerSection = if (passengerSection in primarySections) "Home" else "More"
    }

    LaunchedEffect(telebirrCheckoutUrl) {
        val url = telebirrCheckoutUrl ?: return@LaunchedEffect
        val uri = Uri.parse(url)
        val allowedHost = uri.host == "developerportal.ethiotelebirr.et" ||
            uri.host == "superapp.ethiomobilemoney.et"
        if (uri.scheme == "https" && uri.port == 38443 &&
            uri.path == "/payment/web/paygate" && allowedHost) {
            try { uriHandler.openUri(url) } catch (_: Exception) {
                viewModel.reportTelebirrCheckoutOpenError()
            }
        } else viewModel.reportTelebirrCheckoutOpenError()
        viewModel.consumeTelebirrCheckoutUrl()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Slate50,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp, modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))) {
                primarySections.forEach { section ->
                    NavigationBarItem(
                        selected = selectedPrimary == section,
                        onClick = { passengerSection = section },
                        icon = {
                            Icon(when (section) {
                                "Home" -> Icons.Default.Home
                                "My Pass" -> Icons.Default.ConfirmationNumber
                                "Payments" -> Icons.Default.CreditCard
                                else -> Icons.Default.MoreHoriz
                            }, contentDescription = null)
                        },
                        label = { Text(sectionLabel(section), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = TransportGreenPrimary,
                            selectedTextColor = TransportGreenPrimary,
                            indicatorColor = TransportGreenPrimary.copy(alpha = 0.1f)
                        ),
                        modifier = Modifier.testTag("passenger_nav_${section.replace(" ", "_").lowercase()}")
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).smoothPortalMotion(passengerSection, animateInitial = false),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)
        ) {

            if (passengerSection == "Home") item { CommuterGreeting(lang, canBoard) }
            if (passengerSection != "Home") item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (passengerSection !in primarySections) {
                        TextButton(onClick = { passengerSection = "More" }, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(sectionLabel("More"))
                        }
                    }
                    Text(
                        if (passengerSection == "Home") passengerText(lang, "Your commute", "የእርስዎ ጉዞ")
                        else sectionLabel(passengerSection),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    if (passengerSection == "My Pass" && routeName != null) {
                        Text(routeName, style = MaterialTheme.typography.bodyMedium, color = Slate600)
                    }
                }
            }
            if (passengerSection in listOf("Home", "My Pass", "Payments")) {
                if (subscriptionLoading) item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TransportGreenPrimary)
                    Text(passengerText(lang, "Updating your pass…", "የጉዞ ፈቃድዎ እየታደሰ ነው…"), color = Slate600,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (subscriptionError != null) item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(passengerText(lang, "Couldn't update your pass", "የጉዞ ፈቃድዎን ማደስ አልተቻለም"), fontWeight = FontWeight.SemiBold)
                            Text(passengerText(lang, "Check your connection and try again.", "ግንኙነትዎን ይፈትሹ እና እንደገና ይሞክሩ።"), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { viewModel.refreshPassengerData() }) {
                                Text(passengerText(lang, "Try again", "እንደገና ይሞክሩ"))
                            }
                        }
                    }
                }
            }
            when (passengerSection) {
                "Home" -> {
                    item {
                        AssignedDriverCard(subscription = subscription, route = selectedRoute, routeName = routeName,
                            stops = routeStops, lang = lang, onShowPass = { passengerSection = "My Pass" },
                            onRenew = { viewModel.openTelebirrDialog() }, paymentAvailable = canPay,
                            stopAssignment = subscriptionSnapshot.stopAssignment)
                    }
                    item { CommuterLocationCard(trackedVehicle, trackingMessage, lang) { passengerSection = "Live Location" } }
                    if (!subscriptionLoading && subscriptionError == null) item {
                        CommuterBoardingPreview(subscription, routeName, lang) { passengerSection = "My Pass" }
                    }
                    item { ProfileCompletionPrompt(onOpen = onOpenProfile) }
                }
                "My Pass" -> if (!subscriptionLoading && subscriptionError == null) item {
                    PassengerQrCard(
                        isSubscribed = canBoard,
                        token = subscription?.qrToken.orEmpty(),
                        status = passengerBoardingStatus(subscription),
                        lang = lang,
                        onSubscribeClick = { passengerSection = "Payments" }
                    )
                }
                "Payments" -> {
                    if (!subscriptionLoading && subscriptionError == null) item {
                        FinancialBalanceCard(
                            lang = lang,
                            onPayClick = { viewModel.openTelebirrDialog() },
                            subscription = subscription,
                            monthlyAmountEtb = checkoutAmount,
                            paymentAvailable = canPay
                        )
                    }
                    item { PaymentHistoryCard(payments, lang, onReceiptClick = { viewModel.openReceipt(it) }) }
                }
                "Live Location" -> item {
                    MiniVehicleMap(vehicle = trackedVehicle, modifier = Modifier.testTag("passenger_live_vehicle_map"), serverMessage = trackingMessage)
                }
                "Trip Attendance" -> item { TripAttendanceCard(checkIns, lang) }
                "Help & Complaints" -> item { ComplaintsCard(complaints, lang, onSubmitClick = { viewModel.openComplaintDialog() }) }
                else -> {
                    item { PassengerMenuRow(passengerText(lang, "My profile", "የእኔ መገለጫ"), passengerText(lang, "Photo, ID and personal details", "ፎቶ፣ መታወቂያ እና የግል መረጃ"), Icons.Default.Person, onOpenProfile) }
                    item { PassengerMenuRow(sectionLabel("Live Location"), passengerText(lang, "Your driver's latest position", "የአሽከርካሪዎ የቅርብ ጊዜ ቦታ"), Icons.Default.LocationOn, { passengerSection = "Live Location" }) }
                    item { PassengerMenuRow(sectionLabel("Trip Attendance"), passengerText(lang, "Review your recorded boardings", "የተመዘገቡ ጉዞዎችዎን ይመልከቱ"), Icons.Default.EventAvailable, { passengerSection = "Trip Attendance" }) }
                    item { PassengerMenuRow(sectionLabel("Help & Complaints"), passengerText(lang, "Get help or report an issue", "እገዛ ያግኙ ወይም ችግር ያሳውቁ"), Icons.Default.SupportAgent, { passengerSection = "Help & Complaints" }) }
                    item {
                        TextButton(onClick = { viewModel.refreshPassengerData() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(passengerText(lang, "Refresh my information", "መረጃዬን አድስ"))
                        }
                    }
                }
            }
        }
    }

    if (isTelebirrOpen) {
        TelebirrCheckoutDialog(
            amountEtb = checkoutAmount ?: 0.0,
            routeName = routeName ?: passengerText(lang, "Your commute", "የእርስዎ ጉዞ"),
            isProcessing = isProcessingPayment,
            statusMessage = paymentMessage,
            lang = lang,
            onDismiss = { viewModel.closeTelebirrDialog() },
            onConfirm = { if (canPay) viewModel.processTelebirrPayment() }
        )
    }
    selectedReceipt?.let { receipt -> ReceiptDetailsDialog(receipt, lang, onDismiss = { viewModel.closeReceipt() }) }
    if (isComplaintOpen) ComplaintSubmissionDialog(lang, onDismiss = { viewModel.closeComplaintDialog() }, onSubmit = { category, text -> viewModel.submitComplaint(category, text) })
}

@Composable
fun SubscriptionStatusCard(
    subscription: SubscriptionEntity?,
    userAppliedRoute: String?,
    lang: AppLanguage,
    onRenewClick: () -> Unit,
    onCancelClick: () -> Unit = {},
    route: RouteEntity? = null,
    onShowPass: (() -> Unit)? = null,
    paymentAvailable: Boolean = true
) {
    val canBoard = canShowPassengerBoardingPass(subscription)
    val daysLeft = rememberSubscriptionDaysLeft(subscription)
    PassengerSurface(Modifier.testTag("subscription_status_card")) {
        Text(passengerText(lang, "Your pass", "የእርስዎ የጉዞ ፈቃድ"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Slate900)
        PassStatusBadge(passengerBoardingStatus(subscription), lang)
        if (subscription == null) {
            Text(passengerText(lang, "Your pass will appear here when your route is approved and activated. Contact your administrator if you need help.", "መስመርዎ ሲጸድቅ እና ሲነቃ የጉዞ ፈቃድዎ እዚህ ይታያል። እገዛ ከፈለጉ አስተዳዳሪዎን ያግኙ።"), style = MaterialTheme.typography.bodyMedium, color = Slate600)
        } else {
            if (!userAppliedRoute.isNullOrBlank()) Text(userAppliedRoute, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Slate900)
            if (subscription.subscriptionStatus == "PENDING") Text(passengerText(lang, "Your pass is waiting for activation by your administrator.", "የጉዞ ፈቃድዎ በአስተዳዳሪዎ እስኪነቃ እየጠበቀ ነው።"), style = MaterialTheme.typography.bodySmall, color = Slate600)
            if (subscription.endDate.isNotBlank()) DetailRow(passengerText(lang, "Valid until", "የሚያበቃበት ቀን"), subscription.endDate.take(10))
            DetailRow(passengerText(lang, "Days remaining", "የቀሩ ቀናት"), daysLeft?.toString() ?: passengerText(lang, "Not available", "አልተገኘም"))
            DetailRow(passengerText(lang, "Payment", "ክፍያ"), paymentStatus(subscription.paymentStatus, lang))
            val morning = subscription.morningSchedule.takeIf { it.isNotBlank() } ?: route?.morningDeparture?.takeIf { it.isNotBlank() }
            val evening = subscription.eveningSchedule.takeIf { it.isNotBlank() } ?: route?.eveningDeparture?.takeIf { it.isNotBlank() }
            if (morning != null) DetailRow(passengerText(lang, "Morning departure", "የጠዋት መነሻ"), morning)
            if (evening != null) DetailRow(passengerText(lang, "Return departure", "የመመለሻ መነሻ"), evening)
        }
        if (canBoard && onShowPass != null) {
            Button(onClick = onShowPass, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("show_boarding_pass_button"), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary)) {
                Icon(Icons.Default.QrCode, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(passengerText(lang, "Show boarding pass", "የመሳፈሪያ ፈቃድ አሳይ"), textAlign = TextAlign.Center)
            }
        }
        if (subscription != null && subscription.subscriptionStatus != "CANCELLED") {
            OutlinedButton(onClick = onRenewClick, enabled = paymentAvailable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(if (canBoard) "renew_subscription_button" else "pay_and_subscribe_button"), shape = RoundedCornerShape(12.dp)) {
                Text(passengerText(lang, if (canBoard) "Renew pass" else "View payment options", if (canBoard) "የጉዞ ፈቃድ አድስ" else "የክፍያ አማራጮች"), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PassStatusBadge(status: String, lang: AppLanguage) {
    val active = status == "ACTIVE"
    Surface(color = if (active) Color(0xFFE5F4EC) else Slate100, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (active) Icons.Default.CheckCircle else Icons.Default.Info, contentDescription = null, tint = if (active) TransportGreenPrimary else Slate600, modifier = Modifier.size(16.dp))
            Text(passStatus(status, lang), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (active) TransportGreenPrimary else Slate600, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
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
    PassengerSurface(Modifier.testTag("passenger_qr_card")) {
        Text(passengerText(lang, "Boarding pass", "የመሳፈሪያ ፈቃድ"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Slate900)
        PassStatusBadge(status, lang)
        if (isSubscribed && token.isNotBlank()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCodeCanvas(token = token, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f).testTag("passenger_boarding_qr"), moduleColor = Color.Black, backgroundColor = Color.White)
            }
            Text(passengerText(lang, "Show this code to your driver when you board.", "ሲሳፈሩ ይህን ኮድ ለአሽከርካሪዎ ያሳዩ።"), style = MaterialTheme.typography.bodyMedium, color = Slate600, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            Icon(if (isSubscribed) Icons.Default.QrCode else Icons.Default.Lock, contentDescription = null, tint = Slate600, modifier = Modifier.padding(top = 12.dp).size(40.dp).align(Alignment.CenterHorizontally))
            Text(
                when {
                    isSubscribed -> passengerText(lang, "Your pass is active, but its boarding code is unavailable. Refresh your information or contact your administrator.", "የጉዞ ፈቃድዎ ንቁ ነው፣ የመሳፈሪያ ኮዱ ግን አልተገኘም። መረጃዎን ያድሱ ወይም አስተዳዳሪዎን ያግኙ።")
                    status == "PENDING" -> passengerText(lang, "Your boarding code will appear after your administrator activates your pass.", "አስተዳዳሪዎ የጉዞ ፈቃድዎን ካነቃ በኋላ የመሳፈሪያ ኮድዎ ይታያል።")
                    else -> passengerText(lang, "You need an active pass to board. Check payment options or contact your administrator.", "ለመሳፈር ንቁ የጉዞ ፈቃድ ያስፈልግዎታል። የክፍያ አማራጮችን ይመልከቱ ወይም አስተዳዳሪዎን ያግኙ።")
                }, style = MaterialTheme.typography.bodyMedium, color = Slate600, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            if (!isSubscribed) OutlinedButton(onClick = onSubscribeClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                Text(passengerText(lang, "View payments", "ክፍያዎችን ይመልከቱ"))
            }
        }
    }
}

private fun paymentStatus(status: String, lang: AppLanguage): String = when (status) {
    "PAID", "SUCCESS" -> passengerText(lang, "Paid", "ተከፍሏል")
    "PENDING" -> passengerText(lang, "Pending", "በመጠባበቅ ላይ")
    "UNPAID" -> passengerText(lang, "Unpaid", "ያልተከፈለ")
    "FAILED" -> passengerText(lang, "Failed", "አልተሳካም")
    "CANCELLED" -> passengerText(lang, "Cancelled", "ተሰርዟል")
    else -> passengerText(lang, "Not available", "አልተገኘም")
}

@Composable
fun FinancialBalanceCard(
    lang: AppLanguage,
    onPayClick: () -> Unit,
    subscription: SubscriptionEntity? = null,
    monthlyAmountEtb: Double? = null,
    paymentAvailable: Boolean = true
) {
    PassengerSurface(Modifier.testTag("financial_balance_card")) {
        Text(passengerText(lang, "Pass payments", "የጉዞ ፈቃድ ክፍያ"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        DetailRow(passengerText(lang, "Monthly fare", "ወርሃዊ ክፍያ"), monthlyAmountEtb?.takeIf { it.isFinite() && it > 0 }?.let(::etb) ?: passengerText(lang, "Not available", "አልተገኘም"))
        if (subscription != null) {
            DetailRow(passengerText(lang, "Payment status", "የክፍያ ሁኔታ"), paymentStatus(subscription.paymentStatus, lang))
            if (subscription.endDate.isNotBlank()) DetailRow(passengerText(lang, "Pass valid until", "የፈቃድ ማብቂያ ቀን"), subscription.endDate.take(10))
        }
        Text(passengerText(lang, "Your administrator can also activate your pass. Payment is confirmed only after verification.", "አስተዳዳሪዎም የጉዞ ፈቃድዎን ማግበር ይችላል። ክፍያ ከተረጋገጠ በኋላ ብቻ ይፀድቃል።"), style = MaterialTheme.typography.bodySmall, color = Slate600)
        Button(onClick = onPayClick, enabled = paymentAvailable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("telebirr_pay_quick_button"), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary)) {
            Text(passengerText(lang, "Pay with Telebirr", "በቴሌብር ይክፈሉ"))
        }
    }
}

@Composable
fun PaymentHistoryCard(payments: List<PaymentTransactionEntity>, lang: AppLanguage, onReceiptClick: (PaymentTransactionEntity) -> Unit) {
    PassengerSurface(Modifier.testTag("payment_history_card")) {
        Text(passengerText(lang, "Payment history", "የክፍያ ታሪክ"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (payments.isEmpty()) Text(passengerText(lang, "No payments recorded yet.", "እስካሁን የተመዘገበ ክፍያ የለም።"), style = MaterialTheme.typography.bodyMedium, color = Slate600)
        else payments.forEach { payment ->
            Column(Modifier.fillMaxWidth().clickable { onReceiptClick(payment) }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(etb(payment.amountEtb), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Slate900)
                Text("${payment.provider} · ${paymentStatus(payment.status, lang)}", style = MaterialTheme.typography.bodyMedium, color = Slate600)
                Text(payment.timestamp, style = MaterialTheme.typography.bodySmall, color = Slate600)
                Text(passengerText(lang, "View details", "ዝርዝሮችን ይመልከቱ"), color = TransportGreenPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                HorizontalDivider(Modifier.padding(top = 8.dp), color = Slate200)
            }
        }
    }
}

@Composable
fun TripAttendanceCard(checkIns: List<com.example.data.entity.CheckInRecordEntity>, lang: AppLanguage) {
    PassengerSurface(Modifier.testTag("trip_attendance_card")) {
        Text(passengerText(lang, "Recorded boardings", "የተመዘገቡ ጉዞዎች"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (checkIns.isEmpty()) Text(passengerText(lang, "No boardings recorded yet.", "እስካሁን የተመዘገበ ጉዞ የለም።"), color = Slate600)
        else checkIns.forEach { item ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.stopName, fontWeight = FontWeight.SemiBold)
                Text(item.checkInTimestamp, style = MaterialTheme.typography.bodySmall, color = Slate600)
                Text(item.status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = Slate600)
                HorizontalDivider(Modifier.padding(top = 8.dp), color = Slate200)
            }
        }
    }
}

@Composable
fun ComplaintsCard(complaints: List<com.example.data.entity.ComplaintEntity>, lang: AppLanguage, onSubmitClick: () -> Unit) {
    PassengerSurface(Modifier.testTag("complaints_card")) {
        Text(passengerText(lang, "Help & support", "እገዛ"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onSubmitClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("submit_complaint_button"), shape = RoundedCornerShape(12.dp)) {
            Text(passengerText(lang, "Report an issue", "ችግር ያሳውቁ"))
        }
        if (complaints.isEmpty()) Text(passengerText(lang, "No support requests yet.", "እስካሁን የእገዛ ጥያቄ የለም።"), color = Slate600)
        else complaints.forEach { complaint ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(complaint.category, fontWeight = FontWeight.SemiBold)
                Text(complaint.text, style = MaterialTheme.typography.bodyMedium)
                Text("${complaint.date} · ${complaint.status.replace('_', ' ').lowercase()}", style = MaterialTheme.typography.bodySmall, color = Slate600)
                HorizontalDivider(Modifier.padding(top = 8.dp), color = Slate200)
            }
        }
    }
}

@Composable
private fun PassengerSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, Slate200)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Slate600)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Slate900, textAlign = TextAlign.End)
    }
}

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
    PassengerDialog("telebirr_checkout_dialog", onDismiss) {
        Text(passengerText(lang, "Pay with Telebirr", "በቴሌብር ይክፈሉ"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(routeName, style = MaterialTheme.typography.bodyMedium, color = Slate600)
        Text(if (amountEtb.isFinite() && amountEtb > 0) etb(amountEtb) else passengerText(lang, "Fare unavailable", "ክፍያው አልተገኘም"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Slate900)
        Text(passengerText(lang, "Complete payment on Telebirr's secure page. Never share your PIN. Your pass updates after payment is verified.", "ክፍያዎን በቴሌብር ደህንነቱ በተጠበቀ ገጽ ይጨርሱ። ፒንዎን ለማንም አያጋሩ። ክፍያዎ ሲረጋገጥ የጉዞ ፈቃድዎ ይታደሳል።"), style = MaterialTheme.typography.bodyMedium, color = Slate600)
        statusMessage?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = onConfirm,
            enabled = !isProcessing && amountEtb.isFinite() && amountEtb > 0,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("telebirr_confirm_pay_button"),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary)
        ) {
            if (isProcessing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(passengerText(lang, if (isProcessing) "Checking payment…" else "Continue to Telebirr", if (isProcessing) "ክፍያው እየተረጋገጠ ነው…" else "ወደ ቴሌብር ይቀጥሉ"), textAlign = TextAlign.Center)
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("close_telebirr_dialog")) {
            Text(passengerText(lang, "Close", "ዝጋ"))
        }
    }
}

@Composable
fun ReceiptDetailsDialog(receipt: PaymentTransactionEntity, lang: AppLanguage, onDismiss: () -> Unit) {
    PassengerDialog("receipt_details_dialog", onDismiss) {
        Text(passengerText(lang, "Payment details", "የክፍያ ዝርዝሮች"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(etb(receipt.amountEtb), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(paymentStatus(receipt.status, lang), style = MaterialTheme.typography.bodyMedium, color = Slate600)
        HorizontalDivider(color = Slate200)
        ReceiptRow(passengerText(lang, "Provider", "አቅራቢ"), receipt.provider)
        ReceiptRow(passengerText(lang, "Transaction reference", "የክፍያ ማጣቀሻ"), receipt.transactionRef)
        if (receipt.receiptNumber.isNotBlank()) ReceiptRow(passengerText(lang, "Receipt number", "የደረሰኝ ቁጥር"), receipt.receiptNumber)
        ReceiptRow(passengerText(lang, "Phone", "ስልክ"), receipt.paymentPhone)
        ReceiptRow(passengerText(lang, "Date & time", "ቀን እና ሰዓት"), receipt.timestamp)
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("close_receipt_button"), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary)) {
            Text(passengerText(lang, "Done", "ተከናውኗል"))
        }
    }
}

@Composable
private fun ReceiptRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Slate600)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Slate900)
    }
}

@Composable
fun ComplaintSubmissionDialog(lang: AppLanguage, onDismiss: () -> Unit, onSubmit: (category: String, message: String) -> Unit) {
    var category by rememberSaveable { mutableStateOf("Driver Service") }
    var text by rememberSaveable { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    val categories = listOf("Driver Service", "Vehicle Condition", "Punctuality", "Route Dispute", "Other")
    PassengerDialog("complaint_submission_dialog", onDismiss) {
        Text(passengerText(lang, "Report an issue", "ችግር ያሳውቁ"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(passengerText(lang, "Category", "ምድብ"), style = MaterialTheme.typography.labelLarge, color = Slate600)
        Box {
            OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                Text(category, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                categories.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { category = option; menuExpanded = false }) }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(passengerText(lang, "What happened?", "ምን ተከሰተ?")) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("complaint_text_input"),
            shape = RoundedCornerShape(12.dp)
        )
        Button(onClick = { onSubmit(category, text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("submit_ticket_confirm_button"), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary)) {
            Text(passengerText(lang, "Send request", "ጥያቄ ላክ"))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(passengerText(lang, "Cancel", "ሰርዝ")) }
    }
}

@Composable
private fun PassengerDialog(tag: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp).testTag(tag), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
private fun PassengerMenuRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = TransportGreenPrimary.copy(alpha = 0.12f)) {
                Icon(icon, contentDescription = null, tint = TransportGreenPrimary, modifier = Modifier.padding(11.dp).size(24.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, color = Slate900)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Slate600)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Slate600)
        }
    }
}
