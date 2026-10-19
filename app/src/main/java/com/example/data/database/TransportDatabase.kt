package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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

                // Production data is loaded from authenticated server responses.
                // Do not add sample passengers, vehicles, payments or active passes.


                instance
            }
        }

    }
}
