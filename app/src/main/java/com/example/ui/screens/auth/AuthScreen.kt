package com.example.ui.screens.auth

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    var selectedRole by remember { mutableStateOf(AppRole.PASSENGER) }
    var isRegisterMode by remember { mutableStateOf(false) }

    // Form fields
    var fullName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("123456") }
    var licenseNumber by remember { mutableStateOf("") }
    var companyName by remember { mutableStateOf("") }
    var vehiclePlate by remember { mutableStateOf("") }

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

    // Pre-fill default phone when switching role for convenience
    LaunchedEffect(selectedRole, isRegisterMode) {
        if (!isRegisterMode) {
            when (selectedRole) {
                AppRole.PASSENGER -> phone = "+251911223344"
                AppRole.DRIVER -> phone = "+251911998877"
                AppRole.ADMIN -> phone = "+251910001122"
            }
        } else {
            phone = ""
            fullName = ""
        }
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
                        "የተቀናጀ የኢትዮጵያ የትራንስፖርት መግቢያ እና ምዝገባ"
                    else
                        "Ethiopian Scheduled Transit • Independent Access Portal",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Role Selector Pills
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (lang == AppLanguage.AMHARIC) "የመግቢያ ሚና ይምረጡ:" else "Select Login Role:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Slate700
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RoleSelectionButton(
                        title = if (lang == AppLanguage.AMHARIC) "ተሳፋሪ" else "Passenger",
                        isSelected = selectedRole == AppRole.PASSENGER,
                        icon = Icons.Default.Person,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedRole = AppRole.PASSENGER }
                    )
                    RoleSelectionButton(
                        title = if (lang == AppLanguage.AMHARIC) "ሹፌር / አጓጓዥ" else "Transporter",
                        isSelected = selectedRole == AppRole.DRIVER,
                        icon = Icons.Default.DirectionsBus,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedRole = AppRole.DRIVER }
                    )
                    RoleSelectionButton(
                        title = if (lang == AppLanguage.AMHARIC) "ኦፕሬተር / አስተዳዳሪ" else "Operator",
                        isSelected = selectedRole == AppRole.ADMIN,
                        icon = Icons.Default.AdminPanelSettings,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedRole = AppRole.ADMIN }
                    )
                }
            }
        }

        // Mode Switcher: Sign In vs Register
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

        // Auth Form Card
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
                            when (selectedRole) {
                                AppRole.PASSENGER -> "Register New Passenger Account"
                                AppRole.DRIVER -> "Register New Transporter / Driver Profile"
                                AppRole.ADMIN -> "Register Transport Operator Organization"
                            }
                        } else {
                            when (selectedRole) {
                                AppRole.PASSENGER -> "Passenger Portal Login"
                                AppRole.DRIVER -> "Transporter / Driver Console Login"
                                AppRole.ADMIN -> "Transport Operator & Admin Login"
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

                        if (selectedRole == AppRole.ADMIN) {
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
                        }

                        if (selectedRole == AppRole.DRIVER) {
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
                        if (selectedRole == AppRole.PASSENGER || selectedRole == AppRole.DRIVER) {
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
                                    text = if (selectedRole == AppRole.PASSENGER)
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
                                    role = selectedRole,
                                    licenseNumber = licenseNumber,
                                    companyName = companyName,
                                    assignedVehiclePlate = vehiclePlate,
                                    appliedRouteId = selectedRouteId,
                                    appliedRouteName = selectedRouteName
                                )
                            } else {
                                viewModel.login(phone, password, selectedRole)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
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

        // Quick 1-Tap Demo Logins
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
                        text = "⚡ Quick Demo Access (1-Tap Independent Logins):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Slate700
                    )

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

@Composable
fun RoleSelectionButton(
    title: String,
    isSelected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = if (isSelected) TransportGreenPrimary else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) TransportGreenPrimary else Slate400
        ),
        modifier = modifier
            .height(58.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) Color.White else Slate700,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) Color.White else Slate700,
                maxLines = 1,
                fontSize = 11.sp
            )
        }
    }
}
