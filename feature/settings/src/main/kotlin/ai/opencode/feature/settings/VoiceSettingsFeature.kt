package ai.opencode.feature.settings

import ai.opencode.core.dependencies.Dependency
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.voice.CallVoice
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceModelsAnswer
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceCallSoundPack
import ai.opencode.feature.voice.VoiceDelegatorChoice
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceModelsCache
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CancellationException

/**
 * The shared values are read live through [shared]; the reducer writes them only when they change.
 */
class VoiceSettingsFeature : Reducer<VoiceSettingsFeature.State, VoiceSettingsFeature.Action> {
    data class State(
        /** The server calls start from; the last one used when null. */
        val server: OpenCodeServer? = null,
        /** The connected server's sessions, for the Delegator page's pick (Home's, else its own load). */
        val sessions: List<OpenCodeSession> = emptyList(),
    ) {
        val serverVoiceSupportByServer: Map<String, ServerVoiceSupport> get() = shared(VoiceSharedKeys.serverVoiceSupportByServer).value
        val savedServer: OpenCodeServer? get() = shared(ServerSharedKeys.savedServer).value
        val savedServers: List<OpenCodeServer> get() = shared(ServerSharedKeys.savedServers).value
        val cachedHomeSnapshots: Map<String, CachedHomeSnapshot> get() = shared(ServerSharedKeys.cachedHomeSnapshots).value
        val voiceSessionDelegatorExperiment: Boolean get() = shared(ExperimentalFeature.voiceSessionDelegator.key).value
        val voiceDelegatorChoice: VoiceDelegatorChoice get() = shared(VoiceSharedKeys.voiceDelegatorChoice).value
        /** Each saved server's voice models, the connected one's too, by `persistenceID`, as it last answered. */
        val voiceModelsByServer: Map<String, ServerVoiceModelsAnswer> get() = shared(VoiceSharedKeys.voiceModelsByServer).value
        val callSoundPack: VoiceCallSoundPack get() = shared(VoiceSharedKeys.callSoundPack).value
        val serverVoiceProvider: ServerVoiceProvider get() = shared(VoiceSharedKeys.serverVoiceProvider).value
        val voiceServer: VoiceServerChoice get() = shared(VoiceSharedKeys.voiceServerChoice).value
        val voiceModeEnabled: Boolean get() = shared(SharedKeys.voiceModeEnabled).value
        val voiceEngine: VoiceEngine get() = shared(VoiceSharedKeys.voiceEngine).value
        val callVoice: CallVoice get() = shared(VoiceSharedKeys.callVoice).value
        val raiseToTalk: Boolean get() = shared(SharedKeys.raiseToTalk).value

        val serverVoiceSupport: ServerVoiceSupport get() = broker.support(serverVoiceSupportByServer)

        val route: VoiceRoute get() = broker.route(engine = voiceEngine, support = serverVoiceSupport)
    }

    sealed interface Action {
        data object Task : Action
        data class VoiceModeEnabledChanged(val on: Boolean) : Action
        data class VoiceEngineChanged(val engine: VoiceEngine) : Action
        data class CallVoiceSelected(val voice: CallVoice) : Action
        data class RaiseToTalkChanged(val on: Boolean) : Action
        data class CallSoundPackSelected(val pack: VoiceCallSoundPack) : Action
        data object ProviderPageAppeared : Action
        data class VoiceModelsLoaded(val serverID: String, val asked: Int, val answer: ServerVoiceModelsAnswer) : Action
        data class VoiceDelegatorChoiceChanged(val choice: VoiceDelegatorChoice) : Action
        data object DelegatorPageAppeared : Action
        data class DelegatorSessionsLoaded(val sessions: List<OpenCodeSession>) : Action
    }

    internal sealed interface CancelID {
        data class VoiceModels(val serverID: String) : CancelID
        data object DelegatorSessions : CancelID
    }

    internal val serverVoice by Dependency(ServerVoiceClient)
    private val openCodeClient by Dependency(OpenCodeClient)

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> Reduced(state, checkVoiceServers(listOfNotNull(state.connectedServer)))
        is Action.VoiceModeEnabledChanged -> {
            shared(SharedKeys.voiceModeEnabled).set(action.on)
            Reduced(state)
        }
        is Action.VoiceEngineChanged -> {
            shared(SharedKeys.voiceEngineMigratedToGPTLive).set(true)
            shared(VoiceSharedKeys.voiceEngine).set(action.engine)
            Reduced(state)
        }
        is Action.CallVoiceSelected -> {
            if (state.callVoice != action.voice) shared(VoiceSharedKeys.callVoice).set(action.voice)
            Reduced(state)
        }
        is Action.RaiseToTalkChanged -> {
            if (state.raiseToTalk != action.on) shared(SharedKeys.raiseToTalk).set(action.on)
            Reduced(state)
        }
        is Action.CallSoundPackSelected -> {
            if (state.callSoundPack != action.pack) shared(VoiceSharedKeys.callSoundPack).set(action.pack)
            Reduced(state)
        }
        Action.ProviderPageAppeared -> Reduced(state, checkVoiceServers(state.otherSavedServers))
        is Action.VoiceModelsLoaded -> {
            VoiceModelsCache.record(action.answer, action.serverID, action.asked)
            Reduced(state)
        }
        is Action.VoiceDelegatorChoiceChanged -> {
            if (state.voiceDelegatorChoice != action.choice) shared(VoiceSharedKeys.voiceDelegatorChoice).set(action.choice)
            Reduced(state)
        }
        Action.DelegatorPageAppeared -> {
            val server = state.connectedServer
            if (server == null) {
                Reduced(state)
            } else {
                val client = openCodeClient
                Reduced(
                    state,
                    Effect.run<Action> { send ->
                        val snapshot = try {
                            client.homeSnapshot(server)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            return@run
                        }
                        send(Action.DelegatorSessionsLoaded(snapshot.sessions))
                    }.cancellable(CancelID.DelegatorSessions, cancelInFlight = true),
                )
            }
        }
        is Action.DelegatorSessionsLoaded -> Reduced(state.copy(sessions = action.sessions))
    }
}
