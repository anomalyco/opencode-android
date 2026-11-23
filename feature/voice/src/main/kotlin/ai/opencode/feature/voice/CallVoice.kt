package ai.opencode.feature.voice

import ai.opencode.core.strings.localized
import kotlinx.serialization.Serializable

/**
 * A built-in voice a call speaks with. GPT-Live offers every case; Realtime
 * offers only the ten it shares. Both fix the voice when the call starts
 * (Realtime once it has spoken), so a change applies to the next call.
 *
 * Sources: openai-node `LiveAPI.BuiltInVoice` (`resources/live/live.ts`) and
 * `RealtimeAudioConfigOutput.voice` (`resources/realtime/realtime.ts`); OpenAI's
 * Managing GPT-Live sessions and Realtime conversations guides.
 */
@Suppress("EnumEntryName")
@Serializable
enum class CallVoice {
    alloy, ash, ballad, beacon, bossa, cedar, cinder, coral, delta, echo, gleam,
    marin, meridian, quartz, ripple, sage, shimmer, stone, tempo, verse, vesper, willow;

    val rawValue: String get() = name
    val id: CallVoice get() = this

    val title: String get() = name.replaceFirstChar(Char::uppercaseChar)

    /** How the regional voices speak; null for the rest. */
    val accent: String?
        get() = when (this) {
            quartz, ripple -> localized(R.string.voice_accent_australian_english)
            vesper -> localized(R.string.voice_accent_british_english)
            willow, stone -> localized(R.string.voice_accent_irish_english)
            gleam, meridian -> localized(R.string.voice_accent_north_american_english)
            bossa, tempo -> localized(R.string.voice_accent_brazilian_portuguese)
            beacon -> localized(R.string.voice_accent_filipino_english)
            delta, cinder -> localized(R.string.voice_accent_southern_us_english)
            else -> null
        }

    fun isSupported(engine: VoiceEngine): Boolean = when (engine) {
        VoiceEngine.gptLive -> true
        VoiceEngine.realtime -> this in realtimeVoices
    }

    /**
     * The voice a call on `engine` uses for this saved choice: the choice
     * itself, or the engine's default when it doesn't offer it.
     */
    fun resolved(engine: VoiceEngine): CallVoice = if (isSupported(engine)) this else default

    companion object {
        val default = marin

        val allCases: List<CallVoice> get() = entries

        fun supported(engine: VoiceEngine): List<CallVoice> = entries.filter { it.isSupported(engine) }

        fun ofRawValue(rawValue: String): CallVoice? = entries.firstOrNull { it.name == rawValue }

        private val realtimeVoices = setOf(alloy, ash, ballad, coral, echo, sage, shimmer, verse, marin, cedar)
    }
}
