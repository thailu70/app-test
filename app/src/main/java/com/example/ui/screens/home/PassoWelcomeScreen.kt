package com.example.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.localization.AppLanguage

enum class WelcomeService { PASSENGER, DRIVER, INTERCITY }

private val WelcomeInk = Color(0xFF06233D)
private val WelcomeTeal = Color(0xFF007C78)
private val WelcomeCream = Color(0xFFFFFBF4)

/** Artwork is decorative; all labels, selections and navigation are native controls. */
@Composable
fun PassoWelcomeScreen(
    onContinue: (WelcomeService) -> Unit,
    language: AppLanguage,
    onLanguage: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedService by rememberSaveable { mutableStateOf(WelcomeService.PASSENGER) }
    fun copy(en: String, am: String, om: String) = when (language) {
        AppLanguage.ENGLISH -> en
        AppLanguage.AMHARIC -> am
        AppLanguage.OROMO -> om
    }
    Box(modifier.fillMaxSize().background(WelcomeCream).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // The source artwork has blank space below its scene. Clip to the scene,
                // keeping its original aspect ratio rather than stretching the photograph.
                Box(Modifier.fillMaxWidth().height(maxWidth * 1.05f).clip(RoundedCornerShape(0.dp))) {
                    Image(painterResource(R.drawable.passo_hero_background), null,
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).requiredHeight(maxWidth * 2.162f),
                        contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter)
                    Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PASSO", color = WelcomeInk, fontSize = 52.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp)
                        Text("Go Further, Together.", color = WelcomeInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(copy("A clearer way", "የተሻለ መንገድ", "Karaa caalu"), fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black, color = WelcomeInk, textAlign = TextAlign.Center)
                Text(copy("to move together.", "አብረን ለመጓዝ።", "waliin imaluuf."), fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black, color = WelcomeTeal, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(copy("Reliable rides, smarter connections.\nGo Further, Together.", "አስተማማኝ ጉዞ፣ የተሻለ ግንኙነት።\nአብረን ወደ ፊት።", "Imala amanamaa, walqunnamtii caalu.\nWaliin fagootti haa imallu."), color = Color(0xFF53606C), fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppLanguage.entries.forEach { item ->
                        Surface(color = if (language == item) WelcomeTeal else WelcomeCream, shape = CircleShape,
                            border = BorderStroke(1.dp, if (language == item) WelcomeTeal else Color(0xFFE1DED7)), modifier = Modifier.weight(1f)) {
                            Row(Modifier.testTag("welcome_language_${item.code}").selectable(language == item, role = Role.RadioButton, onClick = { onLanguage(item) }).heightIn(min = 48.dp).padding(horizontal = 5.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, null, Modifier.size(18.dp), tint = if (language == item) Color.White else WelcomeInk)
                                Spacer(Modifier.width(4.dp))
                                Text(item.displayName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (language == item) Color.White else WelcomeInk)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WelcomeServiceCard(copy("Passenger", "ተሳፋሪ", "Imaltuu"), copy("Find and ride with ease.", "በቀላሉ ይጓዙ።", "Salphaatti imalaa."), R.drawable.passo_passenger_card, Icons.Default.DirectionsBus, Color(0xFFE4F4E9), selectedService == WelcomeService.PASSENGER, Modifier.weight(1f).testTag("welcome_service_PASSENGER")) { selectedService = WelcomeService.PASSENGER }
                    WelcomeServiceCard(copy("Driver", "ሹፌር", "Konkolaachisaa"), copy("Earn, drive and grow.", "ይንዱ፣ ያግኙ፣ ያድጉ።", "Oofaa, hojjedhaa, guddadhaa."), R.drawable.passo_driver_card, Icons.Default.DirectionsCar, Color(0xFFE4F2FA), selectedService == WelcomeService.DRIVER, Modifier.weight(1f).testTag("welcome_service_DRIVER")) { selectedService = WelcomeService.DRIVER }
                    WelcomeServiceCard(copy("Intercity Travel", "የከተማ መካከል ጉዞ", "Imala magaalotaa"), copy("Explore more places.", "አዳዲስ ቦታዎችን ያስሱ።", "Bakkeewwan haaraa daawwadhaa."), R.drawable.passo_intercity_card, Icons.Default.AddRoad, Color(0xFFFFF1DB), selectedService == WelcomeService.INTERCITY, Modifier.weight(1f).testTag("welcome_service_INTERCITY")) { selectedService = WelcomeService.INTERCITY }
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = { onContinue(selectedService) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("welcome_continue"), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = WelcomeTeal)) {
                    Text(copy("Continue", "ቀጥል", "Itti fufi"), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Default.ArrowForward, null)
                }
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

@Composable
private fun WelcomeServiceCard(title: String, subtitle: String, image: Int, icon: ImageVector, color: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier, color = color, shape = RoundedCornerShape(20.dp), border = BorderStroke(2.dp, if (selected) WelcomeTeal else color)) {
        Column(Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)) {
            Box {
                Image(painterResource(image), null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
                Surface(Modifier.padding(8.dp).size(30.dp), shape = CircleShape, color = color, border = BorderStroke(2.dp, Color.White)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(19.dp), tint = WelcomeInk) }
                }
                if (selected) Icon(Icons.Default.CheckCircle, "Selected", Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp), tint = WelcomeTeal)
            }
            Column(Modifier.padding(9.dp).heightIn(min = 85.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, color = WelcomeInk)
                Text(subtitle, fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFF47545D))
                Icon(Icons.Default.ArrowForward, null, Modifier.size(18.dp).align(Alignment.End), tint = WelcomeInk)
            }
        }
    }
}
