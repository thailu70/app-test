package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.entity.NotificationEntity
import com.example.ui.theme.*
import com.example.ui.viewmodel.AppRole

@Composable
fun NotificationTrayDialog(
    role: AppRole,
    notifications: List<NotificationEntity>,
    onDismiss: () -> Unit,
    onSendBroadcast: (title: String, message: String, audience: String) -> Unit
) {
    var isComposing by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var newMessage by remember { mutableStateOf("") }
    var targetAudience by remember { mutableStateOf("PASSENGERS") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(8.dp)
                .testTag("notification_tray_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = TransportGreenPrimary.copy(alpha = 0.15f),
                            shape = CircleShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Notifications,
                                    contentDescription = null,
                                    tint = TransportGreenPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Notifications & Alerts",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Target: ${role.name.lowercase().replaceFirstChar { it.uppercase() }} feed",
                                style = MaterialTheme.typography.labelSmall,
                                color = Slate600
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Operator Action: Compose Broadcast Notification
                if (role == AppRole.ADMIN) {
                    if (!isComposing) {
                        Button(
                            onClick = { isComposing = true },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("compose_broadcast_button")
                        ) {
                            Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("DISPATCH BROADCAST NOTIFICATION", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    } else {
                        // Compose Card
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate50),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(text = "Send Broadcast Alert", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("PASSENGERS", "TRANSPORTERS", "ALL").forEach { aud ->
                                        FilterChip(
                                            selected = targetAudience == aud,
                                            onClick = { targetAudience = aud },
                                            label = { Text(aud.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 11.sp) }
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = newTitle,
                                    onValueChange = { newTitle = it },
                                    label = { Text("Title") },
                                    placeholder = { Text("E.g. Route Delay / Trip Alert") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = newMessage,
                                    onValueChange = { newMessage = it },
                                    label = { Text("Message Body") },
                                    placeholder = { Text("E.g. Expect 10 min delay near Mexico due to rain...") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { isComposing = false }) {
                                        Text("Cancel")
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            if (newTitle.isNotBlank() && newMessage.isNotBlank()) {
                                                onSendBroadcast(newTitle, newMessage, targetAudience)
                                                newTitle = ""
                                                newMessage = ""
                                                isComposing = false
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("SEND NOW")
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = Slate200)

                // Notification Feed List
                if (notifications.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.NotificationsNone, contentDescription = null, tint = Slate400, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No notifications yet.", style = MaterialTheme.typography.bodyMedium, color = Slate600)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(notifications) { item ->
                            NotificationItemCard(item = item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationItemCard(item: NotificationEntity) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Slate50),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (item.type) {
                                    "REMINDER" -> TransportGold
                                    "ALERT" -> StatusExpiredRed
                                    "SCHEDULE" -> StatusInfoBlue
                                    else -> StatusActiveGreen
                                }
                            )
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                }
                Text(
                    text = item.timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate400
                )
            }

            Text(
                text = item.message,
                style = MaterialTheme.typography.bodySmall,
                color = Slate700
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "From: ${item.senderName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Slate400,
                    fontSize = 10.sp
                )
                Surface(
                    color = Slate200,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = item.targetAudience,
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate700,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
