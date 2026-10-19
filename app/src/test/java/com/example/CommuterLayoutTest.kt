package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.example.core.localization.AppLanguage
import com.example.data.entity.RouteEntity
import com.example.data.entity.RouteStopEntity
import com.example.ui.screens.passenger.*
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
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommuterLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        lateinit var image: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        val dir = File("build/commuter-previews").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }

    @Test fun `commuter home uses actual assigned data and native controls`() {
        val pass = passengerPreviewSubscription()
        var opened = 0
        var renewed = 0
        var language by mutableStateOf(AppLanguage.ENGLISH)
        val route = RouteEntity("route-test", "Bole – CMC", "", "", "07:20", "17:30", 12.0, 2500.0)
        val driver = AssignedDriver("driver-test", "Assigned Test Driver", "0912345678", "MINIVAN_8", "Green & White", "TEST-4321", "Toyota Hiace", route.name)
        compose.setContent {
            MyApplicationTheme {
                Column(Modifier.fillMaxSize().background(CommuteCream).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CommuterHeader(language, onLanguage = { language = it })
                    CommuterGreeting(language, true)
                    CommuterActiveTripCard(pass, route, route.name, emptyList(), driver, false, null, language,
                        onRetry = {}, onShowPass = { opened++ }, onRenew = { renewed++ }, paymentAvailable = true,
                        driverPortrait = { Icon(Icons.Default.Person, null, Modifier.size(56.dp)) })
                    CommuterLocationCard(null, "Waiting for the driver's location.", language) {}
                    CommuterBoardingPreview(pass, route.name, language) { opened++ }
                }
            }
        }
        compose.onNodeWithText("Assigned Test Driver").assertExists()
        compose.onNodeWithText("TEST-4321").assertExists()
        compose.onNodeWithText("0912345678").assertExists()
        compose.onNodeWithText("5 minutes away").assertDoesNotExist()
        capture("01-commuter-home")
        compose.onNodeWithTag("commuter_payment_action").performScrollTo().performClick()
        assertEquals(1, renewed)
        compose.onNodeWithTag("commuter_boarding_preview").performScrollTo().performClick()
        assertEquals(1, opened)
        capture("02-commuter-boarding")
        compose.onNodeWithTag("commuter_language_om").performScrollTo().performClick()
        assertEquals(AppLanguage.OROMO, language)
        compose.onNodeWithText("IMALA GUYYAA KEETII").assertExists()
    }

    @Test fun `home QR encodes server token and is removed when pass becomes ineligible`() {
        var pass by mutableStateOf(passengerPreviewSubscription())
        compose.setContent { MyApplicationTheme { CommuterBoardingPreview(pass, "Assigned route", AppLanguage.ENGLISH) {} } }
        compose.onNodeWithTag("commuter_home_qr", useUnmergedTree = true).assertExists()
        val bounds = compose.onNodeWithTag("commuter_home_qr", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val screenshot = capture("03-home-qr")
        val qr = Bitmap.createBitmap(screenshot, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        val pixels = IntArray(qr.width * qr.height)
        qr.getPixels(pixels, 0, qr.width, 0, 0, qr.width, qr.height)
        assertEquals(pass.qrToken, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width, qr.height, pixels)))).text)
        compose.runOnIdle { pass = pass.copy(paymentStatus = "PENDING") }
        compose.onNodeWithTag("commuter_home_qr", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { pass = pass.copy(paymentStatus = "PAID", daysRemaining = 0) }
        compose.onNodeWithTag("commuter_home_qr", useUnmergedTree = true).assertDoesNotExist()
    }
}
