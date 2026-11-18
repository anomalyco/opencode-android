package ai.opencode.feature.composer

import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionPose
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.modelpicker.OnePickerChipRoll
import ai.opencode.feature.composer.plusmenu.findActivity
import ai.opencode.server.model.AgentDisplayName
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import android.os.SystemClock
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max

// Agent Selection (`SharedKeys.agentSelection`): choose the primary agent (Build, Plan,
// or a custom one) from the composer.

object AgentPickerSharedKeys {
    /** The agent new sessions start with (an agent ID); empty is the server's default. Declared in `:core` (Settings writes it). */
    val defaultAgentID = ai.opencode.core.sharing.SharedKeys.defaultAgentID

    /** The agent picked in each session (session ID → agent ID). Declared in `:server`. */
    val sessionAgents = ai.opencode.server.ServerSharedKeys.sessionAgents

    /** The primary agents last listed, for Settings' Default Agent. Declared in `:server`. */
    val knownPrimaryAgents = ai.opencode.server.ServerSharedKeys.knownPrimaryAgents
}

/**
 * The agent chip beside the model chip, only while Agent Selection is on ([isEnabled]). [isFolded]
 * hides it as the composer folds.
 */
@Composable
fun ComposerAgentChip(
    isEnabled: Boolean,
    name: String,
    currentID: String?,
    agents: List<OpenCodeAutocompleteAgent>,
    onOpen: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isFolded: Boolean = false,
    targetDrop: Dp = 0.dp,
    menuBottomInWindow: () -> Float = { Float.NaN },
) {
    if (!isEnabled) return
    AgentChip(
        name = name,
        currentID = currentID,
        agents = agents,
        onOpen = onOpen,
        onPick = onPick,
        modifier = if (isFolded) modifier.alpha(0f).clearAndSetSemantics {} else modifier,
        enabled = !isFolded,
        targetDrop = targetDrop,
        menuBottomInWindow = menuBottomInWindow,
    )
}

/**
 * The current agent's name, opening a menu of the server's primary agents. [targetDrop]: how far
 * its target sits below its ink (the composer's `pickerTargetDrop`). [menuBottomInWindow]: where the
 * menu's bottom edge goes, such as the composer's top; NaN puts it just above the chip.
 */
@Composable
fun AgentChip(
    name: String,
    currentID: String?,
    agents: List<OpenCodeAutocompleteAgent>,
    onOpen: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    targetDrop: Dp = 0.dp,
    menuBottomInWindow: () -> Float = { Float.NaN },
) {
    var open by remember { mutableStateOf(false) }
    var closedAt by remember { mutableLongStateOf(0L) }
    // Mounted as the composer expands: the list is fresh before it opens.
    LaunchedEffect(Unit) { onOpen() }
    val chooseAgentDescription = stringResource(R.string.composer_agent_choose_description)
    val secondary = OpenCodeTheme.colors.secondaryText
    val reduceMotion = rememberReduceMotion()
    AgentMenuBackHandler(enabled = open) { open = false }
    // The whole menu moves its target down and its ink back up, so the menu drops from the lowered
    // target too.
    Box(modifier.offset(y = targetDrop)) {
        Row(
            Modifier
                .clickable(enabled = enabled, role = Role.Button) {
                    // A tap on the chip while the menu is open has already closed it as an outside tap.
                    if (SystemClock.uptimeMillis() - closedAt > 500) open = !open
                }
                .heightIn(min = 48.dp)
                .clearAndSetSemantics {
                    contentDescription = chooseAgentDescription
                    stateDescription = name
                    role = Role.DropdownList
                }
                .testTag("composer-agent")
                .offset(y = -targetDrop),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium)
            OnePickerChipRoll(name, reduceMotion, label = "agentName") { rolled ->
                Text(rolled, style = style, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
            }
            OpenCodeIcon(SFSymbol.ChevronDown, null, size = 11.dp, tint = secondary)
        }
        AgentMenu(
            open = open,
            agents = agents,
            currentID = currentID,
            anchorLift = targetDrop,
            menuBottomInWindow = menuBottomInWindow,
            onDismiss = {
                open = false
                closedAt = SystemClock.uptimeMillis()
            },
            onPick = { id ->
                open = false
                onPick(id)
            },
        )
    }
}

