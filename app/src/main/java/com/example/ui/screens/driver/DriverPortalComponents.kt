package com.example.ui.screens.driver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.localization.AppLanguage
import com.example.data.api.CompletionProfile
import com.example.data.api.DriverDepartureValidation
import com.example.data.api.DriverGpsFix
import com.example.data.entity.RouteStopEntity
import com.example.ui.screens.passenger.CommuteCream
import com.example.ui.screens.passenger.CommuteInk
import com.example.ui.screens.passenger.CommuteMint
import com.example.ui.screens.passenger.CommuteTeal
import com.example.ui.screens.profile.AuthenticatedProfilePhoto
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun driverText(language: AppLanguage, english: String, amharic: String, oromo: String): String = when (language) {
    AppLanguage.ENGLISH -> english
    AppLanguage.AMHARIC -> amharic
    AppLanguage.OROMO -> oromo
}

internal fun driverHasPreciseFix(fix: DriverGpsFix?, now: Long): Boolean = fix != null &&
    fix.latitude.isFinite() && fix.longitude.isFinite() && fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0 &&
    fix.accuracyMeters.isFinite() && fix.accuracyMeters >= 0 && fix.accuracyMeters <= 100 && now - fix.timestamp in 0L..120_000L

internal data class DriverWindowInfo(val timeLabel: String, val message: String)

/** Advisory display only. The server remains authoritative for the departure gate. */
internal fun driverWindowInfo(schedule: String, now: Long): DriverWindowInfo {
    val zone = TimeZone.getTimeZone("Africa/Addis_Ababa")
    val target = runCatching {
        if (Regex("^\\d{1,2}:\\d{2}$").matches(schedule.trim())) {
            val parts = schedule.trim().split(':').map { it.toInt() }
            require(parts[0] in 0..23 && parts[1] in 0..59)
            Calendar.getInstance(zone).apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, parts[0]); set(Calendar.MINUTE, parts[1])
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                // A recurring departure close to midnight belongs to the nearest day.
                if (timeInMillis - now > 43_200_000L) add(Calendar.DAY_OF_MONTH, -1)
                else if (now - timeInMillis > 43_200_000L) add(Calendar.DAY_OF_MONTH, 1)
            }.time
        } else {
            SimpleDateFormat(if (schedule.contains('.')) "yyyy-MM-dd'T'HH:mm:ss.SSSX" else "yyyy-MM-dd'T'HH:mm:ssX", Locale.US).apply { isLenient = false }.parse(schedule)
        }
    }.getOrNull() ?: return DriverWindowInfo(schedule.ifBlank { "Time not configured" }, "Check the departure schedule with your operator.")
    val delta = target.time - now
    val text = when {
        delta > 1_800_000 -> "Boarding window opens in ${(delta - 1_800_000 + 59_999) / 60_000} min."
        delta >= -1_800_000 -> "Departure window open. Your GPS area still needs confirmation."
        else -> "Departure window passed. Contact your operator."
    }
    return DriverWindowInfo(SimpleDateFormat("h:mm a", Locale.ENGLISH).apply { timeZone = zone }.format(target), text)
}

@Composable
fun DriverBottomNavigation(selectedSection: String, documentsOpen: Boolean, language: AppLanguage,
                           onSection: (String) -> Unit, onDocuments: () -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier = modifier.clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
        containerColor = CommuteInk, contentColor = Color.White, tonalElevation = 8.dp) {
        listOf("Home" to Icons.Default.Home, "Passengers" to Icons.Default.People,
            "Documents" to Icons.Default.Description, "More" to Icons.Default.MoreHoriz).forEach { (key, icon) ->
            val label = when (key) {
                "Home" -> driverText(language, "Today", "ዛሬ", "Har'a")
                "Passengers" -> driverText(language, "Passengers", "ተሳፋሪዎች", "Imaltoota")
                "Documents" -> driverText(language, "Documents", "ሰነዶች", "Sanadoota")
                else -> driverText(language, "More", "ተጨማሪ", "Dabalata")
            }
            val selected = if (key == "Documents") documentsOpen
                else !documentsOpen && (selectedSection == key || key == "Home" && selectedSection == "My Trips")
            NavigationBarItem(selected = selected, onClick = { if (key == "Documents") onDocuments() else onSection(key) },
                icon = { Icon(icon, contentDescription = null) }, label = { Text(label) },
                modifier = Modifier.testTag("driver_nav_${key.lowercase()}"),
                colors = NavigationBarItemDefaults.colors(selectedIconColor = Color(0xFF38D9B0), selectedTextColor = Color(0xFF38D9B0),
                    unselectedIconColor = Color.White.copy(alpha = .82f), unselectedTextColor = Color.White.copy(alpha = .82f),
                    indicatorColor = Color.White.copy(alpha = .12f)))
        }
    }
}

