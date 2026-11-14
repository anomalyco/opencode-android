package ai.opencode.feature.drawer

import ai.opencode.feature.transcript.SessionSeenWatermark
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.increment
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receive
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.CachedTranscriptSnapshots
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.TranscriptCachePersistence
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * Port of iOS `TranscriptCachePersistenceTests.swift`'s Home tests: the transcript cache as Home
 * restores, ranks, touches and prunes it. The envelope and writer tests are `:server`'s, the
 * `AppFeature.serverDeleted` half `:app`'s; backup exclusion is iOS-only (Android's backup rules
 * decide).
 */
class TranscriptCachePersistenceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")

    /** `Date(timeIntervalSince1970:)`. */
    private fun date(seconds: Number): Instant = Instant.ofEpochMilli(Math.round(seconds.toDouble() * 1_000))

    private fun makeSession(id: String, updatedAt: Number, idle: Long? = null, viewed: Long? = null) = OpenCodeSession(
        id = id,
        serverTitle = id,
        projectID = "p",
        directory = "/project",
        updatedAt = date(updatedAt),
        idleMilliseconds = idle,
        viewedMilliseconds = viewed,
    )

    /**
     * One test's dependency world (iOS's process-wide storage and `withDependencies`): the shared
     * store and transcript persistence states are built in, and the stores' dependencies.
     */
    private class World(
        private val extra: DependencyValues.Builder.() -> Unit = {},
        val persistence: TranscriptCachePersistence = TranscriptCachePersistence.testValue,
    ) {
        val sharedStore: SharedStore = SharedStore.inMemory()
        val composer: ComposerPersistence = ComposerPersistence.inMemory()

        private val pinned: DependencyValues.Builder.() -> Unit = {
            this[SharedStore] = sharedStore
            this[ComposerPersistence] = composer
            this[TranscriptCachePersistence] = persistence
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            extra()
        }

        fun <R> state(block: () -> R): R = DependencyValues.withValues(DependencyValues.testing().update(pinned), block)

        fun store(scope: CoroutineScope, state: HomeFeature.State, dependencies: DependencyValues.Builder.() -> Unit = {}) =
            TestStore(state, HomeFeature(), scope) {
                pinned()
                dependencies()
            }
    }

    private val TestStore<HomeFeature.State, HomeFeature.Action>.persisted: CachedTranscriptSnapshots
        get() = withDependencies { ServerSharedKeys.cachedTranscriptSnapshots().value }

    /** `store.send(.transcriptPrefetched(…)) { $0.publishTranscript(…); $0.persistTranscriptCache(including:) }`. */
    private suspend fun TestStore<HomeFeature.State, HomeFeature.Action>.prefetch(sessionID: String, items: List<OpenCodeTranscriptItem>) =
        send(HomeFeature.Action.TranscriptPrefetched(sessionID = sessionID, items = items)) { state ->
            withDependencies { state.publishingTranscript(sessionID, items).persistingTranscriptCache(sessionID) }
        }

    @Test
    fun coldLaunchRestoresRowsAndBaselinesBeforeTheFirstRefreshCanPersist() = runTest {
        val now = date(1_800_000_000)
        val sessions = listOf(makeSession("open", updatedAt = 1_800_000_000), makeSession("other", updatedAt = 1_800_000_000))
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "cached", text = "Saved"))
        val snapshot = CachedTranscriptSnapshots(
            entries = listOf(
                CachedTranscriptSnapshots.Entry(serverID = server.persistenceID, sessionID = "open", updatedAt = now, items = items),
                CachedTranscriptSnapshots.Entry(serverID = server.persistenceID, sessionID = "other", updatedAt = now, items = items),
                CachedTranscriptSnapshots.Entry(serverID = "another-server", sessionID = "remote", updatedAt = now, items = items),
            ),
        )
        val storage = TranscriptCachePersistence.Storage.inMemory()
        storage.set(requireNotNull(snapshot.encodedForPersistence()))
        val persistence = TranscriptCachePersistence.make(storage)
        val loads = LockIsolated(0)
        val counting = TranscriptCachePersistence(
            identity = persistence.identity,
            load = {
                loads.increment()
                persistence.load()
            },
            schedule = persistence.schedule,
            flush = persistence.flush,
            remove = persistence.remove,
        )
        val world = World(
            extra = {
                this[HomeSnapshotDate] = DateClient.constant(now)
            },
            persistence = counting,
        )
        val state = world.state {
            shared(SharedKeys.lastViewedSessionIDs).update { it + (server.persistenceID to "open") }
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = emptySet()))
        }
        assertEquals("Full disk restoration must precede any refresh effect", 1, loads.value)
        assertEquals("The first render receives cached rows, never an empty state", items, state.sessionDetail?.items)
        assertEquals(false, state.sessionDetail?.isLoading)
        assertEquals(setOf("cached"), state.sessionDetail?.newMessagesBaseline)
        val store = world.store(this, state)
        val fresh = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "fresh", text = "Refreshed"))
        store.send(detail(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = "open", items = fresh))) { s ->
            store.withDependencies { s.publishingTranscript("open", fresh).persistingTranscriptCache("open")
                .settingSeenWatermark(SessionSeenWatermark(itemID = "cached", viewedMilliseconds = null), "open") }
        }
        store.finish()
        // The cache's writer schedules each change off the reducer's thread.
        var saved: CachedTranscriptSnapshots? = null
        var tries = 0
        while (tries++ < 200) {
            persistence.flush()
            saved = persistence.load()
            if (saved?.entries?.firstOrNull { it.sessionID == "open" }?.items == fresh) break
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { delay(5) }
        }
        val result = requireNotNull(saved)
        assertEquals(3, result.entries.size)
        assertEquals(fresh, result.entries.firstOrNull { it.sessionID == "open" }?.items)
        assertEquals(items, result.entries.firstOrNull { it.sessionID == "other" }?.items)
        assertEquals(items, result.entries.firstOrNull { it.serverID == "another-server" }?.items)
    }

    @Test
    fun weightedLRUEvictionOrdersNeedsInputWorkingPinnedUnreadAndRecency() = runTest {
        val baseTime = 1_700_000_000L
        val now = date(baseTime + 1_000)

        // Older timestamps on higher-priority sessions prove priority beats raw recency.
        val needsInput = makeSession("ses_input", updatedAt = baseTime + 10)
        val working = makeSession("ses_working", updatedAt = baseTime + 20)
        val pinned = makeSession("ses_pinned", updatedAt = baseTime + 30, idle = 100, viewed = 100)
        val unread = makeSession("ses_unread", updatedAt = baseTime + 40, idle = 200, viewed = 100)
        val regularSessions = (1..14).map { index ->
            makeSession("ses_reg_%02d".format(index), updatedAt = baseTime + 100 + index, idle = 100, viewed = 100)
        }
        val allSessions = listOf(needsInput, working, pinned, unread) + regularSessions

        val world = World(extra = { this[HomeSnapshotDate] = DateClient.constant(now) })
        val state = world.state {
            shared(SharedKeys.pinnedSessionKeys).set(listOf("${server.persistenceID}|${pinned.id}"))
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = allSessions, activeSessionIDs = setOf(working.id)))
                .copy(sessionDetail = null, attentionSessionIDs = setOf(needsInput.id))
        }
        val store = world.store(this, state)

        // Incrementally prefetch all 18 sessions; RAM caps at 8, persisted cache caps at 16.
        for (session in allSessions) {
            store.prefetch(session.id, listOf(OpenCodeTranscriptItem.UserText(id = "m_${session.id}", text = session.id)))
        }

        val keptIDs = store.persisted.entries.map { it.sessionID }
        assertEquals(CachedTranscriptSnapshots.MAXIMUM_ENTRIES, keptIDs.size)
        assertEquals(listOf(needsInput.id, working.id, pinned.id, unread.id), keptIDs.take(4))
        // The two oldest regular sessions (ses_reg_01, ses_reg_02) were evicted first.
        assertFalse(keptIDs.contains("ses_reg_01"))
        assertFalse(keptIDs.contains("ses_reg_02"))
        assertTrue(keptIDs.contains("ses_reg_03"))
        assertTrue(keptIDs.contains("ses_reg_14"))
        store.finish()
    }

    @Test
    fun fourteenDayTTLEvictsExpiredEntriesAndSkipsThemOnRestoreAndFallback() = runTest {
        val baseTime = 1_700_000_000L
        val expiredSession = makeSession("ses_expired", updatedAt = baseTime, idle = 100, viewed = 100)
        val touchedOldSession = makeSession("ses_touched", updatedAt = baseTime, idle = 100, viewed = 100)
        val freshSession = makeSession("ses_fresh", updatedAt = baseTime + 10 * 86_400, idle = 100, viewed = 100)
        val now = date(baseTime + 15 * 86_400)

        val seeded = CachedTranscriptSnapshots(
            entries = listOf(
                CachedTranscriptSnapshots.Entry(
                    serverID = server.persistenceID,
                    sessionID = expiredSession.id,
                    updatedAt = expiredSession.updatedAt,
                    items = listOf(OpenCodeTranscriptItem.UserText(id = "m_exp", text = "Expired")),
                ),
                CachedTranscriptSnapshots.Entry(
                    serverID = server.persistenceID,
                    sessionID = touchedOldSession.id,
                    updatedAt = touchedOldSession.updatedAt,
                    items = listOf(OpenCodeTranscriptItem.UserText(id = "m_touched", text = "Touched recently")),
                    lastAccessedAt = now.minusSeconds(2 * 86_400),
                ),
            ),
        )

        val world = World(extra = { this[HomeSnapshotDate] = DateClient.constant(now) })
        val initialState = world.state {
            ServerSharedKeys.cachedTranscriptSnapshots().set(seeded)
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(expiredSession, touchedOldSession, freshSession), activeSessionIDs = emptySet()))
        }

        // Restore skipped the 15-day-old entry but kept the entry accessed 2 days ago.
        assertNull(initialState.transcriptCache[expiredSession.id])
        assertEquals(listOf(OpenCodeTranscriptItem.UserText(id = "m_touched", text = "Touched recently")), initialState.transcriptCache[touchedOldSession.id])

        val store = world.store(this, initialState)
        store.prefetch(freshSession.id, listOf(OpenCodeTranscriptItem.UserText(id = "m_fresh", text = "Fresh")))

        // Persisting at `now` prunes the expired entry and orders `ses_touched` (accessed 2d ago) ahead of `ses_fresh` (updated 5d ago).
        assertEquals(listOf(touchedOldSession.id, freshSession.id), store.persisted.entries.map { it.sessionID })
        store.finish()
    }

    @Test
    fun sessionTappedFallsBackToPersistedSnapshotWhenAgedOutOfRAM() = runTest {
        val baseTime = 1_700_000_000L
        val now = date(baseTime + 500)
        val sessions = (1..10).map { index -> makeSession("ses_%02d".format(index), updatedAt = baseTime + index, idle = 100, viewed = 100) }

        val world = World(extra = { this[HomeSnapshotDate] = DateClient.constant(now) })
        val state = world.state {
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = emptySet())).copy(sessionDetail = null)
        }
        val store = world.store(this, state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(viewSession = { _, _, _ -> })
        }

        // Prefetch 10 sessions; RAM holds only 8 (`ses_03`...`ses_10`), while disk holds all 10.
        for (session in sessions) {
            store.prefetch(session.id, listOf(OpenCodeTranscriptItem.UserText(id = "m_${session.id}", text = "Cached ${session.id}")))
        }

        val agedOut = sessions[0]
        val agedOutItems = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m_${agedOut.id}", text = "Cached ${agedOut.id}"))
        assertEquals(HomeFeature.transcriptMemoryLimit, store.state.transcriptCache.size)
        assertNull(store.state.transcriptCache[agedOut.id])
        assertTrue(store.persisted.entries.any { it.sessionID == agedOut.id })

        // Tapping `ses_01` opens immediately from the persisted snapshot without `selectedTranscriptFetch`.
        store.send(HomeFeature.Action.SessionTapped(agedOut.id)) { s ->
            store.withDependencies {
                val read = s.updatingLastViewedSessionIDs { it + (server.persistenceID to agedOut.id) }.markingReadLocally(agedOut)
                val (next, opened) = read.makeDetail(agedOut, cachedItems = agedOutItems)
                next.copy(sessionDetail = opened)
            }
        }
        store.receive(HomeFeature.Action.ViewAcknowledged(sessionID = agedOut.id, idle = 100))
        assertNull(store.state.selectedTranscriptFetch)
        assertEquals(agedOutItems, store.state.sessionDetail?.items)
        // Touching `ses_01` promoted it to the most-recently-accessed entry in the persisted cache.
        assertEquals(agedOut.id, store.persisted.entries.firstOrNull()?.sessionID)
        assertEquals(now, store.persisted.entries.firstOrNull()?.lastAccessedAt)
        store.exhaustive = false
        store.finish()
    }

    @Test
    fun deletingSessionOrForgettingServerRemovesPersistedEntriesAndExcludesBackup() = runTest {
        val otherServer = OpenCodeServer(url = "http://10.0.0.2:4096", username = "opencode", password = "")
        val s1 = makeSession("ses_1", updatedAt = 100, idle = 100, viewed = 100)
        val s2 = makeSession("ses_2", updatedAt = 200, idle = 100, viewed = 100)

        val world = World()
        val homeState = world.state {
            val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(s1, s2), activeSessionIDs = emptySet()))
                .copy(sessionDetail = null)
            ServerSharedKeys.cachedTranscriptSnapshots().set(
                CachedTranscriptSnapshots(
                    entries = listOf(
                        CachedTranscriptSnapshots.Entry(serverID = server.persistenceID, sessionID = s1.id, updatedAt = s1.updatedAt, items = listOf(OpenCodeTranscriptItem.UserText(id = "1", text = "1"))),
                        CachedTranscriptSnapshots.Entry(serverID = server.persistenceID, sessionID = s2.id, updatedAt = s2.updatedAt, items = listOf(OpenCodeTranscriptItem.UserText(id = "2", text = "2"))),
                        CachedTranscriptSnapshots.Entry(serverID = otherServer.persistenceID, sessionID = "other_ses", updatedAt = s1.updatedAt, items = listOf(OpenCodeTranscriptItem.UserText(id = "3", text = "3"))),
                    ),
                ),
            )
            state
        }

        val homeStore = world.store(this, homeState)
        homeStore.send(HomeFeature.Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = s1.id))) { s ->
            homeStore.withDependencies {
                val purged = s.purgingSession(s1.id)
                purged.copy(recentlyDeletedIDs = purged.recentlyDeletedIDs - s1.id).persistingSnapshot(Instant.EPOCH)
            }
        }
        assertEquals(listOf(s2.id, "other_ses"), homeStore.persisted.entries.map { it.sessionID })
        homeStore.finish()
    }

    @Test
    fun catchUpPrefetchWarmsFullLookaheadPastOpenAndCachedQueueCards() = runTest {
        val baseTime = 1_700_000_000L
        val sessions = (1..5).map { index -> makeSession("ses_$index", updatedAt = baseTime + (6 - index), idle = 200, viewed = 100) }
        val world = World()
        val state = world.state {
            var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = emptySet()))
            state = state.copy(
                transcriptCache = state.transcriptCache +
                    ("ses_1" to listOf(OpenCodeTranscriptItem.UserText(id = "m_1", text = "Card 1"))) +
                    ("ses_2" to listOf(OpenCodeTranscriptItem.UserText(id = "m_2", text = "Card 2"))),
            )
            state = state.withCatchUp(CatchUpFeature.Stack(queue = sessions.map { it.id }, returnSessionID = null, returnComposer = DraftState()))
            state.openingCatchUpCard().first
        }

        val prefetched = LockIsolated(listOf<String>())
        val store = world.store(this, state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                transcript = { _, sessionID, _ ->
                    prefetched.append(sessionID)
                    listOf(OpenCodeTranscriptItem.UserText(id = "m_$sessionID", text = "Prefetched $sessionID"))
                },
            )
        }
        store.exhaustive = false

        // When the open card ("ses_1") settles its transcript, Catch Up prefetch skips both
        // the open card ("ses_1") and the already-cached card ("ses_2") and warms the next
        // three uncached cards in the queue ("ses_3", "ses_4", "ses_5").
        store.send(detail(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = "ses_1", items = listOf(OpenCodeTranscriptItem.UserText(id = "m_1", text = "Card 1")))))
        store.receive(HomeFeature.Action.TranscriptPrefetched::class)
        store.receive(HomeFeature.Action.TranscriptPrefetched::class)
        store.receive(HomeFeature.Action.TranscriptPrefetched::class)
        store.finish()

        assertEquals(listOf("ses_3", "ses_4", "ses_5"), prefetched.value)
        assertNotNull(store.state.transcriptCache["ses_3"])
        assertNotNull(store.state.transcriptCache["ses_4"])
        assertNotNull(store.state.transcriptCache["ses_5"])
    }

    @Test
    fun bumpedSessionSurvivesOnDiskWhenAnotherSessionPersistsAndPaintsNotFreshOnNextLaunch() = runTest {
        val sessionA = makeSession("ses_a", updatedAt = 300, idle = 100, viewed = 100)
        val sessionB = makeSession("ses_b", updatedAt = 400, idle = 200, viewed = 100)
        val sessionStaleRAM = makeSession("ses_stale_ram", updatedAt = 500, idle = 100, viewed = 100)
        val aItems = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m_a", text = "Accepted A"))
        val bDiskItems = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m_b", text = "Cached B from t=100"))

        val world = World(extra = { this[HomeSnapshotDate] = DateClient.constant(date(500)) })
        val state = world.state {
            val state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(sessionA, sessionB, sessionStaleRAM), activeSessionIDs = emptySet()))
            ServerSharedKeys.cachedTranscriptSnapshots().set(
                CachedTranscriptSnapshots(
                    entries = listOf(
                        // B's disk entry was written at t=100 before the session list bumped B's updatedAt to 400.
                        CachedTranscriptSnapshots.Entry(serverID = server.persistenceID, sessionID = sessionB.id, updatedAt = date(100), items = bDiskItems),
                        // Removed session must still be pruned.
                        CachedTranscriptSnapshots.Entry(
                            serverID = server.persistenceID,
                            sessionID = "ses_removed",
                            updatedAt = date(150),
                            items = listOf(OpenCodeTranscriptItem.UserText(id = "removed_disk", text = "Removed")),
                        ),
                    ),
                ),
            )
            // Pre-populate A with `aItems` in RAM so `publishTranscript` sees no item change,
            // proving `.acceptedTranscriptUpdated` still persists A to disk.
            state.copy(
                transcriptCache = state.transcriptCache +
                    (sessionA.id to aItems) +
                    (sessionB.id to bDiskItems) +
                    (sessionStaleRAM.id to listOf(OpenCodeTranscriptItem.UserText(id = "stale_ram", text = "Unaccepted RAM"))),
            )
        }

        val store = world.store(this, state)
        store.exhaustive = false

        store.send(detail(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = sessionA.id, items = aItems)))

        val persisted = store.persisted.entries
        assertEquals(setOf(sessionA.id, sessionB.id), persisted.map { it.sessionID }.toSet())
        assertEquals(date(100), persisted.firstOrNull { it.sessionID == sessionB.id }?.updatedAt)
        assertFalse(persisted.any { it.sessionID == sessionStaleRAM.id })
        assertFalse(persisted.any { it.sessionID == "ses_removed" })

        // On the next launch with B remembered, B survives restore and paints frame 1 as not fresh.
        val nextWorld = World(extra = { this[HomeSnapshotDate] = DateClient.constant(date(500)) })
        val nextLaunch = nextWorld.state {
            ServerSharedKeys.cachedTranscriptSnapshots().set(store.persisted)
            shared(SharedKeys.lastViewedSessionIDs).update { it + (server.persistenceID to sessionB.id) }
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(sessionA, sessionB), activeSessionIDs = emptySet()))
        }

        assertEquals(bDiskItems, nextLaunch.transcriptCache[sessionB.id])
        assertEquals(sessionB.id, nextLaunch.sessionDetail?.session?.id)
        assertEquals(bDiskItems, nextLaunch.sessionDetail?.items)
        assertEquals(false, nextLaunch.sessionDetail?.isLoading)
        assertEquals(false, nextLaunch.sessionDetail?.initialTranscriptIsFresh)
        store.finish()
    }
}
