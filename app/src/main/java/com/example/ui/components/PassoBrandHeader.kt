package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val PassoNavy = Color(0xFF142D55)
private val PassoTeal = Color(0xFF078875)

@Composable
fun PassoBrandHeader(
    portalLabel: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = PassoNavy
    ) {
        Row(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(PassoNavy, Color(0xFF075A78), PassoTeal)))
                .padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).background(Color.White.copy(alpha = .16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = Color.White, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("PASSO", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text("Go Further, Together.", color = Color.White.copy(alpha = .95f), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.labelSmall)
            }
            Surface(color = Color.White.copy(alpha = .94f), shape = RoundedCornerShape(50.dp)) {
                Text(portalLabel, color = Color(0xFF145A48), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            action?.let { Spacer(Modifier.width(4.dp)); it() }
        }
    }
}

