package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.core.localization.AppLanguage
import com.example.data.api.*
import com.example.ui.screens.intercity.MobilityDriverScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobilityDriverLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val vehicle = MobilityVehicle("fixture-vehicle", "MINIVAN_8", "FIXTURE-1", "Fixture vehicle", 8)
    private val awaiting = MobilityRequest("fixture-awaiting", "CARGO", "Bole, Addis Ababa", "Sendafa", "AWAITING_CONFIRMATION",
        travelDate = "2026-11-03", vehicleType = "MINIVAN_8", cargoDetails = "Cement bags", customerName = "Fixture Customer",
        customerPhone = "0912000111", vehicleId = vehicle.id, vehiclePlate = vehicle.plateNumber, quoteEtb = 12000.0,
        estimatedWeightKg = 2000.0, commissionEtb = 600.0)
    private val completed = awaiting.copy(id = "fixture-completed", origin = "Adama", destination = "Hawassa", status = "COMPLETED")
    private val opportunity = MobilityOpportunity("fixture-opportunity", "CARGO", "Bole", "Adama", "2026-11-04", "10:30",
        vehicleType = "MINIVAN_8", estimatedWeightKg = 500.0, routeDistanceKm = 100.0, pickupDistanceKm = 2.0,
        estimatedCargoPriceEtb = 2500.0, nearby = true)
    private fun api(dashboard: MobilityDriverDashboard): MobilityApi = Proxy.newProxyInstance(MobilityApi::class.java.classLoader,
        arrayOf(MobilityApi::class.java)) { _, method, _ -> when (method.name) {
            "driverDashboard" -> Response.success(dashboard)
            "opportunities" -> Response.success(MobilityOpportunitiesResponse(true, listOf(opportunity), MobilityWallet(2100.0, 600.0, 1500.0)))
            else -> error("Unexpected API call ${method.name}")
        } } as MobilityApi
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/driver-previews").apply { mkdirs() }
            File(directory, name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun `cargo dashboard presents one current job and compact functional sections`() {
        val dashboard = MobilityDriverDashboard(true, "CARGO", listOf(vehicle), requests = listOf(awaiting, completed))
        compose.setContent { MyApplicationTheme { MobilityDriverScreen(api(dashboard), dashboard, AppLanguage.ENGLISH, {}, {}) } }
        compose.onNodeWithText("Your cargo jobs").assertIsDisplayed()
        compose.onNodeWithText("PASSO").assertDoesNotExist()
        compose.onNodeWithTag("market_credit_strip").assertIsDisplayed()
        compose.onNodeWithText("Waiting for customer confirmation").assertIsDisplayed()
        compose.onAllNodesWithText("Bole, Addis Ababa → Sendafa").assertCountEquals(1)
        compose.onNodeWithTag("market_progress").assertDoesNotExist()
        capture("cargo-compact-current")
        compose.onNodeWithTag("market_credit").performClick()
        compose.onNodeWithText("Driver credit").assertIsDisplayed()
        compose.onNodeWithText("Contact Passo").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("market_tab_history").performClick()
        compose.onNodeWithText("Adama → Hawassa").performScrollTo().performClick()
        compose.onNodeWithTag("market_job_details").performScrollTo().performClick()
        compose.onNodeWithText("0912000111").assertExists()
        compose.onNodeWithTag("market_tab_requests").performScrollTo().performClick()
        compose.onNodeWithTag("market_quote_fixture-opportunity").performScrollTo().assertIsEnabled()
        capture("cargo-compact-requests")
    }
    @Test fun `scheduled group journey proposal remains accessible without a duplicate intro`() {
        val dashboard = MobilityDriverDashboard(true, "GROUP_TRAVEL", listOf(vehicle))
        compose.setContent { MyApplicationTheme { MobilityDriverScreen(api(dashboard), dashboard, AppLanguage.ENGLISH, {}, {}) } }
        compose.onNodeWithText("Your group jobs").assertIsDisplayed()
        compose.onNodeWithText("Scheduled group journeys").performScrollTo().performClick()
        compose.onNodeWithTag("group_propose_trip").performScrollTo().performClick()
        compose.onNodeWithTag("group_proposal_origin").assertExists()
        compose.onNodeWithTag("group_proposal_submit").performScrollTo().assertIsNotEnabled()
        capture("group-compact-proposal")
    }
}
