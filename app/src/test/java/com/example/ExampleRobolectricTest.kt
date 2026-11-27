package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `launch MainActivity without crash`() {
    // MainTransportApp includes intentional repeating visual motion. A fully
    // resumed Robolectric activity continuously drains its paused Choreographer
    // queue, so this smoke test checks creation without trying to idle that UI.
    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create()
    org.junit.Assert.assertNotNull(controller.get())
    controller.destroy()
  }
}
