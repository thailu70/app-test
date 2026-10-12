package com.example.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.localization.AppLanguage
import com.example.data.entity.RouteEntity
import com.example.data.entity.RouteStopEntity
import com.example.ui.theme.*
import com.example.ui.viewmodel.AppRole
import com.example.ui.viewmodel.MainViewModel

@Composable
fun AuthScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val lang by viewModel.currentLanguage.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val registrationMessage by viewModel.registrationMessage.collectAsState()
    val otpChallengeId by viewModel.otpChallengeId.collectAsState()
    val otpProof by viewModel.otpProof.collectAsState()
    val otpMessage by viewModel.otpMessage.collectAsState()
    val testPassengerRegistrationBypass by viewModel.testPassengerRegistrationBypass.collectAsState()
    val availableRoutes by viewModel.signupRoutes.collectAsState()
    val routesRefreshing by viewModel.signupRoutesRefreshing.collectAsState()

    // Passenger and driver share one sign-in/registration screen; switch roles without leaving the form.
    var selectedPortal by remember { mutableStateOf<AppRole?>(AppRole.PASSENGER) }
    var isRegisterMode by remember { mutableStateOf(false) }
    var rememberMe by remember { mutableStateOf(true) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }

    // Form fields
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var registrationValidationMessage by remember { mutableStateOf<String?>(null) }
    var licenseNumber by remember { mutableStateOf("") }
    var companyName by remember { mutableStateOf("") }
    var vehiclePlate by remember { mutableStateOf("") }
    var vehicleModel by remember { mutableStateOf("") }
    var vehicleType by remember { mutableStateOf("MINIBUS_14") }
    var vehicleTypeMenuExpanded by remember { mutableStateOf(false) }
    var adminSecret by remember { mutableStateOf("") }

    // Passenger may select an active route or enter a route request for admin review.
    var selectedRouteId by remember { mutableStateOf("") }
    var selectedRouteName by remember { mutableStateOf("") }
    var requestedRoute by remember { mutableStateOf("") }
    val signupRouteStops by viewModel.signupRouteStops.collectAsState()
    var pickupStopId by remember { mutableStateOf("") }
    var destinationStopId by remember { mutableStateOf("") }
    var stopMenu by remember { mutableStateOf("") }
    var serviceType by remember { mutableStateOf("ONE_WAY") }
    var travelUntil by remember { mutableStateOf("") }
    var shift by remember { mutableStateOf("Morning") }
    var pickupTime by remember { mutableStateOf("06:30") }

    // Never pre-fill the login form with shared/demo credentials.
    LaunchedEffect(selectedPortal, isRegisterMode) {
        viewModel.clearRegistrationOtp()
        otpCode = ""
        phone = ""
        password = ""
        confirmPassword = ""
        registrationValidationMessage = null
        if (isRegisterMode) fullName = ""
        requestedRoute = ""
        selectedRouteId = ""
        selectedRouteName = ""
        pickupStopId = ""
        destinationStopId = ""
        viewModel.loadSignupRouteStops("")
        if (isRegisterMode && selectedPortal == AppRole.PASSENGER) {
            viewModel.refreshSignupRoutes()
        }
    }

    LaunchedEffect(registrationMessage) {
        if (!registrationMessage.isNullOrBlank() && selectedPortal == AppRole.DRIVER) {
            isRegisterMode = false
            password = ""
            confirmPassword = ""
        }
    }

    // Back returns to the default passenger form instead of a separate portal-selection page.
    BackHandler(enabled = selectedPortal != AppRole.PASSENGER) {
        viewModel.clearRegistrationMessage()
        selectedPortal = AppRole.PASSENGER
        isRegisterMode = false
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFEAF8F7), Color(0xFFF5FBF4), Color(0xFFE4F5E9))))
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp)
    ) {
        // App Identity Header
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = TransportGreenPrimary,
                    shape = CircleShape,
                    shadowElevation = 10.dp,
                    modifier = Modifier.size(82.dp).border(4.dp, Color.White.copy(alpha = 0.95f), CircleShape)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.DirectionsBus,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
                Text(
                    text = "Transport Navigator",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (lang == AppLanguage.AMHARIC)
                        "የተቀናጀ የኢትዮጵያ የትራንስፖርት መግቢያ • ገለልተኛ ፖርታሎች"
                    else
                        "Ethiopian Scheduled Transit • Isolated Independent Portals",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600,
                    textAlign = TextAlign.Center
                )
            }
        }

        // =========================================================================
        // CASE 1: PORTAL SELECTION GATEWAY (No role tabs mixed together)
        // =========================================================================
        if (selectedPortal == null) {
            item {
                Surface(
                    color = Slate100,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = TransportGreenPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = if (lang == AppLanguage.AMHARIC)
                                    "ገለልተኛ የተጠቃሚ ፖርታል ይምረጡ"
                                else
                                    "Select Isolated Access Portal",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Slate900
                            )
                            Text(
                                text = if (lang == AppLanguage.AMHARIC)
                                    "ተሳፋሪ፣ አጓጓዥ እና አስተዳዳሪ ራሳቸውን የቻሉ ገለልተኛ መግቢያ አላቸው።"
                                else
                                    "Passengers, Transporters, and Admins operate independently with zero cross-role access.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate600
                            )
                        }
                    }
                }
            }

            // Portal Card 1: Passenger Portal
            item {
                PortalOptionCard(
                    title = if (lang == AppLanguage.AMHARIC) "የተሳፋሪ ፖርታል" else "Commuter Passenger Portal",
                    subtitle = if (lang == AppLanguage.AMHARIC)
                        "ወርሃዊ የጉዞ ምዝገባ፣ የቴሌብር ክፍያ እና የQR መሳፈሪያ ኮድ"
                    else
                        "Route subscriptions, monthly Telebirr payments, and dynamic QR boarding passes",
                    badge = "PASSENGER ACCESS",
                    icon = Icons.Default.Person,
                    accentColor = TransportGreenPrimary,
                    buttonText = if (lang == AppLanguage.AMHARIC) "የተሳፋሪ ፖርታል ክፈት →" else "Open Passenger Portal →",
                    testTag = "open_passenger_portal_button",
                    onClick = {
                        viewModel.clearRegistrationMessage()
                        selectedPortal = AppRole.PASSENGER
                        isRegisterMode = false
                    }
                )
            }

            // Portal Card 2: Transporter / Driver Console
            item {
                PortalOptionCard(
                    title = if (lang == AppLanguage.AMHARIC) "የአጓጓዥ እና ሹፌር ኮንሶል" else "Transporter & Driver Console",
                    subtitle = if (lang == AppLanguage.AMHARIC)
                        "የተሽከርካሪ የመንገደኛ ገደብ (24/14/8)፣ የመንገደኞች ክትትል እና የጉዞ መሪ"
                    else
                        "Fleet trip navigation, stop check-in attendance, and vehicle capacity enforcement",
                    badge = "TRANSPORTER ACCESS",
                    icon = Icons.Default.DirectionsBus,
                    accentColor = Color(0xFFB45309),
                    buttonText = if (lang == AppLanguage.AMHARIC) "የአጓጓዥ ኮንሶል ክፈት →" else "Open Transporter Console →",
                    testTag = "open_transporter_portal_button",
                    onClick = {
                        viewModel.clearRegistrationMessage()
                        selectedPortal = AppRole.DRIVER
                        isRegisterMode = false
                    }
                )
            }


        }

        // =========================================================================
        // CASE 2: DEDICATED INDEPENDENT PORTAL (Completely isolated)
        // =========================================================================
        else {
            val portal = selectedPortal!!

            // Role switcher: passenger, driver, and admin stay on the same sign-in/registration page.
            item {
                Text(
                    if (lang == AppLanguage.AMHARIC) "የመግቢያ አይነት ይምረጡ" else "Continue as",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Slate700,
                    modifier = Modifier.fillMaxWidth()
                )
                TabRow(
                    selectedTabIndex = when (portal) {
                        AppRole.PASSENGER -> 0
                        AppRole.DRIVER -> 1
                        else -> 0
                    },
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                    containerColor = Slate200,
                    indicator = {}
                ) {
                    listOf(AppRole.PASSENGER, AppRole.DRIVER).forEachIndexed { index, role ->
                        Tab(
                            selected = portal == role,
                            onClick = {
                                if (portal != role) {
                                    viewModel.clearRegistrationMessage()
                                    selectedPortal = role
                                    // Preserve Sign In vs Register mode while switching roles on this same page.
                                }
                            },
                            text = {
                                Text(
                                    when (role) {
                                        AppRole.PASSENGER -> if (lang == AppLanguage.AMHARIC) "ተሳፋሪ" else "Passenger"
                                        AppRole.DRIVER -> if (lang == AppLanguage.AMHARIC) "ሹፌር" else "Driver"
                                        else -> if (lang == AppLanguage.AMHARIC) "ተሳፋሪ" else "Passenger"
                                    },
                                    fontWeight = if (portal == role) FontWeight.Bold else FontWeight.Normal,
                                    color = if (portal == role) TransportGreenPrimary else Slate700
                                )
                            },
                            modifier = Modifier.testTag("auth_role_tab_$index")
                        )
                    }
                }
            }

            // Mode Switcher: Sign In vs Register (Inside this isolated portal)
            item {
                TabRow(
                    selectedTabIndex = if (isRegisterMode) 1 else 0,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Slate200),
                    containerColor = Slate200,
                    indicator = {}
                ) {
                    Tab(
                        selected = !isRegisterMode,
                        onClick = { isRegisterMode = false },
                        text = {
                            Text(
                                text = if (lang == AppLanguage.AMHARIC) "ግባ (Sign In)" else "Sign In",
                                fontWeight = if (!isRegisterMode) FontWeight.Bold else FontWeight.Normal,
                                color = if (!isRegisterMode) TransportGreenPrimary else Slate700
                            )
                        },
                        modifier = Modifier.testTag("auth_signin_tab")
                    )
                    Tab(
                        selected = isRegisterMode,
                        onClick = {
                            viewModel.clearRegistrationMessage()
                            isRegisterMode = true
                        },
                        text = {
                            Text(
                                text = if (lang == AppLanguage.AMHARIC) "ተመዝገብ (Register)" else "Register New Account",
                                fontWeight = if (isRegisterMode) FontWeight.Bold else FontWeight.Normal,
                                color = if (isRegisterMode) TransportGreenPrimary else Slate700
                            )
                        },
                        modifier = Modifier.testTag("auth_register_tab")
                    )
                }
            }

            // Auth Form Card (Dedicated to this single role)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_form_card"),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.97f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = if (isRegisterMode) {
                                when (portal) {
                                    AppRole.PASSENGER -> "Register New Commuter Passenger"
                                    AppRole.DRIVER -> "Register New Transporter / Driver Profile"
                                    AppRole.ADMIN -> "Register Transport Operator Organization"
                                }
                            } else {
                                when (portal) {
                                    AppRole.PASSENGER -> "Passenger Sign In"
                                    AppRole.DRIVER -> "Transporter / Driver Sign In"
                                    AppRole.ADMIN -> "Transit Operator Admin Sign In"
                                }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (isRegisterMode && portal == AppRole.DRIVER) {
                            Text(
                                text = "Register yourself and your own vehicle below. Your RoutePass administrator will assign the operating route after registration.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF92400E),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFFF7ED), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            )
                        }

                        if (isRegisterMode) {
                            OutlinedTextField(
                                value = fullName,
                                onValueChange = { fullName = it },
                                label = { Text("Full Legal Name") },
                                placeholder = { Text("E.g. Abebe Kebede") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_fullname_input")
                            )

                            if (portal == AppRole.ADMIN) {
                                OutlinedTextField(
                                    value = companyName,
                                    onValueChange = { companyName = it },
                                    label = { Text("Transport Operator / Company Name") },
                                    placeholder = { Text("E.g. Selam Shuttle Services") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("auth_company_input")
                                )

                                OutlinedTextField(
                                    value = adminSecret,
                                    onValueChange = { adminSecret = it },
                                    label = { Text("Admin Registration Secret Key *") },
                                    placeholder = { Text("Authorized invitation secret required") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("auth_admin_secret_input")
                                )
                            }

                            if (portal == AppRole.DRIVER) {
                                OutlinedTextField(
                                    value = licenseNumber,
                                    onValueChange = { licenseNumber = it },
                                    label = { Text("Commercial Driving License Number") },
                                    placeholder = { Text("E.g. ET-AA-789012") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("auth_license_input")
                                )

                                OutlinedTextField(
                                    value = vehiclePlate,
                                    onValueChange = { vehiclePlate = it.uppercase() },
                                    label = { Text("Your vehicle plate number *") },
                                    placeholder = { Text("E.g. 3-AA-12345") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("auth_vehicle_plate_input")
                                )
                                OutlinedTextField(
                                    value = vehicleModel,
                                    onValueChange = { vehicleModel = it },
                                    label = { Text("Vehicle make / model *") },
                                    placeholder = { Text("E.g. Toyota HiAce") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("auth_vehicle_model_input")
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Your vehicle type *", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    Box {
                                        OutlinedButton(
                                            onClick = { vehicleTypeMenuExpanded = true },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(when (vehicleType) {
                                                "MINIVAN_8" -> "Minivan · 8 seats"
                                                "MINIBUS_14" -> "Minibus · 14 seats"
                                                "HIGER_24" -> "Higer · 24 seats"
                                                "ANBESSA_BUS_30" -> "Bus · 30 seats"
                                                else -> vehicleType
                                            })
                                            Spacer(Modifier.weight(1f))
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose vehicle type")
                                        }
                                        DropdownMenu(
                                            expanded = vehicleTypeMenuExpanded,
                                            onDismissRequest = { vehicleTypeMenuExpanded = false }
                                        ) {
                                            listOf(
                                                "MINIVAN_8" to "Minivan · 8 seats",
                                                "MINIBUS_14" to "Minibus · 14 seats",
                                                "HIGER_24" to "Higer · 24 seats",
                                                "ANBESSA_BUS_30" to "Bus · 30 seats"
                                            ).forEach { (type, label) ->
                                                DropdownMenuItem(
                                                    text = { Text(label) },
                                                    onClick = {
                                                        vehicleType = type
                                                        vehicleTypeMenuExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("Email Address (Optional)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Passenger chooses a subscription route at registration; driver route is assigned by admin.
                            if (portal == AppRole.PASSENGER) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Slate100)
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Route,
                                            contentDescription = null,
                                            tint = TransportGreenPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = if (lang == AppLanguage.AMHARIC)
                                                "የሚመዘገቡበትን የጉዞ መስመር ይምረጡ *"
                                            else
                                                "SELECT ROUTE YOU ARE APPLYING FOR *",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Slate900
                                        )
                                    }

                                    Text(
                                        text = "Select the daily commuter shuttle route you wish to subscribe to:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Slate600
                                    )

                                    if (routesRefreshing) {
                                        Text(
                                            text = if (lang == AppLanguage.AMHARIC) "የነቁ መስመሮችን በመጫን ላይ..." else "Loading active routes from server...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Slate600
                                        )
                                    } else if (availableRoutes.isEmpty()) {
                                        Text(
                                            text = if (lang == AppLanguage.AMHARIC) "ንቁ መስመር አልተገኘም። እባክዎ ቆይተው ይሞክሩ።" else "No active routes are available. Please try again later.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Slate600
                                        )
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        availableRoutes.filter { it.active }.forEach { route ->
                                            val isSelected = selectedRouteId == route.id
                                            Surface(
                                                color = if (isSelected) Color(0xFFD1FAE5) else Color.White,
                                                shape = RoundedCornerShape(10.dp),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    width = if (isSelected) 2.dp else 1.dp,
                                                    color = if (isSelected) TransportGreenPrimary else Slate200
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectedRouteId = route.id
                                                        selectedRouteName = route.name
                                                        requestedRoute = ""
                                                        pickupStopId = ""
                                                        destinationStopId = ""
                                                        viewModel.loadSignupRouteStops(route.id)
                                                    }
                                                    .testTag("route_option_${route.id}")
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = route.name,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isSelected) TransportGreenDark else Slate900
                                                        )
                                                        Text(
                                                            text = "${route.nameAm} • ${route.description}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Slate600
                                                        )
                                                        Text(
                                                            text = "Departure: ${route.morningDeparture} AM & ${route.eveningDeparture} PM",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Color(0xFFB45309),
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }

                                                    Column(horizontalAlignment = Alignment.End) {
                                                        Text(
                                                            text = "ETB ${route.basePriceEtb.toInt()}",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.ExtraBold,
                                                            color = TransportGreenPrimary
                                                        )
                                                        if (isSelected) {
                                                            Icon(
                                                                Icons.Default.CheckCircle,
                                                                contentDescription = "Selected",
                                                                tint = TransportGreenPrimary,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (selectedRouteId.isNotBlank() && signupRouteStops.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text("JOURNEY DETAILS", fontWeight = FontWeight.ExtraBold, color = Slate900)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            listOf("ONE_WAY" to "One Way", "TWO_WAY" to "Round Trip").forEach { (value, label) ->
                                                val selected = serviceType == value
                                                OutlinedButton(
                                                    onClick = { serviceType = value },
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Color(0xFF383536) else Color.White, contentColor = if (selected) Color.White else Slate900),
                                                    shape = RoundedCornerShape(14.dp)
                                                ) { Text(label, fontWeight = FontWeight.SemiBold) }
                                            }
                                        }
                                        RouteStopSelector("Pickup", signupRouteStops, pickupStopId, stopMenu == "pickup", { stopMenu = if (stopMenu == "pickup") "" else "pickup" }, { stopMenu = "" }, { pickupStopId = it; stopMenu = "" })
                                        RouteStopSelector("Destination", signupRouteStops, destinationStopId, stopMenu == "destination", { stopMenu = if (stopMenu == "destination") "" else "destination" }, { stopMenu = "" }, { destinationStopId = it; stopMenu = "" })
                                        OutlinedTextField(
                                            value = travelUntil,
                                            onValueChange = { travelUntil = it.take(10) },
                                            label = { Text("Travels until (YYYY-MM-DD)") },
                                            placeholder = { Text("YYYY-MM-DD") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().testTag("passenger_travel_until")
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            var shiftMenuExpanded by remember { mutableStateOf(false) }
                                            var timeMenuExpanded by remember { mutableStateOf(false) }
                                            Column(Modifier.weight(1f)) {
                                                Text("Shift", fontWeight = FontWeight.SemiBold, color = Slate900)
                                                Box {
                                                    OutlinedButton(onClick = { shiftMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                                        Text(shift, maxLines = 1)
                                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                                    }
                                                    DropdownMenu(expanded = shiftMenuExpanded, onDismissRequest = { shiftMenuExpanded = false }) {
                                                        listOf("Morning", "Evening").forEach { value ->
                                                            DropdownMenuItem(text = { Text(value) }, onClick = { shift = value; shiftMenuExpanded = false; pickupTime = if (value == "Morning") "06:30" else "17:30" })
                                                        }
                                                    }
                                                }
                                            }
                                            Column(Modifier.weight(1f)) {
                                                Text("Pickup time", fontWeight = FontWeight.SemiBold, color = Slate900)
                                                Box {
                                                    OutlinedButton(onClick = { timeMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                                        Text(pickupTime, maxLines = 1)
                                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                                    }
                                                    DropdownMenu(expanded = timeMenuExpanded, onDismissRequest = { timeMenuExpanded = false }) {
                                                        (if (shift == "Morning") listOf("06:00", "06:30", "07:00", "07:30", "08:00") else listOf("16:30", "17:00", "17:30", "18:00", "18:30")).forEach { value ->
                                                            DropdownMenuItem(text = { Text(value) }, onClick = { pickupTime = value; timeMenuExpanded = false })
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        Surface(color = Color(0xFFF4F4F4), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("Monthly commuter pass", fontWeight = FontWeight.Bold, color = Slate900)
                                                Text("ETB ${route.basePriceEtb.toInt()} / month", fontWeight = FontWeight.ExtraBold, color = TransportGreenPrimary, style = MaterialTheme.typography.titleMedium)
                                                Text("Vehicle type and final assignment are confirmed by the RoutePass administrator.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (isRegisterMode && portal == AppRole.PASSENGER) {
                            OutlinedTextField(
                                value = requestedRoute,
                                onValueChange = {
                                    requestedRoute = it.take(200)
                                    if (it.isNotBlank()) {
                                        selectedRouteId = ""
                                        selectedRouteName = ""
                                    }
                                },
                                label = { Text("Route not listed? Enter your requested route") },
                                placeholder = { Text("Example: CMC to Bole, near my home and workplace") },
                                supportingText = { Text("Your request stays PENDING until an administrator reviews it and assigns an available route.") },
                                minLines = 2,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth().testTag("custom_route_request_input")
                            )
                        }

                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text("Mobile Phone Number (+251 / 09...)") },
                            placeholder = { Text("+251911223344") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_phone_input")
                        )



                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                registrationValidationMessage = null
                            },
                            label = { Text("Security Password / PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_password_input")
                        )

                        if (!isRegisterMode) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = rememberMe,
                                        onCheckedChange = { rememberMe = it },
                                        colors = CheckboxDefaults.colors(checkedColor = TransportGreenPrimary)
                                    )
                                    Text("Remember Me", color = Slate900, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(
                                    text = "Forgot Password?",
                                    color = TransportGreenPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .clickable { showForgotPasswordDialog = true }
                                        .padding(vertical = 8.dp)
                                        .testTag("forgot_password_button")
                                )
                            }
                        }

                        if (isRegisterMode) {
                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = {
                                    confirmPassword = it
                                    registrationValidationMessage = null
                                },
                                label = { Text("Confirm password") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                singleLine = true,
                                isError = confirmPassword.isNotEmpty() && password != confirmPassword,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_confirm_password_input")
                            )
                        }

                        if (isRegisterMode && portal != AppRole.ADMIN && !(portal == AppRole.PASSENGER && testPassengerRegistrationBypass)) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Text("Step 2: Verify mobile number to complete registration", fontWeight = FontWeight.Bold, color = Slate900)
                                Button(
                                    onClick = {
                                        val requiredDataError = when {
                                            fullName.isBlank() -> "Enter your full name before requesting OTP."
                                            phone.isBlank() -> "Enter your mobile number first."
                                            password.length < 10 -> "Use a password with at least 10 characters before requesting OTP."
                                            password != confirmPassword -> "The two passwords do not match."
                                            portal == AppRole.PASSENGER && selectedRouteId.isBlank() && requestedRoute.isBlank() -> "Select an available route or enter the route you need."
                                            portal == AppRole.PASSENGER && selectedRouteId.isNotBlank() && signupRouteStops.isNotEmpty() && (pickupStopId.isBlank() || destinationStopId.isBlank()) -> "Choose both pickup and destination stops."
                                            portal == AppRole.PASSENGER && pickupStopId.isNotBlank() && pickupStopId == destinationStopId -> "Pickup and destination must be different stops."
                                            portal == AppRole.DRIVER && (licenseNumber.isBlank() || vehiclePlate.isBlank() || vehicleModel.isBlank()) -> "Complete your driver licence and vehicle details first."
                                            else -> null
                                        }
                                        if (requiredDataError != null) registrationValidationMessage = requiredDataError
                                        else {
                                            registrationValidationMessage = null
                                            otpCode = ""
                                            viewModel.requestRegistrationOtp(phone)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (otpChallengeId.isNullOrBlank()) "SEND SMS VERIFICATION CODE" else "RESEND SMS CODE")
                                }
                                if (!otpChallengeId.isNullOrBlank()) {
                                    OutlinedTextField(
                                        value = otpCode,
                                        onValueChange = { otpCode = it.filter(Char::isDigit).take(6) },
                                        label = { Text("6-digit SMS verification code") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = { viewModel.verifyRegistrationOtp(otpCode) },
                                        enabled = otpCode.length == 6 && otpProof.isNullOrBlank(),
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text(if (otpProof.isNullOrBlank()) "VERIFY MOBILE NUMBER" else "MOBILE NUMBER VERIFIED") }
                                }
                                otpMessage?.let { Text(it, color = if (otpProof.isNullOrBlank()) Slate700 else TransportGreenPrimary, style = MaterialTheme.typography.bodySmall) }
                            }
                        }

                        if (isRegisterMode && portal == AppRole.PASSENGER && testPassengerRegistrationBypass) {
                            Text(
                                text = "TEST MODE: Passenger registration skips SMS OTP. Disable server test mode before public launch.",
                                color = Color(0xFFB45309),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        registrationValidationMessage?.let { message ->
                            Text(
                                text = message,
                                color = StatusExpiredRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        authError?.let { err ->
                            Text(
                                text = err,
                                color = StatusExpiredRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        registrationMessage?.let { message ->
                            Text(
                                text = message,
                                color = TransportGreenPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFECFDF5), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            )
                        }

                        Button(
                            onClick = {
                                if (isRegisterMode) {
                                    val validationMessage = when {
                                        password.length < 10 -> "Use a password with at least 10 characters."
                                        password != confirmPassword -> "The two passwords do not match. Please re-enter them."
                                        portal == AppRole.PASSENGER && selectedRouteId.isBlank() && requestedRoute.isBlank() -> "Select an available route or enter the route you need."
                                        portal == AppRole.PASSENGER && selectedRouteId.isNotBlank() && signupRouteStops.isNotEmpty() && (pickupStopId.isBlank() || destinationStopId.isBlank()) -> "Choose both pickup and destination stops."
                                        portal == AppRole.PASSENGER && pickupStopId.isNotBlank() && pickupStopId == destinationStopId -> "Pickup and destination must be different stops."
                                        portal == AppRole.PASSENGER && selectedRouteId.isNotBlank() && travelUntil.isNotBlank() && !travelUntil.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> "Enter the travel end date as YYYY-MM-DD."
                                        portal != AppRole.ADMIN && !(portal == AppRole.PASSENGER && testPassengerRegistrationBypass) && otpProof.isNullOrBlank() -> "Verify your mobile number by SMS before registering."
                                        else -> null
                                    }
                                    if (validationMessage != null) {
                                        registrationValidationMessage = validationMessage
                                    } else {
                                        registrationValidationMessage = null
                                        val routePreferences = if (portal == AppRole.PASSENGER && selectedRouteId.isNotBlank() && pickupStopId.isNotBlank() && destinationStopId.isNotBlank()) {
                                            "Route: $selectedRouteName | Pickup: ${signupRouteStops.firstOrNull { it.id == pickupStopId }?.stopName.orEmpty()} | Destination: ${signupRouteStops.firstOrNull { it.id == destinationStopId }?.stopName.orEmpty()} | Service: $serviceType | Travel until: $travelUntil | Shift: $shift | Pickup time: $pickupTime"
                                        } else requestedRoute.trim()
                                        viewModel.register(
                                            fullName = fullName,
                                            phone = phone,
                                            email = email,
                                            password = password,
                                            role = portal,
                                            licenseNumber = licenseNumber,
                                            companyName = companyName,
                                            assignedVehiclePlate = vehiclePlate,
                                            vehicleModel = vehicleModel,
                                            vehicleType = vehicleType,
                                            appliedRouteId = if (requestedRoute.isBlank()) selectedRouteId else "",
                                            appliedRouteName = if (requestedRoute.isBlank()) selectedRouteName else "",
                                            requestedRoute = routePreferences,
                                            adminSecret = adminSecret,
                                             otpProof = otpProof.orEmpty()
                                        )
                                    }
                                } else {
                                    viewModel.clearRegistrationMessage()
                                    viewModel.login(phone, password, portal)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = when (portal) {
                                    AppRole.PASSENGER -> TransportGreenPrimary
                                    AppRole.DRIVER -> Color(0xFFB45309)
                                    AppRole.ADMIN -> TelebirrBlue
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("auth_submit_button")
                        ) {
                            Text(
                                text = if (isRegisterMode && portal == AppRole.DRIVER) "SUBMIT DRIVER REGISTRATION" else if (isRegisterMode) "COMPLETE REGISTRATION & ENTER" else "SIGN IN TO PORTAL",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

        }
    }

    if (showForgotPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showForgotPasswordDialog = false },
            title = { Text("Forgot Password?") },
            text = { Text("Password recovery by SMS is not yet connected. Please contact the RoutePass administrator to reset your account password.") },
            confirmButton = {
                TextButton(onClick = { showForgotPasswordDialog = false }) {
                    Text("OK", color = TransportGreenPrimary)
                }
            }
        )
    }
}

@Composable
fun PortalOptionCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    accentColor: Color,
    buttonText: String,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag(testTag),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = accentColor.copy(alpha = 0.15f),
                    shape = CircleShape,
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Surface(
                    color = accentColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = badge,
                        color = accentColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 10.sp
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
            }

            Button(
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = buttonText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun RouteStopSelector(
    label: String,
    stops: List<RouteStopEntity>,
    selectedStopId: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val selectedName = stops.firstOrNull { it.id == selectedStopId }?.stopName ?: label
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Slate900)
        Box {
            OutlinedButton(onClick = onExpand, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(12.dp)) {
                Text(selectedName, modifier = Modifier.weight(1f), textAlign = TextAlign.Start, color = if (selectedStopId.isBlank()) Slate400 else Slate900)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose $label")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
                stops.sortedBy { it.stopOrder }.forEach { stop ->
                    DropdownMenuItem(text = { Text(stop.stopName) }, onClick = { onSelect(stop.id) }, leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) })
                }
            }
        }
    }
}
