package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of iOS `ComposerMorphPhaseTests.swift`. */
class ComposerMorphPhaseTests {
    /**
     * A fold waiting for its next turn is dropped when the morph opens or closes another way, and
     * its flag goes with it, so Back and the close guards still work.
     */
    @Test fun openingOrClosingAnotherWayClearsAPendingFold() = runTest {
        val morph = ComposerMorph(animationScope())
        morph.open()
        morph.launchThenFold(Motion.panelClose.animation(), reduceMotion = true, launch = {}, edge = { null })
        assertTrue(morph.foldPending)
        morph.open()
        assertFalse(morph.foldPending)
        assertTrue(morph.isOpening)

        morph.launchThenFold(Motion.panelClose.animation(), reduceMotion = true, launch = {}, edge = { null })
        morph.close(reduceMotion = true)
        assertFalse(morph.foldPending)
        assertEquals(ComposerMorph.Phase.Closing, morph.phase)
    }

    /**
     * The photo sheet's host hands the menu back from this, so it runs while the morph is still
     * drawn, and only for the fold that's still current.
     */
    @Test fun launchThenFoldReportsLandingWhileClosingUnlessItReopened() = runTest {
        val close = Motion.panelClose.animation<Float>()
        val morph = ComposerMorph(animationScope())
        morph.open()
        var landedIn: ComposerMorph.Phase? = null
        morph.launchThenFold(close, reduceMotion = true, launch = {}, edge = { null }, landed = { landedIn = morph.phase })
        for (i in 0 until 100) {
            if (landedIn != null) break
            delay(10)
        }
        assertEquals(ComposerMorph.Phase.Closing, landedIn)

        val reopened = ComposerMorph(animationScope())
        reopened.open()
        var landed = false
        reopened.launchThenFold(close, reduceMotion = true, launch = {}, edge = { null }, landed = { landed = true })
        reopened.open()
        delay(100)
        assertFalse(landed)
        assertTrue(reopened.isOpen)
    }

    /** The morph's springs run on `Animatable`s, which need a frame clock: a 60 Hz one on virtual time. */
    private fun TestScope.animationScope(): CoroutineScope {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
                delay(16)
                return onFrame(testScheduler.currentTime * 1_000_000)
            }
        }
        return CoroutineScope(backgroundScope.coroutineContext + clock)
    }
}
