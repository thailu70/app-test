package com.example

import com.example.data.entity.SubscriptionEntity
import com.example.ui.screens.passenger.canShowPassengerBoardingPass
import com.example.ui.screens.passenger.passengerBoardingStatus
import com.example.ui.screens.passenger.subscriptionDaysLeft
import com.example.ui.screens.passenger.parseSubscriptionExpiry
import com.example.ui.screens.assignedRouteLocations
import com.example.core.localization.AppLanguage
import com.example.data.entity.RouteStopEntity
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

internal fun passengerPreviewSubscription() = SubscriptionEntity(
    id = "preview-pass", passengerId = "preview-passenger", routeId = "preview-route",
    pickupStopId = "", destinationStopId = "", morningSchedule = "06:30", eveningSchedule = "17:30",
    startDate = "2099-01-01", endDate = "2099-01-31", priceEtb = 1200.0,
    paymentStatus = "PAID", subscriptionStatus = "ACTIVE", vehicleId = "preview-vehicle",
    qrToken = "RP1:preview-only:not-a-valid-boarding-token", daysRemaining = 20
)

class PassengerPresentationTest {
    @Test fun `only eligible server subscription permits boarding`() {
        val pass = passengerPreviewSubscription()
        assertTrue(canShowPassengerBoardingPass(pass))
        assertEquals("ACTIVE", passengerBoardingStatus(pass))
        for (ineligible in listOf(null, pass.copy(daysRemaining=0), pass.copy(paymentStatus="PENDING"), pass.copy(subscriptionStatus="PENDING"), pass.copy(subscriptionStatus="CANCELLED"))) assertFalse(canShowPassengerBoardingPass(ineligible))
        assertEquals("EXPIRED", passengerBoardingStatus(pass.copy(daysRemaining=0)))
        assertEquals("PENDING", passengerBoardingStatus(pass.copy(paymentStatus="PENDING")))
    }

    @Test fun `remaining days count down from actual expiry rather than saved activation value`() {
        val pass = passengerPreviewSubscription().copy(endDate = "2026-11-18", daysRemaining = 30)
        assertEquals(30, subscriptionDaysLeft(pass, Instant.parse("2026-10-19T00:00:00Z").toEpochMilli()))
        assertEquals(29, subscriptionDaysLeft(pass, Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()))
        assertEquals(1, subscriptionDaysLeft(pass, Instant.parse("2026-11-17T23:59:59Z").toEpochMilli()))
        assertEquals(0, subscriptionDaysLeft(pass, Instant.parse("2026-11-18T00:00:00Z").toEpochMilli()))
        assertEquals(0, subscriptionDaysLeft(pass, Instant.parse("2026-11-21T03:00:00Z").toEpochMilli()))
    }

    @Test fun `renewed dates refresh countdown and missing expiry never fabricates thirty days`() {
        val now = Instant.parse("2026-10-19T21:30:00Z").toEpochMilli() // Already Oct 20 in Ethiopia.
        val pass = passengerPreviewSubscription().copy(endDate = "2026-10-20", daysRemaining = 30)
        assertEquals(1, subscriptionDaysLeft(pass, now))
        assertEquals(31, subscriptionDaysLeft(pass.copy(endDate = "2026-11-19"), now))
        assertNull(subscriptionDaysLeft(pass.copy(endDate = ""), now))
        assertNull(subscriptionDaysLeft(pass.copy(endDate = "not configured"), now))
        assertNull(subscriptionDaysLeft(null, now))
    }

