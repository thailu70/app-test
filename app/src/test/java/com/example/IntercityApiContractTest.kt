package com.example

import com.example.data.api.IntercityBookingResponse
import com.example.data.api.IntercityCheckoutRequest
import com.example.data.api.IntercityCheckoutResponse
import com.example.data.api.IntercityDeparturesResponse
import com.example.data.api.IntercityPassengerApi
import com.example.data.api.IntercityRouteRequest
import com.example.data.api.IntercitySeatHoldRequest
import com.example.data.api.IntercityTestOtpRequest
import com.example.data.api.IntercityTestOtpResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Header

class IntercityApiContractTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val departureJson = """{
        "id":"departure-1", "origin":"Addis Ababa", "destination":"Hawassa",
        "departure_at":"2026-10-20T04:00:00.000Z", "departureAt":"2026-10-20T04:00:00.000Z",
        "company_id":"company-1", "companyId":"company-1", "route_id":"route-1",
        "fare_etb":650, "fareEtb":650, "seats":30, "status":"SCHEDULED",
        "vehicle_id":"vehicle-1", "vehicleId":"vehicle-1", "driver_id":"driver-1", "driverId":"driver-1"
    }"""

    @Test fun `public discovery parses camelCase availability without private booking data`() {
        val body = """{"success":true,"departures":[{
            "id":"departure-1","origin":"Addis Ababa","destination":"Hawassa",
            "departureAt":"2026-10-20T04:00:00.000Z","companyName":"Example Transport","companyId":"company-1",
            "vehicleType":null,"vehicleModel":null,"seats":30,"availableSeats":28,"fareEtb":650
        }]}"""
        val response = requireNotNull(moshi.adapter(IntercityDeparturesResponse::class.java).fromJson(body))
        assertTrue(response.success)
        val departure = response.departures.single()
        assertEquals(28, departure.availableSeats)
        assertEquals(650.0, departure.fareEtb, 0.0)
        assertNull(departure.vehicleModel)
    }

    @Test fun `hold receipt retains private capability but has no boarding credentials`() {
        val body = """{"success":true,"bookingSecret":"private-device-capability","booking":{
            "id":"booking-1","departureId":"departure-1","seatNumber":12,"amountEtb":650,
            "status":"HOLD","paymentStatus":"PENDING","paymentMethod":null,
            "expiresAt":"2026-10-19T11:10:00.000Z","passengerName":"","passengerPhone":"",
            "departure":$departureJson
        }}"""
        val response = requireNotNull(moshi.adapter(IntercityBookingResponse::class.java).fromJson(body))
        assertEquals("private-device-capability", response.bookingSecret)
        val booking = requireNotNull(response.booking)
        assertEquals("route-1", booking.departure.routeId)
        assertEquals("vehicle-1", booking.departure.vehicleId)
        assertEquals(12, booking.seatNumber)
        assertNull(booking.qrToken)
        assertNull(booking.ticketNumber)
        assertNull(booking.ticketDownloadUrl)
    }

    @Test fun `checkout distinguishes server confirmed test payment from provider redirect`() {
        val redirect = requireNotNull(moshi.adapter(IntercityCheckoutResponse::class.java)
            .fromJson("""{"success":true,"checkoutUrl":"https://payment.example/checkout"}"""))
        assertFalse(redirect.paid)
        assertNull(redirect.booking)
        assertEquals("https://payment.example/checkout", redirect.checkoutUrl)
        val settled = requireNotNull(moshi.adapter(IntercityCheckoutResponse::class.java)
            .fromJson("""{"success":true,"paid":true,"paymentMode":"TEST_AUTO"}"""))
        assertTrue(settled.paid)
        assertEquals("TEST_AUTO", settled.paymentMode)
    }

    @Test fun `settled booking parses server issued QR ticket and PDF capability`() {
        val body = """{"success":true,"booking":{
            "id":"booking-1","departureId":"departure-1","seatNumber":12,"amountEtb":650,
            "status":"CONFIRMED","paymentStatus":"PAID","paymentMethod":"TELEBIRR",
            "expiresAt":"2026-10-19T13:05:00.000Z","passengerName":"Test Passenger","passengerPhone":"0912000000",
            "ticketNumber":"RP-AB12CD34","qrToken":"signed-server-token","qrImage":"data:image/png;base64,AA==",
            "ticketDownloadUrl":"/api/intercity/tickets/booking-1.pdf?token=private-download-capability",
            "departure":$departureJson
        }}"""
        val booking = requireNotNull(moshi.adapter(IntercityBookingResponse::class.java).fromJson(body)?.booking)
        assertEquals("PAID", booking.paymentStatus)
        assertEquals("RP-AB12CD34", booking.ticketNumber)
        assertEquals("signed-server-token", booking.qrToken)
        assertTrue(requireNotNull(booking.ticketDownloadUrl).contains("?token="))
    }

    @Test fun `native requests retain server field names and no client payment override`() {
        assertEquals("""{"seatNumber":12}""",
            moshi.adapter(IntercitySeatHoldRequest::class.java).toJson(IntercitySeatHoldRequest(12)))
        val checkout = moshi.adapter(IntercityCheckoutRequest::class.java)
            .toJson(IntercityCheckoutRequest("Test Passenger", "0912469923"))
        assertEquals("""{"passengerName":"Test Passenger","passengerPhone":"0912469923"}""", checkout)
        assertFalse(checkout.contains("paid"))
        val request = moshi.adapter(IntercityRouteRequest::class.java).toJson(
            IntercityRouteRequest("Addis Ababa", "Hawassa", "2026-10-20", "company-1", "Test Passenger", "0912000000"))
        assertTrue(request.contains("\"companyId\":\"company-1\""))
        assertTrue(request.contains("\"date\":\"2026-10-20\""))
    }

    @Test fun `private booking checkout and PDF use booking secret header rather than query`() {
        for (name in listOf("booking", "checkout", "ticketPdf", "requestTestPaymentOtp", "verifyTestPaymentOtp")) {
            val method = IntercityPassengerApi::class.java.declaredMethods.single { it.name == name }
            val headers = method.parameterAnnotations.flatMap { it.toList() }.filterIsInstance<Header>()
            assertEquals(listOf("X-Booking-Secret"), headers.map { it.value })
        }
    }

    @Test fun `OTP contracts carry booking-bound private headers and a one-use proof only in checkout body`() {
        val request = moshi.adapter(IntercityTestOtpRequest::class.java)
        assertEquals("""{"passengerPhone":"0912469923"}""", request.toJson(IntercityTestOtpRequest("0912469923")))
        assertEquals("""{"passengerPhone":"0912469923","code":"123456"}""", request.toJson(IntercityTestOtpRequest("0912469923", "123456")))
        val sent = moshi.adapter(IntercityTestOtpResponse::class.java).fromJson("""{"success":true,"delivery":"SENT","retryAfterSeconds":60}""")!!
        assertEquals("SENT", sent.delivery)
        assertNull(sent.testPaymentVerificationToken)
        val verified = moshi.adapter(IntercityTestOtpResponse::class.java).fromJson("""{"success":true,"testPaymentVerificationToken":"one-use-proof","expiresInSeconds":120}""")!!
        val checkout = org.json.JSONObject(moshi.adapter(IntercityCheckoutRequest::class.java).toJson(IntercityCheckoutRequest("Test Passenger", "0912469923", verified.testPaymentVerificationToken)))
        assertEquals("one-use-proof", checkout.getString("testPaymentVerificationToken"))
        assertFalse(checkout.has("code"))
        assertFalse(checkout.has("paid"))
        val methods = IntercityPassengerApi::class.java.declaredMethods
        assertEquals("api/intercity/bookings/{id}/test-payment/request-otp", methods.single { it.name == "requestTestPaymentOtp" }.getAnnotation(retrofit2.http.POST::class.java)!!.value)
        assertEquals("api/intercity/bookings/{id}/test-payment/verify-otp", methods.single { it.name == "verifyTestPaymentOtp" }.getAnnotation(retrofit2.http.POST::class.java)!!.value)
    }
}
