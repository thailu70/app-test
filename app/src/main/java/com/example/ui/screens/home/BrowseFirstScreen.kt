package com.example.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.MainViewModel
import com.example.data.entity.RouteEntity
import com.example.ui.theme.TransportGreenPrimary
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate900

private val HomeGreen = Color(0xFF00A982)
private val HomeMint = Color(0xFFE8F6EE)
private val HomeBlue = Color(0xFF0757B8)
private val HomeInk = Color(0xFF17212B)

@Composable
fun BrowseFirstScreen(
    viewModel: MainViewModel,
    onPassengerContinue: (registerFirst: Boolean, routeId: String?, routeName: String?, serviceTier: String?) -> Unit,
    onDriverAccess: () -> Unit,
    onCompanyAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val routes by viewModel.activeRoutes.collectAsState()
    var selectedSection by remember { mutableStateOf(0) }
    var selectedService by remember { mutableStateOf(0) }
    var origin by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var selectedRoute by remember { mutableStateOf<RouteEntity?>(null) }
    var selectedTier by remember { mutableStateOf("Economy") }
    var routeFilter by remember { mutableStateOf(0) }

    selectedRoute?.let { route ->
        Dialog(onDismissRequest = { selectedRoute = null }) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = HomeMint, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(46.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = HomeGreen, modifier = Modifier.size(27.dp))
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Route details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = HomeInk)
                            Text("Browse first • Sign in only to continue", style = MaterialTheme.typography.bodySmall, color = Slate600)
                        }
                        TextButton(onClick = { selectedRoute = null }) { Text("Close", color = HomeGreen) }
                    }
                    HorizontalDivider(color = Color(0xFFE1E8E3))
                    Text(route.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = HomeInk)
                    if (route.nameAm.isNotBlank()) Text(route.nameAm, color = Slate600)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RouteInfoTile("Morning pickup", route.morningDeparture, Modifier.weight(1f))
                        RouteInfoTile("Evening return", route.eveningDeparture, Modifier.weight(1f))
                    }
                    val selectedFare = if (selectedTier == "Economy") "ETB " + String.format(java.util.Locale.US, "%,.0f", route.basePriceEtb) else "Fare not configured"
                    RouteInfoTile("Monthly subscription • $selectedTier", selectedFare, Modifier.fillMaxWidth())
                    Text("Choose your service tier", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = HomeInk)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                        TierChoice("VIP", "4 seats", selectedTier == "VIP", Modifier.weight(1f)) { selectedTier = "VIP" }
                        TierChoice("Deluxe", "7 seats", selectedTier == "Deluxe", Modifier.weight(1f)) { selectedTier = "Deluxe" }
                        TierChoice("Economy", "13 seats", selectedTier == "Economy", Modifier.weight(1f)) { selectedTier = "Economy" }
                    }
                    Surface(color = if (selectedTier == "Economy") HomeMint else Color(0xFFFFF3D6), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (selectedTier == "Economy") "Economy fare configured" else "$selectedTier fare not configured", color = if (selectedTier == "Economy") HomeGreen else Color(0xFF9A6700), fontWeight = FontWeight.Bold)
                            Text(if (selectedTier == "Economy") "The route monthly fare is $selectedFare. Live seat availability still requires confirmation." else "No $selectedTier fare or live seat count is stored for this route yet. The operator must configure it before booking or payment.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                        }
                    }
                    Button(
                        onClick = { selectedRoute = null; onPassengerContinue(true, route.id, route.name, selectedTier) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = HomeGreen)
                    ) {
                        Text("Request / Continue")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null)
                    }
                    OutlinedButton(onClick = { selectedRoute = null }, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to routes")
                    }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFFF6F8F7))) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (selectedSection == 0) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = HomeGreen, modifier = Modifier.size(54.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text("PASSO", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = HomeInk)
                    Text("Tap. Move. Clear.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                }
                TextButton(onClick = { onPassengerContinue(false, null, null, null) }) { Text("Sign in", color = HomeGreen, fontWeight = FontWeight.Bold) }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 26.dp, bottomEnd = 26.dp), color = HomeBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxWidth().height(252.dp)) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF0757B8), Color(0xFF06356D), Color(0xFF00A982)))))
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x22000000), Color(0x33000000), Color(0x55000000)))))
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                            Text("PASSO", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text("Tap. Move. Clear.", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        }
                        Text("Travel across your city and Ethiopia", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ServiceHomeCard("Daily Commuter", "Monthly or daily", "Work • School • Office", Icons.Default.DirectionsBus, HomeGreen, false) { selectedSection = 1; selectedService = 0 }
                ServiceHomeCard("Intercity Travel", "One-way or return tickets", "Across Ethiopia", Icons.Default.Map, HomeBlue, false) { selectedSection = 2; selectedService = 1 }
            }
        }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { selectedSection = 0 }) { Text("← Home", color = HomeGreen, fontWeight = FontWeight.Bold) }
                    Text(if (selectedSection == 1) "Daily Commuter" else "Intercity Travel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = HomeInk)
                }
            }
        }
        if (selectedSection == 1) {
            item {
                Surface(color = HomeBlue, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 0.dp, bottomEnd = 0.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Daily Commuter", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text("Available services in Addis Ababa", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("All Routes", "Near Me", "Popular").forEachIndexed { index, label ->
                        Column(Modifier.weight(1f).clickable { routeFilter = index }.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, color = if (routeFilter == index) HomeGreen else Slate600, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth().height(if (routeFilter == index) 3.dp else 1.dp).background(if (routeFilter == index) HomeGreen else Color(0xFFE5E9E7)))
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Find your daily route", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = HomeInk)
                    Text("Compare routes, departure times and configured monthly fares. Sign in only when you are ready to request a service.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                }
            }
            if (routes.filter { it.active }.isEmpty()) {
                item {
                    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Loading available routes", fontWeight = FontWeight.Bold, color = HomeInk)
                            Text("PASSO is checking routes configured by the transport operator. Please try again shortly.", color = Slate600, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                items(routes.filter { it.active }.size) { index ->
                    val route = routes.filter { it.active }[index]
                    CommuterRouteCard(route = route, selectedTier = selectedTier, onTierSelected = { tier -> selectedTier = tier; selectedRoute = route }, onClick = { selectedRoute = route })
                }
            }

            item {
                Surface(color = Color.White, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No vehicle available yet?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = HomeInk)
                        Text("You can submit a request and join the waitlist. No subscription payment is due until an eligible vehicle is assigned and you accept the offer.", style = MaterialTheme.typography.bodyMedium, color = Slate600)
                        Button(onClick = { onPassengerContinue(true, null, "Requested commuter route", selectedTier) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = HomeGreen)) {
                            Text("Sign in / Sign up to request")
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ArrowForward, contentDescription = null)
                        }
                    }
                }
            }
        } else if (selectedSection == 2) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Find an intercity journey", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = HomeInk)
                    Text("Search without signing in. Seat availability, fares and departure times will be confirmed during booking.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                }
            }
            item {
                Surface(color = Color.White, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(value = origin, onValueChange = { origin = it }, label = { Text("From city or terminal") }, leadingIcon = { Icon(Icons.Default.Map, contentDescription = null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = destination, onValueChange = { destination = it }, label = { Text("To city or terminal") }, leadingIcon = { Icon(Icons.Default.Map, contentDescription = null) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = HomeGreen)
                            Spacer(Modifier.width(8.dp))
                            Text("Travel date and seat selection", color = Slate600, style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = { onPassengerContinue(true, null, "Intercity: $origin → $destination", "Intercity") }, modifier = Modifier.fillMaxWidth(), enabled = origin.isNotBlank() && destination.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = HomeBlue)) {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Continue to search")
                        }
                    }
                }
            }
            item {
                Surface(color = Color(0xFFEAF0FC), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = HomeBlue)
                        Column {
                            Text("One-way and return tickets", fontWeight = FontWeight.Bold, color = HomeInk)
                            Text("Compare participating operators, choose a seat and receive a QR ticket after payment is verified.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                        }
                    }
                }
            }
        }
        item {
            HorizontalDivider()
            Text("Are you a transport provider?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = HomeInk)
            Text("Drivers, transport companies and associations use separate, permission-controlled portals.", style = MaterialTheme.typography.bodySmall, color = Slate600)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDriverAccess, modifier = Modifier.weight(1f)) { Text("Driver access") }
                OutlinedButton(onClick = onCompanyAccess, modifier = Modifier.weight(1f)) { Text("Company portal") }
            }
        }
        item {
            Text("PASSO • Ethiopia", style = MaterialTheme.typography.labelSmall, color = Slate600, modifier = Modifier.fillMaxWidth())
        }
    }
    NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
        NavigationBarItem(selected = selectedSection == 0, onClick = { selectedSection = 0 }, icon = { Icon(Icons.Default.Work, contentDescription = "Home") }, label = { Text("Home") })
        NavigationBarItem(selected = false, onClick = { selectedSection = 1 }, icon = { Icon(Icons.Default.Search, contentDescription = "Search") }, label = { Text("Search") })
        NavigationBarItem(selected = false, onClick = { onPassengerContinue(false, null, null, null) }, icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "Tickets") }, label = { Text("Tickets") })
        NavigationBarItem(selected = false, onClick = { onPassengerContinue(false, null, null, null) }, icon = { Icon(Icons.Default.Luggage, contentDescription = "More") }, label = { Text("More") })
    }
    }
}