@Composable
fun DriverGreeting(userId: String, fullName: String, language: AppLanguage, now: Long, onProfile: () -> Unit,
                   portrait: @Composable (String) -> Unit = { AuthenticatedProfilePhoto(it, Modifier.size(62.dp).clip(CircleShape)) }) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(62.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onProfile).testTag("driver_profile_photo")) { portrait(userId) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(driverText(language, "Ready for today,", "ለዛሬ ዝግጁ፣", "Har'aaf qophii,"), color = CommuteInk, fontSize = 13.sp)
            Text(fullName.trim().substringBefore(' ').ifBlank { driverText(language, "Driver", "ሹፌር", "Konkolaachisaa") } + "!", fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.ExtraBold, color = CommuteInk)
            Text(driverText(language, "Safe trips. Brighter tomorrows.", "ደህንነቱ የተጠበቀ ጉዞ።", "Imala nagaa, boru ifaa."), fontSize = 11.sp, lineHeight = 15.sp, color = Color(0xFF526575))
        }
        Column(horizontalAlignment = Alignment.End) {
            val zone = TimeZone.getTimeZone("Africa/Addis_Ababa")
            Text(SimpleDateFormat("EEE, d MMM", Locale.ENGLISH).apply { timeZone = zone }.format(Date(now)), fontSize = 9.sp, color = CommuteInk)
            Text(SimpleDateFormat("h:mm a", Locale.ENGLISH).apply { timeZone = zone }.format(Date(now)), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
        }
    }
}

@Composable
fun DriverBoardingBanner(boarding: Boolean, completed: Boolean, preciseGps: Boolean, busy: Boolean,
                         validation: DriverDepartureValidation?, schedule: String, now: Long, language: AppLanguage,
                         onConfirm: () -> Unit, onScan: () -> Unit) {
    val enabled = !busy && !completed && (boarding || preciseGps)
    val title = driverText(language, when { completed -> "Route completed"; boarding -> "Scan passengers"; else -> "Start boarding" },
        when { completed -> "ጉዞው ተጠናቋል"; boarding -> "ተሳፋሪ ስካን ያድርጉ"; else -> "ማሳፈር ይጀምሩ" },
        when { completed -> "Imalli xumurame"; boarding -> "Imaltoota yaabbachiisi"; else -> "Yaabbachuu jalqabi" })
    val subtitle = when {
        completed -> driverText(language, "Boarding is closed", "ማሳፈር ተዘግቷል", "Yaabbachuun cufame")
        boarding -> driverText(language, "Boarding open · Scan a QR pass", "ማሳፈር ተከፍቷል · QR ስካን ያድርጉ", "Yaabbachuun banaa · QR dubbisi")
        busy -> driverText(language, "Checking your request…", "ጥያቄዎ እየተፈተሸ ነው…", "Gaaffii kee sakatta'aa jira…")
        !preciseGps -> driverText(language, "Waiting for a fresh, precise GPS fix", "ትክክለኛ የGPS ቦታ ይጠበቃል", "Bakka GPS sirrii eeggachaa")
        validation?.allowed == true -> driverText(language, "Departure verified · Confirm to open", "መነሻ ተረጋግጧል · ለመክፈት ያረጋግጡ", "Ka'umsi mirkanaa'e · Mirkaneessi")
        validation != null -> validation.message
        else -> driverWindowInfo(schedule, now).message
    }
    Surface(color = CommuteTeal, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()
        .clickable(enabled = enabled, role = Role.Button, onClick = if (boarding) onScan else onConfirm).testTag("driver_boarding_banner")) {
        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (boarding) Icons.Default.QrCodeScanner else Icons.Default.DirectionsBus, null, Modifier.size(38.dp), tint = Color.White)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(subtitle, fontSize = 11.sp, lineHeight = 15.sp, color = Color(0xFFD4F2E7))
            }
            Surface(color = if (enabled) Color(0xFFE0FBF2) else Color.White.copy(alpha = 0.15f), shape = CircleShape) {
                Icon(if (completed) Icons.Default.Check else Icons.Default.ArrowForward, null, Modifier.padding(10.dp).size(23.dp), tint = if (enabled) CommuteTeal else Color.White)
            }
        }
    }
}

