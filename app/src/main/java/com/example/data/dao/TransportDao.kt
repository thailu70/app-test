package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TransportDao {

    // Users & Authentication
    @Query("SELECT * FROM users")
    fun getAllUsers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE role = 'PASSENGER'")
    fun getAllPassengers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE role = 'DRIVER'")
    fun getAllDrivers(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1")
    suspend fun getUserById(userId: String): UserEntity?

    @Query("SELECT * FROM users WHERE (phone = :identifier OR email = :identifier) AND role = :role LIMIT 1")
    suspend fun authenticateUser(identifier: String, role: String): UserEntity?

    @Query("SELECT * FROM users WHERE phone = :phone LIMIT 1")
    suspend fun getUserByPhone(phone: String): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<UserEntity>)

    // Routes Management
    @Query("SELECT * FROM routes")
    fun getAllRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE active = 1")
    fun getAllActiveRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE id = :routeId LIMIT 1")
    suspend fun getRouteById(routeId: String): RouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: RouteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutes(routes: List<RouteEntity>)

    @Update
    suspend fun updateRoute(route: RouteEntity)

    @Query("DELETE FROM routes WHERE id = :routeId")
    suspend fun deleteRoute(routeId: String)

    @Query("DELETE FROM route_stops WHERE routeId = :routeId")
    suspend fun deleteStopsForRoute(routeId: String)

    // Stops
    @Query("SELECT * FROM route_stops WHERE routeId = :routeId ORDER BY stopOrder ASC")
    fun getStopsForRoute(routeId: String): Flow<List<RouteStopEntity>>

    @Query("SELECT * FROM route_stops WHERE routeId = :routeId ORDER BY stopOrder ASC")
    suspend fun getStopsForRouteSync(routeId: String): List<RouteStopEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStop(stop: RouteStopEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStops(stops: List<RouteStopEntity>)

    // Vehicles
    @Query("SELECT * FROM vehicles")
    fun getAllVehicles(): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles WHERE id = :vehicleId LIMIT 1")
    suspend fun getVehicleById(vehicleId: String): VehicleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicles(vehicles: List<VehicleEntity>)

    @Update
    suspend fun updateVehicle(vehicle: VehicleEntity)

    // Subscriptions
    @Query("SELECT * FROM subscriptions WHERE passengerId = :passengerId LIMIT 1")
    fun getSubscriptionForPassenger(passengerId: String): Flow<SubscriptionEntity?>

    @Query("SELECT * FROM subscriptions WHERE passengerId = :passengerId LIMIT 1")
    suspend fun getSubscriptionForPassengerSync(passengerId: String): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE qrToken = :qrToken LIMIT 1")
    suspend fun getSubscriptionByQrToken(qrToken: String): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions")
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE subscriptionStatus = 'ACTIVE'")
    fun getActiveSubscriptions(): Flow<List<SubscriptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: SubscriptionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscriptions(subscriptions: List<SubscriptionEntity>)

    @Update
    suspend fun updateSubscription(subscription: SubscriptionEntity)

    // Plans
    @Query("SELECT * FROM subscription_plans")
    fun getAllPlans(): Flow<List<SubscriptionPlanEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlans(plans: List<SubscriptionPlanEntity>)

    // Payments
    @Query("SELECT * FROM payment_transactions WHERE passengerId = :passengerId ORDER BY id DESC")
    fun getPaymentsForPassenger(passengerId: String): Flow<List<PaymentTransactionEntity>>

    @Query("SELECT * FROM payment_transactions ORDER BY id DESC")
    fun getAllPayments(): Flow<List<PaymentTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: PaymentTransactionEntity)

    // Check-ins / Attendance
    @Query("SELECT * FROM check_ins ORDER BY id DESC")
    fun getAllCheckIns(): Flow<List<CheckInRecordEntity>>

    @Query("SELECT * FROM check_ins WHERE passengerId = :passengerId ORDER BY id DESC")
    fun getCheckInsForPassenger(passengerId: String): Flow<List<CheckInRecordEntity>>

    @Query("SELECT * FROM check_ins WHERE tripId = :tripId ORDER BY id DESC")
    fun getCheckInsForTrip(tripId: String): Flow<List<CheckInRecordEntity>>

    @Query("SELECT * FROM check_ins WHERE tripId = :tripId AND passengerId = :passengerId LIMIT 1")
    suspend fun getCheckInForPassengerOnTrip(tripId: String, passengerId: String): CheckInRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckIn(checkIn: CheckInRecordEntity)

    @Query("SELECT COUNT(*) FROM check_ins WHERE tripId = :tripId AND status = 'PRESENT'")
    fun getCheckedInCountForTrip(tripId: String): Flow<Int>

    // Complaints
    @Query("SELECT * FROM complaints ORDER BY date DESC")
    fun getAllComplaints(): Flow<List<ComplaintEntity>>

    @Query("SELECT * FROM complaints WHERE passengerId = :passengerId ORDER BY date DESC")
    fun getComplaintsForPassenger(passengerId: String): Flow<List<ComplaintEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComplaint(complaint: ComplaintEntity)

    @Update
    suspend fun updateComplaint(complaint: ComplaintEntity)

    // Audit logs
    @Query("SELECT * FROM audit_logs ORDER BY id DESC LIMIT 100")
    fun getRecentAuditLogs(): Flow<List<AuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(log: AuditLogEntity)

    // Notifications
    @Query("SELECT * FROM notifications ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE targetAudience IN ('PASSENGERS', 'ALL') ORDER BY timestamp DESC")
    fun getPassengerNotifications(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE targetAudience IN ('TRANSPORTERS', 'ALL') ORDER BY timestamp DESC")
    fun getTransporterNotifications(): Flow<List<NotificationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotifications(notifications: List<NotificationEntity>)
}
