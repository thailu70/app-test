package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.TransportDao
import com.example.data.entity.*
import com.example.core.environment.AppEnvironment
import com.example.data.api.ApiClient
import kotlinx.coroutines.CoroutineScope

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
    version = 6,
    exportSchema = false
)
abstract class TransportDatabase : RoomDatabase() {
    abstract fun transportDao(): TransportDao

    companion object {
        /** Copy non-secret fields only; existing routes, passes and other cached data survive. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query("PRAGMA secure_delete=ON").use { it.moveToFirst() }
                db.execSQL("CREATE TABLE users_without_credentials (id TEXT NOT NULL PRIMARY KEY, role TEXT NOT NULL, fullName TEXT NOT NULL, phone TEXT NOT NULL, email TEXT NOT NULL, status TEXT NOT NULL, licenseNumber TEXT NOT NULL, avatarInitials TEXT NOT NULL, companyName TEXT NOT NULL, assignedVehiclePlate TEXT NOT NULL, appliedRouteId TEXT NOT NULL, appliedRouteName TEXT NOT NULL)")
                db.execSQL("INSERT INTO users_without_credentials SELECT id,role,fullName,phone,email,status,licenseNumber,avatarInitials,companyName,assignedVehiclePlate,appliedRouteId,appliedRouteName FROM users")
                db.execSQL("DROP TABLE users")
                db.execSQL("ALTER TABLE users_without_credentials RENAME TO users")
            }
        }

        /** Preserve cached routes and paid passes while adding assignment-specific fares. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routes ADD COLUMN economyMonthlyFareEtb REAL")
                db.execSQL("ALTER TABLE routes ADD COLUMN deluxeMonthlyFareEtb REAL")
                db.execSQL("ALTER TABLE routes ADD COLUMN vipMonthlyFareEtb REAL")
                db.execSQL("ALTER TABLE routes ADD COLUMN serviceFaresConfigured INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN serviceClass TEXT NOT NULL DEFAULT 'ECONOMY'")
                db.execSQL("ALTER TABLE subscriptions ADD COLUMN renewalPriceEtb REAL")
            }
        }

        private val instances = mutableMapOf<AppEnvironment, TransportDatabase>()

        @Synchronized
        fun getDatabase(context: Context, scope: CoroutineScope, environment: AppEnvironment = ApiClient.environment()): TransportDatabase {
            return instances[environment] ?: run {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TransportDatabase::class.java,
                    environment.storageName("transport_navigator_database")
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.query("PRAGMA secure_delete=ON").use { it.moveToFirst() }
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()
                instances[environment] = instance

                // Production data is loaded from authenticated server responses.
                // Do not add sample passengers, vehicles, payments or active passes.


                instance
            }
        }

    }
}
