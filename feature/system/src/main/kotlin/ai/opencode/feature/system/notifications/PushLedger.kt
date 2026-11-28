package ai.opencode.feature.system.notifications

import kotlinx.serialization.Serializable

/**
 * Which pushes are shown and which were cleared, so late or repeated deliveries land in the
 * order the server sent them. Keys are `<ref>:<id>`: an id is unique only per server.
 */
@Serializable
data class PushLedger(
    /** Key → the `time` of the message shown under it. */
    val shown: Map<String, Long> = emptyMap(),
    /** Key → the `time` of the latest clear for it. */
    val cleared: Map<String, Long> = emptyMap(),
    /** Key → when its entry was last written on this phone, for forgetting. */
    val written: Map<String, Long> = emptyMap(),
) {
    /** The ledger after a message, and whether to show it. */
    fun message(key: String, time: Long, now: Long): Pair<PushLedger, Boolean> {
        val pruned = pruned(now)
        val clearedAt = pruned.cleared[key]
        if (clearedAt != null && clearedAt >= time) return pruned to false
        val shownAt = pruned.shown[key]
        if (shownAt != null && shownAt > time) return pruned to false
        return pruned.copy(shown = pruned.shown + (key to time), written = pruned.written + (key to now)) to true
    }

    /** The ledger after a clear, and whether to remove the notification. */
    fun clear(key: String, time: Long, now: Long): Pair<PushLedger, Boolean> {
        val pruned = pruned(now)
        val latest = maxOf(time, pruned.cleared[key] ?: time)
        val next = pruned.copy(cleared = pruned.cleared + (key to latest), written = pruned.written + (key to now))
        val shownAt = pruned.shown[key]
        if (shownAt != null && shownAt > time) return next to false
        return next.copy(shown = next.shown - key) to true
    }

    private fun pruned(now: Long): PushLedger {
        val stale = written.filterValues { now - it > KEEP_MILLISECONDS }.keys
        if (stale.isEmpty()) return this
        return PushLedger(shown - stale, cleared - stale, written - stale)
    }

    companion object {
        /** The contract asks for an hour; a day also covers a notification left in the shade. */
        const val KEEP_MILLISECONDS = 24 * 60 * 60 * 1000L
    }
}
