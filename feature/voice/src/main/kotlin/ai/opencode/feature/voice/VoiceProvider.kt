package ai.opencode.feature.voice

import ai.opencode.core.R as CoreR
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.shared
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Where a call goes (Automatic or a pick), what each provider can do now, and why a failed call
 * failed. The `voiceModelsByServer` and `voiceModelsEpochs` keys live in [VoiceSharedKeys].
 */

/**
 * What a server last said of its voice models (`GET /api/experimental/voice/models`): its
 * list, empty when it has no such route (an older OpenCode), or nothing when it didn't answer.
 */
@Serializable
sealed interface ServerVoiceModelsAnswer {
    @Serializable
    data class Listed(override val models: List<ServerVoiceModel>) : ServerVoiceModelsAnswer

    @Serializable
    data object NoAnswer : ServerVoiceModelsAnswer

    val models: List<ServerVoiceModel> get() = (this as? Listed)?.models ?: emptyList()
}

/**
 * Voice models answers kept across reconnects only while still true: reducers ask with
 * [epoch], record with [record], and a reconnect [invalidate]s the server's answer and any
 * request still in flight.
 */
object VoiceModelsCache {
    fun epoch(serverID: String): Int = shared(VoiceSharedKeys.voiceModelsEpochs).value[serverID] ?: 0

    /** Whether the answer landed: one asked before a reconnect doesn't. */
    fun record(answer: ServerVoiceModelsAnswer, serverID: String, asked: Int): Boolean {
        val answers = shared(VoiceSharedKeys.voiceModelsByServer)
        if (asked != epoch(serverID) || answers.value[serverID] == answer) return false
        answers.update { it + (serverID to answer) }
        return true
    }

    /** The server reconnected: an answer still being asked no longer counts, so it's asked again. */
    fun invalidate(serverID: String) {
        shared(VoiceSharedKeys.voiceModelsEpochs).update { it + (serverID to ((it[serverID] ?: 0) + 1)) }
    }
}

/**
 * Whether a provider can place a call now, read from what its server already reports: its
 * voice models (which hold a key), whether it has the voice routes, and whether it answered.
 */
enum class VoiceProviderState {
    /** Not asked yet, or the server can't say (it brokers calls but predates the models list): no status is shown. */
    unknown,
    ready,
    noKey,
    needsUpdate,
    offline,
    ;

    /** Sentence case: a status, not a control. */
    val label: String?
        get() = when (this) {
            unknown -> null
            ready -> localized(R.string.voice_provider_state_ready)
            noKey -> localized(R.string.voice_provider_state_no_key)
            needsUpdate -> localized(R.string.voice_provider_state_needs_update)
            offline -> localized(R.string.voice_provider_state_offline)
        }

    val symbol: String?
        get() = when (this) {
            unknown -> null
            ready -> "checkmark.circle.fill"
            noKey -> "exclamationmark.circle.fill"
            needsUpdate -> "arrow.down.circle.fill"
            offline -> "wifi.slash"
        }

    companion object {
        /**
         * A server's state, from its models answer and voice support: an older server lists no
         * models, or lists them without the engine the app picks by.
         */
        fun server(answer: ServerVoiceModelsAnswer?, support: ServerVoiceSupport?): VoiceProviderState {
            if (support == ServerVoiceSupport.unsupported) return needsUpdate
            return when (answer) {
                null -> unknown
                ServerVoiceModelsAnswer.NoAnswer -> offline
                is ServerVoiceModelsAnswer.Listed -> when {
                    ServerVoiceModels.usable(answer.models).isNotEmpty() -> ready
                    answer.models.any { it.engine != null } -> noKey
                    else -> needsUpdate
                }
            }
        }
    }
}

