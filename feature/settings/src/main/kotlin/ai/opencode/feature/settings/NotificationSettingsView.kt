package ai.opencode.feature.settings

import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeErrorText
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.notifications.PushLevel
import ai.opencode.feature.system.notifications.PushSetting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource

/** This phone's notifications from the connected server, as Home keeps them and Settings shows them. */
data class NotificationsStatus(
    /** Null until read, and while this build offers no notifications (the row is hidden). */
    val permission: NotificationPermission? = null,
    val setting: PushSetting = PushSetting(),
    /** Whether the server sends notifications at all; null until it answers. */
    val isOffered: Boolean? = null,
    val isChanging: Boolean = false,
    val error: String? = null,
)

/** Settings → Notifications: whether the connected server notifies this phone and about what. */
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
                        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.AndroidNotificationSettingsTapped))
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
    }
}
