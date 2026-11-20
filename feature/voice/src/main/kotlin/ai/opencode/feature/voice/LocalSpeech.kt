package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeTransport
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speaks text through a self-hosted OpenAI-compatible speech server (Kokoro) in the call's own
 * format: PCM16 mono at [sampleRate], the same frames a live model streams.
 */

/** One PCM16 mono render of a sentence. */
class SpokenAudio(val pcm: ByteArray, val sampleRate: Int = LocalSpeech.sampleRate) {
    companion object {
        /**
         * The samples inside a WAV file, or the bytes as they are when the server answered with
         * raw PCM. Null when the data is neither (an MP3, for instance).
         */
        fun fromResponse(data: ByteArray): SpokenAudio? {
            if (data.isEmpty()) return null
            if (data.size < 12 || data[0] != 'R'.code.toByte() || data[1] != 'I'.code.toByte() ||
                data[2] != 'F'.code.toByte() || data[3] != 'F'.code.toByte()
            ) {
                return if (looksLikeMp3(data)) null else SpokenAudio(data)
            }
            var index = 12
            var rate = LocalSpeech.sampleRate
            var channels = 1
            var bits = 16
            while (index + 8 <= data.size) {
                val id = String(data, index, 4, Charsets.US_ASCII)
                val size = littleEndianInt(data, index + 4)
                val body = index + 8
                if (id == "fmt " && body + 16 <= data.size) {
                    channels = littleEndianShort(data, body + 2)
                    rate = littleEndianInt(data, body + 4)
                    bits = littleEndianShort(data, body + 14)
                } else if (id == "data") {
                    // A streamed WAV writes an unknown (all-ones) or zero size: the rest is samples.
                    val end = if (size <= 0 || body + size > data.size || body + size < body) data.size else body + size
                    if (end <= body) return null
                    if (channels != 1 || bits != 16) return null
                    return SpokenAudio(data.copyOfRange(body, end), rate)
                }
                if (size < 0) return null
                index = body + size + (size and 1)
            }
            return null
        }

        private fun looksLikeMp3(data: ByteArray): Boolean =
            data.size > 3 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()

        private fun littleEndianInt(data: ByteArray, at: Int): Int = ByteBuffer.wrap(data, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

        private fun littleEndianShort(data: ByteArray, at: Int): Int =
            (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    }
}

/** The `POST /v1/audio/speech` body a self-hosted server answers in WAV. */
@Serializable
@Suppress("PropertyName")
data class LocalSpeechPayload(
    val model: String = VoicePreviewRequest.localTTSModel,
    val voice: String,
    val input: String,
    val response_format: String = "wav",
)

object LocalSpeech {
    const val sampleRate = 24_000

    /** How many sentences are being made at once, this one included. */
    const val ahead = 2

    /** Sentences to speak one at a time: a long reply starts after its first sentence, not its last. */
    fun sentences(text: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (character in text) {
            current.append(character)
            if (character in ".!?\n" && current.toString().trim().length >= 24) {
                parts += current.toString().trim()
                current.clear()
            }
        }
        if (current.toString().trim().isNotEmpty()) parts += current.toString().trim()
        return parts.ifEmpty { listOf(text.trim()) }
    }

    private val encoder = kotlinx.serialization.json.Json { encodeDefaults = true }

    fun request(text: String, voice: CallVoice, server: String, apiKey: String = ""): Request? {
        val url = VoicePreviewRequest.speechURL(server) ?: return null
        val body = encoder.encodeToString(
            LocalSpeechPayload.serializer(),
            LocalSpeechPayload(voice = voice.synthesizedVoice, input = text),
        )
        val builder = Request.Builder().url(url).header("Content-Type", "application/json")
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isNotEmpty()) builder.header("Authorization", "Bearer $trimmedKey")
        return builder.post(body.toByteArray().toRequestBody(voiceJsonMediaType)).build()
    }
}

/**
 * Speaks with the speech server set in Settings › Voice › Voice Provider. [speak] returns once the
 * clip has played, false when no server is set (a call falls back to the provider's voice).
 */
class LocalSpeechClient(
    val speak: suspend (text: String) -> Boolean,
    val stop: suspend () -> Unit,
) {
    companion object Key : DependencyKey<LocalSpeechClient> {
        override val liveValue: LocalSpeechClient by lazy { makeLive() }

        override val testValue: LocalSpeechClient = LocalSpeechClient(speak = { false }, stop = {})

        fun makeLive(
            server: () -> String = { shared(SharedKeys.voiceSpeechServer).value },
            voice: () -> CallVoice = { shared(VoiceSharedKeys.callVoice).value },
            send: suspend (Request) -> VoiceHttpResponse = { request ->
                dependency(OpenCodeTransport).request.withVoiceTimeout(60).voiceSend(request)
            },
            play: suspend (SpokenAudio) -> Unit = { LocalSpeechPlayer.play(it) },
            stopPlayback: suspend () -> Unit = { LocalSpeechPlayer.stop() },
        ): LocalSpeechClient = LocalSpeechClient(
            speak = { text ->
                val address = server().trim()
                if (address.isEmpty() || text.isBlank()) {
                    false
                } else {
                    // Sentence by sentence: the first plays while the next are being made.
                    val chunks = LocalSpeech.sentences(text)
                    coroutineScope {
                        val queue = ArrayDeque<Pair<Int, kotlinx.coroutines.Deferred<SpokenAudio?>>>()
                        fun fetch(index: Int) = queue.addLast(
                            index to async {
                                try {
                                    val request = LocalSpeech.request(chunks[index], voice(), address) ?: return@async null
                                    val response = send(request)
                                    if (response.statusCode !in 200..<300) null else SpokenAudio.fromResponse(response.body)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                }
                            },
                        )
                        repeat(minOf(LocalSpeech.ahead, chunks.size)) { fetch(it) }
                        var started = false
                        while (queue.isNotEmpty()) {
                            val (index, job) = queue.removeFirst()
                            val audio = job.await()
                            val next = index + LocalSpeech.ahead
                            if (next < chunks.size) fetch(next)
                            if (audio != null) {
                                started = true
                                play(audio)
                            }
                        }
                        started
                    }
                }
            },
            stop = stopPlayback,
        )
    }
}

sealed class LocalSpeechError : Exception("Couldn’t speak") {
    data object Failed : LocalSpeechError()

