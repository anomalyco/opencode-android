package ai.opencode.feature.voice

import kotlinx.serialization.json.Json
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpeechTests {
    @Test fun readsTheSamplesOutOfAWav() {
        val pcm = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        val wav = wav(pcm, sampleRate = 24000)

        val spoken = SpokenAudio.fromResponse(wav)

        assertEquals(24000, spoken?.sampleRate)
        assertArrayEquals(pcm, spoken?.pcm)
    }

    @Test fun readsARateTheServerChose() {
        val wav = wav(byteArrayOf(9, 0), sampleRate = 22050)

        assertEquals(22050, SpokenAudio.fromResponse(wav)?.sampleRate)
    }

    @Test fun refusesMp3RatherThanPlayingItAsSamples() {
        val mp3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 0)

        assertNull(SpokenAudio.fromResponse(mp3))
    }

    @Test fun refusesAnEmptyAnswer() {
        assertNull(SpokenAudio.fromResponse(byteArrayOf()))
    }

    @Test fun asksTheServerInTheCallsOwnFormat() {
        val request = LocalSpeech.request("Hello there.", CallVoice.marin, "https://kokoro.example.com/")

        assertEquals("https://kokoro.example.com/v1/audio/speech", request?.url.toString())
        assertNull(request?.header("Authorization"))
        val body = Buffer().also { requireNotNull(request?.body).writeTo(it) }.readUtf8()
        val payload = Json.decodeFromString(LocalSpeechPayload.serializer(), body)
        assertEquals("kokoro", payload.model)
        assertEquals("af_heart", payload.voice)
        assertEquals("Hello there.", payload.input)
        assertEquals("wav", payload.response_format)
    }

    @Test fun noServerMeansTheProviderSpeaks() {
        assertNull(LocalSpeech.request("Hello there.", CallVoice.marin, ""))
    }

    @Test fun aKeyIsSentOnlyWhenThereIsOne() {
        val request = LocalSpeech.request("Hi.", CallVoice.alloy, "https://kokoro.example.com", apiKey = " local-key ")

        assertEquals("Bearer local-key", request?.header("Authorization"))
    }

    @Test fun anUnknownVoiceStillSpeaksAsTheDefault() {
        val request = LocalSpeech.request("Hi.", CallVoice.zenith, "https://kokoro.example.com")

        val body = Buffer().also { requireNotNull(request?.body).writeTo(it) }.readUtf8()
        assertTrue(body.contains("af_heart"))
    }

    @Test fun asksForANewLineOnlyWhenItWantsOne() {
        val plain = LocalSpeech.request("Hi.", CallVoice.marin, "https://kokoro.example.com")
        val rewritten = LocalSpeech.request("Hi.", CallVoice.marin, "https://kokoro.example.com", newGeneration = true)

        val plainBody = Buffer().also { requireNotNull(plain?.body).writeTo(it) }.readUtf8()
        val rewrittenBody = Buffer().also { requireNotNull(rewritten?.body).writeTo(it) }.readUtf8()
        assertTrue(!plainBody.contains("new_generation"))
        assertTrue(rewrittenBody.contains("\"new_generation\":true"))
    }

    @Test fun speaksTheServersOwnLineWhenItRewrites() = kotlinx.coroutines.runBlocking {
        val words = "I'll build the React app you described. Checking the details now."
        val encoded = java.util.Base64.getEncoder().encodeToString(words.toByteArray())
        val played = mutableListOf<SpokenAudio>()
        val client = LocalSpeechClient.makeLive(
            server = { "https://kokoro.example.com" },
            voice = { CallVoice.marin },
            send = { VoiceHttpResponse(200, okhttp3.Headers.headersOf("X-Generated-Text", encoded), wav(byteArrayOf(1, 0, 2, 0), sampleRate = 24000)) },
            play = { played += it },
            stopPlayback = {},
        )
        var captioned = ""

        val line = client.engage("Build a React app.") { captioned = it }

        assertEquals(words, line)
        assertEquals(words, captioned)
        assertEquals(1, played.size)
    }

    @Test fun aServerThatDoesNotRewriteSaysNothing() = kotlinx.coroutines.runBlocking {
        val played = mutableListOf<SpokenAudio>()
        val client = LocalSpeechClient.makeLive(
            server = { "https://kokoro.example.com" },
            voice = { CallVoice.marin },
            send = { VoiceHttpResponse(200, okhttp3.Headers.headersOf(), wav(byteArrayOf(1, 0, 2, 0), sampleRate = 24000)) },
            play = { played += it },
            stopPlayback = {},
        )

        assertNull(client.engage("Build a React app.") { })
        assertTrue(played.isEmpty())
    }

    private fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = Buffer()
        header.writeUtf8("RIFF")
        header.writeIntLe(36 + pcm.size)
        header.writeUtf8("WAVE")
        header.writeUtf8("fmt ")
        header.writeIntLe(16)
        header.writeShortLe(1)
        header.writeShortLe(1)
        header.writeIntLe(sampleRate)
        header.writeIntLe(sampleRate * 2)
        header.writeShortLe(2)
        header.writeShortLe(16)
        header.writeUtf8("data")
        header.writeIntLe(pcm.size)
        return header.readByteArray() + pcm
    }
}
