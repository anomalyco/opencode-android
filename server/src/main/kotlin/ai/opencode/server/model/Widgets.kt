package ai.opencode.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A widget template version (`name@version`): immutable, so its program is cached forever. */
@Serializable
data class OpenCodeWidgetRef(val name: String, val version: String = "") {
    val key: String get() = "$name@${version.ifEmpty { "latest" }}"

    /** A version is named; without one the program is the widget template's latest, never cached on disk. */
    val isPinned: Boolean get() = version.isNotEmpty()

    /** Names and versions the server accepts, so a ref is safe as a path segment and a file name. */
    val isValid: Boolean
        get() = NAME.matches(name) && (version.isEmpty() || VERSION.matches(version))

    private companion object {
        val NAME = Regex("^[a-z][a-z0-9-]{0,47}$")
        val VERSION = Regex("^[0-9a-f]{16}$")
    }
}

/** Where a widget stands: open until its one answer (submitted or dismissed) or a newer widget replacing it. */
enum class OpenCodeWidgetStatus(val wireName: String) {
    Open("open"),
    Submitted("submitted"),
    Dismissed("dismissed"),
    Superseded("superseded");

    companion object {
        fun of(raw: String?): OpenCodeWidgetStatus? = entries.firstOrNull { it.wireName == raw }
    }
}

/** How the user ended an open widget: the respond endpoint's body. */
sealed interface OpenCodeWidgetResponse {
    /** [json] is the submitted value as JSON text. */
    data class Submit(val json: String) : OpenCodeWidgetResponse
    data object Dismiss : OpenCodeWidgetResponse

    val body: String
        get() = when (this) {
            is Submit -> "{\"value\":$json}"
            Dismiss -> "{\"dismiss\":true}"
        }
}

/** A message a widget sends the agent while it stays open: [json] is the value; without [wake] it waits for the next turn. */
data class OpenCodeWidgetMessageValue(val json: String, val wake: Boolean) {
    val body: String get() = "{\"value\":$json,\"wake\":$wake}"
}

/**
 * The widget plugin's record of one widget (`opencode.widget` `State`): the `widget` tool call it
 * belongs to ([messageID], [callID]), the pinned widget template, and how it ended. [response] is JSON text.
 */
data class OpenCodeWidgetState(
    val sessionID: String,
    val messageID: String,
    val callID: String,
    val widgetTemplate: OpenCodeWidgetRef,
    val title: String? = null,
    val status: OpenCodeWidgetStatus,
    val response: String? = null,
    val wait: Boolean = false,
    /** Hosts a live widget template may fetch from (`capabilities.https.hosts`). */
    val httpsHosts: List<String>? = null,
    /** The widget template declares `capabilities.storage`. */
    val storage: Boolean = false,
    /** The program error the last report recorded; the widget keeps its status. */
    val failure: OpenCodeWidgetFailure? = null,
) {
    /** One widget in a session: a call ID is unique only within its message. */
    val key: String get() = key(messageID, callID)

    companion object {
        fun key(messageID: String, callID: String): String = "$messageID/$callID"
    }
}

/** A widget template version's runnable program and how it runs: `step` (the default) or `live`, with the hosts a live one may fetch from. */
data class OpenCodeWidgetProgram(
    val program: String,
    val isLive: Boolean = false,
    val httpsHosts: List<String>? = null,
    /** Declares `capabilities.storage`: the widget template's space on its server (live only). */
    val storage: Boolean = false,
    /** Lines of prelude before the widget template's source in [program], to report errors at its own lines. */
    val sourceLineOffset: Int = 0,
)

/** A widget failure on this device, for the widget plugin's `report` (the agent hears of it). */
data class OpenCodeWidgetError(val phase: String, val message: String, val line: Int? = null, val column: Int? = null)

/** A widget's recorded program error: the [client] that hit it, and whether the agent was [sent] it. */
data class OpenCodeWidgetFailure(val error: OpenCodeWidgetError, val client: String, val sent: Boolean)
