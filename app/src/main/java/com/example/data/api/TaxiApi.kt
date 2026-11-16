package com.example.data.api

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class TaxiSettings(val enabled: Boolean = false, val sharedEnabled: Boolean = false,
    val baseFareEtb: Double = 0.0, val perKmEtb: Double = 0.0, val minimumFareEtb: Double = 0.0,
    val waitingPerMinuteEtb: Double = 0.0, val pickupGraceMinutes: Double = 0.0, val stopGraceMinutes: Double = 0.0,
    val fixedCommissionEtb: Double = 0.0, val sharedFixedCommissionEtb: Double = 0.0,
    val pollingSeconds: Int = 8, val gpsUploadSeconds: Int = 5)
@JsonClass(generateAdapter = true)
data class TaxiTier(val id: String, val name: String, val multiplier: Double = 1.0)
@JsonClass(generateAdapter = true)
data class TaxiVehicle(val id: String, val model: String = "", val plateNumber: String = "",
    val capacityLimit: Int = 0, val tier: String = "ECONOMY")
@JsonClass(generateAdapter = true)
data class TaxiRouteInput(val origin: String, val destination: String, val originLatitude: Double,
    val originLongitude: Double, val destinationLatitude: Double, val destinationLongitude: Double,
    val tier: String = "ECONOMY", val idempotencyKey: String = "", val vehicleId: String? = null,
    val passengerConsent: Boolean? = null, val departureAt: String? = null,
    val seats: Int? = null, val farePerSeatEtb: Double? = null,
    val latitude: Double? = null, val longitude: Double? = null, val accuracyMeters: Double? = null, val timestamp: String? = null,
    val expectedFareEtb: Double? = null, val expectedCommissionEtb: Double? = null)
@JsonClass(generateAdapter = true)
data class TaxiEstimate(val distanceKm: Double = 0.0, val durationMinutes: Double = 0.0,
    val estimatedFareEtb: Double = 0.0, val tier: String = "ECONOMY", val routeGeometry: MobilityRoadGeometry? = null,
    val routeGeometryQuality: String? = null, val routeGeometryToleranceMeters: Double? = null)
@JsonClass(generateAdapter = true)
data class TaxiRide(val id: String, val passengerId: String? = null, val driverId: String? = null,
    val vehicleId: String? = null, val status: String = "REQUESTED", val source: String = "APP",
    val origin: String = "", val destination: String = "", val originLatitude: Double = 0.0,
    val originLongitude: Double = 0.0, val destinationLatitude: Double = 0.0, val destinationLongitude: Double = 0.0,
    val tier: String = "ECONOMY", val distanceKm: Double = 0.0, val durationMinutes: Double = 0.0,
    val estimatedFareEtb: Double = 0.0, val fareEtb: Double? = null, val meterDistanceKm: Double = 0.0,
    val billableWaitingSeconds: Int = 0, val tripDurationSeconds: Int = 0, val waiting: String? = null,
    val stopWaitingRequested: Boolean = false, val commissionEtb: Double = 0.0, val settlement: String? = null,
    val boardingPin: String? = null, val driverName: String? = null, val driverPhone: String? = null,
    val vehicleModel: String? = null, val vehiclePlate: String? = null, val vehicleType: String? = null,
    val passengerName: String? = null, val passengerPhone: String? = null, val location: MobilityLocation? = null,
    val routeGeometry: MobilityRoadGeometry? = null, val createdAt: String? = null,
    val startedAt: String? = null, val completedAt: String? = null, val lastSequence: Long = 0,
    val waitingFreeRemainingSeconds: Int? = null, val waitingRateEtbPerMinute: Double? = null,
    val distancePaused: Boolean = false, val waitingFareEtb: Double? = null, val distanceFareEtb: Double? = null,
    val waitingState: String = "", val serverTime: String? = null,
    val routeGeometryQuality: String? = null, val routeGeometryToleranceMeters: Double? = null)
