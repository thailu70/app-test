package com.example.data.repository

import com.example.data.dao.TransportDao
import com.example.data.entity.*
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class TransportRepository(private val dao: TransportDao) {

    // Users & Auth
    val allPassengers: Flow<List<UserEntity>> = dao.getAllPassengers()
    val allDrivers: Flow<List<UserEntity>> = dao.getAllDrivers()
    suspend fun getUserById(userId: String): UserEntity? = dao.getUserById(userId)
    suspend fun insertUser(user: UserEntity) = dao.insertUser(user)

    suspend fun authenticate(identifier: String, role: String): UserEntity? {
        return dao.authenticateUser(identifier.trim(), role)
    }

    suspend fun registerUser(
        fullName: String,
        phone: String,
        email: String,
        password: String,
        role: String,
        licenseNumber: String = "",
        companyName: String = "",
        assignedVehiclePlate: String = "",
        appliedRouteId: String = "",
        appliedRouteName: String = ""
    ): UserEntity {
        val initials = fullName.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")
        val userId = "usr_" + role.lowercase().take(3) + "_" + UUID.randomUUID().toString().take(6)
        val user = UserEntity(
            id = userId,
            role = role,
            fullName = fullName,
            phone = phone,
            email = email.ifBlank { "$phone@transport.et" },
            status = "ACTIVE",
            licenseNumber = licenseNumber,
            avatarInitials = initials.ifBlank { "ET" },
            password = password,
            companyName = companyName,
            assignedVehiclePlate = assignedVehiclePlate.ifBlank { "AA-12345" },
            appliedRouteId = appliedRouteId,
            appliedRouteName = appliedRouteName
        )
        dao.insertUser(user)

        // If passenger applied for a route, initialize subscription record
        if (role == "PASSENGER" && appliedRouteId.isNotBlank()) {
            val route = dao.getRouteById(appliedRouteId)
            val subId = "sub_" + userId + "_" + SimpleDateFormat("yyyyMM", Locale.US).format(Date())
            val qr = com.example.core.qr.QrSecurityEngine.generateSecureQrToken(subId, userId)
            val initialSub = SubscriptionEntity(
                id = subId,
                passengerId = userId,
                routeId = appliedRouteId,
                pickupStopId = "stop_atlas",
                destinationStopId = "stop_merkato",
                morningSchedule = route?.morningDeparture ?: "06:30",
                eveningSchedule = route?.eveningDeparture ?: "17:30",
                startDate = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date()),
                endDate = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000)),
                priceEtb = route?.basePriceEtb ?: 2500.0,
                paymentStatus = "PAID",
                subscriptionStatus = "ACTIVE",
                vehicleId = "veh_aa_12345",
                qrToken = qr,
                daysRemaining = 30
            )
            dao.insertSubscription(initialSub)
        }

        logAction("USER_REGISTERED", userId, role, "Registered $fullName as $role on route: $appliedRouteName ($phone)")
        return user
    }

    // Routes & Stops
    val allRoutes: Flow<List<RouteEntity>> = dao.getAllRoutes()
    val activeRoutes: Flow<List<RouteEntity>> = dao.getAllActiveRoutes()
    suspend fun getRouteById(routeId: String): RouteEntity? = dao.getRouteById(routeId)
    fun getStopsForRoute(routeId: String): Flow<List<RouteStopEntity>> = dao.getStopsForRoute(routeId)
    suspend fun getStopsForRouteSync(routeId: String): List<RouteStopEntity> = dao.getStopsForRouteSync(routeId)

    suspend fun createRoute(
        name: String,
        nameAm: String,
        description: String,
        morningDeparture: String,
        eveningDeparture: String,
        distanceKm: Double,
        basePriceEtb: Double,
        stopsList: List<Pair<String, String>> = emptyList() // List of (StopName, StopNameAm)
    ): RouteEntity {
        val routeId = "route_" + UUID.randomUUID().toString().take(8)
        val route = RouteEntity(
            id = routeId,
            name = name,
            nameAm = nameAm,
            description = description,
            morningDeparture = morningDeparture,
            eveningDeparture = eveningDeparture,
            distanceKm = distanceKm,
            basePriceEtb = basePriceEtb,
            active = true
        )
        dao.insertRoute(route)

        // Insert stops if provided
        stopsList.forEachIndexed { index, pair ->
            val stop = RouteStopEntity(
                id = "stop_" + UUID.randomUUID().toString().take(6),
                routeId = routeId,
                stopName = pair.first,
                stopNameAm = pair.second,
                stopOrder = index + 1,
                latitude = 9.01 + (index * 0.005),
                longitude = 38.75 + (index * 0.005),
                scheduledMorningTime = morningDeparture,
                scheduledEveningTime = eveningDeparture,
                maxCapacity = 20
            )
            dao.insertStop(stop)
        }

        logAction("ROUTE_CREATED", "admin", "ADMIN", "Created route $name (ETB $basePriceEtb)")
        return route
    }

    suspend fun deleteRoute(routeId: String) {
        dao.deleteStopsForRoute(routeId)
        dao.deleteRoute(routeId)
        logAction("ROUTE_DELETED", "admin", "ADMIN", "Deleted route $routeId")
    }

    // Vehicles
    val allVehicles: Flow<List<VehicleEntity>> = dao.getAllVehicles()
    suspend fun getVehicleById(vehicleId: String): VehicleEntity? = dao.getVehicleById(vehicleId)
    suspend fun updateVehicle(vehicle: VehicleEntity) = dao.updateVehicle(vehicle)

    // Plans
    val allPlans: Flow<List<SubscriptionPlanEntity>> = dao.getAllPlans()

    // Subscriptions
    val allSubscriptions: Flow<List<SubscriptionEntity>> = dao.getAllSubscriptions()
    val activeSubscriptions: Flow<List<SubscriptionEntity>> = dao.getActiveSubscriptions()
    fun getSubscriptionForPassenger(passengerId: String): Flow<SubscriptionEntity?> = dao.getSubscriptionForPassenger(passengerId)
    suspend fun getSubscriptionForPassengerSync(passengerId: String): SubscriptionEntity? = dao.getSubscriptionForPassengerSync(passengerId)
    suspend fun getSubscriptionByQrToken(token: String): SubscriptionEntity? = dao.getSubscriptionByQrToken(token)
    suspend fun insertSubscription(sub: SubscriptionEntity) = dao.insertSubscription(sub)
    suspend fun updateSubscription(sub: SubscriptionEntity) = dao.updateSubscription(sub)

    // Payments
    val allPayments: Flow<List<PaymentTransactionEntity>> = dao.getAllPayments()
    fun getPaymentsForPassenger(passengerId: String): Flow<List<PaymentTransactionEntity>> = dao.getPaymentsForPassenger(passengerId)
    suspend fun recordPayment(payment: PaymentTransactionEntity) = dao.insertPayment(payment)

    // Check-ins & Attendance
    val allCheckIns: Flow<List<CheckInRecordEntity>> = dao.getAllCheckIns()
    fun getCheckInsForPassenger(passengerId: String): Flow<List<CheckInRecordEntity>> = dao.getCheckInsForPassenger(passengerId)
    fun getCheckInsForTrip(tripId: String): Flow<List<CheckInRecordEntity>> = dao.getCheckInsForTrip(tripId)
    suspend fun getCheckInForPassengerOnTrip(tripId: String, passengerId: String) = dao.getCheckInForPassengerOnTrip(tripId, passengerId)
    suspend fun recordCheckIn(checkIn: CheckInRecordEntity) = dao.insertCheckIn(checkIn)
    fun getCheckedInCountForTrip(tripId: String): Flow<Int> = dao.getCheckedInCountForTrip(tripId)

    // Complaints
    val allComplaints: Flow<List<ComplaintEntity>> = dao.getAllComplaints()
    fun getComplaintsForPassenger(passengerId: String): Flow<List<ComplaintEntity>> = dao.getComplaintsForPassenger(passengerId)
    suspend fun submitComplaint(complaint: ComplaintEntity) = dao.insertComplaint(complaint)
    suspend fun updateComplaint(complaint: ComplaintEntity) = dao.updateComplaint(complaint)

    // Audit logs
    val recentAuditLogs: Flow<List<AuditLogEntity>> = dao.getRecentAuditLogs()
    suspend fun logAction(action: String, userId: String, role: String, details: String) {
        val log = AuditLogEntity(
            action = action,
            userId = userId,
            role = role,
            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            details = details
        )
        dao.insertAuditLog(log)
    }

    // Notifications Engine
    val allNotifications: Flow<List<NotificationEntity>> = dao.getAllNotifications()
    val passengerNotifications: Flow<List<NotificationEntity>> = dao.getPassengerNotifications()
    val transporterNotifications: Flow<List<NotificationEntity>> = dao.getTransporterNotifications()

    suspend fun sendNotification(
        title: String,
        message: String,
        targetAudience: String, // "PASSENGERS", "TRANSPORTERS", "ALL"
        type: String = "ALERT",
        senderName: String = "Transport Operations"
    ) {
        val notif = NotificationEntity(
            id = "notif_" + UUID.randomUUID().toString().take(8),
            title = title,
            message = message,
            targetAudience = targetAudience,
            timestamp = SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date()),
            type = type,
            senderName = senderName
        )
        dao.insertNotification(notif)
        logAction("NOTIFICATION_DISPATCHED", "admin", "ADMIN", "Dispatched to $targetAudience: '$title'")
    }
}
