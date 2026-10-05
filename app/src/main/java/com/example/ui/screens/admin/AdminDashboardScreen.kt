package com.example.ui.screens.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.core.localization.AppLanguage
import com.example.core.localization.AppStrings
import com.example.data.entity.RouteEntity
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel

@Composable
fun AdminDashboardScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val lang by viewModel.currentLanguage.collectAsState()
    val routes by viewModel.allRoutes.collectAsState()
    val vehicles by viewModel.allVehicles.collectAsState()
    val payments by viewModel.allPayments.collectAsState()
    val checkIns by viewModel.allCheckIns.collectAsState()
    val complaints by viewModel.allComplaints.collectAsState()
    val auditLogs by viewModel.recentAuditLogs.collectAsState()
    val subscriptions by viewModel.allSubscriptions.collectAsState()
    val isCreateRouteOpen by viewModel.isCreateRouteDialogOpen.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showExportDialog by remember { mutableStateOf(false) }

    val tabs = listOf(
        "Overview",
        "Manage Routes",
        "Fleet & Vehicles",
        "Telebirr Reconcile",
        "Attendance Logs",
        "Complaints",
        "Audit Trail"
    )

    fun t(key: String) = AppStrings.get(key, lang)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp)
    ) {
        // Header & Quick Actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Transport Operations Central",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = currentUser?.companyName?.ifBlank { "Addis Commuter Transit Co." } ?: "Addis Commuter Transit Co.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TransportGreenPrimary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { viewModel.openNotificationTray() },
                        modifier = Modifier.testTag("admin_notif_button")
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = "Notifications", tint = TransportGreenPrimary)
                    }

                    Button(
                        onClick = { showExportDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("admin_export_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(text = "Export CSV", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // Horizontal Category Tab Pills
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(tabs.size) { idx ->
                    FilterChip(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        label = { Text(tabs[idx], fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal) },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("admin_tab_$idx")
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> {
                // Tab 0: KPI Overview & Charts
                item {
                    AdminKpiCardsGrid(
                        activeSubsCount = subscriptions.size.coerceAtLeast(128),
                        routesCount = routes.size,
                        fleetCount = vehicles.size,
                        lang = lang
                    )
                }

                item {
                    RevenueAnalyticsChartCard(lang = lang)
                }

                item {
                    RouteDemandCard(lang = lang)
                }
            }
            1 -> {
                // Tab 1: Manage Routes & Stops
                item {
                    AdminRoutesManagementCard(
                        routes = routes,
                        onAddNewRoute = { viewModel.openCreateRouteDialog() },
                        onDeleteRoute = { routeId -> viewModel.deleteRoute(routeId) }
                    )
                }
            }
            2 -> {
                // Tab 2: Fleet Management
                item {
                    AdminFleetCard(vehicles = vehicles)
                }
            }
            3 -> {
                // Tab 3: Telebirr Payments Reconciliation
                item {
                    AdminTelebirrPaymentsCard(payments = payments)
                }
            }
            4 -> {
                // Tab 4: Attendance & Check-in Scans
                item {
                    AdminAttendanceLogsCard(checkIns = checkIns)
                }
            }
            5 -> {
                // Tab 5: Complaints
                item {
                    AdminComplaintsCard(complaints = complaints)
                }
            }
            6 -> {
                // Tab 6: Audit Trail
                item {
                    AdminAuditLogsCard(logs = auditLogs)
                }
            }
        }
    }

    if (isCreateRouteOpen) {
        CreateRouteDialog(
            onDismiss = { viewModel.closeCreateRouteDialog() },
            onCreate = { name, nameAm, desc, morning, evening, dist, price, stops ->
                viewModel.createRoute(name, nameAm, desc, morning, evening, dist, price, stops)
            }
        )
    }

    if (showExportDialog) {
        AdminExportDialog(onDismiss = { showExportDialog = false })
    }
}

@Composable
fun AdminKpiCardsGrid(
    activeSubsCount: Int,
    routesCount: Int,
    fleetCount: Int,
    lang: AppLanguage
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KpiMetricBox(
                title = "ACTIVE SUBSCRIPTIONS",
                value = "$activeSubsCount",
                subtitle = "94% Renewal Rate",
                color = StatusActiveGreen,
                modifier = Modifier.weight(1f)
            )
            KpiMetricBox(
                title = "MONTHLY REVENUE",
                value = "ETB 320,000",
                subtitle = "+18% vs last month",
                color = TelebirrBlue,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KpiMetricBox(
                title = "ACTIVE FLEET",
                value = "$fleetCount Coasters",
                subtitle = "100% Inspected",
                color = TransportGold,
                modifier = Modifier.weight(1f)
            )
            KpiMetricBox(
                title = "ACTIVE ROUTES",
                value = "$routesCount Routes",
                subtitle = "Morning & Evening",
                color = TransportGreenPrimary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun KpiMetricBox(
    title: String,
    value: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = Slate600)
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = color)
            Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = Slate400)
        }
    }
}

