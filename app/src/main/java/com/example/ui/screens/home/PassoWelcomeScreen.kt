package com.example.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.localization.AppLanguage

enum class WelcomeService { PASSENGER, DRIVER, INTERCITY }

private val WelcomeInk = Color(0xFF06233D)
private val WelcomeTeal = Color(0xFF007C78)
@OptIn(ExperimentalTextApi::class)
private val WelcomeFont = FontFamily(
    Font(R.font.outfit, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.outfit, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.outfit, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.outfit, weight = FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900)))
)
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
    ProvideTextStyle(LocalTextStyle.current.copy(fontFamily = WelcomeFont)) {
    Box(modifier.fillMaxSize().background(WelcomeCream).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        WelcomeViewport {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val sceneWidth = maxWidth
                // The hero contains only brand artwork; every control is rendered below.
                Box(Modifier.fillMaxWidth().height(sceneWidth * 0.98f).clip(RoundedCornerShape(0.dp))) {
                    ReferenceArtwork(0, 0, 853, 850, Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.TopCenter).offset(y = sceneWidth * 0.211f)
                        .width(sceneWidth * 0.55f).height(sceneWidth * 0.06f).background(WelcomeCream), contentAlignment = Alignment.Center) {
                        Text("Go Further, Together.", color = WelcomeInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(copy("A clearer way", "የተሻለ መንገድ", "Karaa caalu"), fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black, color = WelcomeInk, textAlign = TextAlign.Center)
                Text(copy("to move together.", "አብረን ለመጓዝ።", "waliin imaluuf."), fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black, color = WelcomeTeal, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(copy("Reliable rides, smarter connections.\nGo Further, Together.", "አስተማማኝ ጉዞ፣ የተሻለ ግንኙነት።\nአብረን ወደ ፊት።", "Imala amanamaa, walqunnamtii caalu.\nWaliin fagootti haa imallu."), color = Color(0xFF53606C), fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
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
                    WelcomeServiceCard(copy("Passenger", "ተሳፋሪ", "Imaltuu"), copy("Find and ride with ease.", "በቀላሉ ይጓዙ።", "Salphaatti imalaa."), 32, Icons.Default.DirectionsBus, Color(0xFFE4F4E9), selectedService == WelcomeService.PASSENGER, Modifier.weight(1f).testTag("welcome_service_PASSENGER")) { selectedService = WelcomeService.PASSENGER }
                    WelcomeServiceCard(copy("Driver", "ሹፌር", "Konkolaachisaa"), copy("Earn, drive and grow.", "ይንዱ፣ ያግኙ፣ ያድጉ።", "Oofaa, hojjedhaa, guddadhaa."), 306, Icons.Default.DirectionsCar, Color(0xFFE4F2FA), selectedService == WelcomeService.DRIVER, Modifier.weight(1f).testTag("welcome_service_DRIVER")) { selectedService = WelcomeService.DRIVER }
                    WelcomeServiceCard(copy("Intercity Travel", "የከተማ መካከል ጉዞ", "Imala magaalotaa"), copy("Explore more places.", "አዳዲስ ቦታዎችን ያስሱ።", "Bakkeewwan haaraa daawwadhaa."), 566, Icons.Default.AddRoad, Color(0xFFFFF1DB), selectedService == WelcomeService.INTERCITY, Modifier.weight(1f).testTag("welcome_service_INTERCITY")) { selectedService = WelcomeService.INTERCITY }
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = { onContinue(selectedService) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("welcome_continue"), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = WelcomeTeal)) {
                    Text(copy("Continue", "ቀጥል", "Itti fufi"), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Default.ArrowForward, null)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        }
    }
}

}

/** Fit the unchanged native welcome composition inside the safe screen area. */
@Composable
private fun WelcomeViewport(content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.widthIn(max = 560.dp).fillMaxSize()) { measurables, constraints ->
        val page = measurables.single().measure(Constraints(
            minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth, maxHeight = Constraints.Infinity
        ))
        val scale = minOf(1f, constraints.maxHeight.toFloat() / page.height.coerceAtLeast(1))
        layout(constraints.maxWidth, constraints.maxHeight) {
            page.placeWithLayer(((constraints.maxWidth - page.width * scale) / 2).toInt(), 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

@Composable
private fun WelcomeServiceCard(title: String, subtitle: String, artworkX: Int, icon: ImageVector, color: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier, color = color, shape = RoundedCornerShape(20.dp), border = BorderStroke(2.dp, if (selected) WelcomeTeal else color)) {
        Column(Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)) {
            Box {
                // Only the decorative photograph and badge are sampled from the reference.
                // The card label, description, selection and action remain native UI.
                ReferenceArtwork(artworkX, 1240, if (artworkX == 306) 242 else 256, 276,
                    Modifier.fillMaxWidth().aspectRatio(0.93f).clip(RoundedCornerShape(18.dp)))
                if (selected) Icon(Icons.Default.CheckCircle, "Selected", Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp), tint = WelcomeTeal)
            }
            Column(Modifier.padding(9.dp).heightIn(min = 47.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, color = WelcomeInk)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(subtitle, modifier = Modifier.weight(1f), fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFF47545D))
                    Surface(color = Color.White.copy(alpha = 0.5f), shape = CircleShape) {
                        Icon(Icons.Default.ChevronRight, null, Modifier.padding(3.dp).size(16.dp), tint = WelcomeInk)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceArtwork(x: Int, y: Int, width: Int, height: Int, modifier: Modifier) {
    val bitmap = ImageBitmap.imageResource(R.drawable.passo_welcome)
    val painter = remember(bitmap, x, y, width, height) {
                val scaleX = bitmap.width / 853f
        val scaleY = bitmap.height / 1844f
        BitmapPainter(bitmap,
            srcOffset = IntOffset((x * scaleX).toInt(), (y * scaleY).toInt()),
            srcSize = IntSize((width * scaleX).toInt(), (height * scaleY).toInt()))
    }
    Image(painter = painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}
