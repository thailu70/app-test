package com.example

import com.example.data.api.RegisterRequest
import com.example.data.api.RouteDto
import com.example.data.api.SubscribeRequest
import com.example.data.api.TelebirrPayRequest
import com.example.data.entity.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class CommuterPricingTest {
    private val route = RouteEntity("route", "Bole - CMC", "", "", "06:30", "17:30", 12.0, 2500.0)
    private val subscription = SubscriptionEntity("sub", "passenger", "route", "", "", "06:30", "17:30", "", "", 4500.0,
        "UNPAID", "PENDING", "vehicle", "", 0, serviceClass = "DELUXE")

    @Test fun `explicit assignment fares distinguish unavailable classes from legacy Economy`() {
        assertEquals(2500.0, route.monthlyFareForClass("Economy"))
        assertNull(route.monthlyFareForClass("VIP"))
        assertNull(route.monthlyFareForClass("unsupported-class"))
        val configured = route.copy(serviceFaresConfigured = true, deluxeMonthlyFareEtb = 4600.0, vipMonthlyFareEtb = 6800.0)
        assertNull("An explicit unavailable Economy must not recover the old route price", configured.monthlyFareForClass("Economy"))
        assertEquals(4600.0, configured.monthlyFareForClass("Deluxe"))
        assertEquals(6800.0, configured.monthlyFareForClass("VIP"))
        assertEquals(4600.0, configured.lowestMonthlyFare())
        assertNull(route.copy(serviceFaresConfigured = true).lowestMonthlyFare())
    }

    @Test fun `checkout uses assigned passenger offer and renewal rather than route minimum`() {
        assertEquals(4500.0, commuterCheckoutFare(subscription, route))
        assertEquals(4800.0, commuterCheckoutFare(subscription.copy(renewalPriceEtb = 4800.0), route))
        assertNull(commuterCheckoutFare(subscription.copy(renewalPriceEtb = 0.0), route))
        assertNull(commuterCheckoutFare(subscription, route.copy(id = "unrelated-route")))
        val configured = route.copy(serviceFaresConfigured = true, economyMonthlyFareEtb = 2500.0, deluxeMonthlyFareEtb = 4600.0)
        assertEquals(4600.0, commuterCheckoutFare(subscription.copy(priceEtb = 0.0), configured))
        assertNull(commuterCheckoutFare(subscription.copy(priceEtb = 0.0, serviceClass = "VIP"), configured))
    }

    @Test fun `service fares and selected classes survive the public API contract`() {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val routeDto = moshi.adapter(RouteDto::class.java).fromJson(
            """{"id":"route","name":"Bole - CMC","nameAm":"","basePriceEtb":0,"serviceFares":{"ECONOMY":null,"DELUXE":4600,"VIP":6800}}"""
        )!!
        assertNull(routeDto.serviceFares!!["ECONOMY"])
        assertEquals(4600.0, routeDto.serviceFares!!["DELUXE"])
        assertEquals(6800.0, routeDto.serviceFares!!["VIP"])
        val tier = commuterServiceClass("Deluxe")
        val registration = RegisterRequest("Passenger", "0912000000", password = "test-password", role = "PASSENGER", serviceClass = tier)
        assertTrue(moshi.adapter(RegisterRequest::class.java).toJson(registration).contains("\"serviceClass\":\"DELUXE\""))
        assertTrue(moshi.adapter(SubscribeRequest::class.java).toJson(SubscribeRequest("route", serviceClass = tier)).contains("\"serviceClass\":\"DELUXE\""))
        assertTrue(moshi.adapter(TelebirrPayRequest::class.java).toJson(TelebirrPayRequest("route", serviceClass = tier)).contains("\"serviceClass\":\"DELUXE\""))
    }
}