@Composable
private fun ServiceHomeCard(title: String, subtitle: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp, border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) tint.copy(alpha = 0.35f) else Color(0xFFE5E9E7)), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = tint, shape = RoundedCornerShape(16.dp), modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tint)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = HomeInk)
                Text(description, style = MaterialTheme.typography.bodySmall, color = Slate600)
            }
            Icon(Icons.Default.ArrowForward, contentDescription = "Open $title", tint = tint)
        }
    }
}

@Composable
private fun ServiceSelector(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) HomeMint else Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) HomeGreen else Color(0xFFE0E6E2))
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 13.dp), color = if (selected) HomeGreen else Slate600, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TierCard(name: String, capacity: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = HomeMint, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = HomeGreen, modifier = Modifier.size(26.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = HomeInk)
                Text(capacity, style = MaterialTheme.typography.labelMedium, color = HomeGreen, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = Slate600)
            }
            Icon(Icons.Default.ArrowForward, contentDescription = "Explore $name", tint = HomeGreen)
        }
    }
}

@Composable
private fun ExistingRouteCard(route: RouteEntity, onClick: () -> Unit, compact: Boolean = false) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E8E3)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        if (compact) {
            Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = HomeMint, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(54.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.DirectionsBus, contentDescription = "Commuter bus", tint = HomeGreen, modifier = Modifier.size(30.dp)) } }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(route.name, fontWeight = FontWeight.Bold, color = HomeInk, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("ETB " + String.format(java.util.Locale.US, "%,.0f", route.basePriceEtb), color = HomeGreen, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text(route.morningDeparture + " • " + route.eveningDeparture, color = Slate600, style = MaterialTheme.typography.labelSmall)
                }
                Icon(Icons.Default.ArrowForward, contentDescription = "View route", tint = HomeBlue)
            }
        } else {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = HomeMint, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(44.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = HomeGreen, modifier = Modifier.size(25.dp)) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(route.name, fontWeight = FontWeight.Bold, color = HomeInk, style = MaterialTheme.typography.titleMedium)
                        if (route.nameAm.isNotBlank()) Text(route.nameAm, color = Slate600, style = MaterialTheme.typography.bodySmall)
                    }
                    Surface(color = HomeMint, shape = RoundedCornerShape(50.dp)) {
                        Text("Active", color = HomeGreen, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = HomeGreen, modifier = Modifier.size(18.dp))
                    Text("Morning " + route.morningDeparture + " • Evening " + route.eveningDeparture, color = Slate600, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Monthly from", color = Slate600, style = MaterialTheme.typography.labelSmall)
                        Text("ETB " + String.format(java.util.Locale.US, "%,.0f", route.basePriceEtb), color = HomeGreen, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                    }
                    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = HomeGreen)) { Text("View route"); Spacer(Modifier.width(5.dp)); Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TierChip(title: String, seats: String, modifier: Modifier = Modifier) {
    Surface(color = Color.White, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E6E2)), modifier = modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = HomeBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(seats, color = Slate600, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RouteInfoTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Color(0xFFF3F7F4), shape = RoundedCornerShape(14.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = Slate600, style = MaterialTheme.typography.labelSmall)
            Text(value, color = HomeGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun CommuterRouteCard(route: RouteEntity, selectedTier: String, onTierSelected: (String) -> Unit, onClick: () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), tonalElevation = 1.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(route.name, color = HomeInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                    Text("Mon – Fri • Two way", color = Slate600, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = HomeBlue, modifier = Modifier.size(17.dp))
                        Text(route.morningDeparture + " & " + route.eveningDeparture, color = Slate600, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Surface(color = HomeMint, shape = RoundedCornerShape(8.dp)) {
                    Text("Route listed", color = HomeGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                }
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "View route", tint = HomeInk, modifier = Modifier.size(25.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                TierPriceCard("VIP", "Premium", "Fare not set", selectedTier == "VIP", Color(0xFFFFF3D6), Color(0xFFB87500), Modifier.weight(1f)) { onTierSelected("VIP") }
                TierPriceCard("Deluxe", "Comfort", "Fare not set", selectedTier == "Deluxe", Color(0xFFEAF1FF), HomeBlue, Modifier.weight(1f)) { onTierSelected("Deluxe") }
                TierPriceCard("Economy", "Standard", "ETB " + String.format(java.util.Locale.US, "%,.0f", route.basePriceEtb), selectedTier == "Economy", HomeMint, HomeGreen, Modifier.weight(1f)) { onTierSelected("Economy") }
            }
        }
    }
}

@Composable
private fun TierPriceCard(name: String, seats: String, fare: String, selected: Boolean, bg: Color, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(color = if (selected) bg else bg.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) tint else tint.copy(alpha = 0.25f)), modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = tint, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge)
            Text(seats, color = Slate600, style = MaterialTheme.typography.labelSmall)
            Text(fare, color = HomeGreen, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}


@Composable
private fun TierChoice(name: String, seats: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(color = if (selected) HomeMint else Color.White, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) HomeGreen else Color(0xFFE0E6E2)), modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(name, color = if (selected) HomeGreen else HomeBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(seats, color = Slate600, style = MaterialTheme.typography.labelSmall)
            if (selected) Text("Selected", color = HomeGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}