@JsonClass(generateAdapter = true)
data class SharedRideBooking(val id: String, val tripId: String = "", val passengerId: String? = null,
    val seats: Int = 1, val seatNumbers: List<Int> = emptyList(), val farePerSeatEtb: Double = 0.0,
    val totalEtb: Double = 0.0, val status: String = "CONFIRMED", val boardingPin: String? = null,
    val passengerName: String? = null, val passengerPhone: String? = null)
@JsonClass(generateAdapter = true)
data class SharedRideTrip(val id: String, val initiatorRole: String = "DRIVER", val passengerId: String? = null,
    val driverId: String? = null, val vehicleId: String? = null, val origin: String = "", val destination: String = "",
    val originLatitude: Double = 0.0, val originLongitude: Double = 0.0, val destinationLatitude: Double = 0.0,
    val destinationLongitude: Double = 0.0, val departureAt: String = "", val seats: Int = 0,
    val availableSeats: Int = 0, val farePerSeatEtb: Double? = null, val status: String = "OPEN",
    val driverName: String? = null, val driverPhone: String? = null, val vehicleModel: String? = null,
    val vehiclePlate: String? = null, val vehicleType: String? = null,
    val bookings: List<SharedRideBooking> = emptyList(), val location: MobilityLocation? = null,
    val routeGeometry: MobilityRoadGeometry? = null, val commissionEtb: Double = 0.0, val settlement: String? = null,
    val routeGeometryQuality: String? = null, val routeGeometryToleranceMeters: Double? = null)
@JsonClass(generateAdapter = true)
data class TaxiResponse(val success: Boolean, val error: String? = null, val code: String? = null,
    val settings: TaxiSettings? = null, val tiers: List<TaxiTier> = emptyList(), val estimate: TaxiEstimate? = null,
    val ride: TaxiRide? = null, val rides: List<TaxiRide> = emptyList(), val eligible: Boolean = false,
    val online: Boolean = false, val vehicles: List<TaxiVehicle> = emptyList(), val wallet: MobilityWallet = MobilityWallet(),
    val calls: List<TaxiRide> = emptyList(), val sharedTrips: List<SharedRideTrip> = emptyList(),
    val sharedRequests: List<SharedRideTrip> = emptyList(), val trips: List<SharedRideTrip> = emptyList(),
    val trip: SharedRideTrip? = null, val bookings: List<SharedRideBooking> = emptyList(),
    val booking: SharedRideBooking? = null, val occupiedSeats: List<Int> = emptyList())
@JsonClass(generateAdapter = true)
data class TaxiGpsPoint(val sequence: Long, val latitude: Double, val longitude: Double,
    val accuracyMeters: Double, val timestamp: String, val mock: Boolean = false)
@JsonClass(generateAdapter = true)
data class TaxiGpsBatch(val points: List<TaxiGpsPoint>)
@JsonClass(generateAdapter = true)
data class TaxiNearbyCar(val latitude: Double, val longitude: Double, val tier: String = "ECONOMY")
@JsonClass(generateAdapter = true)
data class TaxiNearbyResponse(val success: Boolean, val vehicles: List<TaxiNearbyCar> = emptyList(), val approximate: Boolean = true, val error: String? = null)

