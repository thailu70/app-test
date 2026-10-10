package com.example.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// -------------------------------------------------------------
// Authentication Models
// -------------------------------------------------------------
@JsonClass(generateAdapter = true)
data class LoginRequest(
    val phone: String,
    val password: String,
    val role: String? = null
)

@JsonClass(generateAdapter = true)
data class OtpRequest(val phone: String)

@JsonClass(generateAdapter = true)
data class OtpVerifyRequest(val phone: String, val code: String)

@JsonClass(generateAdapter = true)
data class OtpResponse(
    val success: Boolean,
    val verified: Boolean = false,
    val expiresInSeconds: Int? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null
)

@JsonClass(generateAdapter = true)
data class RegisterRequest(
    val fullName: String,
    val phone: String,
    val email: String? = null,
    val password: String,
    val role: String,
    val adminSecret: String? = null,
    val licenseNumber: String? = null,
    val companyName: String? = null,
    val assignedVehiclePlate: String? = null,
    val vehicleModel: String? = null,
    val vehicleType: String? = null,
    val appliedRouteId: String? = null,
    val appliedRouteName: String? = null
)

@JsonClass(generateAdapter = true)
data class UserDto(
    val id: String,
    val role: String,
    val fullName: String,
    val phone: String,
    val email: String? = null,
    val status: String? = "ACTIVE",
    val licenseNumber: String? = "",
    val companyName: String? = "",
    val assignedVehiclePlate: String? = "",
    val appliedRouteId: String? = "",
    val appliedRouteName: String? = ""
)

@JsonClass(generateAdapter = true)
data class AuthResponse(
    val success: Boolean,
    val token: String? = null,
    val user: UserDto? = null,
    val subscription: SubscriptionDto? = null,
    val error: String? = null,
    val errorAm: String? = null,
    val message: String? = null
)

// -------------------------------------------------------------
// Routes and Stops Models
// -------------------------------------------------------------
@JsonClass(generateAdapter = true)
data class RouteDto(
    val id: String,
    val name: String,
    val nameAm: String,
    val description: String? = null,
    val morningDeparture: String? = "06:30",
    val eveningDeparture: String? = "17:30",
    val distanceKm: Double? = 12.0,
    val basePriceEtb: Double? = 2500.0,
    val active: Boolean? = true,
    val stops: List<RouteStopDto>? = null
)

@JsonClass(generateAdapter = true)
data class RouteStopDto(
    val id: String,
    val routeId: String,
    val stopName: String,
    val stopNameAm: String,
    val stopOrder: Int,
    val latitude: Double,
    val longitude: Double,
    val scheduledMorningTime: String? = null,
    val scheduledEveningTime: String? = null,
    val maxCapacity: Int? = 25
)

