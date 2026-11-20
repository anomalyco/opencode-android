package ai.opencode.feature.session

import ai.opencode.feature.composer.DictationDrag
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.session.SessionFeature.State.TaskIdentity
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Android's is a value, reassigned; so are [DictationHoldVisuals]' `begin()`/`end()`. */
class DictationHoldTrackerTests {
    /**
     * What one recording's hold showed never carries into the next: the second starts at the
     * mic's size, with no lock target or chevrons.
     */
    @Test fun aSecondDictationStartsWithNoCircleOrGuides() {
        // The first: held long enough to grow, then slid up to lock.
        val visuals = DictationHoldVisuals().begin().copy(showsGuides = true)
        for (start in listOf(false, true)) {
            // Ended (the bar left), or a new press began without an end between.
            val next = if (start) visuals.begin() else visuals.end()
            val pose = DictationBarPose(
                hold = DictationFeature.Hold.Pose(),
                phase = DictationFeature.Phase.Preparing,
                drag = DictationDrag(),
                reducedMotion = false,
                visuals = next,
            )
            assertFalse("start: $start", pose.guidesShown)
            assertFalse("start: $start", pose.showsGrownCircle)
        }
    }

    /**
     * Switching sessions or drafts (or a dictation dropped on switch) immediately hides the
     * previous session's leaving hold and folded-composer state on the new `taskIdentity`,
     * whereas a carried dictation keeps its hold.
     */
    @Test fun dictationHoldRetentionDropsLeavingHoldAcrossSessionAndDraftSwitches() {
        val firstIdentity = TaskIdentity(serverID = "srv", visit = 1, sessionID = "ses_1")
        val secondIdentity = TaskIdentity(serverID = "srv", visit = 2, sessionID = "ses_2")
        val hold = DictationFeature.Hold.Pose(isLocked = true)

        var retention = DictationHoldRetention()
        assertNull(retention.identity)
        assertEquals(false, retention.settledRecording(recording = false, currentIdentity = firstIdentity))

        // First press on a freshly mounted screen (identity == null) adopts firstIdentity while
        // keeping settledRecording false, so the composer controls' fade runs instead of jumping
        // to true on frame 0.
        retention = retention.holdChanged(hold, firstIdentity, recording = true)
        assertEquals(firstIdentity, retention.identity)
        assertEquals(false, retention.settledRecording(recording = true, currentIdentity = firstIdentity))
        retention = retention.copy(settledRecording = true)
        // Dictation ends on session 1: leavingHold stays while its bar fades out.
        retention = retention.holdChanged(null, firstIdentity, recording = false)
        assertEquals(hold, retention.shownHold(active = null, currentIdentity = firstIdentity))
        assertEquals(true, retention.settledRecording(recording = false, currentIdentity = firstIdentity))

        // Switching to session 2 before the fade finishes never shows session 1's bar.
        assertNull(retention.shownHold(active = null, currentIdentity = secondIdentity))
        assertNull(retention.leavingHold(secondIdentity))
        assertEquals(false, retention.settledRecording(recording = false, currentIdentity = secondIdentity))

        retention = retention.syncIdentity(secondIdentity, activeHold = null, recording = false)
        assertNull(retention.leavingHold)
        assertEquals(false, retention.settledRecording)

        // When a recording is carried onto session 2, its adopted hold is shown and retained.
        val carriedHold = DictationFeature.Hold.Pose(isLocked = true, armed = DictationHoldArm.Lock)
        val thirdIdentity = TaskIdentity(serverID = "srv", visit = 3, sessionID = "ses_3")
        retention = retention.syncIdentity(thirdIdentity, activeHold = carriedHold, recording = true)
        assertEquals(carriedHold, retention.shownHold(active = carriedHold, currentIdentity = thirdIdentity))
        assertEquals(true, retention.settledRecording(recording = true, currentIdentity = thirdIdentity))
    }

    /**
     * Speaking while the mic is held or locked: each heard word changes the words the hold keeps
     * for Send, which nothing draws, so the composer's snapshot stays equal and it doesn't recompose.
     */
    @Test fun heldDictationWordsRedrawNeitherSessionNorComposer() = sessionTest { h ->
        var state = h.makeState()
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationHold(DictationFeature.Hold(text = "", isLocked = true))
        val locked = ComposerSnapshot.of(state)
        var words = ""
        for (word in 1..30) {
            words += " word$word"
            state = state.showDictation(words)
            assertEquals(locked, ComposerSnapshot.of(state))
        }
        assertEquals("The hold kept every word for Send", words, state.dictationHold?.text)
    }
}
