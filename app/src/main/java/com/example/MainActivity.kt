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
import com.example.ui.screens.admin.AdminDashboardScreen
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
                        AdminDashboardScreen(viewModel = viewModel)
                    }
                }
            }
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
