package ai.opencode.feature.voice

import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeServer

/** The only precedence table for a new call. */
sealed interface VoiceRoute {
    data class Server(val server: ServerRoute) : VoiceRoute
    data class Unavailable(val reason: Reason) : VoiceRoute

    enum class OpenAI {
        gptLive, realtime;

        val engine: VoiceEngine get() = if (this == gptLive) VoiceEngine.gptLive else VoiceEngine.realtime
    }

    /** Named `ServerRoute`: Kotlin can't nest it beside the `Server` case. */
    sealed interface ServerRoute {
        data class OpenAI(val model: VoiceRoute.OpenAI) : ServerRoute
        data object GeminiLive : ServerRoute
    }

    sealed interface Reason {
        data object ServerUnsupported : Reason

        /** The server lists voice models, but none it's signed in to that the app can speak. */
        data object NoVoiceModels : Reason

        val message: String
            get() = when (this) {
                ServerUnsupported -> ServerVoiceError.unsupportedServerNotice
                NoVoiceModels -> localized(R.string.voice_error_no_voice_models)
            }
    }

    val isAvailable: Boolean get() = this !is Unavailable

    val isServer: Boolean get() = this is Server

    val usesBrokerDelegator: Boolean get() = this == serverOpenAI(OpenAI.gptLive)
    val sendsContextDelta: Boolean get() = engine == VoiceEngine.gptLive || this == serverGeminiLive

    /** Gemini is not an OpenAI engine, even though it shares the attached-session tools. */
    val engine: VoiceEngine?
        get() = when (this) {
            is Server -> when (val route = server) {
                is ServerRoute.OpenAI -> route.model.engine
                ServerRoute.GeminiLive -> null
            }
            is Unavailable -> null
        }

    val logEngine: String get() = engine?.rawValue ?: if (this == serverGeminiLive) "gemini-live" else "unavailable"
    val provider: ServerVoiceProvider get() = if (this == serverGeminiLive) ServerVoiceProvider.geminiLive else ServerVoiceProvider.openAI
    val title: String get() = engine?.title ?: "Gemini Live"

    val keySource: VoiceKeySource
        get() = when (this) {
            is Server -> when (server) {
                is ServerRoute.OpenAI -> VoiceKeySource.Server(providerName = "OpenAI")
                ServerRoute.GeminiLive -> VoiceKeySource.Server(providerName = "Gemini Live")
            }
            is Unavailable -> VoiceKeySource.Server(providerName = "Gemini Live")
        }

    fun sessionConfig(
        model: String,
        voice: CallVoice,
        context: VoiceInstructions.Context,
        isAtEar: Boolean,
        server: OpenCodeServer,
        delegation: LiveDelegation = LiveDelegation.client,
        unboundSpeechUsesInstructions: Boolean = false,
    ): RealtimeSessionConfig = RealtimeSessionConfig(
        engine = engine,
        model = model,
        voice = voice,
        instructions = VoiceInstructions.build(context.copy(model = model), route = this),
        tools = if (engine == VoiceEngine.gptLive) emptyList() else VoiceInstructions.tools,
        isAtEar = isAtEar,
        serverVoice = if (isServer) server else null,
        provider = provider,
        liveDelegation = if (usesBrokerDelegator) delegation else LiveDelegation.client,
        unboundSpeechUsesInstructions = unboundSpeechUsesInstructions,
    )

    companion object {
        fun serverOpenAI(model: OpenAI): VoiceRoute = Server(ServerRoute.OpenAI(model))

        val serverGeminiLive: VoiceRoute = Server(ServerRoute.GeminiLive)

        /** The call `model` starts, or why there's none: `missing` when the server lists nothing usable. */
        fun resolve(
            model: ServerVoiceModel?,
            serverVoiceEnabled: Boolean,
            serverVoiceSupport: ServerVoiceSupport,
            missing: Reason = Reason.NoVoiceModels,
        ): VoiceRoute {
            if (!serverVoiceEnabled || serverVoiceSupport == ServerVoiceSupport.unsupported) return Unavailable(Reason.ServerUnsupported)
            val route = model?.let { ServerVoiceModels.brokered(it) } ?: return Unavailable(missing)
            return Server(route)
        }
    }
}

