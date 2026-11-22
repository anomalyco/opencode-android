package ai.opencode.feature.drawer.overview

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.ThemeSharedKeys
import ai.opencode.core.ui.rememberHighContrast
import ai.opencode.feature.drawer.HomeConnectionFeature
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.list.DrawerListProjectTile
import ai.opencode.feature.settings.HomeSettingsFeature
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * The Overview, over the session: the totals, the projects and the recent sessions, and the
 * appearance control that switches between light and dark without leaving the screen.
 *
 * Every action it sends is Home's, so the reducer owns the navigation it asks for.
 */
@Composable
fun OverviewHost(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    modifier: Modifier = Modifier,
) {
    val summary by store.observe { it.overviewSummary() }
    val isLive by store.observe {
        it.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.connected
    }
    val appearance by remember { shared(ThemeSharedKeys.appearanceColorScheme).flow }.collectAsState()
    val reducedMotion = rememberReduceMotion()
    val highContrast = rememberHighContrast()
    BackHandler { store.send(HomeFeature.Action.OverviewClosed) }
    OverviewScreen(
        summary = summary,
        isLive = isLive,
        appearance = appearance,
        onSelectAppearance = {
            store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.AppearanceColorSchemeChanged(it)))
        },
        onClose = { store.send(HomeFeature.Action.OverviewClosed) },
        onNewSession = { store.send(HomeFeature.Action.OverviewNewSessionTapped) },
        onOpenCatchUp = { store.send(HomeFeature.Action.OverviewCatchUpTapped) },
        onOpenSettings = { store.send(HomeFeature.Action.OverviewSettingsTapped) },
        onOpenProject = { store.send(HomeFeature.Action.OverviewSessionTapped(it)) },
        onOpenSession = { store.send(HomeFeature.Action.OverviewSessionTapped(it)) },
        projectTile = { project, side, tileModifier ->
            DrawerListProjectTile(
                project = project,
                server = store.state.server,
                muted = false,
                side = side,
                increasedContrast = highContrast,
                reducedMotion = reducedMotion,
                modifier = tileModifier,
            )
        },
        modifier = modifier.fillMaxSize(),
    )
}
