package com.example

import android.os.Bundle
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.core.localization.AppLanguage
import com.example.core.localization.AppStrings
import com.example.data.api.ApiClient
import com.example.data.database.TransportDatabase
import com.example.data.repository.TransportRepository
import com.example.ui.components.NotificationTrayDialog
import com.example.ui.screens.auth.AuthScreen
import com.example.ui.screens.home.BrowseFirstScreen
import com.example.ui.screens.home.PassoWelcomeScreen
import com.example.ui.screens.home.WelcomeService
import com.example.ui.screens.home.HostedPortalScreen
import com.example.ui.screens.profile.ProfileCompletionScreen
import com.example.ui.screens.driver.DriverTripScreen
import com.example.ui.screens.passenger.PassengerDashboardScreen
import com.example.ui.screens.passenger.CommuterWordmark
import com.example.ui.screens.passenger.CommuterHeader
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
internal fun PassengerTopBar(
    fullName: String,
    initials: String,
    language: AppLanguage,
    profileOpen: Boolean = false,
    notificationCount: Int = 0,
    onProfile: () -> Unit,
    onBack: () -> Unit,
    onNotifications: () -> Unit,
    onLanguage: () -> Unit,
    onLogout: () -> Unit,
    onSelectLanguage: ((AppLanguage) -> Unit)? = null,
    onToggleNetwork: (() -> Unit)? = null,
    networkLabel: String = ""
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            if (profileOpen) Text("Your profile", fontWeight = FontWeight.Bold)
            else if (onSelectLanguage != null) CommuterHeader(language, onSelectLanguage) else CommuterWordmark()
        },
        navigationIcon = {
            if (profileOpen) IconButton(onClick = onBack, modifier = Modifier.testTag("top_bar_profile_button")) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back to your commute")
            }
        },
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("passenger_account_menu")) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Account options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Notifications" + if (notificationCount > 0) " ($notificationCount)" else "") }, leadingIcon = { Icon(Icons.Default.NotificationsNone, null) }, onClick = { menuOpen = false; onNotifications() }, modifier = Modifier.testTag("top_bar_notifications_button"))
                    DropdownMenuItem(text = { Text("Profile & documents") }, leadingIcon = { Icon(Icons.Default.AccountCircle, null) }, onClick = { menuOpen = false; onProfile() })
                    DropdownMenuItem(text = { Text(if (language == AppLanguage.ENGLISH) "አማርኛ" else "English") }, leadingIcon = { Icon(Icons.Default.Language, null) }, onClick = { menuOpen = false; onLanguage() }, modifier = Modifier.testTag("top_bar_language_button"))
                    if (onToggleNetwork != null) DropdownMenuItem(text = { Text("Connection: $networkLabel") }, leadingIcon = { Icon(Icons.Default.Wifi, null) }, onClick = { onToggleNetwork() }, modifier = Modifier.testTag("top_bar_network_badge"))
                    DropdownMenuItem(text = { Text("Sign out") }, leadingIcon = { Icon(Icons.Default.Logout, null) }, onClick = { menuOpen = false; onLogout() }, modifier = Modifier.testTag("top_bar_logout_button"))
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFFFBF4))
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTransportApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val currentRole by viewModel.currentRole.collectAsState()
    val currentLang by viewModel.currentLanguage.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val isNotificationTrayOpen by viewModel.isNotificationTrayOpen.collectAsState()
    val passengerNotifs by viewModel.passengerNotifications.collectAsState()
    val transporterNotifs by viewModel.transporterNotifications.collectAsState()
    val allNotifs by viewModel.allNotifications.collectAsState()
    var showProfile by remember(currentUser?.id) { mutableStateOf(false) }

    fun t(key: String) = AppStrings.get(key, currentLang)

    val relevantNotifications = when (currentRole) {
        AppRole.PASSENGER -> passengerNotifs
        AppRole.DRIVER -> transporterNotifs
        AppRole.ADMIN -> allNotifs
    }

    if (!isAuthenticated) {
        var showWelcome by rememberSaveable { mutableStateOf(true) }
        var showPublicHome by rememberSaveable { mutableStateOf(true) }
        var showCompanyPortal by rememberSaveable { mutableStateOf(false) }
        var showIntercityPortal by rememberSaveable { mutableStateOf(false) }
        var requestedAuthPortal by rememberSaveable { mutableStateOf<AppRole?>(null) }
        var requestedRegisterFirst by rememberSaveable { mutableStateOf(false) }
        var requestedRouteId by rememberSaveable { mutableStateOf<String?>(null) }
        var requestedRouteName by rememberSaveable { mutableStateOf<String?>(null) }
        var requestedServiceTier by rememberSaveable { mutableStateOf<String?>(null) }
        fun returnFromAuthentication() {
            // Passenger sign-in is entered from route browsing, including My pass.
            // Keep that return destination while driver access returns to Welcome.
            val returnToRoutes = requestedAuthPortal == AppRole.PASSENGER
            showPublicHome = true
            showWelcome = !returnToRoutes
            requestedAuthPortal = null
            requestedRegisterFirst = false
            requestedRouteId = null
            requestedRouteName = null
            requestedServiceTier = null
        }
        androidx.activity.compose.BackHandler(enabled = !showWelcome && !showIntercityPortal && !showCompanyPortal) {
            if (showPublicHome) showWelcome = true else returnFromAuthentication()
        }
        if (showWelcome) {
            PassoWelcomeScreen(
                onContinue = { service ->
                    requestedAuthPortal = if (service == WelcomeService.DRIVER) AppRole.DRIVER else null
                    requestedRegisterFirst = false
                    requestedRouteId = null
                    requestedRouteName = null
                    requestedServiceTier = null
                    showPublicHome = service == WelcomeService.PASSENGER
                    showIntercityPortal = service == WelcomeService.INTERCITY
                    showWelcome = false
                },
                language = currentLang,
                onLanguage = { viewModel.setLanguage(it) },
                modifier = Modifier.fillMaxSize()
            )
        } else if (showIntercityPortal) {
            HostedPortalScreen("/travel/", "Intercity travel", onExit = { showIntercityPortal = false; showWelcome = true })
        } else if (showCompanyPortal) {
            CompanyPortalScreen(onExit = {
                ApiClient.setAuthToken(null)
                showCompanyPortal = false
                showPublicHome = true
            })
        } else if (showPublicHome) {
            BrowseFirstScreen(
                viewModel = viewModel,
                startWithRoutes = true,
                onWelcome = { showWelcome = true },
                onIntercityTravel = { showIntercityPortal = true; showPublicHome = false },
                onPassengerContinue = { registerFirst, routeId, routeName, tier ->
                    requestedAuthPortal = AppRole.PASSENGER
                    requestedRegisterFirst = registerFirst
                    requestedRouteId = routeId
                    requestedRouteName = routeName
                    requestedServiceTier = tier
                    showPublicHome = false
                },
                onDriverAccess = { requestedAuthPortal = AppRole.DRIVER; requestedRegisterFirst = false; requestedRouteId = null; requestedRouteName = null; requestedServiceTier = null; showPublicHome = false },
                onCompanyAccess = { showCompanyPortal = true; showPublicHome = false }
            )
        } else {
        // Independent Authentication Screen (Passenger, Driver/Transporter, Operator/Admin)
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        TextButton(onClick = { returnFromAuthentication() }) {
                            Text("Back", color = TransportGreenPrimary, fontWeight = FontWeight.Bold)
                        }
                    },
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
                    .padding(innerPadding),
                initialPortal = requestedAuthPortal,
                initialRegisterMode = requestedRegisterFirst,
                initialRouteId = requestedRouteId,
                initialRouteName = requestedRouteName,
                initialServiceTier = requestedServiceTier
            )
        }
        }
    } else {
        // Authenticated System Experience
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (currentRole == AppRole.PASSENGER || currentRole == AppRole.DRIVER) {
                    PassengerTopBar(
                        fullName = currentUser?.fullName.orEmpty(),
                        initials = currentUser?.avatarInitials.orEmpty(),
                        language = currentLang,
                        profileOpen = showProfile,
                        notificationCount = relevantNotifications.size,
                        onProfile = { showProfile = true },
                        onBack = { showProfile = false },
                        onNotifications = { viewModel.openNotificationTray() },
                        onLanguage = { viewModel.toggleLanguage() },
                        onLogout = { viewModel.logout() },
                        onSelectLanguage = { viewModel.setLanguage(it) },
                        onToggleNetwork = if (currentRole == AppRole.DRIVER) ({ viewModel.toggleNetworkMode() }) else null,
                        networkLabel = networkStatus.name
                    )
                } else TopAppBar(
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
                        if (currentRole != AppRole.ADMIN) {
                            IconButton(onClick = { showProfile = true }) { Icon(Icons.Default.AccountCircle, contentDescription = "Profile, documents and emergency contact") }
                        }
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
            androidx.activity.compose.BackHandler(enabled = showProfile || currentRole != AppRole.PASSENGER) {
                if (showProfile) showProfile = false else viewModel.logout()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (showProfile) {
                    ProfileCompletionScreen(role = currentRole.name, onBack = { showProfile = false })
                } else when (currentRole) {
                    AppRole.PASSENGER -> {
                        PassengerDashboardScreen(viewModel = viewModel, onOpenProfile = { showProfile = true })
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
private fun CompanyPortalField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    secret: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveFleetMap(vehicles: List<Map<String, Any>>) {
    val points = vehicles.mapNotNull { vehicle ->
        val latitude = vehicle["latitude"]?.toString()?.toDoubleOrNull()
        val longitude = vehicle["longitude"]?.toString()?.toDoubleOrNull()
        if (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            Triple(latitude, longitude, (vehicle["plateNumber"] ?: vehicle["model"] ?: "Company vehicle").toString())
        } else null
    }
    val markerJs = points.joinToString(",") { (lat, lon, label) ->
        "{lat:$lat,lon:$lon,label:${org.json.JSONObject.quote(label)}}"
    }
    val html = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <style>html,body,#map{height:100%;margin:0;background:#edf2f7}.leaflet-control-attribution{font-size:9px}</style>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script></head><body><div id="map"></div>
        <script>
        var pts=[${markerJs}];
        var map=L.map('map').setView(pts.length?[pts[0].lat,pts[0].lon]:[8.9806,38.7578],pts.length?12:11);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
        pts.forEach(function(p){L.marker([p.lat,p.lon]).addTo(map).bindPopup(p.label)});
        if(pts.length>1){var group=L.featureGroup(pts.map(function(p){return L.marker([p.lat,p.lon])}));map.fitBounds(group.getBounds().pad(0.2))}
        </script></body></html>
    """.trimIndent()
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = CircleShape, color = TransportGreenPrimary.copy(alpha = 0.12f)) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, tint = TransportGreenPrimary, modifier = Modifier.padding(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Live fleet map", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Vehicle positions refresh every 15 seconds", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(points.size.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = TransportGreenPrimary)
            }
            if (points.isNotEmpty()) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp)),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            setBackgroundColor(AndroidColor.rgb(237, 242, 247))
                        }
                    },
                    update = { webView ->
                        val page = html.replace("${markerJs}", markerJs)
                        webView.loadDataWithBaseURL("https://routepass.duckdns.org", page, "text/html", "UTF-8", null)
                    }
                )
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.LocationSearching, contentDescription = null, modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Waiting for vehicle GPS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("The map will show a vehicle when its driver sends valid GPS coordinates.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyPortalScreen(onExit: () -> Unit) {
    HostedPortalScreen("/company/", "Company portal", onExit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyCompanyPortalScreen(onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    var registerMode by remember { mutableStateOf(false) }
    var companyName by remember { mutableStateOf("") }
    var representative by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var companyLabel by remember { mutableStateOf("") }
    var token by remember { mutableStateOf<String?>(null) }
    var section by remember { mutableStateOf("Overview") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<Map<String, Any>>(emptyMap()) }
    var fleet by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var gpsVehicles by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var drivers by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var routes by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var bookings by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var plate by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var vehicleType by remember { mutableStateOf("MINIBUS_14") }
    var routeName by remember { mutableStateOf("") }
    var routeType by remember { mutableStateOf("INTERCITY") }
    var routeOrigin by remember { mutableStateOf("") }
    var routeDestination by remember { mutableStateOf("") }
    var intercityDeparture by remember { mutableStateOf("08:00") }
    var morning by remember { mutableStateOf("06:30") }
    var evening by remember { mutableStateOf("17:30") }
    var distance by remember { mutableStateOf("10") }
    var fare by remember { mutableStateOf("0") }
    var editingRouteId by remember { mutableStateOf("") }
    var assignRouteId by remember { mutableStateOf("") }
    var assignDriverId by remember { mutableStateOf("") }
    var assignVehicleId by remember { mutableStateOf("") }
    var routeMenuOpen by remember { mutableStateOf(false) }
    var driverMenuOpen by remember { mutableStateOf(false) }
    var vehicleMenuOpen by remember { mutableStateOf(false) }

    fun errorText(response: retrofit2.Response<out Any>): String {
        return try {
            val body = response.errorBody()?.string().orEmpty()
            org.json.JSONObject(body).optString("error").ifBlank { "Request failed (${response.code()})." }
        } catch (_: Exception) { "Request failed (${response.code()})." }
    }

    fun loadSection() {
        if (token == null) return
        scope.launch {
            busy = true
            message = null
            try {
                val api = ApiClient.getService()
                when (section) {
                    "Overview" -> {
                        val r = api.getCompanySummary()
                        if (r.isSuccessful) summary = r.body()?.get("summary") as? Map<String, Any> ?: emptyMap() else message = errorText(r)
                        val gps = api.getCompanyFleet()
                        if (gps.isSuccessful) {
                            val b = gps.body().orEmpty()
                            fleet = (b["vehicles"] as? List<*>)?.mapNotNull { it as? Map<String, Any> } ?: emptyList()
                            gpsVehicles = fleet.filter { (it["latitude"]?.toString()?.toDoubleOrNull() ?: 0.0) != 0.0 && (it["longitude"]?.toString()?.toDoubleOrNull() ?: 0.0) != 0.0 }
                        }
                    }
                    "Fleet" -> {
                        val r = api.getCompanyFleet()
                        if (r.isSuccessful) {
                            val b = r.body().orEmpty()
                            fleet = (b["vehicles"] as? List<*>)?.mapNotNull { it as? Map<String, Any> } ?: emptyList()
                            gpsVehicles = fleet.filter { (it["latitude"]?.toString()?.toDoubleOrNull() ?: 0.0) != 0.0 && (it["longitude"]?.toString()?.toDoubleOrNull() ?: 0.0) != 0.0 }
                            drivers = (b["drivers"] as? List<*>)?.mapNotNull { it as? Map<String, Any> } ?: emptyList()
                            val rr = api.getCompanyRoutes()
                            if (rr.isSuccessful) routes = (rr.body()?.get("routes") as? List<*>)?.mapNotNull { it as? Map<String, Any> } ?: emptyList()
                        } else message = errorText(r)
                    }
                    "Drivers" -> {
                        val r = api.getCompanyDrivers()
                        if (r.isSuccessful) drivers = (r.body()?.get("drivers") as? List<*>)?.mapNotNull { it as? Map<String, Any> } ?: emptyList() else message = errorText(r)
                    }
                    "Routes" -> {
                        val r = api.getCompanyRoutes()
                        if (r.isSuccessful) routes = (r.body()?.get("routes") as? List<*>)?.mapNotNull { it as? Map<String, Any> } ?: emptyList() else message = errorText(r)
                    }
                    "Bookings" -> {
                        val r = api.getCompanyBookings()
                        if (r.isSuccessful) bookings = (r.body()?.get("bookings") as? List<*>)?.mapNotNull { it as? Map<String, Any> } ?: emptyList() else message = errorText(r)
                    }
                }
            } catch (e: Exception) {
                message = e.message ?: "Could not connect to RoutePass."
            } finally { busy = false }
        }
    }

    LaunchedEffect(token, section) {
        loadSection()
        if (token != null && section == "Overview") {
            while (true) {
                kotlinx.coroutines.delay(15000)
                loadSection()
            }
        }
    }

    if (token == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onExit) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                    title = { Text("RoutePass Company Portal", fontWeight = FontWeight.Bold) }
                )
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(color = TransportGreenPrimary, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("COMPANY OPERATIONS", color = TransportGold, fontWeight = FontWeight.ExtraBold)
                        Text(if (registerMode) "Activate your company account" else "Welcome back", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Manage drivers, company vehicles, routes, assignments and passenger bookings in the RoutePass app.", color = Color.White)
                    }
                }
                if (registerMode) {
                    CompanyPortalField("Registered company name", companyName, { companyName = it })
                    CompanyPortalField("Representative full name", representative, { representative = it })
                }
                CompanyPortalField("Company phone number", phone, { phone = it })
                if (registerMode) CompanyPortalField("One-time company invitation code", code, { code = it })
                CompanyPortalField("Password (minimum 10 characters)", password, { password = it }, true)
                message?.let { Text(it, color = StatusErrorRed) }
                Button(
                    onClick = {
                        scope.launch {
                            busy = true; message = null
                            try {
                                val api = ApiClient.getService()
                                val response = if (registerMode) api.registerTransportCompany(mapOf(
                                    "companyName" to companyName.trim(), "fullName" to representative.trim(),
                                    "phone" to phone.trim(), "secretCode" to code.trim(), "password" to password
                                )) else api.loginTransportCompany(mapOf("phone" to phone.trim(), "password" to password))
                                if (response.isSuccessful) {
                                    val body = response.body().orEmpty()
                                    val receivedToken = body["token"] as? String
                                    if (receivedToken.isNullOrBlank()) message = "The server did not return a company access token."
                                    else {
                                        token = receivedToken
                                        ApiClient.setAuthToken(receivedToken)
                                        val user = body["user"] as? Map<String, Any>
                                        companyLabel = user?.get("companyName")?.toString() ?: companyName
                                    }
                                } else message = errorText(response)
                            } catch (e: Exception) { message = e.message ?: "Could not connect to RoutePass." }
                            finally { busy = false }
                        }
                    },
                    enabled = !busy && phone.isNotBlank() && password.isNotBlank() &&
                        (!registerMode || (companyName.isNotBlank() && representative.isNotBlank() && code.isNotBlank() && password.length >= 10)),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (busy) "Please wait…" else if (registerMode) "Activate Company Account" else "Sign In to Company Portal") }
                TextButton(onClick = { registerMode = !registerMode; message = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (registerMode) "Already activated? Sign in" else "Have an invitation code? Register company")
                }
            }
        }
        return
    }

    Scaffold(
        containerColor = Color(0xFFF4F6FA),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                title = { Column { Text("Company Portal", fontWeight = FontWeight.ExtraBold); Text(companyLabel.ifBlank { "Transport operations" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                navigationIcon = { IconButton(onClick = onExit) { Icon(Icons.Default.ArrowBack, contentDescription = "Exit company portal") } },
                actions = { IconButton(onClick = { token = null; ApiClient.setAuthToken(null) }) { Icon(Icons.Default.Logout, contentDescription = "Sign out") } }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                listOf("Overview", "Fleet", "Drivers", "Routes", "Bookings").forEach { item ->
                    NavigationBarItem(selected = section == item, onClick = { section = item }, icon = {
                        Icon(when(item) {
                            "Overview" -> Icons.Default.Dashboard
                            "Fleet" -> Icons.Default.DirectionsBus
                            "Drivers" -> Icons.Default.Groups
                            "Routes" -> Icons.Default.AltRoute
                            else -> Icons.Default.ConfirmationNumber
                        }, contentDescription = item)
                    }, label = { Text(item) })
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().background(Color(0xFFF4F6FA)).padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = StatusErrorRed) }
            when (section) {
                "Overview" -> {
                    Text("Operations overview", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF17233C))
                    Text("Your fleet at a glance", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("Drivers" to "drivers", "Vehicles" to "vehicles", "Assignments" to "activeAssignments", "GPS live" to "vehiclesReportingGps").forEach { (label, key) ->
                            Card(Modifier.weight(1f), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(summary[key]?.toString() ?: "0", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = TransportGreenPrimary)
                                }
                            }
                        }
                    }
                    LiveFleetMap(gpsVehicles)
                    Text("Manage drivers, assign company vehicles and routes, and review passenger check-ins.", style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { loadSection() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Refresh dashboard") }
                }
                "Fleet" -> {
                    Text("Fleet management", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF17233C))
                    Text("Register vehicles owned by your company and assign them to approved drivers.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CompanyPortalField("Plate number", plate, { plate = it })
                    CompanyPortalField("Vehicle model", model, { model = it })
                    var typeExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                        OutlinedTextField(value = vehicleType, onValueChange = {}, readOnly = true, label = { Text("Vehicle class") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                            listOf("MINIVAN_8", "MINIBUS_14", "HIGER_24", "ANBESSA_BUS_30").forEach { type ->
                                DropdownMenuItem(text = { Text(type) }, onClick = { vehicleType = type; typeExpanded = false })
                            }
                        }
                    }
                    Button(onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val r = ApiClient.getService().createCompanyVehicle(mapOf("plateNumber" to plate.trim(), "model" to model.trim(), "vehicleType" to vehicleType))
                                if (r.isSuccessful) { plate = ""; model = ""; message = "Company vehicle registered."; loadSection() } else message = errorText(r)
                            } catch (e: Exception) { message = e.message ?: "Could not register vehicle." } finally { busy = false }
                        }
                    }, enabled = !busy && plate.isNotBlank() && model.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Register company vehicle") }
                    fleet.forEach { v -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(14.dp)) {
                        Text("${v["plateNumber"] ?: "No plate"} • ${v["model"] ?: ""}", fontWeight = FontWeight.Bold)
                        Text("${v["vehicleType"] ?: ""} • Seats: ${v["capacityLimit"] ?: "—"}")
                        Text("Driver: ${v["driverName"] ?: "Unassigned"} • Route: ${v["routeName"] ?: "Unassigned"}")
                        Text("GPS: ${v["latitude"] ?: "—"}, ${v["longitude"] ?: "—"}")
                    } } }
                    if (fleet.isEmpty()) Text("No company vehicles registered yet.")
                    HorizontalDivider()
                    Text("Assign route and company vehicle to an approved driver", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    ExposedDropdownMenuBox(expanded = routeMenuOpen, onExpandedChange = { routeMenuOpen = it }) {
                        OutlinedTextField(value = routes.firstOrNull { it["id"]?.toString() == assignRouteId }?.get("name")?.toString() ?: "Select company route", onValueChange = {}, readOnly = true, label = { Text("Route") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(routeMenuOpen) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = routeMenuOpen, onDismissRequest = { routeMenuOpen = false }) {
                            routes.forEach { r -> DropdownMenuItem(text = { Text(r["name"]?.toString() ?: "Route") }, onClick = { assignRouteId = r["id"]?.toString().orEmpty(); routeMenuOpen = false }) }
                        }
                    }
                    ExposedDropdownMenuBox(expanded = driverMenuOpen, onExpandedChange = { driverMenuOpen = it }) {
                        OutlinedTextField(value = drivers.firstOrNull { it["id"]?.toString() == assignDriverId }?.get("fullName")?.toString() ?: "Select approved driver", onValueChange = {}, readOnly = true, label = { Text("Driver") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(driverMenuOpen) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = driverMenuOpen, onDismissRequest = { driverMenuOpen = false }) {
                            drivers.filter { it["status"]?.toString().equals("ACTIVE", true) }.forEach { d -> DropdownMenuItem(text = { Text(d["fullName"]?.toString() ?: "Driver") }, onClick = { assignDriverId = d["id"]?.toString().orEmpty(); driverMenuOpen = false }) }
                        }
                    }
                    ExposedDropdownMenuBox(expanded = vehicleMenuOpen, onExpandedChange = { vehicleMenuOpen = it }) {
                        OutlinedTextField(value = fleet.firstOrNull { it["id"]?.toString() == assignVehicleId }?.get("plateNumber")?.toString() ?: "Select company vehicle", onValueChange = {}, readOnly = true, label = { Text("Vehicle") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(vehicleMenuOpen) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = vehicleMenuOpen, onDismissRequest = { vehicleMenuOpen = false }) {
                            fleet.forEach { v -> DropdownMenuItem(text = { Text("${v["plateNumber"] ?: "Vehicle"} • ${v["model"] ?: ""}") }, onClick = { assignVehicleId = v["id"]?.toString().orEmpty(); vehicleMenuOpen = false }) }
                        }
                    }
                    Button(onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val r = ApiClient.getService().assignCompanyDriverVehicleRoute(mapOf("routeId" to assignRouteId, "driverId" to assignDriverId, "vehicleId" to assignVehicleId))
                                if (r.isSuccessful) { message = "Driver, vehicle and route assigned successfully."; assignRouteId = ""; assignDriverId = ""; assignVehicleId = ""; loadSection() } else message = errorText(r)
                            } catch (e: Exception) { message = e.message ?: "Could not assign driver and vehicle." } finally { busy = false }
                        }
                    }, enabled = !busy && assignRouteId.isNotBlank() && assignDriverId.isNotBlank() && assignVehicleId.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Assign driver, vehicle and route") }
                    HorizontalDivider()
                    Text("Current assignments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val activeAssignments = routes.filter { it["assignment_status"]?.toString() == "ACTIVE" && !it["assignment_id"]?.toString().isNullOrBlank() }
                    if (activeAssignments.isEmpty()) Text("No active route assignments yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    activeAssignments.forEach { assignment ->
                        val assignmentId = assignment["assignment_id"]?.toString().orEmpty()
                        val assignedDriver = drivers.firstOrNull { it["id"]?.toString() == assignment["driver_id"]?.toString() }
                        val assignedVehicle = fleet.firstOrNull { it["id"]?.toString() == assignment["vehicle_id"]?.toString() }
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(assignment["name"]?.toString() ?: "Company route", fontWeight = FontWeight.Bold)
                                Text("Driver: ${assignedDriver?.get("fullName") ?: "—"} • Vehicle: ${assignedVehicle?.get("plateNumber") ?: "—"}")
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            try {
                                                val response = ApiClient.getService().unassignCompanyVehicle(assignmentId)
                                                if (response.isSuccessful) {
                                                    message = "Assignment removed. The vehicle remains in your fleet."
                                                    loadSection()
                                                } else message = errorText(response)
                                            } catch (e: Exception) {
                                                message = e.message ?: "Could not remove assignment."
                                            } finally { busy = false }
                                        }
                                    },
                                    enabled = !busy && assignmentId.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Remove assignment") }
                            }
                        }
                    }
                }
                "Drivers" -> {
                    Text("Drivers and approvals", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF17233C))
                    Text("Review applications and manage your approved driving team.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    drivers.forEach { d -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(d["fullName"]?.toString() ?: "Driver", fontWeight = FontWeight.Bold)
                        Text("Phone: ${d["phone"] ?: "—"} • Licence: ${d["licenseNumber"] ?: "—"}")
                        Text("Status: ${d["status"] ?: "Unknown"}")
                        if (d["status"]?.toString().equals("PENDING", true)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch { try { val r = ApiClient.getService().updateCompanyDriverApproval(d["id"].toString(), mapOf("action" to "APPROVE")); message = if (r.isSuccessful) "Driver approved. Assign a vehicle and route." else errorText(r); loadSection() } catch (e: Exception) { message = e.message } } }, modifier = Modifier.weight(1f)) { Text("Approve") }
                            OutlinedButton(onClick = { scope.launch { try { val r = ApiClient.getService().updateCompanyDriverApproval(d["id"].toString(), mapOf("action" to "REJECT")); message = if (r.isSuccessful) "Driver application rejected." else errorText(r); loadSection() } catch (e: Exception) { message = e.message } } }, modifier = Modifier.weight(1f)) { Text("Reject") }
                        }
                    } } }
                    if (drivers.isEmpty()) Text("No drivers registered with this company yet.")
                }
                "Routes" -> {
                    Text("Routes and schedules", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF17233C))
                    Text("Define the actual departure city, destination and schedule for every service.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(selected = routeType == "INTERCITY", onClick = { routeType = "INTERCITY" }, label = { Text("Intercity") }, leadingIcon = { Icon(Icons.Default.DirectionsBus, null) })
                        FilterChip(selected = routeType == "DAILY_COMMUTE", onClick = { routeType = "DAILY_COMMUTE" }, label = { Text("Daily commute") }, leadingIcon = { Icon(Icons.Default.Home, null) })
                    }
                    CompanyPortalField("Route name (e.g. Addis Ababa – Hawassa)", routeName, { routeName = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        CompanyPortalField("Departure / origin", routeOrigin, { routeOrigin = it }, modifier = Modifier.weight(1f))
                        CompanyPortalField("Destination", routeDestination, { routeDestination = it }, modifier = Modifier.weight(1f))
                    }
                    if (routeType == "INTERCITY") {
                        CompanyPortalField("Departure time", intercityDeparture, { intercityDeparture = it })
                        Text("Intercity routes have one scheduled departure. Return journeys should be created as a separate route if they run at a different time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            CompanyPortalField("Morning departure", morning, { morning = it }, modifier = Modifier.weight(1f))
                            CompanyPortalField("Evening departure", evening, { evening = it }, modifier = Modifier.weight(1f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        CompanyPortalField("Distance km", distance, { distance = it }, modifier = Modifier.weight(1f))
                        CompanyPortalField("Fare ETB", fare, { fare = it }, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val departure = if (routeType == "INTERCITY") intercityDeparture.trim() else morning.trim()
                                val payload = mapOf(
                                    "name" to routeName.trim(), "routeType" to routeType,
                                    "origin" to routeOrigin.trim(), "destination" to routeDestination.trim(),
                                    "departureTime" to departure,
                                    "morningDeparture" to departure,
                                    "eveningDeparture" to if (routeType == "INTERCITY") departure else evening.trim(),
                                    "distanceKm" to (distance.toDoubleOrNull() ?: 10.0),
                                    "basePriceEtb" to (fare.toDoubleOrNull() ?: 0.0)
                                )
                                val r = if (editingRouteId.isBlank()) ApiClient.getService().createCompanyRoute(payload) else ApiClient.getService().updateCompanyRoute(editingRouteId, payload)
                                if (r.isSuccessful) {
                                    val wasEditing = editingRouteId.isNotBlank()
                                    routeName = ""; routeOrigin = ""; routeDestination = ""; editingRouteId = ""
                                    message = if (wasEditing) "Route updated." else "Route created."
                                    loadSection()
                                } else message = errorText(r)
                            } catch (e: Exception) { message = e.message ?: "Could not create route." } finally { busy = false }
                        }
                    }, enabled = !busy && routeName.isNotBlank() && routeOrigin.isNotBlank() && routeDestination.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (editingRouteId.isBlank()) "Create route" else "Save route changes")
                    }
                    routes.forEach { r -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(r["name"]?.toString() ?: "Route", fontWeight = FontWeight.Bold)
                        Text("${r["origin"] ?: "Origin not set"} → ${r["destination"] ?: "Destination not set"}", style = MaterialTheme.typography.bodyLarge)
                        val type = r["routeType"]?.toString() ?: "DAILY_COMMUTE"
                        Text(if (type == "INTERCITY") "Intercity • Departure: ${r["departureTime"] ?: r["morningDeparture"] ?: "—"}" else "Daily commute • Morning: ${r["morningDeparture"] ?: "—"} • Evening: ${r["eveningDeparture"] ?: "—"}")
                        Text("${r["distanceKm"] ?: "—"} km • Fare: ${r["basePriceEtb"] ?: "—"} ETB")
                        OutlinedButton(onClick = {
                            editingRouteId = r["id"]?.toString().orEmpty()
                            routeName = r["name"]?.toString().orEmpty()
                            routeType = r["routeType"]?.toString() ?: "DAILY_COMMUTE"
                            routeOrigin = r["origin"]?.toString().orEmpty()
                            routeDestination = r["destination"]?.toString().orEmpty()
                            intercityDeparture = r["departureTime"]?.toString() ?: r["morningDeparture"]?.toString() ?: "08:00"
                            morning = r["morningDeparture"]?.toString() ?: "06:30"
                            evening = r["eveningDeparture"]?.toString() ?: "17:30"
                            distance = r["distanceKm"]?.toString() ?: "10"
                            fare = r["basePriceEtb"]?.toString() ?: "0"
                        }, modifier = Modifier.fillMaxWidth()) { Text("Edit this route") }
                    } } }
                    if (routes.isEmpty()) Text("No company routes yet.")
                }
                "Bookings" -> {
                    Text("Passenger bookings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF17233C))
                    Text("Review passenger subscriptions, route assignments and vehicle details.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    bookings.forEach { b -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(14.dp)) {
                        Text(b["passenger_name"]?.toString() ?: "Passenger", fontWeight = FontWeight.Bold)
                        Text("Route: ${b["route_name"] ?: "—"}")
                        Text("Phone: ${b["passenger_phone"] ?: "—"}")
                        Text("Pickup: ${b["pickup_stop_id"] ?: "—"} • Destination: ${b["destination_stop_id"] ?: "—"}")
                        Text("Subscription: ${b["subscription_status"] ?: "—"} • Payment: ${b["payment_status"] ?: "—"}")
                        Text("Vehicle: ${b["vehicle_plate"] ?: "Not assigned"}")
                    } } }
                    if (bookings.isEmpty()) Text("No bookings found for your active company route assignments.")
                }
            }
        }
    }
}
