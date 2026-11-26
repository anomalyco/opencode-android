package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.BackgroundTaskClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.InAppNoticeMode
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.notices.DeliveredNotificationsClient
import ai.opencode.feature.drawer.notices.InAppNoticeSignal
import ai.opencode.feature.session.InAppNotice
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.system.background.BackgroundRunServiceClient
import ai.opencode.feature.system.notifications.SessionPushNotifications
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CancellationException

/** What Stay Connected During Runs' service follows. */
internal data class BackgroundRunDemand(val wanted: Boolean, val isInBackground: Boolean)

internal val State.backgroundRunDemand: BackgroundRunDemand
    get() = BackgroundRunDemand(
        wanted = isStreaming && hasConnected && !credentialsRejected && activeSessionIDs.isNotEmpty(),
        isInBackground = connection.isInBackground,
    )

/**
 * Stay Connected During Runs (Experimental): the service runs while any session on this server
 * works. Android refuses to start it from the background, so a run first seen there goes without.
 */
internal fun syncBackgroundRuns(state: State): HomeReduced {
    val client = dependency(BackgroundRunServiceClient)
    val demand = state.backgroundRunDemand
    if (!dependency(ExperimentalFeatures)(ExperimentalFeature.backgroundRuns) || !demand.wanted) {
        return Reduced(state, Effect.run { client.stop() })
    }
    if (demand.isInBackground) return Reduced(state)
    return Reduced(state, Effect.run { client.start() })
}

internal val HomeSettingsFeature.Action.switchesBackgroundRuns: Boolean
    get() {
        if (this is HomeSettingsFeature.Action.ExperimentalFeatureChanged) return feature == ExperimentalFeature.backgroundRuns
        if (this is HomeSettingsFeature.Action.ExperimentalFeatureApplied) return feature == ExperimentalFeature.backgroundRuns
        return false
    }

/** What Stay Connected During Runs' notifications read: Home's live state while its switch is on. */
internal data class BackgroundNoticeReading(
    val isOn: Boolean,
    val isHeld: Boolean,
    val isInBackground: Boolean,
    val input: InAppNoticeInput,
)

internal fun backgroundNoticeReading(state: State): BackgroundNoticeReading {
    if (!dependency(ExperimentalFeatures)(ExperimentalFeature.backgroundRuns)) {
        return BackgroundNoticeReading(isOn = false, isHeld = false, isInBackground = false, input = InAppNoticeInput.off)
    }
    return BackgroundNoticeReading(
        isOn = true,
        isHeld = state.connection.isBackgroundHeld,
        isInBackground = state.connection.isInBackground,
        input = liveNoticeInput(state, InAppNoticeMode.all),
    )
}

/**
 * While the app is away, notifies a session that starts needing the user or finishes a run since
 * the last live reading. Readings in the foreground only move the baseline, so what was pending
 * when the app left stays quiet, and a reconnect compares against the last reading before the drop.
 */
internal fun backgroundNoticesChanged(reading: BackgroundNoticeReading, state: State): HomeReduced {
    val baseline = state.backgroundNoticeBaseline
    val follows = reading.isOn && (reading.isHeld || (reading.isInBackground && baseline.isLive))
    if (!follows) {
        if (baseline == InAppNoticeSignal.none) return Reduced(state)
        return Reduced(state.copy(backgroundNoticeBaseline = InAppNoticeSignal.none))
    }
    val signal = reading.input.signal
    if (!signal.isLive) return Reduced(state)
    val s = state.copy(backgroundNoticeBaseline = signal)
    if (!reading.isInBackground) return Reduced(s)
    val effects = mutableListOf<Effect<Action>>()
    for ((event, _) in InAppNoticeSignal.events(from = baseline, to = signal)) {
        effects += postBackgroundNotice(s, event)
    }
    return Reduced(s, Effect.merge(effects))
}

private fun postBackgroundNotice(state: State, event: InAppNotice.Event): Effect<Action> {
    val delivered = dependency(DeliveredNotificationsClient)
    val client = dependency(OpenCodeClient)
    val background = dependency(BackgroundTaskClient)
    val server = state.server
    val sessionID = event.sessionID
    val isNeedsInput = event.kind == InAppNotice.Kind.needsInput
    return Effect.run<Action> {
        val end = background.begin("Session notification")
        try {
            var status: String? = SessionPushNotifications.STATUS_NEEDS_INPUT
            if (!isNeedsInput) {
                val items = try {
                    client.transcript(server, sessionID, runOutcomeFetchLimit)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    null
                }
                status = runOutcomeStatus(items)
            }
            if (status == null) {
                AppLog.stream.info { "background_notice_skipped session=$sessionID stopped" }
            } else {
                AppLog.stream.info { "background_notice_posted session=$sessionID status=$status" }
                delivered.post(server.persistenceID, sessionID, event.title, status)
            }
        } finally {
            end()
        }
    }.cancellable(HomeFeature.CancelID.BackgroundNotice(sessionID), cancelInFlight = true)
}

private const val runOutcomeFetchLimit = 20

/** A finished run's notification status from its transcript's tail; null when the user stopped it. */
internal fun runOutcomeStatus(items: List<OpenCodeTranscriptItem>?): String? {
    if (items == null) return SessionPushNotifications.STATUS_SUCCEEDED
    for (item in items.asReversed()) {
        if (item is OpenCodeTranscriptItem.UserText) break
        if (item is OpenCodeTranscriptItem.RunError) {
            if (item.error.isStop) return null
            return SessionPushNotifications.STATUS_FAILED
        }
    }
    return SessionPushNotifications.STATUS_SUCCEEDED
}
