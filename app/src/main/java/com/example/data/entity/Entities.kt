package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val role: String, // "PASSENGER", "DRIVER", "ADMIN"
    val fullName: String,
    val phone: String,
    val email: String,
    val status: String = "ACTIVE",
    val licenseNumber: String = "",
    val avatarInitials: String = "",
    val password: String = "123456",
    val companyName: String = "",
    val assignedVehiclePlate: String = "",
    val appliedRouteId: String = "",
    val appliedRouteName: String = ""
)

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameAm: String,
    val description: String,
    val morningDeparture: String,
    val eveningDeparture: String,
    val distanceKm: Double,
    val basePriceEtb: Double,
    val active: Boolean = true
)

@Entity(tableName = "route_stops")
data class RouteStopEntity(
    @PrimaryKey val id: String,
    val routeId: String,
    val stopName: String,
    val stopNameAm: String,
    val stopOrder: Int,
    val latitude: Double,
    val longitude: Double,
    val scheduledMorningTime: String,
    val scheduledEveningTime: String,
    val maxCapacity: Int = 25
)

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey val id: String,
    val plateNumber: String,
    val model: String,
    val type: String, // "Toyota Coaster", "Minibus HiAce"
    val capacity: Int,
    val assignedDriverId: String,
    val status: String, // "ACTIVE", "MAINTENANCE", "IN_SERVICE"
    val insuranceExpiry: String,
    val inspectionExpiry: String
)

@Entity(tableName = "subscription_plans")
data class SubscriptionPlanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val routeId: String,
    val priceEtb: Double,
    val billingPeriod: String = "MONTHLY",
    val description: String
)

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val passengerId: String,
    val routeId: String,
    val pickupStopId: String,
    val destinationStopId: String,
    val morningSchedule: String,
    val eveningSchedule: String,
    val startDate: String,
    val endDate: String,
    val priceEtb: Double,
    val paymentStatus: String, // "PAID", "PENDING", "FAILED"
    val subscriptionStatus: String, // "ACTIVE", "EXPIRING", "EXPIRED", "CANCELLED"
    val vehicleId: String,
    val qrToken: String,
    val daysRemaining: Int = 26
)

@Entity(tableName = "payment_transactions")
data class PaymentTransactionEntity(
    @PrimaryKey val id: String,
    val transactionRef: String,
    val passengerId: String,
    val subscriptionId: String,
    val amountEtb: Double,
    val provider: String = "Telebirr",
    val status: String, // "SUCCESS", "FAILED", "PENDING", "CANCELLED"
    val timestamp: String,
    val paymentPhone: String,
    val receiptNumber: String
)

@Entity(tableName = "check_ins")
data class CheckInRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val qrToken: String,
    val passengerId: String,
    val passengerName: String,
    val tripId: String,
    val routeId: String,
    val vehicleId: String,
    val driverId: String,
    val stopName: String,
    val checkInTimestamp: String,
    val status: String, // "PRESENT", "NO_SHOW", "LATE", "WRONG_ROUTE"
    val latitude: Double,
    val longitude: Double,
    val syncStatus: String = "SYNCED" // "SYNCED", "PENDING_SYNC"
)

@Entity(tableName = "complaints")
data class ComplaintEntity(
    @PrimaryKey val id: String,
    val passengerId: String,
    val passengerName: String,
    val category: String, // "Driver", "Vehicle", "Punctuality", "Payment", "Other"
    val text: String,
    val date: String,
    val status: String = "OPEN", // "OPEN", "IN_REVIEW", "RESOLVED"
    val routeId: String
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val action: String,
    val userId: String,
    val role: String,
    val timestamp: String,
    val details: String
)

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val message: String,
    val targetAudience: String, // "PASSENGERS", "TRANSPORTERS", "ALL"
    val timestamp: String,
    val type: String, // "ALERT", "REMINDER", "SCHEDULE", "PAYMENT"
    val senderName: String = "Transport Operations",
    val isRead: Boolean = false
)
