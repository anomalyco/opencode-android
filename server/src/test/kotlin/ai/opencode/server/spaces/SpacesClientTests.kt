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
            client.set(fixture.server, "pins", "a", "1")
            client.delete(fixture.server, "pins", "a")

            assertTrue(seen.isEmpty())
            assertEquals("one sync, no retries and no writes", listOf("GET /api/experimental/space/pins"), fixture.requests.map { it.route })

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s2"))
            assertEquals(2, fixture.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test fun writesPutAndDeleteTheKeyAndShowAtOnce() = runTest {
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /api/experimental/space/pins" -> page("snapshot", 1, mapOf("a" to "1"), cursor = "e.1")
                "POST /api/experimental/space/subscribe", "PUT /api/experimental/space/pins/ses_b", "DELETE /api/experimental/space/pins/a" ->
                    Reply.status(204)
                else -> Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))
            assertEquals(mapOf("a" to "1"), client.entries(fixture.server, "pins").first())
            client.set(fixture.server, "pins", "ses_b", "1")
            client.delete(fixture.server, "pins", "a")

            assertEquals(mapOf("ses_b" to "1"), client.entries(fixture.server, "pins").first())
            val writes = fixture.requests.drop(2)
            assertEquals(listOf("PUT /api/experimental/space/pins/ses_b", "DELETE /api/experimental/space/pins/a"), writes.map { it.route })
            assertEquals(Json.parseToJsonElement("""{"value":"1"}"""), writes[0].json())
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
                else -> if (request.method == "PUT" || request.method == "DELETE") Reply.status(if (writesFail) 503 else 204) else Reply.status(500)
            }
        }
        try {
            val client = SpaceStore().client
            backgroundScope.launch { client.entries(fixture.server, "pins").collect {} }
            runCurrent()
            suspend fun shown() = client.entries(fixture.server, "pins").first()
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s1"))

            writesFail = true
            client.set(fixture.server, "pins", "c", "1")
            client.delete(fixture.server, "pins", "a")
            client.set(fixture.server, "pins", "d", "1")
            client.delete(fixture.server, "pins", "d")
            assertEquals("writes show before the server confirms them", mapOf("b" to "1", "c" to "1"), shown())

            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s2"))
            client.receive(fixture.server, OpenCodeServerEvent.SpaceUpdated("pins", "a", "1", revision = 3))
            assertEquals("neither a sync nor an event undoes a pending write", mapOf("b" to "1", "c" to "1"), shown())

            writesFail = false
            val before = fixture.requests.size
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s3"))
            assertEquals(mapOf("b" to "1", "c" to "1"), shown())
            assertEquals(
                "pending writes go first, in order, the later write to a key in place of the earlier",
                listOf(
                    "PUT /api/experimental/space/pins/c", "DELETE /api/experimental/space/pins/a", "DELETE /api/experimental/space/pins/d",
                    "GET /api/experimental/space/pins", "POST /api/experimental/space/subscribe",
                ),
                fixture.requests.drop(before).map { it.route },
            )

            val confirmed = fixture.requests.size
            client.receive(fixture.server, OpenCodeServerEvent.StreamOpened("s4"))
            assertTrue("confirmed writes aren't sent again", fixture.requests.drop(confirmed).none { it.method == "PUT" || it.method == "DELETE" })
        } finally {
            fixture.close()
        }
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
