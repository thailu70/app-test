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
    val availableRoutes by viewModel.allRoutes.collectAsState()

    // Dedicated Independent Portal Gateway (null = selecting portal; non-null = inside specific portal)
    var selectedPortal by remember { mutableStateOf<AppRole?>(null) }
    var isRegisterMode by remember { mutableStateOf(false) }

    // Form fields
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("123456") }
    var licenseNumber by remember { mutableStateOf("") }
    var companyName by remember { mutableStateOf("") }
    var vehiclePlate by remember { mutableStateOf("") }
    var adminSecret by remember { mutableStateOf("") }

    // Applied Route Selection for Passenger and Driver
    var selectedRouteId by remember { mutableStateOf("route_bole_merkato") }
    var selectedRouteName by remember { mutableStateOf("Bole → Merkato") }

    // Automatically synchronize default route if available
    LaunchedEffect(availableRoutes) {
        if (selectedRouteId.isBlank() && availableRoutes.isNotEmpty()) {
            selectedRouteId = availableRoutes.first().id
            selectedRouteName = availableRoutes.first().name
        }
    }

    // Pre-fill default phone when entering portal for convenience
    LaunchedEffect(selectedPortal, isRegisterMode) {
        if (!isRegisterMode && selectedPortal != null) {
            when (selectedPortal) {
                AppRole.PASSENGER -> phone = "+251911223344"
                AppRole.DRIVER -> phone = "+251911998877"
                AppRole.ADMIN -> phone = "+251910001122"
                null -> {}
            }
        } else {
            phone = ""
            fullName = ""
        }
    }

    // Handle back button when inside a portal to safely return to Portal Gateway
    BackHandler(enabled = selectedPortal != null) {
        selectedPortal = null
        isRegisterMode = false
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 48.dp)
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
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.DirectionsBus,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
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
                        selectedPortal = AppRole.DRIVER
                        isRegisterMode = false
                    }
                )
            }

            // Portal Card 3: Operator / Admin Center
            item {
                PortalOptionCard(
                    title = if (lang == AppLanguage.AMHARIC) "የትራንስፖርት ኦፕሬተር እና አስተዳዳሪ" else "Transit Operator & Admin Center",
                    subtitle = if (lang == AppLanguage.AMHARIC)
                        "የጉዞ መስመሮች እና ታሪፍ፣ የተሽከርካሪዎች ቁጥጥር፣ የቴሌብር ገቢ እና ማሳወቂያዎች"
                    else
                        "Route creation, fleet operations, Telebirr reconciliation, and system audits",
                    badge = "OPERATOR ADMIN",
                    icon = Icons.Default.AdminPanelSettings,
                    accentColor = TelebirrBlue,
                    buttonText = if (lang == AppLanguage.AMHARIC) "የኦፕሬተር ፖርታል ክፈት →" else "Open Operator Center →",
                    testTag = "open_admin_portal_button",
                    onClick = {
                        selectedPortal = AppRole.ADMIN
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

            // Portal Active Header with Back to Portals button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            selectedPortal = null
                            isRegisterMode = false
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate700),
                        modifier = Modifier.testTag("back_to_portals_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (lang == AppLanguage.AMHARIC) "ፖርታል ቀይር" else "Change Portal",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    Surface(
                        color = when (portal) {
                            AppRole.PASSENGER -> TransportGreenPrimary.copy(alpha = 0.15f)
                            AppRole.DRIVER -> Color(0xFFF59E0B).copy(alpha = 0.15f)
                            AppRole.ADMIN -> TelebirrBlue.copy(alpha = 0.15f)
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            when (portal) {
                                AppRole.PASSENGER -> TransportGreenPrimary
                                AppRole.DRIVER -> Color(0xFFB45309)
                                AppRole.ADMIN -> TelebirrBlue
                            }
                        )
                    ) {
                        Text(
                            text = when (portal) {
                                AppRole.PASSENGER -> "PASSENGER PORTAL"
                                AppRole.DRIVER -> "TRANSPORTER CONSOLE"
                                AppRole.ADMIN -> "OPERATOR CENTER"
                            },
                            color = when (portal) {
                                AppRole.PASSENGER -> TransportGreenDark
                                AppRole.DRIVER -> Color(0xFFB45309)
                                AppRole.ADMIN -> TelebirrBlue
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
                        onClick = { isRegisterMode = true },
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
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
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
                                    onValueChange = { vehiclePlate = it },
                                    label = { Text("Assigned Vehicle Plate Number") },
                                    placeholder = { Text("E.g. AA-12345 (Toyota Coaster)") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("auth_vehicle_plate_input")
                                )
                            }

                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("Email Address (Optional)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // ROUTE SELECTION REQUIREMENT FOR BOTH PASSENGER AND DRIVER
                            if (portal == AppRole.PASSENGER || portal == AppRole.DRIVER) {
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
                                        text = if (portal == AppRole.PASSENGER)
                                            "Select the daily commuter shuttle route you wish to subscribe to:"
                                        else
                                            "Select the assigned transport route you will be operating:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Slate600
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        availableRoutes.forEach { route ->
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
                                }
                            }
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
                            onValueChange = { password = it },
                            label = { Text("Security Password / PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_password_input")
                        )

                        authError?.let { err ->
                            Text(
                                text = err,
                                color = StatusExpiredRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = {
                                if (isRegisterMode) {
                                    viewModel.register(
                                        fullName = fullName,
                                        phone = phone,
                                        email = email,
                                        password = password,
                                        role = portal,
                                        licenseNumber = licenseNumber,
                                        companyName = companyName,
                                        assignedVehiclePlate = vehiclePlate,
                                        appliedRouteId = selectedRouteId,
                                        appliedRouteName = selectedRouteName,
                                        adminSecret = adminSecret
                                    )
                                } else {
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
                                text = if (isRegisterMode) "COMPLETE REGISTRATION & ENTER" else "SIGN IN TO PORTAL",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Quick 1-Tap Demo Login (Isolated to THIS portal only)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Slate100)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "⚡ Quick Demo Access (${portal.name.lowercase().replaceFirstChar { it.uppercase() }}):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Slate700
                        )

                        when (portal) {
                            AppRole.PASSENGER -> {
                                OutlinedButton(
                                    onClick = { viewModel.quickLoginAs(AppRole.PASSENGER) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("quick_login_passenger"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TransportGreenPrimary)
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Log In as Passenger (Abebe Kebede)")
                                }
                            }
                            AppRole.DRIVER -> {
                                OutlinedButton(
                                    onClick = { viewModel.quickLoginAs(AppRole.DRIVER) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("quick_login_driver"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB45309))
                                ) {
                                    Icon(Icons.Default.DirectionsBus, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Log In as Transporter / Driver (Abebe Alemu)")
                                }
                            }
                            AppRole.ADMIN -> {
                                OutlinedButton(
                                    onClick = { viewModel.quickLoginAs(AppRole.ADMIN) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("quick_login_admin"),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TelebirrBlue)
                                ) {
                                    Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Log In as Transport Operator / Admin")
                                }
                            }
                        }
                    }
                }
            }
        }
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
