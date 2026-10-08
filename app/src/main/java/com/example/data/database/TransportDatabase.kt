package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.TransportDao
import com.example.data.entity.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        UserEntity::class,
        RouteEntity::class,
        RouteStopEntity::class,
        VehicleEntity::class,
        SubscriptionPlanEntity::class,
        SubscriptionEntity::class,
        PaymentTransactionEntity::class,
        CheckInRecordEntity::class,
        ComplaintEntity::class,
        AuditLogEntity::class,
        NotificationEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class TransportDatabase : RoomDatabase() {
    abstract fun transportDao(): TransportDao

    companion object {
        @Volatile
        private var INSTANCE: TransportDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): TransportDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TransportDatabase::class.java,
                    "transport_navigator_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance

                scope.launch(Dispatchers.IO) {
                    try {
                        val dao = instance.transportDao()
                        if (dao.getUserById("usr_p_abebe") == null) {
                            populateInitialData(dao)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                instance
            }
        }

        private suspend fun populateInitialData(dao: TransportDao) {
            // Seed Users with passwords, roles, and applied routes
            val users = listOf(
                UserEntity(
                    id = "usr_p_abebe",
                    role = "PASSENGER",
                    fullName = "Abebe Kebede",
                    phone = "+251911223344",
                    email = "abebe.kebede@example.com",
                    avatarInitials = "AK",
                    password = "password123",
                    appliedRouteId = "route_bole_merkato",
                    appliedRouteName = "Bole → Merkato"
                ),
                UserEntity(
                    id = "usr_p_hana",
                    role = "PASSENGER",
                    fullName = "Hana Tadesse",
                    phone = "+251922334455",
                    email = "hana.t@example.com",
                    avatarInitials = "HT",
                    password = "password123",
                    appliedRouteId = "route_cmc_bole",
                    appliedRouteName = "CMC → Bole"
                ),
                UserEntity(
                    id = "usr_p_dawit",
                    role = "PASSENGER",
                    fullName = "Dawit Bekele",
                    phone = "+251933445566",
                    email = "dawit.b@example.com",
                    avatarInitials = "DB",
                    password = "password123",
                    appliedRouteId = "route_saris_kazanchis",
                    appliedRouteName = "Saris → Kazanchis"
                ),
                UserEntity(
                    id = "usr_p_meron",
                    role = "PASSENGER",
                    fullName = "Meron Hailu",
                    phone = "+251944556677",
                    email = "meron.h@example.com",
                    avatarInitials = "MH",
                    password = "password123",
                    appliedRouteId = "route_bole_merkato",
                    appliedRouteName = "Bole → Merkato"
                ),
                UserEntity(
                    id = "usr_d_alemu",
                    role = "DRIVER",
                    fullName = "Abebe Alemu",
                    phone = "+251911998877",
                    email = "driver.alemu@transport.et",
                    licenseNumber = "ET-AA-789012",
                    avatarInitials = "AA",
                    password = "password123",
                    assignedVehiclePlate = "AA-12345",
                    appliedRouteId = "route_bole_merkato",
                    appliedRouteName = "Bole → Merkato"
                ),
                UserEntity(
                    id = "usr_d_girma",
                    role = "DRIVER",
                    fullName = "Girma Tesfaye",
                    phone = "+251912887766",
                    email = "driver.girma@transport.et",
                    licenseNumber = "ET-AA-345678",
                    avatarInitials = "GT",
                    password = "password123",
                    assignedVehiclePlate = "AA-67890",
                    appliedRouteId = "route_cmc_bole",
                    appliedRouteName = "CMC → Bole"
                ),
                UserEntity(
                    id = "usr_admin",
                    role = "ADMIN",
                    fullName = "Operations Admin",
                    phone = "+251910001122",
                    email = "admin@transport.et",
                    avatarInitials = "OA",
                    password = "adminpassword",
                    companyName = "Addis Commuter Transit Co."
                )
            )
            dao.insertUsers(users)

            // Seed Routes
            val routes = listOf(
                RouteEntity(
                    id = "route_bole_merkato",
                    name = "Bole → Merkato",
                    nameAm = "ቦሌ → መርካቶ",
                    description = "Via Mexico, Piazza, Bole Atlas, Medhanialem",
                    morningDeparture = "06:30",
                    eveningDeparture = "17:30",
                    distanceKm = 16.5,
                    basePriceEtb = 2500.0,
                    active = true
                ),
                RouteEntity(
                    id = "route_cmc_bole",
                    name = "CMC → Bole",
                    nameAm = "ሲኤምሲ → ቦሌ",
                    description = "Via Megenagna, Hayahulet, Edna Mall",
                    morningDeparture = "06:45",
                    eveningDeparture = "17:15",
                    distanceKm = 14.2,
                    basePriceEtb = 3000.0,
                    active = true
                ),
                RouteEntity(
                    id = "route_saris_kazanchis",
                    name = "Saris → Kazanchis",
                    nameAm = "ሳሪስ → ካዛንቺስ",
                    description = "Via Gotera, Meskel Square, Kasanchis UNECA",
                    morningDeparture = "07:00",
                    eveningDeparture = "17:30",
                    distanceKm = 18.0,
                    basePriceEtb = 2800.0,
                    active = true
                )
            )
            dao.insertRoutes(routes)

            // Route Stops
            val stops = listOf(
                RouteStopEntity(
                    id = "stop_atlas",
                    routeId = "route_bole_merkato",
                    stopName = "Bole Atlas",
                    stopNameAm = "ቦሌ አትላስ",
                    stopOrder = 1,
                    latitude = 9.0016,
                    longitude = 38.7845,
                    scheduledMorningTime = "06:30",
                    scheduledEveningTime = "17:30",
                    maxCapacity = 6
                ),
                RouteStopEntity(
                    id = "stop_medhanialem",
                    routeId = "route_bole_merkato",
                    stopName = "Bole Medhanialem",
                    stopNameAm = "ቦሌ መድኃኔዓለም",
                    stopOrder = 2,
                    latitude = 8.9950,
                    longitude = 38.7885,
                    scheduledMorningTime = "06:38",
                    scheduledEveningTime = "17:40",
                    maxCapacity = 5
                ),
                RouteStopEntity(
                    id = "stop_megenagna",
                    routeId = "route_bole_merkato",
                    stopName = "Megenagna",
                    stopNameAm = "መገናኛ",
                    stopOrder = 3,
                    latitude = 9.0195,
                    longitude = 38.8021,
                    scheduledMorningTime = "06:52",
                    scheduledEveningTime = "17:55",
                    maxCapacity = 7
                ),
                RouteStopEntity(
                    id = "stop_mexico",
                    routeId = "route_bole_merkato",
                    stopName = "Mexico",
                    stopNameAm = "ሜክሲኮ",
                    stopOrder = 4,
                    latitude = 9.0105,
                    longitude = 38.7460,
                    scheduledMorningTime = "07:10",
                    scheduledEveningTime = "18:15",
                    maxCapacity = 6
                ),
                RouteStopEntity(
                    id = "stop_merkato",
                    routeId = "route_bole_merkato",
                    stopName = "Merkato",
                    stopNameAm = "መርካቶ",
                    stopOrder = 5,
                    latitude = 9.0350,
                    longitude = 38.7350,
                    scheduledMorningTime = "07:30",
                    scheduledEveningTime = "18:35",
                    maxCapacity = 25
                )
            )
            dao.insertStops(stops)

            // Vehicles
            val vehicles = listOf(
                VehicleEntity(
                    id = "veh_aa_12345",
                    plateNumber = "AA-12345",
                    model = "Toyota Coaster 2023",
                    type = "Coaster Shuttle",
                    capacity = 25,
                    assignedDriverId = "usr_d_alemu",
                    status = "ACTIVE",
                    insuranceExpiry = "2027-05-15",
                    inspectionExpiry = "2027-04-10"
                ),
                VehicleEntity(
                    id = "veh_aa_67890",
                    plateNumber = "AA-67890",
                    model = "Toyota HiAce Minibus",
                    type = "HiAce Commuter",
                    capacity = 16,
                    assignedDriverId = "usr_d_girma",
                    status = "ACTIVE",
                    insuranceExpiry = "2027-08-20",
                    inspectionExpiry = "2027-07-01"
                )
            )
            dao.insertVehicles(vehicles)

            // Subscription Plans
            val plans = listOf(
                SubscriptionPlanEntity(
                    id = "plan_bole_merkato",
                    name = "Plan A: Bole ↔ Merkato",
                    routeId = "route_bole_merkato",
                    priceEtb = 2500.0,
                    description = "Morning 06:30 & Evening 17:30 Weekday Shuttle"
                ),
                SubscriptionPlanEntity(
                    id = "plan_cmc_bole",
                    name = "Plan B: CMC ↔ Bole",
                    routeId = "route_cmc_bole",
                    priceEtb = 3000.0,
                    description = "Morning 06:45 & Evening 17:15 Weekday Shuttle"
                ),
                SubscriptionPlanEntity(
                    id = "plan_saris_kazanchis",
                    name = "Plan C: Saris ↔ Kazanchis",
                    routeId = "route_saris_kazanchis",
                    priceEtb = 2800.0,
                    description = "Morning 07:00 & Evening 17:30 Weekday Shuttle"
                )
            )
            dao.insertPlans(plans)

            // Active Subscription for default passenger Abebe Kebede
            val defaultSub = SubscriptionEntity(
                id = "sub_abebe_202610",
                passengerId = "usr_p_abebe",
                routeId = "route_bole_merkato",
                pickupStopId = "stop_atlas",
                destinationStopId = "stop_merkato",
                morningSchedule = "06:30",
                eveningSchedule = "17:30",
                startDate = "01 Oct 2026",
                endDate = "31 Oct 2026",
                priceEtb = 2500.0,
                paymentStatus = "PAID",
                subscriptionStatus = "ACTIVE",
                vehicleId = "veh_aa_12345",
                qrToken = "ET-NAV-2026-BOLE-AK7899",
                daysRemaining = 26
            )
            dao.insertSubscription(defaultSub)

            // Payment Transaction for default passenger
            val payment = PaymentTransactionEntity(
                id = "tx_tb_998822",
                transactionRef = "TB-ET-20261001-9988",
                passengerId = "usr_p_abebe",
                subscriptionId = "sub_abebe_202610",
                amountEtb = 2500.0,
                provider = "Telebirr",
                status = "SUCCESS",
                timestamp = "01 Oct 2026, 09:15 AM",
                paymentPhone = "+251911223344",
                receiptNumber = "REC-ET-44392"
            )
            dao.insertPayment(payment)

            // Seed Notifications for Passengers and Transporters
            val notifications = listOf(
                NotificationEntity(
                    id = "notif_sub_expiry",
                    title = "Subscription Expiry Reminder",
                    message = "Your Bole ↔ Merkato monthly commuter pass expires in 7 days. Renew early with Telebirr to keep your assigned seat.",
                    targetAudience = "PASSENGERS",
                    timestamp = "05 Oct, 08:00 AM",
                    type = "REMINDER",
                    senderName = "Subscription System"
                ),
                NotificationEntity(
                    id = "notif_pickup_approaching",
                    title = "Morning Shuttle On Schedule",
                    message = "Shuttle AA-12345 (Abebe Alemu) is en route to Bole Atlas for 06:30 AM pickup.",
                    targetAudience = "PASSENGERS",
                    timestamp = "05 Oct, 06:15 AM",
                    type = "ALERT",
                    senderName = "Dispatch"
                ),
                NotificationEntity(
                    id = "notif_driver_trip",
                    title = "Assigned Trip Briefing",
                    message = "Morning Trip 06:30 assigned: Bole → Merkato. 18 / 20 passengers scheduled for check-in.",
                    targetAudience = "TRANSPORTERS",
                    timestamp = "05 Oct, 06:00 AM",
                    type = "SCHEDULE",
                    senderName = "Operations Center"
                ),
                NotificationEntity(
                    id = "notif_driver_safety",
                    title = "Safety & QR Protocol Notice",
                    message = "Drivers must complete all scans while vehicle is halted at designated stops. Drive safely.",
                    targetAudience = "TRANSPORTERS",
                    timestamp = "04 Oct, 07:30 PM",
                    type = "ALERT",
                    senderName = "Fleet Safety"
                )
            )
            dao.insertNotifications(notifications)

            // Past check-ins
            val checkins = listOf(
                CheckInRecordEntity(
                    qrToken = "ET-NAV-2026-BOLE-AK7899",
                    passengerId = "usr_p_abebe",
                    passengerName = "Abebe Kebede",
                    tripId = "trip_morn_20261004",
                    routeId = "route_bole_merkato",
                    vehicleId = "veh_aa_12345",
                    driverId = "usr_d_alemu",
                    stopName = "Bole Atlas",
                    checkInTimestamp = "04 Oct 2026 06:29",
                    status = "PRESENT",
                    latitude = 9.0016,
                    longitude = 38.7845
                ),
                CheckInRecordEntity(
                    qrToken = "ET-NAV-2026-BOLE-HT1102",
                    passengerId = "usr_p_hana",
                    passengerName = "Hana Tadesse",
                    tripId = "trip_morn_20261004",
                    routeId = "route_bole_merkato",
                    vehicleId = "veh_aa_12345",
                    driverId = "usr_d_alemu",
                    stopName = "Bole Atlas",
                    checkInTimestamp = "04 Oct 2026 06:31",
                    status = "PRESENT",
                    latitude = 9.0016,
                    longitude = 38.7845
                )
            )
            for (c in checkins) {
                dao.insertCheckIn(c)
            }

            // Initial audit log
            dao.insertAuditLog(
                AuditLogEntity(
                    action = "SYSTEM_INITIALIZED",
                    userId = "usr_admin",
                    role = "SUPER_ADMIN",
                    timestamp = "01 Oct 2026 00:00:00",
                    details = "Transport Navigator seed data loaded. Addis Ababa network live."
                )
            )
        }
    }
}
