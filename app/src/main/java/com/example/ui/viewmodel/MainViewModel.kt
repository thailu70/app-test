package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.localization.AppLanguage
import com.example.core.payment.TelebirrGateway
import com.example.core.payment.TelebirrPaymentResult
import com.example.core.qr.QrSecurityEngine
import com.example.core.qr.QrValidationResult
import com.example.data.api.LiveTrackingWebSocket
import com.example.data.api.TrackedVehicleDto
import com.example.data.api.RosterPersonDto
import com.example.data.api.RegistrationUploadDto
import com.example.data.entity.*
import com.example.data.repository.TransportRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

enum class AppRole {
    PASSENGER,
    DRIVER,
    ADMIN
}

enum class NetworkStatus {
    ONLINE,
    OFFLINE,
    SYNCING
}

enum class NavigationStepState {
    EN_ROUTE,
    ARRIVED,
    COMPLETED,
    SKIPPED
}

data class DriverTripState(
    val tripId: String = "",
    val vehicleId: String = "",
    val routeId: String = "",
    val routeName: String = "",
    val vehiclePlate: String = "",
    val vehicleType: String = "MINIBUS_14",
    val vehicleCapacity: Int = 14,
    val departureTime: String = "06:30",
    val currentStopIndex: Int = 0,
    val isNavigating: Boolean = false,
    val isArrivedAtStop: Boolean = false,
    val stopStates: Map<Int, NavigationStepState> = emptyMap(),
    val currentDistanceKm: Double = 0.0,
    val currentEtaMins: Int = 0,
    val checkedInCount: Int = 0,
    val totalPassengers: Int = 14
)

class MainViewModel(private val repository: TransportRepository) : ViewModel() {

    // Language
    private val _currentLanguage = MutableStateFlow(AppLanguage.ENGLISH)
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    // Authentication Session State
    private val _currentUser = MutableStateFlow<UserEntity?>(null)
    val currentUser: StateFlow<UserEntity?> = _currentUser.asStateFlow()

    private var passengerSubscriptionRefreshJob: Job? = null

