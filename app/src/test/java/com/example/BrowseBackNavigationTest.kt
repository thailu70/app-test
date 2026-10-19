package com.example

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.example.data.api.RoutePassApiService
import com.example.data.database.TransportDatabase
import com.example.data.repository.TransportRepository
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MainViewModel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowseBackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: TransportDatabase
    private lateinit var viewModel: MainViewModel

    @Before fun showAppWithoutNetwork() {
        val offlineApi = Proxy.newProxyInstance(
            RoutePassApiService::class.java.classLoader,
            arrayOf(RoutePassApiService::class.java)
        ) { _, _, _ ->
            Response.error<Any>(503, "{}".toResponseBody("application/json".toMediaType()))
        } as RoutePassApiService
        compose.runOnIdle {
            database = Room.inMemoryDatabaseBuilder(compose.activity, TransportDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            viewModel = MainViewModel(TransportRepository(database.transportDao(), offlineApi))
            compose.activity.viewModelStore.put("browse-navigation-test", viewModel)
        }
        compose.setContent { MyApplicationTheme { MainTransportApp(viewModel) } }
    }

    @After fun closeDatabase() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        database.close()
    }

    private fun enterDailyRoutes() {
        compose.onNodeWithTag("welcome_service_PASSENGER").performClick()
        compose.onNodeWithTag("welcome_continue").performClick()
        assertDailyRoutes()
    }

    private fun assertDailyRoutes() {
        compose.onNodeWithText("Find a daily route").assertIsDisplayed()
        compose.onNodeWithTag("commuter_browse_sign_in").assertIsDisplayed()
        compose.onNodeWithTag("welcome_continue").assertDoesNotExist()
        compose.onNodeWithText("Passenger Sign In").assertDoesNotExist()
    }

    @Test fun passengerSignInTopBackReturnsToDailyRoutes() {
        enterDailyRoutes()
        compose.onNodeWithTag("commuter_browse_sign_in").performClick()
        compose.onNodeWithText("Passenger Sign In").assertExists()
        compose.onNodeWithText("Back").performClick()
        assertDailyRoutes()
    }

    @Test fun myPassHardwareBackReturnsToDailyRoutes() {
        enterDailyRoutes()
        compose.onNodeWithText("My pass").performClick()
        compose.onNodeWithText("Passenger Sign In").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertDailyRoutes()
    }
}
