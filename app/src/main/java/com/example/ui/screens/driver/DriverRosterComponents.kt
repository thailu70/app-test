package com.example.ui.screens.driver

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.localization.AppLanguage
import com.example.data.api.DriverRosterPassenger
import com.example.ui.screens.passenger.CommuteInk
import com.example.ui.screens.passenger.CommuteTeal
import com.example.ui.screens.profile.AuthenticatedProfilePhoto
import java.util.Locale

/** Renders server roster counts and emits the unchanged API status key on selection. */
@Composable
fun DriverRosterSummary(
    roster: List<DriverRosterPassenger>,
    onFilter: (String) -> Unit,
    filter: String,
    modifier: Modifier = Modifier,
    language: AppLanguage = AppLanguage.ENGLISH
) {
    val statuses = listOf("ALL", "BOARDED", "ASSIGNED", "PENDING", "INACTIVE") +
        if (roster.any { it.status.equals("BOARDING", ignoreCase = true) }) listOf("BOARDING") else emptyList()
    Column(modifier.fillMaxWidth().testTag("driver_roster_summary"), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.People, contentDescription = null, tint = CommuteInk, modifier = Modifier.size(24.dp))
            Text(rosterText(language, "Passengers", "መንገደኞች", "Imaltoota"), color = CommuteInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Surface(color = Color(0xFFE8F3ED), shape = CircleShape) {
                Text(roster.size.toString(), Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = CommuteTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            statuses.forEach { status ->
                val appearance = rosterStatus(status, language)
                val count = if (status == "ALL") roster.size else roster.count { it.status.equals(status, ignoreCase = true) }
                FilterChip(
                    selected = filter.equals(status, ignoreCase = true),
                    onClick = { onFilter(status) },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(count.toString(), fontWeight = FontWeight.Bold)
                            Text(appearance.label, fontWeight = FontWeight.Medium)
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = appearance.background,
                        labelColor = appearance.foreground,
                        selectedContainerColor = CommuteTeal,
                        selectedLabelColor = Color.White
                    ),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("driver_roster_filter_${status.lowercase(Locale.ROOT)}")
                )
            }
        }
    }
}

/** Uses only roster data; private profile photos are loaded through the authenticated photo API. */
@Composable
fun DriverRosterRow(
    passenger: DriverRosterPassenger,
    modifier: Modifier = Modifier,
    language: AppLanguage = AppLanguage.ENGLISH,
    photoContent: @Composable (String) -> Unit = {
        AuthenticatedProfilePhoto(it, Modifier.size(44.dp).clip(CircleShape))
    }
) {
    val appearance = rosterStatus(passenger.status, language)
    val details = listOfNotNull(
        passenger.seatNumber?.let { rosterText(language, "Seat $it", "መቀመጫ $it", "Teessoo $it") },
        passenger.pickupStop.trim().takeIf { it.isNotBlank() }
    ).joinToString(" · ")
    Surface(
        modifier = modifier.fillMaxWidth().testTag("driver_roster_row_${passenger.id}"),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE7EAE5))
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 11.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val personId = passenger.passengerId?.takeIf { it.isNotBlank() }
            if (personId != null) {
                Box(Modifier.size(44.dp).clip(CircleShape), contentAlignment = Alignment.Center) { photoContent(personId) }
            } else {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFE8EFEB)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF71827C), modifier = Modifier.size(25.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    passenger.passengerName.takeIf { it.isNotBlank() } ?: rosterText(language, "Passenger", "መንገደኛ", "Imaltuu"),
                    color = CommuteInk, fontSize = 14.sp, lineHeight = 17.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (passenger.passengerPhone.isNotBlank()) {
                    Text(passenger.passengerPhone, color = Color(0xFF536979), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (details.isNotBlank()) {
                    Text(details, color = Color(0xFF536979), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Surface(
                color = appearance.background,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.widthIn(max = 104.dp).testTag("driver_roster_status_${passenger.id}")
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(appearance.icon, contentDescription = null, tint = appearance.foreground, modifier = Modifier.size(14.dp))
                    Text(appearance.label, color = appearance.foreground, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private data class RosterStatusAppearance(val label: String, val foreground: Color, val background: Color, val icon: ImageVector)

private fun rosterStatus(status: String, language: AppLanguage): RosterStatusAppearance = when (status.uppercase(Locale.ROOT)) {
    "ALL" -> RosterStatusAppearance(rosterText(language, "All", "ሁሉም", "Hunda"), CommuteInk, Color(0xFFF1F3F2), Icons.Default.People)
    "BOARDED" -> RosterStatusAppearance(rosterText(language, "Boarded", "ተሳፍሯል", "Yaabbate"), Color(0xFF08793F), Color(0xFFDCF9E4), Icons.Default.CheckCircle)
    "ASSIGNED" -> RosterStatusAppearance(rosterText(language, "Assigned", "ተመድቧል", "Ramadame"), Color(0xFF08793F), Color(0xFFE7FAEC), Icons.Default.CheckCircle)
    "BOARDING" -> RosterStatusAppearance(rosterText(language, "Boarding", "እየተሳፈረ", "Yaabbachaa"), Color(0xFF0869AF), Color(0xFFE8F4FF), Icons.Default.DirectionsBus)
    "PENDING" -> RosterStatusAppearance(rosterText(language, "Pending", "በመጠባበቅ", "Eeggachaa"), Color(0xFF445D6F), Color(0xFFEDF1F3), Icons.Default.Schedule)
    "INACTIVE" -> RosterStatusAppearance(rosterText(language, "Inactive", "ንቁ ያልሆነ", "Hojii irra hin jiru"), Color(0xFFB82D43), Color(0xFFFFE3E7), Icons.Default.Close)
    else -> RosterStatusAppearance(status.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }, Color(0xFF445D6F), Color(0xFFEDF1F3), Icons.Default.Person)
}

private fun rosterText(language: AppLanguage, english: String, amharic: String, oromo: String): String = when (language) {
    AppLanguage.ENGLISH -> english
    AppLanguage.AMHARIC -> amharic
    AppLanguage.OROMO -> oromo
}
