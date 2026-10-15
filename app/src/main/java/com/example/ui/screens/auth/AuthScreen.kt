package com.example.ui.screens.auth

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.localization.AppLanguage
import com.example.data.entity.RouteEntity
import com.example.data.api.ApiClient
import com.example.data.api.TransportCompanyDto
import com.example.ui.theme.*
import com.example.ui.viewmodel.AppRole
import com.example.ui.viewmodel.MainViewModel

@Composable
fun AuthScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    initialPortal: AppRole? = null,
    initialRegisterMode: Boolean = false,
    initialRouteId: String? = null,
    initialRouteName: String? = null,
    initialServiceTier: String? = null
) {
    val context = LocalContext.current
    val lang by viewModel.currentLanguage.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val registrationMessage by viewModel.registrationMessage.collectAsState()
    val availableRoutes by viewModel.allRoutes.collectAsState()

    // Dedicated Independent Portal Gateway (null = selecting portal; non-null = inside specific portal)
    var selectedPortal by remember { mutableStateOf<AppRole?>(initialPortal ?: AppRole.PASSENGER) }
    var isRegisterMode by remember { mutableStateOf(initialRegisterMode) }
    var showPasswordHelp by remember { mutableStateOf(false) }

    // Form fields
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var registrationValidationMessage by remember { mutableStateOf<String?>(null) }
    var licenseNumber by remember { mutableStateOf("") }
    var companyName by remember { mutableStateOf("") }
    var vehiclePlate by remember { mutableStateOf("") }
    var vehicleModel by remember { mutableStateOf("") }
    var vehicleType by remember { mutableStateOf("MINIBUS_14") }
    var vehicleTypeMenuExpanded by remember { mutableStateOf(false) }
    var adminSecret by remember { mutableStateOf("") }

    // Applied Route Selection for Passenger and Driver
    var selectedRouteId by remember { mutableStateOf(initialRouteId ?: if (initialRouteName == "Requested commuter route") "" else "route_bole_merkato") }
    var selectedRouteName by remember { mutableStateOf(initialRouteName ?: "Bole → Merkato") }
    var requestPickup by remember { mutableStateOf("") }
    var requestDestination by remember { mutableStateOf("") }
    var commuteDirection by remember { mutableStateOf("Two way") }
    var driverRouteMode by remember { mutableStateOf("existing") }
    var driverPickup by remember { mutableStateOf("") }
    var driverDestination by remember { mutableStateOf("") }
    var driverDepartureTime by remember { mutableStateOf("06:30") }
    var driverJourneyType by remember { mutableStateOf("Two way") }
    var driverServiceTier by remember { mutableStateOf("Economy") }
    var driverServiceMode by remember { mutableStateOf("Daily Commuter") }
    var driverTransportCompany by remember { mutableStateOf("") }
    var transportCompanies by remember { mutableStateOf<List<TransportCompanyDto>>(emptyList()) }
    var companyMenuExpanded by remember { mutableStateOf(false) }
    var companyDirectoryMessage by remember { mutableStateOf<String?>(null) }
    val isCustomCommuterRequest = initialRouteName == "Requested commuter route" && initialRouteId.isNullOrBlank()
    var selectedServiceTier by remember { mutableStateOf(initialServiceTier ?: "Economy") }
    var showRoutePicker by remember { mutableStateOf(initialRouteName.isNullOrBlank()) }

    LaunchedEffect(initialPortal, initialRegisterMode, initialRouteId, initialRouteName, initialServiceTier) {
        if (initialPortal != null) selectedPortal = initialPortal
        isRegisterMode = initialRegisterMode
        if (!initialRouteId.isNullOrBlank()) selectedRouteId = initialRouteId
        if (!initialRouteName.isNullOrBlank()) { selectedRouteName = initialRouteName; showRoutePicker = false }
        if (!initialServiceTier.isNullOrBlank()) selectedServiceTier = initialServiceTier
    }

    LaunchedEffect(driverServiceMode) {
        if (driverServiceMode == "Intercity") {
            try {
                val response = ApiClient.getService().getTransportCompanies()
                if (response.isSuccessful && response.body()?.success == true) {
                    transportCompanies = response.body()?.companies.orEmpty()
                    companyDirectoryMessage = if (transportCompanies.isEmpty()) "No registered transport companies are available yet. Register the company with the authorized secret code first." else null
                    if (transportCompanies.none { it.name == driverTransportCompany }) driverTransportCompany = ""
                } else {
                    transportCompanies = emptyList()
                    companyDirectoryMessage = "Could not load registered companies. Check your connection and try again."
                }
            } catch (_: Exception) {
                transportCompanies = emptyList()
                companyDirectoryMessage = "Could not load registered companies. Check your connection and try again."
            }
        } else {
            companyMenuExpanded = false
            companyDirectoryMessage = null
            driverTransportCompany = ""
        }
    }
    // Automatically synchronize default route if available
    LaunchedEffect(availableRoutes) {
        if (!isCustomCommuterRequest && (selectedRouteId.isBlank() || availableRoutes.none { it.id == selectedRouteId }) && initialRouteId.isNullOrBlank() && availableRoutes.isNotEmpty()) {
            selectedRouteId = availableRoutes.first().id
            selectedRouteName = availableRoutes.first().name
        }
    }

    // Never pre-fill the login form with shared/demo credentials.
    LaunchedEffect(selectedPortal, isRegisterMode) {
        phone = ""
        password = ""
        confirmPassword = ""
        registrationValidationMessage = null
        if (isRegisterMode) fullName = ""
    }

    LaunchedEffect(registrationMessage) {
        if (!registrationMessage.isNullOrBlank() && selectedPortal == AppRole.DRIVER) {
            isRegisterMode = false
            password = ""
            confirmPassword = ""
        }
    }

    if (showPasswordHelp) {
        AlertDialog(
            onDismissRequest = { showPasswordHelp = false },
            title = { Text("Password help") },
            text = { Text("Password reset is not connected to the server yet. Please contact your PASSO administrator to reset your account password.") },
            confirmButton = { TextButton(onClick = { showPasswordHelp = false }) { Text("OK") } }
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (!isRegisterMode) 8.dp else 16.dp),
        contentPadding = PaddingValues(top = if (!isRegisterMode) 6.dp else 16.dp, bottom = if (!isRegisterMode) 6.dp else 28.dp)
    ) {
        // PASSO sign-in identity panel, styled to match the supplied reference.
        item {
            Surface(
                color = Color(0xFF172D58),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(Color(0xFF172D58), Color(0xFF075A78), Color(0xFF078875))
                            )
                        )
                        .padding(horizontal = 14.dp, vertical = if (!isRegisterMode) 10.dp else 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (!isRegisterMode) 4.dp else 8.dp)
                ) {
                    Surface(color = Color.White.copy(alpha = 0.16f), shape = CircleShape, modifier = Modifier.size(if (!isRegisterMode) 42.dp else 58.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                    Text(
                        text = "PASSO • Tap. Move. Clear.",
                        style = if (selectedPortal == AppRole.PASSENGER && !isRegisterMode) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (lang == AppLanguage.AMHARIC)
                            "የተቀናጀ የኢትዮጵያ የትራንስፖርት መግቢያ • ገለልተኛ ፖርታሎች"
                        else
                            "Ethiopian Scheduled Transit • Independent Portals",
                        style = if (!isRegisterMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.88f),
                        textAlign = TextAlign.Center
                    )
                    Surface(color = Color.White.copy(alpha = 0.94f), shape = RoundedCornerShape(50.dp)) {
                        Text("PASSENGER PORTAL", color = Color(0xFF154A3A), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = if (!isRegisterMode) 4.dp else 7.dp))
                    }
                }
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
                    accentColor = Color(0xFF1769C2),
                    buttonText = if (lang == AppLanguage.AMHARIC) "የአጓጓዥ ኮንሶል ክፈት →" else "Open Transporter Console →",
                    testTag = "open_transporter_portal_button",
                    onClick = {
                        viewModel.clearRegistrationMessage()
                        selectedPortal = AppRole.DRIVER
                        isRegisterMode = false
                    }
                )
            }

            // Company sign-up and login use the secured company portal.
            item {
                PortalOptionCard(
                    title = if (lang == AppLanguage.AMHARIC) "የትራንስፖርት ድርጅት ፖርታል" else "Transport Company Portal",
                    subtitle = if (lang == AppLanguage.AMHARIC)
                        "የድርጅት ምዝገባ፣ መግቢያ፣ ተሽከርካሪዎች እና የጉዞ ምደባ"
                    else
                        "Company sign-up with an admin invitation code, secure login, fleet and trip assignments.",
                    badge = "COMPANY ACCESS",
                    icon = Icons.Default.Business,
                    accentColor = Color(0xFF53677F),
                    buttonText = if (lang == AppLanguage.AMHARIC) "የድርጅት ፖርታል ክፈት →" else "Open Company Sign-up / Login →",
                    testTag = "open_company_portal_button",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://routepass.duckdns.org/company/"))
                        context.startActivity(intent)
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
                    Spacer(Modifier.width(1.dp))

                    Surface(
                        color = when (portal) {
                            AppRole.PASSENGER -> TransportGreenPrimary.copy(alpha = 0.15f)
                            AppRole.DRIVER -> Color(0xFF00A982).copy(alpha = 0.15f)
                            AppRole.ADMIN -> TelebirrBlue.copy(alpha = 0.15f)
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            when (portal) {
                                AppRole.PASSENGER -> TransportGreenPrimary
                                AppRole.DRIVER -> Color(0xFF1769C2)
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
                                AppRole.DRIVER -> Color(0xFF1769C2)
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
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(if (portal == AppRole.PASSENGER && !isRegisterMode) 12.dp else 20.dp),
                        verticalArrangement = Arrangement.spacedBy(if (portal == AppRole.PASSENGER && !isRegisterMode) 7.dp else 14.dp)
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
                            style = if (portal == AppRole.PASSENGER && !isRegisterMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (isRegisterMode && portal == AppRole.DRIVER) {
                            Text(
                                text = "Daily Commute drivers register their own vehicle. For Intercity, the transport company owns and assigns the vehicle, so you do not enter a plate number here. Your driver profile must be approved before trips are assigned.",
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
                                    label = { Text("Company Registration Secret Code *") },
                                    placeholder = { Text("Enter the secret code provided by RoutePass administration") },
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

                                if (driverServiceMode != "Intercity") {
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
                            }

                            if (isRegisterMode && portal == AppRole.DRIVER) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF0F6FF)).padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("Operating preferences", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color(0xFF123C78))
                                    Text("Choose one service type. Daily commuter drivers select or propose a route. City Travel drivers select their transport company. Approval and trip assignment are required before operating.", style = MaterialTheme.typography.bodySmall, color = Slate700)
                                    Text("Service type", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Slate900)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        listOf("Daily Commuter", "Intercity").forEach { mode ->
                                            FilterChip(selected = driverServiceMode == mode, onClick = {
                                                driverServiceMode = mode
                                                if (mode == "Intercity") { driverRouteMode = "existing"; selectedRouteId = ""; selectedRouteName = ""; driverPickup = ""; driverDestination = "" }
                                                else { driverTransportCompany = "" }
                                            }, label = { Text(if (mode == "Intercity") "City Travel" else "Daily", maxLines = 1) }, modifier = Modifier.weight(1f))
                                        }
                                    }
                                    if (driverServiceMode == "Intercity") {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            OutlinedTextField(
                                                value = driverTransportCompany,
                                                onValueChange = {},
                                                readOnly = true,
                                                label = { Text("Transport company *") },
                                                placeholder = { Text("Select a registered company") },
                                                leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) },
                                                trailingIcon = {
                                                    IconButton(onClick = { companyMenuExpanded = !companyMenuExpanded }) {
                                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose transport company")
                                                    }
                                                },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth().testTag("driver_transport_company_input")
                                                    .clickable { companyMenuExpanded = true }
                                            )
                                            DropdownMenu(expanded = companyMenuExpanded, onDismissRequest = { companyMenuExpanded = false }) {
                                                transportCompanies.forEach { company ->
                                                    DropdownMenuItem(
                                                        text = { Text(company.name) },
                                                        onClick = {
                                                            driverTransportCompany = company.name
                                                            companyMenuExpanded = false
                                                            companyDirectoryMessage = null
                                                        }
                                                    )
                                                }
                                            }
                                            companyDirectoryMessage?.let { Text(it, color = Slate600, style = MaterialTheme.typography.bodySmall) }
                                        }
                                        Text("Select a company already registered with RoutePass. Your driver account remains pending until approved.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                                    } else {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            FilterChip(selected = driverRouteMode == "existing", onClick = { driverRouteMode = "existing" }, label = { Text("Existing route") }, modifier = Modifier.weight(1f))
                                            FilterChip(selected = driverRouteMode == "custom", onClick = { driverRouteMode = "custom" }, label = { Text("Propose route") }, modifier = Modifier.weight(1f))
                                        }
                                        if (driverRouteMode == "existing") {
                                        if (availableRoutes.isEmpty()) {
                                            Text("No active routes are currently listed. Choose Propose route to submit your route details.", color = Slate600, style = MaterialTheme.typography.bodySmall)
                                        } else {
                                            availableRoutes.filter { it.active }.forEach { route ->
                                                val chosen = selectedRouteId == route.id
                                                Surface(
                                                    color = if (chosen) Color(0xFFDCEBFF) else Color.White,
                                                    shape = RoundedCornerShape(10.dp),
                                                    border = androidx.compose.foundation.BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) Color(0xFF1769C2) else Slate200),
                                                    modifier = Modifier.fillMaxWidth().clickable {
                                                        selectedRouteId = route.id
                                                        selectedRouteName = route.name
                                                        showRoutePicker = false
                                                    }
                                                ) {
                                                    Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Column(Modifier.weight(1f)) {
                                                            Text(route.name, fontWeight = FontWeight.Bold, color = Slate900)
                                                            Text("Morning " + route.morningDeparture + " • Evening " + route.eveningDeparture, color = Slate600, style = MaterialTheme.typography.bodySmall)
                                                        }
                                                        if (chosen) Icon(Icons.Default.CheckCircle, contentDescription = "Selected route", tint = Color(0xFF1769C2))
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        OutlinedTextField(value = driverPickup, onValueChange = { driverPickup = it }, label = { Text("Pickup / departure location") }, placeholder = { Text("E.g. Alem Gena") }, leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                        OutlinedTextField(value = driverDestination, onValueChange = { driverDestination = it }, label = { Text("Destination") }, placeholder = { Text("E.g. Sefera") }, leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                        OutlinedTextField(value = driverDepartureTime, onValueChange = { driverDepartureTime = it }, label = { Text("Preferred departure time") }, placeholder = { Text("06:30") }, leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                        Text("Journey type", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Slate900)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            listOf("One way", "Two way").forEach { option ->
                                                FilterChip(selected = driverJourneyType == option, onClick = { driverJourneyType = option }, label = { Text(option) }, modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                    }
                                    Text("Vehicle service preference", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Slate900)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        listOf("VIP", "Deluxe", "Economy").forEach { tier ->
                                            FilterChip(selected = driverServiceTier == tier, onClick = { driverServiceTier = tier }, label = { Text(tier) }, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }

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
                                                if (showRoutePicker) "CHOOSE YOUR COMMUTER ROUTE" else "YOUR COMMUTER ROUTE",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Slate900
                                        )
                                    }

                                    if (isCustomCommuterRequest) {
                                        OutlinedTextField(
                                            value = requestPickup,
                                            onValueChange = { requestPickup = it },
                                            label = { Text("Pickup location") },
                                            placeholder = { Text("E.g. Alem Gena") },
                                            leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().testTag("commuter_pickup_input")
                                        )
                                        OutlinedTextField(
                                            value = requestDestination,
                                            onValueChange = { requestDestination = it },
                                            label = { Text("Destination") },
                                            placeholder = { Text("E.g. Sefera") },
                                            leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().testTag("commuter_destination_input")
                                        )
                                        Text("Journey type", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Slate900)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            listOf("One way", "Two way").forEach { option ->
                                                FilterChip(
                                                    selected = commuteDirection == option,
                                                    onClick = { commuteDirection = option },
                                                    label = { Text(option) },
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                        Text("Commute service tier", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Slate900)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                            listOf("VIP", "Deluxe", "Economy").forEach { tier ->
                                                FilterChip(
                                                    selected = selectedServiceTier == tier,
                                                    onClick = { selectedServiceTier = tier },
                                                    label = { Text(tier) },
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    } else if (!showRoutePicker && selectedRouteName.isNotBlank()) {
                                        val chosenRoute = availableRoutes.firstOrNull { it.id == selectedRouteId }
                                        Surface(
                                            color = Color(0xFFECFDF5),
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB7E4D0)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(selectedRouteName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Slate900)
                                                    Text("Tier: $selectedServiceTier", style = MaterialTheme.typography.bodyMedium, color = Slate700)
                                                    if (chosenRoute != null) {
                                                        Text("Morning ${chosenRoute.morningDeparture} • Evening ${chosenRoute.eveningDeparture}", style = MaterialTheme.typography.bodySmall, color = Slate600)
                                                        Text("Monthly fare from ETB ${chosenRoute.basePriceEtb.toInt()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TransportGreenDark)
                                                    } else {
                                                        Text("Fare and schedule confirmed when your route is reviewed", style = MaterialTheme.typography.bodySmall, color = Slate600)
                                                    }
                                                }
                                                TextButton(onClick = { showRoutePicker = true }) { Text("Change route") }
                                            }
                                        }
                                        Text("No payment is due until an eligible vehicle is assigned and you accept the offer.", style = MaterialTheme.typography.bodySmall, color = Slate600)

                                    } else {
                                    Text(
                                        text = "Select the daily commuter shuttle route you wish to subscribe to:",
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
                                                        showRoutePicker = false
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
                                                            color = Color(0xFF1769C2),
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
                                .then(if (!isRegisterMode) Modifier.height(54.dp) else Modifier)
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
                                .then(if (!isRegisterMode) Modifier.height(54.dp) else Modifier)
                                .testTag("auth_password_input")
                        )

                        if (!isRegisterMode) {
                            Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(
                                    modifier = Modifier.defaultMinSize(minHeight = 28.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    onClick = { showPasswordHelp = true }) { Text("Forgot password?", color = if (portal == AppRole.DRIVER) Color(0xFF1769C2) else TransportGreenPrimary) }
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

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isRegisterMode) "Already a member? " else "New to PASSO? ", color = Slate600, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                viewModel.clearRegistrationMessage()
                                isRegisterMode = !isRegisterMode
                            }) { Text(if (isRegisterMode) "Sign in" else "Sign up", color = if (portal == AppRole.DRIVER) Color(0xFF1769C2) else TransportGreenPrimary, fontWeight = FontWeight.Bold) }
                        }

                        Button(
                            onClick = {
                                if (isRegisterMode) {
                                    val validationMessage = when {
                                        portal == AppRole.PASSENGER && isCustomCommuterRequest && requestPickup.isBlank() -> "Enter your pickup location."
                                         portal == AppRole.PASSENGER && isCustomCommuterRequest && requestDestination.isBlank() -> "Enter your destination."
                                         portal == AppRole.DRIVER && isRegisterMode && driverServiceMode == "Daily Commuter" && driverRouteMode == "custom" && driverPickup.isBlank() -> "Enter the proposed route pickup location."
                                         portal == AppRole.DRIVER && isRegisterMode && driverServiceMode == "Daily Commuter" && driverRouteMode == "custom" && driverDestination.isBlank() -> "Enter the proposed route destination."
                                         portal == AppRole.DRIVER && isRegisterMode && driverServiceMode == "Daily Commuter" && driverRouteMode == "custom" && driverDepartureTime.isBlank() -> "Enter a preferred departure time."
                                         portal == AppRole.DRIVER && isRegisterMode && driverServiceMode == "Intercity" && driverTransportCompany.isBlank() -> "Select or enter your transport company."
                                         password.length < 10 -> "Use a password with at least 10 characters."
                                        password != confirmPassword -> "The two passwords do not match. Please re-enter them."
                                        else -> null
                                    }
                                    if (validationMessage != null) {
                                        registrationValidationMessage = validationMessage
                                    } else {
                                        registrationValidationMessage = null
                                        viewModel.register(
                                            fullName = fullName,
                                            phone = phone,
                                            email = "",
                                            password = password,
                                            role = portal,
                                            licenseNumber = licenseNumber,
                                            companyName = if (portal == AppRole.DRIVER && driverServiceMode == "Intercity") driverTransportCompany.trim() else companyName,
                                            // City Travel drivers use company-owned vehicles; never submit stale personal vehicle fields.
                                            assignedVehiclePlate = if (portal == AppRole.DRIVER && driverServiceMode == "Intercity") "" else vehiclePlate,
                                            vehicleModel = if (portal == AppRole.DRIVER && driverServiceMode == "Intercity") "" else vehicleModel,
                                            vehicleType = vehicleType,
                                            appliedRouteId = when { portal == AppRole.DRIVER && (driverServiceMode == "Intercity" || driverRouteMode == "custom") -> ""; isCustomCommuterRequest -> ""; else -> selectedRouteId },
                                            appliedRouteName = when {
                                                portal == AppRole.DRIVER && isRegisterMode && driverServiceMode == "Intercity" -> "Driver service preference: City Travel | Transport company: " + driverTransportCompany.trim() + " | Tier: " + driverServiceTier
                                                portal == AppRole.DRIVER && isRegisterMode && driverRouteMode == "custom" -> "Driver service preference: Daily Commuter | Proposed route | Pickup: $driverPickup | Destination: $driverDestination | Departure: $driverDepartureTime | Journey: $driverJourneyType | Tier: $driverServiceTier"
                                                portal == AppRole.DRIVER && isRegisterMode -> "Driver service preference: Daily Commuter | Route: $selectedRouteName | Tier: $driverServiceTier"
                                                isCustomCommuterRequest -> "Requested commuter route | Pickup: $requestPickup | Destination: $requestDestination | Journey: $commuteDirection | Tier: $selectedServiceTier"
                                                else -> selectedRouteName
                                            },
                                            adminSecret = adminSecret
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
                                    AppRole.DRIVER -> Color(0xFF1769C2)
                                    AppRole.ADMIN -> TelebirrBlue
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (!isRegisterMode) 44.dp else 50.dp)
                                .testTag("auth_submit_button")
                        ) {
                            Text(
                                text = if (isRegisterMode && portal == AppRole.DRIVER) "SUBMIT DRIVER REGISTRATION" else if (isRegisterMode) "COMPLETE REGISTRATION & ENTER" else "SIGN IN TO PORTAL",
                                fontWeight = FontWeight.Bold,
                                fontSize = if (!isRegisterMode) 13.sp else 14.sp
                            )
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
