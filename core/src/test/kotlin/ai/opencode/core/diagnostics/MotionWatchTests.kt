package ai.opencode.core.diagnostics

import ai.opencode.core.testing.TestIsolationRule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

private fun row(
    id: String,
    y: Double,
    h: Double = 44.0,
    alpha: Double = 1.0,
    kind: String = "markdown",
    animated: Boolean = false,
    key: String? = null,
): MotionFrame.Row = MotionFrame.Row(id = id, y = y, h = h, alpha = alpha, kind = kind, animated = animated, key = key)

private fun frame(
    index: Int,
    wall: Int,
    offset: Double = 100.0,
    contentHeight: Double = 1_200.0,
    gesture: Boolean = false,
    motions: List<String> = emptyList(),
    phase: String = "live",
    session: String = "ses_test",
    rows: List<MotionFrame.Row>,
    events: List<MotionFrame.Event> = emptyList(),
): MotionFrame = MotionFrame(
    launch = "L1",
    index = index,
    sequence = index,
    elapsed = 1,
    wall = wall,
    offset = offset,
    insetTop = 80.0,
    insetBottom = 90.0,
    contentHeight = contentHeight,
    height = 956.0,
    cardX = 0.0,
    width = 440.0,
    gesture = gesture,
    motions = motions,
    phase = phase,
    reduceMotion = false,
    session = session,
    rows = rows,
    events = events,
)

private fun apply(
    inserted: List<String> = emptyList(),
    removed: List<String> = emptyList(),
    order: List<String>,
    isInitial: Boolean = false,
) = MotionFrame.Event(name = "apply", inserted = inserted, removed = removed, order = order, isInitial = isInitial)

