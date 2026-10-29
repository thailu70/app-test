package com.example

import androidx.lifecycle.viewModelScope
import com.example.data.api.ApiClient
import com.example.data.api.RoutePassApiService
import com.example.data.dao.TransportDao
import com.example.data.entity.UserEntity
import com.example.data.repository.TransportRepository
import com.example.core.environment.AppEnvironment
import com.example.core.qr.QrSecurityEngine
import com.example.core.qr.QrValidationResult
import com.example.core.payment.TelebirrGateway
import com.example.core.payment.TelebirrPaymentResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentTransitionTest {
    @Test fun `legacy QR and checkout helpers keep repository scope and cannot follow a later environment`() = runBlocking {
        val originalBase = ApiClient.getBaseUrl()
        ApiClient.setBaseUrl(ApiClient.DEFAULT_HTTPS_URL)
        var scans = 0
        var checkouts = 0
        val dao = Proxy.newProxyInstance(TransportDao::class.java.classLoader, arrayOf(TransportDao::class.java)) { _, method, _ ->
            if (Flow::class.java.isAssignableFrom(method.returnType)) flowOf(emptyList<Any>()) else Unit
        } as TransportDao
        val api = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader, arrayOf(RoutePassApiService::class.java)) { _, method, _ ->
            when (method.name) {
                "scanCheckin" -> scans++
                "payTelebirr" -> checkouts++
                else -> error("Unexpected fixture API call: ${method.name}")
            }
            Response.error<Any>(503, "{}".toResponseBody("application/json".toMediaType()))
        } as RoutePassApiService
        val repository = TransportRepository(dao, api)
        suspend fun exerciseHelpers() {
            assertTrue(QrSecurityEngine.validateToken("synthetic-qr", "trip", "route", "vehicle", "stop", "driver", repository) is QrValidationResult.Invalid)
            assertTrue(TelebirrGateway.processSubscriptionPayment("passenger", "route", "origin", "destination", "07:00", "17:00", 500.0, "0900000101", "vehicle", repository = repository) is TelebirrPaymentResult.Failed)
        }
        try {
            exerciseHelpers()
            assertEquals(1, scans)
            assertEquals(1, checkouts)
            ApiClient.setBaseUrl(AppEnvironment.DEMO_BASE_URL)
            exerciseHelpers()
            ApiClient.setBaseUrl(ApiClient.DEFAULT_HTTPS_URL)
            exerciseHelpers()
            // A same-origin return still has a new generation. Neither helper
            // may obtain a replacement global API or submit this old QR/phone.
            assertEquals(1, scans)
            assertEquals(1, checkouts)
        } finally { ApiClient.setAuthToken(null); ApiClient.setBaseUrl(originalBase) }
    }

    @Test fun `environment switch cancels active session work without deleting production cache or guest history`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var cacheClears = 0
        val dao = Proxy.newProxyInstance(TransportDao::class.java.classLoader, arrayOf(TransportDao::class.java)) { _, method, _ ->
            if (method.name == "clearPrivateCache") cacheClears++
            if (Flow::class.java.isAssignableFrom(method.returnType)) flowOf(emptyList<Any>()) else Unit
        } as TransportDao
        val api = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader, arrayOf(RoutePassApiService::class.java)) { _, _, _ ->
            throw IllegalStateException("No external requests in fixture")
        } as RoutePassApiService
        val model = MainViewModel(TransportRepository(dao, api))
        try {
            runCurrent()
            @Suppress("UNCHECKED_CAST")
            val user = MainViewModel::class.java.getDeclaredField("_currentUser").apply { isAccessible = true }.get(model) as MutableStateFlow<UserEntity?>
            @Suppress("UNCHECKED_CAST")
            val authenticated = MainViewModel::class.java.getDeclaredField("_isAuthenticated").apply { isAccessible = true }.get(model) as MutableStateFlow<Boolean>
            user.value = UserEntity("fixture-driver", "DRIVER", "Fixture", "0900000000", "")
            authenticated.value = true
            ApiClient.setAuthToken("fixture-only-live-token")
            var cancelled = false
            val work = model.viewModelScope.launch { try { awaitCancellation() } finally { cancelled = true } }
            runCurrent()
            model.prepareForEnvironmentChange()
            runCurrent()
            assertTrue(work.isCancelled)
            assertTrue(cancelled)
            assertNull(model.currentUser.value)
            assertFalse(model.isAuthenticated.value)
            assertNull(ApiClient.getAuthToken())
            assertEquals(0, cacheClears)
        } finally { model.viewModelScope.cancel(); ApiClient.setAuthToken(null); Dispatchers.resetMain() }
    }
}
