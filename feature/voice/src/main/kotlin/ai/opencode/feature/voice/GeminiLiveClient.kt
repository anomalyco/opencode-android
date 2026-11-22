package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class GeminiLiveConnection(
    override val events: Flow<GeminiLiveServerEvent>,
    override val send: suspend (GeminiLiveCommand) -> Unit,
    override val close: suspend () -> Unit,
) : VoiceSocketConnection<GeminiLiveServerEvent, GeminiLiveCommand>

class GeminiLiveClient(val connect: suspend (GeminiLiveSessionConfig) -> GeminiLiveConnection) {
    companion object Key : DependencyKey<GeminiLiveClient> {
        override val liveValue = GeminiLiveClient { config -> GeminiLiveSocket(config).open() }
        override val testValue = GeminiLiveClient { _ -> GeminiLiveConnection(emptyFlow(), {}, {}) }
    }
}
class GeminiLiveSocket(private val config: GeminiLiveSessionConfig) {
    suspend fun open(): GeminiLiveConnection {
        val server = config.serverVoice ?: throw RealtimeClientError.InvalidURL
        val transport = VoiceBrokerTransport(server).webSocket(config.model, config.serverSessionPayload)
        val targetURL = GeminiLiveSessionConfig.webSocketURL(transport.url, transport.token)
        val socket = VoiceWebSocket(targetURL ?: throw RealtimeClientError.InvalidURL)
        socket.sendVoiceHandshake("gemini.setup") { socket.sendUTF8(GeminiLiveCommand.Setup(config).encoded()) }
        return GeminiLiveConnection(
            events = socket.voiceEventStream(GeminiLiveServerEvent::decodeAll),
            send = { command -> socket.sendUTF8(command.encoded(), audio = command is GeminiLiveCommand.RealtimeInputAudio) },
            close = { socket.close() },
        )
    }
}
