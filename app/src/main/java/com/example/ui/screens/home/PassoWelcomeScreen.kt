package com.example.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.TransportGreenPrimary

@Composable
fun PassoWelcomeScreen(
    onContinue: () -> Unit,
    onLanguage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cream = Color(0xFFFFFBF4)
    val navy = Color(0xFF142D55)
    Column(
        modifier = modifier.fillMaxSize().background(cream).padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("PASSO", color = navy, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text("Go Further, Together.", color = TransportGreenPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Surface(color = Color.White, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().height(230.dp)) {
            Box {
                Image(
                    painter = painterResource(com.example.R.drawable.passo_hero_background),
                    contentDescription = "PASSO people and vehicle",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(color = navy.copy(alpha = .82f), shape = RoundedCornerShape(18.dp), modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("A clearer way to move.", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Go Further, Together.", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Choose your service", color = navy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WelcomeChoice("Passenger", Icons.Default.DirectionsBus, Modifier.weight(1f))
            WelcomeChoice("Driver", Icons.Default.DirectionsCar, Modifier.weight(1f))
            WelcomeChoice("Intercity", Icons.Default.Map, Modifier.weight(1f))
        }
        Text("App language", color = navy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguagePill("English", true, Modifier.weight(1f))
            LanguagePill("አማርኛ", false, Modifier.weight(1f))
            LanguagePill("Afaan Oromoo", false, Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = TransportGreenPrimary)) {
            Text("Continue", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        TextButton(onClick = onLanguage, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Change language", color = navy) }
    }
}

@Composable
private fun WelcomeChoice(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 2.dp, modifier = modifier.height(108.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = TransportGreenPrimary, modifier = Modifier.size(30.dp))
            Text(label, color = Color(0xFF142D55), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LanguagePill(label: String, selected: Boolean, modifier: Modifier) {
    Surface(color = if (selected) TransportGreenPrimary else Color.White, contentColor = if (selected) Color.White else Color(0xFF142D55), shape = RoundedCornerShape(50.dp), modifier = modifier.height(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
    }
}