/** Port of iOS `MotionAuditDetectorTests` (`MotionWatchTests.swift`); the detector lives in `:core`. */
class MotionAuditDetectorTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun growingRowsSoftEdgeOnlyEverBrightens() {
        fun growing(index: Int, h: Double, fade: Double?): MotionFrame {
            val f = frame(index, wall = 1_000 + 16 * index, motions = listOf("grow"), rows = listOf(row("r1", y = 120.0, h = 60.0), row("r2", y = 200.0, h = h)))
            return if (fade != null) f.copy(growthFades = mapOf("r2" to fade)) else f
        }
        val open = frame(
            0, wall = 1_000,
            rows = listOf(row("r1", y = 120.0, h = 60.0), row("r2", y = 200.0, h = 80.0)),
            events = listOf(apply(inserted = listOf("r1", "r2"), order = listOf("r1", "r2"), isInitial = true)),
        )
        // Widening with the growth, narrowing, and ending once it is thin read clean.
        val smooth = listOf(open, growing(1, 82.0, 2.0), growing(2, 86.0, 6.0), growing(3, 88.0, 3.0), growing(4, 89.0, 0.6), growing(5, 89.4, null))
        assertTrue(MotionAuditDetector.findings(smooth).none { it.kind == MotionFinding.Kind.MaskJump })
        // A new line arriving widens the edge at once: what was drawn dims.
        val widened = listOf(open, growing(1, 82.0, 2.0), growing(2, 83.0, 20.0))
        assertEquals(listOf(MotionFinding.Kind.MaskJump), MotionAuditDetector.findings(widened).map { it.kind })
        // The row lands while its edge is still wide: it brightens at once.
        val dropped = listOf(open, growing(1, 90.0, 10.0), growing(2, 91.0, null))
        assertEquals(listOf(MotionFinding.Kind.MaskJump), MotionAuditDetector.findings(dropped).map { it.kind })
    }

    @Test
    fun openingFramesSettleWithoutFalsePositives() {
        val f1 = frame(
            1, wall = 1_000, phase = "opening",
            rows = listOf(row("r1", y = 120.0, h = 60.0), row("r2", y = 200.0, h = 80.0)),
            events = listOf(apply(inserted = listOf("r1", "r2"), order = listOf("r1", "r2"), isInitial = true)),
        )
        val f2 = frame(2, wall = 1_016, phase = "live", rows = listOf(row("r1", y = 120.0, h = 60.0), row("r2", y = 200.0, h = 80.0)))
        assertTrue(MotionAuditDetector.findings(listOf(f1, f2)).isEmpty())
    }

    @Test
    fun catchesPopInPopOutSnapOffsetJumpAndHeightSnap() {
        val open = frame(
            1, wall = 1_000,
            rows = listOf(row("r1", y = 120.0, h = 60.0, kind = "user"), row("r2", y = 200.0, h = 80.0, kind = "markdown")),
            events = listOf(apply(inserted = listOf("r1", "r2"), order = listOf("r1", "r2"), isInitial = true)),
        )
        // r3 pops in mid-screen at full opacity and height with no animation.
        val popIn = frame(
            2, wall = 1_016,
            rows = listOf(
                row("r1", y = 120.0, h = 60.0, kind = "user"),
                row("r2", y = 200.0, h = 80.0, kind = "markdown"),
                row("r3", y = 300.0, h = 52.0, alpha = 1.0, kind = "tools", animated = false),
            ),
            events = listOf(apply(inserted = listOf("r3"), order = listOf("r1", "r2", "r3"))),
        )
        // r3 pops out in one frame with no exit snapshot.
        val popOut = frame(
            3, wall = 1_032,
            rows = listOf(row("r1", y = 120.0, h = 60.0, kind = "user"), row("r2", y = 200.0, h = 80.0, kind = "markdown")),
            events = listOf(apply(removed = listOf("r3"), order = listOf("r1", "r2"))),
        )
        // Offset jumps by +30 pt with no running motion, moving all rows uniformly by -30 pt.
        val offsetJump = frame(
            4, wall = 1_048, offset = 130.0,
            rows = listOf(row("r1", y = 90.0, h = 60.0, kind = "user"), row("r2", y = 170.0, h = 80.0, kind = "markdown")),
        )
        // r1 grows by +24 pt in one frame with no growth/disclosure motion, pushing r2 down by 24 pt.
        val heightAndSnap = frame(
            5, wall = 1_064, offset = 130.0,
            rows = listOf(row("r1", y = 90.0, h = 84.0, kind = "user"), row("r2", y = 194.0, h = 80.0, kind = "markdown")),
        )

        val findings = MotionAuditDetector.findings(listOf(open, popIn, popOut, offsetJump, heightAndSnap))
        assertEquals(
            listOf(MotionFinding.Kind.PopIn, MotionFinding.Kind.PopOut, MotionFinding.Kind.OffsetJump, MotionFinding.Kind.Snap, MotionFinding.Kind.HeightSnap),
            findings.map { it.kind },
        )
        assertEquals(listOf("r3"), findings[0].rows)
        assertEquals(listOf("r3"), findings[1].rows)
        assertEquals(listOf("r1", "r2"), findings[2].rows)
        assertEquals(listOf("r2"), findings[3].rows)
        assertEquals(listOf("r1"), findings[4].rows)
    }

    /**
     * Bottom chrome moves as one: a reader at the end rides the composer's drawn top in the frame it
     * moves (a new line, attachments easing in).
     */
    @Test
    fun rowsRidingTheComposersDrawnTopAreNotAnOffsetJump() {
        // Each frame: its wall time, and how far the composer's top has risen.
        fun track(
            frames: List<Pair<Int, Double>>,
            listRise: List<Double>? = null,
            measured: Boolean = true,
            elapsed: Map<Int, Int> = emptyMap(),
        ): List<MotionFrame> = frames.mapIndexed { index, (wall, rise) ->
            val offset = 100 + (listRise?.get(index) ?: rise)
            val f = frame(
                index, wall = 1_000 + wall, offset = offset,
                rows = listOf(row("r1", y = 700 - (offset - 100), h = 60.0, kind = "user"), row("r2", y = 780 - (offset - 100), h = 80.0, kind = "markdown")),
                events = if (index == 0) listOf(apply(inserted = listOf("r1", "r2"), order = listOf("r1", "r2"), isInitial = true)) else emptyList(),
            ).copy(composerTop = if (measured) 866 - rise else null)
            f.elapsed = elapsed[index] ?: 1
            f
        }
        fun frames(rises: List<Double>): List<Pair<Int, Double>> = rises.mapIndexed { i, rise -> 17 * i to rise }
        // A new line on its spring: 22 pt over a few frames, the rows riding it.
        val newLine = track(frames(listOf(0.0, 2.0, 6.5, 11.5, 15.5, 18.5, 20.3, 21.3, 21.8, 22.0)))
        assertTrue(MotionAuditDetector.findings(newLine).isEmpty())
        assertEquals(864.0, MotionFrame(newLine[1].dictionary)?.composerTop)
        // Attachments ease in on the composer's spring, a stall merging four frames into one 64 pt step.
        val stalled = track(listOf(0 to 0.0, 17 to 4.3, 34 to 12.0, 101 to 76.0, 118 to 86.0, 135 to 91.0, 152 to 93.0), elapsed = mapOf(3 to 4))
        assertTrue(MotionAuditDetector.findings(stalled).isEmpty())
        // A spring that's a frame along when it's first drawn starts with a large step from rest, and
        // keeps gliding.
        val fromRest = track(frames(listOf(0.0, 11.7, 25.0, 35.4, 49.4, 59.0, 67.0, 72.4, 78.7, 82.7, 86.0, 88.0, 90.4, 91.7, 93.0, 94.0, 94.7)))
        assertTrue(MotionAuditDetector.findings(fromRest).isEmpty())
        // Composer and rows snapping together are a jump, not a ride: the composer stopping dead after
        // its step gives the snap away.
        val snap = track(frames(listOf(0.0, 22.0, 22.0)))
        assertEquals(listOf(MotionFinding.Kind.OffsetJump), MotionAuditDetector.findings(snap).map { it.kind })
        // The trace skips ticks that draw nothing new: the next line's snap, 300 ms on, gives the first
        // one away.
        val snaps = track(listOf(0 to 0.0, 17 to 22.0, 300 to 44.0))
        assertEquals(listOf(MotionFinding.Kind.OffsetJump), MotionAuditDetector.findings(snaps).map { it.kind })
        // A stall merging frames doesn't excuse a snap: the composer stops dead.
        val stalledSnap = track(listOf(0 to 0.0, 17 to 0.0, 67 to 22.0, 84 to 22.0), elapsed = mapOf(2 to 3))
        assertEquals(listOf(MotionFinding.Kind.OffsetJump), MotionAuditDetector.findings(stalledSnap).map { it.kind })
        // As does a merged step the stall can't account for.
        val unaccounted = track(listOf(0 to 0.0, 17 to 4.3, 34 to 12.0, 101 to 76.0))
        assertEquals(listOf(MotionFinding.Kind.OffsetJump), MotionAuditDetector.findings(unaccounted).map { it.kind })
        val leap = track(frames(listOf(0.0, 8.0, 34.0, 51.0)))
        assertEquals(listOf(MotionFinding.Kind.OffsetJump), MotionAuditDetector.findings(leap).map { it.kind })
        // The rows jumping while the composer stays put is still a jump.
        val alone = track(frames(listOf(0.0, 0.0)), listRise = listOf(0.0, 22.0))
        assertEquals(listOf(MotionFinding.Kind.OffsetJump), MotionAuditDetector.findings(alone).map { it.kind })
        // So is a list that moves farther than the composer did.
        val farther = track(frames(listOf(0.0, 2.0, 6.5)), listRise = listOf(0.0, 2.0, 24.0))
        assertEquals(listOf(MotionFinding.Kind.OffsetJump), MotionAuditDetector.findings(farther).map { it.kind })
        // And a frame with no composer measured.
        val unmeasured = track(frames(listOf(0.0, 2.0, 6.5)), measured = false)
        assertEquals(listOf(MotionFinding.Kind.OffsetJump, MotionFinding.Kind.OffsetJump), MotionAuditDetector.findings(unmeasured).map { it.kind })
    }

    @Test
    fun workingToReplyEndHandoffInPlaceIsNotAPopInOrPopOut() {
        val f1 = frame(
            1, wall = 1_000,
            rows = listOf(row("r1", y = 200.0, h = 60.0), row("working", y = 276.0, h = 28.0, kind = "working")),
            events = listOf(apply(inserted = listOf("r1", "working"), order = listOf("r1", "working"), isInitial = true)),
        )
        val f2 = frame(
            2, wall = 1_016,
            rows = listOf(row("r1", y = 200.0, h = 60.0), row("reply-end:msg_1", y = 276.0, h = 28.0, kind = "reply-end")),
            events = listOf(apply(inserted = listOf("reply-end:msg_1"), removed = listOf("working"), order = listOf("r1", "reply-end:msg_1"))),
        )
        assertTrue(MotionAuditDetector.findings(listOf(f1, f2)).isEmpty())
    }

    @Test
    fun catchesDuplicateAndOutOfOrderRows() {
        val f1 = frame(
            1, wall = 1_000,
            rows = listOf(row("r1", y = 120.0, h = 60.0, key = "msg_1"), row("r2", y = 200.0, h = 60.0, key = "msg_2")),
            events = listOf(apply(inserted = listOf("r1", "r2"), order = listOf("r1", "r2"), isInitial = true)),
        )
        // Pending row and delivered row showing msg_1 at the same time, and r2 drawn above r1.
        val f2 = frame(
            2, wall = 1_016,
            rows = listOf(
                row("r2", y = 120.0, h = 60.0, key = "msg_2"),
                row("r1", y = 200.0, h = 60.0, key = "msg_1"),
                row("pending:msg_1", y = 280.0, h = 60.0, kind = "pending", key = "msg_1"),
            ),
        )
        val kinds = MotionAuditDetector.findings(listOf(f1, f2)).map { it.kind }
        assertTrue(MotionFinding.Kind.Duplicate in kinds)
        assertTrue(MotionFinding.Kind.Order in kinds)
    }

    @Test
    fun scalesLeapThresholdsForProMotion120HzAndSlowMotionAndCatchesKeyboardPops() {
        val initial = listOf(apply(inserted = listOf("r1"), order = listOf("r1"), isInitial = true))
        // 1. At 120 Hz (periodMs = 8.33 ms):
        // - A 60 Hz producer (the motion loop stepping up to 18 pt every 16.67 ms) sampled at 120 Hz
        //   must NOT false-flag in either every-tick capture (alternating hold ticks) or changed-only
        //   trace mode (odd callbacks skipped).
        // - A true 120 Hz producer moving on back-to-back 8.33 ms ticks passes at ~9 pt/tick and flags
        //   `snap` when leaping > 12 pt on consecutive 120 Hz ticks.
        fun proMotionFrame(index: Int, seq: Int? = null, y: Double): MotionFrame {
            val f = frame(
                index, wall = 1_000 + Math.round(index * (1_000.0 / 120.0)).toInt(), motions = listOf("arrival"),
                rows = listOf(row("r1", y = y, h = 60.0)),
                events = if (index == 0) initial else emptyList(),
            )
            f.sequence = seq ?: index
            f.periodMs = 1_000.0 / 120.0
            return f
        }
        val producer60EveryTick = listOf(300.0, 282.0, 282.0, 265.0, 265.0, 250.0, 250.0, 238.0).mapIndexed { idx, y -> proMotionFrame(idx, y = y) }
        assertTrue(MotionAuditDetector.findings(producer60EveryTick).isEmpty())

        val producer60ChangedOnly = listOf(
            proMotionFrame(0, seq = 0, y = 300.0),
            proMotionFrame(1, seq = 1, y = 282.0),
            proMotionFrame(3, seq = 2, y = 265.0),
            proMotionFrame(5, seq = 3, y = 250.0),
            proMotionFrame(7, seq = 4, y = 238.0),
        )
        assertTrue(MotionAuditDetector.findings(producer60ChangedOnly).isEmpty())

        assertTrue(MotionAuditDetector.findings(listOf(proMotionFrame(0, y = 300.0), proMotionFrame(1, y = 291.0), proMotionFrame(2, y = 283.0))).isEmpty())
        assertEquals(
            listOf(MotionFinding.Kind.Snap),
            MotionAuditDetector.findings(listOf(proMotionFrame(0, y = 300.0), proMotionFrame(1, y = 291.0), proMotionFrame(2, y = 274.0)))
                .filter { it.kind.isError }.map { it.kind },
        )

        // 2. At 0.25× slow motion (60 Hz), a 10 pt single-frame step exceeds the 6 pt/frame virtual budget.
        fun slowFrame(index: Int, y: Double): MotionFrame = frame(
            index, wall = 1_000 + index * 17, motions = listOf("follow"),
            rows = listOf(row("r1", y = y, h = 60.0)),
            events = if (index == 0) initial else emptyList(),
        ).copy(timeScale = 0.25)
        assertTrue(MotionAuditDetector.findings(listOf(slowFrame(0, y = 300.0), slowFrame(1, y = 296.0))).isEmpty())
        assertEquals(listOf(MotionFinding.Kind.Snap), MotionAuditDetector.findings(listOf(slowFrame(0, y = 300.0), slowFrame(1, y = 288.0))).map { it.kind })

        // 3. A disclosure spring across a 3-frame hitch records `hitch`, not a false `snap`.
        val d0 = frame(0, wall = 1_000, motions = listOf("disclosure"), rows = listOf(row("r1", y = 300.0, h = 60.0)), events = initial)
        val d1 = frame(1, wall = 1_050, motions = listOf("disclosure"), rows = listOf(row("r1", y = 255.0, h = 60.0)))
        d1.elapsed = 3
        val hitchFindings = MotionAuditDetector.findings(listOf(d0, d1))
        assertEquals(listOf(MotionFinding.Kind.Hitch), hitchFindings.map { it.kind })
        assertTrue(hitchFindings.none { it.kind.isError })

        // 4. Keyboard transition: a smooth multi-frame curve passes, while a 1-frame pop that stops dead fails.
        val kbSmooth = listOf(300.0, 278.0, 244.0, 212.0, 188.0, 173.0, 165.0, 161.0, 160.0).mapIndexed { idx, y ->
            frame(idx, wall = 1_000 + idx * 17, offset = 400 - y, motions = listOf("keyboard"), rows = listOf(row("r1", y = y, h = 60.0)), events = if (idx == 0) initial else emptyList())
        }
        assertTrue(MotionAuditDetector.findings(kbSmooth).none { it.kind.isError })

        val kbPop = listOf(300.0, 272.0, 272.0).mapIndexed { idx, y ->
            frame(idx, wall = 1_000 + idx * 17, offset = 400 - y, motions = listOf("keyboard"), rows = listOf(row("r1", y = y, h = 60.0)), events = if (idx == 0) initial else emptyList())
        }
        assertEquals(listOf(MotionFinding.Kind.OffsetJump), MotionAuditDetector.findings(kbPop).map { it.kind })
    }

    /**
     * The keyboard's first frame carries the composer and the rows riding it past the 60 pt
     * keyboard limit. Riding the composer isn't a jump; the same step without it, or one both stop
     * dead after, still is.
     */
    @Test
    fun rowsRidingTheKeyboardsFirstFrameAreNotAJump() {
        fun keyboard(steps: List<Pair<Double, Double?>>): List<MotionFrame> {
            // A resting frame first: the first frame that draws rows is the opening.
            val start = steps.firstOrNull()?.first ?: 445.3
            val open = frame(
                0, wall = 1_000, rows = listOf(row("r1", y = start, h = 109.0)),
                events = listOf(apply(inserted = listOf("r1"), order = listOf("r1"), isInitial = true)),
            ).copy(composerTop = steps.firstOrNull()?.second)
            val frames = mutableListOf(open)
            for ((index, step) in steps.withIndex()) {
                frames += frame(
                    index + 1, wall = 1_017 + index * 17, offset = 2_191.4 + start - step.first, motions = listOf("keyboard"),
                    rows = listOf(row("r1", y = step.first, h = 109.0)),
                ).copy(composerTop = step.second)
            }
            return frames
        }
        fun errors(frames: List<MotionFrame>) = MotionAuditDetector.findings(frames).filter { it.kind.isError }.map { it.kind }
        val rows = listOf(445.3, 377.4, 335.6, 294.8, 256.5, 223.4, 195.6, 173.3, 156.1, 142.5, 132.1, 124.8)
        val composer = listOf(773.3, 705.4, 663.6, 622.8, 584.5, 551.4, 523.6, 501.3, 484.1, 470.5, 460.1, 452.8)
        val glide = keyboard(rows.zip(composer))
        assertTrue(errors(glide).isEmpty())
        // As recorded: a 4.7 pt first tick, then the 67.9 pt frame.
        val ticked = keyboard((listOf(450.0) + rows).zip(listOf(778.0) + composer))
        assertTrue(errors(ticked).isEmpty())

        // The same 67.9 pt step with the composer standing still is a snap.
        val alone = keyboard(rows.take(3).map { it to 773.3 })
        assertEquals(listOf(MotionFinding.Kind.OffsetJump), errors(alone))

        // Riding the composer, but both stop dead after: a pop.
        val pop = keyboard(listOf(445.3 to 773.3, 377.4 to 705.4, 377.4 to 705.4, 377.4 to 705.4))
        assertEquals(listOf(MotionFinding.Kind.OffsetJump), errors(pop))

        // Riding the composer up to a ceiling: a 120 pt leap is still a jump.
        val leap = keyboard(listOf(445.3 to 773.3, 325.3 to 653.3, 300.3 to 628.3))
        assertEquals(listOf(MotionFinding.Kind.OffsetJump), errors(leap))

        // Only the first step from rest: an 80 pt leap mid-glide is a jump.
        val midGlide = keyboard(listOf(445.3 to 773.3, 425.3 to 753.3, 345.3 to 673.3, 345.3 to 673.3, 345.3 to 673.3))
        assertTrue(MotionFinding.Kind.OffsetJump in errors(midGlide))
    }
}

