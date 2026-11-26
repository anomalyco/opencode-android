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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SpacesClientTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun page(type: String, revision: Long, entries: Map<String, String>, cursor: String, next: String? = null, deleted: List<String> = emptyList()): Reply {
        val rows = entries.entries.joinToString(",") { """{"key":"${it.key}","value":"${it.value}"}""" }
        val tail = (if (type == "delta") ""","deleted":[${deleted.joinToString(",") { "\"$it\"" }}]""" else "") +
            (next?.let { ""","next":"$it"""" } ?: "")
        return Reply.Json(200, """{"type":"$type","space":"pins","epoch":"e","revision":$revision,"entries":[$rows],"cursor":"$cursor"$tail}""")
    }

    private fun subscription(request: WireFixture.Request) = request.json().toString()

    private fun subscribed(stream: String, cursor: String) =
        Json.parseToJsonElement("""{"stream":"$stream","spaces":[{"space":"pins","cursor":"$cursor"}]}""").toString()

    @Test fun aSyncFollowsEveryPageThenSubscribesTheStreamFromTheLastCursor() = runTest {
        val fixture = WireFixture { request ->
            when (request.route to request.queryValue("cursor")) {
                "GET /api/experimental/space/pins" to null -> page("snapshot", 2, mapOf("a" to "1", "b" to "1"), cursor = "e.2", next = "e.2.4")
                "GET /api/experimental/space/pins" to "e.2.4" -> page("delta", 4, mapOf("c" to "1"), cursor = "e.4")
                "POST /api/experimental/space/subscribe" to null -> Reply.status(204)
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            val seen = mutableListOf<Map<String, String>>()
            backgroundScope.launch { client.entries(fixture.server, "pins").collect { seen += it } }
            runCurrent()
            assertTrue("nothing to sync before the stream is known", fixture.requests.isEmpty())

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))
            runCurrent()

            assertEquals("a snapshot shows once, whole", listOf(mapOf("a" to "1", "b" to "1", "c" to "1")), seen)
            val requests = fixture.requests
            assertEquals(
                listOf("GET /api/experimental/space/pins", "GET /api/experimental/space/pins", "POST /api/experimental/space/subscribe"),
                requests.map { it.route },
            )
            assertNull(requests[0].queryValue("cursor"))
            assertEquals("e.2.4", requests[1].queryValue("cursor"))
            assertEquals(subscribed("s1", "e.4"), subscription(requests[2]))
        } finally {
            fixture.close()
        }
    }

    @Test fun eventsApplyInOrderAndOnesTheSyncCoveredAreSkipped() = runTest {
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /api/experimental/space/pins" -> page("snapshot", 5, mapOf("a" to "1", "b" to "1"), cursor = "e.5")
                "POST /api/experimental/space/subscribe" -> Reply.status(204)
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))
            assertEquals(mapOf("a" to "1", "b" to "1"), client.entries(fixture.server, "pins").first())
            assertEquals("the stream was known: one sync", 2, fixture.requests.size)

            client.receive(fixture.server, OpenCodeServerEvent.SpaceUpdated("pins", "c", "1", revision = 6))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceDeleted("pins", "a", revision = 7))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceUpdated("pins", "old", "1", revision = 5))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceUpdated("other", "x", "1", revision = 8))

            assertEquals(mapOf("b" to "1", "c" to "1"), client.entries(fixture.server, "pins").first())
            assertEquals("events cost no requests", 2, fixture.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test fun aNewStreamOrAResyncSyncsAgainFromTheSavedCursorThenSubscribes() = runTest {
        val fixture = WireFixture { request ->
            when (request.route to request.queryValue("cursor")) {
                "GET /api/experimental/space/pins" to null -> page("snapshot", 1, mapOf("a" to "1"), cursor = "e.1")
                "GET /api/experimental/space/pins" to "e.1" -> page("delta", 3, mapOf("b" to "1"), cursor = "e.3", deleted = listOf("a"))
                "GET /api/experimental/space/pins" to "e.3" -> page("snapshot", 9, mapOf("c" to "1"), cursor = "e.9")
                "POST /api/experimental/space/subscribe" to null -> Reply.status(204)
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            val seen = mutableListOf<Map<String, String>>()
            backgroundScope.launch { client.entries(fixture.server, "pins").collect { seen += it } }
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
            val requests = fixture.requests
            assertEquals(listOf(null, "e.1", "e.3"), requests.filter { it.method == "GET" }.map { it.queryValue("cursor") })
            assertEquals(
                "the same stream again changes nothing",
                listOf(subscribed("s1", "e.1"), subscribed("s2", "e.3"), subscribed("s2", "e.9")),
                requests.filter { it.method == "POST" }.map(::subscription),
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
            backgroundScope.launch { client.entries(fixture.server, "pins").collect { seen += it } }
            runCurrent()
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceResync("pins"))
            backgroundScope.launch { client.entries(fixture.server, "pins").collect { seen += it } }
            runCurrent()
            client.write(fixture.server, "pins", mapOf("a" to "1"))
            client.write(fixture.server, "pins", mapOf("a" to null))

            assertTrue(seen.isEmpty())
            assertEquals("one sync, no retries and no writes", listOf("GET /api/experimental/space/pins"), fixture.requests.map { it.route })

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s2"))
            assertEquals(2, fixture.requests.size)
        } finally {
            fixture.close()
        }
    }

    private fun written(request: WireFixture.Request) = request.json()["changes"].toString()

    private fun changes(vararg changes: Pair<String, String?>) = Json.parseToJsonElement(
        changes.joinToString(",", "[", "]") { (key, value) -> """{"key":"$key","value":${value?.let { "\"$it\"" } ?: "null"}}""" },
    ).toString()

    @Test fun aWriteGoesAsOneRequestAndItsCursorIsSavedWhenNothingElseChanged() = runTest {
        var head = 3
        val fixture = WireFixture { request ->
            when (request.route to request.queryValue("cursor")) {
                "GET /api/experimental/space/pins" to null -> page("snapshot", 1, mapOf("a" to "1"), cursor = "e.1")
                "GET /api/experimental/space/pins" to "e.3" -> page("delta", 3, emptyMap(), cursor = "e.3")
                "POST /api/experimental/space/subscribe" to null -> Reply.status(204)
                "POST /api/experimental/space/pins/write" to null -> Reply.Json(200, """{"revision":$head,"cursor":"e.$head"}""")
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

            assertEquals(mapOf("ses_b" to "1"), client.entries(fixture.server, "pins").first())
            val write = fixture.requests.single { it.route == "POST /api/experimental/space/pins/write" }
            assertEquals(changes("ses_b" to "1", "a" to null, "x" to null), written(write))

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s2"))
            assertEquals("the next sync starts after the write", "e.3", fixture.requests.last { it.method == "GET" }.queryValue("cursor"))

            // Another client wrote meanwhile: the write's cursor would skip its change.
            head = 9
            client.write(fixture.server, "pins", mapOf("c" to "1"))
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s3"))
            assertEquals("e.3", fixture.requests.last { it.method == "GET" }.queryValue("cursor"))
        } finally {
            fixture.close()
        }
    }

    @Test fun writesTheServerHasntConfirmedSurviveSyncsAndGoFirstOnTheNextStream() = runTest {
        var writesFail = false
        val fixture = WireFixture { request ->
            when (request.route to request.queryValue("cursor")) {
                "GET /api/experimental/space/pins" to null -> page("snapshot", 1, mapOf("a" to "1", "b" to "1"), cursor = "e.1")
                // The server still has `a` and lacks `c`.
                "GET /api/experimental/space/pins" to "e.1" -> page("snapshot", 2, mapOf("a" to "1", "b" to "1"), cursor = "e.2")
                "GET /api/experimental/space/pins" to "e.2" -> page("delta", 5, mapOf("c" to "1"), cursor = "e.5", deleted = listOf("a"))
                "POST /api/experimental/space/subscribe" to null -> Reply.status(204)
                "POST /api/experimental/space/pins/write" to null ->
                    if (writesFail) Reply.status(503) else Reply.Json(200, """{"revision":7,"cursor":"e.7"}""")
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            backgroundScope.launch { client.entries(fixture.server, "pins").collect {} }
            runCurrent()
            suspend fun shown() = client.entries(fixture.server, "pins").first()
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
            assertEquals(
                "pending writes go first, as one request",
                listOf("POST /api/experimental/space/pins/write", "GET /api/experimental/space/pins", "POST /api/experimental/space/subscribe"),
                sent.map { it.route },
            )
            assertEquals("in order, a later write to a key in place of the earlier", changes("c" to "1", "a" to null, "d" to null), written(sent[0]))

            val confirmed = fixture.requests.size
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s4"))
            assertTrue("confirmed writes aren't sent again", fixture.requests.drop(confirmed).none { it.route.endsWith("/write") })
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
