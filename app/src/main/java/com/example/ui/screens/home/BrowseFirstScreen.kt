package com.example.ui.screens.home

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.MainViewModel
import com.example.data.entity.RouteEntity
import com.example.ui.theme.TransportGreenPrimary
import com.example.ui.theme.Slate600
import com.example.ui.theme.Slate900

private val HomeGreen = Color(0xFF0E6B46)
private val HomeMint = Color(0xFFE8F6EE)
private val HomeBlue = Color(0xFF315EA8)
private val HomeInk = Color(0xFF17212B)

@Composable
fun BrowseFirstScreen(
    viewModel: MainViewModel,
    onPassengerContinue: () -> Unit,
    onDriverAccess: () -> Unit,
    onCompanyAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val routes by viewModel.activeRoutes.collectAsState()
    var selectedService by remember { mutableStateOf(0) }
    var origin by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf("") }
    var selectedRoute by remember { mutableStateOf<RouteEntity?>(null) }

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
                    RouteInfoTile("Monthly subscription", "ETB " + String.format(java.util.Locale.US, "%,.0f", route.basePriceEtb), Modifier.fillMaxWidth())
                    Text("Choose your service tier", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = HomeInk)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                        TierChip("VIP", "4 seats", Modifier.weight(1f))
                        TierChip("Deluxe", "7 seats", Modifier.weight(1f))
                        TierChip("Economy", "13 seats", Modifier.weight(1f))
                    }
                    Text("Pickup points and vehicle availability are confirmed when you continue with this route.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                    Button(
                        onClick = { selectedRoute = null; onPassengerContinue() },
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

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Color(0xFFF6F8F7)),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = HomeGreen, modifier = Modifier.size(46.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = Color.White, modifier = Modifier.size(27.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("RoutePass", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = HomeInk)
                    Text("Your journey, made simple.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                }
                TextButton(onClick = onPassengerContinue) { Text("Sign in", color = HomeGreen, fontWeight = FontWeight.Bold) }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = HomeGreen, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = Color.White.copy(alpha = 0.16f), shape = RoundedCornerShape(50.dp)) {
                        Text("YOUR JOURNEY STARTS HERE", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                    Text("Move people. Connect places.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Explore routes and services before you sign in.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { selectedService = 0 }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = HomeGreen)) {
                            Icon(Icons.Default.Work, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Daily commute")
                        }
                        OutlinedButton(onClick = { selectedService = 1 }, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.8f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                            Icon(Icons.Default.Luggage, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Intercity")
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ServiceSelector("Daily commuter", selectedService == 0, Modifier.weight(1f)) { selectedService = 0 }
                ServiceSelector("Intercity tickets", selectedService == 1, Modifier.weight(1f)) { selectedService = 1 }
            }
        }
        if (selectedService == 0) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Popular commuter routes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = HomeInk)
                    Text("See existing routes, departure times and monthly fares. Sign in only when you are ready to request a subscription.", style = MaterialTheme.typography.bodySmall, color = Slate600)
                }
            }
            if (routes.filter { it.active }.isEmpty()) {
                item {
                    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Loading available routes", fontWeight = FontWeight.Bold, color = HomeInk)
                            Text("RoutePass is checking routes configured by the transport operator. Please try again shortly.", color = Slate600, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                items(routes.filter { it.active }.size) { index ->
                    val route = routes.filter { it.active }[index]
                    ExistingRouteCard(route = route, onClick = { selectedRoute = route })
                }
            }
            item {
                Text("Choose your service tier", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = HomeInk)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TierChip("VIP", "4 seats", Modifier.weight(1f))
                    TierChip("Deluxe", "7 seats", Modifier.weight(1f))
                    TierChip("Economy", "13 seats", Modifier.weight(1f))
                }
            }
            item {
                Surface(color = Color.White, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No vehicle available yet?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = HomeInk)
                        Text("You can submit a request and join the waitlist. No subscription payment is due until an eligible vehicle is assigned and you accept the offer.", style = MaterialTheme.typography.bodyMedium, color = Slate600)
                        Button(onClick = onPassengerContinue, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = HomeGreen)) {
                            Text("Request a commuter route")
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ArrowForward, contentDescription = null)
                        }
                    }
                }
            }
        } else {
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
                        Button(onClick = onPassengerContinue, modifier = Modifier.fillMaxWidth(), enabled = origin.isNotBlank() && destination.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = HomeBlue)) {
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
            Text("RoutePass • Ethiopia", style = MaterialTheme.typography.labelSmall, color = Slate600, modifier = Modifier.fillMaxWidth())
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
private fun ExistingRouteCard(route: RouteEntity, onClick: () -> Unit) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE1E8E3)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
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
                Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = HomeGreen)) {
                    Text("View route")
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
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
