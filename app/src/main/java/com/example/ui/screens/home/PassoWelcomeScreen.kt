package com.example.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.R

/**
 * Reference-matched PASSO welcome page.
 *
 * The supplied artwork is the visual source of truth. Transparent interaction
 * zones preserve the exact composition while keeping service, language and
 * continue actions functional.
 */
@Composable
fun PassoWelcomeScreen(
    onContinue: () -> Unit,
    onLanguage: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedService by remember { mutableStateOf("Passenger") }
    var selectedLanguage by remember { mutableStateOf("English") }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFFFFBF4))) {
        val pageHeight = maxHeight
        val pageWidth = maxWidth

        Image(
            painter = painterResource(R.drawable.passo_welcome),
            contentDescription = "PASSO welcome: Go Further, Together",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(pageHeight * 0.18f)
                .offset(y = pageHeight * 0.665f)
                .padding(horizontal = pageWidth * 0.045f)
        ) {
            WelcomeHotspot("Passenger service", selectedService == "Passenger", Modifier.weight(1f)) { selectedService = "Passenger" }
            WelcomeHotspot("Driver service", selectedService == "Driver", Modifier.weight(1f)) { selectedService = "Driver" }
            WelcomeHotspot("Intercity service", selectedService == "Intercity", Modifier.weight(1f)) { selectedService = "Intercity" }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(pageHeight * 0.065f)
                .offset(y = pageHeight * 0.605f)
                .padding(horizontal = pageWidth * 0.04f)
        ) {
            WelcomeHotspot("English language", selectedLanguage == "English", Modifier.weight(1f)) { selectedLanguage = "English" }
            WelcomeHotspot("Amharic language", selectedLanguage == "አማርኛ", Modifier.weight(1f)) { selectedLanguage = "አማርኛ" }
            WelcomeHotspot("Afaan Oromoo language", selectedLanguage == "Afaan Oromoo", Modifier.weight(1f)) { selectedLanguage = "Afaan Oromoo" }
        }

        WelcomeHotspot(
            label = "Continue",
            selected = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(pageHeight * 0.085f)
                .offset(y = pageHeight * 0.87f)
                .padding(horizontal = pageWidth * 0.04f),
            onClick = onContinue
        )

        WelcomeHotspot(
            label = "Change language",
            selected = false,
            modifier = Modifier
                .width(pageWidth * 0.42f)
                .height(pageHeight * 0.05f)
                .offset(x = pageWidth * 0.29f, y = pageHeight * 0.955f),
            onClick = onLanguage
        )
    }
}

@Composable
private fun WelcomeHotspot(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .semantics { contentDescription = label }
            .clickable(onClick = onClick)
    )
}

