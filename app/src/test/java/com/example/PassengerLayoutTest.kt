package com.example

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.core.localization.AppLanguage
import com.example.ui.components.MiniVehicleMap
import com.example.ui.screens.passenger.PassengerQrCard
import com.example.ui.screens.passenger.SubscriptionStatusCard
import com.example.ui.theme.MyApplicationTheme
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PassengerLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun savePreview(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(image.width > 100 && image.height > 100)
        val dir = File("build/passenger-previews").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `boarding pass fits narrow phone with larger text and its rendered QR decodes`() {
        val token = passengerPreviewSubscription().qrToken
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                MyApplicationTheme {
                    Column(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                        PassengerTopBar("Sample Passenger", "SP", AppLanguage.ENGLISH, onProfile={}, onBack={}, onNotifications={}, onLanguage={}, onLogout={})
                        Column(Modifier.padding(16.dp)) { PassengerQrCard(true, token, "ACTIVE", AppLanguage.ENGLISH) }
                    }
                }
            }
        }
        compose.onNodeWithText("Active").assertIsDisplayed()
        compose.onNodeWithText("Show this code to your driver when you board.").assertIsDisplayed()
        val qr = compose.onNodeWithTag("passenger_boarding_qr").captureToImage().asAndroidBitmap()
        val pixels = IntArray(qr.width * qr.height)
        qr.getPixels(pixels, 0, qr.width, 0, 0, qr.width, qr.height)
        assertEquals(token, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width, qr.height, pixels)))).text)
        savePreview("02-boarding-pass-360dp-large-text")
    }

    @Test fun `home summary uses actual pass data and boarding action remains visible`() {
        var opened = 0
        compose.setContent {
            MyApplicationTheme {
                Column(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                    PassengerTopBar("Sample Passenger", "SP", AppLanguage.ENGLISH, onProfile={}, onBack={}, onNotifications={}, onLanguage={}, onLogout={})
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SubscriptionStatusCard(passengerPreviewSubscription(), "Garment – Bole Medhanialem", AppLanguage.ENGLISH, onRenewClick={}, onShowPass={ opened++ })
                        MiniVehicleMap(null)
                    }
                }
            }
        }
        compose.onNodeWithText("Abebe Alemu").assertDoesNotExist()
        compose.onNodeWithText("Toyota Coaster • Plate: AA-12345").assertDoesNotExist()
        compose.onNodeWithText("20").assertIsDisplayed()
        compose.onNodeWithTag("show_boarding_pass_button").assertIsDisplayed().performClick()
        assertEquals(1, opened)
        savePreview("01-pass-summary-360dp")
    }
}
