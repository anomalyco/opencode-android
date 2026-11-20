package ai.opencode.feature.voice

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.holdsPrompt
import ai.opencode.server.sendPromptConfirming
import ai.opencode.server.transcriptWithContextUsageAndCursor
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * A call with no voice model in it: this phone listens with its own speech recognition, sends
 * what it heard to the session on screen, and speaks the reply with the speech server
 * (Settings › Voice › Voice Provider). Turns are push-to-talk in shape: it listens while the
 * agent is quiet and stops once a sentence is final.
 */
class LocalCallCoordinator(
    private val server: OpenCodeServer,
    private val sessionID: String,
    private val recognizer: LocalSpeechRecognizer = dependency(LocalSpeechRecognizer),
    private val speech: LocalSpeechClient = dependency(LocalSpeechClient),
    private val openCode: OpenCodeClient = dependency(OpenCodeClient),
    private val clock: Clock = dependency(Clock),
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : VoiceCallTestingCoordinator {
    private val values = DependencyValues.current
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + values.asContextElement())
    private val events = Channel<VoiceCallEvent>(Channel.UNLIMITED)
    private val finished = AtomicBoolean(false)
    private var muted = false

    override suspend fun checkIsFinished(): Boolean = finished.get()

    override suspend fun setAtEar(atEar: Boolean) = Unit

    override suspend fun end() {
        if (!finished.compareAndSet(false, true)) return
        scope.cancel()
        withContext(Dispatchers.Default) {
            runCatching { recognizer.stop() }
            runCatching { speech.stop() }
        }
        events.close()
    }

    suspend fun setMuted(muted: Boolean) {
        this.muted = muted
        if (muted) runCatching { recognizer.stop() }
    }

    suspend fun start(): Flow<VoiceCallEvent> {
        events.trySend(VoiceCallEvent.Connected)
        scope.launch { listenLoop() }
        return flow {
            try {
                for (event in events) emit(event)
            } finally {
                end()
            }
        }
    }

    private suspend fun listenLoop() {
        while (!finished.get() && coroutineContext[Job]!!.isActive) {
            if (muted) {
                clock.sleep(300.milliseconds)
                continue
            }
            val heard = try {
                turn()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clock.sleep(600.milliseconds)
                continue
            }
            if (heard.isBlank() || finished.get()) continue
            AppLog.voice.notice("Local call heard ${heard.length} characters")
            val id = "local-${heard.hashCode()}"
            events.trySend(VoiceCallEvent.UserSpeechStarted)
            events.trySend(VoiceCallEvent.UserTranscript(id, heard, final = true))
            val before = assistantIDs()
            try {
                openCode.sendPromptConfirming(server, sessionID, heard, OpenCodeDelivery.Queue)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.voice.notice("Local call send failed for $sessionID: ${error.message}")
                events.trySend(VoiceCallEvent.ServiceError(VoiceRoute.Reason.MissingPhoneKey(VoiceEngine.realtime).message))
                continue
            }
            AppLog.voice.notice("Local call prompt sent")
            // The agent and the server's model work at the same time: the model's short line is
            // heard while the agent is busy, rather than silence followed by the answer.
            val reply = coroutineScope {
                val waiting = async { awaitReply(before) }
                speakEngagement(heard)
                waiting.await()
            }
            if (reply.isBlank() || finished.get()) continue
            events.trySend(VoiceCallEvent.AssistantDelta(null, reply))
            events.trySend(VoiceCallEvent.AssistantDone(null, reply))
            val spoken = try {
                speech.speak(reply)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.voice.notice("Local call speech failed: ${error.message}")
                false
            }
            events.trySend(VoiceCallEvent.AssistantPlaybackFinished)
            if (!spoken) events.trySend(VoiceCallEvent.ServiceError(LocalSpeechError.Failed.message ?: ""))
        }
    }

    /** One utterance: the recognizer's final words, or empty when nothing was understood. */
    private suspend fun turn(): String {
        val result = CompletableDeferred<String>()
        recognizer.listen(
            onFinal = { text -> if (!result.isCompleted) result.complete(text) },
            onFailure = { if (!result.isCompleted) result.complete("") },
        )
        val text = result.await()
        recognizer.stop()
        return text.trim()
    }

    /** The server's model answers the same words while the agent works; its line is spoken. */
    private suspend fun speakEngagement(heard: String) {
        val words = try {
            speech.engage(heard) { line ->
                events.trySend(VoiceCallEvent.AssistantDelta(null, line))
                events.trySend(VoiceCallEvent.AssistantDone(null, line))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.voice.notice("Local call engagement failed: ${error.message}")
            null
        }
        if (!words.isNullOrBlank()) {
            AppLog.voice.notice("Local call engagement ${words.length} characters")
            events.trySend(VoiceCallEvent.AssistantPlaybackFinished)
        }
    }

    /** Waits for the agent's answer: a new assistant message that has stopped growing. */
    private suspend fun awaitReply(before: Set<String>): String {
        var waited = 0L
        var lastCount = 0
        var quiet = 0L
        while (waited < 120_000 && !finished.get()) {
            clock.sleep(600.milliseconds)
            waited += 600
            val items = try {
                openCode.transcriptWithContextUsageAndCursor(server, sessionID, 20).items
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.voice.notice("Local call reply read failed: ${error.message}")
                continue
            }
            val replies = items.filterIsInstance<OpenCodeTranscriptItem.AssistantMarkdown>().filter { it.id !in before && !it.inProgress }
            if (replies.isEmpty()) continue
            quiet = if (replies.size == lastCount) quiet + 600 else 0
            lastCount = replies.size
            if (quiet >= 1_800) {
                val reply = replies.joinToString("\n\n") { it.text }.trim()
                AppLog.voice.notice("Local call reply ${reply.length} characters")
                return reply
            }
        }
        return ""
    }

    private suspend fun assistantIDs(): Set<String> = try {
        openCode.transcriptWithContextUsageAndCursor(server, sessionID, 20).items
            .filterIsInstance<OpenCodeTranscriptItem.AssistantMarkdown>().map { it.id }.toSet()
    } catch (_: Exception) {
        emptySet()
    }
}

/** The phone's speech recognition, behind a seam so a test can answer without a microphone. */
interface LocalSpeechRecognizer {
    companion object Key : ai.opencode.core.dependencies.DependencyKey<LocalSpeechRecognizer> {
        override val liveValue: LocalSpeechRecognizer by lazy { AndroidSpeechRecognizer() }

        override val testValue: LocalSpeechRecognizer = object : LocalSpeechRecognizer {
            override suspend fun listen(onFinal: (String) -> Unit, onFailure: () -> Unit) = onFailure()
            override suspend fun stop() = Unit
        }
    }

    /** Listens until one final sentence, or until the recognizer gives up. */
    suspend fun listen(onFinal: (String) -> Unit, onFailure: () -> Unit)

    suspend fun stop()
}

/** Android's own recognizer, on the main looper, one listen at a time. */
class AndroidSpeechRecognizer : LocalSpeechRecognizer {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    override suspend fun listen(onFinal: (String) -> Unit, onFailure: () -> Unit) {
        val context = dependency(AppContext)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        main.post {
            val engine = try {
                SpeechRecognizer.createSpeechRecognizer(context)
            } catch (_: Exception) {
                null
            }
            if (engine == null) {
                onFailure()
                return@post
            }
            recognizer = engine
            var delivered = false
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: android.os.Bundle?) {
                    if (delivered) return
                    delivered = true
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    onFinal(text)
                }

                override fun onError(error: Int) {
                    if (delivered) return
                    delivered = true
                    onFailure()
                }

                override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            })
            try {
                engine.startListening(intent)
            } catch (_: Exception) {
                if (!delivered) {
                    delivered = true
                    onFailure()
                }
            }
        }
    }

    override suspend fun stop() {
        main.post {
            val engine = recognizer ?: return@post
            recognizer = null
            runCatching { engine.stopListening() }
            runCatching { engine.cancel() }
            runCatching { engine.destroy() }
        }
    }
}
