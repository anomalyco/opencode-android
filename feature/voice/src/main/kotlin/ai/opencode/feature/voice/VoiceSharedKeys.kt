package ai.opencode.feature.voice

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey

/**
 * The voice-typed shared keys, declared here because their value types belong to Voice.
 * [VoiceEngine] (`LiveProtocol.kt`), [ServerVoiceProvider], [ServerVoiceSupport] and
 * [VoiceServerChoice] (`ServerVoiceClient.kt`), [CallVoice] (`CallVoice.kt`),
 * [VoiceTranscriptRecord] (`VoiceTranscript.kt`). Bool and key-chain voice keys
 * (`voiceModeEnabled`, …) live in
 * `:core`'s `SharedKeys`.
 */
object VoiceSharedKeys {
    /** Whether each connected server (by `persistenceID`) offers `POST /api/experimental/voice/session`. */
    val serverVoiceSupportByServer =
        SharedKey.inMemory<Map<String, ServerVoiceSupport>>("server-voice-support", emptyMap())

    /** The voice new calls speak with. A call on an engine that doesn't offer it uses the default instead (`CallVoice.resolved(engine)`). */
    val callVoice = SharedKey.appStorage("voice-call-voice", CallVoice.default)

    /** Settings › Voice › Call Sounds. */
    val callSoundPack = SharedKey.appStorage("call-sound-pack", VoiceCallSoundPack.default)

    /** Settings › Voice › Voice Provider. Automatic by default. */
    val voiceServerChoice = SharedKey.appStorage<VoiceServerChoice>("voice-server", VoiceServerChoice.Automatic)

    /** Settings › Voice › Delegator. Built-in by default. */
    val voiceDelegatorChoice = SharedKey.appStorage<VoiceDelegatorChoice>("voice-delegator-choice", VoiceDelegatorChoice.BuiltIn)

    /** Cached Automatic `"Voice"` delegator session ID per server (`srv_…` → `ses_…`). */
    val voiceDelegatorSessionIDs = SharedKey.fileStorage<Map<String, String>>(
        FileDirectory.Documents,
        "voice-delegator-sessions.json",
        emptyMap(),
    )

    /**
     * Each saved server's last voice models answer, by [voiceServerID]. Asked on each connect, and
     * kept until the server answers again so the call button and the model list draw from it at once.
     */
    val voiceModelsByServer =
        SharedKey.appStorage<Map<String, ServerVoiceModelsAnswer>>("voice-models-by-server", emptyMap())

    /** The voice model new calls ask for, by id; empty for Automatic, the first usable one the server lists. */
    val voiceModel = SharedKey.appStorage("voice-model", "")

    /**
     * How many times each server (by [voiceServerID]) reconnected: an answer asked before the
     * latest says nothing of the server now.
     */
    val voiceModelsEpochs = SharedKey.inMemory<Map<String, Int>>("voice-models-epochs", emptyMap())

    /** Phone-local voice transcripts keyed by `serverID:sessionID`. */
    val voiceTranscripts = SharedKey.fileStorage<Map<String, VoiceTranscriptRecord>>(
        FileDirectory.Documents,
        "voice-transcripts.json",
        emptyMap(),
    )
}