/**
 * Port of iOS `MotionWatchTests`. iOS's `UserDefaults` suite is the shared key under test storage
 * (the watcher reads `developer-motion-watch` when `enabled` isn't given).
 * `recordsReplayContextRunningMotionsAndNeverEncodesMessageText` drops its `replayContext` asserts
 * (`PlaygroundReplayContext` belongs to the Mac-driven playgrounds, not on Android) and reads
 * `magnitudePt` from the JSON rather than matching Swift's pretty-printer spacing.
 */
class MotionWatchTests {
    @get:Rule val isolation = TestIsolationRule()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun offByDefaultAndZeroCostWhenDisabled() {
        val recorder = DiagnosticsRecorder(enabled = true)
        val watch = MotionWatch(recorder = { recorder })
        assertFalse(watch.isEnabled)
        assertFalse(watch.isActive)

        // Ingesting while off records nothing.
        watch.ingest(frame(1, wall = 1_000, rows = listOf(row("r1", y = 120.0))))
        watch.ingest(frame(2, wall = 1_016, rows = listOf(row("r1", y = 200.0))))
        assertTrue(watch.snapshot().isEmpty())
        assertTrue(recorder.snapshot().isEmpty())
    }

    @Test
    fun capturesTwoSecondWindowAroundJumpWithoutMessageContentAndAttachesToBundle() {
        val recorder = DiagnosticsRecorder(enabled = true)
        val watch = MotionWatch(
            enabled = true,
            recorder = { recorder },
            monotonicNow = { 42.5 },
            wallNow = { Instant.ofEpochSecond(2_000) },
        )
        assertTrue(watch.isActive)

        val settled = listOf(row("msg_1", y = 120.0, h = 60.0, kind = "user"), row("prt_1", y = 200.0, h = 80.0, kind = "markdown"))
        // Pre-history before the 2 s window (at t = 500 ms, outside [1000, 3000]).
        watch.ingest(
            frame(1, wall = 500, rows = settled, events = listOf(apply(inserted = listOf("msg_1", "prt_1"), order = listOf("msg_1", "prt_1"), isInitial = true))),
        )
        // Pre-frames inside [-1000 ms, 0 ms] of the jump at wall = 2_000 ms.
        watch.ingest(frame(2, wall = 1_200, rows = settled))
        watch.ingest(frame(3, wall = 1_800, rows = settled))

        // Jump at wall = 2_000 ms: tool-group:prt_2 pops in at full opacity/height.
        watch.ingest(
            frame(
                4, wall = 2_000,
                rows = settled + row("tool-group:prt_2", y = 300.0, h = 44.0, alpha = 1.0, kind = "tools", animated = false),
                events = listOf(apply(inserted = listOf("tool-group:prt_2"), order = listOf("msg_1", "prt_1", "tool-group:prt_2"))),
            ),
        )

        // Post-frames inside [0 ms, +1000 ms] and completion at wall = 3_050 ms.
        watch.ingest(frame(5, wall = 2_500, rows = settled + row("tool-group:prt_2", y = 300.0, h = 44.0, kind = "tools")))
        watch.ingest(frame(6, wall = 3_050, rows = settled + row("tool-group:prt_2", y = 300.0, h = 44.0, kind = "tools")))

        val jumps = watch.snapshot()
        assertEquals(1, jumps.size)
        val jump = jumps.first()
        assertEquals(MotionFinding.Kind.PopIn, jump.kind)
        assertEquals(listOf("tools"), jump.rowKinds)
        assertEquals(listOf("tool-group:prt_2"), jump.rows)
        assertEquals("ses_test", jump.sessionID)
        // Frame 1 (at 500 ms) and frame 6 (at 3_050 ms) are outside the [-1000 ms, +1000 ms] window
        // around 2000 ms; frame 6 finalizes the capture.
        assertEquals(listOf(2, 3, 4, 5), jump.frames.map { it.index })

        // Diagnostics timeline recorded the jump mark and highlights include it.
        val timeline = recorder.snapshot()
        assertEquals(1, timeline.size)
        assertEquals("motion_jump_pop_in", timeline.first().name)
        assertTrue(timeline.first().reason?.contains("kinds=tools rows=tool-group:prt_2") == true)

        val device = ProblemReportDevice(appVersion = "0.2.0", build = "329", distribution = "TestFlight", system = "iOS 27.0", model = "iPhone18,2")
        val bundle = ProblemReportBuilder.make(
            id = UUID.randomUUID(),
            context = ProblemReportContext(sessionID = "ses_test", serverID = "srv_test", screen = "Session"),
            device = device,
            now = Instant.ofEpochSecond(2_000),
            events = timeline,
            screenshot = null,
            motionJumps = jumps,
        )
        assertTrue(bundle.report.highlights.firstOrNull()?.contains("motion_jump_pop_in") == true)
        assertNotNull(bundle.files[ProblemReportBundle.File.MOTION_JUMPS])
        assertNotNull(bundle.files[ProblemReportBundle.File.MOTION_JUMPS_TEXT])
        val text = String(bundle.files[ProblemReportBundle.File.MOTION_JUMPS_TEXT] ?: ByteArray(0))
        assertTrue(text, text.contains("pop-in [ses_test] (tools: tool-group:prt_2)"))

        // Symmetric trimming keeps the jump frame itself (f4) when fitting a tighter budget.
        val fullSize = MotionWatch.exportJSON(jumps).toByteArray().size
        val trimmedData = MotionWatch.boundedJSON(jumps, limit = fullSize / 2)
        val decoded = json.decodeFromString(ListSerializer(MotionWatchEvent.serializer()), String(trimmedData))
        assertTrue(decoded.first().frames.any { it.index == 4 })
        assertTrue(decoded.first().frames.size < 4)

        watch.setEnabled(false)
        assertFalse(watch.isActive)
        assertTrue(watch.snapshot().isEmpty())
    }

