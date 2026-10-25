package com.example

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.room.Room
import com.example.data.api.*
import com.example.data.database.TransportDatabase
import com.example.data.entity.RouteStopEntity
import com.example.data.entity.UserEntity
import com.example.data.repository.TransportRepository
import com.example.ui.screens.driver.DriverTripScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Exercises the actual driver screen, without a login socket, GPS fix or production HTTP client. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverAssignmentsLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: TransportDatabase
    private lateinit var model: MainViewModel
    private var assignmentRequests = 0
    private var intercityRequests = 0
    private var rosterRequests = 0
    private var activeTripUnavailable = false
    private var pauseActiveTrip = false
    private var pauseIntercity = false
    private var intercityResponse: String? = null
    private var pendingActiveTrip: Continuation<Response<ActiveTripResponse>>? = null
    private var pendingIntercity: Continuation<Response<DriverIntercityResponse>>? = null
    private var pauseRoster = false
    private var rosterUnavailable = false
    private var rosterResponse = DriverRosterResponse(success = true)
    private var pendingRoster: Continuation<Response<DriverRosterResponse>>? = null
    private var pendingRosterAssignment: String? = null
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(DriverAssignmentsResponse::class.java)
    private var response = ""

    private fun assignmentJson(departure: String = "Garment pickup", destination: String = "Bole destination",
        vehicleId: String = "assigned-vehicle", routeId: String = "assigned-route") = """
        {"success":true,"vehicles":[{
          "id":"$vehicleId","plateNumber":"TEST-4321","vehicleType":"MINIVAN_8","capacityLimit":8,
          "assignedRouteId":"$routeId","routeName":"Assigned daily service",
          "morningDeparture":"07:15","eveningDeparture":"17:30",
          "departureStopName":"$departure","departureStopNameAm":"ጋርመንት",
          "destinationStopName":"$destination","destinationStopNameAm":"ቦሌ"
        }]}
    """.trimIndent()

    @Before fun isolatedDriverInstallation() {
        ApiClient.setAuthToken(null)
        response = assignmentJson()
        compose.runOnIdle {
            val direct = java.util.concurrent.Executor { it.run() }
            database = Room.inMemoryDatabaseBuilder(compose.activity, TransportDatabase::class.java)
                .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
            val backend = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader,
                arrayOf(RoutePassApiService::class.java)) { _, _, _ -> unavailable<Any>() } as RoutePassApiService
            model = MainViewModel(TransportRepository(database.transportDao(), backend))
            compose.activity.viewModelStore.put("driver-assignment-loading", model)
            // Seed only session state. Successful login would open the real tracking WebSocket.
            // A blank photo ID also suppresses the independent private portrait HTTP request.
            @Suppress("UNCHECKED_CAST")
            val currentUser = MainViewModel::class.java.getDeclaredField("_currentUser")
                .apply { isAccessible = true }.get(model) as MutableStateFlow<UserEntity?>
            currentUser.value = UserEntity("", "DRIVER", "Fixture Driver", "0912000000", "")
        }
    }

    private fun <T> unavailable(): Response<T> = Response.error(503,
        "{\"error\":\"Auxiliary service unavailable\"}".toResponseBody("application/json".toMediaType()))

    private fun showDriver(registryOwner: ActivityResultRegistryOwner? = null) {
        val operations = Proxy.newProxyInstance(DriverOperationsApi::class.java.classLoader,
            arrayOf(DriverOperationsApi::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "IsolatedDriverOperations"
                "assignments" -> { assignmentRequests++; Response.success(requireNotNull(adapter.fromJson(response))) }
                "activeTrip" -> when {
                    pauseActiveTrip -> {
                        @Suppress("UNCHECKED_CAST")
                        val continuation = args!!.last() as Continuation<Response<ActiveTripResponse>>
                        pendingActiveTrip = continuation
                        COROUTINE_SUSPENDED
                    }
                    activeTripUnavailable -> unavailable<ActiveTripResponse>()
                    else -> Response.success(ActiveTripResponse(success = true))
                }
                "departures" -> {
                    intercityRequests++
                    if (pauseIntercity) {
                        @Suppress("UNCHECKED_CAST")
                        val continuation = args!!.last() as Continuation<Response<DriverIntercityResponse>>
                        pendingIntercity = continuation
                        COROUTINE_SUSPENDED
                    } else intercityResponse?.let { json ->
                        Response.success(requireNotNull(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                            .adapter(DriverIntercityResponse::class.java).fromJson(json)))
                    } ?: unavailable<DriverIntercityResponse>()
                }
                "commuteRoster", "intercityRoster" -> {
                    rosterRequests++
                    when {
                        pauseRoster -> {
                            @Suppress("UNCHECKED_CAST")
                            val continuation = args!!.last() as Continuation<Response<DriverRosterResponse>>
                            pendingRoster = continuation
                            pendingRosterAssignment = args.first().toString()
                            COROUTINE_SUSPENDED
                        }
                        rosterUnavailable -> unavailable<DriverRosterResponse>()
                        else -> Response.success(rosterResponse)
                    }
                }
                else -> unavailable<Any>()
            }
        } as DriverOperationsApi
        val profile = Proxy.newProxyInstance(ProfileApi::class.java.classLoader,
            arrayOf(ProfileApi::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "IsolatedDriverProfile"
                "getMyProfile" -> Response.success(ProfileResponse(success = true, profile = CompletionProfile(role = "DRIVER")))
                else -> error("No photo or document requests belong in this fixture")
            }
        } as ProfileApi
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides (registryOwner ?: compose.activity)) {
                MyApplicationTheme { DriverTripScreen(model, operationsApi = operations, providedProfileApi = profile) }
            }
        }
    }

    @Test fun openingDriverPortalNeverLaunchesPermissionUntilDisclosureIsAccepted() {
        shadowOf(compose.activity.application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val permissionLaunches = mutableListOf<List<String>>()
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    @Suppress("UNCHECKED_CAST")
                    permissionLaunches += (input as Array<String>).toList()
                }
            }
        }
        showDriver(registryOwner)
        compose.onNodeWithTag("driver_enable_location").performScrollTo().assertIsDisplayed()
        assertTrue("Portal entry cannot request a runtime permission", permissionLaunches.isEmpty())
        compose.onNodeWithTag("driver_enable_location").performClick()
        compose.onNodeWithTag("driver_location_disclosure").assertExists()
        assertTrue("Disclosure must precede the Android prompt", permissionLaunches.isEmpty())
        compose.onNodeWithTag("driver_location_disclosure_cancel").performClick()
        assertTrue(permissionLaunches.isEmpty())
        compose.onNodeWithTag("driver_enable_location").performClick()
        compose.onNodeWithTag("driver_location_disclosure_allow").performClick()
        compose.waitUntil(5_000) { permissionLaunches.isNotEmpty() }
        assertEquals(listOf(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)), permissionLaunches)
        assertFalse("Location consent cannot create a driving session", model.isAuthenticated.value)
    }

    @After fun closeDriverInstallation() {
        compose.runOnIdle {
            pendingRoster?.resumeWithException(CancellationException("Test finished"))
            pendingRoster = null
            pendingActiveTrip?.resumeWithException(CancellationException("Test finished"))
            pendingActiveTrip = null
            pendingIntercity?.resumeWithException(CancellationException("Test finished"))
            pendingIntercity = null
            compose.activity.viewModelStore.clear()
        }
        database.close()
        ApiClient.setAuthToken(null)
    }

    private fun routeText(value: String) = compose.onNode(
        hasText(value) and hasAnyAncestor(hasTestTag("driver_route_overview")))

    private fun assertEndpoints(departure: String, destination: String) {
        compose.onNodeWithTag("driver_route_overview").performScrollTo().assertIsDisplayed()
        routeText(departure).assertIsDisplayed()
        routeText(destination).assertIsDisplayed()
        assertTrue("The departure must precede the destination on the actual route card",
            routeText(departure).fetchSemanticsNode().boundsInRoot.top <
                routeText(destination).fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("Departure unavailable").assertDoesNotExist()
        compose.onNodeWithText("Destination unavailable").assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        File("build/driver-previews").apply { mkdirs() }.resolve("$name.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun emptyCacheStillShowsAssignedEndpointsWhenOtherDriverServicesFail() {
        activeTripUnavailable = true
        val assignment = requireNotNull(adapter.fromJson(response)).vehicles.single()
        assertEquals("ጋርመንት", assignment.departureStopNameAm)
        assertEquals("ቦሌ", assignment.destinationStopNameAm)
        assertNull(runBlocking { database.transportDao().getRouteById("assigned-route") })
        showDriver()
        compose.waitUntil(5_000) { assignmentRequests > 0 && intercityRequests > 0 && rosterRequests > 0 }
        assertEndpoints("Garment pickup", "Bole destination")
        capture("07-driver-live-assignment-empty-cache")
    }

    @Test fun delayedIntercityRequestDoesNotDelayDailyAssignmentSelection() {
        pauseIntercity = true
        showDriver()
        compose.waitUntil(5_000) { pendingIntercity != null && rosterRequests > 0 }
        assertEndpoints("Garment pickup", "Bole destination")
        assertNotNull("The unrelated intercity response must still be pending", pendingIntercity)
        assertEquals(1, assignmentRequests)
    }

    @Test fun refreshUsesNewEndpointsForSameRouteAndReturnReversesThemDespiteStaleRoom() {
        runBlocking {
            database.transportDao().insertStops(listOf(
                RouteStopEntity("stale-pickup", "assigned-route", "Old cached pickup", "", 1, 9.03, 38.74, "07:15", "17:30"),
                RouteStopEntity("stale-destination", "assigned-route", "Old cached destination", "", 2, 9.04, 38.75, "08:00", "18:00")
            ))
        }
        showDriver()
        compose.waitUntil(5_000) { rosterRequests > 0 }
        assertEndpoints("Garment pickup", "Bole destination")
        routeText("Old cached pickup").assertDoesNotExist()
        routeText("Old cached destination").assertDoesNotExist()
        val previousRequests = assignmentRequests
        compose.runOnIdle { response = assignmentJson("Updated pickup", "Updated destination") }
        compose.onNodeWithTag("driver_nav_more").performClick()
        compose.onNodeWithText("Refresh assignments").performScrollTo().performClick()
        compose.waitUntil(5_000) { assignmentRequests > previousRequests }
        compose.onNodeWithTag("driver_nav_home").performClick()
        assertEndpoints("Updated pickup", "Updated destination")
        routeText("Garment pickup").assertDoesNotExist()
        compose.onNodeWithText("Return").performScrollTo().performClick()
        assertEndpoints("Updated destination", "Updated pickup")
        capture("08-driver-refreshed-return-endpoints")
    }

    @Test fun selectingIntercityAfterCommuteReturnKeepsItsForwardEndpoints() {
        intercityResponse = """{"success":true,"departures":[{
            "id":"intercity-assigned","origin":"Hawassa","destination":"Adama",
            "departure_at":"2026-10-20T06:00:00+03:00","status":"SCHEDULED",
            "vehicle_id":"intercity-vehicle","seats":24,"plateNumber":"COACH-9"
        }]}"""
        showDriver()
        compose.waitUntil(5_000) { intercityRequests > 0 && rosterRequests > 0 }
        assertEndpoints("Garment pickup", "Bole destination")
        compose.onNodeWithText("Return").performScrollTo().performClick()
        assertEndpoints("Bole destination", "Garment pickup")
        compose.onNodeWithText("Hawassa → Adama").performScrollTo().performClick()
        assertEndpoints("Hawassa", "Adama")
        routeText("Bole destination").assertDoesNotExist()
        compose.onNodeWithText("Return").assertDoesNotExist()
    }

    @Test fun activeTripArrivingAfterAssignmentsSelectsItsMatchingSecondVehicle() {
        pauseActiveTrip = true
        val first = org.json.JSONObject(assignmentJson()).getJSONArray("vehicles").getJSONObject(0)
        val second = org.json.JSONObject(assignmentJson("Active pickup", "Active destination", "active-vehicle", "active-route"))
            .getJSONArray("vehicles").getJSONObject(0)
        response = """{"success":true,"vehicles":[$first,$second]}"""
        showDriver()
        compose.waitUntil(5_000) { pendingActiveTrip != null && rosterRequests > 0 }
        assertEndpoints("Garment pickup", "Bole destination")
        val activeResponse = requireNotNull(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(ActiveTripResponse::class.java).fromJson("""{"success":true,"hasActiveTrip":true,"trip":{
                "id":"running-trip","driverId":"fixture-driver","vehicleId":"active-vehicle",
                "routeId":"active-route","direction":"OUTBOUND","status":"IN_PROGRESS"
            }}"""))
        compose.runOnIdle {
            pendingActiveTrip!!.resume(Response.success(activeResponse))
            pendingActiveTrip = null
        }
        compose.waitForIdle()
        assertEndpoints("Active pickup", "Active destination")
        routeText("Garment pickup").assertDoesNotExist()
        compose.onNodeWithTag("driver_complete_route").assertExists()
    }

    private fun twoPassengers() = DriverRosterResponse(success = true, roster = listOf(
        DriverRosterPassenger(id = "first-passenger", passengerName = "First fixture passenger", status = "ASSIGNED"),
        DriverRosterPassenger(id = "second-passenger", passengerName = "Second fixture passenger", status = "PENDING")
    ))

    private fun showPopulatedRoster() {
        rosterResponse = twoPassengers()
        showDriver()
        compose.waitUntil(5_000) { rosterRequests > 0 }
        compose.onNodeWithTag("driver_nav_passengers").performClick()
        assertRosterCount(2)
        compose.onNodeWithTag("driver_roster_row_first-passenger").assertIsDisplayed()
        compose.onNodeWithTag("driver_roster_row_second-passenger").assertIsDisplayed()
    }

    private fun assertRosterCount(count: Int) {
        compose.onNodeWithTag("driver_roster_filter_all").assertTextContains(count.toString())
    }

    private fun refreshRoster() {
        val previous = rosterRequests
        compose.onNodeWithText("Refresh roster").performScrollTo().performClick()
        compose.waitUntil(5_000) { rosterRequests > previous }
    }

    @Test fun refreshingRosterKeepsVisiblePassengersUntilSuccessfulReplacement() {
        showPopulatedRoster()
        compose.runOnIdle { pauseRoster = true }
        refreshRoster()
        compose.waitUntil(5_000) { pendingRoster != null }
        assertRosterCount(2)
        compose.onNodeWithTag("driver_roster_row_first-passenger").assertIsDisplayed()
        compose.onNodeWithTag("driver_roster_row_second-passenger").assertIsDisplayed()
        compose.runOnIdle {
            pauseRoster = false
            pendingRoster!!.resume(Response.success(DriverRosterResponse(success = true, roster = listOf(
                twoPassengers().roster.first().copy(status = "BOARDED")
            ))))
            pendingRoster = null
        }
        compose.waitForIdle()
        assertRosterCount(1)
        compose.onNode(hasText("Boarded") and hasAnyAncestor(hasTestTag("driver_roster_status_first-passenger"))).assertExists()
        compose.onNodeWithTag("driver_roster_row_second-passenger").assertDoesNotExist()
    }

    @Test fun failedHttpAndLogicalRosterResponsesPreserveLastSuccessfulPassengers() {
        showPopulatedRoster()
        compose.runOnIdle { rosterUnavailable = true }
        refreshRoster()
        assertRosterCount(2)
        compose.onNodeWithTag("driver_roster_row_first-passenger").assertIsDisplayed()
        compose.runOnIdle {
            rosterUnavailable = false
            rosterResponse = DriverRosterResponse(success = false, error = "Roster temporarily unavailable")
        }
        refreshRoster()
        assertRosterCount(2)
        compose.onNodeWithTag("driver_roster_row_first-passenger").assertIsDisplayed()
        compose.onNodeWithTag("driver_roster_row_second-passenger").assertIsDisplayed()
    }

    @Test fun switchingAssignmentsClearsPreviousPassengersWhileNewRosterIsPendingThenAcceptsEmptyRoster() {
        val first = org.json.JSONObject(assignmentJson()).getJSONArray("vehicles").getJSONObject(0)
        val second = org.json.JSONObject(assignmentJson("Other pickup", "Other destination", "other-vehicle", "other-route"))
            .getJSONArray("vehicles").getJSONObject(0).put("routeName", "Another assigned service")
        response = """{"success":true,"vehicles":[$first,$second]}"""
        showPopulatedRoster()
        compose.runOnIdle { pauseRoster = true }
        compose.onNodeWithTag("driver_nav_home").performClick()
        compose.onNodeWithText("Another assigned service · TEST-4321").performScrollTo().performClick()
        compose.waitUntil(5_000) { pendingRoster != null && pendingRosterAssignment == "other-vehicle" }
        compose.onNodeWithTag("driver_nav_passengers").performClick()
        assertRosterCount(0)
        compose.onNodeWithTag("driver_roster_row_first-passenger").assertDoesNotExist()
        compose.onNodeWithTag("driver_roster_row_second-passenger").assertDoesNotExist()
        compose.runOnIdle {
            pauseRoster = false
            pendingRoster!!.resume(Response.success(DriverRosterResponse(success = true)))
            pendingRoster = null
        }
        compose.waitForIdle()
        assertRosterCount(0)
        compose.onNodeWithText("No passengers assigned yet.").assertIsDisplayed()
    }
}
