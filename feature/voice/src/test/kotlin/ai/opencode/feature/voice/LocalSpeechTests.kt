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
