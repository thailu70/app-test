package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.api.*
import com.example.data.database.TransportDatabase
import com.example.data.entity.RouteEntity
import com.example.data.entity.UserEntity
import com.example.data.repository.TransportRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CredentialStorageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `upgrading old cache removes password column and preserves user and route`() = runBlocking {
        val name = "credential-migration-${System.nanoTime()}"
        var database = Room.databaseBuilder(context, TransportDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            database.transportDao().insertUser(UserEntity("existing-user", "PASSENGER", "Cached name", "0912000000", ""))
            database.transportDao().insertRoute(RouteEntity("existing-route", "Actual route", "", "", "07:00", "17:00", 12.0, 2500.0))
            database.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("ALTER TABLE users ADD COLUMN password TEXT NOT NULL DEFAULT ''")
                old.execSQL("UPDATE users SET password='old-plaintext-secret'")
                old.version = 4
            }
            database = Room.databaseBuilder(context, TransportDatabase::class.java, name)
                .addMigrations(TransportDatabase.MIGRATION_4_5).allowMainThreadQueries().build()
            assertEquals("Cached name", database.transportDao().getUserById("existing-user")!!.fullName)
            assertEquals("Actual route", database.transportDao().getRouteById("existing-route")!!.name)
            database.openHelper.readableDatabase.query("PRAGMA table_info(users)").use { columns ->
                val names = mutableListOf<String>()
                while (columns.moveToNext()) names += columns.getString(columns.getColumnIndexOrThrow("name"))
                assertFalse(names.contains("password"))
            }
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun `login sends password to server but caches only non-secret profile and logout cache is removable`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, TransportDatabase::class.java).allowMainThreadQueries().build()
        try {
            var receivedPassword: String? = null
            val api = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader, arrayOf(RoutePassApiService::class.java)) { _, method, args ->
                check(method.name == "login")
                receivedPassword = (args!![0] as LoginRequest).password
                Response.success(AuthResponse(true, "fixture-token", UserDto("signed-in", "PASSENGER", "Server Name", "0912000000")))
            } as RoutePassApiService
            val repository = TransportRepository(database.transportDao(), api)
            assertNotNull(repository.authenticate("0912000000", "PASSENGER", "only-used-for-request"))
            assertEquals("only-used-for-request", receivedPassword)
            assertFalse(UserEntity::class.java.declaredFields.any { it.name == "password" })
            database.openHelper.readableDatabase.query("SELECT * FROM users WHERE id='signed-in'").use { row ->
                assertTrue(row.moveToFirst())
                assertFalse((0 until row.columnCount).any { row.getString(it) == receivedPassword })
            }
            database.transportDao().insertRoute(RouteEntity("public-route", "Public route", "", "", "07:00", "17:00", 12.0, 2500.0))
            repository.clearPrivateCache()
            assertNull(database.transportDao().getUserById("signed-in"))
            assertNotNull(database.transportDao().getRouteById("public-route"))
        } finally { ApiClient.setAuthToken(null); database.close() }
    }

    @Test fun `cloud and device transfer exclude cached databases and private ticket capabilities`() {
        for (resource in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val parser = context.resources.getXml(resource)
            var databaseExclusions = 0
            var ticketExclusions = 0
            parser.use {
                while (it.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && it.name == "exclude") {
                        if (it.getAttributeValue(null, "domain") == "database" && it.getAttributeValue(null, "path") == ".") databaseExclusions++
                        if (it.getAttributeValue(null, "path") == "intercity_booking_access.xml") ticketExclusions++
                    }
                    it.next()
                }
            }
            assertEquals(if (resource == R.xml.data_extraction_rules) 2 else 1, databaseExclusions)
            assertEquals(if (resource == R.xml.data_extraction_rules) 2 else 1, ticketExclusions)
        }
    }
}
