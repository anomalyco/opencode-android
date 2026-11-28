package ai.opencode.feature.settings

import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeErrorText
import ai.opencode.feature.system.notifications.NotificationAlert
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.PushAlerts
import ai.opencode.feature.system.notifications.PushLevel
import ai.opencode.feature.system.notifications.PushSetting
import ai.opencode.feature.system.notifications.SystemNotificationChannels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource

/** This phone's notifications from the connected server, as Home keeps them and Settings shows them. */
data class NotificationsStatus(
    /** Null until read, and while this build offers no notifications (the row is hidden). */
    val permission: NotificationPermission? = null,
    val setting: PushSetting = PushSetting(),
    val alerts: PushAlerts? = null,
    /** Whether the server sends notifications at all; null until it answers. */
    val isOffered: Boolean? = null,
    val isChanging: Boolean = false,
    val error: String? = null,
)

/**
 * Settings → Notifications: whether the connected server notifies this phone and about what, and
 * the way into Android's settings for how each kind shows.
 */
@Composable
fun NotificationSettingsView(store: SettingsStore) {
    val status by store.observe { it.notifications }
    val serverName by store.observe { it.displayName }
    LaunchedEffect(store) {
        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotificationSettingsRequested))
    }
    val blocked = status.permission == NotificationPermission.Disabled
    val unsupported = status.isOffered == false
    val isOn = status.setting.isOn && status.permission == NotificationPermission.Enabled && !unsupported
    val footer = when {
        unsupported -> stringResource(R.string.settings_notifications_unsupported_footer)
        blocked -> stringResource(R.string.settings_notifications_blocked_footer)
        isOn -> null
        else -> stringResource(R.string.settings_notifications_off_footer)
    }
    val alertsHeader = stringResource(R.string.settings_notifications_alerts_header)
    val alertsFooter = stringResource(R.string.settings_notifications_alerts_footer)

    SettingsPageScaffold(title = stringResource(R.string.settings_notifications_title), store = store) {
        settingsSection(key = "server", header = serverName, footer = footer?.let { footerText(it) }) {
            SettingsToggleRow(
                stringResource(R.string.settings_notifications_title),
                checked = isOn,
                onCheckedChange = {
                    store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotificationsToggled(it)))
                },
                enabled = !unsupported && !blocked && !status.isChanging,
            )
            if (isOn) {
                SettingsDivider()
                val options = listOf(
                    SettingsMenuOption(PushLevel.All, stringResource(R.string.settings_notifications_level_all)),
                    SettingsMenuOption(PushLevel.Attention, stringResource(R.string.settings_notifications_level_attention)),
                )
                SettingsMenuRow(
                    title = stringResource(R.string.settings_notifications_notify_about),
                    value = options.first { it.value == status.setting.level }.title,
                    selection = status.setting.level,
                    options = options,
                    onSelect = {
                        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotifyAboutPicked(it)))
                    },
                )
            }
            if (blocked && !unsupported) {
                SettingsDivider()
                SettingsButtonRow(
                    stringResource(R.string.settings_notifications_open_android_settings),
                    onClick = {
                        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.AndroidNotificationSettingsTapped(null)))
                    },
                )
            }
            status.error?.let { error ->
                SettingsDivider()
                SettingsRowFrame {
                    OpenCodeErrorText(error, style = SettingsText.summary)
                }
            }
        }
        val alerts = status.alerts
        if (isOn && alerts != null) {
            settingsSection(key = "alerts", header = alertsHeader, footer = footerText(alertsFooter)) {
                AlertRow(store, R.string.settings_notifications_needs_you, alerts.attention, SystemNotificationChannels.PUSH_ATTENTION)
                SettingsDivider()
                AlertRow(store, R.string.settings_notifications_finished, alerts.finished, SystemNotificationChannels.PUSH_FINISHED)
                SettingsDivider()
                AlertRow(store, R.string.settings_notifications_failed, alerts.failed, SystemNotificationChannels.PUSH_FAILED)
            }
        }
    }
}

@Composable
private fun AlertRow(store: SettingsStore, title: Int, alert: NotificationAlert, channelID: String) {
    val value = when (alert) {
        NotificationAlert.Banner -> stringResource(R.string.settings_notifications_alert_banner)
        NotificationAlert.Sound -> stringResource(R.string.settings_notifications_alert_sound)
        NotificationAlert.Silent -> stringResource(R.string.settings_notifications_alert_silent)
        NotificationAlert.Off -> stringResource(R.string.settings_value_off)
    }
    SettingsChoiceRow(stringResource(title), value) {
        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.AndroidNotificationSettingsTapped(channelID)))
    }
}
