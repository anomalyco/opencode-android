package ai.opencode.feature.transcript.widgets

import ai.opencode.server.model.WIDGET_CLIENT
import ai.opencode.server.model.OpenCodeWidgetInstance
import ai.opencode.server.model.OpenCodeWidgetState
import ai.opencode.server.model.OpenCodeWidgetStatus
import androidx.compose.runtime.Immutable

/**
 * The server's widgets outside their transcripts (`opencode.widget` `instances`), kept current by
 * `rpc.opencode.widget.changed`: what every view of widgets beyond a session reads. A key
 * (`sessionID:messageID:callID`) of a widget the agent has since replaced resolves to the widget that replaced it,
 * so a widget shown elsewhere follows its updates.
 */
@Immutable
data class AgentWidgetInstances(
    /** Last updated first. */
    val all: List<OpenCodeWidgetInstance> = emptyList(),
    /** Superseded widgets' keys to the key of the widget that replaced them. */
    private val forwards: Map<String, String> = emptyMap(),
) {
    private val byKey: Map<String, OpenCodeWidgetInstance> by lazy { all.associateBy { it.key } }

    /**
     * The widgets worth offering, last updated first: open, without a failure this app recorded (another client's,
     * iOS without storage say, says nothing about running here). Only the newest widget of a replace chain is listed,
     * so a chain whose newest widget isn't usable isn't offered at all.
     */
    val usable: List<OpenCodeWidgetInstance> by lazy { all.filter(::isUsable) }

    fun isUsable(instance: OpenCodeWidgetInstance): Boolean =
        instance.state.status == OpenCodeWidgetStatus.Open && instance.state.failure?.client != WIDGET_CLIENT

    /** What changed in the server's widgets means for the holder. */
    enum class Change { None, Updated, NeedsRefetch }

    /** [key]'s widget now: itself, or the one that replaced it; null when the server has neither. */
    operator fun get(key: String): OpenCodeWidgetInstance? = byKey[current(key)]

    /** [key], or the key of the widget that replaced it, following replacements to the last. */
    fun current(key: String): String {
        var k = key
        repeat(MAX_HOPS) { k = forwards[k] ?: return k }
        return k
    }

    /** A fresh read: replaced widgets the instances list (and those followed so far) resolve to their replacements. */
    fun loaded(instances: List<OpenCodeWidgetInstance>): AgentWidgetInstances {
        val next = HashMap(forwards)
        for (instance in instances) {
            for (call in instance.replaced) next[OpenCodeWidgetInstance.key(instance.state.sessionID, call.messageID, call.callID)] = instance.key
        }
        return AgentWidgetInstances(instances, next)
    }

    /** A `changed` event: a known widget changes in place, a superseded one forwards to its replacement. */
    fun changed(sessionID: String, state: OpenCodeWidgetState): Pair<AgentWidgetInstances, Change> {
        val key = OpenCodeWidgetInstance.key(sessionID, state.messageID, state.callID)
        if (state.status == OpenCodeWidgetStatus.Superseded) {
            val by = state.supersededBy
            val forwards = if (by == null) forwards else forwards + (key to OpenCodeWidgetInstance.key(sessionID, by.messageID, by.callID))
            val next = AgentWidgetInstances(all.filter { it.key != key }, forwards)
            // The replacement's data and version come only with a read.
            return next to if (by != null) Change.NeedsRefetch else if (next.all.size != all.size) Change.Updated else Change.None
        }
        val known = byKey[key] ?: return this to Change.NeedsRefetch
        if (known.state == state) return this to Change.None
        return copy(all = all.map { if (it.key == key) it.copy(state = state) else it }) to Change.Updated
    }

    private companion object {
        const val MAX_HOPS = 64
    }
}