/**
 * Who pays for a call, moved down to Voice because [VoiceRoute.keySource] returns it and Settings
 * sits above Voice.
 */
sealed interface VoiceKeySource {
    data class Server(val providerName: String) : VoiceKeySource

    /**
     * The server's OpenCode Console connection: OpenCode hosts the call and
     * bills the connection's workspace.
     */
    data object OpenCode : VoiceKeySource

    /** Settings › Voice's footer: whose key, or which workspace, pays for calls. */
    val footer: String
        get() = when (this) {
            is Server -> localized(R.string.voice_key_source_server_footer, providerName)
            OpenCode -> localized(R.string.voice_key_source_opencode_footer)
        }
}

/**
 * Which server brokers a new call's Server Voice session (Settings › Voice ›
 * Voice Provider), for calls on `connected`. Settings, Home, the session and a
 * call's start all resolve it here ([VoicePick.resolve]), and [route] resolves
 * the call from it. Only the broker moves: a call's session, context and
 * tools stay on its own server.
 */
class VoiceBroker(
    choice: VoiceServerChoice,
    connected: OpenCodeServer?,
    saved: List<OpenCodeServer>,
    private val readiness: VoiceReadiness = VoiceReadiness(),
) {
    /** A server brokers new calls: the pick, or Automatic's choice, is a server. */
    val isEnabled: Boolean

    /** The server that brokers, picked or chosen automatically. Null while nothing brokers. */
    val server: OpenCodeServer?

    /** Chosen automatically: a server without the voice routes moves on to the next ready one. A pick never does. */
    val isAutomatic: Boolean

    init {
        val pick = VoicePick.resolve(choice, connected, saved, readiness)
        server = pick.server
        isEnabled = server != null
        isAutomatic = pick.isAutomatic
    }

    companion object {
        /** The broker and the readiness it was resolved with. */
        fun resolve(
            choice: VoiceServerChoice,
            connected: OpenCodeServer?,
            saved: List<OpenCodeServer>,
            answers: Map<String, ServerVoiceModelsAnswer>,
            support: Map<String, ServerVoiceSupport>,
            pick: String,
        ): Pair<VoiceBroker, VoiceReadiness> {
            val readiness = VoiceReadiness(answers = answers, support = support, pick = pick)
            val broker = VoiceBroker(choice, connected, saved, readiness)
            return broker to readiness
        }
    }

    /** What's known of whether the broker offers `POST /api/experimental/voice/session`. */
    fun support(supportByServer: Map<String, ServerVoiceSupport>): ServerVoiceSupport =
        server?.let { supportByServer[it.persistenceID] } ?: ServerVoiceSupport.unknown

    /** The route a new call takes through this broker: the model it picks there. */
    fun route(support: ServerVoiceSupport): VoiceRoute {
        val server = server
        return VoiceRoute.resolve(
            model = server?.let { readiness.chosen(it) },
            serverVoiceEnabled = isEnabled,
            serverVoiceSupport = support,
            missing = if (server != null && readiness.state(server) == VoiceProviderState.needsUpdate) {
                VoiceRoute.Reason.ServerUnsupported
            } else {
                VoiceRoute.Reason.NoVoiceModels
            },
        )
    }

    override fun equals(other: Any?): Boolean =
        other is VoiceBroker && other.isEnabled == isEnabled && other.server == server && other.isAutomatic == isAutomatic
    override fun hashCode(): Int = listOf(isEnabled, server, isAutomatic).hashCode()
    override fun toString(): String =
        "VoiceBroker(isEnabled=$isEnabled, server=${server?.persistenceID?.let { "set" }}, isAutomatic=$isAutomatic)"
}
