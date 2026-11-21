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
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.OpenCodeToolCall
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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

    /**
     * Waits for the agent's answer: the whole run must be over before anything is spoken. A run
     * that keeps working (a long task, a subagent's) is waited out however long it takes, so the
     * call never listens over it; only a run that ends without an answer or a server that can't
     * be read ends the wait.
     */
    private suspend fun awaitReply(before: Set<String>): String {
        var last = ""
        var stable = 0L
        // Idle with no answer yet: a prompt queued behind another run gets a moment to start.
        var pending = 0L
        // The server can't be read: with no run state to go on, this is the only limit.
        var unreadable = 0L
        while (!finished.get()) {
            clock.sleep(pollInterval)
            val items = try {
                openCode.transcriptWithContextUsageAndCursor(server, sessionID, 20).items
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.voice.notice("Local call reply read failed: ${error.message}")
                unreadable = if (sessionRunning()) 0 else unreadable + pollInterval.inWholeMilliseconds
                if (unreadable >= unreadableLimit.inWholeMilliseconds) return ""
                continue
            }
            unreadable = 0
            val replies = items
                .filterIsInstance<OpenCodeTranscriptItem.AssistantMarkdown>()
                .filter { it.id !in before && !it.inProgress }
            val text = replies.joinToString("\n\n") { it.text }.trim()
            if (stillWorking(items) || sessionRunning()) {
                last = ""
                stable = 0
                pending = 0
                continue
            }
            if (text.isBlank()) {
                pending += pollInterval.inWholeMilliseconds
                if (pending >= pendingLimit.inWholeMilliseconds) return ""
                continue
            }
            pending = 0
            // Idle and unchanged for a moment: every part of the run has landed.
            stable = if (text == last) stable + pollInterval.inWholeMilliseconds else 0
            last = text
            if (stable >= replySettle.inWholeMilliseconds) {
                AppLog.voice.notice("Local call reply ${text.length} characters")
                return text
            }
        }
        return ""
    }

    private fun stillWorking(items: List<OpenCodeTranscriptItem>): Boolean = transcriptShowsWork(items)

    /** The server may report idle between steps; it is the final word on a finished run. */
    private suspend fun sessionRunning(): Boolean = try {
        openCode.isSessionActive(server, sessionID)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun assistantIDs(): Set<String> = try {
        openCode.transcriptWithContextUsageAndCursor(server, sessionID, 20).items
            .filterIsInstance<OpenCodeTranscriptItem.AssistantMarkdown>().map { it.id }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private companion object {
        /** How often the call reads the run while it waits. */
        val pollInterval = 600.milliseconds
        /** Idle with no answer yet: long enough for a queued prompt to start. */
        val pendingLimit = 15.seconds
        /** The answer's text holds still this long: every part of the run has landed. */
        val replySettle = 1_200.milliseconds
        /** A server that can't be read is given up on after this long, so the call can listen. */
        val unreadableLimit = 2.minutes
    }
}

/** True while a tool, a compaction or a streaming message is still going. */
internal fun transcriptShowsWork(items: List<OpenCodeTranscriptItem>): Boolean = items.any { item ->
    when (item) {
        is OpenCodeTranscriptItem.Tool ->
            item.call.status == OpenCodeToolCall.Status.Running ||
                item.call.status == OpenCodeToolCall.Status.Streaming
        is OpenCodeTranscriptItem.Compaction ->
            item.info?.status == OpenCodeCompaction.Status.Running
        else -> item.isInProgressText
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
