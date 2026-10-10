package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val RouteNavy = Color(0xFF071A2D)
private val RouteBlue = Color(0xFF123B5D)
private val RouteMint = Color(0xFF62E6C5)
private val RouteCyan = Color(0xFF6ED9FF)

@Composable
fun RoutePassHeroHeader(
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Default.DirectionsBus,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "routepassHero")
    val glowAlpha by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "routepassGlow"
    )
    val drift by transition.animateFloat(
        initialValue = 0.84f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "routepassDrift"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = RouteNavy,
        shadowElevation = 10.dp
    ) {
        Box(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(RouteNavy, RouteBlue, Color(0xFF145B70))))
                .padding(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp, top = 2.dp)
                    .size((112 * drift).dp)
                    .alpha(glowAlpha)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(RouteMint, RouteCyan.copy(alpha = 0.1f))))
            )
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(14.dp)) {
                        Icon(imageVector = icon, contentDescription = null, tint = RouteMint, modifier = Modifier.padding(11.dp).size(27.dp))
                    }
                    Column {
                        Text("ROUTEPASS", color = RouteMint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
                        Text("MOVE SMARTER", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { index ->
                        Box(
                            Modifier.size(if (index == 0) 18.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (index == 0) RouteMint.copy(alpha = glowAlpha) else Color.White.copy(alpha = 0.5f))
                        )
                    }
                    Text("CONNECTED JOURNEYS", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
