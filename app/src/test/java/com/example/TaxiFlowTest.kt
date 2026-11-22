package com.example

import com.example.data.api.*
import com.example.ui.screens.taxi.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import retrofit2.Response
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class TaxiFlowTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val route = TaxiRouteInput("Fixture pickup", "Fixture destination", 9.0, 38.7, 9.05, 38.8)
    private val ride = TaxiRide("ride-fixture", status = "REQUESTED", estimatedFareEtb = 325.0)
    private fun fake(call: (String, Array<out Any?>) -> Any): TaxiApi = Proxy.newProxyInstance(TaxiApi::class.java.classLoader, arrayOf(TaxiApi::class.java)) { _, method, args -> call(method.name, args.orEmpty()) } as TaxiApi
    private fun initial(name: String) = when (name) {
        "catalog" -> Response.success(TaxiResponse(true, settings = TaxiSettings(enabled = true)))
        "shared", "me" -> Response.success(TaxiResponse(true))
        else -> error("Unexpected API call $name")
    }
    @Test fun `anonymous discovery calls only catalog and shared and estimate uses server fare`() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val vm = TaxiViewModel(fake { name, _ -> calls += name; if (name == "estimate") Response.success(TaxiResponse(true, estimate = TaxiEstimate(8.0, 22.0, 325.0))) else initial(name) }, false, false)
        advanceUntilIdle(); assertEquals(listOf("catalog", "shared"), calls)
        vm.estimate(route); advanceUntilIdle()
        assertEquals(325.0, vm.state.value.estimate!!.estimatedFareEtb, 0.0)
        assertFalse(calls.contains("request"))
    }
    @Test fun `uncertain request retry retains key but a confirmed new booking intent gets a fresh key`() = runTest(dispatcher) {
        val keys = mutableListOf<String>(); var fail = true
        val vm = TaxiViewModel(fake { name, args ->
            if (name == "request") { keys += (args[0] as TaxiRouteInput).idempotencyKey; if (fail) { fail = false; error("Connection interrupted") }; Response.success(TaxiResponse(true, ride = ride)) }
            else initial(name)
        }, true, false)
        advanceUntilIdle(); vm.request(route); advanceUntilIdle(); assertNotNull(vm.state.value.message)
        vm.request(route); advanceUntilIdle(); assertEquals(keys[0], keys[1]); assertEquals(ride, vm.state.value.ride)
        vm.dismissRide(); vm.request(route); advanceUntilIdle(); assertNotEquals(keys[1], keys[2])
    }
    @Test fun `a rejected refresh preserves the previous driver roster and credit`() = runTest(dispatcher) {
        var broken = false
        val dashboard = TaxiResponse(true, eligible = true, online = true, calls = listOf(ride), wallet = MobilityWallet(1000.0, 20.0, 980.0))
        val vm = TaxiViewModel(fake { name, _ -> if (name == "dashboard") { if (broken) error("Offline"); Response.success(dashboard) } else initial(name) }, true, true)
        advanceUntilIdle(); broken = true; vm.refresh(); advanceUntilIdle()
        assertEquals(listOf(ride), vm.state.value.dashboard!!.calls)
        assertEquals(980.0, vm.state.value.dashboard!!.wallet.availableEtb, 0.0)
    }
    @Test fun `wire adapter preserves null fare requested group and never invents a boarding PIN`() {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val result = moshi.adapter(TaxiResponse::class.java).fromJson("""{"success":true,"ride":{"id":"r","fareEtb":null},"trip":{"id":"s","status":"REQUESTED","farePerSeatEtb":null}}""")!!
        assertNull(result.ride!!.fareEtb); assertNull(result.ride.boardingQrToken)
        assertNull(result.trip!!.farePerSeatEtb)
        assertEquals(0L, result.ride.lastSequence)
    }
    @Test fun `admin tariff change requires a new displayed estimate instead of silently accepting the new price`() = runTest(dispatcher) {
        val vm = TaxiViewModel(fake { name, _ -> when (name) {
            "estimate" -> Response.success(TaxiResponse(true, estimate = TaxiEstimate(8.0, 22.0, 325.0)))
            "request" -> Response.error<TaxiResponse>(409, """{"success":false,"code":"ESTIMATE_CHANGED","error":"The fare changed. Refresh the estimate."}""".toResponseBody("application/json".toMediaType()))
            else -> initial(name)
        } }, true, false)
        advanceUntilIdle(); vm.estimate(route); advanceUntilIdle(); vm.request(route); advanceUntilIdle()
        assertNull(vm.state.value.ride); assertNull(vm.state.value.estimate)
        assertTrue(vm.state.value.message!!.contains("Refresh"))
    }
    @Test fun `nearby cars use only real valid approximate server entries and refresh on tier selection`() = runTest(dispatcher) {
        val queries = mutableListOf<String>()
        val vm = TaxiViewModel(fake { name, args -> if (name == "nearby") { queries += args[2] as String
            Response.success(TaxiNearbyResponse(true, listOf(TaxiNearbyCar(9.0, 38.7), TaxiNearbyCar(Double.NaN, 38.7))))
        } else initial(name) }, false, false)
        advanceUntilIdle(); vm.nearby(9.0 to 38.7, "ECONOMY"); advanceUntilIdle()
        assertEquals(listOf(TaxiNearbyCar(9.0, 38.7)), vm.state.value.nearbyCars)
        vm.nearby(9.0 to 38.7, "COMFORT"); advanceUntilIdle()
        assertEquals(listOf("ECONOMY", "COMFORT"), queries)
        vm.nearby(null, "COMFORT"); assertTrue(vm.state.value.nearbyCars.isEmpty())
    }
    @Test fun `editing route while an estimate is in flight discards its old geometry and fare`() = runTest(dispatcher) {
        var continuation: Continuation<Response<TaxiResponse>>? = null
        val vm = TaxiViewModel(fake { name, args -> if (name == "estimate") {
            @Suppress("UNCHECKED_CAST") val pending = args.last() as Continuation<Response<TaxiResponse>>
            continuation = pending; COROUTINE_SUSPENDED
        } else initial(name) }, false, false)
        advanceUntilIdle(); vm.estimate(route); runCurrent(); assertTrue(vm.state.value.busy)
        vm.clearEstimate()
        continuation!!.resume(Response.success(TaxiResponse(true, estimate = TaxiEstimate(8.0, 22.0, 325.0,
            routeGeometry = MobilityRoadGeometry("LineString", listOf(listOf(38.7, 9.0), listOf(38.8, 9.05)))))))
        advanceUntilIdle(); assertNull(vm.state.value.estimate); assertFalse(vm.state.value.busy)
    }
    @Test fun `passenger stop flushes all pre stop GPS evidence before asking for approval`() = runTest(dispatcher) {
        val order = mutableListOf<String>()
        val active = ride.copy(status = "IN_PROGRESS")
        val vm = TaxiViewModel(fake { name, _ -> when (name) {
            "dashboard" -> Response.success(TaxiResponse(true, ride = active))
            "wait" -> { order += "REQUEST_STOP"; Response.success(TaxiResponse(true, ride = active.copy(stopWaitingRequested = true))) }
            else -> initial(name)
        } }, true, true, flushMeter = { id, _ -> assertEquals(ride.id, id); order += "GPS_FLUSH"; 10L })
        advanceUntilIdle(); vm.wait("REQUEST_STOP", mapOf("latitude" to 9.0)); advanceUntilIdle()
        assertEquals(listOf("GPS_FLUSH", "REQUEST_STOP"), order)
        assertTrue(vm.state.value.ride!!.stopWaitingRequested)
    }
    @Test fun `failed GPS flush cannot start or end a passenger stop`() = runTest(dispatcher) {
        var calls = 0
        val vm = TaxiViewModel(fake { name, _ -> when (name) {
            "dashboard" -> Response.success(TaxiResponse(true, ride = ride.copy(status = "IN_PROGRESS")))
            "wait" -> { calls++; Response.success(TaxiResponse(true)) }
            else -> initial(name)
        } }, true, true, flushMeter = { _, _ -> error("Reconnect to save the latest distance.") })
        advanceUntilIdle(); vm.wait("REQUEST_STOP", mapOf("latitude" to 9.0)); advanceUntilIdle()
        assertEquals(0, calls); assertFalse(vm.state.value.ride!!.stopWaitingRequested)
        assertTrue(vm.state.value.message!!.contains("Reconnect"))
    }
    @Test fun `approved stop clearly distinguishes frozen distance free allowance and billable waiting`() {
        val stopped = ride.copy(status = "IN_PROGRESS", waiting = "STOP", distancePaused = true,
            waitingState = "FREE_ALLOWANCE", waitingFreeRemainingSeconds = 30, waitingRateEtbPerMinute = 2.5)
        assertEquals("Distance paused · free waiting 00:30", taxiStopSummary(stopped))
        assertEquals("Distance paused · waiting ETB 2.5/min", taxiStopSummary(stopped.copy(waitingFreeRemainingSeconds = 0, waitingState = "BILLABLE_WAITING")))
        assertEquals("Waiting clock paused · fresh GPS needed", taxiStopSummary(stopped.copy(waitingState = "GPS_PAUSED")))
    }
}
