package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.strings.localized
import ai.opencode.feature.settings.NotificationsStatus
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.NotificationPermissionClient
import ai.opencode.feature.system.notifications.PushLevel
import ai.opencode.feature.system.notifications.PushSetting
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.server.OpenCodeServer

/**
 * This phone's notifications from one server, as Settings › Notifications shows and changes them.
 * Home supplies the current server and keeps [NotificationsStatus] as `notificationState`.
 */
class HomeNotificationsFeature : Reducer<HomeNotificationsFeature.State, HomeNotificationsFeature.Action> {
    data class State(val server: OpenCodeServer, val status: NotificationsStatus = NotificationsStatus())

    sealed interface Action {
        /** Notifications opened, or the app came back to it. */
        data object SettingsRequested : Action
        data class Loaded(val permission: NotificationPermission, val setting: PushSetting) : Action
        /** Asks the server whether it sends notifications at all. */
        data object OfferRequested : Action
        data class OfferLoaded(val isOffered: Boolean?) : Action
        data class Toggled(val isOn: Boolean) : Action
        data class LevelPicked(val level: PushLevel) : Action
        data class Changed(val permission: NotificationPermission, val setting: PushSetting, val error: String?) : Action
        data object AndroidSettingsTapped : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val status = state.status
        return when (action) {
            Action.SettingsRequested -> {
                val sessionNotifications = dependency(SessionNotificationsClient)
                if (!sessionNotifications.isAvailable) return Reduced(state)
                val notificationPermission = dependency(NotificationPermissionClient)
                val serverKey = state.server.persistenceID
                Reduced(
                    state,
                    Effect.run { send ->
                        send(Action.Loaded(notificationPermission.status(), sessionNotifications.setting(serverKey)))
                    },
                )
            }

            Action.OfferRequested -> {
                val sessionNotifications = dependency(SessionNotificationsClient)
                val server = state.server
                Reduced(state, Effect.run { send -> send(Action.OfferLoaded(sessionNotifications.isOffered(server))) })
            }

            is Action.Loaded -> Reduced(
                state.copy(
                    status = status.copy(
                        permission = action.permission,
                        // The permission prompt brings the app back while a change is still on its way.
                        setting = if (status.isChanging) status.setting else action.setting,
                    ),
                ),
            )

            is Action.OfferLoaded -> Reduced(state.copy(status = status.copy(isOffered = action.isOffered)))

            is Action.Toggled -> change(state, status.setting.copy(isOn = action.isOn))

            is Action.LevelPicked -> change(state, status.setting.copy(level = action.level))

            is Action.Changed -> Reduced(
                state.copy(status = status.copy(permission = action.permission, setting = action.setting, isChanging = false, error = action.error)),
            )

            Action.AndroidSettingsTapped -> {
                val notificationPermission = dependency(NotificationPermissionClient)
                Reduced(state, Effect.run { notificationPermission.openSettings() })
            }
        }
    }

    private fun change(state: State, wanted: PushSetting): Reduced<State, Action> {
        val status = state.status
        if (status.isChanging) return Reduced(state)
        val previous = status.setting
        val server = state.server
        val sessionNotifications = dependency(SessionNotificationsClient)
        val notificationPermission = dependency(NotificationPermissionClient)
        return Reduced(
            state.copy(status = status.copy(setting = wanted, isChanging = true, error = null)),
            Effect.run { send ->
                var permission = notificationPermission.status()
                if (wanted.isOn && permission == NotificationPermission.NotDetermined) permission = notificationPermission.request()
                if (wanted.isOn && permission != NotificationPermission.Enabled) {
                    send(Action.Changed(permission, previous, null))
                    return@run
                }
                if (sessionNotifications.setSetting(server, wanted)) {
                    send(Action.Changed(permission, wanted, null))
                } else {
                    send(Action.Changed(permission, previous, localized(R.string.drawer_notifications_change_error)))
                }
            },
        )
    }
}
