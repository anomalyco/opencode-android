package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.system.notifications.AppBadgeLedger
import ai.opencode.feature.system.notifications.AppIconBadgeClient
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant

/*
 * What Home does as notifications change (before `HomeNotificationsFeature` settles the same
 * action) and the app icon's badge.
 */

internal fun HomeFeature.reduceNotifications(state: State, action: HomeNotificationsFeature.Action): HomeReduced {
    val permission = when (action) {
        is HomeNotificationsFeature.Action.Loaded -> action.permission
        is HomeNotificationsFeature.Action.Changed -> action.permission
        else -> return Reduced(state)
    }
    if (permission != NotificationPermission.Enabled || state.notificationPermission == permission) return Reduced(state)
    return Reduced(state, Effect.merge(registerForPush(state.server), publishAppBadge(state)))
}

/** Shows Catch Up's count on the icon, once it can be trusted. */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.publishAppBadge(state: State, ledger: AppBadgeLedger? = null): Effect<Action> {
    val counted = ledger ?: state.appBadgeLedger ?: return Effect.none()
    val appIconBadge = dependency(AppIconBadgeClient)
    val persistenceID = state.server.persistenceID
    return Effect.run { appIconBadge.publish(counted, persistenceID) }
}

/**
 * What the icon's badge counts: the sessions Catch Up holds (unread or needing input, never muted),
 * matching its row in the drawer. Null until the list and its attention read have landed on a live
 * connection, so a reconnect, an outage, or the app leaving keeps the last count rather than
 * clearing it.
 */
internal val State.appBadgeLedger: AppBadgeLedger? get() = appBadgePublication.ledger

/** Change-detection key for [appBadgeLedger]. */
internal val State.appBadgePublication: AppBadgePublication
    get() {
        val isReady = isConnected && snapshotLoaded && noticeReadiness == State.NoticeReadiness.settled
        if (!isReady) return AppBadgePublication.notReady
        return AppBadgePublication(
            isReady = true,
            server = server,
            sessions = sessions,
            activeSessionIDs = activeSessionIDs,
            parents = parentSources,
            attentionSessionIDs = attentionSessionIDs,
            sessionReadDates = sessionReadDates,
            manuallyUnreadSessionKeys = manuallyUnreadSessionKeys,
            mutedSessionKeys = mutedSessionKeys,
            viewing = viewing,
        )
    }

/** Home's half of the badge, which `:feature:system` can't see. */
internal data class AppBadgePublication(
    val isReady: Boolean,
    val server: OpenCodeServer?,
    val sessions: List<OpenCodeSession>,
    val activeSessionIDs: Set<String>,
    val parents: State.ParentSources,
    val attentionSessionIDs: Set<String>,
    val sessionReadDates: Map<String, Instant>,
    val manuallyUnreadSessionKeys: Set<String>,
    val mutedSessionKeys: Set<String>,
    val viewing: State.Viewing,
) {
    val ledger: AppBadgeLedger?
        get() {
            if (!isReady || server == null) return null
            val serverID = server.persistenceID
            val status = State.StatusSnapshot(
                serverID = serverID,
                showsActivity = true,
                busy = parents.busy(activeSessionIDs),
                attention = attentionSessionIDs,
                mutedKeys = mutedSessionKeys,
                manuallyUnreadKeys = manuallyUnreadSessionKeys,
                readDates = sessionReadDates,
            )
            val counted = sessions.filter { status.needsCatchUp(it) }
            val prefix = "$serverID|"
            val muted = mutedSessionKeys.mapNotNull { key -> if (key.startsWith(prefix)) key.removePrefix(prefix) else null }
            return AppBadgeLedger(
                serverKey = AppBadgeLedger.serverKey(serverID),
                ref = null,
                sessionIDs = counted.map { it.id }.sorted(),
                mutedSessionIDs = muted.sorted(),
                viewingSessionIDs = viewing.threads.sorted(),
            )
        }

    companion object {
        val notReady = AppBadgePublication(
            isReady = false, server = null, sessions = emptyList(), activeSessionIDs = emptySet(),
            parents = State.ParentSources.empty, attentionSessionIDs = emptySet(),
            sessionReadDates = emptyMap(), manuallyUnreadSessionKeys = emptySet(), mutedSessionKeys = emptySet(),
            viewing = State.Viewing(),
        )
    }
}
