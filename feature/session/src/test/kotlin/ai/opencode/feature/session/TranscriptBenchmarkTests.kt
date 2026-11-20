package ai.opencode.feature.session

import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * `projectionCacheTailPathAndConfigurationDiffersOnlyInTailAssistantMarkdown` drops its
 * `TranscriptListViewController.Configuration.differsOnlyInTailAssistantMarkdown` half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptBenchmarkTests {
    @Test
    fun projectionCacheTailPathRebuildsWhenRevertBoundaryChangesTail() {
        val before = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Prompt"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a1-text-0", text = "Hello"))
        val after = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Prompt"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a1-text-0", text = "Hello world"))
        val cache = SessionTranscriptView.ProjectionCache()
        var computeCalls = 0
        cache.resolveVisibleItems(items = before, visibleLimit = 50, browsingWindowStartID = null, revertMessageID = null) {
            computeCalls += 1
            before
        }
        val reverted = cache.resolveVisibleItems(items = after, visibleLimit = 50, browsingWindowStartID = null, revertMessageID = "u1") {
            computeCalls += 1
            after.take(0)
        }
        assertEquals(2, computeCalls)
        assertTrue(reverted.isEmpty())
        val cleared = cache.resolveVisibleItems(items = after, visibleLimit = 50, browsingWindowStartID = null, revertMessageID = null) {
            computeCalls += 1
            after
        }
        assertEquals(3, computeCalls)
        assertEquals(after, cleared)
    }

    @Test
    fun projectionCacheTailPathAndConfigurationDiffersOnlyInTailAssistantMarkdown() {
        val baseTime = Instant.ofEpochSecond(1_760_000_000)
        val items1 = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Prompt"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a1-text-0", text = "Hello"))
        val items2 = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Prompt"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a1-text-0", text = "Hello world"))

        val cache = SessionTranscriptView.ProjectionCache()
        var computeCalls = 0
        val vis1 = cache.resolveVisibleItems(items = items1, visibleLimit = 20, browsingWindowStartID = null, revertMessageID = null) {
            computeCalls += 1
            items1
        }
        val vis2 = cache.resolveVisibleItems(items = items2, visibleLimit = 20, browsingWindowStartID = null, revertMessageID = null) {
            computeCalls += 1
            items2
        }
        assertEquals("In-place tail AssistantMarkdown update must update visibleItems in place without calling compute()", 1, computeCalls)
        assertEquals(2, vis1.size)
        assertEquals(items2, vis2)

        val (entries1, _) = cache.resolveEntries(
            visibleItems = vis1, allItems = items1, showsWorking = true, failedFinale = false, models = emptyList(),
            sentNow = null, mark = null, now = baseTime,
        )
        val (entries2, _) = cache.resolveEntries(
            visibleItems = vis2, allItems = items2, showsWorking = true, failedFinale = false, models = emptyList(),
            sentNow = null, mark = null, now = baseTime,
        )
        // What the dropped `differsOnlyInTailAssistantMarkdown` compared: only the tail reply changed.
        assertEquals(entries1.size, entries2.size)
        assertEquals(entries1.dropLast(1), entries2.dropLast(1))
        assertEquals("a1-text-0", entries2.last().id)
        assertEquals(items2.last(), (entries2.last() as? TranscriptEntry.Single)?.item)
    }

    /** An OpenCode ID minted at [at]: 12 hex digits of `ms × 4096`, then 14 characters. */
    private fun id(at: Instant, salt: Int): String {
        val value = (at.toEpochMilli() * 0x1000 + salt % 0x1000) and 0xFFFF_FFFF_FFFFL
        return "msg_" + "%012x".format(value) + "%014d".format(salt)
    }

    /** A new minute rebuilds the stamps once; seconds don't. */
    @Test
    fun stampsRebuildOnTheMinuteNotTheSecond() {
        val start = Instant.ofEpochSecond(1_760_000_040)
        val history = ArrayList<OpenCodeTranscriptItem>()
        for (turn in 0 until 50) {
            val time = start.minusSeconds((50 - turn) * 2 * 3600L)
            history += OpenCodeTranscriptItem.UserText(id = id(time, turn * 2), text = "Prompt $turn")
            history += OpenCodeTranscriptItem.AssistantMarkdown(id = id(time.plusSeconds(5), turn * 2 + 1) + "-text-0", text = "Reply $turn")
        }
        history += OpenCodeTranscriptItem.UserText(id = id(start, 9998), text = "Prompt")
        val live = id(start, 9999) + "-text-0"
        val cache = SessionTranscriptView.ProjectionCache()
        var text = "Hello"
        fun firstEntry(second: Long): TranscriptEntry {
            text += " word"
            val items = history + OpenCodeTranscriptItem.AssistantMarkdown(id = live, text = text)
            val (entries, _) = cache.resolveEntries(
                visibleItems = items, allItems = items, showsWorking = true, failedFinale = false, models = emptyList(),
                sentNow = null, mark = null, now = start.plusSeconds(second),
            )
            return entries.first()
        }
        var previous = firstEntry(0)
        assertTrue(TranscriptTimeStamps.isStamp(previous))
        var rebuilds = 0
        // Over 70 s, a batch a second.
        for (second in 1L..70L) {
            val entry = firstEntry(second)
            if (entry !== previous) rebuilds += 1
            previous = entry
        }
        assertEquals(1, rebuilds)
    }
}
