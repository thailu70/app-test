package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = TransportGreenLight,
    onPrimary = Slate900,
    primaryContainer = TransportGreenDark,
    onPrimaryContainer = Color.White,
    secondary = TransportGold,
    onSecondary = Slate900,
    secondaryContainer = Slate800,
    onSecondaryContainer = TransportGoldLight,
    tertiary = TelebirrBlue,
    background = Color(0xFF0A0F1D),
    surface = Slate900,
    onBackground = Slate50,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate200,
    error = StatusExpiredRed
)

private val LightColorScheme = lightColorScheme(
    primary = TransportGreenPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = TransportGreenDark,
    secondary = TransportGold,
    onSecondary = Slate900,
    secondaryContainer = Color(0xFFFEF3C7),
    onSecondaryContainer = Color(0xFF78350F),
    tertiary = TelebirrBlue,
    background = Slate100,
    surface = Color.White,
    onBackground = Slate900,
    onSurface = Slate900,
    surfaceVariant = Slate50,
    onSurfaceVariant = Slate700,
    error = StatusExpiredRed
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep custom brand palette consistent
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
