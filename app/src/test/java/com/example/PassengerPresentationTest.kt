package com.example

import com.example.data.entity.SubscriptionEntity
import com.example.ui.screens.passenger.canShowPassengerBoardingPass
import com.example.ui.screens.passenger.passengerBoardingStatus
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
}
