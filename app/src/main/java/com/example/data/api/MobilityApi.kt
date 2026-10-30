package com.example.data.api

import com.squareup.moshi.JsonClass
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class MobilityTrip(val id: String, val origin: String, val destination: String,
    val departureAt: String, val fareEtb: Double, val seats: Int, val availableSeats: Int = 0,
    val vehicleId: String = "", val vehicleType: String = "", val vehicleModel: String = "",
    val vehiclePlate: String = "", val driverName: String = "", val status: String = "", val reviewNote: String = "")
@JsonClass(generateAdapter = true)
data class MobilityTraveler(val id: String = "", val name: String, val phone: String, val seatNumber: Int,
    val ticketNumber: String? = null, val boardingPassUrl: String? = null, val qrToken: String? = null,
    val status: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityBooking(val id: String, val tripId: String, val buyerName: String, val buyerPhone: String,
    val status: String, val paymentStatus: String, val amountEtb: Double, val expiresAt: String = "",
    val travelers: List<MobilityTraveler> = emptyList(), val trip: MobilityTrip)
@JsonClass(generateAdapter = true)
data class MobilityVehicleType(val id: String, val name: String, val capacity: Int = 0)
@JsonClass(generateAdapter = true)
data class MobilityVehicle(val id: String, val vehicleType: String = "", val plateNumber: String = "",
    val model: String = "", val capacityLimit: Int = 0)
@JsonClass(generateAdapter = true)
data class MobilityRequest(val id: String, val kind: String, val origin: String, val destination: String,
    val status: String, val travelDate: String = "", val people: Int? = null, val vehicleType: String = "",
    val cargoDetails: String? = null, val customerName: String = "", val customerPhone: String = "",
    val driverId: String? = null, val vehicleId: String? = null, val driverName: String? = null,
    val driverPhone: String? = null, val vehiclePlate: String? = null, val quoteEtb: Double? = null,
    val photoUrl: String? = null, val originLatitude: Double? = null, val originLongitude: Double? = null,
    val destinationLatitude: Double? = null, val destinationLongitude: Double? = null, val reviewNote: String = "",
    val travelTime: String = "", val estimatedWeightKg: Double? = null, val routeDistanceKm: Double? = null,
    val quotesCount: Int = 0, val commissionEtb: Double? = null, val review: MobilityReview? = null)
@JsonClass(generateAdapter = true)
data class MobilityTripsResponse(val success: Boolean, val trips: List<MobilityTrip> = emptyList(), val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilitySeatsResponse(val success: Boolean, val trip: MobilityTrip? = null,
    val occupiedSeats: List<Int> = emptyList(), val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityBookingResponse(val success: Boolean, val booking: MobilityBooking? = null,
    val bookingSecret: String? = null, val checkoutUrl: String? = null, val paid: Boolean = false, val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityCatalogResponse(val success: Boolean, val vehicleTypes: List<MobilityVehicleType> = emptyList(), val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityRequestResponse(val success: Boolean, val request: MobilityRequest? = null,
    val requestSecret: String? = null, val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityBookingInput(val buyerName: String, val buyerPhone: String, val adultConfirmed: Boolean,
    val travelers: List<MobilityTraveler>)
@JsonClass(generateAdapter = true)
data class MobilityRequestInput(val kind: String, val origin: String, val destination: String, val travelDate: String,
    val people: Int? = null, val vehicleType: String, val cargoDetails: String? = null,
    val customerName: String, val customerPhone: String, val adultConfirmed: Boolean,
    val originLatitude: Double? = null, val originLongitude: Double? = null,
    val destinationLatitude: Double? = null, val destinationLongitude: Double? = null,
    val estimatedWeightKg: Double? = null, val travelTime: String = "")
@JsonClass(generateAdapter = true)
data class MobilityTripInput(val vehicleId: String, val origin: String, val destination: String,
    val departureAt: String, val seats: Int, val fareEtb: Double, val latitude: Double, val longitude: Double)
@JsonClass(generateAdapter = true)
data class MobilityDriverDashboard(val success: Boolean, val serviceType: String = "DAILY_COMMUTE",
    val vehicles: List<MobilityVehicle> = emptyList(), val trips: List<MobilityTrip> = emptyList(),
    val requests: List<MobilityRequest> = emptyList(), val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityTripResponse(val success: Boolean, val trip: MobilityTrip? = null, val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityRosterResponse(val success: Boolean, val trip: MobilityTrip? = null,
    val travelers: List<MobilityTraveler> = emptyList(), val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityBoardingInput(val latitude: Double, val longitude: Double, val accuracyMeters: Float,
    val timestamp: String, val ticketNumber: String? = null, val qrToken: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityActionResponse(val success: Boolean, val message: String? = null, val error: String? = null)

@JsonClass(generateAdapter = true)
data class MobilityQuote(val id: String, val driverId: String = "", val driverName: String = "Driver",
    val vehicleType: String = "", val vehicleModel: String = "", val vehiclePlate: String = "",
    val priceEtb: Double, val note: String = "", val etaMinutes: Int? = null,
    val ratingAverage: Double? = null, val ratingCount: Int = 0, val status: String = "OPEN")
@JsonClass(generateAdapter = true)
data class MobilityQuotesResponse(val success: Boolean, val quotes: List<MobilityQuote> = emptyList(), val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityWallet(val balanceEtb: Double = 0.0, val heldEtb: Double = 0.0, val availableEtb: Double = 0.0)
@JsonClass(generateAdapter = true)
data class MobilityOpportunity(val id: String, val kind: String, val origin: String, val destination: String,
    val travelDate: String = "", val travelTime: String = "", val people: Int? = null, val vehicleType: String = "",
    val details: String? = null, val estimatedWeightKg: Double? = null, val routeDistanceKm: Double? = null,
    val pickupDistanceKm: Double? = null, val estimatedCargoPriceEtb: Double? = null, val myQuote: MobilityQuote? = null,
    val nearby: Boolean = false)
@JsonClass(generateAdapter = true)
data class MobilityOpportunitiesResponse(val success: Boolean, val requests: List<MobilityOpportunity> = emptyList(),
    val wallet: MobilityWallet = MobilityWallet(), val busy: Boolean = false, val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityQuoteInput(val vehicleId: String, val priceEtb: Double, val note: String, val etaMinutes: Int?)
@JsonClass(generateAdapter = true)
data class MobilityQuoteResponse(val success: Boolean, val quote: MobilityQuote? = null, val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilitySelectInput(val quoteId: String)
@JsonClass(generateAdapter = true)
data class MobilityProgressInput(val action: String)
@JsonClass(generateAdapter = true)
data class MobilityConfirmInput(val action: String, val note: String = "")
@JsonClass(generateAdapter = true)
data class MobilityLocation(val latitude: Double, val longitude: Double, val timestamp: String = "", val accuracyMeters: Double? = null)
@JsonClass(generateAdapter = true)
data class MobilityLocationResponse(val success: Boolean, val location: MobilityLocation? = null, val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityReview(val rating: Int, val comment: String = "")
@JsonClass(generateAdapter = true)
data class MobilityReviewResponse(val success: Boolean, val review: MobilityReview? = null, val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityPlace(val name: String, val latitude: Double, val longitude: Double)
@JsonClass(generateAdapter = true)
data class MobilityPlacesResponse(val success: Boolean, val places: List<MobilityPlace> = emptyList(), val searchAvailable: Boolean = true, val error: String? = null)
@JsonClass(generateAdapter = true)
data class MobilityRoadResponse(val success: Boolean, val distanceKm: Double? = null, val durationMinutes: Double? = null, val error: String? = null)

/** Public availability is separate from capability-protected guest details and authenticated driver operations. */
interface MobilityApi {
    @GET("api/mobility/locations") suspend fun locations(@Query("q") query: String): Response<MobilityPlacesResponse>
    @GET("api/mobility/route") suspend fun road(@Query("originLatitude") originLatitude: Double,
        @Query("originLongitude") originLongitude: Double, @Query("destinationLatitude") destinationLatitude: Double,
        @Query("destinationLongitude") destinationLongitude: Double): Response<MobilityRoadResponse>
    @GET("api/mobility/driver/opportunities") suspend fun opportunities(@Query("latitude") latitude: Double? = null,
        @Query("longitude") longitude: Double? = null): Response<MobilityOpportunitiesResponse>
    @POST("api/mobility/driver/requests/{id}/quotes") suspend fun quote(@Path("id") id: String,
        @Body input: MobilityQuoteInput): Response<MobilityQuoteResponse>
    @GET("api/mobility/requests/{id}/quotes") suspend fun quotes(@Path("id") id: String,
        @Header("X-Request-Secret") secret: String): Response<MobilityQuotesResponse>
    @POST("api/mobility/requests/{id}/select") suspend fun selectQuote(@Path("id") id: String,
        @Header("X-Request-Secret") secret: String, @Body input: MobilitySelectInput): Response<MobilityRequestResponse>
    @POST("api/mobility/driver/requests/{id}/progress") suspend fun progress(@Path("id") id: String,
        @Body input: MobilityProgressInput): Response<MobilityRequestResponse>
    @POST("api/mobility/driver/requests/{id}/location") suspend fun updateRequestLocation(@Path("id") id: String,
        @Body input: MobilityLocation): Response<MobilityActionResponse>
    @GET("api/mobility/requests/{id}/location") suspend fun requestLocation(@Path("id") id: String,
        @Header("X-Request-Secret") secret: String): Response<MobilityLocationResponse>
    @POST("api/mobility/requests/{id}/confirm") suspend fun confirmRequest(@Path("id") id: String,
        @Header("X-Request-Secret") secret: String, @Body input: MobilityConfirmInput): Response<MobilityRequestResponse>
    @POST("api/mobility/requests/{id}/review") suspend fun reviewRequest(@Path("id") id: String,
        @Header("X-Request-Secret") secret: String, @Body input: MobilityReview): Response<MobilityReviewResponse>
    @GET("api/mobility/catalog") suspend fun catalog(): Response<MobilityCatalogResponse>
    @GET("api/mobility/trips") suspend fun trips(@Query("origin") origin: String? = null,
        @Query("destination") destination: String? = null, @Query("date") date: String? = null): Response<MobilityTripsResponse>
    @GET("api/mobility/trips/{id}/seats") suspend fun seats(@Path("id") id: String): Response<MobilitySeatsResponse>
    @POST("api/mobility/trips/{id}/bookings") suspend fun book(@Path("id") id: String,
        @Body input: MobilityBookingInput): Response<MobilityBookingResponse>
    @GET("api/mobility/bookings/{id}") suspend fun booking(@Path("id") id: String,
        @Header("X-Booking-Secret") secret: String): Response<MobilityBookingResponse>
    @POST("api/mobility/bookings/{id}/checkout") suspend fun checkout(@Path("id") id: String,
        @Header("X-Booking-Secret") secret: String): Response<MobilityBookingResponse>
    @POST("api/mobility/bookings/{id}/reconcile") suspend fun reconcile(@Path("id") id: String,
        @Header("X-Booking-Secret") secret: String): Response<MobilityBookingResponse>
    @POST("api/mobility/requests") suspend fun request(@Body input: MobilityRequestInput): Response<MobilityRequestResponse>
    @GET("api/mobility/requests/{id}") suspend fun requestStatus(@Path("id") id: String,
        @Header("X-Request-Secret") secret: String): Response<MobilityRequestResponse>
    @PUT("api/mobility/requests/{id}/photo") suspend fun photo(@Path("id") id: String,
        @Header("X-Request-Secret") secret: String, @Body body: RequestBody): Response<MobilityRequestResponse>
    @Streaming @GET("api/mobility/requests/{id}/photo") suspend fun requestPhoto(@Path("id") id: String,
        @Header("X-Request-Secret") secret: String? = null): Response<okhttp3.ResponseBody>
    @POST("api/mobility/requests/{id}/submit") suspend fun submitRequest(@Path("id") id: String,
        @Header("X-Request-Secret") secret: String): Response<MobilityRequestResponse>
    @GET("api/mobility/driver/dashboard") suspend fun driverDashboard(): Response<MobilityDriverDashboard>
    @POST("api/mobility/driver/trips") suspend fun proposeTrip(@Body input: MobilityTripInput): Response<MobilityTripResponse>
    @GET("api/mobility/driver/trips/{id}/roster") suspend fun roster(@Path("id") id: String): Response<MobilityRosterResponse>
    @POST("api/mobility/driver/trips/{id}/boarding") suspend fun startBoarding(@Path("id") id: String,
        @Body input: MobilityBoardingInput): Response<MobilityActionResponse>
    @POST("api/mobility/driver/trips/{id}/board") suspend fun board(@Path("id") id: String,
        @Body input: MobilityBoardingInput): Response<MobilityActionResponse>
    @POST("api/mobility/driver/trips/{id}/complete") suspend fun complete(@Path("id") id: String): Response<MobilityActionResponse>
}
