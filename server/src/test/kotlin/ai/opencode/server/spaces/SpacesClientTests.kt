package ai.opencode.server.spaces

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.live.decodeStreamFrame
import ai.opencode.server.live.decodeStreamID
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SpacesClientTests {
    @get:Rule val isolation = TestIsolationRule()

    private val sync = "POST /api/experimental/space/sync"
    private val subscribe = "POST /api/experimental/space/subscribe"
    private val write = "POST /api/experimental/space/write"

    private fun page(
        type: String,
        revision: Long,
        entries: Map<String, String>,
        cursor: String,
        next: String? = null,
        deleted: List<String> = emptyList(),
        space: String = "pins",
    ): String {
        val rows = entries.entries.joinToString(",") { """{"key":"${it.key}","value":"${it.value}"}""" }
        val tail = (if (type == "delta") ""","deleted":[${deleted.joinToString(",") { "\"$it\"" }}]""" else "") +
            (next?.let { ""","next":"$it"""" } ?: "")
        return """{"type":"$type","space":"$space","epoch":"e","revision":$revision,"entries":[$rows],"cursor":"$cursor"$tail}"""
    }

    /** The spaces a sync asked for, with their cursors. */
    private fun asked(request: WireFixture.Request): List<Pair<String, String?>> = request.json()["spaces"]!!.jsonArray.map {
        it.jsonObject["space"]!!.jsonPrimitive.content to it.jsonObject["cursor"]?.jsonPrimitive?.contentOrNull
    }

    /** Answers a sync with a page for each space it asks for, in order; null leaves the space without one. */
    private fun pages(request: WireFixture.Request, page: (space: String, cursor: String?) -> String?): Reply =
        Reply.Json(200, asked(request).mapNotNull { (space, cursor) -> page(space, cursor) }.joinToString(",", """{"pages":[""", "]}"))

    /** Each sync, as `space@cursor` for every space it asked for. */
    private fun synced(fixture: WireFixture) =
        fixture.requests.filter { it.route == sync }.map { request -> asked(request).joinToString(" ") { "${it.first}@${it.second}" } }

    private fun subscription(request: WireFixture.Request) = request.json().toString()

    private fun subscribed(stream: String, vararg cursors: Pair<String, String>) = Json.parseToJsonElement(
        """{"stream":"$stream","spaces":[${cursors.joinToString(",") { """{"space":"${it.first}","cursor":"${it.second}"}""" }}]}""",
    ).toString()

    private fun written(request: WireFixture.Request) = request.json()["changes"].toString()

    private fun changes(vararg changes: Pair<String, String?>) = Json.parseToJsonElement(
        changes.joinToString(",", "[", "]") { (key, value) ->
            """{"space":"pins","key":"$key","value":${value?.let { "\"$it\"" } ?: "null"}}"""
        },
    ).toString()

    @Test fun aSyncFollowsEveryPageThenSubscribesTheStreamFromTheLastCursor() = runTest {
        var continuations = 0
        val fixture = WireFixture { request ->
            when (request.route) {
                sync -> {
                    if (asked(request).single().second == "e.2.4" && continuations++ == 0) return@WireFixture Reply.status(500)
                    pages(request) { _, cursor ->
                        when (cursor) {
                            null -> page("snapshot", 2, mapOf("a" to "1", "b" to "1"), cursor = "e.2", next = "e.2.4")
                            "e.2.4" -> page("snapshot", 4, mapOf("c" to "1"), cursor = "e.4")
                            else -> null
                        }
                    }
                }
                subscribe -> Reply.status(204)
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            var shown: SpaceEntries? = null
            backgroundScope.launch { client.entries(fixture.server, "pins").collect { shown = it } }
            runCurrent()
            assertTrue("nothing to sync before the stream is known", fixture.requests.isEmpty())

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))
            runCurrent()
            assertEquals("the first sync's pages show as they arrive", SpaceEntries(mapOf("a" to "1", "b" to "1"), isSyncComplete = false), shown)

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s2"))
            runCurrent()
            assertEquals(SpaceEntries(mapOf("a" to "1", "b" to "1", "c" to "1"), isSyncComplete = true), shown)
            assertEquals(listOf("pins@null", "pins@e.2.4", "pins@null", "pins@e.2.4"), synced(fixture))
            assertEquals(subscribed("s2", "pins" to "e.4"), subscription(fixture.requests.single { it.route == subscribe }))
        } finally {
            fixture.close()
        }
    }

    @Test fun theRowBudgetLeavesASpaceWithoutAPageAndItAsksAgainWithTheSameCursor() = runTest {
        val fixture = WireFixture { request ->
            when (request.route) {
                sync -> {
                    val budgetSpent = asked(request).size > 1
                    pages(request) { space, cursor ->
                        when {
                            space == "pins" && cursor == null -> page("snapshot", 1, mapOf("a" to "1"), cursor = "p.1")
                            space == "tags" && cursor == null && !budgetSpent ->
                                page("snapshot", 1, mapOf("x" to "1"), cursor = "t.1", space = "tags")
                            else -> null
                        }
                    }
                }
                subscribe -> Reply.status(204)
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            backgroundScope.launch { client.entries(fixture.server, "pins").collect {} }
            backgroundScope.launch { client.entries(fixture.server, "tags").collect {} }
            runCurrent()
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))

            assertEquals(listOf("pins@null tags@null", "tags@null"), synced(fixture))
            assertEquals(mapOf("a" to "1"), client.entries(fixture.server, "pins").first().entries)
            assertEquals(mapOf("x" to "1"), client.entries(fixture.server, "tags").first().entries)
            assertEquals(
                "both spaces in one subscribe",
                listOf(subscribed("s1", "pins" to "p.1", "tags" to "t.1")),
                fixture.requests.filter { it.route == subscribe }.map(::subscription),
            )
        } finally {
            fixture.close()
        }
    }

    @Test fun aSnapshotRemovesWhatItDidntHoldOnlyAfterItsLastPage() = runTest {
        var continuations = 0
        val fixture = WireFixture { request ->
            when (request.route) {
                sync -> {
                    if (asked(request).single().second == "e.5.6" && continuations++ == 0) return@WireFixture Reply.status(500)
                    pages(request) { _, cursor ->
                        when (cursor) {
                            null -> page("snapshot", 1, mapOf("a" to "1", "b" to "1", "old" to "1"), cursor = "e.1")
                            "e.1" -> page("snapshot", 5, mapOf("a" to "2"), cursor = "e.5", next = "e.5.6")
                            "e.5.6" -> page("snapshot", 6, mapOf("b" to "1"), cursor = "e.6")
                            else -> null
                        }
                    }
                }
                subscribe -> Reply.status(204)
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            backgroundScope.launch { client.entries(fixture.server, "pins").collect {} }
            runCurrent()
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceResync("pins"))
            assertEquals(
                "a snapshot page applies at once, but removes nothing before its last page",
                mapOf("a" to "2", "b" to "1", "old" to "1"),
                client.entries(fixture.server, "pins").first().entries,
            )

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s2"))
            assertEquals(mapOf("a" to "2", "b" to "1"), client.entries(fixture.server, "pins").first().entries)
            assertEquals(
                "a reconnect mid-snapshot starts again from the last whole cursor",
                listOf("pins@null", "pins@e.1", "pins@e.5.6", "pins@e.1", "pins@e.5.6"),
                synced(fixture),
            )
        } finally {
            fixture.close()
        }
    }

    @Test fun aDeltaPageAppliesAtOnceAndItsCursorIsKept() = runTest {
        val fixture = WireFixture { request ->
            when (request.route) {
                sync -> {
                    if (asked(request).single().second == "e.2") return@WireFixture Reply.status(500)
                    pages(request) { _, cursor ->
                        when (cursor) {
                            null -> page("snapshot", 1, mapOf("a" to "1"), cursor = "e.1")
                            "e.1" -> page("delta", 2, mapOf("b" to "1"), cursor = "e.2", next = "e.2")
                            else -> null
                        }
                    }
                }
                subscribe -> Reply.status(204)
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            backgroundScope.launch { client.entries(fixture.server, "pins").collect {} }
            runCurrent()
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceResync("pins"))
            assertEquals("the delta shows though its next page failed", mapOf("a" to "1", "b" to "1"), client.entries(fixture.server, "pins").first().entries)

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s2"))
            assertEquals(listOf("pins@null", "pins@e.1", "pins@e.2", "pins@e.2"), synced(fixture))
        } finally {
            fixture.close()
        }
    }

    @Test fun eventsApplyInOrderAndOnesTheSyncCoveredAreSkipped() = runTest {
        val fixture = WireFixture { request ->
            when (request.route) {
                sync -> pages(request) { _, _ -> page("snapshot", 5, mapOf("a" to "1", "b" to "1"), cursor = "e.5") }
                subscribe -> Reply.status(204)
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))
            assertEquals(mapOf("a" to "1", "b" to "1"), client.entries(fixture.server, "pins").first().entries)
            assertEquals("the stream was known: one sync", 2, fixture.requests.size)

            client.receive(fixture.server, OpenCodeServerEvent.SpaceUpdated("pins", "c", "1", revision = 6))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceDeleted("pins", "a", revision = 7))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceUpdated("pins", "old", "1", revision = 5))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceUpdated("other", "x", "1", revision = 8))

            assertEquals(mapOf("b" to "1", "c" to "1"), client.entries(fixture.server, "pins").first().entries)
            assertEquals("events cost no requests", 2, fixture.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test fun aNewStreamOrAResyncSyncsAgainFromTheSavedCursorThenSubscribes() = runTest {
        val fixture = WireFixture { request ->
            when (request.route) {
                sync -> pages(request) { _, cursor ->
                    when (cursor) {
                        null -> page("snapshot", 1, mapOf("a" to "1"), cursor = "e.1")
                        "e.1" -> page("delta", 3, mapOf("b" to "1"), cursor = "e.3", deleted = listOf("a"))
                        "e.3" -> page("snapshot", 9, mapOf("c" to "1"), cursor = "e.9")
                        else -> null
                    }
                }
                subscribe -> Reply.status(204)
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            val seen = mutableListOf<Map<String, String>>()
            backgroundScope.launch { client.entries(fixture.server, "pins").collect { if (seen.lastOrNull() != it.entries) seen += it.entries } }
            runCurrent()
            suspend fun receive(event: OpenCodeServerEvent) {
                client.receive(fixture.server, event)
                runCurrent()
            }
            receive(OpenCodeServerEvent.StreamOpened("s1"))
            // A live event moves no cursor: the reconnect's delta still starts from the sync's.
            receive(OpenCodeServerEvent.SpaceUpdated("pins", "b", "1", revision = 2))
            receive(OpenCodeServerEvent.StreamOpened("s2"))
            receive(OpenCodeServerEvent.StreamOpened("s2"))
            receive(OpenCodeServerEvent.SpaceResync("pins"))

            assertEquals(listOf(mapOf("a" to "1"), mapOf("a" to "1", "b" to "1"), mapOf("b" to "1"), mapOf("c" to "1")), seen)
            assertEquals(listOf("pins@null", "pins@e.1", "pins@e.3"), synced(fixture))
            assertEquals(
                "the same stream again changes nothing",
                listOf(subscribed("s1", "pins" to "e.1"), subscribed("s2", "pins" to "e.3"), subscribed("s2", "pins" to "e.9")),
                fixture.requests.filter { it.route == subscribe }.map(::subscription),
            )
        } finally {
            fixture.close()
        }
    }

    @Test fun aServerWithoutSpacesIsLeftAloneUntilItsNextStream() = runTest {
        val fixture = WireFixture { Reply.status(404) }
        try {
            val client = SpaceStore().client
            val seen = mutableListOf<Map<String, String>>()
            backgroundScope.launch { client.entries(fixture.server, "pins").collect { if (seen.lastOrNull() != it.entries) seen += it.entries } }
            runCurrent()
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceResync("pins"))
            backgroundScope.launch { client.entries(fixture.server, "pins").collect { if (seen.lastOrNull() != it.entries) seen += it.entries } }
            runCurrent()
            client.write(fixture.server, "pins", mapOf("a" to "1"))
            client.write(fixture.server, "pins", mapOf("a" to null))

            assertTrue(seen.isEmpty())
            assertEquals("one sync, no retries and no writes", listOf(sync), fixture.requests.map { it.route })

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s2"))
            assertEquals(2, fixture.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test fun aWriteGoesAsOneRequestAndItsCursorIsSavedWhenNothingElseChanged() = runTest {
        var head = 3
        val fixture = WireFixture { request ->
            when (request.route) {
                sync -> pages(request) { _, cursor ->
                    when (cursor) {
                        null -> page("snapshot", 1, mapOf("a" to "1"), cursor = "e.1")
                        "e.3" -> page("delta", 3, emptyMap(), cursor = "e.3")
                        else -> null
                    }
                }
                subscribe -> Reply.status(204)
                write -> Reply.Json(200, """{"spaces":[{"space":"pins","revision":$head,"cursor":"e.$head"}]}""")
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            backgroundScope.launch { client.entries(fixture.server, "pins").collect {} }
            runCurrent()
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))
            // Deleting `x`, which isn't set, takes no revision: 1 + 2 = 3 follows straight on.
            client.write(fixture.server, "pins", mapOf("ses_b" to "1", "a" to null, "x" to null))

            assertEquals(mapOf("ses_b" to "1"), client.entries(fixture.server, "pins").first().entries)
            assertEquals(changes("ses_b" to "1", "a" to null, "x" to null), written(fixture.requests.single { it.route == write }))

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s2"))
            assertEquals("the next sync starts after the write", "pins@e.3", synced(fixture).last())

            // Another client wrote meanwhile: the write's cursor would skip its change.
            head = 9
            client.write(fixture.server, "pins", mapOf("c" to "1"))
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s3"))
            assertEquals("pins@e.3", synced(fixture).last())
        } finally {
            fixture.close()
        }
    }

    @Test fun writesTheServerHasntConfirmedSurviveSyncsAndGoFirstOnTheNextStream() = runTest {
        var writesFail = false
        val fixture = WireFixture { request ->
            when (request.route) {
                sync -> pages(request) { _, cursor ->
                    when (cursor) {
                        null -> page("snapshot", 1, mapOf("a" to "1", "b" to "1"), cursor = "e.1")
                        // The server still has `a` and lacks `c`.
                        "e.1" -> page("snapshot", 2, mapOf("a" to "1", "b" to "1"), cursor = "e.2")
                        "e.2" -> page("delta", 5, mapOf("c" to "1"), cursor = "e.5", deleted = listOf("a"))
                        else -> null
                    }
                }
                subscribe -> Reply.status(204)
                write -> if (writesFail) Reply.status(503) else Reply.Json(200, """{"spaces":[{"space":"pins","revision":7,"cursor":"e.7"}]}""")
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            backgroundScope.launch { client.entries(fixture.server, "pins").collect {} }
            runCurrent()
            suspend fun shown() = client.entries(fixture.server, "pins").first().entries
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))

            writesFail = true
            client.write(fixture.server, "pins", mapOf("c" to "1"))
            client.write(fixture.server, "pins", mapOf("a" to null, "d" to "1"))
            client.write(fixture.server, "pins", mapOf("d" to null))
            assertEquals("writes show before the server confirms them", mapOf("b" to "1", "c" to "1"), shown())

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s2"))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceUpdated("pins", "a", "1", revision = 3))
            assertEquals("neither a sync nor an event undoes a pending write", mapOf("b" to "1", "c" to "1"), shown())

            writesFail = false
            val before = fixture.requests.size
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s3"))
            assertEquals(mapOf("b" to "1", "c" to "1"), shown())
            val sent = fixture.requests.drop(before)
            assertEquals("pending writes go first, as one request", listOf(write, sync, subscribe), sent.map { it.route })
            assertEquals("in order, a later write to a key in place of the earlier", changes("c" to "1", "a" to null, "d" to null), written(sent[0]))

            val confirmed = fixture.requests.size
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s4"))
            assertTrue("confirmed writes aren't sent again", fixture.requests.drop(confirmed).none { it.route == write })
        } finally {
            fixture.close()
        }
    }

    @Test fun writesSplitOnlyPastTheServersLimits() {
        val many = (1..MAX_WRITE_CHANGES + 1).map { "k$it" to "1" }
        assertEquals(listOf(MAX_WRITE_CHANGES, 1), batches(many).map { it.size })
        val big = "é".repeat(MAX_WRITE_VALUE_BYTES / 4)
        assertEquals("a value's UTF-8 counts", listOf(2, 1), batches(listOf("a" to big, "b" to big, "c" to big)).map { it.size })
        assertEquals("deletes carry no value", listOf(3), batches(listOf("a" to big, "b" to null, "c" to big)).map { it.size })
    }

    @Test fun streamIDsAndSpaceFramesDecode() {
        val connected = """{"id":"evt_1","type":"server.connected","data":{"stream":"s1"}}"""
        assertEquals(OpenCodeServerEvent.Connected, decodeStreamFrame(connected))
        assertEquals("s1", decodeStreamID(connected))
        assertNull("an older server names no stream", decodeStreamID("""{"type":"server.connected","data":{}}"""))
        assertEquals(
            OpenCodeServerEvent.SpaceUpdated("pins", "ses_1", "1", 4),
            decodeStreamFrame("""{"type":"experimental.space.updated","data":{"space":"pins","key":"ses_1","value":"1","revision":4}}"""),
        )
        assertEquals(
            OpenCodeServerEvent.SpaceDeleted("pins", "ses_1", 5),
            decodeStreamFrame("""{"type":"experimental.space.deleted","data":{"space":"pins","key":"ses_1","revision":5}}"""),
        )
        assertEquals(OpenCodeServerEvent.SpaceResync("pins"), decodeStreamFrame("""{"type":"experimental.space.resync","data":{"space":"pins"}}"""))
        assertNull(decodeStreamFrame("""{"type":"experimental.space.updated","data":{"space":"pins","key":"ses_1"}}"""))
    }
}
