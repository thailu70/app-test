package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.api.RosterPersonDto

@Composable
fun AssignedRosterCard(
    title: String,
    people: List<RosterPersonDto>,
    message: String,
    showVehicle: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (people.isEmpty()) {
                Text(message.ifBlank { "No assigned people to show yet." }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                people.forEachIndexed { index, person ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        ProfilePhoto(dataUrl = person.profilePhotoDataUrl, name = person.fullName, size = 64.dp)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(person.fullName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(if (person.role == "DRIVER") "Assigned driver" else "Monthly passenger", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            if (person.vehiclePlate.orEmpty().isNotBlank()) {
                                Text("Vehicle: ${person.vehiclePlate}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                if (!person.vehicleModel.isNullOrBlank()) Text(person.vehicleModel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val schedule = listOfNotNull(
                                person.morningSchedule?.takeIf { it.isNotBlank() }?.let { "Morning $it" },
                                person.eveningSchedule?.takeIf { it.isNotBlank() }?.let { "Evening $it" }
                            ).joinToString(" · ")
                            if (schedule.isNotBlank()) Text(schedule, style = MaterialTheme.typography.bodySmall)
                            if (!person.pickupStopName.isNullOrBlank()) Text("Pickup: ${person.pickupStopName}", style = MaterialTheme.typography.bodySmall)
                            if (!person.destinationStopName.isNullOrBlank()) Text("Drop-off: ${person.destinationStopName}", style = MaterialTheme.typography.bodySmall)
                            if (!person.subscriptionEnd.isNullOrBlank()) Text("Subscription until: ${person.subscriptionEnd}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (showVehicle && !person.vehiclePhotoDataUrl.isNullOrBlank()) {
                        Text("Assigned vehicle photo", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        ProfilePhoto(
                            dataUrl = person.vehiclePhotoDataUrl,
                            name = person.vehiclePlate ?: "Assigned vehicle",
                            size = 160.dp,
                            circular = false
                        )
                    }
                }
                Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
