package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.core.location.DriverLocationService
import com.example.core.location.DriverLocationSharing
import com.example.data.api.*
import com.example.data.database.TransportDatabase
import com.example.data.entity.UserEntity
import com.example.data.repository.TransportRepository
import com.example.ui.screens.driver.DriverTripScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real emulator UI/service recording, with synthetic network responses only.
 * It neither changes the release app nor grants/replaces Android runtime permissions.
 * Run through the manual video workflow: it disables device networking and supplies
 * an emulated GNSS fix. The production stale/mock-GPS rejection remains active.
 */
@RunWith(AndroidJUnit4::class)
class DriverLocationVideoTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val originalFactory = DriverLocationService.createApis
    private val originalBaseUrl = ApiClient.getBaseUrl()
    private lateinit var database: TransportDatabase
    private lateinit var model: MainViewModel
    private val uploads = AtomicInteger()
    private val events = mutableListOf<String>()
    private var passed = false
    private val evidence by lazy { File(context.getExternalFilesDir(null), "play-location-video").apply { mkdirs() } }
    private val trip = TripDto("video-synthetic-trip", "video-synthetic-driver", "video-synthetic-vehicle",
        plateNumber = "DEMO-VIDEO", vehicleType = "MINIVAN_8", routeId = "video-synthetic-route",
        routeName = "Synthetic demonstration route", currentStop = "Demo departure", status = "IN_PROGRESS")

    @Before fun isolatedDeviceFixture() {
        check(BuildConfig.DEBUG) { "This instrumentation fixture must never run against a release installation." }
        check(!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) { "Use a fresh install so the real permission prompt is recorded." }
        check(!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) { "Use a fresh install so the real notification prompt is recorded." }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        // A stray portrait request cannot reach production, even if this fixture changes.
        ApiClient.setBaseUrl("http://127.0.0.1:9/")
        ApiClient.setAuthToken("synthetic-video-session-only")
        val backend = fixture<RoutePassApiService> { method, _ ->
            when (method) {
                "getGpsSharing" -> Response.success(GpsSharingResponse(true, 1))
                "updateMyLocation" -> { uploads.incrementAndGet(); Response.success(GenericResponse(true)) }
                else -> unavailable<Any>()
            }
        }
        val operations = fixture<DriverOperationsApi> { method, _ ->
            when (method) {
                "assignments" -> Response.success(DriverAssignmentsResponse(true, listOf(DriverVehicleAssignment(
                    trip.vehicleId, "DEMO-VIDEO", "MINIVAN_8", 8, trip.routeId,
                    "Synthetic demonstration route", "07:15", "17:30",
                    "Demo departure", destinationStopName = "Demo destination"))))
                "activeTrip" -> Response.success(ActiveTripResponse(true, true, trip))
                "departures" -> Response.success(DriverIntercityResponse(true))
                "commuteRoster", "intercityRoster" -> Response.success(DriverRosterResponse(true))
                else -> unavailable<Any>()
            }
        }
        val profile = fixture<ProfileApi> { method, _ ->
            check(method == "getMyProfile")
            Response.success(ProfileResponse(true, CompletionProfile(role = "DRIVER")))
        }
        DriverLocationService.createApis = { token ->
            check(token == "synthetic-video-session-only")
            operations to backend
        }
        compose.runOnIdle {
            val direct = java.util.concurrent.Executor { it.run() }
            database = Room.inMemoryDatabaseBuilder(context, TransportDatabase::class.java)
                .allowMainThreadQueries().setQueryExecutor(direct).setTransactionExecutor(direct).build()
            model = MainViewModel(TransportRepository(database.transportDao(), backend))
            compose.activity.viewModelStore.put("location-video-fixture", model)
            // Existing driver-screen tests use this session-only seam; it never opens a login socket.
            @Suppress("UNCHECKED_CAST")
            val user = MainViewModel::class.java.getDeclaredField("_currentUser")
                .apply { isAccessible = true }.get(model) as MutableStateFlow<UserEntity?>
            user.value = UserEntity(trip.driverId, "DRIVER", "Demo Driver", "", "")
        }
        compose.setContent {
            MyApplicationTheme {
                Column(Modifier.fillMaxSize()) {
                    Surface(color = Color(0xFFE3F3ED)) {
                        Text("Passo Mobility · Synthetic demonstration data",
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                    DriverTripScreen(model, Modifier.weight(1f), operationsApi = operations, providedProfileApi = profile)
                }
            }
        }
    }

    @Test fun realDisclosurePermissionsForegroundNotificationAndStop() {
        try {
            compose.onNodeWithTag("driver_location_sharing").performScrollTo().assertIsDisplayed()
            checkpoint("01-driver-active-trip")
            compose.onNodeWithTag("driver_location_sharing").performClick()
            compose.onNodeWithText("Share during this trip?").assertIsDisplayed()
            compose.onNodeWithTag("driver_location_disclosure").assertExists()
            checkpoint("02-location-disclosure")
            compose.onNodeWithText("Allow and share").performClick()
            acceptActualAndroidPermissionDialogs()
            waitUntil("The real foreground service did not start", 20_000) {
                DriverLocationSharing.running.value && currentNotice() != null
            }
            val notice = requireNotNull(currentNotice())
            assertTrue(notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("Stop sharing", notice.actions.single().title.toString())
            // Success requires a genuine platform GNSS fix accepted by production validation.
            waitUntil("No fresh non-mock emulator GPS fix reached the synthetic service", 30_000) {
                DriverLocationSharing.fix.value != null && uploads.get() > 0
            }
            assertEquals(8.995, DriverLocationSharing.fix.value!!.latitude, 0.001)
            assertEquals(38.788, DriverLocationSharing.fix.value!!.longitude, 0.001)
            checkpoint("05-sharing-started")
            shell("input keyevent KEYCODE_HOME")
            SystemClock.sleep(3_000)
            assertTrue("Location service stopped when minimized", DriverLocationSharing.running.value)
            assertNotNull("Ongoing notice disappeared when minimized", currentNotice())
            checkpoint("06-app-minimized-service-running")
            shell("cmd statusbar expand-notifications")
            SystemClock.sleep(2_000)
            var stop = findNode { it.text?.toString() == "Stop sharing" }
            if (stop == null) {
                findNode { it.viewIdResourceName?.endsWith(":id/expand_button") == true ||
                    it.contentDescription?.toString()?.startsWith("Expand") == true }?.let(::clickNode)
                SystemClock.sleep(1_000)
                stop = findNode { it.text?.toString() == "Stop sharing" }
            }
            assertNotNull("Stop action is not visible in the actual Android notification shade", stop)
            checkpoint("07-ongoing-notification-stop-action")
            clickNode(requireNotNull(stop))
            waitUntil("Stop did not end the real service and remove its notice", 10_000) {
                !DriverLocationSharing.running.value && currentNotice() == null && DriverLocationSharing.session == null
            }
            val countAfterStop = uploads.get()
            SystemClock.sleep(2_200)
            assertEquals("Location uploads continued after Stop", countAfterStop, uploads.get())
            checkpoint("08-stop-notification-removed")
            shell("cmd statusbar collapse")
            compose.activityRule.scenario.onActivity { activity ->
                activity.startActivity(Intent(activity, ComponentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            compose.onNodeWithTag("driver_location_sharing").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Share trip location").assertExists()
            checkpoint("09-returned-to-stopped-app")
            passed = true
        } catch (error: Throwable) {
            evidence.resolve("failure.txt").writeText(error.stackTraceToString())
            checkpoint("failure", pauseMillis = 0)
            throw error
        }
    }

    @After fun restoreFixtureAndSaveEvidence() {
        val result = JSONObject().put("passed", passed).put("appVersion", BuildConfig.VERSION_NAME)
            .put("syntheticData", true).put("releaseSourceChanged", false)
            .put("productionNetworkCalls", false).put("runtimePermissionsGrantedThroughSystemUi", passed)
            .put("freshGpsUploadsBeforeStop", uploads.get()).put("events", JSONArray(events))
        evidence.resolve("assertions.json").writeText(result.toString(2))
        DriverLocationSharing.stop()
        if (::model.isInitialized) compose.runOnIdle { compose.activity.viewModelStore.clear() }
        if (::database.isInitialized) database.close()
        DriverLocationService.createApis = originalFactory
        ApiClient.setAuthToken(null)
        ApiClient.setBaseUrl(originalBaseUrl)
    }

    private fun acceptActualAndroidPermissionDialogs() {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
            val location = findNode { it.viewIdResourceName?.endsWith(":id/permission_allow_foreground_only_button") == true ||
                it.text?.toString()?.equals("While using the app", true) == true }
            if (location != null) {
                checkpoint("03-android-location-permission", 1_500)
                clickNode(location)
            } else {
                val allow = findNode { it.viewIdResourceName?.endsWith(":id/permission_allow_button") == true ||
                    it.text?.toString()?.equals("Allow", true) == true }
                if (allow != null) { checkpoint("04-android-notification-permission", 1_500); clickNode(allow) }
            }
            SystemClock.sleep(400)
        }
        fail("Both precise location and notification permission must be granted through actual Android dialogs")
    }

    private fun hasPermission(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    private fun currentNotice() = notifications.activeNotifications.firstOrNull {
        it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "PASSO · Trip location sharing"
    }?.notification

    private fun checkpoint(name: String, pauseMillis: Long = 2_500) {
        events += "$name at ${SystemClock.elapsedRealtime()} ms"
        automation.takeScreenshot()?.useBitmap { bitmap ->
            evidence.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        evidence.resolve("$name-ui.txt").writeText(roots().joinToString("\n") { describe(it) })
        if (pauseMillis > 0) SystemClock.sleep(pauseMillis)
    }

    private fun roots(): List<AccessibilityNodeInfo> = automation.windows.mapNotNull { it.root }
        .ifEmpty { listOfNotNull(automation.rootInActiveWindow) }
    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && node.isEnabled && predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { found -> return found } }
            return null
        }
        return roots().firstNotNullOfOrNull(::walk)
    }
    private fun clickNode(node: AccessibilityNodeInfo) {
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null) {
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            candidate = candidate.parent
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        check(!bounds.isEmpty) { "System UI action has no clickable bounds" }
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }
    private fun describe(node: AccessibilityNodeInfo, depth: Int = 0): String = buildString {
        append("  ".repeat(depth)).append(node.packageName).append(' ').append(node.viewIdResourceName)
            .append(" text=").append(node.text).append(" description=").append(node.contentDescription).append('\n')
        for (i in 0 until node.childCount) node.getChild(i)?.let { append(describe(it, depth + 1)) }
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun waitUntil(message: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(150)
        assertTrue(message, condition())
    }
    private inline fun Bitmap.useBitmap(action: (Bitmap) -> Unit) { try { action(this) } finally { recycle() } }
    private fun <T> unavailable(): Response<T> = Response.error(503,
        "{\"error\":\"No external service is used by this fixture\"}".toResponseBody("application/json".toMediaType()))
    private inline fun <reified T> fixture(crossinline response: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SyntheticVideoFixture"
                else -> response(method.name, args)
            }
        } as T
}
