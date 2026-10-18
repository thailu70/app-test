package com.example

import com.example.data.api.RoutePassApiService
import com.example.data.api.SubscriptionDto
import com.example.data.api.SubscriptionStatusResponse
import com.example.data.dao.TransportDao
import com.example.data.entity.SubscriptionEntity
import com.example.data.repository.TransportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy

class PassengerSubscriptionRepositoryTest {
    private class Fixture {
        var response: Response<SubscriptionStatusResponse> = Response.success(SubscriptionStatusResponse(success = true))
        var failure: Exception? = null
        var replacedFor: String? = null
        var cached: SubscriptionEntity? = null
        @Suppress("UNCHECKED_CAST")
        val dao = Proxy.newProxyInstance(TransportDao::class.java.classLoader, arrayOf(TransportDao::class.java)) { _, method, args ->
            when {
                method.name == "replacePassengerSubscription" -> {
                    replacedFor = args!![0] as String
                    cached = args[1] as SubscriptionEntity?
                    Unit
                }
                Flow::class.java.isAssignableFrom(method.returnType) -> flowOf(emptyList<Any>())
                else -> error("Unexpected cache call: ${method.name}")
            }
        } as TransportDao
        val api = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader, arrayOf(RoutePassApiService::class.java)) { _, method, _ ->
            check(method.name == "getMySubscriptionStatus")
            failure?.let { throw it }
            response
        } as RoutePassApiService
        val repository = TransportRepository(dao, api)
    }

    @Test fun successfulEmptyResponseClearsOnlyThisPassengersCachedPass() = runTest {
        val fixture = Fixture()
        assertNull(fixture.repository.refreshSubscriptionFromBackend("passenger-a"))
        assertEquals("passenger-a", fixture.replacedFor)
        assertNull(fixture.cached)
    }

    @Test fun connectionFailureIsReportedWithoutInventingOrReplacingAPass() = runTest {
        val fixture = Fixture()
        fixture.failure = IllegalStateException("offline")
        try {
            fixture.repository.refreshSubscriptionFromBackend("passenger-a")
            fail("A failed server request must not look like a successful cached subscription")
        } catch (expected: IllegalStateException) {
            assertEquals("offline", expected.message)
        }
        assertNull(fixture.replacedFor)
    }

    @Test fun actualServerFieldsAreUsedAndInactiveQrIsHidden() = runTest {
        val fixture = Fixture()
        fixture.response = Response.success(SubscriptionStatusResponse(success = true, isSubscribed = false,
            subscription = SubscriptionDto(id = "server-pass", routeId = "real-route", status = "PENDING", paymentStatus = "UNPAID",
                priceEtb = 1200.0, pickupStopId = "real-pickup", destinationStopId = "real-destination", vehicleId = "real-vehicle",
                qrToken = "must-not-display", morningSchedule = "07:20", eveningSchedule = "18:10", daysRemaining = 0)))
        val subscription = fixture.repository.refreshSubscriptionFromBackend("passenger-b")!!
        assertEquals("passenger-b", subscription.passengerId)
        assertEquals("real-pickup", subscription.pickupStopId)
        assertEquals("real-destination", subscription.destinationStopId)
        assertEquals("real-vehicle", subscription.vehicleId)
        assertEquals("07:20", subscription.morningSchedule)
        assertEquals("", subscription.qrToken)
        assertEquals(subscription, fixture.cached)
    }
}
