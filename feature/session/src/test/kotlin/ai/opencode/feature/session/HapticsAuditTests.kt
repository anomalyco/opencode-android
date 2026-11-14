package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.AttachmentPickerButton
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.LocationClient
import ai.opencode.feature.composer.LocationClientError
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.plusmenu.PlusPhotoLibrary
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.media.OpenURLClient
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.HapticsRecorder
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Port of the `SessionFeature` (and session-module `SessionSheetFeature` / `ShellOutputFeature`) tests of the
 * `HapticsAuditTests` suite in iOS `HapticsAuditTests.swift`: which interactions play which haptics.
 *
 * Not ported here (:feature:drawer, `HomeFeature`): `homeHaptics`.
 * Not ported here (:app, `AppFeature`): the `AppFeature.copyTextTapped` step of `copyingPlaysATapWhileOpeningLocationSettingsStaysQuiet`
 * (its expected log here leaves that one tap out).
 * UIKit-only: the `SessionView.composerSwipeYields` / `performSwipeUpFocus` window and text-view checks of
 * `swipingUpOnTheComposerTapsAndRequestsFocusUnlessDictating` (its reducer half is here).
 * Not ported here (:core, :feature:voice): the `LiveHapticsTests` suite (see docs/parity/core.md).
 */
class HapticsAuditTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.EPOCH)
    private val json = Json { ignoreUnknownKeys = true }

    private fun Harness.sessionState(isRunning: Boolean = false): SessionFeature.State =
        state { SessionFeature.State.session(server = server, session = session, cachedItems = emptyList(), isRunning = isRunning) }

    private fun Harness.sessionStore(
        state: SessionFeature.State?,
        log: HapticsRecorder,
        dependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<SessionFeature.State, Action> {
        val store = store(state ?: sessionState()) {
            this[UuidClient] = UuidClient.incrementing()
            this[HapticsClient] = log.client
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
            dependencies()
        }
        store.exhaustive = false
        return store
    }

    private val HapticsRecorder.names: List<String> get() = log.map { it.name }

    /** iOS `log.setValue([])`: the names played from here on. */
    private class Since(private val log: HapticsRecorder) {
        private var start = log.log.size
        fun reset() { start = log.log.size }
        val names: List<String> get() = log.log.drop(start).map { it.name }
    }

    private fun DependencyValues.Builder.dictation(
        start: suspend (Int) -> kotlinx.coroutines.channels.ReceiveChannel<String>,
        stop: suspend (Int) -> Unit = {},
        cancel: suspend (Int) -> Unit = {},
    ) {
        this[DictationClient] = DictationClient(start = start, stop = stop, cancel = cancel)
    }

    private val dictationReady: (Action) -> Boolean = { it == Action.dictationReady }

    /** iOS `@Test(arguments: [.sendTapped, .queueTapped])`. */
    @Test fun sendingSteeredOrQueuedIsOneTap() {
        for (action in listOf(Action.SendTapped, Action.QueueTapped)) sessionTest { h ->
            val log = HapticsRecorder()
            val store = h.sessionStore(h.sessionState().withDraft("Ship it"), log)
            store.send(action)
            assertEquals("$action", listOf("tap"), log.names)
            h.skipInFlightEffects()
        }
    }

    /**
     * iOS `withDependencies { $0.haptics = .recording(log) } operation: { PlusPhotoLibrary() }`. The library's scope binds
     * `Dispatchers.Main` when it's made; these tests never launch on it.
     */
    private fun plusPhotoLibrary(log: HapticsRecorder): PlusPhotoLibrary {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            return withDependencies({ this[HapticsClient] = log.client }) { PlusPhotoLibrary() }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun addingPhotosFromTheSheetIsOneHaptic() = sessionTest { h ->
        val log = HapticsRecorder()
        val library = plusPhotoLibrary(log)
        library.toggle("p1")
        library.toggle("p2")
        assertEquals("One per pick", listOf("selection", "selection"), log.names)
        val since = Since(log)
        library.addPressed()
        val store = h.sessionStore(null, log)
        for (byte in listOf<Byte>(1, 2, 3)) {
            store.send(Action.ComposerAttachments(ComposerAttachmentsFeature.Action.AttachmentLanded(OpenCodeOutgoingAttachment(data = byteArrayOf(byte), mime = "image/jpeg"))))
        }
        assertEquals(3, store.state.pendingAttachments.size)
        assertEquals(listOf("tap"), since.names)
        // A paste still ticks once as it lands.
        store.send(Action.attachmentAdded(OpenCodeOutgoingAttachment(data = byteArrayOf(4), mime = "image/jpeg")))
        assertEquals(listOf("tap", "selection"), since.names)
        h.skipInFlightEffects()
    }

    /** Eight photos at most; a tap past it warns instead of silently doing nothing. */
    @Test fun pickingPastTheLimitWarns() {
        val log = HapticsRecorder()
        val library = plusPhotoLibrary(log)
        assertEquals(8, AttachmentPickerButton.MAX_PHOTO_SELECTION)
        for (index in 1..8) library.toggle("p$index")
        assertEquals(8, library.picks.size)
        val since = Since(log)
        library.toggle("p9")
        assertEquals(8, library.picks.size)
        assertEquals(listOf("warning"), since.names)
        library.toggle("p3")
        library.toggle("p9")
        assertEquals(8, library.order("p9"))
    }

    /** Back keeps the picks on show (pill and badges) while the sheet folds. */
    @Test fun backKeepsThePicksShownWhileTheSheetFolds() {
        val library = plusPhotoLibrary(HapticsRecorder())
        library.toggle("p1")
        library.toggle("p2")
        library.setAsidePicks()
        assertTrue(library.picks.isEmpty())
        assertEquals(2, library.shownPickCount)
        assertEquals(2, library.order("p2"))
        library.resetClosingPicks()
        assertNull(library.order("p2"))
    }

    @Test fun aFailedSendIsAnError() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log)
        store.send(
            Action.PromptFailed(
                sessionID = session.id,
                error = OpenCodeClientError(message = "Offline"),
                pending = PendingPrompt(messageID = "msg_1", text = "Ship it", attachments = emptyList(), requestEpoch = 1),
            ),
        )
        assertEquals(listOf("error"), log.names)
        h.skipInFlightEffects()
    }

    private val permission: OpenCodePermissionRequest
        get() = json.decodeFromString(
            OpenCodePermissionRequest.serializer(),
            """{"id":"per_1","sessionID":"ses_1","action":"shell","resources":["git status"],"source":{"type":"tool","messageID":"msg_1","id":"call_1"}}""",
        )

    @Test fun answeringARequestIsOneSuccessNotATapAndASuccess() = sessionTest { h ->
        val permission = permission
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(replyPermission = { _, _, _, _, _ -> })
        }
        // A request that arrives while the session is open docks with a single attention tap.
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(permission), forms = emptyList()))))
        assertNull(store.state.activePicker)
        assertEquals(permission.id, store.state.dockRequestID)
        assertEquals(listOf("attention"), log.names)
        store.send(Action.permissionReplyTapped(permission.id, OpenCodePermissionReply.Once))
        store.receive({ (it as? Action.RequestDock)?.action is ai.opencode.feature.composer.RequestDockFeature.Action.ResponseFinished }, "requestResponseFinished")
        assertEquals(listOf("attention", "success"), log.names)
        h.skipInFlightEffects()
    }

    @Test fun aCoveredScreenLeavesTheArrivalToTheScreenOnTop() = sessionTest { h ->
        val permission = permission
        val log = HapticsRecorder()
        val store = h.sessionStore(h.sessionState().copy(isCovered = true), log)
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(permission), forms = emptyList()))))
        assertEquals(permission.id, store.state.dockRequestID)
        assertTrue(log.names.isEmpty())
        h.skipInFlightEffects()
    }

    @Test fun anIncompleteFormIsAnError() = sessionTest { h ->
        val form = json.decodeFromString(
            OpenCodeFormRequest.serializer(),
            """{"id":"frm_1","sessionID":"ses_1","title":"Deploy","fields":[{"key":"note","type":"string","title":"Note","required":true}]}""",
        )
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log)
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(form)))))
        val since = Since(log)
        store.send(Action.formSubmitTapped(form.id))
        assertNotNull(store.state.requestErrors[form.id])
        assertEquals(listOf("error"), since.names)
        h.skipInFlightEffects()
    }

    @Test fun dictationStartsWithATapAndLocksWithASelection() = sessionTest { h ->
        val log = HapticsRecorder()
        val transcripts = Channel<String>(Channel.UNLIMITED)
        val store = h.sessionStore(null, log) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            dictation(start = { transcripts }, stop = { transcripts.close() })
        }
        store.send(Action.micPressBegan)
        store.receive(dictationReady, "dictationReady")
        store.send(Action.micPressLocked)
        assertEquals(listOf("tap", "selection"), log.names)
        transcripts.close()
        h.skipInFlightEffects()
    }

    @Test fun armingAHeldMicCrossesAThresholdAndDisarmingTicks() = sessionTest { h ->
        val log = HapticsRecorder()
        val transcripts = Channel<String>(Channel.UNLIMITED)
        val store = h.sessionStore(null, log) {
            dictation(start = { transcripts }, stop = { transcripts.close() })
        }
        store.send(Action.micPressBegan)
        store.receive(dictationReady, "dictationReady")
        val since = Since(log)
        store.send(Action.micPressArmed(DictationFeature.Hold.Arm.Lock))
        store.send(Action.micPressArmed(DictationFeature.Hold.Arm.Lock))
        store.send(Action.micPressArmed(null))
        store.send(Action.micPressArmed(DictationFeature.Hold.Arm.Cancel))
        // Arming crosses a threshold and disarming ticks, the same for lock and cancel.
        assertEquals(listOf("threshold", "selection", "threshold"), since.names)
        transcripts.close()
        h.skipInFlightEffects()
    }

    @Test fun cancellingDictationBacksOut() = sessionTest { h ->
        val log = HapticsRecorder()
        val transcripts = Channel<String>(Channel.UNLIMITED)
        val store = h.sessionStore(null, log) {
            dictation(start = { transcripts }, stop = { transcripts.close() }, cancel = { transcripts.close() })
        }
        store.send(Action.micPressBegan)
        store.receive(dictationReady, "dictationReady")
        store.send(Action.micPressLocked)
        val since = Since(log)
        store.send(Action.micPressCancelled)
        assertEquals(listOf("cancel"), since.names)
        transcripts.close()
        h.skipInFlightEffects()
    }

    @Test fun stopBacksOut() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.sessionStore(h.sessionState(isRunning = true), log) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(interrupt = { _, _ -> true })
        }
        store.send(Action.InterruptTapped)
        assertEquals(listOf("cancel"), log.names)
        h.skipInFlightEffects()
    }

    @Test fun pickingAModelIsASelection() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log)
        val model = OpenCodeModel(modelID = "m1", providerID = "p1", name = "Model One")
        store.send(Action.ModelPicked(model))
        assertEquals(listOf("selection"), log.names)
        h.skipInFlightEffects()
    }

    @Test fun aDictatedSendTapsOnceAtThePress() = sessionTest { h ->
        val log = HapticsRecorder()
        val transcripts = Channel<String>(Channel.UNLIMITED)
        val store = h.sessionStore(null, log) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            dictation(start = { transcripts }, stop = {
                transcripts.send("Ship it")
                transcripts.close()
            })
        }
        store.send(Action.micPressBegan)
        store.receive(dictationReady, "dictationReady")
        store.send(Action.micPressLocked)
        val since = Since(log)
        store.send(Action.dictationSendTapped)
        store.receive(Action.DeferredSendReady::class)
        assertEquals(listOf("tap"), since.names)
        h.skipInFlightEffects()
    }

    @Test fun reChoosingTheCurrentModelOrLevelIsQuiet() = sessionTest { h ->
        val log = HapticsRecorder()
        val model = OpenCodeModel(modelID = "m1", providerID = "p1", name = "Model One", variants = listOf("low", "high"))
        val store = h.sessionStore(h.sessionState().copy(currentModel = model.ref("high")), log)
        store.send(Action.ModelPicked(model))
        store.send(Action.VariantPicked("high"))
        assertTrue(log.names.isEmpty())
        store.send(Action.VariantPicked("low"))
        assertEquals(listOf("selection"), log.names)
        h.skipInFlightEffects()
    }

    private val projects = listOf("opencode-ios", "opencode-slack").map { OpenCodeProject(id = it, name = it, directory = "/srv/$it") }

    private fun Harness.newSessionStore(log: HapticsRecorder, picker: Boolean = false): TestStore<SessionFeature.State, Action> {
        var state = state { SessionFeature.State.draft(server = server, draftProject = projects[0], projects = projects) }
        if (picker) state = state.openProjectPicker()
        return store(state) { this[HapticsClient] = log.client }
    }

    @Test fun pickingAnotherRecentProjectIsASelectionAndTheChosenOneIsQuiet() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.newSessionStore(log)
        store.send(Action.DraftProjectTapped(projects[0]))
        assertTrue("The project already chosen is a no-op", log.names.isEmpty())
        store.send(Action.DraftProjectTapped(projects[1])) { it.copy(draftProject = projects[1]) }
        assertEquals(listOf("selection"), log.names)
        store.send(Action.DraftProjectTapped(projects[1]))
        assertEquals(listOf("selection"), log.names)
        store.finish()
    }

    @Test fun allProjectsOpensQuietlyAndAPickInsideItIsASelection() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.newSessionStore(log)
        store.send(Action.ProjectButtonTapped) { it.openProjectPicker() }
        assertTrue("Opening the sheet is navigation", log.names.isEmpty())
        store.send(Action.ProjectPicked(projects[1])) { it.copy(draftProject = projects[1], activePicker = null) }
        assertEquals(listOf("selection"), log.names)
        store.finish()
    }

    @Test fun pickingTheChosenProjectInTheSheetIsQuiet() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.newSessionStore(log, picker = true)
        store.send(Action.ProjectPicked(projects[0])) { it.copy(activePicker = null) }
        assertTrue(log.names.isEmpty())
        store.finish()
    }

    @Test fun aSystemActionMovingTheDraftIsQuiet() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.newSessionStore(log)
        store.send(Action.ProjectPicked(projects[1])) { it.copy(draftProject = projects[1]) }
        assertTrue("A shortcut or the voice agent moving the draft isn't a press", log.names.isEmpty())
        store.finish()
    }

    /** The reducer half (the `SessionView` swipe gating with a `UIWindow` is UIKit-only). */
    @Test fun swipingUpOnTheComposerTapsAndRequestsFocusUnlessDictating() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log)
        store.send(Action.ComposerSwipedUp) { it.copy(composerFocusRequest = 1) }
        assertEquals(listOf("tap"), log.names)

        // While dictating, the folded bar owns the composer: swipe-up does nothing.
        val dictatingStore = h.sessionStore(h.sessionState().withDictationPhase(DictationFeature.Phase.Recording), log)
        dictatingStore.send(Action.ComposerSwipedUp)
        assertEquals(0, dictatingStore.state.composerFocusRequest)
        assertEquals(listOf("tap"), log.names)
        h.skipInFlightEffects()
    }

    @Test fun compactingCompletesWithSuccessAndForkingNavigatesQuietly() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.store(SessionSheetFeature.State(server = server, sessionID = session.id), { SessionSheetFeature() }) {
            this[HapticsClient] = log.client
            this[OpenCodeClient] = this[OpenCodeClient].copy(compactSession = { _, _ -> })
        }
        store.exhaustive = false
        store.send(SessionSheetFeature.Action.CompactTapped)
        store.receive({ it is SessionSheetFeature.Action.CompactFinished }, "compactFinished")
        assertEquals(listOf("success"), log.names)
        store.send(SessionSheetFeature.Action.ForkFinished(Result.success(session)))
        assertEquals(listOf("success"), log.names)
        store.skipReceivedActions()
        h.skipInFlightEffects()
    }

    @Test fun copyingPlaysATapWhileOpeningLocationSettingsStaysQuiet() = sessionTest { h ->
        val log = HapticsRecorder()
        val copied = LockIsolated(listOf<String>())
        val copiedURLs = LockIsolated(listOf<String>())
        val opened = LockIsolated(listOf<String>())
        val state = h.sessionState().withLocationError(LocationClientError.Denied.message)
        val store = h.sessionStore(state, log) {
            this[PasteboardClient] = PasteboardClient(setURL = { copiedURLs.append(it) }, setString = { copied.append(it) })
            this[OpenURLClient] = OpenURLClient(open = { opened.append(it); true })
            this[LocationClient] = LocationClient(current = { throw LocationClientError.Denied }, openSettings = { opened.append("app-settings:") })
        }
        store.send(Action.CopyTextTapped(""))
        assertTrue("An empty copy is a no-op", log.names.isEmpty())
        store.send(Action.CopyTextTapped("markdown reply"))
        assertEquals(listOf("markdown reply"), copied.value)
        assertEquals(listOf("tap"), log.names)
        store.send(Action.LinkMenu(LinkMenuAction.Copy, "https://opencode.ai/docs"))
        assertEquals(listOf("https://opencode.ai/docs"), copiedURLs.value)
        assertEquals(listOf("tap", "tap"), log.names)
        store.send(Action.SessionInfoTapped)
        assertEquals(SessionFeature.State.ActivePicker.session, store.state.activePicker)
        assertEquals("Opening the session sheet from the title taps", listOf("tap", "tap", "tap"), log.names)
        store.send(Action.locationSettingsTapped)
        assertNull(store.state.locationError)
        settle()
        assertEquals(1, opened.value.size)
        assertEquals("Opening location settings is navigation, so quiet", listOf("tap", "tap", "tap"), log.names)
        h.skipInFlightEffects()

        reopened { h2 ->
            val sheetStore = h2.store(SessionSheetFeature.State(server = server, sessionID = session.id), { SessionSheetFeature() }) {
                this[HapticsClient] = log.client
                this[PasteboardClient] = PasteboardClient(setURL = {}, setString = { copied.append(it) })
            }
            sheetStore.send(SessionSheetFeature.Action.CopySessionIDTapped)
            sheetStore.finish()
            assertEquals(listOf("markdown reply", session.id), copied.value)
            assertEquals(listOf("tap", "tap", "tap", "tap"), log.names)

            val shellState = ShellOutputFeature.State(
                server = server,
                directory = session.directory,
                shell = OpenCodeShell(id = "sh_1", command = "ls", sessionID = session.id, status = OpenCodeShell.Status.Exited),
            ).copy(output = "README.md\n")
            val shellStore = h2.store(shellState, { ShellOutputFeature() }) {
                this[HapticsClient] = log.client
                this[PasteboardClient] = PasteboardClient(setURL = {}, setString = { copied.append(it) })
            }
            shellStore.send(ShellOutputFeature.Action.CopyOutputTapped)
            shellStore.finish()
            assertEquals("README.md\n", copied.value.last())
            assertEquals(listOf("tap", "tap", "tap", "tap", "tap"), log.names)
        }
    }

    @Test fun quotingSelectedTextPlaysOneTap() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log)
        store.send(Action.QuoteTextTapped("Keep the existing parser boundary"))
        assertEquals(listOf("tap"), log.names)
        store.send(Action.QuoteTextTapped("   "))
        assertEquals(listOf("tap"), log.names)
        h.skipInFlightEffects()
    }

    private suspend fun reopened(block: suspend (Harness) -> Unit) {
        kotlinx.coroutines.coroutineScope {
            val harness = Harness(this)
            try {
                block(harness)
            } finally {
                harness.skipInFlightEffects()
            }
        }
    }
}