@Composable
fun DriverRouteOverview(routeName: String, vehiclePlate: String, vehicleType: String, schedule: String,
                        direction: String, canChangeDirection: Boolean, stops: List<RouteStopEntity>,
                        origin: String?, destination: String?, fix: DriverGpsFix?, gpsStatus: String,
                        validation: DriverDepartureValidation?, now: Long, language: AppLanguage,
                        onDirection: (String) -> Unit, onReadiness: (() -> Unit)?, boarding: Boolean,
                        onComplete: () -> Unit, busy: Boolean) {
    val ordered = if (direction == "INBOUND") stops.sortedByDescending { it.stopOrder } else stops.sortedBy { it.stopOrder }
    Surface(shape = RoundedCornerShape(24.dp), color = Color.White, modifier = Modifier.fillMaxWidth().testTag("driver_route_overview")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CommuteMint, shape = CircleShape) { Text(driverText(language, if (origin != null) "Intercity" else "Daily commute", if (origin != null) "የከተማ መካከል" else "የዕለት ጉዞ", if (origin != null) "Magaalaa gidduu" else "Imala guyyaa"), Modifier.padding(horizontal = 11.dp, vertical = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CommuteTeal) }
                Spacer(Modifier.weight(1f))
                Text(vehiclePlate, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
            }
            Text(routeName.ifBlank { driverText(language, "Assigned journey", "የተመደበ ጉዞ", "Imala ramadame") }, fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
            if (vehicleType.isNotBlank()) Text(vehicleType.replace('_', ' '), color = Color(0xFF526575), fontSize = 11.sp)
            if (canChangeDirection) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(direction == "OUTBOUND", { onDirection("OUTBOUND") }, label = { Text(driverText(language, "Outbound", "መሄጃ", "Ka'umsa")) })
                FilterChip(direction == "INBOUND", { onDirection("INBOUND") }, label = { Text(driverText(language, "Return", "መመለሻ", "Deebii")) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DriverRouteStop(driverText(language, "DEPARTURE", "መነሻ", "KA'UMSA"), ordered.firstOrNull()?.stopName ?: origin ?: driverText(language, "Assigned pickup", "የተመደበ መሳፈሪያ", "Ka'umsa ramadame"), driverWindowInfo(schedule, now).timeLabel)
                    DriverRouteStop(driverText(language, "DESTINATION", "መድረሻ", "GA'UMSA"), ordered.lastOrNull()?.stopName ?: destination ?: driverText(language, "See assigned route", "የተመደበ መስመር ይመልከቱ", "Karaa ramadame ilaali"), null)
                }
                DriverRouteMap(ordered, fix, Modifier.weight(1f).height(155.dp), language)
            }
            Text(gpsStatus, fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFF526575))
            validation?.let { Text(it.message, color = if (it.allowed) CommuteTeal else Color(0xFF9A302F), fontSize = 12.sp) }
            if (onReadiness != null && !boarding) OutlinedButton(onClick = onReadiness, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("driver_readiness_check"), shape = CircleShape) {
                Icon(Icons.Default.MyLocation, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp))
                Text(driverText(language, "Check departure area", "የመነሻ ቦታ ፈትሽ", "Bakka ka'umsaa sakatta'i"), fontSize = 12.sp)
            }
            if (boarding) OutlinedButton(onClick = onComplete, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("driver_complete_route"), shape = CircleShape) {
                Icon(Icons.Default.Flag, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp))
                Text(driverText(language, "Complete route", "ጉዞ አጠናቅቅ", "Imala xumuri"), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DriverRouteStop(label: String, name: String, time: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.RadioButtonChecked, null, Modifier.size(14.dp), tint = CommuteTeal)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF647989))
            Text(name, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
            time?.let { Text(it, fontSize = 12.sp, color = CommuteInk) }
        }
    }
}

@Composable
fun DriverDocumentsOverview(profile: CompletionProfile?, loading: Boolean, loadError: Boolean, language: AppLanguage, onOpen: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).testTag("driver_documents_overview")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Description, null, tint = CommuteTeal)
                Text(driverText(language, "Documents", "ሰነዶች", "Sanadoota"), Modifier.weight(1f), fontWeight = FontWeight.Bold, color = CommuteInk)
                Icon(Icons.Default.ChevronRight, null, tint = CommuteTeal)
            }
            listOf("LICENSE" to driverText(language, "Driving licence", "የመንጃ ፈቃድ", "Hayyama konkolaachisummaa"),
                "VEHICLE_REGISTRATION" to driverText(language, "Vehicle registration", "የተሽከርካሪ ምዝገባ", "Galmee konkolaataa"),
                "INSURANCE" to driverText(language, "Insurance", "ኢንሹራንስ", "Inshuraansii")).forEach { (kind, label) ->
                val uploaded = profile?.documents?.any { it.kind == kind } == true
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (uploaded) Icons.Default.CheckCircle else Icons.Default.Description, null, Modifier.size(18.dp), tint = if (uploaded) CommuteTeal else Color(0xFF8A9B92))
                    Text(label, Modifier.weight(1f), color = CommuteInk, fontSize = 12.sp)
                    Text(driverText(language, when { loading -> "Loading"; profile == null -> "Review"; uploaded -> "Uploaded"; else -> "Upload" }, when { uploaded -> "ተጭኗል"; else -> "ጫን" }, when { uploaded -> "Ol fe'ame"; else -> "Ol fe'i" }), fontSize = 10.sp, color = CommuteTeal)
                }
            }
            if (loadError) Text(driverText(language, "Open documents to refresh your upload status.", "የሰነድ መረጃ ለማደስ ክፈት።", "Odeeffannoo haaromsuuf bani."), fontSize = 11.sp, color = Color(0xFF647989))
            Text(driverText(language, "Photo and emergency contact →", "ፎቶ እና የአደጋ ጊዜ ተጠሪ →", "Suuraa fi nama balaa yeroo →"), fontSize = 11.sp, color = CommuteTeal)
        }
    }
}
