package com.example.data.repository

import com.example.core.qr.QrValidationResult
import com.example.core.payment.TelebirrPaymentResult
import com.example.data.api.*
import com.example.data.dao.TransportDao
import com.example.data.entity.*
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * TransportRepository: Coordinates online data synchronization with VPS Backend
 * and local Room offline cache.
 * VPS Backend via Retrofit/OkHttp is the primary SOURCE OF TRUTH.
 * Room serves as an offline-first cache.
 */
class TransportRepository(
    private val dao: TransportDao,
    private val apiService: RoutePassApiService = ApiClient.getService()
) {

    // Users & Auth
    val allPassengers: Flow<List<UserEntity>> = dao.getAllPassengers()
    val allDrivers: Flow<List<UserEntity>> = dao.getAllDrivers()
    suspend fun getUserById(userId: String): UserEntity? = dao.getUserById(userId)
    suspend fun insertUser(user: UserEntity) = dao.insertUser(user)

    var lastAuthError: String? = null
        private set

    /**
     * Authenticate via VPS Backend REST API.
     * Caches authenticated user into Room on success.
     * Server is the strict SOURCE OF TRUTH.
     */
    suspend fun authenticate(identifier: String, role: String, password: String): UserEntity? {
        val cleanPhone = identifier.trim()
        val normalizedRole = role.trim().uppercase()
        lastAuthError = null

        return try {
            val response = apiService.login(LoginRequest(phone = cleanPhone, password = password, role = normalizedRole))
            if (response.isSuccessful && response.body()?.success == true) {
                val body = response.body()!!
                val token = body.token
                ApiClient.setAuthToken(token)

                val userDto = body.user!!
                val initials = userDto.fullName.split(" ")
                    .mapNotNull { it.firstOrNull()?.uppercase() }
                    .take(2).joinToString("")

                val user = UserEntity(
                    id = userDto.id,
                    role = userDto.role,
                    fullName = userDto.fullName,
                    phone = userDto.phone,
                    email = userDto.email ?: "${userDto.phone}@transport.et",
                    status = userDto.status ?: "ACTIVE",
                    licenseNumber = userDto.licenseNumber ?: "",
                    avatarInitials = initials.ifBlank { "ET" },
                    password = password,
                    companyName = userDto.companyName ?: "",
                    assignedVehiclePlate = userDto.assignedVehiclePlate ?: "",
                    appliedRouteId = userDto.appliedRouteId ?: "",
                    appliedRouteName = userDto.appliedRouteName ?: ""
                )
                dao.insertUser(user)
                logAction("ONLINE_LOGIN", user.id, user.role, "Logged into VPS Backend (${user.phone})")
                user
            } else {
                val errorBody = response.errorBody()?.string()
                val parsedMsg = try {
                    if (!errorBody.isNullOrBlank()) {
                        org.json.JSONObject(errorBody).optString("error")
                    } else null
                } catch (e: Exception) { null }
                lastAuthError = parsedMsg ?: response.message().ifBlank { "Invalid credentials for $role." }
                null
            }
        } catch (e: Exception) {
            lastAuthError = "Unable to reach VPS backend at ${ApiClient.getBaseUrl()}. Error: ${e.message}"
            null
        }
    }


    suspend fun requestSignupOtp(phone: String): Pair<Boolean, String> {
        val response = try {
            apiService.requestSignupOtp(OtpRequest(phone.trim()))
        } catch (_: Exception) {
            return false to "Cannot reach RoutePass to send SMS. Check your connection and try again."
        }
        val body = response.body()
        return if (response.isSuccessful && body?.success == true) {
            true to (body.message ?: "Verification SMS requested.")
        } else {
            false to (body?.error ?: "Could not send verification code.")
        }
    }

    suspend fun verifySignupOtp(phone: String, code: String): Pair<Boolean, String> {
        val response = try {
            apiService.verifySignupOtp(OtpVerifyRequest(phone.trim(), code.trim()))
        } catch (_: Exception) {
            return false to "Cannot reach RoutePass to verify the code. Check your connection and try again."
        }
        val body = response.body()
        return if (response.isSuccessful && body?.success == true && body.verified) {
            true to (body.message ?: "Phone number verified.")
        } else {
            false to (body?.error ?: "The verification code could not be verified.")
        }
    }

    /**
     * Register a new user via VPS Backend.
     * Server is the strict SOURCE OF TRUTH.
     * Throws an exception on server rejection or connection error.
     */
    suspend fun registerUser(
        fullName: String,
        phone: String,
        email: String,
        password: String,
        role: String,
        adminSecret: String = "",
        licenseNumber: String = "",
        companyName: String = "",
        assignedVehiclePlate: String = "",
        vehicleModel: String = "",
        vehicleType: String = "MINIBUS_14",
        appliedRouteId: String = "",
        appliedRouteName: String = ""
    ): UserEntity {
        val normalizedRole = role.trim().uppercase()
        val cleanPhone = phone.trim()

        val request = RegisterRequest(
            fullName = fullName.trim(),
            phone = cleanPhone,
            email = email.ifBlank { null },
            password = password,
            role = normalizedRole,
            adminSecret = adminSecret.ifBlank { null },
            licenseNumber = licenseNumber.ifBlank { null },
            companyName = companyName.ifBlank { null },
            assignedVehiclePlate = assignedVehiclePlate.ifBlank { null },
            vehicleModel = vehicleModel.ifBlank { null },
            vehicleType = vehicleType.ifBlank { null },
            appliedRouteId = appliedRouteId.ifBlank { null },
            appliedRouteName = appliedRouteName.ifBlank { null }
        )

        val response = try {
            apiService.register(request)
        } catch (e: Exception) {
            throw IllegalStateException("Cannot connect to VPS backend at ${ApiClient.getBaseUrl()}: ${e.message}")
        }

        if (response.isSuccessful && response.body()?.success == true) {
            val body = response.body()!!
            ApiClient.setAuthToken(body.token)

            val userDto = body.user!!
            val initials = userDto.fullName.split(" ")
                .mapNotNull { it.firstOrNull()?.uppercase() }
                .take(2).joinToString("")

            val user = UserEntity(
                id = userDto.id,
                role = userDto.role,
                fullName = userDto.fullName,
                phone = userDto.phone,
                email = userDto.email ?: "$cleanPhone@transport.et",
                status = userDto.status ?: "ACTIVE",
                licenseNumber = userDto.licenseNumber ?: "",
                avatarInitials = initials.ifBlank { "ET" },
                password = password,
                companyName = userDto.companyName ?: "",
                assignedVehiclePlate = userDto.assignedVehiclePlate ?: "",
                appliedRouteId = userDto.appliedRouteId ?: "",
                appliedRouteName = userDto.appliedRouteName ?: ""
            )
            dao.insertUser(user)

            // Caches pending subscription if returned by backend
            body.subscription?.let { subDto ->
                val sub = SubscriptionEntity(
                    id = subDto.id,
                    passengerId = user.id,
                    routeId = subDto.routeId,
                    pickupStopId = "stop_atlas",
                    destinationStopId = "stop_merkato",
                    morningSchedule = "06:30",
                    eveningSchedule = "17:30",
                    startDate = "",
                    endDate = "",
                    priceEtb = subDto.priceEtb ?: 2500.0,
                    paymentStatus = subDto.paymentStatus ?: "UNPAID",
                    subscriptionStatus = subDto.status ?: "PENDING",
                    vehicleId = "",
                    qrToken = subDto.qrToken ?: "",
                    daysRemaining = subDto.daysRemaining
                )
                dao.insertSubscription(sub)
            }

            logAction("ONLINE_REGISTER", user.id, user.role, "Registered on VPS Backend ($cleanPhone)")
            return user
        } else {
            val errorBody = response.errorBody()?.string()
            val parsedMsg = try {
                if (!errorBody.isNullOrBlank()) {
                    org.json.JSONObject(errorBody).optString("error")
                } else null
            } catch (e: Exception) { null }
            val errorMsg = parsedMsg ?: response.message().ifBlank { "Registration rejected by VPS server." }
            throw IllegalStateException(errorMsg)
        }
    }

    // Routes & Stops
    val allRoutes: Flow<List<RouteEntity>> = dao.getAllRoutes()
    val activeRoutes: Flow<List<RouteEntity>> = dao.getAllActiveRoutes()
    suspend fun getRouteById(routeId: String): RouteEntity? = dao.getRouteById(routeId)
    fun getStopsForRoute(routeId: String): Flow<List<RouteStopEntity>> = dao.getStopsForRoute(routeId)
    suspend fun getStopsForRouteSync(routeId: String): List<RouteStopEntity> = dao.getStopsForRouteSync(routeId)

    /**
     * Synchronize routes from VPS Backend into Room cache
     */
    suspend fun refreshRoutesFromBackend() {
        try {
            val response = apiService.getRoutes()
            if (!response.isSuccessful || response.body()?.success != true) return

            val routeList = response.body()?.routes ?: emptyList()
            for (summary in routeList) {
                // GET /api/routes/:id returns { success, route, stops }, not a bare RouteDto.
                // Fetch this authoritative detail response so newly created/edited stop lists
                // replace stale Room values instead of leaving the driver with demo stops.
                val detailsResponse = try {
                    apiService.getRouteById(summary.id)
                } catch (_: Exception) {
                    null
                }
                val details = detailsResponse?.takeIf { it.isSuccessful }?.body()
                val route = details?.route ?: summary
                val stops = if (details?.success == true) details.stops else (summary.stops ?: emptyList())

                dao.insertRoute(
                    RouteEntity(
                        id = route.id,
                        name = route.name,
                        nameAm = route.nameAm,
                        description = route.description ?: "",
                        morningDeparture = route.morningDeparture ?: "06:30",
                        eveningDeparture = route.eveningDeparture ?: "17:30",
                        distanceKm = route.distanceKm ?: 12.0,
                        basePriceEtb = route.basePriceEtb ?: 2500.0,
                        active = route.active ?: true
                    )
                )

                // The server is authoritative for stops. Remove obsolete cached stop rows
                // before inserting the current ordered route stop list.
                if (details?.success == true) dao.deleteStopsForRoute(route.id)
                stops.forEach { stop ->
                    dao.insertStop(
                        RouteStopEntity(
                            id = stop.id,
                            routeId = stop.routeId,
                            stopName = stop.stopName,
                            stopNameAm = stop.stopNameAm,
                            stopOrder = stop.stopOrder,
                            latitude = stop.latitude,
                            longitude = stop.longitude,
                            scheduledMorningTime = stop.scheduledMorningTime ?: route.morningDeparture.orEmpty(),
                            scheduledEveningTime = stop.scheduledEveningTime ?: route.eveningDeparture.orEmpty(),
                            maxCapacity = stop.maxCapacity ?: 25
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Keep last-known cache if the server is temporarily unavailable.
        }
    }

    suspend fun createRoute(
        name: String,
        nameAm: String,
        description: String,
        morningDeparture: String,
        eveningDeparture: String,
        distanceKm: Double,
        basePriceEtb: Double,
        stopsList: List<Pair<String, String>> = emptyList()
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

        try {
            apiService.createRoute(mapOf(
                "name" to name,
                "nameAm" to nameAm,
                "description" to description,
                "morningDeparture" to morningDeparture,
                "eveningDeparture" to eveningDeparture,
                "distanceKm" to distanceKm,
                "basePriceEtb" to basePriceEtb
            ))
        } catch (e: Exception) {
            // Offline
        }

        logAction("ROUTE_CREATED", "admin", "ADMIN", "Created route $name (ETB $basePriceEtb)")
        return route
    }

    suspend fun deleteRoute(routeId: String) {
        dao.deleteStopsForRoute(routeId)
        dao.deleteRoute(routeId)
        logAction("ROUTE_DELETED", "admin", "ADMIN", "Deleted route $routeId")
    }

    // Server-authoritative driver-owned vehicle and live location
    suspend fun fetchTrackedVehicleStatus(): TrackingVehicleResponse {
        val response = apiService.getTrackingVehicle()
        if (response.isSuccessful && response.body()?.success == true) {
            return response.body()!!
        }
        val errorBody = response.errorBody()?.string()
        val message = try {
            if (!errorBody.isNullOrBlank()) org.json.JSONObject(errorBody).optString("error") else null
        } catch (_: Exception) { null }
        throw IllegalStateException(message ?: response.body()?.message ?: "Could not load assigned vehicle tracking.")
    }

    suspend fun fetchTrackedVehicle(): TrackedVehicleDto? = fetchTrackedVehicleStatus().vehicle

    suspend fun submitDriverLocation(latitude: Double, longitude: Double, speed: Double = 0.0, currentStop: String = "") {
        val response = apiService.updateMyLocation(LocationUpdateRequest(latitude, longitude, speed, currentStop))
        if (!response.isSuccessful || response.body()?.success != true) {
            val errorBody = response.errorBody()?.string()
            val message = try {
                if (!errorBody.isNullOrBlank()) org.json.JSONObject(errorBody).optString("error") else null
            } catch (_: Exception) { null }
            throw IllegalStateException(message ?: response.body()?.message ?: "GPS update rejected by server.")
        }
    }

    suspend fun getDriverTripReadiness(routeId: String, direction: String): TripReadinessResponse {
        val response = apiService.getTripReadiness(routeId = routeId, direction = direction)
        if (response.isSuccessful && response.body()?.success == true) return response.body()!!
        val error = response.errorBody()?.string()
        val message = try {
            if (!error.isNullOrBlank()) org.json.JSONObject(error).optString("error") else null
        } catch (_: Exception) { null }
        throw IllegalStateException(message ?: response.body()?.error ?: "Could not check departure readiness.")
    }

    suspend fun startDriverTrip(routeId: String, vehicleId: String, direction: String, confirmedArrival: Boolean): TripDto {
        val response = apiService.startTrip(StartTripRequest(routeId = routeId, vehicleId = vehicleId, direction = direction, confirmedArrival = confirmedArrival))
        if (response.isSuccessful && response.body()?.success == true && response.body()?.trip != null) {
            return response.body()!!.trip!!
        }
        val errorBody = response.errorBody()?.string()
        val message = try {
            if (!errorBody.isNullOrBlank()) org.json.JSONObject(errorBody).optString("error") else null
        } catch (_: Exception) { null }
        throw IllegalStateException(message ?: response.body()?.error ?: "Could not start trip. Check your route assignment.")
    }

    // Vehicles & Fleet Management
    val allVehicles: Flow<List<VehicleEntity>> = dao.getAllVehicles()
    suspend fun getVehicleById(vehicleId: String): VehicleEntity? = dao.getVehicleById(vehicleId)
    suspend fun updateVehicle(vehicle: VehicleEntity) = dao.updateVehicle(vehicle)

    suspend fun refreshVehiclesFromBackend() {
        try {
            val response = apiService.getVehicles()
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.vehicles?.forEach { v ->
                    val entity = VehicleEntity(
                        id = v.id,
                        plateNumber = v.plateNumber,
                        model = v.model,
                        type = v.vehicleType,
                        capacity = v.capacityLimit,
                        assignedDriverId = v.driverId ?: "",
                        status = v.status ?: "IN_SERVICE",
                        insuranceExpiry = "2026-12-31",
                        inspectionExpiry = "2026-12-31"
                    )
                    dao.insertVehicle(entity)
                }
            }
        } catch (e: Exception) {
            // Keep cache
        }
    }

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

    /**
     * Refresh passenger subscription status from VPS Backend.
     */
    suspend fun refreshSubscriptionFromBackend(passengerId: String) {
        try {
            val response = apiService.getMySubscriptionStatus()
            if (response.isSuccessful && response.body()?.success == true) {
                val body = response.body()!!
                val subDto = body.subscription
                if (subDto != null) {
                    val entity = SubscriptionEntity(
                        id = subDto.id,
                        passengerId = passengerId,
                        routeId = subDto.routeId,
                        pickupStopId = "stop_atlas",
                        destinationStopId = "stop_merkato",
                        morningSchedule = subDto.morningSchedule ?: "06:30",
                        eveningSchedule = subDto.eveningSchedule ?: "17:30",
                        startDate = subDto.startDate ?: "",
                        endDate = subDto.endDate ?: "",
                        priceEtb = subDto.priceEtb ?: 2500.0,
                        paymentStatus = subDto.paymentStatus ?: "UNPAID",
                        subscriptionStatus = subDto.status ?: "PENDING",
                        vehicleId = subDto.vehicleId ?: "",
                        qrToken = subDto.qrToken ?: "",
                        daysRemaining = subDto.daysRemaining
                    )
                    dao.insertSubscription(entity)
                }
            }
        } catch (e: Exception) {
            // Keep Room cache
        }
    }

    // Telebirr hosted checkout status is always read from the VPS, never inferred from a browser redirect.
    suspend fun getTelebirrPaymentStatus(merchantOrderId: String): TelebirrPaymentStatusResponse? {
        return try {
            val response = apiService.getTelebirrPaymentStatus(merchantOrderId)
            response.body()?.takeIf { response.isSuccessful && it.success }
        } catch (e: Exception) {
            null
        }
    }

    // Payments
    val allPayments: Flow<List<PaymentTransactionEntity>> = dao.getAllPayments()
    fun getPaymentsForPassenger(passengerId: String): Flow<List<PaymentTransactionEntity>> = dao.getPaymentsForPassenger(passengerId)
    suspend fun recordPayment(payment: PaymentTransactionEntity) = dao.insertPayment(payment)

    suspend fun cachePaymentAndSubscription(payment: PaymentTransactionEntity, subscription: SubscriptionEntity) {
        dao.insertPayment(payment)
        dao.insertSubscription(subscription)
    }

    // Check-ins & Attendance
    val allCheckIns: Flow<List<CheckInRecordEntity>> = dao.getAllCheckIns()
    fun getCheckInsForPassenger(passengerId: String): Flow<List<CheckInRecordEntity>> = dao.getCheckInsForPassenger(passengerId)
    fun getCheckInsForTrip(tripId: String): Flow<List<CheckInRecordEntity>> = dao.getCheckInsForTrip(tripId)
    suspend fun getCheckInForPassengerOnTrip(tripId: String, passengerId: String) = dao.getCheckInForPassengerOnTrip(tripId, passengerId)
    suspend fun recordCheckIn(checkIn: CheckInRecordEntity) = dao.insertCheckIn(checkIn)
    fun getCheckedInCountForTrip(tripId: String): Flow<Int> = dao.getCheckedInCountForTrip(tripId)

    suspend fun recordRemoteCheckIn(
        tripId: String,
        passengerId: String,
        passengerName: String,
        routeId: String,
        stopName: String,
        vehicleId: String,
        driverId: String
    ) {
        val checkIn = CheckInRecordEntity(
            id = 0L,
            qrToken = "RP1:$passengerId",
            passengerId = passengerId,
            passengerName = passengerName,
            tripId = tripId,
            routeId = routeId,
            vehicleId = vehicleId,
            driverId = driverId,
            stopName = stopName,
            checkInTimestamp = SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date()),
            status = "PRESENT",
            latitude = 9.01,
            longitude = 38.75
        )
        dao.insertCheckIn(checkIn)
    }

    // Complaints
    val allComplaints: Flow<List<ComplaintEntity>> = dao.getAllComplaints()
    fun getComplaintsForPassenger(passengerId: String): Flow<List<ComplaintEntity>> = dao.getComplaintsForPassenger(passengerId)
    suspend fun submitComplaint(complaint: ComplaintEntity) {
        dao.insertComplaint(complaint)
        try {
            apiService.submitComplaint(SubmitComplaintRequest(complaint.category, complaint.text))
        } catch (e: Exception) {
            // Saved locally
        }
    }
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

    suspend fun refreshNotificationsFromBackend() {
        try {
            val response = apiService.getNotifications()
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.notifications?.forEach { n ->
                    val entity = NotificationEntity(
                        id = n.id,
                        title = n.title,
                        message = n.message,
                        targetAudience = n.targetAudience,
                        timestamp = n.timestamp ?: SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(Date()),
                        type = n.type ?: "ALERT",
                        senderName = n.senderName ?: "Transport Operations"
                    )
                    dao.insertNotification(entity)
                }
            }
        } catch (e: Exception) {
            // Keep cache
        }
    }

    suspend fun sendNotification(
        title: String,
        message: String,
        targetAudience: String,
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
        try {
            apiService.sendBroadcastNotification(SendNotificationRequest(title, message, targetAudience, type))
        } catch (e: Exception) {
            // Local notification dispatched
        }
        logAction("NOTIFICATION_DISPATCHED", "admin", "ADMIN", "Dispatched to $targetAudience: '$title'")
    }

    // Offline Helper for QR Verification
    suspend fun validateLocalQr(
        qrToken: String,
        currentTripId: String,
        currentVehicleId: String,
        currentStopName: String,
        driverId: String,
        vehicleCapacity: Int,
        currentPassengerCount: Int
    ): QrValidationResult {
        if (currentPassengerCount >= vehicleCapacity) {
            return QrValidationResult.Invalid(
                reason = "reason_capacity_full",
                details = "Vehicle capacity limit reached ($currentPassengerCount/$vehicleCapacity seats full)."
            )
        }

        val sub = dao.getSubscriptionByQrToken(qrToken)
            ?: return QrValidationResult.Invalid("reason_not_subscribed", "Passenger not subscribed.")

        if (sub.subscriptionStatus != "ACTIVE" || sub.paymentStatus != "PAID") {
            return QrValidationResult.Invalid("reason_subscription_expired", "Pass is unpaid or expired.")
        }

        val passenger = dao.getUserById(sub.passengerId)
            ?: return QrValidationResult.Invalid("reason_qr_corrupted", "Passenger profile not found.")

        recordRemoteCheckIn(
            tripId = currentTripId,
            passengerId = passenger.id,
            passengerName = passenger.fullName,
            routeId = sub.routeId,
            stopName = currentStopName,
            vehicleId = currentVehicleId,
            driverId = driverId
        )

        return QrValidationResult.Valid(
            passenger = passenger,
            subscription = sub,
            stopName = currentStopName,
            destinationName = "Merkato Terminal",
            vehiclePlate = "3-AA-34921",
            currentOccupancy = currentPassengerCount + 1,
            capacityLimit = vehicleCapacity
        )
    }

    // Offline Helper for Telebirr Payment
    suspend fun processOfflinePayment(
        passengerId: String,
        routeId: String,
        pickupStopId: String,
        destinationStopId: String,
        morningSchedule: String,
        eveningSchedule: String,
        amountEtb: Double,
        phoneNumber: String,
        vehicleId: String,
        idempotencyKey: String
    ): TelebirrPaymentResult {
        val txnId = "tx_tb_" + UUID.randomUUID().toString().take(8)
        val txnRef = "TB-ET-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + "-" + (1000..9999).random()
        val nowStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())
        val subId = "sub_" + passengerId + "_" + SimpleDateFormat("yyyyMM", Locale.US).format(Date())
        val signedToken = "RP1:$subId:$passengerId:$routeId:${System.currentTimeMillis() + 30L * 86400000}:OFFLINE_PASS"

        val newSub = SubscriptionEntity(
            id = subId,
            passengerId = passengerId,
            routeId = routeId,
            pickupStopId = pickupStopId.ifBlank { "stop_atlas" },
            destinationStopId = destinationStopId.ifBlank { "stop_merkato" },
            morningSchedule = morningSchedule.ifBlank { "06:30" },
            eveningSchedule = eveningSchedule.ifBlank { "17:30" },
            startDate = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date()),
            endDate = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(System.currentTimeMillis() + 30L * 86400000)),
            priceEtb = amountEtb,
            paymentStatus = "PAID",
            subscriptionStatus = "ACTIVE",
            vehicleId = vehicleId.ifBlank { "3-AA-34921" },
            qrToken = signedToken,
            daysRemaining = 30
        )

        val transaction = PaymentTransactionEntity(
            id = txnId,
            transactionRef = txnRef,
            passengerId = passengerId,
            subscriptionId = subId,
            amountEtb = amountEtb,
            provider = "Telebirr",
            status = "SUCCESS",
            timestamp = nowStr,
            paymentPhone = phoneNumber,
            receiptNumber = "REC-ET-${(10000..99999).random()}"
        )

        dao.insertPayment(transaction)
        dao.insertSubscription(newSub)

        return TelebirrPaymentResult.Success(transaction, newSub)
    }
}