/** What every entry point knows of each provider when it resolves a call. */
data class VoiceReadiness(
    val answers: Map<String, ServerVoiceModelsAnswer> = emptyMap(),
    val support: Map<String, ServerVoiceSupport> = emptyMap(),
    /** The picked voice model ([VoiceSharedKeys.voiceModel]); empty for Automatic. */
    val pick: String = "",
) {
    /** The model a call through `server` asks for. */
    fun chosen(server: OpenCodeServer): ServerVoiceModel? =
        ServerVoiceModels.chosen(answers[server.persistenceID]?.models ?: emptyList(), pick)

    fun state(server: OpenCodeServer): VoiceProviderState =
        VoiceProviderState.server(answers[server.persistenceID], support[server.persistenceID])

    /** Who'd run calls on `server`: OpenCode when its Console connection hosts the chosen model. */
    fun brand(server: OpenCodeServer): VoiceProviderOption.Brand {
        val model = chosen(server)
        if (model?.engine == "gemini-live") return VoiceProviderOption.Brand.gemini
        if (model?.provider == "opencode") return VoiceProviderOption.Brand.openCode
        return VoiceProviderOption.Brand.openAI
    }
}

/** A provider calls can use, and where it runs. */
data class VoiceProviderOption(
    val place: Place,
    /** Null for a server that can't run calls now: who'd run them isn't known. */
    val brand: Brand?,
    val state: VoiceProviderState,
    /** What picking it stores in Voice Provider. */
    val choice: VoiceServerChoice,
) {
    enum class Brand { openAI, openCode, gemini }

    sealed interface Place {
        data class Server(val server: OpenCodeServer) : Place
    }

    val id: String
        get() = when (place) {
            is Place.Server -> place.server.persistenceID
        }

    val placeName: String
        get() = when (place) {
            is Place.Server -> place.server.friendlyName
        }

    val title: String
        get() = when (brand) {
            Brand.openAI -> "OpenAI"
            Brand.openCode -> "OpenCode"
            Brand.gemini -> "Gemini Live"
            null -> placeName
        }

    /** "OpenAI on My Mac", or the server's name alone while it can't run calls. */
    val name: String get() = if (brand == null) title else localized(R.string.voice_provider_name, title, placeName)
    val subtitle: String get() = if (brand == null) state.label ?: "" else localized(R.string.voice_provider_subtitle, placeName)
    val isReady: Boolean get() = state == VoiceProviderState.ready
}

/**
 * Settings › Voice › Voice Provider: which provider calls use, chosen automatically or picked,
 * and the rest.
 */
class VoiceProviderCatalog(
    choice: VoiceServerChoice,
    connected: OpenCodeServer?,
    others: List<OpenCodeServer>,
    readiness: VoiceReadiness,
) {
    /** What new calls use: Automatic's pick, or the person's. Null with no server to call through. */
    val active: VoiceProviderOption?
    val isAutomatic: Boolean

    /** Providers ready to take calls, other than the active one. */
    val others: List<VoiceProviderOption>

    /** Servers with no provider ready, and why. */
    val unavailable: List<VoiceProviderOption>

    init {
        val pick = VoicePick.resolve(choice, connected, others, readiness)
        isAutomatic = pick.isAutomatic

        fun option(server: OpenCodeServer): VoiceProviderOption {
            val state = readiness.state(server)
            return VoiceProviderOption(
                // Unknown: a server that brokers calls but can't say whose; OpenAI's, as it starts them.
                place = VoiceProviderOption.Place.Server(server),
                brand = if (state == VoiceProviderState.ready || state == VoiceProviderState.unknown) readiness.brand(server) else null,
                // Always the server itself: picking the connected one must survive a switch.
                state = state,
                choice = VoiceServerChoice.of(server),
            )
        }
        active = pick.server?.let { option(it) }
        val activeID = active?.id
        val servers = listOfNotNull(connected) + others
        val ready = mutableListOf<VoiceProviderOption>()
        val unavailable = mutableListOf<VoiceProviderOption>()
        for (server in servers) {
            val shown = option(server)
            if (shown.id == activeID) continue
            // One not heard from yet isn't listed.
            if (shown.isReady) {
                ready += shown
            } else if (shown.state != VoiceProviderState.unknown) {
                unavailable += shown
            }
        }
        this.others = ready
        this.unavailable = unavailable
    }

    override fun equals(other: Any?): Boolean =
        other is VoiceProviderCatalog && other.active == active && other.isAutomatic == isAutomatic &&
            other.others == others && other.unavailable == unavailable

    override fun hashCode(): Int = listOf(active, isAutomatic, others, unavailable).hashCode()
}