    @Test
    fun isolatedJumpSynthesizesSettledEndpointFrameOnSettle() {
        val recorder = DiagnosticsRecorder(enabled = true)
        val watch = MotionWatch(enabled = true, recorder = { recorder })
        watch.ingest(frame(1, wall = 1_000, rows = listOf(row("r1", y = 120.0, h = 60.0)), events = listOf(apply(inserted = listOf("r1"), order = listOf("r1"), isInitial = true))))
        watch.ingest(frame(2, wall = 1_500, offset = 140.0, rows = listOf(row("r1", y = 80.0, h = 60.0))))
        watch.settle(2_600)
        val jump = watch.snapshot().first()
        assertEquals(MotionFinding.Kind.OffsetJump, jump.kind)
        assertEquals(40.0, jump.magnitudePt)
        assertEquals(listOf(1_000, 1_500, 2_500), jump.frames.map { it.wall })
        assertEquals(60, jump.frames.last().elapsed)

        // A 120 Hz recorded frame carries periodMs = 8.33 ms and synthesizes 120 elapsed frames over the 1,000 ms post-window.
        watch.clear()
        val open120 = frame(1, wall = 3_000, rows = listOf(row("r1", y = 120.0, h = 60.0)), events = listOf(apply(inserted = listOf("r1"), order = listOf("r1"), isInitial = true)))
        open120.periodMs = 1_000.0 / 120.0
        val jump120 = frame(2, wall = 3_500, offset = 140.0, rows = listOf(row("r1", y = 80.0, h = 60.0)))
        jump120.periodMs = 1_000.0 / 120.0
        watch.ingest(open120)
        watch.ingest(jump120)
        watch.settle(4_600)
        val settled120 = watch.snapshot().first()
        assertEquals(120, settled120.frames.last().elapsed)

        // Decoding an old full-format export JSON without `periodMs` defaults `periodMs` to 16.667 ms.
        val legacyFrameJSON = """
        {
          "launch": "L1",
          "index": 1,
          "sequence": 1,
          "elapsed": 1,
          "wall": 1000,
          "offset": 100,
          "insetTop": 80,
          "insetBottom": 90,
          "contentHeight": 1200,
          "height": 956,
          "cardX": 0,
          "width": 440,
          "gesture": false,
          "motions": ["arrival"],
          "phase": "live",
          "reduceMotion": false,
          "session": "ses_legacy",
          "rows": []
        }
        """
        val decodedLegacy = json.decodeFromString(MotionFrame.serializer(), legacyFrameJSON)
        assertTrue(abs(decodedLegacy.periodMs - 1_000.0 / 60.0) < 0.001)
        assertEquals("ses_legacy", decodedLegacy.session)
    }

