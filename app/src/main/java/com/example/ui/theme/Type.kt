package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.example.R

@OptIn(ExperimentalTextApi::class)
val PassoFont = FontFamily(
    Font(R.font.outfit, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.outfit, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.outfit, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.outfit, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.outfit, weight = FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800)))
)

private val BaseTypography = Typography()
val Typography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = PassoFont),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = PassoFont),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = PassoFont),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = PassoFont),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = PassoFont),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = PassoFont),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = PassoFont),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = PassoFont),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = PassoFont),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = PassoFont),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = PassoFont),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = PassoFont),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = PassoFont),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = PassoFont),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = PassoFont)
)
