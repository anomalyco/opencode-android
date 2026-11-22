package ai.opencode.feature.drawer.overview

import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeAppearanceControl
import ai.opencode.core.ui.OpenCodeDisplay
import ai.opencode.core.ui.OpenCodeDisplayTextStyles
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.OpenCodeTopBarBackButton
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.core.ui.displayCard
import ai.opencode.core.ui.openCodeDisplayBanner
import ai.opencode.core.ui.OpenCodeDisplayCard
import ai.opencode.core.motion.motionPress
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.list.DrawerListActivityRing
import ai.opencode.server.model.OpenCodeProject
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Currency
import kotlinx.coroutines.launch

/**
 * The Overview: what this server is doing, at a glance.
 *
 * The banner carries the totals (sessions, running, spend), the quick actions lead to the places
 * the reader goes next, and the two cards below list the projects and the sessions behind those
 * totals. Everything here is read from Home's state; nothing is fetched.
 */
@Composable
fun OverviewScreen(
    summary: OverviewSummary,
    isLive: Boolean,
    appearance: AppearanceColorScheme,
    onSelectAppearance: (AppearanceColorScheme) -> Unit,
    onClose: () -> Unit,
    onNewSession: () -> Unit,
    onOpenCatchUp: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    projectTile: @Composable (OpenCodeProject, Dp, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(
        modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        OpenCodeTopBar(
            title = {
                Text(
                    stringResource(R.string.drawer_overview_title),
                    style = OpenCodeTextStyles.headline,
                    modifier = Modifier.semantics { heading() },
                )
            },
            navigation = { OpenCodeTopBarBackButton(onClick = onClose) },
            actions = {
                OpenCodeAppearanceControl(selected = appearance, onSelect = onSelectAppearance)
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = OpenCodeDisplay.gutter,
                end = OpenCodeDisplay.gutter,
                bottom = OpenCodeDisplay.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(OpenCodeDisplay.sectionGap),
        ) {
            item(key = "banner") { OverviewBanner(summary = summary, isLive = isLive) }
            item(key = "actions") {
                OverviewQuickActions(
                    onNewSession = onNewSession,
                    onOpenProjects = { scope.launch { listState.animateScrollToItem(OVERVIEW_PROJECTS_ITEM) } },
                    onOpenCatchUp = onOpenCatchUp,
                    onOpenSettings = onOpenSettings,
                )
            }
            item(key = "projects") {
                OverviewProjectsCard(summary = summary, onOpenProject = onOpenProject, projectTile = projectTile)
            }
            item(key = "sessions") {
                OverviewSessionsCard(summary = summary, onOpenSession = onOpenSession)
            }
        }
    }
}

/** The Overview list's projects card, so the quick action can scroll to it. */
private const val OVERVIEW_PROJECTS_ITEM = 2

/**
 * The Overview's banner: the state of this server over the totals that matter. A dark gradient
 * panel, so it reads the same in either appearance.
 */
@Composable
private fun OverviewBanner(summary: OverviewSummary, isLive: Boolean) {
    val label = when {
        summary.running > 0 -> pluralStringResource(R.plurals.drawer_overview_banner_running, summary.running, summary.running)
        summary.attention > 0 -> pluralStringResource(R.plurals.drawer_overview_banner_waiting, summary.attention, summary.attention)
        else -> stringResource(R.string.drawer_overview_banner_quiet)
    }
    val headline = when {
        summary.running > 0 -> pluralStringResource(R.plurals.drawer_overview_headline_running, summary.running, summary.running)
        summary.attention > 0 -> stringResource(R.string.drawer_overview_headline_waiting)
        else -> stringResource(R.string.drawer_overview_headline_quiet)
    }
    val body = when {
        summary.sessions > 0 -> pluralStringResource(R.plurals.drawer_overview_body_sessions, summary.sessions, summary.sessions)
        else -> stringResource(R.string.drawer_overview_body_empty)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .openCodeDisplayBanner()
            .padding(OpenCodeDisplay.cardPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OverviewStatusPill(
                label = label,
                active = summary.running > 0 || summary.attention > 0,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (isLive) stringResource(R.string.drawer_overview_live) else stringResource(R.string.drawer_overview_connecting),
                style = OpenCodeDisplayTextStyles.eyebrow.copy(fontSize = 9.sp, letterSpacing = 1.5.sp),
                color = Color(0xFFA9A8DF),
            )
        }
        Text(
            stringResource(R.string.drawer_overview_kicker),
            style = OpenCodeDisplayTextStyles.eyebrow.copy(fontSize = 10.sp),
            color = Color(0xFFA9A8C9),
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            headline,
            style = OpenCodeDisplayTextStyles.hero,
            color = Color.White,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            body,
            style = OpenCodeDisplayTextStyles.rowDetail,
            color = Color(0xFFB6BDCB),
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            OverviewMetric(
                value = summary.sessions.toString(),
                label = stringResource(R.string.drawer_overview_metric_sessions),
            )
            OverviewMetric(
                value = summary.running.toString(),
                label = stringResource(R.string.drawer_overview_metric_running),
            )
            OverviewMetric(
                value = OverviewFormat.cost(summary.spend),
                label = stringResource(R.string.drawer_overview_metric_spend),
            )
        }
    }
}

/** The banner's status pill: a dot and a short label on a soft fill. */
@Composable
private fun OverviewStatusPill(label: String, active: Boolean) {
    val tint = if (active) Color(0xFF8FB0FF) else Color(0xFF79D3C8)
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(tint))
        Text(
            label,
            style = OpenCodeDisplayTextStyles.eyebrow.copy(fontSize = 9.sp, letterSpacing = 0.5.sp),
            color = Color.White,
        )
    }
}

