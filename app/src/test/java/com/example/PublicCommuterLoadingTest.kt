package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.example.data.api.ApiClient
import com.example.data.api.RouteDetailsResponse
import com.example.data.api.RoutePassApiService
import com.example.data.api.RoutesResponse
import com.example.data.api.RouteDto
import com.example.data.api.RouteStopDto
import com.example.data.database.TransportDatabase
import com.example.data.repository.TransportRepository
import com.example.data.entity.RouteStopEntity
import com.example.ui.screens.home.BrowseFirstScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicCommuterLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: TransportDatabase
    private lateinit var model: MainViewModel
    private lateinit var repository: TransportRepository
    private var online = true
    private var pauseFirstDetail = false
    private var detailWasPaused = false
    private var pendingDetail: Continuation<Response<RouteDetailsResponse>>? = null
    private var listRequests = 0
    private var assignedDetails: RouteDetailsResponse? = null
    private var assignedCollection: Job? = null
    private var observedStops = emptyList<RouteStopEntity>()
    private var rawResponse = ""
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(RoutesResponse::class.java)

    @Before fun emptyInstallation() {
        ApiClient.setAuthToken(null)
        rawResponse = requireNotNull(javaClass.getResourceAsStream("/commuter-public-routes.json")).bufferedReader().use { it.readText() }
        compose.runOnIdle {
            val direct = java.util.concurrent.Executor { it.run() }
            database = Room.inMemoryDatabaseBuilder(compose.activity, TransportDatabase::class.java)
                .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
            assertNull("The installation fixture must not contain seeded routes", runBlocking { database.transportDao().getRouteById("route_bole_merkato") })
        }
    }

    private fun showBrowse() {
        val api = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader, arrayOf(RoutePassApiService::class.java)) { _, method, args ->
            when (method.name) {
                "getRoutes" -> {
                    listRequests++
                    if (online) Response.success(requireNotNull(adapter.fromJson(rawResponse)))
                    else Response.error<RoutesResponse>(503, "{}".toResponseBody("application/json".toMediaType()))
                }
                "getRouteById" -> {
                    if (assignedDetails?.route?.id == args!![0]) Response.success(requireNotNull(assignedDetails))
                    else if (pauseFirstDetail && !detailWasPaused) {
                        detailWasPaused = true
                        @Suppress("UNCHECKED_CAST")
                        val continuation = args!!.last() as Continuation<Response<RouteDetailsResponse>>
                        pendingDetail = continuation
                        COROUTINE_SUSPENDED
                    } else Response.error<RouteDetailsResponse>(503, "{}".toResponseBody("application/json".toMediaType()))
                }
                else -> Response.error<Any>(503, "{}".toResponseBody("application/json".toMediaType()))
            }
        } as RoutePassApiService
        compose.runOnIdle {
            repository = TransportRepository(database.transportDao(), api)
            model = MainViewModel(repository)
            compose.activity.viewModelStore.put("public-commuter-loading", model)
        }
        compose.setContent {
            MyApplicationTheme {
                BrowseFirstScreen(model, { _, _, _, _ -> }, {}, {}, startWithRoutes = true)
            }
        }
    }

    @After fun closeInstallation() {
        compose.runOnIdle {
            pendingDetail?.resumeWithException(CancellationException("Test finished"))
            pendingDetail = null
            assignedCollection?.cancel()
            compose.activity.viewModelStore.clear()
        }
        database.close()
        ApiClient.setAuthToken(null)
    }

    @Test fun freshInstallationPublishesLiveContractRoutesBeforeSlowStopDetails() {
        pauseFirstDetail = true
        showBrowse()
        compose.waitUntil(5_000) { model.activeRoutes.value.size == 5 && pendingDetail != null }
        assertTrue("Stop hydration is still pending while all public summaries are already visible", model.publicRoutesLoading.value)
        assertEquals(1, listRequests)
        compose.onNodeWithText("5 matching services").assertExists()
        compose.onNodeWithText("Bole - Merkato Express").assertExists()
        val cached = requireNotNull(runBlocking { database.transportDao().getRouteById("route_bole_merkato") })
        assertEquals(14.5, cached.distanceKm, 0.0)
        assertEquals(2500.0, cached.basePriceEtb, 0.0)
        assertEquals("06:30", cached.morningDeparture)
        assertFalse(model.isAuthenticated.value)
        compose.runOnIdle {
            val continuation = requireNotNull(pendingDetail)
            pendingDetail = null
            continuation.resume(Response.error(503, "{}".toResponseBody("application/json".toMediaType())))
        }
        compose.waitUntil(5_000) { !model.publicRoutesLoading.value }
        compose.onNodeWithTag("commuter_routes_error").assertDoesNotExist()
        compose.onNodeWithTag("commuter_route_details_route_bole_merkato").performScrollTo().assertIsDisplayed()
        capture("09-fresh-public-daily-routes")
    }

    @Test fun failedInitialDiscoveryShowsRetryAndRecoversPublishedRoutesWithoutRestart() {
        online = false
        showBrowse()
        compose.waitUntil(5_000) { !model.publicRoutesLoading.value && model.publicRoutesError.value != null }
        compose.onNodeWithTag("commuter_routes_retry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No routes available yet").assertDoesNotExist()
        online = true
        compose.onNodeWithTag("commuter_routes_retry").performClick()
        compose.waitUntil(5_000) { !model.publicRoutesLoading.value && model.activeRoutes.value.size == 5 }
        assertNull(model.publicRoutesError.value)
        compose.onNodeWithTag("commuter_routes_error").assertDoesNotExist()
        compose.onNodeWithTag("commuter_route_details_route_bole_merkato").performScrollTo().assertIsDisplayed()
        assertFalse(model.isAuthenticated.value)
    }

    @Test fun successfulEmptyDirectoryHasGenuineEmptyStateRatherThanConnectionError() {
        rawResponse = """{"success":true,"count":0,"routes":[]}"""
        showBrowse()
        compose.waitUntil(5_000) { !model.publicRoutesLoading.value }
        compose.onNodeWithText("No routes available yet").assertExists()
        compose.onNodeWithTag("commuter_routes_error").assertDoesNotExist()
        assertNull(model.publicRoutesError.value)
    }

    @Test fun assignedRouteStopsHydrateEvenWhenRouteIsNotInTheActivePublicDirectory() {
        rawResponse = """{"success":true,"count":0,"routes":[]}"""
        assignedDetails = RouteDetailsResponse(true,
            RouteDto("assigned-only", "Bole - CMC", "ቦሌ - ሲኤምሲ", active = false),
            listOf(
                RouteStopDto("assigned-destination", "assigned-only", "CMC", "ሲኤምሲ", 2, 9.01, 38.8),
                RouteStopDto("assigned-pickup", "assigned-only", "Bole", "ቦሌ", 1, 9.0, 38.75)
            ))
        showBrowse()
        compose.waitUntil(5_000) { !model.publicRoutesLoading.value }
        compose.runOnIdle {
            assignedCollection = compose.activity.lifecycleScope.launch {
                model.observeRouteStops("assigned-only").collect { observedStops = it }
            }
        }
        compose.waitUntil(5_000) { observedStops.size == 2 }
        assertEquals(listOf("Bole", "CMC"), observedStops.map { it.stopName })
        assertEquals(listOf("assigned-only", "assigned-only"), observedStops.map { it.routeId })
        assertTrue("An inactive assigned route must not become a public available service", model.activeRoutes.value.isEmpty())
    }

    @Test fun liveGarmentDetailContractPreservesAuthoritativeNamesIdsSchedulesAndCoordinatesInRoom() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/commuter-route-detail.json")).bufferedReader().use { it.readText() }
        val detailAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(RouteDetailsResponse::class.java)
        val details = requireNotNull(detailAdapter.fromJson(raw))
        assertTrue(details.success)
        assertEquals("route_4b6c8a9a", details.route?.id)
        assertEquals(listOf("stop_587d964c", "stop_bf53b936"), details.stops.map { it.id })
        assertEquals(listOf("Garment", "Bole medhanialem"), details.stops.map { it.stopName })
        assertEquals(8.936007, details.stops.first().latitude, 0.0)
        assertEquals(38.722349, details.stops.first().longitude, 0.0)
        assertEquals(8.786702, details.stops.last().longitude, 0.0)

        rawResponse = """{"success":true,"count":0,"routes":[]}"""
        assignedDetails = details
        showBrowse()
        compose.waitUntil(5_000) { !model.publicRoutesLoading.value }
        runBlocking { repository.refreshRouteDetailsFromBackend("route_4b6c8a9a") }
        val stops = runBlocking { database.transportDao().getStopsForRouteSync("route_4b6c8a9a") }
        assertEquals(listOf("Garment", "Bole medhanialem"), stops.map { it.stopName })
        assertEquals(listOf("stop_587d964c", "stop_bf53b936"), stops.map { it.id })
        assertEquals(listOf("route_4b6c8a9a", "route_4b6c8a9a"), stops.map { it.routeId })
        assertEquals(listOf("01:04", "01:04"), stops.map { it.scheduledMorningTime })
        assertEquals(listOf("22:50", "22:50"), stops.map { it.scheduledEveningTime })
        assertEquals(38.722349, stops.first().longitude, 0.0)
        assertEquals(8.786702, stops.last().longitude, 0.0)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val folder = File("build/commuter-previews").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
