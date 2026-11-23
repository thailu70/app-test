package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.example.data.api.*
import com.example.data.api.ApiClient
import com.example.data.database.TransportDatabase
import com.example.data.repository.TransportRepository
import com.example.ui.screens.taxi.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
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
class TaxiLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: TransportDatabase
    private lateinit var main: MainViewModel

    @Before fun createMainViewModel() {
        val offlineApi = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader,
            arrayOf(RoutePassApiService::class.java)) { _, _, _ ->
            Response.error<Any>(503, "{}".toResponseBody("application/json".toMediaType()))
        } as RoutePassApiService
        compose.runOnIdle {
            database = Room.inMemoryDatabaseBuilder(compose.activity, TransportDatabase::class.java)
                .allowMainThreadQueries().build()
            main = MainViewModel(TransportRepository(database.transportDao(), offlineApi))
            compose.activity.viewModelStore.put("taxi-layout-test", main)
        }
    }

    @After fun closeDatabase() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        TaxiPendingNavigation.clear()
        ApiClient.setAuthToken(null)
        database.close()
    }

    private fun api(): TaxiApi = Proxy.newProxyInstance(TaxiApi::class.java.classLoader, arrayOf(TaxiApi::class.java)) { _, method, _ ->
        when (method.name) {
            "catalog" -> Response.success(TaxiResponse(true,
                settings = TaxiSettings(enabled = true, sharedEnabled = true, baseFareEtb = 35.0, perKmEtb = 18.0, minimumFareEtb = 120.0),
                tiers = listOf(TaxiTier("ECONOMY", "Economy"), TaxiTier("COMFORT", "Comfort", 1.25), TaxiTier("MINI_VAN", "Mini-Van", 1.5))))
            "shared" -> Response.success(TaxiResponse(true))
            "nearby" -> Response.success(TaxiNearbyResponse(true))
            "estimate" -> Response.success(TaxiResponse(true, estimate = TaxiEstimate(5.0, 12.0, 125.0, "ECONOMY")))
            else -> error("Unexpected ${method.name}")
        }
    } as TaxiApi
    @Test fun `sign-in chooser uses OTP-style role cards before passenger phone prompt`() {
        compose.setContent { MyApplicationTheme {
            val vm = androidx.compose.runtime.remember { TaxiViewModel(api(), false, false) }
            TaxiPassengerContent(main, false, vm, {}, {}, {}, onDriverSignIn = {}, mapTiles = false)
        } }
        compose.onNodeWithTag("taxi_sign_in").performClick()
        compose.onNodeWithTag("taxi_sign_in_chooser").assertIsDisplayed()
        compose.onNodeWithTag("taxi_sign_in_continue").assertIsNotEnabled()
        compose.onNodeWithTag("taxi_sign_in_passenger").performClick()
        compose.onNodeWithTag("taxi_sign_in_continue").assertIsEnabled().performClick()
        compose.onNodeWithTag("taxi_phone_dialog").assertIsDisplayed()
    }

    @Test fun `sign-in chooser continues to driver authentication`() {
        var openedDriver = false
        compose.setContent { MyApplicationTheme {
            val vm = androidx.compose.runtime.remember { TaxiViewModel(api(), false, false) }
            TaxiPassengerContent(main, false, vm, {}, {}, {}, onDriverSignIn = { openedDriver = true }, mapTiles = false)
        } }
        compose.onNodeWithTag("taxi_sign_in").performClick()
        compose.onNodeWithTag("taxi_sign_in_driver").performClick()
        compose.onNodeWithTag("taxi_sign_in_continue").performClick()
        compose.runOnIdle { check(openedDriver) { "Driver selection must continue to driver authentication." } }
    }
    @Test fun `taxi landing keeps destination first, group ad and services visible before ride tiers`() {
        var commute = 0
        compose.setContent { MyApplicationTheme { val vm = androidx.compose.runtime.remember { TaxiViewModel(api(), false, false) }
            TaxiPassengerContent(main, false, vm, { commute++ }, {}, {}, mapTiles = false)
        } }
        compose.onNodeWithTag("taxi_where_to").assertIsDisplayed()
        compose.onNodeWithTag("taxi_tier_ECONOMY").assertDoesNotExist()
        compose.onNodeWithTag("taxi_tier_COMFORT").assertDoesNotExist()
        compose.onNodeWithTag("taxi_tier_MINI_VAN").assertDoesNotExist()
        compose.onNodeWithTag("taxi_travel_together").assertIsDisplayed()
        compose.onNodeWithTag("taxi_services_footer").assertIsDisplayed()
        compose.onNodeWithText("Current location").assertDoesNotExist()
        compose.onNodeWithTag("taxi_service_COMMUTE").assertIsDisplayed().performClick()
        assertEquals(1, commute)
        compose.runOnIdle { val view = compose.activity.window.decorView; val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
            val dir = File("build/taxi-previews").apply { mkdirs() }; File(dir, "01-passenger-home.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun `ride choices appear after a destination and service footer collapses`() {
        TaxiPendingNavigation.route(TaxiRouteInput("Bole", "Bole Atlas", 9.0, 38.7, 9.02, 38.75))
        compose.setContent { MyApplicationTheme { val vm = androidx.compose.runtime.remember { TaxiViewModel(api(), false, false) }
            TaxiPassengerContent(main, false, vm, {}, {}, {}, mapTiles = false)
        } }
        compose.onNodeWithTag("taxi_clear_destination").assertIsDisplayed()
        compose.onNodeWithTag("taxi_travel_together").assertDoesNotExist()
        compose.onNodeWithTag("taxi_services_footer").assertDoesNotExist()
        compose.onNodeWithTag("taxi_tier_ECONOMY").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("taxi_tier_COMFORT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("taxi_tier_MINI_VAN").performScrollTo().assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w320dp-h740dp-xhdpi") fun `daily commute shortcut stays compact on a narrow phone`() {
        var commute = 0
        compose.setContent { MyApplicationTheme { TaxiHeader(onDailyCommute = { commute++ }) } }
        compose.onNodeWithTag("taxi_daily_commute").assertIsDisplayed().performClick()
        assertEquals(1, commute)
        compose.onNodeWithText("Daily").assertIsDisplayed()
    }
}