/** A banner number and its caption. */
@Composable
private fun OverviewMetric(value: String, label: String) {
    Column {
        RollingNumber(
            text = value,
            style = OpenCodeDisplayTextStyles.metricValue,
            color = Color.White,
        )
        Text(
            label,
            style = OpenCodeDisplayTextStyles.bannerLabel,
            color = Color(0xFFAEB9C7),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The quick actions: the places the reader goes next, as big tiles. */
@Composable
private fun OverviewQuickActions(
    onNewSession: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenCatchUp: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OpenCodeDisplay.stackGap),
    ) {
        OverviewAction(
            symbol = SFSymbol.SquareAndPencil,
            label = stringResource(R.string.drawer_overview_action_new_session),
            onClick = onNewSession,
            modifier = Modifier.weight(1f),
        )
        OverviewAction(
            symbol = SFSymbol.Folder,
            label = stringResource(R.string.drawer_overview_action_projects),
            onClick = onOpenProjects,
            modifier = Modifier.weight(1f),
        )
        OverviewAction(
            symbol = SFSymbol.TrayFull,
            label = stringResource(R.string.drawer_overview_action_catch_up),
            onClick = onOpenCatchUp,
            modifier = Modifier.weight(1f),
        )
        OverviewAction(
            symbol = SFSymbol.GearshapeFill,
            label = stringResource(R.string.drawer_overview_action_settings),
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One quick action: an icon tile over its label. */
@Composable
private fun OverviewAction(
    symbol: SFSymbol,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    OpenCodeDisplayCard(
        shape = RoundedCornerShape(OpenCodeDisplay.tileRadius),
        color = colors.displayCard,
        modifier = modifier.motionPress(interaction),
    ) {
        Column(
            Modifier
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(vertical = 14.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(OpenCodeDisplay.tileSize)
                    .clip(RoundedCornerShape(19.dp))
                    .background(colors.brandSoft),
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(symbol, contentDescription = null, size = OpenCodeIconSize.medium, tint = colors.brand)
            }
            Text(
                label,
                style = OpenCodeDisplayTextStyles.rowDetail.copy(fontSize = 11.sp),
                color = colors.secondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The projects card: every project, its sessions and its spend. */
@Composable
private fun OverviewProjectsCard(
    summary: OverviewSummary,
    onOpenProject: (String) -> Unit,
    projectTile: @Composable (OpenCodeProject, Dp, Modifier) -> Unit,
) {
    OverviewCard(title = stringResource(R.string.drawer_overview_projects_title)) {
        if (summary.projects.isEmpty()) {
            OverviewEmpty(stringResource(R.string.drawer_overview_no_projects))
            return@OverviewCard
        }
        summary.projects.forEachIndexed { index, entry ->
            if (index > 0) OverviewDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpenProject(entry.latestSessionID) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                projectTile(entry.project, 44.dp, Modifier)
                Spacer(Modifier.width(OpenCodeDisplay.stackGap))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.project.name,
                        style = OpenCodeDisplayTextStyles.rowTitle,
                        color = OpenCodeTheme.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        pluralStringResource(R.plurals.drawer_overview_project_sessions, entry.sessions, entry.sessions),
                        style = OpenCodeDisplayTextStyles.rowDetail,
                        color = OpenCodeTheme.colors.secondaryText,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (entry.running > 0) {
                    OverviewLiveDot()
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    OverviewFormat.cost(entry.spend),
                    style = OpenCodeDisplayTextStyles.rowTitle.copy(fontSize = 14.sp),
                    color = OpenCodeTheme.colors.secondaryText,
                )
            }
        }
    }
}

/** The sessions card: the most recently touched sessions. */
@Composable
private fun OverviewSessionsCard(
    summary: OverviewSummary,
    onOpenSession: (String) -> Unit,
) {
    OverviewCard(title = stringResource(R.string.drawer_overview_sessions_title)) {
        if (summary.recents.isEmpty()) {
            OverviewEmpty(stringResource(R.string.drawer_overview_no_sessions))
            return@OverviewCard
        }
        summary.recents.forEachIndexed { index, entry ->
            if (index > 0) OverviewDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpenSession(entry.id) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(OpenCodeDisplay.rowTileSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (entry.running) {
                        DrawerListActivityRing(isSpinning = true, opaqueTail = OpenCodeTheme.colors.highContrast)
                    } else {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        entry.attention -> OpenCodeTheme.colors.warning
                                        entry.unread -> OpenCodeTheme.colors.brand
                                        else -> OpenCodeTheme.colors.border
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.width(OpenCodeDisplay.stackGap))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = OpenCodeDisplayTextStyles.rowTitle,
                        color = OpenCodeTheme.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.running || entry.attention || entry.unread) {
                        Text(
                            when {
                                entry.running -> stringResource(R.string.drawer_overview_state_working)
                                entry.attention -> stringResource(R.string.drawer_overview_state_waiting)
                                else -> stringResource(R.string.drawer_overview_state_unread)
                            },
                            style = OpenCodeDisplayTextStyles.rowDetail,
                            color = if (entry.attention) {
                                OpenCodeTheme.colors.warning
                            } else {
                                OpenCodeTheme.colors.secondaryText
                            },
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The live dot beside a running project. */
@Composable
private fun OverviewLiveDot() {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(OpenCodeTheme.colors.brand),
    )
}

/** A titled card: the Overview's surface, with a hairline between its rows. */
@Composable
private fun OverviewCard(title: String, content: @Composable () -> Unit) {
    val colors = OpenCodeTheme.colors
    OpenCodeDisplayCard(
        shape = RoundedCornerShape(OpenCodeDisplay.cardRadius),
        color = colors.displayCard,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                title,
                style = OpenCodeDisplayTextStyles.sectionTitle,
                color = colors.text,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp),
            )
            content()
        }
    }
}

/** The hairline between a card's rows. */
@Composable
private fun OverviewDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(OpenCodeTheme.colors.rowDivider),
    )
}

/** A card's empty line. */
@Composable
private fun OverviewEmpty(text: String) {
    Text(
        text,
        style = OpenCodeDisplayTextStyles.rowDetail,
        color = OpenCodeTheme.colors.tertiaryText,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
    )
}

/** How the Overview writes money. */
object OverviewFormat {
    /** The spend in USD, as the session sheet writes it. */
    fun cost(value: Double): String =
        NumberFormat.getCurrencyInstance(appFormattingLocale())
            .apply { currency = Currency.getInstance("USD") }
            .format(value)
}
