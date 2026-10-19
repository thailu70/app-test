package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/** Public discovery contains availability only; passenger details require a booking capability. */
@JsonClass(generateAdapter = true)
data class IntercityDeparture(
    val id: String,
    val origin: String,
    val destination: String,
    val departureAt: String,
    val companyName: String,
    val companyId: String,
    val vehicleType: String? = null,
    val vehicleModel: String? = null,
    val seats: Int,
    val availableSeats: Int,
    val fareEtb: Double
)

/** The booking endpoint emits camelCase aliases alongside its database fields. */
@JsonClass(generateAdapter = true)
data class IntercityDepartureDetail(
    val id: String,
    val origin: String,
    val destination: String,
    val departureAt: String,
    val companyId: String,
    val fareEtb: Double,
    val seats: Int,
    val status: String,
    val vehicleId: String? = null,
    val driverId: String? = null,
    @Json(name = "route_id") val routeId: String? = null
)

@JsonClass(generateAdapter = true)
data class IntercityBooking(
    val id: String,
    val departureId: String,
    val seatNumber: Int,
    val amountEtb: Double,
    val status: String,
    val paymentStatus: String,
    val paymentMethod: String? = null,
    val expiresAt: String,
    val passengerName: String? = null,
    val passengerPhone: String? = null,
    // Issued by the server only after payment is settled. Never fabricate ticket credentials.
    val ticketNumber: String? = null,
    val qrToken: String? = null,
    val qrImage: String? = null,
    val ticketDownloadUrl: String? = null,
    val departure: IntercityDepartureDetail
)

@JsonClass(generateAdapter = true)
data class IntercityDeparturesResponse(
    val success: Boolean,
    val departures: List<IntercityDeparture> = emptyList(),
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class IntercitySeatsResponse(
    val success: Boolean,
    val seats: Int = 0,
    val reserved: List<Int> = emptyList(),
    val fareEtb: Double = 0.0,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class IntercityBookingResponse(
    val success: Boolean,
    val booking: IntercityBooking? = null,
    // Returned once when holding a seat; required for all subsequent private booking operations.
    val bookingSecret: String? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class IntercityCheckoutResponse(
    val success: Boolean,
    val paid: Boolean = false,
    val checkoutUrl: String? = null,
    val paymentMode: String? = null,
    val booking: IntercityBooking? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class IntercityCompany(val id: String, val name: String)

@JsonClass(generateAdapter = true)
data class IntercityCompaniesResponse(
    val success: Boolean,
    val companies: List<IntercityCompany> = emptyList(),
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class IntercitySeatHoldRequest(val seatNumber: Int)

@JsonClass(generateAdapter = true)
data class IntercityCheckoutRequest(val passengerName: String, val passengerPhone: String)

@JsonClass(generateAdapter = true)
data class IntercityRouteRequest(
    val origin: String,
    val destination: String,
    /** ISO calendar date interpreted in the operating timezone, Africa/Addis_Ababa. */
    val date: String,
    val companyId: String,
    val passengerName: String,
    val passengerPhone: String
)

@JsonClass(generateAdapter = true)
data class IntercityRouteRequestResponse(
    val success: Boolean,
    val id: String? = null,
    val message: String? = null,
    val error: String? = null
)

interface IntercityPassengerApi {
    @GET("api/intercity/departures")
    suspend fun departures(
        @Query("origin") origin: String? = null,
        @Query("destination") destination: String? = null,
        @Query("date") date: String? = null
    ): Response<IntercityDeparturesResponse>

    @GET("api/intercity/departures/{id}/seats")
    suspend fun seats(@Path("id") id: String): Response<IntercitySeatsResponse>

    @POST("api/intercity/departures/{id}/hold")
    suspend fun hold(
        @Path("id") id: String,
        @Body body: IntercitySeatHoldRequest
    ): Response<IntercityBookingResponse>

    /** Also reconciles pending Telebirr payments; ticket data is returned only for settled payments. */
    @GET("api/intercity/bookings/{id}")
    suspend fun booking(
        @Path("id") id: String,
        @Header("X-Booking-Secret") bookingSecret: String
    ): Response<IntercityBookingResponse>

    /** The backend exclusively decides test-phone confirmation or a real Telebirr checkout. */
    @POST("api/intercity/bookings/{id}/checkout")
    suspend fun checkout(
        @Path("id") id: String,
        @Header("X-Booking-Secret") bookingSecret: String,
        @Body body: IntercityCheckoutRequest
    ): Response<IntercityCheckoutResponse>

    @Streaming
    @GET("api/intercity/bookings/{id}/ticket.pdf")
    suspend fun ticketPdf(
        @Path("id") id: String,
        @Header("X-Booking-Secret") secret: String
    ): Response<ResponseBody>

    @GET("api/companies")
    suspend fun companies(): Response<IntercityCompaniesResponse>

    @POST("api/intercity/requests")
    suspend fun requestRoute(@Body body: IntercityRouteRequest): Response<IntercityRouteRequestResponse>
}