    override fun fillInStackTrace(): Throwable = this
}

/** One process-wide player, in the call's own format and route. */
internal object LocalSpeechPlayer {
    private var track: AudioTrack? = null

    suspend fun play(audio: SpokenAudio) = withContext(Dispatchers.IO) {
        stop()
        val minimum = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(minimum, audio.pcm.size.coerceAtMost(audio.sampleRate * 2))
        val player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(size)
            .build()
        track = player
        try {
            player.play()
            var offset = 0
            while (offset < audio.pcm.size) {
                ensureActive()
                if (track !== player) return@withContext
                val count = minOf(size, audio.pcm.size - offset)
                val written = player.write(audio.pcm, offset, count)
                if (written <= 0) break
                offset += written
            }
            // Wait for the buffer to play out: the caller starts listening again after this
            // returns, and a microphone opened over our own voice hears the agent and barges in.
            val totalFrames = audio.pcm.size / 2
            val timeout = totalFrames.toLong() * 1_000 / audio.sampleRate + 2_000
            var waited = 0L
            while (track === player && player.playbackHeadPosition < totalFrames && waited < timeout) {
                kotlinx.coroutines.delay(40)
                waited += 40
            }
            player.stop()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (track === player) track = null
            runCatching { player.release() }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val player = track ?: return@withContext
        track = null
        runCatching { player.pause(); player.flush(); player.stop() }
        runCatching { player.release() }
    }
}
