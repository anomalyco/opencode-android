package ai.opencode.server.spaces

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.live.encodedBody
import ai.opencode.server.live.pathComponent
import ai.opencode.server.live.perform
import ai.opencode.server.live.request
import ai.opencode.server.retry.RequestIdempotency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Spaces (`/api/experimental/space`): string values a server keeps by key and sends to every client
 * that follows them. A followed space is synced over REST, then the event stream the app already
 * reads is subscribed from the cursor the sync ended on. Live events carry no cursor, so a new
 * stream or a resync syncs again from the saved one first. It knows nothing of what a space holds.
 */
data class SpacesClient(
    /**
     * Follows [space] on [server] while collected: its entries once synced, and after every change.
     * A server without spaces never yields.
     */
    val entries: (server: OpenCodeServer, space: String) -> Flow<Map<String, String>>,
    /** The event stream's [OpenCodeServerEvent.StreamOpened] and [OpenCodeServerEvent.SpaceEvent]s, in order. */
    val receive: suspend (server: OpenCodeServer, event: OpenCodeServerEvent) -> Unit,
    /**
     * Sets keys to values, or deletes them (null), in one request. Shows at once. A change the server
     * hasn't confirmed stays pending: it's sent again before the next sync, which doesn't undo it
     * meanwhile. A later change to the key replaces it.
     */
    val write: suspend (server: OpenCodeServer, space: String, changes: Map<String, String?>) -> Unit,
) {
    companion object Key : DependencyKey<SpacesClient> {
        private val shared by lazy { SpaceStore().client }
        override val liveValue: SpacesClient get() = shared

        /** Inert: nothing to follow, events are dropped, and writes are unimplemented. */
        override val testValue: SpacesClient = SpacesClient(
            entries = { _, _ -> emptyFlow() },
            receive = { _, _ -> },
            write = { _, _, _ -> throw AssertionError("Unimplemented: SpacesClient.write") },
        )
    }
}

@Serializable
internal data class SpacePage(
    val type: String,
    val revision: Long,
    val entries: List<Entry>,
    val deleted: List<String> = emptyList(),
    val cursor: String,
    val next: String? = null,
) {
    @Serializable data class Entry(val key: String, val value: String)
}

@Serializable private data class SpaceSubscription(val space: String, val cursor: String)
@Serializable private data class SubscribeBody(val stream: String, val spaces: List<SpaceSubscription>)
@Serializable private data class WriteResult(val revision: Long, val cursor: String)

/** What one write request may carry (`Space.MaxChanges`, `Space.MaxValueBytes`). */
internal const val MAX_WRITE_CHANGES = 1024
internal const val MAX_WRITE_VALUE_BYTES = 1024 * 1024

/** Each server's spaces as this process last synced them, with their cursors and pending writes. */
internal class SpaceStore {
    private class Space(val name: String) {
        val syncing = Mutex()
        val writing = Mutex()
        /** Null until the first sync. */
        val entries = MutableStateFlow<Map<String, String>?>(null)
        /** The space as the server has it at [revision]; [entries] shows it with the pending writes on top. */
        var confirmed: Map<String, String>? = null
        var cursor: String? = null
        var revision = -1L
        /** The stream it's subscribed on. */
        var stream: String? = null
        var followers = 0
        /** Writes the server hasn't confirmed, in order: key → value, null for a delete. */
        val pending = LinkedHashMap<String, String?>()

        fun show() {
            val confirmed = confirmed ?: return
            entries.value = if (pending.isEmpty()) confirmed else applying(confirmed, pending.entries.map { it.toPair() })
        }
    }

    private val lock = Any()
    private val spaces = HashMap<Pair<String, String>, Space>()
    /** Each server's newest event stream. */
    private val streams = HashMap<String, String>()
    /** Servers that answered 404, with the stream they answered on: tried again on the next one. */
    private val unavailable = HashMap<String, String>()

    val client = SpacesClient(entries = ::entries, receive = ::receive, write = ::write)

    private fun space(server: OpenCodeServer, name: String): Space =
        synchronized(lock) { spaces.getOrPut(server.persistenceID to name) { Space(name) } }

    private fun entries(server: OpenCodeServer, name: String): Flow<Map<String, String>> = flow {
        val space = space(server, name)
        val stream = synchronized(lock) {
            space.followers += 1
            streams[server.persistenceID]
        }
        try {
            if (stream != null) follow(server, space, stream, again = false)
            emitAll(space.entries.filterNotNull())
        } finally {
            synchronized(lock) { space.followers -= 1 }
        }
    }

    private suspend fun receive(server: OpenCodeServer, event: OpenCodeServerEvent) {
        val serverID = server.persistenceID
        when (event) {
            is OpenCodeServerEvent.StreamOpened -> {
                val followed = synchronized(lock) {
                    streams[serverID] = event.stream
                    spaces.filter { it.key.first == serverID && it.value.followers > 0 }.values.toList()
                }
                for (space in followed) follow(server, space, event.stream, again = false)
            }
            is OpenCodeServerEvent.SpaceResync -> {
                val (space, stream) = synchronized(lock) { spaces[serverID to event.space] to streams[serverID] }
                if (space != null && stream != null && space.followers > 0) follow(server, space, stream, again = true)
            }
            is OpenCodeServerEvent.SpaceUpdated -> apply(serverID, event.space, event.revision) { it + (event.key to event.value) }
            is OpenCodeServerEvent.SpaceDeleted -> apply(serverID, event.space, event.revision) { it - event.key }
            else -> Unit
        }
    }

