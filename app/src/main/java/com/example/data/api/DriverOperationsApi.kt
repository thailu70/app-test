package com.example.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

data class DriverGpsFix(val latitude: Double, val longitude: Double, val accuracyMeters: Double, val timestamp: Long)
data class DriverDepartureRequest(val routeId: String, val vehicleId: String, val direction: String = "OUTBOUND", val location: DriverGpsFix?)
data class DriverArrivalRequest(val location: DriverGpsFix?)
data class DriverVehicleAssignment(
    val id: String, val plateNumber: String, val vehicleType: String, val capacityLimit: Int,
    val assignedRouteId: String, val routeName: String = "", val morningDeparture: String = "", val eveningDeparture: String = "",
    val departureStopName: String? = null, val departureStopNameAm: String? = null,
    val destinationStopName: String? = null, val destinationStopNameAm: String? = null
)
data class DriverDepartureValidation(
    val allowed: Boolean = false, val code: String = "", val message: String = "",
    val distanceMeters: Double? = null, val radiusMeters: Int = 300, val departureAt: String? = null,
    val windowOpensAt: String? = null, val windowClosesAt: String? = null, val serverTime: String? = null
)
data class DriverRosterPassenger(
    val id: String = "", val passengerId: String? = null, val passengerName: String = "Passenger",
    val passengerPhone: String = "", val pickupStop: String = "", val seatNumber: Int? = null,
    val photoUrl: String? = null, val paymentStatus: String = "", val status: String = "PENDING"
)
data class DriverIntercityDeparture(
    val id: String, val origin: String, val destination: String, val departure_at: String,
    val status: String, val vehicle_id: String? = null, val driver_id: String? = null,
    val seats: Int = 0, val plateNumber: String? = null
)
data class DriverAssignmentsResponse(val success: Boolean, val vehicles: List<DriverVehicleAssignment> = emptyList(), val error: String? = null)
data class DriverDepartureCheckResponse(val success: Boolean, val validation: DriverDepartureValidation? = null, val error: String? = null)
data class DriverRosterResponse(val success: Boolean, val roster: List<DriverRosterPassenger> = emptyList(), val error: String? = null)
data class DriverIntercityResponse(val success: Boolean, val departures: List<DriverIntercityDeparture> = emptyList(), val error: String? = null)
data class DriverJourneyResponse(val success: Boolean, val departure: DriverIntercityDeparture? = null, val error: String? = null, val message: String? = null)
data class DriverBoardRequest(val qrToken: String)
data class DriverBoardResponse(val success: Boolean, val booking: DriverRosterPassenger? = null, val error: String? = null)

interface DriverOperationsApi {
    @GET("api/trips/assignments") suspend fun assignments(): Response<DriverAssignmentsResponse>
    @GET("api/trips/active") suspend fun activeTrip(): Response<ActiveTripResponse>
    @POST("api/trips/departure-check") suspend fun checkDeparture(@Body body: DriverDepartureRequest): Response<DriverDepartureCheckResponse>
    @POST("api/trips/start") suspend fun confirmCommuteArrival(@Body body: DriverDepartureRequest): Response<StartTripResponse>
    @GET("api/trips/roster") suspend fun commuteRoster(@Query("vehicleId") vehicleId: String): Response<DriverRosterResponse>
    @POST("api/trips/{id}/end") suspend fun completeCommute(@Path("id") id: String): Response<GenericResponse>
    @POST("api/checkins/scan") suspend fun boardCommute(@Body body: ScanCheckinRequest): Response<ScanCheckinResponse>
    @GET("api/intercity/driver/departures") suspend fun departures(): Response<DriverIntercityResponse>
    @GET("api/intercity/driver/departures/{id}/roster") suspend fun intercityRoster(@Path("id") id: String): Response<DriverRosterResponse>
    @POST("api/intercity/driver/departures/{id}/arrival") suspend fun confirmIntercityArrival(@Path("id") id: String, @Body body: DriverArrivalRequest): Response<DriverJourneyResponse>
    @POST("api/intercity/driver/departures/{id}/complete") suspend fun completeIntercity(@Path("id") id: String): Response<DriverJourneyResponse>
    @POST("api/intercity/driver/departures/{id}/board") suspend fun boardIntercity(@Path("id") id: String, @Body body: DriverBoardRequest): Response<DriverBoardResponse>
}