    private fun startPassengerSubscriptionRefresh(passengerId: String) {
        passengerSubscriptionRefreshJob?.cancel()
        passengerSubscriptionRefreshJob = viewModelScope.launch {
            while (true) {
                try {
                    repository.refreshSubscriptionFromBackend(passengerId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the last known subscription visible; retry on the next interval.
                }
                delay(15000)
            }
        }
    }

    private fun stopPassengerSubscriptionRefresh() {
        passengerSubscriptionRefreshJob?.cancel()
        passengerSubscriptionRefreshJob = null
    }


    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _currentRole = MutableStateFlow(AppRole.PASSENGER)
    val currentRole: StateFlow<AppRole> = _currentRole.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _registrationMessage = MutableStateFlow<String?>(null)
    val registrationMessage: StateFlow<String?> = _registrationMessage.asStateFlow()

    fun clearRegistrationMessage() { _registrationMessage.value = null }

    private val _assignedRoster = MutableStateFlow<List<RosterPersonDto>>(emptyList())
    val assignedRoster: StateFlow<List<RosterPersonDto>> = _assignedRoster.asStateFlow()

    private val _rosterMessage = MutableStateFlow("Your assigned people will appear when a monthly subscription is active.")
    val rosterMessage: StateFlow<String> = _rosterMessage.asStateFlow()

    fun refreshAssignedRoster() {
        viewModelScope.launch {
            try {
                val result = repository.fetchAssignedRoster()
                _assignedRoster.value = result.people
                _rosterMessage.value = result.message ?: ""
            } catch (error: Exception) {
                _rosterMessage.value = error.message ?: "Assigned roster is temporarily unavailable."
            }
        }
    }

    private val _networkStatus = MutableStateFlow(NetworkStatus.ONLINE)
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    // Notification Tray State
    private val _isNotificationTrayOpen = MutableStateFlow(false)
    val isNotificationTrayOpen: StateFlow<Boolean> = _isNotificationTrayOpen.asStateFlow()

    // Create Route Dialog State
    private val _isCreateRouteDialogOpen = MutableStateFlow(false)
    val isCreateRouteDialogOpen: StateFlow<Boolean> = _isCreateRouteDialogOpen.asStateFlow()

    // Dynamic passenger ID based on logged in user
    val currentPassengerId: String
        get() = _currentUser.value?.id ?: ""

    val currentDriverId: String
        get() = _currentUser.value?.id ?: ""

    init {
        viewModelScope.launch {
            try {
                repository.refreshRoutesFromBackend()
                repository.refreshVehiclesFromBackend()
                repository.refreshNotificationsFromBackend()
                LiveTrackingWebSocket.connect()
            } catch (e: Exception) {
                // Offline fallback
            }
        }
    }

    // Passenger Flows
    val activeSubscription: StateFlow<SubscriptionEntity?> = _currentUser.flatMapLatest { user ->
        val id = user?.id ?: ""
        if (id.isBlank()) flowOf(null) else repository.getSubscriptionForPassenger(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val passengerPayments: StateFlow<List<PaymentTransactionEntity>> = _currentUser.flatMapLatest { user ->
        val id = user?.id ?: ""
        if (id.isBlank()) flowOf(emptyList()) else repository.getPaymentsForPassenger(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val passengerCheckIns: StateFlow<List<CheckInRecordEntity>> = _currentUser.flatMapLatest { user ->
        val id = user?.id ?: ""
        if (id.isBlank()) flowOf(emptyList()) else repository.getCheckInsForPassenger(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val passengerComplaints: StateFlow<List<ComplaintEntity>> = _currentUser.flatMapLatest { user ->
        val id = user?.id ?: ""
        if (id.isBlank()) flowOf(emptyList()) else repository.getComplaintsForPassenger(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // All System Data Flows
    val allRoutes: StateFlow<List<RouteEntity>> = repository.allRoutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeRoutes: StateFlow<List<RouteEntity>> = repository.activeRoutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allVehicles: StateFlow<List<VehicleEntity>> = repository.allVehicles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPlans: StateFlow<List<SubscriptionPlanEntity>> = repository.allPlans
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSubscriptions: StateFlow<List<SubscriptionEntity>> = repository.allSubscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allPayments: StateFlow<List<PaymentTransactionEntity>> = repository.allPayments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allCheckIns: StateFlow<List<CheckInRecordEntity>> = repository.allCheckIns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allComplaints: StateFlow<List<ComplaintEntity>> = repository.allComplaints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentAuditLogs: StateFlow<List<AuditLogEntity>> = repository.recentAuditLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Driver Trip State
    private val _driverTrip = MutableStateFlow(DriverTripState())
    val driverTrip: StateFlow<DriverTripState> = _driverTrip.asStateFlow()

    val routeStops: StateFlow<List<RouteStopEntity>> = _driverTrip
        .map { it.routeId }
        .distinctUntilChanged()
        .flatMapLatest { routeId -> if (routeId.isBlank()) flowOf(emptyList()) else repository.getStopsForRoute(routeId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _driverActionMessage = MutableStateFlow<String?>(null)
    val driverActionMessage: StateFlow<String?> = _driverActionMessage.asStateFlow()

    private val _trackingMessage = MutableStateFlow("Tracking status is loading.")
    val trackingMessage: StateFlow<String> = _trackingMessage.asStateFlow()

    val trackedVehicle: StateFlow<TrackedVehicleDto?> = _currentUser.flatMapLatest { user ->
        if (user == null || (user.role != "DRIVER" && user.role != "PASSENGER")) {
            flow {
                _trackingMessage.value = "Sign in as a driver or passenger to view vehicle tracking."
                emit(null)
            }
        } else {
            flow {
                while (true) {
                    try {
                        val response = repository.fetchTrackedVehicleStatus()
                        val vehicle = response.vehicle
                        _trackingMessage.value = response.message ?: when {
                            vehicle == null -> "No vehicle is available for this account yet."
                            vehicle.hasGpsLocation && !vehicle.lastGpsAt.isNullOrBlank() ->
                                "RoutePass server received a GPS report at ${vehicle.lastGpsAt}."
                            else -> "Vehicle found, but RoutePass has not received a GPS report for it yet."
                        }
                        emit(vehicle)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _trackingMessage.value = "Tracking refresh failed: ${error.message ?: "server unavailable"}"
                        emit(null)
                    }
                    delay(5000)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Notifications Feeds
    val allNotifications: StateFlow<List<NotificationEntity>> = repository.allNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val passengerNotifications: StateFlow<List<NotificationEntity>> = repository.passengerNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transporterNotifications: StateFlow<List<NotificationEntity>> = repository.transporterNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Scanner UI State
    private val _isScannerOpen = MutableStateFlow(false)
    val isScannerOpen: StateFlow<Boolean> = _isScannerOpen.asStateFlow()

    private val _scanResult = MutableStateFlow<QrValidationResult?>(null)
    val scanResult: StateFlow<QrValidationResult?> = _scanResult.asStateFlow()

    // Telebirr Payment Dialog UI State
    private val _isTelebirrDialogOpen = MutableStateFlow(false)
    val isTelebirrDialogOpen: StateFlow<Boolean> = _isTelebirrDialogOpen.asStateFlow()

    private val _isProcessingPayment = MutableStateFlow(false)
    val isProcessingPayment: StateFlow<Boolean> = _isProcessingPayment.asStateFlow()

    private val _paymentMessage = MutableStateFlow<String?>(null)
    val paymentMessage: StateFlow<String?> = _paymentMessage.asStateFlow()

    // View Receipt Dialog
    private val _selectedReceipt = MutableStateFlow<PaymentTransactionEntity?>(null)
    val selectedReceipt: StateFlow<PaymentTransactionEntity?> = _selectedReceipt.asStateFlow()

    // Complaint Dialog
    private val _isComplaintDialogOpen = MutableStateFlow(false)
    val isComplaintDialogOpen: StateFlow<Boolean> = _isComplaintDialogOpen.asStateFlow()

    init {
        // App starts on independent login portal for Passenger, Driver, and Operator
    }

    // Language Toggle
    fun toggleLanguage() {
        _currentLanguage.value = if (_currentLanguage.value == AppLanguage.ENGLISH) {
            AppLanguage.AMHARIC
        } else {
            AppLanguage.ENGLISH
        }
    }

    // Authentication Actions
    fun login(identifier: String, password: String, role: AppRole) {
        viewModelScope.launch {
            _authError.value = null
            val requiredUploads = when (role) {
                AppRole.PASSENGER -> setOf("PROFILE_PHOTO")
                AppRole.DRIVER -> setOf("PROFILE_PHOTO", "DRIVER_LICENSE_DOCUMENT", "NATIONAL_ID_DOCUMENT", "VEHICLE_PHOTO", "VEHICLE_TRADE_LICENSE")
                AppRole.ADMIN -> emptySet()
            }
            val missingUploads = requiredUploads - uploads.map { it.documentType }.toSet()
            if (missingUploads.isNotEmpty()) {
                _authError.value = "Please select all required registration photos and documents: " + missingUploads.joinToString { it.replace("_", " ").lowercase().replaceFirstChar { ch -> ch.uppercase() } }
                return@launch
            }
            val roleStr = when (role) {
                AppRole.PASSENGER -> "PASSENGER"
                AppRole.DRIVER -> "DRIVER"
                AppRole.ADMIN -> "ADMIN"
            }
            val user = repository.authenticate(identifier, roleStr, password)
            if (user != null) {
                _currentUser.value = user
                _currentRole.value = role
                _assignedRoster.value = emptyList()
                refreshAssignedRoster()
                _isAuthenticated.value = true
                if (role == AppRole.DRIVER) {
                    _driverTrip.value = DriverTripState(
                        routeId = user.appliedRouteId,
                        routeName = user.appliedRouteName,
                        vehiclePlate = user.assignedVehiclePlate
                    )
                    try {
                        val vehicle = repository.fetchTrackedVehicle()
                        if (vehicle != null) _driverTrip.update {
                            it.copy(
                                vehicleId = vehicle.id,
                                vehiclePlate = vehicle.plateNumber,
                                vehicleType = vehicle.vehicleType,
                                vehicleCapacity = vehicle.capacityLimit,
                                totalPassengers = vehicle.capacityLimit,
                                departureTime = vehicle.morningDeparture ?: "06:30",
                                routeId = vehicle.assignedRouteId.orEmpty(),
                                routeName = vehicle.routeName.orEmpty()
                            )
                        }
                    } catch (_: Exception) {
                        // UI will show registration or route assignment status instead of fabricated trip data.
                    }
                } else if (role == AppRole.PASSENGER) {
                    startPassengerSubscriptionRefresh(user.id)
                } else {
                    stopPassengerSubscriptionRefresh()
                }
                repository.logAction("LOGIN_SUCCESS", user.id, roleStr, "User logged in as $roleStr")
            } else {
                _authError.value = repository.lastAuthError
                    ?: "Account not found for $identifier as ${role.name}. Please check credentials or Register."
            }
        }
    }

    fun register(
        fullName: String,
        phone: String,
        email: String,
        password: String,
        role: AppRole,
        adminSecret: String = "",
        licenseNumber: String = "",
        companyName: String = "",
        assignedVehiclePlate: String = "",
        vehicleModel: String = "",
        vehicleType: String = "MINIBUS_14",
        appliedRouteId: String = "",
        appliedRouteName: String = "",
        uploads: List<RegistrationUploadDto> = emptyList()
    ) {
        viewModelScope.launch {
            _authError.value = null
            _registrationMessage.value = null
            if (fullName.isBlank() || phone.isBlank()) {
                _authError.value = "Full Name and Phone Number are required."
                return@launch
            }
            if (role == AppRole.PASSENGER && appliedRouteId.isBlank()) {
                _authError.value = "Please select the route you want to subscribe to."
                return@launch
            }
            if (role == AppRole.DRIVER &&
                (licenseNumber.isBlank() || assignedVehiclePlate.isBlank() || vehicleModel.isBlank())
            ) {
                _authError.value = "Enter your commercial licence, your own vehicle plate and vehicle model."
                return@launch
            }
            val roleStr = when (role) {
                AppRole.PASSENGER -> "PASSENGER"
                AppRole.DRIVER -> "DRIVER"
                AppRole.ADMIN -> "ADMIN"
            }
            try {
                val user = repository.registerUser(
                    fullName = fullName,
                    phone = phone,
                    email = email,
                    password = password,
                    role = roleStr,
                    adminSecret = adminSecret,
                    licenseNumber = licenseNumber,
                    companyName = companyName,
                    assignedVehiclePlate = assignedVehiclePlate,
                    vehicleModel = vehicleModel,
                    vehicleType = vehicleType,
                    appliedRouteId = appliedRouteId,
                    appliedRouteName = appliedRouteName,
                    uploads = uploads
                )
                if (role == AppRole.DRIVER) {
                    _registrationMessage.value = "Your registration photos and documents were submitted. We will review your driving licence, ID and vehicle trade licence, approve your account and assign your route. You can sign in after approval."
                    return@launch
                }
                _currentUser.value = user
                _currentRole.value = role
                _isAuthenticated.value = true
                if (role == AppRole.DRIVER) {
                    _driverTrip.value = DriverTripState(
                        routeId = "",
                        routeName = "",
                        vehiclePlate = user.assignedVehiclePlate
                    )
                    try {
                        val vehicle = repository.fetchTrackedVehicle()
                        if (vehicle != null) _driverTrip.update {
                            it.copy(
                                vehicleId = vehicle.id,
                                vehiclePlate = vehicle.plateNumber,
                                vehicleType = vehicle.vehicleType,
                                vehicleCapacity = vehicle.capacityLimit,
                                totalPassengers = vehicle.capacityLimit,
                                departureTime = vehicle.morningDeparture ?: "06:30"
                            )
                        }
                    } catch (_: Exception) {}
                } else if (role == AppRole.PASSENGER) {
                    startPassengerSubscriptionRefresh(user.id)
                } else {
                    stopPassengerSubscriptionRefresh()
                }
            } catch (e: Exception) {
                _authError.value = e.message ?: "Registration failed. Check the information and try again."
            }
        }
    }

    fun logout() {
        stopPassengerSubscriptionRefresh()
        _currentUser.value = null
        _assignedRoster.value = emptyList()
        _isAuthenticated.value = false
        _authError.value = null
    }

    fun setRole(role: AppRole) {
        _currentRole.value = role
    }

    fun toggleNetworkMode() {
        viewModelScope.launch {
            when (_networkStatus.value) {
                NetworkStatus.ONLINE -> _networkStatus.value = NetworkStatus.OFFLINE
                NetworkStatus.OFFLINE -> {
                    _networkStatus.value = NetworkStatus.SYNCING
                    delay(1500)
                    _networkStatus.value = NetworkStatus.ONLINE
                }
                NetworkStatus.SYNCING -> _networkStatus.value = NetworkStatus.ONLINE
            }
        }
    }

    // Notification Tray
    fun openNotificationTray() {
        _isNotificationTrayOpen.value = true
    }

    fun closeNotificationTray() {
        _isNotificationTrayOpen.value = false
    }

    fun sendBroadcastNotification(
        title: String,
        message: String,
        targetAudience: String,
        type: String = "ALERT"
    ) {
        viewModelScope.launch {
            val sender = _currentUser.value?.companyName?.ifBlank { "Transport Operations" } ?: "Transport Operations"
            repository.sendNotification(
                title = title,
                message = message,
                targetAudience = targetAudience,
                type = type,
                senderName = sender
            )
        }
    }

    // Route Management
    fun openCreateRouteDialog() {
        _isCreateRouteDialogOpen.value = true
    }

    fun closeCreateRouteDialog() {
        _isCreateRouteDialogOpen.value = false
    }

    fun createRoute(
        name: String,
        nameAm: String,
        description: String,
        morning: String,
        evening: String,
        distanceKm: Double,
        priceEtb: Double,
        stops: List<Pair<String, String>>
    ) {
        viewModelScope.launch {
            repository.createRoute(
                name = name,
                nameAm = nameAm,
                description = description,
                morningDeparture = morning,
                eveningDeparture = evening,
                distanceKm = distanceKm,
                basePriceEtb = priceEtb,
                stopsList = stops
            )
            _isCreateRouteDialogOpen.value = false
        }
    }

    fun deleteRoute(routeId: String) {
        viewModelScope.launch {
            repository.deleteRoute(routeId)
        }
    }

    // Start a server-authoritative trip using the driver's own registered vehicle and admin-assigned route.
    fun startNavigation() {
        viewModelScope.launch {
            _driverActionMessage.value = null
            try {
                val user = _currentUser.value ?: throw IllegalStateException("Sign in as a driver first.")
                val vehicle = repository.fetchTrackedVehicle()
                    ?: throw IllegalStateException("Your owned vehicle is not registered. Sign out and register it again.")
                val routeId = vehicle.assignedRouteId?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Your vehicle has not been assigned a route yet. Contact the RoutePass administrator.")
                val trip = repository.startDriverTrip(routeId, vehicle.id)
                val stops = repository.getStopsForRouteSync(routeId)
                _driverTrip.value = DriverTripState(
                    tripId = trip.id,
                    vehicleId = vehicle.id,
                    routeId = routeId,
                    routeName = trip.routeName ?: vehicle.routeName.orEmpty(),
                    vehiclePlate = trip.plateNumber ?: vehicle.plateNumber,
                    vehicleType = trip.vehicleType ?: vehicle.vehicleType,
                    vehicleCapacity = trip.capacityLimit ?: vehicle.capacityLimit,
                    departureTime = vehicle.morningDeparture ?: "06:30",
                    currentStopIndex = 0,
                    isNavigating = true,
                    isArrivedAtStop = false,
                    checkedInCount = trip.currentOccupancy,
                    totalPassengers = trip.capacityLimit ?: vehicle.capacityLimit
                )
                _driverActionMessage.value = if (stops.isEmpty()) "Trip started, but this route has no stop list configured yet." else "Live trip started. GPS sharing is available while this screen is open."
                repository.logAction("DRIVER_NAVIGATION_START", user.id, "DRIVER", "Started live trip ${trip.id} on route ${trip.routeId}")
            } catch (e: Exception) {
                _driverActionMessage.value = e.message ?: "Could not start the trip."
            }
        }
    }

    fun submitDriverLocation(
        latitude: Double,
        longitude: Double,
        speed: Double = 0.0,
        currentStop: String = "",
        onResult: ((Boolean, String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                repository.submitDriverLocation(latitude, longitude, speed, currentStop)
                val message = "RoutePass server accepted the live GPS update."
                _driverActionMessage.value = message
                onResult?.invoke(true, message)
            } catch (e: Exception) {
                val message = e.message ?: "GPS update failed."
                _driverActionMessage.value = message
                onResult?.invoke(false, message)
            }
        }
    }

    fun arriveAtCurrentStop() {
        _driverTrip.update {
            it.copy(
                isArrivedAtStop = true,
                currentDistanceKm = 0.0,
                currentEtaMins = 0
            )
        }
        viewModelScope.launch {
            val stops = routeStops.value
            val currentStop = stops.getOrNull(_driverTrip.value.currentStopIndex)
            repository.logAction("DRIVER_ARRIVED_STOP", currentDriverId, "DRIVER", "Arrived at stop ${currentStop?.stopName ?: "Atlas"}")
        }
    }

    fun nextStop() {
        val stops = routeStops.value
        val currentIndex = _driverTrip.value.currentStopIndex
        val updatedStates = _driverTrip.value.stopStates.toMutableMap()
        updatedStates[currentIndex] = NavigationStepState.COMPLETED

        val nextIndex = if (currentIndex + 1 < stops.size) currentIndex + 1 else currentIndex

        _driverTrip.update {
            it.copy(
                currentStopIndex = nextIndex,
                isArrivedAtStop = false,
                currentDistanceKm = if (nextIndex < stops.size - 1) 2.1 else 0.8,
                currentEtaMins = if (nextIndex < stops.size - 1) 6 else 3,
                stopStates = updatedStates
            )
        }
    }

    fun skipCurrentStop() {
        val stops = routeStops.value
        val currentIndex = _driverTrip.value.currentStopIndex
        val updatedStates = _driverTrip.value.stopStates.toMutableMap()
        updatedStates[currentIndex] = NavigationStepState.SKIPPED

        val nextIndex = if (currentIndex + 1 < stops.size) currentIndex + 1 else currentIndex
        _driverTrip.update {
            it.copy(
                currentStopIndex = nextIndex,
                isArrivedAtStop = false,
                stopStates = updatedStates
            )
        }
    }

    // QR Verification for Driver
    fun openScanner() {
        _scanResult.value = null
        _isScannerOpen.value = true
    }

    fun closeScanner() {
        _isScannerOpen.value = false
        _scanResult.value = null
    }

    fun setVehicleType(type: String, capacity: Int) {
        _driverTrip.update {
            it.copy(
                vehicleType = type,
                vehicleCapacity = capacity,
                totalPassengers = capacity
            )
        }
    }

    fun verifyQrToken(rawToken: String) {
        viewModelScope.launch {
            val stops = routeStops.value
            val currentStop = stops.getOrNull(_driverTrip.value.currentStopIndex)?.stopName ?: "Bole Atlas"
            val tripState = _driverTrip.value
            if (!tripState.isNavigating || tripState.tripId.isBlank() || tripState.vehicleId.isBlank()) {
                _scanResult.value = QrValidationResult.Invalid(
                    "reason_qr_corrupted",
                    "Start a live trip on your own assigned vehicle before scanning passenger passes."
                )
                return@launch
            }
            val result = QrSecurityEngine.validateToken(
                qrToken = rawToken,
                currentTripId = tripState.tripId,
                currentRouteId = tripState.routeId,
                currentVehicleId = tripState.vehicleId,
                currentStopName = currentStop,
                driverId = currentDriverId,
                repository = repository,
                vehicleCapacity = tripState.vehicleCapacity,
                currentPassengerCount = tripState.checkedInCount
            )
            _scanResult.value = result

            if (result is QrValidationResult.Valid) {
                _driverTrip.update { it.copy(checkedInCount = it.checkedInCount + 1) }
            }
        }
    }

    fun dismissScanResult() {
        _scanResult.value = null
    }

    // Telebirr Checkout
    fun openTelebirrDialog() {
        _paymentMessage.value = null
        _isTelebirrDialogOpen.value = true
    }

    fun closeTelebirrDialog() {
        _isTelebirrDialogOpen.value = false
        _paymentMessage.value = null
    }

    fun processTelebirrPayment(phone: String) {
        viewModelScope.launch {
            _isProcessingPayment.value = true
            val user = _currentUser.value
            val appliedRouteId = user?.appliedRouteId?.ifBlank { "route_bole_merkato" } ?: "route_bole_merkato"
            val routesList = repository.allRoutes.firstOrNull() ?: emptyList()
            val matchedRoute = routesList.find { it.id == appliedRouteId }
            val amount = matchedRoute?.basePriceEtb ?: 2500.0

            val result = TelebirrGateway.processSubscriptionPayment(
                passengerId = currentPassengerId,
                routeId = appliedRouteId,
                pickupStopId = "stop_atlas",
                destinationStopId = "stop_merkato",
                morningSchedule = "06:30",
                eveningSchedule = "17:30",
                amountEtb = amount,
                phoneNumber = phone,
                vehicleId = "veh_higer_aa_34921",
                idempotencyKey = UUID.randomUUID().toString(),
                repository = repository
            )

            _isProcessingPayment.value = false
            when (result) {
                is TelebirrPaymentResult.Success -> {
                    _paymentMessage.value = "Payment Successful! Subscription activated."
                    delay(1200)
                    _isTelebirrDialogOpen.value = false
                }
                is TelebirrPaymentResult.Failed -> {
                    _paymentMessage.value = "Failed: ${result.message}"
                }
                else -> {
                    _paymentMessage.value = "Payment cancelled or timed out."
                }
            }
        }
    }

    fun cancelSubscription() {
        viewModelScope.launch {
            val sub = activeSubscription.value
            if (sub != null) {
                repository.insertSubscription(sub.copy(subscriptionStatus = "EXPIRED", daysRemaining = 0))
            }
        }
    }

    fun openReceipt(receipt: PaymentTransactionEntity) {
        _selectedReceipt.value = receipt
    }

    fun closeReceipt() {
        _selectedReceipt.value = null
    }

    // Complaint Submission
    fun openComplaintDialog() {
        _isComplaintDialogOpen.value = true
    }

    fun closeComplaintDialog() {
        _isComplaintDialogOpen.value = false
    }

    fun submitComplaint(category: String, message: String) {
        viewModelScope.launch {
            val name = _currentUser.value?.fullName ?: "Abebe Kebede"
            val complaint = ComplaintEntity(
                id = "cmp_" + UUID.randomUUID().toString().take(8),
                passengerId = currentPassengerId,
                passengerName = name,
                category = category,
                text = message,
                date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
                status = "OPEN",
                routeId = "route_bole_merkato"
            )
            repository.submitComplaint(complaint)
            repository.logAction("COMPLAINT_FILED", currentPassengerId, "PASSENGER", "Filed $category ticket")
            _isComplaintDialogOpen.value = false
        }
    }
}

class MainViewModelFactory(private val repository: TransportRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
