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
            val reply = try {
                askSession(heard)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.voice.notice("Local call prompt failed: ${error.message}")
                events.trySend(VoiceCallEvent.ServiceError(VoiceRoute.Reason.MissingPhoneKey(VoiceEngine.realtime).message))
                continue
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

    private suspend fun askSession(text: String): String {
        val before = assistantIDs()
        val messageID = openCode.sendPromptConfirming(server, sessionID, text, OpenCodeDelivery.Queue)
        AppLog.voice.notice("Local call prompt sent")
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
            } catch (_: Exception) {
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