private val AgentMenuInset = 4.dp
private val AgentMenuRowPadding = 12.dp
private val AgentMenuGap = 6.dp
private val AgentMenuShadowRoom = 8.dp
private val AgentMenuMaxHeight = 320.dp
private val AgentMenuHeaderRoom = 72.dp

/**
 * The agents in a small floating menu that grows upward from the chip, its names lined up with the
 * chip's. [anchorLift]: how far the chip's ink sits above its layout box.
 */
@Composable
private fun AgentMenu(
    open: Boolean,
    agents: List<OpenCodeAutocompleteAgent>,
    currentID: String?,
    anchorLift: Dp,
    menuBottomInWindow: () -> Float,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = open
    var anchorTop by remember { mutableFloatStateOf(0f) }
    Box(Modifier.onGloballyPositioned { anchorTop = it.positionInWindow().y })
    if (!visible.currentState && !visible.targetState) return

    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduceMotion = rememberReduceMotion()
    val statusBarTop = WindowInsets.statusBars.getTop(density)
    var bottom = menuBottomInWindow()
    if (!bottom.isFinite()) {
        bottom = anchorTop - with(density) { anchorLift.toPx() }
    }
    val maxHeight = with(density) {
        val room = (bottom - statusBarTop).toDp() - AgentMenuHeaderRoom - AgentMenuGap
        room.coerceIn(OpenCodeMinTapTarget * 2, AgentMenuMaxHeight)
    }
    val positionProvider = remember(density, bottom) {
        with(density) {
            AboveChipPositionProvider(
                bottom = bottom.toInt(),
                gap = (AgentMenuGap - AgentMenuShadowRoom).roundToPx(),
                inset = (AgentMenuShadowRoom + AgentMenuInset + AgentMenuRowPadding).roundToPx(),
            )
        }
    }
    val origin = TransformOrigin(if (isRtl) 1f else 0f, 1f)
    val enter = if (reduceMotion) {
        fadeIn(MotionRole.Reduced.animation(reduceMotion = true))
    } else {
        fadeIn(MotionRole.MicroIn.animation(MotionPhase.Enter)) +
            scaleIn(MotionRole.MicroIn.animation(MotionPhase.Enter), initialScale = MotionPose.microIn.scale, transformOrigin = origin)
    }
    val exit = if (reduceMotion) {
        fadeOut(MotionRole.Reduced.animation(reduceMotion = true))
    } else {
        fadeOut(MotionRole.MicroOut.animation(MotionPhase.Exit)) +
            scaleOut(MotionRole.MicroOut.animation(MotionPhase.Exit), targetScale = MotionPose.swap.scale, transformOrigin = origin)
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        AnimatedVisibility(visibleState = visible, enter = enter, exit = exit) {
            Surface(
                modifier = Modifier.padding(AgentMenuShadowRoom),
                shape = OpenCodeSurfaces.popoverShape,
                color = OpenCodeSurfaces.color,
                border = OpenCodeSurfaces.border,
                shadowElevation = OpenCodeSurfaces.popoverShadow,
            ) {
                val currentIndex = agents.indexOfFirst { it.id == currentID }
                val rowPx = with(density) { OpenCodeMinTapTarget.roundToPx() }
                val viewportPx = with(density) { maxHeight.roundToPx() }
                val scroll = rememberScrollState(initial = maxOf(0, (currentIndex + 1) * rowPx - viewportPx + rowPx / 2))
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(min = 140.dp, max = 260.dp)
                        .heightIn(max = maxHeight)
                        .verticalScroll(scroll)
                        .padding(AgentMenuInset),
                ) {
                    if (agents.isEmpty()) {
                        AgentMenuRow(stringResource(R.string.composer_agent_loading), isOn = false, enabled = false) {}
                    }
                    for (agent in agents) {
                        AgentMenuRow(agent.displayName, isOn = agent.id == currentID, enabled = true) {
                            onPick(agent.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentMenuRow(
    title: String,
    isOn: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = OpenCodeTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(OpenCodeRadius.sm))
            .selectable(selected = isOn, enabled = enabled, role = Role.Button, onClick = onClick)
            .openCodeTapTarget()
            .fillMaxWidth()
            .padding(start = AgentMenuRowPadding, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = OpenCodeTextStyles.subheadline,
            color = if (enabled) colors.text else colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(24.dp))
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (isOn) OpenCodeIcon(SFSymbol.Checkmark, null, size = 14.dp, tint = colors.text)
        }
    }
}

/** Puts the menu's bottom edge [gap] above [bottom] (window px) and its text [inset] before the chip's. */
private class AboveChipPositionProvider(private val bottom: Int, private val gap: Int, private val inset: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val start = if (layoutDirection == LayoutDirection.Rtl) {
            anchorBounds.right + inset - popupContentSize.width
        } else {
            anchorBounds.left - inset
        }
        val x = start.coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        val y = bottom - gap - popupContentSize.height
        return IntOffset(x, max(0, y))
    }
}

// The menu's popup doesn't take focus, so back goes to the activity; overlay priority runs it before the keyboard's.
@Composable
private fun AgentMenuBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val activity = LocalContext.current.findActivity() ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val callback = OnBackInvokedCallback { currentOnBack() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

/**
 * Settings › Composer › Default Agent, the agent new sessions start with, only while Agent
 * Selection is on. Reads [AgentPickerSharedKeys.defaultAgentID] and
 * [AgentPickerSharedKeys.knownPrimaryAgents]; [onChange] hands the choice to the settings reducer,
 * which writes the key.
 *
 * Note: Settings sits beside (not above) this module, so the settings area needs these keys and
 * this section moved to a module it can see (`:server`); see the composer parity row.
 */
@Composable
fun DefaultAgentSection(
    isEnabled: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isEnabled) return
    val selectionShared = remember { shared(AgentPickerSharedKeys.defaultAgentID) }
    val agentsShared = remember { shared(AgentPickerSharedKeys.knownPrimaryAgents) }
    val selection by selectionShared.flow.collectAsState()
    val agents by agentsShared.flow.collectAsState()
    var open by remember { mutableStateOf(false) }
    val serverDefault = stringResource(R.string.composer_agent_server_default)
    val defaultAgentTitle = stringResource(R.string.composer_agent_default_title)

    data class Option(val id: String, val title: String)
    val options = buildList {
        add(Option("", serverDefault))
        agents.forEach { add(Option(it.id, it.displayName)) }
        // A saved agent this server doesn't list stays choosable.
        if (selection.isNotEmpty() && agents.none { it.id == selection }) add(Option(selection, AgentDisplayName.titleCased(selection)))
    }
    val current = options.firstOrNull { it.id == selection }?.title ?: serverDefault

    Column(modifier.fillMaxWidth()) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.DropdownList) { open = true }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp)
                    .clearAndSetSemantics {
                        contentDescription = defaultAgentTitle
                        stateDescription = current
                        role = Role.DropdownList
                    }
                    .testTag("settings-default-agent"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(defaultAgentTitle, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                Text(current, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
                OpenCodeIcon(SFSymbol.ChevronUpChevronDown, null, size = 14.dp, tint = OpenCodeTheme.colors.secondaryText, modifier = Modifier.padding(start = 4.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (option in options) {
                    val isOn = option.id == selection
                    DropdownMenuItem(
                        text = { Text(option.title) },
                        leadingIcon = { Box(Modifier.width(24.dp)) { if (isOn) OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small) } },
                        modifier = Modifier.semantics { selected = isOn },
                        onClick = {
                            open = false
                            onChange(option.id)
                        },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.composer_agent_default_footer),
            style = OpenCodeTextStyles.footnote,
            color = OpenCodeTheme.colors.secondaryText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
