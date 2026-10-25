package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import com.example.core.localization.AppLanguage
import com.example.data.api.*
import com.example.data.database.TransportDatabase
import com.example.data.repository.IntercityBookingAccess
import com.example.data.repository.IntercityBookingAccessStore
import com.example.data.repository.TransportRepository
import com.example.ui.screens.home.BrowseFirstScreen
import com.example.ui.screens.home.PassoWelcomeScreen
import com.example.ui.screens.intercity.IntercityPassengerScreen
import com.example.ui.screens.intercity.IntercityPassengerViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId

/**
 * Store assets rendered by the shipping screens, not reconstructed marketing layouts.
 * All inventory is explicitly labelled demo data and injected locally: these tests
 * never connect to a server, open a map, sign in, or create a booking/payment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayStorePreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var database: TransportDatabase? = null

    @After fun releaseLocalFixtures() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        database?.close()
        database = null
    }

    @Test fun welcomeShowsNativeServiceAndLanguageControls() {
        var language by mutableStateOf(AppLanguage.ENGLISH)
        compose.setContent {
            MyApplicationTheme {
                PassoWelcomeScreen({}, language, { language = it }, onPrivacy = {})
            }
        }
        compose.onNodeWithTag("welcome_continue").assertIsDisplayed()
        compose.onNodeWithTag("welcome_privacy").assertIsDisplayed()
        compose.onNodeWithTag("welcome_language_en").assertIsDisplayed()
        listOf("PASSENGER", "DRIVER", "INTERCITY").forEach {
            compose.onNodeWithTag("welcome_service_$it").assertIsDisplayed()
        }
        capture("01-welcome")
    }

    @Test fun dailyCommuteShowsRealPublicBrowserWithDemoInventory() {
        lateinit var model: MainViewModel
        compose.runOnIdle {
            val direct = java.util.concurrent.Executor { it.run() }
            val local = Room.inMemoryDatabaseBuilder(compose.activity, TransportDatabase::class.java)
                .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
            database = local
            model = MainViewModel(TransportRepository(local.transportDao(), demoDailyApi()))
            compose.activity.viewModelStore.put("play-preview-daily", model)
        }
        compose.setContent {
            MyApplicationTheme {
                BrowseFirstScreen(model, { _, _, _, _ -> }, {}, {}, startWithRoutes = true)
            }
        }
        compose.waitUntil(10_000) { !model.publicRoutesLoading.value && model.activeRoutes.value.size == 1 }
        assertFalse("The store preview must remain public", model.isAuthenticated.value)
        compose.onNodeWithTag("commuter_browse_sign_in").assertIsDisplayed()
        compose.onNodeWithText("Find a daily route").assertIsDisplayed()
        compose.onNodeWithText("Where do you commute?").assertIsDisplayed()
        compose.onNodeWithText("All routes").assertIsDisplayed()
        capture("02-daily-commute")

        // A second, naturally scrolled view shows the actual fare/availability UI.
        compose.onNodeWithTag("commuter_route_details_${demoDailyRoute.id}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(demoDailyRoute.name).assertIsDisplayed()
        compose.onNodeWithText("Economy").assertIsDisplayed()
        compose.onNodeWithText("ETB 2,500").assertIsDisplayed()
        capture("03-daily-commute-routes")
    }

    @Test fun intercityShowsRealNativeSearchAndUpcomingDemoDeparture() {
        lateinit var model: IntercityPassengerViewModel
        compose.runOnIdle {
            model = IntercityPassengerViewModel(DemoIntercityApi(), EmptyBookingStore(), Dispatchers.Unconfined)
            compose.activity.viewModelStore.put("play-preview-intercity", model)
        }
        compose.setContent {
            MyApplicationTheme {
                IntercityPassengerScreen(AppLanguage.ENGLISH, {}, {}, providedViewModel = model)
            }
        }
        compose.waitUntil(10_000) { model.state.value.searchCompleted && !model.state.value.busy }
        assertTrue(model.state.value.showingUpcoming)
        assertTrue("Store previews must contain no private bookings", model.state.value.recent.isEmpty())
        compose.onNodeWithTag("intercity_hero").assertIsDisplayed()
        compose.onNodeWithTag("intercity_from").assertIsDisplayed()
        compose.onNodeWithTag("intercity_to").assertIsDisplayed()
        compose.onNodeWithTag("intercity_search").assertIsDisplayed()
        capture("04-intercity-search")

        compose.onNodeWithTag("intercity_content")
            .performScrollToNode(hasTestTag("intercity_select_demo-departure"))
        compose.onNodeWithTag("intercity_select_demo-departure").assertIsDisplayed()
        compose.onNodeWithText("Passo Demo Transport").assertIsDisplayed()
        capture("05-intercity-routes")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            assertEquals("Phone preview width must meet Play's 9:16 asset size", 1080, view.width)
            assertEquals("Phone preview height must meet Play's 9:16 asset size", 1920, view.height)
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            try {
                val directory = File("build/play-store-previews").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use {
                    assertTrue("PNG encoding must succeed", image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                image.recycle()
            }
        }
    }

    private fun demoDailyApi(): RoutePassApiService = Proxy.newProxyInstance(
        RoutePassApiService::class.java.classLoader, arrayOf(RoutePassApiService::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getRoutes" -> Response.success(RoutesResponse(true, 1, listOf(demoDailyRoute)))
            "getRouteById" -> {
                assertEquals(demoDailyRoute.id, args?.first())
                Response.success(RouteDetailsResponse(true, demoDailyRoute))
            }
            // MainViewModel also refreshes vehicles during initialization. A local
            // error response leaves private data empty, without any Retrofit call.
            else -> Response.error<Any>(503, "{}".toResponseBody("application/json".toMediaType()))
        }
    } as RoutePassApiService

    private class EmptyBookingStore : IntercityBookingAccessStore {
        override fun load() = emptyList<IntercityBookingAccess>()
        override fun save(access: IntercityBookingAccess) = error("Store preview cannot save a booking")
        override fun forget(id: String) = error("Store preview cannot alter bookings")
    }

    private class DemoIntercityApi : IntercityPassengerApi {
        private val departure = IntercityDeparture(
            "demo-departure", "Addis Ababa", "Hawassa",
            LocalDate.now(ZoneId.of("Africa/Addis_Ababa")).plusDays(1).toString() + "T04:00:00Z",
            "Passo Demo Transport", "demo-company", vehicleType = "Coach", vehicleModel = "Demo coach",
            seats = 45, availableSeats = 18, fareEtb = 650.0
        )
        override suspend fun departures(origin: String?, destination: String?, date: String?) =
            Response.success(IntercityDeparturesResponse(true, listOf(departure)))
        override suspend fun companies() = Response.success(
            IntercityCompaniesResponse(true, listOf(IntercityCompany("demo-company", "Passo Demo Transport")))
        )
        override suspend fun seats(id: String): Response<IntercitySeatsResponse> = error("Browse-only preview")
        override suspend fun hold(id: String, body: IntercitySeatHoldRequest): Response<IntercityBookingResponse> = error("Browse-only preview")
        override suspend fun booking(id: String, bookingSecret: String): Response<IntercityBookingResponse> = error("Browse-only preview")
        override suspend fun checkout(id: String, bookingSecret: String, body: IntercityCheckoutRequest): Response<IntercityCheckoutResponse> = error("Browse-only preview")
        override suspend fun requestTestPaymentOtp(id: String, bookingSecret: String, body: IntercityTestOtpRequest): Response<IntercityTestOtpResponse> = error("Browse-only preview")
        override suspend fun verifyTestPaymentOtp(id: String, bookingSecret: String, body: IntercityTestOtpRequest): Response<IntercityTestOtpResponse> = error("Browse-only preview")
        override suspend fun ticketPdf(id: String, secret: String): Response<ResponseBody> = error("Browse-only preview")
        override suspend fun requestRoute(body: IntercityRouteRequest): Response<IntercityRouteRequestResponse> = error("Browse-only preview")
    }

    private companion object {
        val demoDailyRoute = RouteDto(
            id = "demo-daily-route", name = "Bole - CMC · Demo", nameAm = "",
            description = "Demonstration route", morningDeparture = "06:30", eveningDeparture = "17:30",
            distanceKm = 12.0, basePriceEtb = 2500.0, active = true
        )
    }
}