    @Test
    fun recordsReplayContextRunningMotionsAndNeverEncodesMessageText() {
        val recorder = DiagnosticsRecorder(enabled = true)
        val watch = MotionWatch(enabled = true, recorder = { recorder })

        watch.ingest(
            frame(
                1, wall = 1_000, motions = listOf("promotion"),
                rows = listOf(row("msg_pg_steer_1", y = 300.0, h = 52.0, kind = "user")),
                events = listOf(apply(inserted = listOf("msg_pg_steer_1"), order = listOf("msg_pg_steer_1"), isInitial = true)),
            ),
        )
        watch.ingest(frame(2, wall = 1_016, motions = listOf("promotion"), rows = listOf(row("msg_pg_steer_1", y = 268.0, h = 52.0, kind = "user", animated = false))))

        val jump = watch.snapshot().first()
        assertEquals(MotionFinding.Kind.Snap, jump.kind)
        assertEquals(32.0, jump.magnitudePt)
        assertEquals(listOf("promotion"), jump.runningMotions)

        val jsonString = MotionWatch.exportJSON(listOf(jump))
        assertTrue(jsonString.contains("msg_pg_steer_1"))
        val exported = Json.parseToJsonElement(jsonString).jsonArray.first().jsonObject
        assertEquals(32.0, exported.getValue("magnitudePt").jsonPrimitive.double, 0.0)
        assertFalse(jsonString.contains("Promote this bubble"))
    }
}
