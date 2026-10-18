package com.example.data.api

import retrofit2.Response
import retrofit2.http.*

interface RoutePassApiService {

    // -------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/request-otp")
    suspend fun requestOtp(@Body request: Map<String, String>): Response<OtpResponse>

    @POST("api/auth/verify-otp")
    suspend fun verifyOtp(@Body request: Map<String, String>): Response<OtpResponse>

    @GET("api/auth/me")
    suspend fun getCurrentUser(): Response<AuthResponse>

    // -------------------------------------------------------------
    // Transit Routes & Stops
    // -------------------------------------------------------------
    @GET("api/routes")
    suspend fun getRoutes(): Response<RoutesResponse>

    @GET("api/companies")
    suspend fun getTransportCompanies(): Response<TransportCompaniesResponse>

    // Native transport-company portal (kept separate from system admin auth)
    @POST("api/companies/register")
    suspend fun registerTransportCompany(@Body request: Map<String, @JvmSuppressWildcards Any>): Response<Map<String, Any>>

    @POST("api/companies/login")
    suspend fun loginTransportCompany(@Body request: Map<String, @JvmSuppressWildcards Any>): Response<Map<String, Any>>

    @GET("api/companies/me")
    suspend fun getCompanyProfile(): Response<Map<String, Any>>

    @GET("api/companies/operations/summary")
    suspend fun getCompanySummary(): Response<Map<String, Any>>

    @GET("api/companies/operations/fleet")
    suspend fun getCompanyFleet(): Response<Map<String, Any>>

    @GET("api/companies/operations/drivers")
    suspend fun getCompanyDrivers(): Response<Map<String, Any>>

    @PATCH("api/companies/operations/drivers/{id}/approval")
    suspend fun updateCompanyDriverApproval(@Path("id") driverId: String, @Body request: Map<String, @JvmSuppressWildcards Any>): Response<Map<String, Any>>

    @GET("api/companies/operations/routes")
    suspend fun getCompanyRoutes(): Response<Map<String, Any>>

    @POST("api/companies/operations/routes")
    suspend fun createCompanyRoute(@Body request: Map<String, @JvmSuppressWildcards Any>): Response<Map<String, Any>>

    @PUT("api/companies/operations/routes/{id}")
    suspend fun updateCompanyRoute(@Path("id") routeId: String, @Body request: Map<String, @JvmSuppressWildcards Any>): Response<Map<String, Any>>

    @POST("api/companies/operations/vehicles")
    suspend fun createCompanyVehicle(@Body request: Map<String, @JvmSuppressWildcards Any>): Response<Map<String, Any>>

    @POST("api/companies/operations/assignments")
    suspend fun assignCompanyDriverVehicleRoute(@Body request: Map<String, @JvmSuppressWildcards Any>): Response<Map<String, Any>>

    @DELETE("api/companies/operations/assignments/{id}")
    suspend fun unassignCompanyVehicle(@Path("id") assignmentId: String): Response<Map<String, Any>>

    @GET("api/companies/operations/bookings")
    suspend fun getCompanyBookings(): Response<Map<String, Any>>

    @GET("api/routes/{id}")
    suspend fun getRouteById(@Path("id") routeId: String): Response<RouteDetailsResponse>

    @POST("api/routes")
    suspend fun createRoute(@Body route: Map<String, Any>): Response<GenericResponse>

    // -------------------------------------------------------------
    // Commuter Subscriptions
    // -------------------------------------------------------------
    @GET("api/subscriptions/my-status")
    suspend fun getMySubscriptionStatus(): Response<SubscriptionStatusResponse>

    @POST("api/subscriptions/subscribe")
    suspend fun subscribeRoute(@Body request: SubscribeRequest): Response<SubscribeResponse>

    @POST("api/subscriptions/telebirr/pay")
    suspend fun payTelebirr(
        @Body request: TelebirrPayRequest,
        @Header("x-idempotency-key") idempotencyKey: String? = null
    ): Response<TelebirrPayResponse>

