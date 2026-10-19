package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.example.data.entity.RouteEntity
import com.example.ui.screens.home.CommuterBrandHeader
import com.example.ui.screens.home.CommuterRouteCard
import com.example.ui.screens.home.DailyCommuteIntro
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommuterBrowseTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `browse header and monthly tier prices retain working controls`() {
        val route = RouteEntity("browse-route", "Garment - Bole Medhanialem", "ጋርመንት - ቦሌ መድኃኒዓለም", "", "06:30", "17:30", 12.0, 2500.0)
        var signIns = 0
        var openedRoutes = 0
        compose.setContent {
            MyApplicationTheme {
                Column(
                    Modifier.fillMaxSize().background(Color(0xFFFFFBF4))
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CommuterBrandHeader(onHome = {}, onSignIn = { signIns++ })
                    DailyCommuteIntro()
                    CommuterRouteCard(route = route, onClick = { openedRoutes++ })
                }
            }
        }
        compose.onNodeWithTag("commuter_browse_sign_in").assertIsDisplayed().performClick()
        assertEquals(1, signIns)
        compose.onNodeWithText("Daily commute").assertExists()
        compose.onNodeWithText("Find a daily route").assertExists()
        compose.onNodeWithText("YOUR EVERYDAY, MADE EASIER").assertDoesNotExist()
        compose.onNodeWithText("A better way to commute.").assertDoesNotExist()
        compose.onNodeWithText("Find a daily route for work, school and everything in between.").assertDoesNotExist()
        compose.onNodeWithText("Your route. Your routine.").assertDoesNotExist()
        listOf("Economy", "Deluxe", "VIP", "ETB 2,500").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onAllNodesWithText("Not available", useUnmergedTree = true).assertCountEquals(2)
        capture()
        compose.onNodeWithTag("commuter_route_details_${route.id}").performScrollTo().performClick()
        assertEquals(1, openedRoutes)
    }

    private fun capture() {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val directory = File("build/commuter-previews").apply { mkdirs() }
            File(directory, "04-route-browse.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
