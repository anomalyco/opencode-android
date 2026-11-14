package ai.opencode.feature.composer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Android-only: after an app-controlled IME animation, Compose can keep `imeAnimationSource` at the
 * keys' old height while the keys and the target are down; that must not read as an animation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerKeyboardProgressTests {
    private fun keyboard(source: Int, target: Int, ime: Int, excluded: Boolean = false) = ComposerKeyboardProgress(
        density = mutableStateOf(Density(1f)),
        isActive = mutableStateOf(true),
        excluded = mutableStateOf(excluded),
        ime = WindowInsets(bottom = ime),
        source = WindowInsets(bottom = source),
        target = WindowInsets(bottom = target),
        navigation = WindowInsets(bottom = 0),
    )

    @Test fun keysRestingOnTheirTargetAreNotAnimatingWhateverTheSourceSays() {
        val gaveBack = keyboard(source = 900, target = 0, ime = 0)
        assertFalse(gaveBack.isAnimating)
        assertEquals(0f, gaveBack.progress)
        assertFalse(keyboard(source = 0, target = 900, ime = 900).isAnimating)
    }

    @Test fun keysOnTheirWayAreAnimating() {
        assertTrue(keyboard(source = 0, target = 900, ime = 0).isAnimating)
        assertTrue(keyboard(source = 0, target = 900, ime = 300).isAnimating)
        assertTrue(keyboard(source = 900, target = 0, ime = 600).isAnimating)
        assertFalse(keyboard(source = 0, target = 900, ime = 300, excluded = true).isAnimating)
    }

    /**
     * iOS `.allowsHitTesting(false)` on the shut row's controls: a tap on them reaches the field
     * laid out under them in another branch (the composer's input slot), and they still draw.
     */
    @Test fun shutControlsLetTheFieldUnderThemTakeTheTap() {
        for (passes in listOf(true, false)) {
            var fieldTaps = 0
            var chipTaps = 0
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            val view = ComposeView(activity)
            activity.setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.size(200.dp, 50.dp)) {
                        // The field, then the controls' row over it, as the composer's layout has them.
                        Box(Modifier.offset(x = 50.dp).size(150.dp, 50.dp).clickable { fieldTaps++ })
                        Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.size(50.dp))
                            Box(if (passes) Modifier.passesTouchesThrough() else Modifier) {
                                Box(Modifier.size(80.dp, 50.dp).background(Color.Red).clickable { chipTaps++ })
                            }
                        }
                    }
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            tap(view, x = 90f, y = 25f)
            assertEquals("passes=$passes: the field's taps", if (passes) 1 else 0, fieldTaps)
            assertEquals("passes=$passes: the controls' taps", if (passes) 0 else 1, chipTaps)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            assertEquals("passes=$passes: the controls draw in place", android.graphics.Color.RED, bitmap.getPixel(90, 25))
        }
    }

    private fun tap(view: ComposeView, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
