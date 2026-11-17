package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.example.data.api.*
import com.example.data.database.TransportDatabase
import com.example.data.repository.TransportRepository
import com.example.ui.screens.taxi.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: TransportDatabase
    private lateinit var main: MainViewModel

    @Before fun createMainViewModel() {
        val offlineApi = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader,
            arrayOf(RoutePassApiService::class.java)) { _, _, _ ->
            Response.error<Any>(503, "{}".toResponseBody("application/json".toMediaType()))
        } as RoutePassApiService
        compose.runOnIdle {
            database = Room.inMemoryDatabaseBuilder(compose.activity, TransportDatabase::class.java)
                .allowMainThreadQueries().build()
            main = MainViewModel(TransportRepository(database.transportDao(), offlineApi))
            compose.activity.viewModelStore.put("taxi-layout-test", main)
        }
    }

    @After fun closeDatabase() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        database.close()
    }

    private fun api(): TaxiApi = Proxy.newProxyInstance(TaxiApi::class.java.classLoader, arrayOf(TaxiApi::class.java)) { _, method, _ ->
        when (method.name) {
            "catalog" -> Response.success(TaxiResponse(true, settings = TaxiSettings(enabled = true, sharedEnabled = true)))
            "shared" -> Response.success(TaxiResponse(true))
            else -> error("Unexpected ${method.name}")
        }
    } as TaxiApi
    @Test fun `destination first home fits phone and daily commute stays independently reachable`() {
        var commute = 0
        compose.setContent { MyApplicationTheme { val vm = androidx.compose.runtime.remember { TaxiViewModel(api(), false, false) }
            TaxiPassengerContent(main, false, vm, { commute++ }, {}, {}, mapTiles = false)
        } }
        compose.onNodeWithTag("taxi_where_to").assertIsDisplayed()
        compose.onNodeWithTag("taxi_tier_ECONOMY").assertIsDisplayed()
        compose.onNodeWithTag("taxi_tier_COMFORT").assertIsDisplayed().performClick()
        compose.onNodeWithTag("taxi_tier_MINI_VAN").assertIsDisplayed()
        compose.onNodeWithText("Current location").assertDoesNotExist()
        compose.onNodeWithText("Daily").assertIsDisplayed().performClick()
        assertEquals(1, commute)
        compose.runOnIdle { val view = compose.activity.window.decorView; val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
            val dir = File("build/taxi-previews").apply { mkdirs() }; File(dir, "01-passenger-home.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