    @Test fun `strict expiry parsing supports server ISO and legacy English dates independently of device timezone`() {
        val expected = Instant.parse("2026-11-18T00:00:00Z").toEpochMilli()
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Adak"))
            assertEquals(expected, parseSubscriptionExpiry("2026-11-18"))
            assertEquals(expected, parseSubscriptionExpiry("18 Nov 2026"))
            assertEquals(expected, parseSubscriptionExpiry("2026-11-18T03:00:00+03:00"))
            assertEquals(expected + 123, parseSubscriptionExpiry("2026-11-18T00:00:00.123Z"))
            assertNull(parseSubscriptionExpiry("2026-02-30"))
            assertNull(parseSubscriptionExpiry("2026-11-18T00:00:00"))
        } finally { java.util.TimeZone.setDefault(original) }
    }

    @Test fun `issued QR exact expiry prevents truncated end date from reducing displayed validity early`() {
        val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        val expiry = Instant.parse("2026-10-20T20:00:00Z").toEpochMilli()
        val pass = passengerPreviewSubscription().copy(endDate = "2026-10-20", daysRemaining = 30)
        val token = "RP1:${pass.id}:${pass.passengerId}:${pass.routeId}:$expiry:${"a".repeat(16)}"
        assertEquals(2, subscriptionDaysLeft(pass.copy(qrToken = token), now))
        assertEquals(2, subscriptionDaysLeft(pass.copy(qrToken = token.substringBeforeLast(':') + ":" + "a".repeat(64)), now))
        assertEquals(1, subscriptionDaysLeft(pass.copy(qrToken = token.replace(pass.routeId, "other-route")), now))
        assertEquals(1, subscriptionDaysLeft(pass.copy(qrToken = token.substringBeforeLast(':') + ":invalid"), now))
    }

    @Test fun `inactive and unpaid subscriptions show zero despite old activation expiry`() {
        val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        val pass = passengerPreviewSubscription().copy(endDate = "2026-11-18", daysRemaining = 30)
        for (status in listOf("PENDING", "CANCELLED", "EXPIRED"))
            assertEquals(0, subscriptionDaysLeft(pass.copy(subscriptionStatus = status), now))
        for (payment in listOf("UNPAID", "PENDING", "FAILED"))
            assertEquals(0, subscriptionDaysLeft(pass.copy(paymentStatus = payment), now))
    }

    private val routeStops = listOf(
        RouteStopEntity("terminal", "assigned", "CMC", "ሲኤምሲ", 3, 9.0, 38.8, "08:00", "17:30"),
        RouteStopEntity("personal", "assigned", "Megenagna", "መገናኛ", 2, 9.0, 38.8, "07:30", "18:00"),
        RouteStopEntity("origin", "assigned", "Bole", "ቦሌ", 1, 9.0, 38.8, "07:00", "18:30"),
        RouteStopEntity("foreign", "unrelated", "Another route pickup", "", 0, 9.0, 38.8, "06:00", "19:00")
    )

    @Test fun `personal stop IDs are authoritative and scoped to their assigned route`() {
        val locations = assignedRouteLocations("assigned", "Old title – Old destination", routeStops, AppLanguage.ENGLISH,
            pickupStopId = "personal", destinationStopId = "terminal")
        assertEquals("Megenagna", locations.departure)
        assertEquals("CMC", locations.destination)
        val unavailable = assignedRouteLocations("assigned", "Bole – CMC", routeStops, AppLanguage.ENGLISH,
            pickupStopId = "foreign", destinationStopId = "removed")
        assertNull(unavailable.departure)
        assertNull(unavailable.destination)
    }

    @Test fun `route endpoints appear without personal IDs and return journeys reverse actual stops`() {
        val outbound = assignedRouteLocations("assigned", "Old title – Old destination", routeStops, AppLanguage.ENGLISH)
        assertEquals("Bole", outbound.departure)
        assertEquals("CMC", outbound.destination)
        val inbound = assignedRouteLocations("assigned", "Old title – Old destination", routeStops, AppLanguage.AMHARIC, inbound = true)
        assertEquals("ሲኤምሲ", inbound.departure)
        assertEquals("ቦሌ", inbound.destination)
    }

    @Test fun `legacy named endpoints remain real locations without inventing defaults`() {
        val named = assignedRouteLocations("assigned", "Garment - Bole Medhanialem", emptyList(), AppLanguage.ENGLISH)
        assertEquals("Garment", named.departure)
        assertEquals("Bole Medhanialem", named.destination)
        val missing = assignedRouteLocations("assigned", "Route 102", emptyList(), AppLanguage.ENGLISH)
        assertNull(missing.departure)
        assertNull(missing.destination)
        val intercity = assignedRouteLocations(null, "Trip 102", emptyList(), AppLanguage.ENGLISH,
            origin = "Addis Ababa", destination = "Hawassa")
        assertEquals("Addis Ababa", intercity.departure)
        assertEquals("Hawassa", intercity.destination)
    }
}
