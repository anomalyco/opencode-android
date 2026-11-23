package ai.opencode.feature.voice

import ai.opencode.core.sharing.Shared
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.server.OpenCodeServer

/**
 * Shared settings read by Home's call entry points, without loading a session. Holds the `@Shared`
 * references; views that must follow a change collect their `flow`s. Equality is by reference
 * identity (`Shared`).
 */
data class VoiceRoutePreferences(
    val enabled: Shared<Boolean> = shared(SharedKeys.voiceModeEnabled),
    val engine: Shared<VoiceEngine> = shared(VoiceSharedKeys.voiceEngine),
    val provider: Shared<ServerVoiceProvider> = shared(VoiceSharedKeys.serverVoiceProvider),
    val voiceServer: Shared<VoiceServerChoice> = shared(VoiceSharedKeys.voiceServerChoice),
    val support: Shared<Map<String, ServerVoiceSupport>> = shared(VoiceSharedKeys.serverVoiceSupportByServer),
    val voiceModels: Shared<Map<String, ServerVoiceModelsAnswer>> = shared(VoiceSharedKeys.voiceModelsByServer),
) {
    fun route(server: OpenCodeServer): VoiceRoute {
        val (broker, _) = VoiceBroker.resolve(
            choice = voiceServer.value,
            provider = provider.value,
            connected = server,
            saved = VoiceFeature.savedServers,
            answers = voiceModels.value,
            support = support.value,
            engine = engine.value,
        )
        return broker.route(engine = engine.value, support = broker.support(support.value))
    }

    fun isAvailable(server: OpenCodeServer): Boolean {
        // The lab's scripted call (debug builds only).
        if (VoiceLabFlags.isEnabled) return true
        return enabled.value && route(server).isAvailable
    }
}