@Composable
fun RevenueAnalyticsChartCard(lang: AppLanguage) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("revenue_chart_card"),
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
                    text = "Telebirr Revenue by Month (ETB)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "2026 Fiscal Year",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate600
                )
            }

            val months = listOf("Jun" to 180, "Jul" to 220, "Aug" to 260, "Sep" to 295, "Oct" to 320)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Slate50)
                    .padding(12.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val maxVal = 350f
                    val barWidth = size.width / (months.size * 2)

                    months.forEachIndexed { i, pair ->
                        val barHeight = (pair.second / maxVal) * (size.height - 25.dp.toPx())
                        val x = i * (barWidth * 2) + (barWidth / 2)
                        val y = size.height - barHeight - 20.dp.toPx()

                        drawRoundRect(
                            color = if (i == months.size - 1) Color(0xFF0D683E) else Color(0xFF007AE6),
                            topLeft = Offset(x, y),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    months.forEach {
                        Text(text = "${it.first} (${it.second}k)", fontSize = 10.sp, color = Slate700, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun RouteDemandCard(lang: AppLanguage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                text = "Route Utilization & Attendance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            RouteProgressItem(name = "Bole → Merkato", utilization = 0.95f, passengers = "24 / 25 seats")
            RouteProgressItem(name = "CMC → Bole", utilization = 0.88f, passengers = "22 / 25 seats")
            RouteProgressItem(name = "Saris → Kazanchis", utilization = 0.80f, passengers = "20 / 25 seats")
        }
    }
}

@Composable
private fun RouteProgressItem(name: String, utilization: Float, passengers: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(text = "$passengers (${(utilization * 100).toInt()}%)", style = MaterialTheme.typography.labelSmall, color = TransportGreenPrimary, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { utilization },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = TransportGreenPrimary,
            trackColor = Slate200,
        )
    }
}

@Composable
fun AdminRoutesManagementCard(
    routes: List<RouteEntity>,
    onAddNewRoute: () -> Unit,
    onDeleteRoute: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Scheduled Transit Routes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = onAddNewRoute,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("admin_add_route_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(text = "Add New Route", style = MaterialTheme.typography.labelMedium)
                }
            }

            routes.forEach { r ->
                Surface(
                    color = Slate50,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = r.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                Text(text = r.nameAm, style = MaterialTheme.typography.bodySmall, color = Slate600)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = "ETB ${r.basePriceEtb.toInt()}/mo", fontWeight = FontWeight.Bold, color = TransportGreenPrimary)
                                IconButton(
                                    onClick = { onDeleteRoute(r.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Route", tint = StatusExpiredRed, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        Text(text = "${r.description} • ${r.distanceKm} km", style = MaterialTheme.typography.bodySmall, color = Slate700)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Morning: ${r.morningDeparture}", style = MaterialTheme.typography.labelSmall, color = Slate600)
                            Text(text = "Evening: ${r.eveningDeparture}", style = MaterialTheme.typography.labelSmall, color = Slate600)
                        }
                    }
                }
            }
        }
    }
}

