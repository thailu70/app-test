package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import android.util.TypedValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    @Test fun `adaptive and themed launcher icons render the welcome P inside the safe area`() {
        val context = RuntimeEnvironment.getApplication()
        val icon = requireNotNull(context.getDrawable(R.mipmap.ic_launcher)) as AdaptiveIconDrawable
        val round = requireNotNull(context.getDrawable(R.mipmap.ic_launcher_round)) as AdaptiveIconDrawable
        assertTrue(icon.foreground is VectorDrawable)
        assertTrue(icon.monochrome is VectorDrawable)
        assertNotEquals(R.drawable.ic_launcher_foreground, R.drawable.ic_launcher_monochrome)
        val foreground = render(icon.foreground)
        val mono = render(requireNotNull(icon.monochrome))
        var painted = 0
        var navy = false
        var teal = false
        for (y in 0 until foreground.height) for (x in 0 until foreground.width) {
            val pixel = foreground.getPixel(x, y)
            if (Color.alpha(pixel) > 0) {
                painted++
                val dx = x + 0.5f - 162f
                val dy = y + 0.5f - 162f
                assertTrue("P must remain inside the adaptive safe area", dx * dx + dy * dy <= 100f * 100f)
                if (Color.alpha(pixel) > 240) {
                    navy = navy || Color.blue(pixel) > Color.green(pixel)
                    teal = teal || Color.green(pixel) > Color.blue(pixel)
                }
            }
            val monochrome = mono.getPixel(x, y)
            if (Color.alpha(monochrome) > 0) assertEquals("Themed icon must use a solid tintable mask", Color.BLACK, monochrome or (0xff shl 24))
        }
        assertTrue("The welcome P should be visible", painted > 5_000)
        assertTrue("The mark retains its navy-to-teal gradient", navy && teal)
        assertEquals("The P counter stays open", 0, Color.alpha(foreground.getPixel(163, 132)))
        save("01-adaptive", render(icon))
        save("02-adaptive-round", render(round))
        save("03-legacy", render(requireNotNull(context.getDrawable(R.drawable.ic_launcher_legacy))))
        save("04-monochrome", mono)
    }

    @Test
    @Config(sdk = [24, 25])
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun `Android 7 resolves vector P icons instead of the old density bus PNGs`() {
        val context = RuntimeEnvironment.getApplication()
        for (resource in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val value = TypedValue()
            context.resources.getValue(resource, value, true)
            assertTrue(value.string.toString(), value.string.toString().contains("mipmap-anydpi"))
            assertTrue(value.string.toString(), value.string.toString().endsWith(".xml"))
            assertFalse(value.string.toString(), value.string.toString().contains("v26"))
            assertTrue(context.getDrawable(resource) is InsetDrawable)
        }
    }

    private fun render(drawable: Drawable): Bitmap = Bitmap.createBitmap(324, 324, Bitmap.Config.ARGB_8888).also { bitmap ->
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
    }

    private fun save(name: String, bitmap: Bitmap) {
        val folder = File("build/launcher-previews").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
