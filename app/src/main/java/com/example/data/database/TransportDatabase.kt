package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.TransportDao
import com.example.data.entity.*
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
    version = 5,
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

        @Volatile
        private var INSTANCE: TransportDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope): TransportDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TransportDatabase::class.java,
                    "transport_navigator_database"
                )
                    .addMigrations(MIGRATION_4_5)
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.query("PRAGMA secure_delete=ON").use { it.moveToFirst() }
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance

                // Production data is loaded from authenticated server responses.
                // Do not add sample passengers, vehicles, payments or active passes.


                instance
            }
        }

    }
}
