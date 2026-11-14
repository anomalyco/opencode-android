package ai.opencode.feature.drawer.list

import ai.opencode.core.diagnostics.MotionFinding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of iOS `DrawerDragAuditTests.swift`: a lifted drawer row tracks the finger 1:1 with a
 * frame's latency allowed, is never clipped, and floats above the drawer's chrome.
 */
class DrawerDragAuditTests {
    private val width = 220f
    private val height = 36f

    private fun frame(index: Int, finger: Float, row: Float, clipped: Float = 0f, underChrome: Boolean = false) = DrawerDragFrame(
        index = index,
        time = index * 16,
        finger = Offset(120f, finger),
        expected = Offset(130f, finger + 4),
        lifted = Rect(130 - width / 2, row + 4 - height / 2, 130 + width / 2, row + 4 + height / 2),
        clipped = clipped,
        underChrome = underChrome,
    )

    @Test
    fun aRowOnTheFingerHasNoFindings() {
        val frames = (0 until 10).map { frame(it, finger = 600f - it * 12, row = 600f - it * 12) }
        val summary = DrawerDragAudit.summarize(frames)
        assertEquals(10, summary.frames)
        assertEquals(0, summary.lagFrames)
        assertEquals(0f, summary.maxGap)
        assertTrue(summary.kinds.isEmpty())
        assertTrue(DrawerDragAudit.findings(frames).isEmpty())
    }

    @Test
    fun oneFrameBehindIsAllowed() {
        // The row shows where the finger was on the frame before.
        val frames = (0 until 10).map { frame(it, finger = 600f - it * 12, row = 600f - maxOf(it - 1, 0) * 12) }
        val summary = DrawerDragAudit.summarize(frames)
        assertEquals(12f, summary.maxGap)
        assertEquals(0f, summary.maxLag)
        assertEquals(0, summary.lagFrames)
        assertTrue(DrawerDragAudit.findings(frames).isEmpty())
    }

    @Test
    fun trailingTheFingerIsFollowLag() {
        // The system platter's way: a few points behind, more than a frame late.
        val frames = (0 until 6).map { frame(it, finger = 600f - it * 12, row = 600f - it * 12 + 9) }
        val summary = DrawerDragAudit.summarize(frames)
        assertEquals(6, summary.lagFrames)
        assertEquals(listOf(MotionFinding.Kind.FollowLag), summary.kinds)
        val findings = DrawerDragAudit.findings(frames)
        assertTrue(findings.all { it.kind == MotionFinding.Kind.FollowLag })
        // 9 pt behind on the first frame; after it, 3 pt short of where the finger was a frame before.
        assertEquals(listOf(9.0, 3.0, 3.0, 3.0, 3.0, 3.0), findings.map { it.magnitudePt })
    }

    @Test
    fun clippedAndUnderChromeAreFindings() {
        val frames = listOf(
            frame(0, finger = 120f, row = 120f),
            frame(1, finger = 100f, row = 100f, clipped = 14f),
            frame(2, finger = 80f, row = 80f, underChrome = true),
            frame(3, finger = 70f, row = 70f, clipped = 0.4f),
        )
        val summary = DrawerDragAudit.summarize(frames)
        assertEquals(14f, summary.maxClipped)
        assertEquals(1, summary.underChromeFrames)
        assertEquals(listOf(MotionFinding.Kind.LiftedClipped, MotionFinding.Kind.Underlap), summary.kinds)
        assertEquals(listOf(MotionFinding.Kind.LiftedClipped, MotionFinding.Kind.Underlap), DrawerDragAudit.findings(frames).map { it.kind })
        assertEquals(74f, summary.fingerMinY)
        assertEquals(74f, summary.liftedMinY)
    }
}