/** Taxi operations are isolated from the existing guest cargo and charter APIs. */
interface TaxiApi {
    @GET("api/rides/catalog") suspend fun catalog(): Response<TaxiResponse>
    @GET("api/rides/nearby") suspend fun nearby(@Query("latitude") latitude: Double, @Query("longitude") longitude: Double, @Query("tier") tier: String): Response<TaxiNearbyResponse>
    @POST("api/rides/estimate") suspend fun estimate(@Body input: TaxiRouteInput): Response<TaxiResponse>
    @GET("api/rides/me") suspend fun me(): Response<TaxiResponse>
    @POST("api/rides/requests") suspend fun request(@Body input: TaxiRouteInput): Response<TaxiResponse>
    @GET("api/rides/requests/{id}") suspend fun ride(@Path("id") id: String): Response<TaxiResponse>
    @POST("api/rides/requests/{id}/cancel") suspend fun cancel(@Path("id") id: String, @Body input: Map<String, String>): Response<TaxiResponse>
    @POST("api/rides/requests/{id}/stop-wait") suspend fun approveWait(@Path("id") id: String, @Body input: Map<String, Boolean>): Response<TaxiResponse>
    @GET("api/rides/driver/dashboard") suspend fun dashboard(): Response<TaxiResponse>
    @PUT("api/rides/driver/availability") suspend fun availability(@Body input: Map<String, @JvmSuppressWildcards Any>): Response<TaxiResponse>
    @POST("api/rides/driver/requests/{id}/accept") suspend fun accept(@Path("id") id: String, @Body input: Map<String, String>): Response<TaxiResponse>
    @POST("api/rides/driver/street") suspend fun street(@Body input: TaxiRouteInput): Response<TaxiResponse>
    @POST("api/rides/driver/trips/{id}/arrive") suspend fun arrive(@Path("id") id: String, @Body input: Map<String, @JvmSuppressWildcards Any>): Response<TaxiResponse>
    @POST("api/rides/driver/trips/{id}/start") suspend fun start(@Path("id") id: String, @Body input: Map<String, String>): Response<TaxiResponse>
    @POST("api/rides/driver/trips/{id}/locations") suspend fun locations(@Path("id") id: String, @Body input: TaxiGpsBatch): Response<TaxiResponse>
    @POST("api/rides/driver/trips/{id}/wait") suspend fun wait(@Path("id") id: String, @Body input: Map<String, String>): Response<TaxiResponse>
    @POST("api/rides/driver/trips/{id}/complete") suspend fun complete(@Path("id") id: String, @Body input: Map<String, @JvmSuppressWildcards Any>): Response<TaxiResponse>
    @POST("api/rides/driver/trips/{id}/cancel") suspend fun driverCancel(@Path("id") id: String, @Body input: Map<String, String>): Response<TaxiResponse>
    @GET("api/rides/shared/trips") suspend fun shared(@Query("origin") origin: String? = null, @Query("destination") destination: String? = null): Response<TaxiResponse>
    @GET("api/rides/shared/trips/{id}") suspend fun sharedTrip(@Path("id") id: String): Response<TaxiResponse>
    @POST("api/rides/shared/trips/{id}/cancel") suspend fun cancelSharedRequest(@Path("id") id: String, @Body input: Map<String, String> = emptyMap()): Response<TaxiResponse>
    @POST("api/rides/shared/requests") suspend fun sharedRequest(@Body input: TaxiRouteInput): Response<TaxiResponse>
    @POST("api/rides/driver/shared/trips") suspend fun offer(@Body input: TaxiRouteInput): Response<TaxiResponse>
    @POST("api/rides/driver/shared/trips/{id}/accept") suspend fun acceptShared(@Path("id") id: String, @Body input: Map<String, @JvmSuppressWildcards Any>): Response<TaxiResponse>
    @POST("api/rides/shared/trips/{id}/bookings") suspend fun book(@Path("id") id: String, @Body input: Map<String, @JvmSuppressWildcards Any>): Response<TaxiResponse>
    @POST("api/rides/shared/bookings/{id}/cancel") suspend fun cancelBooking(@Path("id") id: String): Response<TaxiResponse>
    @POST("api/rides/driver/shared/trips/{id}/actions") suspend fun sharedAction(@Path("id") id: String, @Body input: Map<String, @JvmSuppressWildcards Any>): Response<TaxiResponse>
    @POST("api/rides/driver/shared/trips/{id}/location") suspend fun sharedLocation(@Path("id") id: String, @Body input: Map<String, @JvmSuppressWildcards Any>): Response<TaxiResponse>
}