    private fun apply(serverID: String, name: String, revision: Long, change: (Map<String, String>) -> Map<String, String>) {
        synchronized(lock) {
            val space = spaces[serverID to name] ?: return
            val current = space.confirmed ?: return
            // Already in the last sync.
            if (revision <= space.revision) return
            space.revision = revision
            space.confirmed = change(current)
            space.show()
        }
    }

    /**
     * Sends the pending writes, syncs from the saved cursor until no page is left, then subscribes
     * [stream] from where it ended.
     */
    private suspend fun follow(server: OpenCodeServer, space: Space, stream: String, again: Boolean) = space.syncing.withLock {
        val serverID = server.persistenceID
        if (synchronized(lock) { unavailable[serverID] == stream || (!again && space.stream == stream) }) return@withLock
        space.writing.withLock { send(server, space) }
        val (cursor, base) = synchronized(lock) { space.cursor to space.confirmed }
        try {
            var page = sync(server, space.name, cursor)
            val working = LinkedHashMap(base ?: emptyMap())
            while (true) {
                if (page.type == "snapshot") working.clear()
                for (key in page.deleted) working.remove(key)
                for (entry in page.entries) working[entry.key] = entry.value
                page = sync(server, space.name, page.next ?: break)
            }
            synchronized(lock) {
                unavailable.remove(serverID)
                space.cursor = page.cursor
                space.revision = page.revision
                space.confirmed = working
                space.show()
            }
            perform(
                "/api/experimental/space/subscribe",
                method = "POST",
                body = encodedBody(SubscribeBody(stream, listOf(SpaceSubscription(space.name, page.cursor)))),
                idempotency = RequestIdempotency.Idempotent,
                server = server,
            )
            synchronized(lock) { space.stream = stream }
        } catch (error: OpenCodeClientError) {
            if (error.statusCode == 404) {
                synchronized(lock) { unavailable[serverID] = stream }
                AppLog.network.notice { "Space ${space.name}: the server has no spaces" }
            } else {
                AppLog.network.notice { "Space ${space.name}: sync failed: ${error.logSummary}" }
            }
        }
    }

    private suspend fun sync(server: OpenCodeServer, name: String, cursor: String?): SpacePage = request(
        "/api/experimental/space/${pathComponent(name)}",
        query = cursor?.let { listOf("cursor" to it) } ?: emptyList(),
        server = server,
    )

    private suspend fun write(server: OpenCodeServer, name: String, changes: Map<String, String?>) {
        if (changes.isEmpty() || synchronized(lock) { server.persistenceID in unavailable }) return
        val space = space(server, name)
        synchronized(lock) {
            for ((key, value) in changes) {
                space.pending.remove(key)
                space.pending[key] = value
            }
            space.show()
        }
        space.writing.withLock { send(server, space) }
    }

    /** Sends the pending writes, as few requests as the server's limits allow; false when one failed (the rest stay pending). */
    private suspend fun send(server: OpenCodeServer, space: Space): Boolean {
        val pending = synchronized(lock) { space.pending.entries.map { it.toPair() } }
        for (batch in batches(pending)) {
            val result = try {
                request<WriteResult>(
                    "/api/experimental/space/${pathComponent(space.name)}/write",
                    method = "POST",
                    body = writeBody(batch),
                    // Setting the same values again leaves the same result.
                    idempotency = RequestIdempotency.Idempotent,
                    server = server,
                )
            } catch (error: OpenCodeClientError) {
                AppLog.network.notice { "Space ${space.name}: write kept for later: ${error.logSummary}" }
                return false
            }
            synchronized(lock) { confirm(space, batch, result) }
        }
        return true
    }

    /**
     * The server has [batch]: it's no longer pending (unless a later write replaced it). When the
     * write followed straight on from what this space last saw, its cursor becomes the saved one.
     */
    private fun confirm(space: Space, batch: List<Pair<String, String?>>, result: WriteResult) {
        for ((key, value) in batch) if (space.pending.containsKey(key) && space.pending[key] == value) space.pending.remove(key)
        val confirmed = space.confirmed
        if (confirmed != null && space.revision >= 0) {
            // Only a change that changes a key takes a revision: deleting an unset key doesn't.
            var known: Map<String, String> = confirmed
            var changed = 0
            for ((key, value) in batch) {
                if (value != null || key in known) changed += 1
                known = applying(known, listOf(key to value))
            }
            if (result.revision == space.revision + changed) {
                space.confirmed = known
                space.revision = result.revision
                space.cursor = result.cursor
            } else if (result.revision <= space.revision) {
                space.cursor = result.cursor
            }
        }
        space.show()
    }

    private fun writeBody(batch: List<Pair<String, String?>>): ByteArray = buildJsonObject {
        putJsonArray("changes") {
            for ((key, value) in batch) addJsonObject {
                put("key", key)
                put("value", value)
            }
        }
    }.toString().toByteArray()
}

private fun applying(map: Map<String, String>, changes: List<Pair<String, String?>>): Map<String, String> {
    val next = LinkedHashMap(map)
    for ((key, value) in changes) if (value == null) next.remove(key) else next[key] = value
    return next
}

/** [changes] in order, split only where one request would pass the server's limits. */
internal fun batches(changes: List<Pair<String, String?>>): List<List<Pair<String, String?>>> {
    val batches = mutableListOf<List<Pair<String, String?>>>()
    var batch = mutableListOf<Pair<String, String?>>()
    var bytes = 0
    for (change in changes) {
        val size = change.second?.toByteArray()?.size ?: 0
        if (batch.isNotEmpty() && (batch.size == MAX_WRITE_CHANGES || bytes + size > MAX_WRITE_VALUE_BYTES)) {
            batches += batch
            batch = mutableListOf()
            bytes = 0
        }
        batch += change
        bytes += size
    }
    if (batch.isNotEmpty()) batches += batch
    return batches
}
