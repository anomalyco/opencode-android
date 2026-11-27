package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.spaces.SpacesClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `widget.storage` (specs/v2/widgets-live.md, Storage): each widget template's space on its server, `widget.<name>`,
 * followed through [SpacesClient] while a storage widget of that template runs, and every change of it told to each
 * of them, in order. Main thread only.
 */
class AgentWidgetStorage(private val spaces: SpacesClient, private val scope: CoroutineScope) {
    /** A running widget of the template: [value] is JSON text, null when [key] was deleted. */
    fun interface Listener {
        fun changed(key: String, value: String?)
    }

    internal class Space(val server: OpenCodeServer, val name: String) {
        val listeners = LinkedHashSet<Listener>()
        /** The local copy, pending writes applied. */
        val entries = LinkedHashMap<String, String>()
        var bytes = 0L
        val synced = CompletableDeferred<Unit>()
        var job: Job? = null
    }

    private val followed = HashMap<String, Space>()

    /** Follows [template]'s space on [server] for [listener] until the handle is closed. */
    fun follow(server: OpenCodeServer, template: String, listener: Listener): Handle {
        val id = "${server.persistenceID}\u0000$template"
        val space = followed.getOrPut(id) { Space(server, "widget.$template").also(::collect) }
        space.listeners += listener
        return Handle(id, space, listener)
    }

    inner class Handle internal constructor(private val id: String, private val space: Space, private val listener: Listener) {
        /** Waits (at most [timeoutMillis]) for the space's first complete sync in this process. */
        suspend fun synced(timeoutMillis: Long = SYNC_WAIT_MILLIS) {
            withTimeoutOrNull(timeoutMillis) { space.synced.await() }
        }

        /** The live input's `storage`: `{ entries: [{ key, value }] }`. */
        fun input(): String = buildJsonObject {
            putJsonArray("entries") {
                for ((key, value) in space.entries) addJsonObject {
                    put("key", key)
                    put("value", value)
                }
            }
        }.toString()

        /** A storage post ([value] null deletes): null once it's among the writes, else the program's error. */
        fun write(key: String, value: String?): String? {
            if (value != null) {
                val before = space.entries[key]
                if (before == null && space.entries.size >= MAX_KEYS) return "TooLarge: widget.storage: storage holds $MAX_KEYS keys already"
                val bytes = space.bytes + utf8(key) + utf8(value) - (before?.let { utf8(key) + utf8(it) } ?: 0)
                if (bytes > MAX_BYTES) return "TooLarge: widget.storage: storage would hold over ${MAX_BYTES shr 20} MiB"
            }
            change(space, key, value)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    spaces.write(space.server, space.name, mapOf(key to value))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLog.app.notice { "Widget storage write didn't go: ${error.javaClass.simpleName}" }
                }
            }
            return null
        }

        fun close() {
            space.listeners -= listener
            if (space.listeners.isNotEmpty() || followed[id] !== space) return
            followed -= id
            space.job?.cancel()
        }
    }

    private fun collect(space: Space) {
        space.job = scope.launch {
            spaces.entries(space.server, space.name).collect { update ->
                val next = update.entries
                val changes = ArrayList<Pair<String, String?>>()
                for ((key, value) in next) if (space.entries[key] != value) changes += key to value
                for (key in space.entries.keys) if (key !in next) changes += key to null
                for ((key, value) in changes) change(space, key, value)
                if (update.isSyncComplete) space.synced.complete(Unit)
            }
        }
    }

    private fun change(space: Space, key: String, value: String?) {
        val before = space.entries[key]
        if (before == value) return
        if (before != null) space.bytes -= utf8(key) + utf8(before)
        if (value == null) space.entries -= key else {
            space.entries[key] = value
            space.bytes += utf8(key) + utf8(value)
        }
        for (listener in space.listeners.toList()) listener.changed(key, value)
    }

    companion object Key : DependencyKey<AgentWidgetStorage> {
        const val MAX_KEYS = 1000
        const val MAX_BYTES = 1024 * 1024L
        const val SYNC_WAIT_MILLIS = 2_000L

        override val liveValue: AgentWidgetStorage by lazy {
            AgentWidgetStorage(dependency(SpacesClient), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        }
        override val testValue: AgentWidgetStorage
            get() = AgentWidgetStorage(dependency(SpacesClient), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

        /** UTF-8 bytes, a lone surrogate counting as U+FFFD's 3, as the prelude counts them. */
        internal fun utf8(text: String): Long {
            var bytes = 0L
            var index = 0
            while (index < text.length) {
                val code = text[index].code
                bytes += when {
                    code < 0x80 -> 1
                    code < 0x800 -> 2
                    code in 0xd800 until 0xdc00 && index + 1 < text.length && text[index + 1].code in 0xdc00 until 0xe000 -> {
                        index++
                        4
                    }
                    else -> 3
                }
                index++
            }
            return bytes
        }
    }
}
