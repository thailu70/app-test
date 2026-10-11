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
import com.example.core.localization.AppLanguage
import com.example.core.localization.AppStrings
import com.example.data.database.TransportDatabase
import com.example.data.repository.TransportRepository
import com.example.ui.components.NotificationTrayDialog
import com.example.ui.components.PrivateMediaUploadButton
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
            val preferences = getSharedPreferences("routepass_preferences", MODE_PRIVATE)
            var darkTheme by remember { mutableStateOf(preferences.getBoolean("dark_theme", false)) }
            MyApplicationTheme(darkTheme = darkTheme) {
                MainTransportApp(
                    viewModel = viewModel,
                    darkTheme = darkTheme,
                    onDarkThemeChange = { enabled ->
                        darkTheme = enabled
                        preferences.edit().putBoolean("dark_theme", enabled).apply()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTransportApp(
    viewModel: MainViewModel,
    darkTheme: Boolean = false,
    onDarkThemeChange: (Boolean) -> Unit = {}
) {
    var showSettings by remember { mutableStateOf(false) }
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentRole by viewModel.currentRole.collectAsState()
    val currentLang by viewModel.currentLanguage.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val isNotificationTrayOpen by viewModel.isNotificationTrayOpen.collectAsState()
    val passengerNotifs by viewModel.passengerNotifications.collectAsState()
    val transporterNotifs by viewModel.transporterNotifications.collectAsState()
    val allNotifs by viewModel.allNotifications.collectAsState()
    val mediaUploadMessage by viewModel.mediaUploadMessage.collectAsState()

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
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
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
                        IconButton(onClick = { showSettings = true }, modifier = Modifier.testTag("top_bar_settings_button")) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Slate700)
                        }
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

        if (showSettings) {
            RoutePassSettingsDialog(
                viewModel = viewModel,
                userName = currentUser?.fullName.orEmpty(),
                phone = currentUser?.phone.orEmpty(),
                role = currentRole,
                roleLabel = when (currentRole) { AppRole.PASSENGER -> "Passenger"; AppRole.DRIVER -> "Driver"; AppRole.ADMIN -> "Administrator" },
                darkTheme = darkTheme,
                mediaUploadMessage = mediaUploadMessage,
                onDarkThemeChange = onDarkThemeChange,
                onSwitchPortal = {
                    showSettings = false
                    viewModel.logout()
                },
                onDismiss = { showSettings = false }
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

@Composable
private fun RoutePassSettingsDialog(
    viewModel: MainViewModel,
    userName: String,
    phone: String,
    role: AppRole,
    roleLabel: String,
    darkTheme: Boolean,
    mediaUploadMessage: String?,
    onDarkThemeChange: (Boolean) -> Unit,
    onSwitchPortal: () -> Unit,
    onDismiss: () -> Unit
) {
    var profileExpanded by remember { mutableStateOf(true) }
    var appearanceExpanded by remember { mutableStateOf(false) }
    var notificationsExpanded by remember { mutableStateOf(false) }
    var supportExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        TextButton(onClick = { profileExpanded = !profileExpanded }) { Text(if (profileExpanded) "▼ Profile & security" else "▶ Profile & security") }
                        if (profileExpanded) {
                            Text(userName.ifBlank { "RoutePass user" }, fontWeight = FontWeight.SemiBold)
                            Text("Account type: $roleLabel")
                            Text("Phone: $phone")
                            if (role == AppRole.PASSENGER || role == AppRole.DRIVER) {
                                PrivateMediaUploadButton(
                                    viewModel = viewModel,
                                    assetType = if (role == AppRole.DRIVER) "DRIVER_PROFILE_PHOTO" else "PROFILE_PHOTO",
                                    label = "Upload profile photo",
                                    imagesOnly = true
                                )
                                mediaUploadMessage?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(
                                "Password changes are not enabled by the account API yet. The app will not pretend a local-only change is secure.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        TextButton(onClick = { appearanceExpanded = !appearanceExpanded }) { Text(if (appearanceExpanded) "▼ Appearance" else "▶ Appearance") }
                        if (appearanceExpanded) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Dark theme", modifier = Modifier.weight(1f))
                                Switch(checked = darkTheme, onCheckedChange = { darkTheme = it; onDarkThemeChange(it) }, modifier = Modifier.testTag("settings_dark_theme_switch"))
                            }
                            Text("Theme preference is saved on this device.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        TextButton(onClick = { notificationsExpanded = !notificationsExpanded }) { Text(if (notificationsExpanded) "▼ Notifications" else "▶ Notifications") }
                        if (notificationsExpanded) Text("Use the notification bell for available alerts. Notification preferences are not connected yet.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        TextButton(onClick = { supportExpanded = !supportExpanded }) { Text(if (supportExpanded) "▼ Support & route requests" else "▶ Support & route requests") }
                        if (supportExpanded) Text("Contact your RoutePass administrator for support. Route-change requests and approval are not enabled here; no request has been submitted.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(
                    onClick = onSwitchPortal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Switch account / portal")
                }
                Text(
                    "Portal access stays tied to your server-authorized account role. Sign in with an account registered for the other role.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