/** Where a new call goes: the person's pick, or Automatic's. */
data class VoicePick(
    /** Null with no server to call through. */
    val place: VoiceProviderOption.Place?,
    /** Chosen automatically: when the call can't start there, it may move on. */
    val isAutomatic: Boolean,
) {
    val server: OpenCodeServer? get() = (place as? VoiceProviderOption.Place.Server)?.server

    companion object {
        /**
         * Automatic takes the first that can place the call: the connected server, then the
         * other saved servers. The connected server counts until it's
         * known it can't (its support check still decides); another server only once it's
         * known ready. With none ready, the connected server, whose failure says why. A pick
         * is never swapped; one no longer saved chooses automatically.
         */
        fun resolve(
            choice: VoiceServerChoice,
            connected: OpenCodeServer?,
            saved: List<OpenCodeServer>,
            readiness: VoiceReadiness,
        ): VoicePick {
            when (choice) {
                VoiceServerChoice.Connected ->
                    if (connected != null) return VoicePick(VoiceProviderOption.Place.Server(connected), isAutomatic = false)
                is VoiceServerChoice.Saved -> {
                    choice.savedServer(saved)?.let { return VoicePick(VoiceProviderOption.Place.Server(it), isAutomatic = false) }
                    if (connected != null && choice.savedServer(listOf(connected)) != null) {
                        return VoicePick(VoiceProviderOption.Place.Server(connected), isAutomatic = false)
                    }
                }
                VoiceServerChoice.Automatic -> {}
            }
            if (connected != null && readiness.state(connected) in setOf(VoiceProviderState.ready, VoiceProviderState.unknown)) {
                return VoicePick(VoiceProviderOption.Place.Server(connected), isAutomatic = true)
            }
            val others = saved.filter { it.persistenceID != connected?.persistenceID }
            others.firstOrNull { readiness.state(it) == VoiceProviderState.ready }?.let {
                return VoicePick(VoiceProviderOption.Place.Server(it), isAutomatic = true)
            }
            if (connected != null) return VoicePick(VoiceProviderOption.Place.Server(connected), isAutomatic = true)
            return VoicePick(null, isAutomatic = true)
        }
    }
}

/** Where Settings opens from a failed call's card: Settings › Voice or its Voice Provider page. */
enum class VoiceSettingsPage { voice, provider }

/** The server brokering a call, as the call bar and its failure name it. */
data class VoiceCallPlace(val name: String, val brand: VoiceProviderOption.Brand)

/** The one button a failed call's card offers, besides closing it. */
enum class VoiceCallFix {
    chooseProvider,
    updateOpenCode,
    openSettings,
    ;

    val title: String
        get() = when (this) {
            chooseProvider -> localized(R.string.voice_fix_choose_provider)
            updateOpenCode -> localized(R.string.voice_fix_update_opencode)
            openSettings -> localized(CoreR.string.common_open_settings)
        }

    companion object {
        /** Where Update OpenCode sends the person. */
        const val downloadURL = "https://opencode.ai/download"
    }
}

/**
 * Opens what a failed call's fix points at: a web page, or this app's system settings.
 * `OpenURLClient` lives in `:feature:media`, above voice, so the call keeps this small opener;
 * inert under tests.
 */
class VoiceCallFixClient(
    val openURL: suspend (String) -> Unit,
    val openAppSettings: suspend () -> Unit,
) {
    companion object Key : DependencyKey<VoiceCallFixClient> {
        override val liveValue: VoiceCallFixClient by lazy {
            VoiceCallFixClient(
                openURL = { url -> start(Intent(Intent.ACTION_VIEW, url.toUri())) },
                openAppSettings = {
                    val context = dependency(AppContext)
                    start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                },
            )
        }
        override val testValue: VoiceCallFixClient = VoiceCallFixClient(openURL = {}, openAppSettings = {})

        private suspend fun start(intent: Intent) = withContext(Dispatchers.Main) {
            try {
                dependency(AppContext).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                AppLog.voice.notice("Couldn’t open a call fix: nothing handles it")
            }
        }
    }
}

