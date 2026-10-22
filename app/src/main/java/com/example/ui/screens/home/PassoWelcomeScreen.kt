package com.example.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
    modifier: Modifier = Modifier,
    onPrivacy: (() -> Unit)? = null
) {
    var selectedService by rememberSaveable { mutableStateOf(WelcomeService.PASSENGER) }
    var languagePresented by remember { mutableStateOf(false) }
    val languageOpacity = remember(language) { Animatable(if (languagePresented) 0.78f else 1f) }
    LaunchedEffect(languageOpacity) {
        languagePresented = true
        languageOpacity.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
    }
    // Animate drawing only: translated labels never animate the viewport's measurements.
    val languageTextMotion = Modifier.graphicsLayer { alpha = languageOpacity.value }
    fun copy(en: String, am: String, om: String) = when (language) {
        AppLanguage.ENGLISH -> en
        AppLanguage.AMHARIC -> am
        AppLanguage.OROMO -> om
    }
    ProvideTextStyle(LocalTextStyle.current.copy(fontFamily = WelcomeFont)) {
    Box(modifier.fillMaxSize().background(WelcomeCream).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        WelcomeViewport { portrait, availableHeight ->
        // Portrait uses the full safe width. The photo takes the height left by the
        // native controls, rather than shrinking the entire page and leaving gutters.
        val compact = portrait && availableHeight < 740.dp
        Column(if (portrait) Modifier.fillMaxSize() else Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(if (portrait) Modifier.fillMaxWidth().weight(1f).testTag("welcome_hero") else Modifier.fillMaxWidth().testTag("welcome_hero")) {
                val sceneWidth = maxWidth
                val sceneHeight = if (portrait) maxHeight else sceneWidth * 0.98f
                // The hero contains only brand artwork; every control is rendered below.
                if (sceneHeight >= sceneWidth * 0.75f) {
                    // Use one continuous photograph on regular/tall phones. Derive
                    // the motto position from the actual crop, including its scale.
                    val artworkScale = maxOf(sceneWidth.value / 853f, sceneHeight.value / 850f)
                    Box(Modifier.fillMaxWidth().height(sceneHeight)) {
                        ReferenceArtwork(0, 0, 853, 850, Modifier.fillMaxSize(), Alignment.TopCenter)
                        WelcomeMotto(artworkScale)
                        if (portrait) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, WelcomeCream))))
                    }
                } else Column(Modifier.fillMaxSize()) {
                    // Keep branding at its source aspect ratio so the responsive
                    // photo crop can never move the motto over the PASSO logo.
                    Box(Modifier.fillMaxWidth().height(sceneWidth * (230f / 853f))) {
                    ReferenceArtwork(0, 0, 853, 230, Modifier.fillMaxSize())
                    WelcomeMotto(sceneWidth.value / 853f)
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(8.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, WelcomeCream))))
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                    // A separate photo crop retains faces and the vehicle on
                    // short phones instead of showing only the sky and logo.
                    ReferenceArtwork(0, 230, 853, 620, Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(8.dp)
                        .background(Brush.verticalGradient(listOf(WelcomeCream, Color.Transparent))))
                    if (portrait) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, WelcomeCream))))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().testTag("welcome_controls").padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(copy("A clearer way", "የተሻለ መንገድ", "Karaa caalu"), modifier = languageTextMotion, fontSize = if (compact) 24.sp else 28.sp, lineHeight = if (compact) 28.sp else 32.sp, fontWeight = FontWeight.Black, color = WelcomeInk, textAlign = TextAlign.Center)
                Text(copy("to move together.", "አብረን ለመጓዝ።", "waliin imaluuf."), modifier = languageTextMotion, fontSize = if (compact) 24.sp else 28.sp, lineHeight = if (compact) 28.sp else 32.sp, fontWeight = FontWeight.Black, color = WelcomeTeal, textAlign = TextAlign.Center)
                Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
                Text(copy("Reliable rides, smarter connections.\nGo Further, Together.", "አስተማማኝ ጉዞ፣ የተሻለ ግንኙነት።\nአብረን ወደ ፊት።", "Imala amanamaa, walqunnamtii caalu.\nWaliin fagootti haa imallu."), modifier = languageTextMotion, color = Color(0xFF53606C), fontSize = if (compact) 11.sp else 13.sp, lineHeight = if (compact) 15.sp else 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppLanguage.entries.forEach { item ->
                        val selected = language == item
                        val optionColor by animateColorAsState(if (selected) WelcomeTeal else WelcomeCream, tween(200), label = "welcome-language-background-${item.code}")
                        val optionBorder by animateColorAsState(if (selected) WelcomeTeal else Color(0xFFE1DED7), tween(200), label = "welcome-language-border-${item.code}")
                        val optionInk by animateColorAsState(if (selected) Color.White else WelcomeInk, tween(200), label = "welcome-language-ink-${item.code}")
                        Surface(color = optionColor, shape = CircleShape,
                            border = BorderStroke(1.dp, optionBorder), modifier = Modifier.weight(1f)) {
                            Row(Modifier.testTag("welcome_language_${item.code}").selectable(language == item, role = Role.RadioButton, onClick = { onLanguage(item) }).heightIn(min = 48.dp).padding(horizontal = 5.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, null, Modifier.size(18.dp), tint = optionInk)
                                Spacer(Modifier.width(4.dp))
                                Text(item.displayName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = optionInk)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WelcomeServiceCard(copy("Passenger", "ተሳፋሪ", "Imaltuu"), copy("Find and ride with ease.", "በቀላሉ ይጓዙ።", "Salphaatti imalaa."), 32, Icons.Default.DirectionsBus, Color(0xFFE4F4E9), selectedService == WelcomeService.PASSENGER, Modifier.weight(1f).testTag("welcome_service_PASSENGER"), { languageOpacity.value }) { selectedService = WelcomeService.PASSENGER }
                    WelcomeServiceCard(copy("Driver", "ሹፌር", "Konkolaachisaa"), copy("Earn, drive and grow.", "ይንዱ፣ ያግኙ፣ ያድጉ።", "Oofaa, hojjedhaa, guddadhaa."), 306, Icons.Default.DirectionsCar, Color(0xFFE4F2FA), selectedService == WelcomeService.DRIVER, Modifier.weight(1f).testTag("welcome_service_DRIVER"), { languageOpacity.value }) { selectedService = WelcomeService.DRIVER }
                    WelcomeServiceCard(copy("Intercity Travel", "የከተማ መካከል ጉዞ", "Imala magaalotaa"), copy("Explore more places.", "አዳዲስ ቦታዎችን ያስሱ።", "Bakkeewwan haaraa daawwadhaa."), 566, Icons.Default.AddRoad, Color(0xFFFFF1DB), selectedService == WelcomeService.INTERCITY, Modifier.weight(1f).testTag("welcome_service_INTERCITY"), { languageOpacity.value }) { selectedService = WelcomeService.INTERCITY }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { onContinue(selectedService) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("welcome_continue"), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = WelcomeTeal)) {
                    Text(copy("Continue", "ቀጥል", "Itti fufi"), modifier = languageTextMotion, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(16.dp))
                    Icon(Icons.Default.ArrowForward, null)
                }
                if (onPrivacy != null) TextButton(onClick = onPrivacy, modifier = Modifier.heightIn(min = 40.dp).testTag("welcome_privacy"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                    Text(copy("Privacy policy", "የግላዊነት መመሪያ", "Imaammata dhuunfaa"), fontSize = 11.sp, color = WelcomeTeal)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        }
    }
}

}

@Composable
private fun BoxScope.WelcomeMotto(artworkScale: Float) {
    Box(Modifier.align(Alignment.TopCenter).offset(y = (182f * artworkScale).dp)
        .width((480f * artworkScale).dp).height((48f * artworkScale).dp).background(WelcomeCream),
        contentAlignment = Alignment.Center) {
        Text("Go Further, Together.", color = WelcomeInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Reflow portrait at screen width; retain a contained composition in landscape. */
@Composable
private fun WelcomeViewport(content: @Composable (Boolean, androidx.compose.ui.unit.Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("welcome_viewport"), contentAlignment = Alignment.TopCenter) {
    val portrait = maxHeight >= maxWidth
    if (portrait) {
        content(true, maxHeight)
    } else Layout(content = { content(false, maxHeight) }, modifier = Modifier.widthIn(max = 560.dp).fillMaxSize()) { measurables, constraints ->
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
}

@Composable
private fun WelcomeServiceCard(title: String, subtitle: String, artworkX: Int, icon: ImageVector, color: Color, selected: Boolean, modifier: Modifier, labelOpacity: () -> Float, onClick: () -> Unit) {
    val borderColor by animateColorAsState(if (selected) WelcomeTeal else color, tween(220), label = "welcome-service-border-$artworkX")
    val selectionProgress by animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "welcome-service-check-$artworkX")
    Surface(modifier = modifier.fillMaxHeight(), color = color, shape = RoundedCornerShape(20.dp), border = BorderStroke(2.dp, borderColor)) {
        Column(Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)) {
            Box {
                // Only the decorative photograph and badge are sampled from the reference.
                // The card label, description, selection and action remain native UI.
                ReferenceArtwork(artworkX, 1240, if (artworkX == 306) 242 else 256, 276,
                    Modifier.fillMaxWidth().aspectRatio(1.25f).clip(RoundedCornerShape(18.dp)), Alignment.TopCenter)
                Icon(Icons.Default.CheckCircle, if (selected) "Selected" else null, Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).graphicsLayer { alpha = selectionProgress; scaleX = 0.8f + 0.2f * selectionProgress; scaleY = scaleX }, tint = WelcomeTeal)
            }
            Column(Modifier.padding(9.dp).heightIn(min = 47.dp).graphicsLayer { alpha = labelOpacity() }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun ReferenceArtwork(x: Int, y: Int, width: Int, height: Int, modifier: Modifier, alignment: Alignment = Alignment.Center) {
    val bitmap = ImageBitmap.imageResource(R.drawable.passo_welcome)
    val painter = remember(bitmap, x, y, width, height) {
                val scaleX = bitmap.width / 853f
        val scaleY = bitmap.height / 1844f
        BitmapPainter(bitmap,
            srcOffset = IntOffset((x * scaleX).toInt(), (y * scaleY).toInt()),
            srcSize = IntSize((width * scaleX).toInt(), (height * scaleY).toInt()))
    }
    Image(painter = painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop, alignment = alignment)
}
