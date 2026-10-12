package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.core.localization.AppLanguage
import com.example.core.localization.AppStrings
import com.example.data.database.TransportDatabase
import com.example.data.repository.TransportRepository
import com.example.ui.components.NotificationTrayDialog
import com.example.ui.screens.auth.AuthScreen
import com.example.ui.screens.driver.DriverTripScreen
import com.example.ui.screens.passenger.PassengerDashboardScreen
import com.example.ui.theme.*
import com.example.ui.viewmodel.AppRole
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.MainViewModelFactory
import com.example.ui.viewmodel.NetworkStatus

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = TransportDatabase.getDatabase(this, lifecycleScope)
        val repository = TransportRepository(database.transportDao())
        val factory = MainViewModelFactory(repository)
        viewModel = ViewModelProvider(this, factory)[MainViewModel::class.java]

        setContent {
            MyApplicationTheme {
                MainTransportApp(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTransportApp(viewModel: MainViewModel) {
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentRole by viewModel.currentRole.collectAsState()
    val currentLang by viewModel.currentLanguage.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val isNotificationTrayOpen by viewModel.isNotificationTrayOpen.collectAsState()
    val passengerNotifs by viewModel.passengerNotifications.collectAsState()
    val transporterNotifs by viewModel.transporterNotifications.collectAsState()
    val allNotifs by viewModel.allNotifications.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var drawerNotice by remember { mutableStateOf<String?>(null) }

    fun t(key: String) = AppStrings.get(key, currentLang)

    val relevantNotifications = when (currentRole) {
        AppRole.PASSENGER -> passengerNotifs
        AppRole.DRIVER -> transporterNotifs
        AppRole.ADMIN -> allNotifs
    }

    if (!isAuthenticated) {
        // Independent Authentication Screen (Passenger, Driver/Transporter, Operator/Admin)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = t("app_title"),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    actions = {
                        TextButton(onClick = { viewModel.toggleLanguage() }) {
                            Text(
                                text = if (currentLang == AppLanguage.ENGLISH) "አማርኛ" else "English",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            AuthScreen(
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    } else {
        // Authenticated System Experience
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(300.dp), drawerContainerColor = Color.White) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 34.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Surface(shape = CircleShape, color = Color(0xFFFCEEEB), modifier = Modifier.size(64.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(currentUser?.avatarInitials ?: "A", color = Color(0xFFC75C42), fontSize = 28.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(currentUser?.fullName ?: "RoutePass Passenger", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF171717))
                                Text(currentUser?.phone ?: "", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF777777))
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFFE7E7E7), thickness = 1.dp)
                    Spacer(Modifier.height(18.dp))
                    val drawerItems = listOf(
                        "Home" to Icons.Default.Home,
                        "Bookings" to Icons.Default.BookmarkBorder,
                        "Transactions" to Icons.Default.CreditCard,
                        "Favorite Routes" to Icons.Default.FavoriteBorder,
                        "Suggest Route" to Icons.Default.DirectionsBus,
                        "Route Preferences" to Icons.Default.Tune,
                        "FAQ" to Icons.Default.HelpOutline,
                        "Feedback" to Icons.Default.Feedback
                    )
                    drawerItems.forEach { (label, itemIcon) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(72.dp).clickable {
                                drawerScope.launch { drawerState.close() }
                                if (label != "Home") drawerNotice = label
                            }.padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Surface(shape = CircleShape, color = Color(0xFFF0F0F0), modifier = Modifier.size(46.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(itemIcon, contentDescription = null, tint = Color(0xFFC75C42), modifier = Modifier.size(24.dp))
                                }
                            }
                            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color(0xFF111111))
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF999999), modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier.fillMaxWidth().height(76.dp).clickable {
                            drawerScope.launch { drawerState.close() }
                            viewModel.logout()
                        }.padding(horizontal = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Icon(Icons.Default.Logout, contentDescription = null, tint = Color(0xFFC75C42), modifier = Modifier.size(28.dp))
                        Text("Logout", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color(0xFF111111))
                    }
                }
            }
        ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { drawerScope.launch { drawerState.open() } }, modifier = Modifier.testTag("open_navigation_drawer")) {
                            Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = TransportGreenPrimary,
                                shape = CircleShape,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = currentUser?.avatarInitials ?: "ET",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = currentUser?.fullName ?: t("app_title"),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Text(
                                    text = when (currentRole) {
                                        AppRole.PASSENGER -> "Passenger Portal"
                                        AppRole.DRIVER -> "Transporter / Driver"
                                        AppRole.ADMIN -> currentUser?.companyName?.ifBlank { "Operator Admin" } ?: "Operator Admin"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Slate600
                                )
                            }
                        }
                    },
                    actions = {
                        // Notifications Bell with Badge
                        IconButton(
                            onClick = { viewModel.openNotificationTray() },
                            modifier = Modifier.testTag("top_bar_notifications_button")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (relevantNotifications.isNotEmpty()) {
                                        Badge(containerColor = TransportGold) {
                                            Text("${relevantNotifications.size}")
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Notifications,
                                    contentDescription = "Notifications",
                                    tint = Slate700
                                )
                            }
                        }

                        // Language Switcher
                        TextButton(
                            onClick = { viewModel.toggleLanguage() },
                            modifier = Modifier.testTag("top_bar_language_button")
                        ) {
                            Text(
                                text = if (currentLang == AppLanguage.ENGLISH) "አማርኛ" else "EN",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        // Network badge toggle
                        Surface(
                            color = when (networkStatus) {
                                NetworkStatus.ONLINE -> StatusActiveGreen.copy(alpha = 0.15f)
                                NetworkStatus.OFFLINE -> StatusWarningOrange.copy(alpha = 0.15f)
                                NetworkStatus.SYNCING -> StatusInfoBlue.copy(alpha = 0.15f)
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .clickable { viewModel.toggleNetworkMode() }
                                .padding(horizontal = 4.dp)
                                .testTag("top_bar_network_badge")
                        ) {
                            Text(
                                text = when (networkStatus) {
                                    NetworkStatus.ONLINE -> "LIVE"
                                    NetworkStatus.OFFLINE -> "OFFLINE"
                                    NetworkStatus.SYNCING -> "SYNC"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (networkStatus) {
                                    NetworkStatus.ONLINE -> StatusActiveGreen
                                    NetworkStatus.OFFLINE -> StatusWarningOrange
                                    NetworkStatus.SYNCING -> StatusInfoBlue
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }

                        // Logout button
                        IconButton(
                            onClick = { viewModel.logout() },
                            modifier = Modifier.testTag("top_bar_logout_button")
                        ) {
                            Icon(Icons.Default.Logout, contentDescription = "Log Out", tint = Slate600)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            // Note: bottomBar is removed to ensure complete role isolation.
            // A passenger ONLY sees their passenger account, a driver ONLY sees their driver account,
            // and an operator ONLY sees their operator dashboard.
        ) { innerPadding ->
            androidx.activity.compose.BackHandler {
                viewModel.logout()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (currentRole) {
                    AppRole.PASSENGER -> {
                        PassengerDashboardScreen(viewModel = viewModel)
                    }
                    AppRole.DRIVER -> {
                        DriverTripScreen(viewModel = viewModel)
                    }
                    AppRole.ADMIN -> {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "The administrator portal is available in your browser only.",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Text("Open https://routepass.duckdns.org/admin/ to manage RoutePass.")
                            Button(onClick = { viewModel.logout() }) { Text("Return to sign in") }
                        }
                    }
                }
            }
        }
        }

        if (drawerNotice != null) {
            AlertDialog(
                onDismissRequest = { drawerNotice = null },
                title = { Text(drawerNotice!!) },
                text = { Text(when (drawerNotice) {
                    "Bookings" -> "Your route and subscription details are shown on the Home screen."
                    "Transactions" -> "Payment records and receipts are available in your subscription and payment section."
                    "Favorite Routes" -> "Favorite-route management will be connected to your route preferences."
                    "Suggest Route" -> "You can request a route during passenger registration. Admin approval is required."
                    "Route Preferences" -> "Your current route, pickup and destination preferences are managed with your subscription."
                    "FAQ" -> "RoutePass support information will be added here."
                    "Feedback" -> "Use the support and complaint section on the Home screen to send feedback."
                    else -> "RoutePass"
                }) },
                confirmButton = { TextButton(onClick = { drawerNotice = null }) { Text("OK") } }
            )
        }

        // Notification Tray Dialog
        if (isNotificationTrayOpen) {
            NotificationTrayDialog(
                role = currentRole,
                notifications = relevantNotifications,
                onDismiss = { viewModel.closeNotificationTray() },
                onSendBroadcast = { title, msg, aud ->
                    viewModel.sendBroadcastNotification(title, msg, aud)
                }
            )
        }
    }
}
