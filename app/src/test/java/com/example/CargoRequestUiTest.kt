package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.example.core.localization.AppLanguage
import com.example.data.api.*
import com.example.ui.screens.intercity.*
import com.example.ui.screens.passenger.CommuteCream
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy

/** Production form with isolated local fixtures. No map, network, account, SMS or booking is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CargoRequestUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val types = listOf(MobilityVehicleType("TRUCK", "Truck", 1))
    private val complete = CargoRequestDraft("Bole, Addis Ababa", "Sendafa", intercityToday(), "10:30", "2000", "TRUCK",
        "Cement bags · loading help required", "Demo Customer", "0912000111", 9.00 to 38.78, 9.15 to 39.03)
    private val api: MobilityApi get() = Proxy.newProxyInstance(MobilityApi::class.java.classLoader, arrayOf(MobilityApi::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Cargo request API fixture"
            "road" -> Response.success(MobilityRoadResponse(true, 38.2, 60.0))
            else -> error("Unexpected API call: ${method.name}")
        }
    } as MobilityApi

    private fun photo(): CargoPhotoState {
        // Isolated test image extracted from the selected mockup, never production request data.
        val bytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/cargo-cement-demo.png")).use { it.readBytes() }
        return CargoPhotoState().apply { this.bytes = bytes; type = "image/png" }
    }
    private fun show(draft: CargoRequestDraft, photo: CargoPhotoState, adult: Boolean = true,
        submit: (MobilityRequestInput, ByteArray?, String?) -> Unit = { _, _, _ -> }) {
        compose.setContent {
            var confirmed by remember { mutableStateOf(adult) }
            MyApplicationTheme {
                Column(Modifier.fillMaxSize().background(CommuteCream).verticalScroll(rememberScrollState()).padding(16.dp)) {
                    CargoRequestForm(MobilityUiState(types = types, adultConfirmed = confirmed), AppLanguage.ENGLISH,
                        { confirmed = it }, submit, initialDraft = draft, providedPhoto = photo, providedApi = api)
                }
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File("build/mobility-previews").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun `compact sections preserve edits and never submit before review confirmation`() {
        var sent: MobilityRequestInput? = null
        show(complete, photo(), submit = { input, bytes, mime -> assertNotNull(bytes); assertEquals("image/png", mime); sent = input })
        compose.onNodeWithTag("cargo_section_2").performScrollTo().performClick()
        compose.onNodeWithTag("cargo_weight").performTextReplacement("250.5")
        compose.onNodeWithTag("cargo_section_3").performScrollTo().performClick()
        compose.onNodeWithTag("request_name").performTextReplacement("Updated Customer")
        compose.onNodeWithTag("cargo_section_3").performScrollTo().performClick()
        compose.onNodeWithTag("cargo_section_2").performScrollTo().performClick()
        compose.onNodeWithTag("cargo_weight").assertTextContains("250.5")
        compose.onNodeWithTag("cargo_section_2").performScrollTo().performClick()
        compose.onNodeWithTag("cargo_review").performScrollTo().performClick()
        compose.onNodeWithText("Review cargo request").assertExists()
        assertNull(sent)
        compose.onNodeWithTag("mobility_send_request").performClick()
        compose.runOnIdle {
            assertEquals("Updated Customer", sent!!.customerName)
            assertEquals(250.5, sent!!.estimatedWeightKg!!, 0.001)
            assertEquals(9.0, sent!!.originLatitude!!, 0.001)
            assertEquals("CARGO", sent!!.kind)
        }
    }
    @Test fun `consent is required and edit from review preserves cargo details`() {
        var sent = false
        show(complete, photo(), adult = false, submit = { _, _, _ -> sent = true })
        compose.onNodeWithTag("cargo_review").performScrollTo().performClick()
        compose.onNodeWithText("Please confirm the age and contact permission statement.").assertExists()
        compose.onNodeWithTag("mobility_send_request").assertDoesNotExist()
        compose.onNodeWithTag("cargo_consent").performScrollTo().performClick()
        compose.onNodeWithTag("cargo_review").performScrollTo().performClick()
        compose.onNodeWithText("Edit details").performClick()
        compose.onNodeWithTag("cargo_section_2").performScrollTo().performClick()
        compose.onNodeWithTag("cargo_details").assertTextContains(complete.details)
        assertFalse(sent)
    }
    @Test fun `missing location prevents review and validation opens route section`() {
        show(complete.copy(pickup = null), photo())
        compose.onNodeWithTag("cargo_section_3").performScrollTo().performClick()
        compose.onNodeWithTag("cargo_review").performScrollTo().performClick()
        compose.onNodeWithTag("request_origin").performScrollTo().assertExists()
        compose.onNodeWithText("Select and confirm this location on the map.").assertExists()
        compose.onNodeWithTag("mobility_send_request").assertDoesNotExist()
    }
    @Test fun `compact request renders actual photo and readiness state without a passenger price estimate`() {
        show(complete, photo())
        compose.waitUntil(5000) { compose.onAllNodesWithTag("cargo_photo_preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cargo_photo_preview").assertExists()
        compose.onNodeWithText("All required details added").assertExists()
        compose.onNodeWithTag("cargo_review").assertIsDisplayed()
        compose.onNodeWithText("Suggested cargo quote", substring = true).assertDoesNotExist()
        capture("05-cargo-compact-request")
        compose.onNodeWithTag("cargo_review").performScrollTo().performClick()
        capture("06-cargo-review")
    }
}
