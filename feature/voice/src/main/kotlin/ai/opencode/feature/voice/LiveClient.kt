package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant

/** Commands/events are encoded/decoded here; nothing above this layer sees JSON/base64. */
class LiveConnection(
    override val events: Flow<LiveServerEvent>,
    override val send: suspend (LiveCommand) -> Unit,
    override val close: suspend () -> Unit,
    override val startAudio: (suspend (Boolean) -> VoiceAudioSession)? = null,
) : VoiceSocketConnection<LiveServerEvent, LiveCommand>

class LiveClient(val connect: suspend (LiveSessionConfig) -> LiveConnection) {
    companion object Key : DependencyKey<LiveClient> {
        override val liveValue = LiveClient { config ->
            try { LiveRTC.connect(config) }
            catch (error: LiveRTCRejected) { throw RealtimeClientError.Closed(error.message) }
        }
        override val testValue = LiveClient { _ -> LiveConnection(emptyFlow(), {}, {}) }
    }
}
/** WebRTC unless it recently failed on this network. A successful WebSocket
 * fallback sticks ten minutes so reconnects do not spend the connect limit again. */
class LiveTransportChoice(private val date: DateClient = dependency(DateClient)) {
    private var failedAt: Instant? = null
    val prefersWebRTC: Boolean
        @Synchronized get() {
            when (VoiceLabEnvironment.string("OPENCODE_LAB_LIVE_TRANSPORT")) {
                "websocket" -> return false
                "webrtc" -> return true
            }
            val failed = failedAt ?: return true
            if (java.time.Duration.between(failed, date.now()).toMillis() / 1000.0 > webSocketFor) {
                failedAt = null; return true
            }
            return false
        }
    @Synchronized fun webRTCFailed(at: Instant = date.now()) { failedAt = at }
    @Synchronized fun webRTCConnected() { failedAt = null }
    suspend fun <Connection> connect(webRTC: suspend () -> Connection, webSocket: suspend () -> Connection): Connection {
        if (!prefersWebRTC) return webSocket()
        val rtcError = try { return webRTC().also { webRTCConnected() }
        } catch (error: LiveRTCRejected) { throw RealtimeClientError.Closed(error.message)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { currentCoroutineContext().ensureActive(); error }
        val log = dependency(VoiceCallLogClient)
        log.noteCurrent(VoiceCallLog.Note.Kind.audio, "⚠︎ WebRTC couldn't connect (${RealtimeTransportDiagnostic.of(rtcError).logSummary}); trying a WebSocket")
        try {
            return webSocket().also {
                webRTCFailed(); log.noteCurrent(VoiceCallLog.Note.Kind.audio, "Connected over a WebSocket instead of WebRTC")
            }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { currentCoroutineContext().ensureActive(); throw rtcError }
    }
    companion object Key : DependencyKey<LiveTransportChoice> {
        const val webSocketFor = 10 * 60.0
        override val liveValue by lazy { LiveTransportChoice() }
        override val testValue get() = LiveTransportChoice()
    }
}
