package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import com.example.core.localization.AppLanguage
import com.example.data.api.*
import com.example.data.database.TransportDatabase
import com.example.data.entity.RouteStopEntity
import com.example.data.entity.SubscriptionEntity
import com.example.data.entity.UserEntity
import com.example.data.repository.TransportRepository
import com.example.ui.screens.passenger.CommuterActiveTripCard
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy

/** Real status JSON → repository → ViewModel → the approved native passenger trip card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PassengerAssignmentLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: TransportDatabase
    private lateinit var model: MainViewModel
    private var requests = 0
    private var statusJson = ""
    private var serviceUnavailable = false
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(SubscriptionStatusResponse::class.java)

    private fun response(names: String = """
        ,"pickupStopName":"Garment pickup","pickupStopNameAm":"ጋርመንት መነሻ",
        "destinationStopName":"Bole destination","destinationStopNameAm":"ቦሌ መድረሻ"
    """, pickupId: String = "current-pickup", destinationId: String = "current-destination") = """
        {"success":true,"isSubscribed":true,"status":"ACTIVE","paymentStatus":"PAID","subscription":{
          "id":"pass-fixture","routeId":"assigned-route","status":"ACTIVE","paymentStatus":"PAID",
          "priceEtb":2500,"startDate":"2026-10-01","endDate":"2026-12-31","daysRemaining":20,
          "qrToken":"server-fixture-token","morningSchedule":"07:15","eveningSchedule":"17:30",
          "pickupStopId":"$pickupId","destinationStopId":"$destinationId" $names
        }}
    """.trimIndent()

    @Before fun isolatedPassenger() {
        ApiClient.setAuthToken(null)
        statusJson = response()
        compose.runOnIdle {
            val direct = java.util.concurrent.Executor { it.run() }
            database = Room.inMemoryDatabaseBuilder(compose.activity, TransportDatabase::class.java)
                .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
            val api = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader,
                arrayOf(RoutePassApiService::class.java)) { _, method, _ ->
                when (method.name) {
                    "getMySubscriptionStatus" -> {
                        requests++
                        if (serviceUnavailable) unavailable<SubscriptionStatusResponse>()
                        else Response.success(requireNotNull(adapter.fromJson(statusJson)))
                    }
                    else -> unavailable<Any>()
                }
            } as RoutePassApiService
            model = MainViewModel(TransportRepository(database.transportDao(), api))
            compose.activity.viewModelStore.put("passenger-assignment-loading", model)
            // Populate only session state; the card must not need profile HTTP or a login socket.
            @Suppress("UNCHECKED_CAST")
            val currentUser = MainViewModel::class.java.getDeclaredField("_currentUser")
                .apply { isAccessible = true }.get(model) as MutableStateFlow<UserEntity?>
            currentUser.value = UserEntity("passenger-fixture", "PASSENGER", "Fixture Passenger", "0912000000", "")
        }
    }

    private fun <T> unavailable(): Response<T> = Response.error(503,
        "{}".toResponseBody("application/json".toMediaType()))

    private fun showCard() {
        compose.setContent {
            val snapshot by model.passengerSubscriptionSnapshot.collectAsState()
            val stops by model.passengerRouteStops.collectAsState()
            val language by model.currentLanguage.collectAsState()
            MyApplicationTheme {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    CommuterActiveTripCard(snapshot.subscription, null, "Garment - Bole", stops,
                        null, false, null, language, {}, {}, {}, false,
                        stopAssignment = snapshot.stopAssignment)
                    Button(onClick = { model.refreshPassengerData() }, modifier = Modifier.testTag("fixture_refresh")) {
                        Text("Refresh pass")
                    }
                }
            }
        }
        compose.runOnIdle { model.refreshPassengerData() }
        compose.waitUntil(5_000) { model.passengerSubscriptionSnapshot.value.subscription != null }
    }

    private fun cardText(value: String) = compose.onNode(hasText(value) and
        hasAnyAncestor(hasTestTag("commuter_active_trip")))

    private fun cachedPass() = SubscriptionEntity("pass-fixture", "passenger-fixture", "assigned-route",
        "stop_atlas", "stop_merkato", "07:15", "17:30", "2026-10-01", "2026-12-31",
        2500.0, "PAID", "ACTIVE", "", "cached-old-token", 20)

    private fun seedStops(pickup: String = "Cached old pickup", destination: String = "Cached old destination") {
        runBlocking { database.transportDao().insertStops(listOf(
            RouteStopEntity("current-pickup", "assigned-route", pickup, "የቆየ መነሻ", 1, 9.03, 38.74, "07:15", "17:30"),
            RouteStopEntity("middle-stop", "assigned-route", "Middle personal stop", "መካከለኛ መነሻ", 2, 9.04, 38.75, "07:30", "17:45"),
            RouteStopEntity("current-destination", "assigned-route", destination, "የቆየ መድረሻ", 3, 9.05, 38.76, "08:00", "18:00")
        )) }
    }

    private fun refreshAndWait() {
        val previous = requests
        compose.onNodeWithTag("fixture_refresh").performClick()
        compose.waitUntil(5_000) { requests > previous && !model.passengerSubscriptionLoading.value }
    }

    @After fun closePassenger() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        database.close()
        ApiClient.setAuthToken(null)
    }

    @Test fun normalizedStatusNamesLoadWithoutStopsAndSameRouteRefreshReplacesStaleCacheInAmharic() {
        runBlocking { database.transportDao().insertSubscription(cachedPass()) }
        assertTrue(runBlocking { database.transportDao().getStopsForRouteSync("assigned-route") }.isEmpty())
        showCard()
        cardText("Garment pickup").assertIsDisplayed()
        cardText("Bole destination").assertIsDisplayed()
        val pass = requireNotNull(model.activeSubscription.value)
        assertEquals("current-pickup", pass.pickupStopId)
        assertEquals("current-destination", pass.destinationStopId)
        assertEquals("current-pickup", runBlocking {
            database.transportDao().getSubscriptionForPassengerSync("passenger-fixture")
        }?.pickupStopId)
        seedStops()
        compose.waitUntil(5_000) { model.passengerRouteStops.value.size == 3 }
        compose.runOnIdle {
            statusJson = response("""
                ,"pickupStopName":"Updated personal pickup","pickupStopNameAm":"አዲስ መነሻ",
                "destinationStopName":"Updated personal destination","destinationStopNameAm":"አዲስ መድረሻ"
            """)
        }
        refreshAndWait()
        cardText("Updated personal pickup").assertIsDisplayed()
        cardText("Updated personal destination").assertIsDisplayed()
        cardText("Garment pickup").assertDoesNotExist()
        cardText("Cached old pickup").assertDoesNotExist()
        compose.runOnIdle { model.setLanguage(AppLanguage.AMHARIC) }
        cardText("አዲስ መነሻ").assertIsDisplayed()
        cardText("አዲስ መድረሻ").assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/commuter-previews").apply { mkdirs() }.resolve("09-passenger-authoritative-stops-am.png")
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun explicitUnresolvedMultiStopAssignmentNeverGuessesFromCachedEndpointsOrRouteName() {
        seedStops()
        statusJson = response("""
            ,"pickupStopName":"","pickupStopNameAm":"",
            "destinationStopName":"","destinationStopNameAm":""
        """, "", "")
        showCard()
        compose.waitUntil(5_000) { model.passengerRouteStops.value.size == 3 }
        cardText("Pickup unavailable").assertIsDisplayed()
        cardText("Destination unavailable").assertIsDisplayed()
        cardText("Cached old pickup").assertDoesNotExist()
        cardText("Cached old destination").assertDoesNotExist()
        cardText("Garment").assertDoesNotExist()
        cardText("Bole").assertDoesNotExist()
    }

    @Test fun olderStatusWithoutNamesStillUsesExactPersonalStopAndFailureClearsSessionMetadata() {
        seedStops()
        statusJson = response(names = "", pickupId = "middle-stop")
        showCard()
        compose.waitUntil(5_000) { model.passengerRouteStops.value.size == 3 }
        cardText("Middle personal stop").assertIsDisplayed()
        cardText("Cached old pickup").assertDoesNotExist()
        cardText("Cached old destination").assertIsDisplayed()
        compose.runOnIdle { serviceUnavailable = true }
        refreshAndWait()
        assertNull(model.passengerSubscriptionSnapshot.value.subscription)
        assertNull(model.passengerSubscriptionSnapshot.value.stopAssignment)
        assertNull(model.activeSubscription.value)
        cardText("Middle personal stop").assertDoesNotExist()
    }
}