/** Why a call couldn't start, in one plain sentence, and what fixes it. */
data class VoiceCallDiagnosis(
    val sentence: String,
    val fix: VoiceCallFix,
    /** The server that couldn't start it; null on this phone. */
    val place: String?,
) {
    companion object {
        /** `message` is the failure's text; `route` the call's, and `place` the server that brokered it. */
        fun diagnose(message: String, route: VoiceRoute, place: String?): VoiceCallDiagnosis {
            if (message == VoiceAudioError.microphoneDenied.message) {
                return VoiceCallDiagnosis(VoiceAudioError.microphoneDenied.displayMessage, VoiceCallFix.openSettings, null)
            }
            if (message == ServerVoiceError.unsupportedServerNotice || route == VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported)) {
                val sentence = if (place == null) localized(R.string.voice_diagnosis_your_server_needs_update) else localized(R.string.voice_diagnosis_needs_update, place)
                return VoiceCallDiagnosis(sentence, VoiceCallFix.updateOpenCode, place)
            }
            val lowered = message.lowercase()
            val sentence = when {
                lowered.contains("api key") || lowered.contains("sign-in") ->
                    if (place == null) localized(R.string.voice_diagnosis_your_server_no_key) else localized(R.string.voice_diagnosis_no_key, place)
                lowered.contains("offline") || lowered.contains("could not connect") || lowered.contains("timed out") ->
                    if (place == null) localized(R.string.voice_diagnosis_your_server_unreachable) else localized(R.string.voice_diagnosis_unreachable, place)
                else -> if (place == null) localized(R.string.voice_diagnosis_your_server_failed) else localized(R.string.voice_diagnosis_failed, place)
            }
            return VoiceCallDiagnosis(sentence, VoiceCallFix.chooseProvider, place)
        }
    }
}

/**
 * A call failure's text as the person reads it. The failure itself stays in English, since
 * it is compared, logged and parsed; text from a server or provider is shown as it came.
 */
fun voiceFailureDisplayText(message: String): String {
    when (message) {
        VoiceAudioError.microphoneDenied.message -> return VoiceAudioError.microphoneDenied.displayMessage
        VoiceAudioError.formatUnavailable.message -> return localized(R.string.voice_error_audio_format_unavailable)
        VoiceAudioError.engineRejectedMessage -> return localized(R.string.voice_error_audio_engine_rejected)
        ServerVoiceError.unsupportedServerNotice -> return localized(R.string.voice_error_server_unsupported)
        ServerVoiceError.InvalidServerURL.errorDescription -> return localized(R.string.voice_error_invalid_server_url)
        ServerVoiceError.InvalidResponse.errorDescription -> return localized(R.string.voice_error_invalid_response)
        RealtimeClientError.InvalidURL.message -> return localized(R.string.voice_error_service_address_invalid)
        RealtimeClientError.Dropped.message -> return localized(R.string.voice_notification_call_ended)
        "The voice connection was lost." -> return localized(R.string.voice_error_connection_lost)
        "The voice connection is closed." -> return localized(R.string.voice_error_connection_closed)
        "The voice connection couldn't be set up." -> return localized(R.string.voice_error_connection_setup)
        "A safety filter ended the voice call." -> return localized(R.string.voice_error_safety_filter)
        "Audio was reset by the system. Start the call again." -> return localized(R.string.voice_error_audio_reset)
    }
    val startPrefix = "Couldn’t start voice: "
    if (message.startsWith(startPrefix)) {
        return localized(R.string.voice_error_start_voice_detail, message.removePrefix(startPrefix))
    }
    val statusCode = httpStatusCode(message, "Couldn’t start voice (HTTP ", ").")
    if (statusCode != null) return localized(R.string.voice_error_start_voice_http, statusCode)
    return message
}

/** The status code in `<prefix><code><suffix>`, or null when [message] isn't in that shape. */
private fun httpStatusCode(message: String, prefix: String, suffix: String): Int? {
    if (!message.startsWith(prefix) || !message.endsWith(suffix)) return null
    return message.removePrefix(prefix).removeSuffix(suffix).toIntOrNull()
}