// Dialog for Admin to create and register a new route
@Composable
fun CreateRouteDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, nameAm: String, desc: String, morning: String, evening: String, dist: Double, price: Double, stops: List<Pair<String, String>>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var nameAm by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var morning by remember { mutableStateOf("06:30") }
    var evening by remember { mutableStateOf("17:30") }
    var distance by remember { mutableStateOf("15.0") }
    var price by remember { mutableStateOf("2600") }
    var stopsInput by remember { mutableStateOf("Bole, Mexico, Merkato") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .testTag("create_route_dialog")
        ) {
            LazyColumn(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Create Scheduled Route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Route Name (e.g. Kazanchis → Bole)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = nameAm,
                        onValueChange = { nameAm = it },
                        label = { Text("Amharic Name (e.g. ካዛንቺስ → ቦሌ)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Route Description & Key Landmarks") },
                        placeholder = { Text("Via Meskel Square, Bambis, Edna Mall") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = morning,
                            onValueChange = { morning = it },
                            label = { Text("Morning (AM)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = evening,
                            onValueChange = { evening = it },
                            label = { Text("Evening (PM)") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = distance,
                            onValueChange = { distance = it },
                            label = { Text("Distance (km)") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = price,
                            onValueChange = { price = it },
                            label = { Text("Price (ETB)") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = stopsInput,
                        onValueChange = { stopsInput = it },
                        label = { Text("Stops (comma separated)") },
                        placeholder = { Text("Bole Atlas, Mexico, Piazza") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Button(
                        onClick = {
                            if (name.isNotBlank()) {
                                val stopsList = stopsInput.split(",").map { it.trim() }.filter { it.isNotBlank() }.map { Pair(it, it) }
                                onCreate(
                                    name,
                                    nameAm.ifBlank { name },
                                    desc.ifBlank { "Scheduled transit" },
                                    morning,
                                    evening,
                                    distance.toDoubleOrNull() ?: 12.0,
                                    price.toDoubleOrNull() ?: 2500.0,
                                    stopsList
                                )
                            }
                        },
                        enabled = name.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("SAVE & ACTIVATE ROUTE", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun AdminFleetCard(vehicles: List<com.example.data.entity.VehicleEntity>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Fleet & Vehicle Inspections",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            vehicles.forEach { v ->
                Surface(
                    color = Slate50,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "${v.plateNumber} • ${v.model}", fontWeight = FontWeight.Bold)
                            Text(text = "Capacity: ${v.capacity} Passengers • ${v.type}", style = MaterialTheme.typography.bodySmall, color = Slate600)
                            Text(text = "Insurance valid until: ${v.insuranceExpiry}", style = MaterialTheme.typography.labelSmall, color = Slate400)
                        }
                        Surface(
                            color = StatusActiveGreen.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = v.status,
                                color = StatusActiveGreen,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminTelebirrPaymentsCard(payments: List<com.example.data.entity.PaymentTransactionEntity>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Telebirr Payment Reconciliations",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            payments.forEach { p ->
                Surface(
                    color = Slate50,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = p.transactionRef, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(text = "${p.timestamp} • ${p.paymentPhone}", style = MaterialTheme.typography.labelSmall, color = Slate600)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "ETB ${p.amountEtb.toInt()}", fontWeight = FontWeight.ExtraBold, color = StatusActiveGreen)
                            Text(text = p.status, style = MaterialTheme.typography.labelSmall, color = TelebirrBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminAttendanceLogsCard(checkIns: List<com.example.data.entity.CheckInRecordEntity>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Passenger Boarding & QR Scan Logs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            checkIns.forEach { c ->
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
                        Column {
                            Text(text = "${c.passengerName} @ ${c.stopName}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(text = "Token: ${c.qrToken.take(14)}... • Time: ${c.checkInTimestamp}", style = MaterialTheme.typography.labelSmall, color = Slate600)
                        }
                        Surface(
                            color = StatusActiveGreen.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(text = c.status, color = StatusActiveGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminComplaintsCard(complaints: List<com.example.data.entity.ComplaintEntity>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Passenger Support Tickets",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            if (complaints.isEmpty()) {
                Text(text = "No open customer support tickets.", style = MaterialTheme.typography.bodySmall, color = Slate600)
            } else {
                complaints.forEach { c ->
                    Surface(
                        color = Slate50,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "${c.passengerName} (${c.category})", fontWeight = FontWeight.Bold)
                                Text(text = c.status, color = TransportGold, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(text = c.text, style = MaterialTheme.typography.bodySmall, color = Slate700)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminAuditLogsCard(logs: List<com.example.data.entity.AuditLogEntity>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "System Security & Audit Trail",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            logs.forEach { log ->
                Surface(
                    color = Slate50,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = log.action, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Text(text = log.timestamp, style = MaterialTheme.typography.labelSmall, color = Slate600)
                        }
                        Text(text = log.details, style = MaterialTheme.typography.bodySmall, color = Slate700)
                    }
                }
            }
        }
    }
}

@Composable
fun AdminExportDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .testTag("admin_export_dialog")
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
                    Text(text = "Export Operational Statement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Text(
                    text = "Generated CSV format report with Telebirr transactions, passenger subscriptions, and daily attendance records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )

                Surface(
                    color = Slate900,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "date,tx_ref,passenger,route,amount_etb,status\n2026-10-01,TB-ET-20261001-9988,Abebe Kebede,Bole-Merkato,2500,SUCCESS\n2026-10-01,TB-ET-20261001-9989,Hana Tadesse,Bole-Merkato,2500,SUCCESS",
                        color = TransportGoldLight,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("DOWNLOAD / SHARE CSV")
                }
            }
        }
    }
}