@JsonClass(generateAdapter = true)
data class RouteDetailsResponse(
    val success: Boolean,
    val route: RouteDto? = null,
    val stops: List<RouteStopDto> = emptyList(),
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class RoutesResponse(
    val success: Boolean,
    val count: Int? = 0,
    val routes: List<RouteDto> = emptyList()
)

// -------------------------------------------------------------
// Subscription Models
// -------------------------------------------------------------
@JsonClass(generateAdapter = true)
data class SubscriptionDto(
    val id: String,
    val routeId: String,
    val routeName: String? = null,
    val routeNameAm: String? = null,
    val status: String? = "PENDING",
    val paymentStatus: String? = "UNPAID",
    val priceEtb: Double? = 2500.0,
    val startDate: String? = null,
    val endDate: String? = null,
    val daysRemaining: Int = 0,
    val qrToken: String? = null,
    val morningSchedule: String? = null,
    val eveningSchedule: String? = null,
    val vehicleId: String? = null
)

@JsonClass(generateAdapter = true)
data class SubscriptionStatusResponse(
    val success: Boolean,
    val isSubscribed: Boolean = false,
    val status: String? = "NOT_SUBSCRIBED",
    val paymentStatus: String? = "UNPAID",
    val qrToken: String? = null,
    val message: String? = null,
    val messageAm: String? = null,
    val subscription: SubscriptionDto? = null
)

@JsonClass(generateAdapter = true)
data class SubscribeRequest(
    val routeId: String,
    val pickupStopId: String? = null,
    val destinationStopId: String? = null
)

@JsonClass(generateAdapter = true)
data class SubscribeResponse(
    val success: Boolean,
    val status: String? = "PENDING",
    val paymentStatus: String? = "UNPAID",
    val message: String? = null,
    val messageAm: String? = null,
    val actionRequired: String? = null
)

// -------------------------------------------------------------
// Telebirr Payment Models
// -------------------------------------------------------------
@JsonClass(generateAdapter = true)
data class TelebirrPayRequest(
    val routeId: String? = null,
    val phone: String? = null,
    val idempotencyKey: String? = null
)

@JsonClass(generateAdapter = true)
data class PaymentTransactionDto(
    val id: String,
    val referenceNumber: String,
    val amountEtb: Double,
    val provider: String? = "Telebirr",
    val date: String? = null,
    val status: String? = "COMPLETED"
)

@JsonClass(generateAdapter = true)
data class TelebirrPayResponse(
    val success: Boolean,
    val message: String? = null,
    val messageAm: String? = null,
    val paymentMode: String? = "TEST",
    val idempotentReplay: Boolean? = false,
    val transaction: PaymentTransactionDto? = null,
    val subscription: SubscriptionDto? = null,
    val error: String? = null,
    val checkoutUrl: String? = null,
    val merchantOrderId: String? = null,
    val status: String? = null,
    val amountEtb: Double? = null
)

@JsonClass(generateAdapter = true)
data class TelebirrPaymentStatusResponse(
    val success: Boolean,
    val status: String? = null,
    val merchantOrderId: String? = null,
    val message: String? = null
)

// -------------------------------------------------------------
// Vehicle & Fleet Models
// -------------------------------------------------------------
@JsonClass(generateAdapter = true)
data class VehicleDto(
    val id: String,
    val plateNumber: String,
    val model: String,
    val vehicleType: String,
    val capacityLimit: Int,
    val currentOccupancy: Int = 0,
    val assignedRouteId: String? = null,
    val routeName: String? = null,
    val driverId: String? = null,
    val driverName: String? = null,
    val currentLat: Double? = 9.010,
    val currentLng: Double? = 38.760,
    val status: String? = "IN_SERVICE",
    val isFull: Boolean? = false,
    val availableSeats: Int? = 0
)

@JsonClass(generateAdapter = true)
data class VehiclesResponse(
    val success: Boolean,
    val count: Int? = 0,
    val vehicles: List<VehicleDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TrackedVehicleDto(
    val id: String,
    val plateNumber: String,
    val model: String,
    val vehicleType: String,
    val capacityLimit: Int,
    val currentOccupancy: Int = 0,
    val assignedRouteId: String? = null,
    val routeName: String? = null,
    val morningDeparture: String? = null,
    val driverId: String? = null,
    val driverName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speed: Double? = null,
    val currentStop: String? = null,
    val lastGpsAt: String? = null,
    val hasGpsLocation: Boolean = false
)

@JsonClass(generateAdapter = true)
data class TrackingVehicleResponse(
    val success: Boolean,
    val vehicle: TrackedVehicleDto? = null,
    val message: String? = null,
    val error: String? = null
)


@JsonClass(generateAdapter = true)
data class UpdateVehicleTypeRequest(
    val vehicleType: String,
    val capacityLimit: Int? = null,
    val currentOccupancy: Int? = null
)

@JsonClass(generateAdapter = true)
data class LocationUpdateRequest(
    val latitude: Double,
    val longitude: Double,
    val speed: Double? = 0.0,
    val currentStop: String? = null
)

// -------------------------------------------------------------
// Driver Trips Models
// -------------------------------------------------------------
@JsonClass(generateAdapter = true)
data class StartTripRequest(
    val routeId: String,
    val direction: String = "OUTBOUND",
    val vehicleId: String? = null,
    val confirmedArrival: Boolean = false
)

@JsonClass(generateAdapter = true)
data class DepartureStopDto(
    val id: String,
    val name: String,
    val nameAm: String? = null,
    val latitude: Double,
    val longitude: Double
)

@JsonClass(generateAdapter = true)
data class TripReadinessResponse(
    val success: Boolean,
    val canConfirmArrival: Boolean = false,
    val routeId: String? = null,
    val routeName: String? = null,
    val direction: String? = null,
    val routeMode: String? = null,
    val scheduledDepartureAt: String? = null,
    val scheduledTime: String? = null,
    val departureStop: DepartureStopDto? = null,
    val radiusMeters: Int = 50,
    val distanceMeters: Int? = null,
    val insideGeofence: Boolean = false,
    val gpsFresh: Boolean = false,
    val gpsAgeSeconds: Int? = null,
    val scheduleWindowOpen: Boolean = false,
    val minutesUntilDeparture: Int? = null,
    val reminderDue: Boolean = false,
    val reminderMessage: String? = null,
    val message: String? = null,
    val code: String? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class TripDto(
    val id: String,
    val driverId: String,
    val vehicleId: String,
    val plateNumber: String? = null,
    val vehicleType: String? = null,
    val capacityLimit: Int? = 24,
    val routeId: String,
    val routeName: String? = null,
    val routeNameAm: String? = null,
    val direction: String? = "OUTBOUND",
    val currentStop: String? = null,
    val currentOccupancy: Int = 0,
    val status: String? = "IN_PROGRESS",
    val startTime: String? = null
)

@JsonClass(generateAdapter = true)
data class StartTripResponse(
    val success: Boolean,
    val message: String? = null,
    val trip: TripDto? = null,
    val error: String? = null
)

@JsonClass(generateAdapter = true)
data class ActiveTripResponse(
    val success: Boolean,
    val hasActiveTrip: Boolean = false,
    val trip: TripDto? = null
)

@JsonClass(generateAdapter = true)
data class StopArrivalRequest(
    val stopName: String
)

// -------------------------------------------------------------
// Boarding & Check-in Models
// -------------------------------------------------------------
@JsonClass(generateAdapter = true)
data class ScanCheckinRequest(
    val qrToken: String,
    val tripId: String? = null,
    val vehicleId: String? = null,
    val currentStop: String? = null
)

@JsonClass(generateAdapter = true)
data class PassengerInfo(
    val id: String,
    val name: String,
    val phone: String? = null,
    val subscriptionId: String? = null,
    val daysRemaining: Int = 0
)

@JsonClass(generateAdapter = true)
data class OccupancyInfo(
    val current: Int,
    val maxCapacity: Int,
    val availableSeats: Int,
    val isFull: Boolean
)

@JsonClass(generateAdapter = true)
data class ScanCheckinResponse(
    val success: Boolean,
    val status: String, // VERIFIED_BOARDED, DENIED_CAPACITY_FULL, ALREADY_CHECKED_IN, NOT_SUBSCRIBED, etc.
    val message: String? = null,
    val messageAm: String? = null,
    val error: String? = null,
    val errorAm: String? = null,
    val passenger: PassengerInfo? = null,
    val occupancy: OccupancyInfo? = null
)

@JsonClass(generateAdapter = true)
data class CheckinRecordDto(
    val id: String,
    val tripId: String,
    val passengerId: String,
    val passengerName: String,
    val routeId: String,
    val stopName: String,
    val timestamp: String,
    val status: String,
    val vehicleId: String? = null,
    val driverId: String? = null,
    val vehiclePlate: String? = null
)

@JsonClass(generateAdapter = true)
data class CheckinsResponse(
    val success: Boolean,
    val checkins: List<CheckinRecordDto> = emptyList()
)

// -------------------------------------------------------------
// Complaints, Notifications & Admin Models
// -------------------------------------------------------------
@JsonClass(generateAdapter = true)
data class SubmitComplaintRequest(
    val category: String,
    val description: String
)

@JsonClass(generateAdapter = true)
data class ComplaintDto(
    val id: String,
    val passengerId: String,
    val passengerName: String,
    val category: String,
    val description: String,
    val status: String,
    val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class NotificationDto(
    val id: String,
    val title: String,
    val message: String,
    val targetAudience: String,
    val type: String? = "ALERT",
    val senderName: String? = "Transport Operations",
    val timestamp: String? = null
)

@JsonClass(generateAdapter = true)
data class SendNotificationRequest(
    val title: String,
    val message: String,
    val targetAudience: String = "ALL",
    val type: String = "ALERT"
)

@JsonClass(generateAdapter = true)
data class NotificationsResponse(
    val success: Boolean,
    val notifications: List<NotificationDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AuditLogDto(
    val id: Int,
    val action: String,
    val userId: String? = null,
    val role: String? = null,
    val details: String? = null,
    val timestamp: String? = null
)

@JsonClass(generateAdapter = true)
data class AdminStatsDto(
    val totalPassengers: Int,
    val totalDrivers: Int,
    val activeRoutes: Int,
    val activeVehicles: Int,
    val activeSubscriptions: Int,
    val totalRevenueEtb: Double,
    val todayCheckins: Int,
    val openComplaints: Int
)

@JsonClass(generateAdapter = true)
data class AdminStatsResponse(
    val success: Boolean,
    val stats: AdminStatsDto
)

@JsonClass(generateAdapter = true)
data class GenericResponse(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null
)
