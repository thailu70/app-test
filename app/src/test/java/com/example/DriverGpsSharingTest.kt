package com.example

import androidx.lifecycle.viewModelScope
import com.example.core.location.DriverGpsUploadGate
import com.example.data.api.GenericResponse
import com.example.data.api.GpsSharingResponse
import com.example.data.api.RoutePassApiService
import com.example.data.dao.TransportDao
import com.example.data.entity.UserEntity
import com.example.data.repository.TransportRepository
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DriverGpsSharingTest {
    @Test fun `duplicate provider fixes and long uploads share one bounded upload stream`() {
        var clock = 0L
        val gate = DriverGpsUploadGate { clock }
        val first = gate.tryBeginUpload()!!
        assertNull(gate.tryBeginUpload())
        clock = 60_000L
        assertNull(gate.tryBeginUpload())
        gate.finishUpload(first)
        val second = gate.tryBeginUpload()!!
        gate.finishUpload(second)
        clock += 4_999L
        assertNull(gate.tryBeginUpload())
        clock++
        assertNotNull(gate.tryBeginUpload())
    }

    @Test fun `new intervals apply immediately and invalid configuration keeps the last good interval`() {
        var clock = 0L
        val gate = DriverGpsUploadGate { clock }
        gate.finishUpload(gate.tryBeginUpload()!!)
        assertTrue(gate.updateInterval(60))
        clock = 59_999L
        assertNull(gate.tryBeginUpload())
        assertTrue(gate.updateInterval(1))
        val claim = gate.tryBeginUpload()!!
        gate.finishUpload(claim)
        for (invalid in listOf(null, 0, -1, 61, Int.MAX_VALUE)) {
            assertFalse(gate.updateInterval(invalid))
            assertEquals(1, gate.intervalSeconds)
        }
        clock += 999L
        assertNull(gate.tryBeginUpload())
        clock++
        assertNotNull(gate.tryBeginUpload())
    }

    @Test fun `late completion from a previous session cannot release a new upload`() {
        val gate = DriverGpsUploadGate { 0L }
        val old = gate.tryBeginUpload()!!
        gate.reset()
        val current = gate.tryBeginUpload()!!
        gate.finishUpload(old)
        assertNull(gate.tryBeginUpload())
        gate.finishUpload(current)
        assertEquals(5, gate.intervalSeconds)
    }

    private class Fixture {
        var clock = 0L
        val gate = DriverGpsUploadGate { clock }
        var setting: Response<GpsSharingResponse> = Response.success(GpsSharingResponse(true, 5))
        var settingsFailure: Exception? = null
        var uploads = 0
        var pending: Continuation<Response<GenericResponse>>? = null
        val dao = Proxy.newProxyInstance(TransportDao::class.java.classLoader, arrayOf(TransportDao::class.java)) { _, method, _ ->
            if (Flow::class.java.isAssignableFrom(method.returnType)) flowOf(emptyList<Any>()) else Unit
        } as TransportDao
        val api = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader, arrayOf(RoutePassApiService::class.java)) { _, method, args ->
            when (method.name) {
                "getGpsSharing" -> { settingsFailure?.let { throw it }; setting }
                "updateMyLocation" -> {
                    uploads++
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<Response<GenericResponse>>
                    pending = continuation
                    COROUTINE_SUSPENDED
                }
                else -> throw IllegalStateException("Initial synchronization unavailable in fixture")
            }
        } as RoutePassApiService
        val repository = TransportRepository(dao, api)
        val model = MainViewModel(repository, gate)
        fun driver(role: String = "DRIVER") {
            @Suppress("UNCHECKED_CAST")
            val user = MainViewModel::class.java.getDeclaredField("_currentUser").apply { isAccessible = true }
                .get(model) as MutableStateFlow<UserEntity?>
            user.value = UserEntity("driver-test", role, "Fixture driver", "0912000000", "")
        }
        fun complete() { pending!!.resume(Response.success(GenericResponse(success = true))); pending = null }
    }

    @Test fun `real ViewModel suppresses concurrent provider uploads while configuration refresh preserves last good settings`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            fixture.driver()
            runCurrent()
            fixture.setting = Response.success(GpsSharingResponse(true, 60))
            fixture.model.refreshDriverGpsSharingInterval()
            assertEquals(60, fixture.gate.intervalSeconds)
            fixture.settingsFailure = IllegalStateException("offline")
            fixture.model.refreshDriverGpsSharingInterval()
            assertEquals(60, fixture.gate.intervalSeconds)
            fixture.settingsFailure = null
            fixture.setting = Response.success(GpsSharingResponse(true, 0))
            fixture.model.refreshDriverGpsSharingInterval()
            assertEquals(60, fixture.gate.intervalSeconds)
            fixture.setting = Response.success(GpsSharingResponse(true, 1))
            fixture.model.refreshDriverGpsSharingInterval()
            assertEquals(1, fixture.gate.intervalSeconds)
            assertTrue(fixture.model.submitDriverLocation(9.0, 38.0))
            assertFalse(fixture.model.submitDriverLocation(9.1, 38.1))
            runCurrent()
            assertEquals(1, fixture.uploads)
            fixture.clock = 60_000
            assertFalse(fixture.model.submitDriverLocation(9.2, 38.2))
            fixture.complete(); runCurrent()
            assertTrue(fixture.model.submitDriverLocation(9.3, 38.3))
            runCurrent()
            assertEquals(2, fixture.uploads)
            fixture.complete(); runCurrent()
        } finally { fixture.model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun `logout rejects new GPS uploads and suppresses late success callbacks`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture()
        try {
            fixture.driver()
            runCurrent()
            var callbacks = 0
            assertTrue(fixture.model.submitDriverLocation(9.0, 38.0, onResult = { _, _ -> callbacks++ }))
            runCurrent()
            fixture.model.logout()
            assertFalse(fixture.model.submitDriverLocation(9.1, 38.1))
            fixture.complete(); runCurrent()
            assertEquals(0, callbacks)
            fixture.driver("PASSENGER")
            assertFalse(fixture.model.submitDriverLocation(9.2, 38.2))
        } finally { fixture.model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
