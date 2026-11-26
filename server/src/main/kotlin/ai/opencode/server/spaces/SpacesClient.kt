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
     * Shows at once. A write the server hasn't confirmed stays pending: it's sent again before the
     * next sync, which doesn't undo it meanwhile. A later write to the key replaces it.
     */
    val set: suspend (server: OpenCodeServer, space: String, key: String, value: String) -> Unit,
    val delete: suspend (server: OpenCodeServer, space: String, key: String) -> Unit,
) {
    companion object Key : DependencyKey<SpacesClient> {
        private val shared by lazy { SpaceStore().client }
        override val liveValue: SpacesClient get() = shared

        /** Inert: nothing to follow, events are dropped, and writes are unimplemented. */
        override val testValue: SpacesClient = SpacesClient(
            entries = { _, _ -> emptyFlow() },
            receive = { _, _ -> },
            set = { _, _, _, _ -> throw AssertionError("Unimplemented: SpacesClient.set") },
            delete = { _, _, _ -> throw AssertionError("Unimplemented: SpacesClient.delete") },
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
@Serializable private data class ValueBody(val value: String)

/** Each server's spaces as this process last synced them, with their cursors and pending writes. */
internal class SpaceStore {
    private class Space(val name: String) {
        val syncing = Mutex()
        val writing = Mutex()
        /** Null until the first sync. */
        val entries = MutableStateFlow<Map<String, String>?>(null)
        var cursor: String? = null
        var revision = -1L
        /** The stream it's subscribed on. */
        var stream: String? = null
        var followers = 0
        /** Writes the server hasn't confirmed, in order: key → value, null for a delete. */
        val pending = LinkedHashMap<String, String?>()

        /** [entries] as the server has them, with the pending writes on top. */
        fun showing(confirmed: Map<String, String>): Map<String, String> {
            if (pending.isEmpty()) return confirmed
            val shown = LinkedHashMap(confirmed)
            for ((key, value) in pending) if (value == null) shown.remove(key) else shown[key] = value
            return shown
        }
    }

    private val lock = Any()
    private val spaces = HashMap<Pair<String, String>, Space>()
    /** Each server's newest event stream. */
    private val streams = HashMap<String, String>()
    /** Servers that answered 404, with the stream they answered on: tried again on the next one. */
    private val unavailable = HashMap<String, String>()

    val client = SpacesClient(entries = ::entries, receive = ::receive, set = ::set, delete = ::delete)

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
            is OpenCodeServerEvent.SpaceUpdated -> apply(serverID, event.space, event.key, event.revision) { it + (event.key to event.value) }
            is OpenCodeServerEvent.SpaceDeleted -> apply(serverID, event.space, event.key, event.revision) { it - event.key }
            else -> Unit
        }
    }

    private fun apply(serverID: String, name: String, key: String, revision: Long, change: (Map<String, String>) -> Map<String, String>) {
        synchronized(lock) {
            val space = spaces[serverID to name] ?: return
            val current = space.entries.value ?: return
            // Already in the last sync.
            if (revision <= space.revision) return
            space.revision = revision
            if (key !in space.pending) space.entries.value = change(current)
        }
    }

    /**
     * Sends the pending writes, syncs from the saved cursor until no page is left, then subscribes
     * [stream] from where it ended.
     */
    private suspend fun follow(server: OpenCodeServer, space: Space, stream: String, again: Boolean) = space.syncing.withLock {
        val serverID = server.persistenceID
        val (skip, cursor, base) = synchronized(lock) {
            Triple(unavailable[serverID] == stream || (!again && space.stream == stream), space.cursor, space.entries.value)
        }
        if (skip) return@withLock
        space.writing.withLock {
            for ((key, value) in synchronized(lock) { space.pending.toList() }) if (!send(server, space, key, value)) break
        }
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
                space.entries.value = space.showing(working)
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

    private suspend fun set(server: OpenCodeServer, name: String, key: String, value: String) = write(server, name, key, value)

    private suspend fun delete(server: OpenCodeServer, name: String, key: String) = write(server, name, key, null)

    /** Shows the write at once and keeps it pending until the server confirms it; writes go one at a time per space. */
    private suspend fun write(server: OpenCodeServer, name: String, key: String, value: String?) {
        if (synchronized(lock) { server.persistenceID in unavailable }) return
        val space = space(server, name)
        synchronized(lock) {
            space.pending.remove(key)
            space.pending[key] = value
            space.entries.value?.let { space.entries.value = space.showing(it) }
        }
        space.writing.withLock { send(server, space, key, value) }
    }

    /** Sends one pending write; once the server has it, it's no longer pending unless a later write replaced it. */
    private suspend fun send(server: OpenCodeServer, space: Space, key: String, value: String?): Boolean {
        val path = path(space.name, key)
        try {
            if (value != null) {
                perform(path, method = "PUT", body = encodedBody(ValueBody(value)), server = server)
            } else {
                // Deleting an unset key succeeds, so a second copy is harmless.
                perform(path, method = "DELETE", idempotency = RequestIdempotency.Idempotent, server = server)
            }
        } catch (error: OpenCodeClientError) {
            AppLog.network.notice { "Space ${space.name}: write kept for later: ${error.logSummary}" }
            return false
        }
        synchronized(lock) { if (space.pending.containsKey(key) && space.pending[key] == value) space.pending.remove(key) }
        return true
    }

    private fun path(name: String, key: String) = "/api/experimental/space/${pathComponent(name)}/${pathComponent(key)}"
}
