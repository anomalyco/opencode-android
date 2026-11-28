package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.drawer.notices.DeliveredNotificationsClient
import ai.opencode.feature.system.notifications.NotificationPermission

/*
 * What Home does as notifications change (before `HomeNotificationsFeature` settles the same
 * action), and the cleanup of notifications a lost clear left behind.
 */

internal fun HomeFeature.reduceNotifications(state: State, action: HomeNotificationsFeature.Action): HomeReduced {
    val permission = when (action) {
        is HomeNotificationsFeature.Action.Loaded -> action.permission
        is HomeNotificationsFeature.Action.Changed -> action.permission
        else -> return Reduced(state)
    }
    if (permission != NotificationPermission.Enabled || state.notificationPermission == permission) return Reduced(state)
    return Reduced(state, registerForPush(state.server))
}

/** A send is the moment to ask Android's permission, while it has never been asked. */
@Suppress("UnusedReceiverParameter")
internal fun HomeFeature.askingToNotify(reduced: HomeReduced): HomeReduced {
    if (reduced.state.notificationPermission != NotificationPermission.NotDetermined) return reduced
    val ask = Effect.send<Action>(Action.Notifications(HomeNotificationsFeature.Action.PermissionWanted))
    return Reduced(reduced.state, Effect.merge(reduced.effect, ask))
}

/** The session list and its pending requests are fresh from the server. */
internal val State.isFresh: Boolean
    get() = isConnected && snapshotLoaded && noticeReadiness == State.NoticeReadiness.settled

/** Removes this server's notifications a lost clear left behind: a read session's, or an answered request's. */
internal fun removeStaleNotifications(state: State): Effect<Action> {
    if (!state.isFresh) return Effect.none()
    val status = state.statusSnapshot()
    val read = HashSet<String>()
    for (session in state.sessions) {
        if (session.parentID != null) continue
        if (session.isArchived || !status.isUnread(session)) read.add(session.id)
    }
    val waiting = state.attentionSessionIDs
    val serverKey = state.server.persistenceID
    val delivered = dependency(DeliveredNotificationsClient)
    return Effect.run { delivered.removeStale(serverKey, read, waiting) }
}