    @GET("api/subscriptions/telebirr/status/{merchantOrderId}")
    suspend fun getTelebirrPaymentStatus(
        @Path("merchantOrderId") merchantOrderId: String
    ): Response<TelebirrPaymentStatusResponse>

    @GET("api/subscriptions/all")
    suspend fun getAllSubscriptions(): Response<Map<String, Any>>

    // -------------------------------------------------------------
    // Vehicles & Fleet Management
    // -------------------------------------------------------------
    @GET("api/vehicles")
    suspend fun getVehicles(): Response<VehiclesResponse>

    @GET("api/vehicles/tracking")
    suspend fun getTrackingVehicle(): Response<TrackingVehicleResponse>

    @POST("api/vehicles/my-location")
    suspend fun updateMyLocation(@Body request: LocationUpdateRequest): Response<GenericResponse>

    @GET("api/vehicles/{id}")
    suspend fun getVehicleById(@Path("id") vehicleId: String): Response<Map<String, Any>>

    @PATCH("api/vehicles/{id}/type")
    suspend fun updateVehicleType(
        @Path("id") vehicleId: String,
        @Body request: UpdateVehicleTypeRequest
    ): Response<GenericResponse>

    @POST("api/vehicles/{id}/location")
    suspend fun updateVehicleLocation(
        @Path("id") vehicleId: String,
        @Body request: LocationUpdateRequest
    ): Response<GenericResponse>

    // -------------------------------------------------------------
    // Driver Trips
    // -------------------------------------------------------------
    @POST("api/trips/start")
    suspend fun startTrip(@Body request: StartTripRequest): Response<StartTripResponse>

    @GET("api/trips/active")
    suspend fun getActiveTrip(): Response<ActiveTripResponse>

    @POST("api/trips/{id}/stop-arrival")
    suspend fun recordStopArrival(
        @Path("id") tripId: String,
        @Body request: StopArrivalRequest
    ): Response<GenericResponse>

    @POST("api/trips/{id}/end")
    suspend fun endTrip(@Path("id") tripId: String): Response<GenericResponse>

    // -------------------------------------------------------------
    // Check-in & Boarding
    // -------------------------------------------------------------
    @POST("api/checkins/scan")
    suspend fun scanCheckin(@Body request: ScanCheckinRequest): Response<ScanCheckinResponse>

    @GET("api/checkins/recent")
    suspend fun getRecentCheckins(): Response<CheckinsResponse>

    @GET("api/checkins/trip/{tripId}")
    suspend fun getCheckinsForTrip(@Path("tripId") tripId: String): Response<Map<String, Any>>

    // -------------------------------------------------------------
    // Operational Notifications
    // -------------------------------------------------------------
    @GET("api/notifications")
    suspend fun getNotifications(@Query("audience") audience: String? = null): Response<NotificationsResponse>

    @POST("api/notifications/broadcast")
    suspend fun sendBroadcastNotification(@Body request: SendNotificationRequest): Response<GenericResponse>

    // -------------------------------------------------------------
    // Passenger Complaints
    // -------------------------------------------------------------
    @POST("api/complaints")
    suspend fun submitComplaint(@Body request: SubmitComplaintRequest): Response<GenericResponse>

    // -------------------------------------------------------------
    // Admin Operations
    // -------------------------------------------------------------
    @GET("api/admin/stats")
    suspend fun getAdminStats(): Response<AdminStatsResponse>

    @GET("api/admin/drivers")
    suspend fun getAdminDrivers(): Response<Map<String, Any>>

    @GET("api/admin/subscriptions")
    suspend fun getAdminSubscriptions(): Response<Map<String, Any>>

    @GET("api/admin/payments")
    suspend fun getAdminPayments(): Response<Map<String, Any>>

    @GET("api/admin/complaints")
    suspend fun getAdminComplaints(): Response<Map<String, Any>>

    @GET("api/admin/audit-logs")
    suspend fun getAdminAuditLogs(): Response<Map<String, Any>>

    // -------------------------------------------------------------
    // Health Check
    // -------------------------------------------------------------
    @GET("api/health")
    suspend fun checkHealth(): Response<Map<String, Any>>
}
