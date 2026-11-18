package ai.opencode.feature.session

import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.tuning.InternalBuild
import ai.opencode.core.motion.transitionBlur
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.feature.composer.OpenCodeBottomSheet
import ai.opencode.feature.transcript.list.TranscriptBorrowedDrag
import ai.opencode.feature.transcript.list.passesDragsToTranscript
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import androidx.annotation.PluralsRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// IOS reads `SessionFeature` state inside the presentation; here every view takes immutable values
// and action lambdas so `SessionView` can wire it (`activeSubagents`, `backgroundShells`,
// `waitingSessionIDs`, `shellGlyphProbe`, and `.subagentTapped` / `.shellOutputTapped`).
// SessionView presents the shell output sheet. Lengths are dp.

// MARK: Copy and sizes

/** The panel's copy and sizing rules. */
object BackgroundWorkSummary {
    /** “1 subagent · 2 shells”, as the capsule reads. */
    fun summary(agents: List<OpenCodeSession>, shells: List<OpenCodeShell>): String = summary(agents.size, shells.size)

    fun summary(agents: Int, shells: Int): String {
        val agentsText = localizedPlural(R.plurals.session_background_work_subagent_count, agents, agents)
        val shellsText = localizedPlural(R.plurals.session_background_work_shell_count, shells, shells)
        if (agents == 0 && shells == 0) return ""
        if (agents == 0) return shellsText
        if (shells == 0) return agentsText
        return localized(R.string.session_background_work_summary, agentsText, shellsText)
    }
}

private const val PILL_GLYPH_GAP = 5f

private val countsStyle: TextStyle
    get() = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")

// MARK: Capsule

/** What the work last had: when it ends, the capsule fades and the sheet closes over it. */
private class LastWork {
    var agents: List<OpenCodeSession> = emptyList()
    var shells: List<OpenCodeShell> = emptyList()

    fun update(agents: List<OpenCodeSession>, shells: List<OpenCodeShell>) {
        if (agents.isEmpty() && shells.isEmpty()) return
        this.agents = agents
        this.shells = shells
    }
}

/**
 * The background-work capsule in the composer's pill row: its glyph and counts. Tapping it opens
 * [BackgroundWorkSheet]. It arrives on `microIn` and leaves on `microOut`.
 *
 * @param transcript the transcript a vertical drag on the capsule scrolls.
 */
@Composable
fun BackgroundWorkCapsule(
    agents: List<OpenCodeSession>,
    shells: List<OpenCodeShell>,
    waitingSessionIDs: Set<String>,
    probe: ShellGlyphProbe?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    transcript: TranscriptBorrowedDrag? = null,
) {
    val reduceMotion = rememberReduceMotion()
    val last = remember { LastWork() }
    last.update(agents, shells)
    val summary = BackgroundWorkSummary.summary(last.agents, last.shells)
    val showWorkLabel = stringResource(R.string.session_background_work_show_label)
    val collapsedDescription = stringResource(R.string.session_background_work_collapsed_description)
    val shape = RoundedCornerShape(BackgroundWorkMotion.panelRadius.dp)
    AnimatedVisibility(
        visible = agents.isNotEmpty() || shells.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(Motion.microIn.animation(reduceMotion = reduceMotion)),
        exit = fadeOut(Motion.microOut.animation(reduceMotion = reduceMotion)),
    ) {
        Box(
            Modifier
                .padding(vertical = BackgroundWorkMotion.capsuleInset.dp)
                .shadow(OpenCodeSurfaces.raisedShadow, shape, clip = false)
                .clip(shape)
                .background(OpenCodeSurfaces.color)
                .openCodeHairline(shape)
                .passesDragsToTranscript(transcript)
                .clickable(onClickLabel = showWorkLabel, onClick = onClick)
                .testTag("background-activity")
                .semantics {
                    contentDescription = summary
                    stateDescription = collapsedDescription
                },
        ) {
            BackgroundWorkCapsuleLabel(last.agents.size, last.shells.size, Modifier.clearAndSetSemantics { }) {
                BackgroundWorkGlyph(last.agents, last.shells, waitingSessionIDs, probe)
            }
        }
    }
}

/**
 * The capsule's glyph and counts, laid out like a composer pill's.
 */
@Composable
fun BackgroundWorkCapsuleLabel(
    agents: Int,
    shells: Int,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
) {
    Row(
        // The glyph's ink as far from the surface's leading edge as from its top and bottom,
        // concentric with the capsule's end.
        modifier
            .heightIn(min = 34.dp)
            .padding(start = BackgroundWorkTunables.glyphLeading.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(PILL_GLYPH_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(glyphWidth(agents, shells).dp).clearAndSetSemantics { }, contentAlignment = Alignment.CenterStart) { leading() }
        BackgroundWorkCounts(agents, shells, Modifier.offset(y = BackgroundWorkTunables.countNudge.dp))
    }
}

/** One 18 dp disc, or the orb and the shell disc side by side. */
fun glyphWidth(agents: Int, shells: Int): Float = if (agents > 0 && shells > 0) 36 + BackgroundWorkTunables.pairGap else 18f

/**
 * “1 subagent · 2 shells”, animated where it stands as the counts change: digits roll, a group,
 * or the dot between groups opens or closes its own width on the resize spring
 * while it fades and blurs, and the rest slides over. Under Reduce Motion the words cross-fade.
 */
@Composable
fun BackgroundWorkCounts(agents: Int, shells: Int, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    // What a group last counted, shown while it closes.
    var lastAgents by remember { mutableIntStateOf(max(1, agents)) }
    var lastShells by remember { mutableIntStateOf(max(1, shells)) }
    if (agents > 0 && agents != lastAgents) lastAgents = agents
    if (shells > 0 && shells != lastShells) lastShells = shells
    if (reduceMotion) {
        Crossfade(BackgroundWorkSummary.summary(agents, shells), modifier, animationSpec = BackgroundWorkMotion.fade(), label = "BackgroundWorkCounts") {
            Text(it, style = countsStyle, maxLines = 1, softWrap = false)
        }
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Opening(agents > 0) { CountGroup(if (agents > 0) agents else lastAgents, R.plurals.session_background_work_subagent_count) }
        Opening(agents > 0 && shells > 0) { Text(" · ", style = countsStyle, maxLines = 1, softWrap = false) }
        Opening(shells > 0) { CountGroup(if (shells > 0) shells else lastShells, R.plurals.session_background_work_shell_count) }
    }
}

@Composable
private fun CountGroup(count: Int, @PluralsRes textRes: Int) {
    // The whole phrase (“2 shells”) comes from the plural resource; the number inside it rolls.
    val text = pluralStringResource(textRes, count, count)
    val number = count.toString()
    val numberAt = text.indexOf(number)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (numberAt < 0) {
            Text(text, style = countsStyle, maxLines = 1, softWrap = false)
            return@Row
        }
        val before = text.substring(0, numberAt)
        val after = text.substring(numberAt + number.length)
        if (before.isNotEmpty()) Text(before, style = countsStyle, maxLines = 1, softWrap = false)
        RollingNumber(count, style = countsStyle)
        if (after.isNotEmpty()) Text(after, style = countsStyle, maxLines = 1, softWrap = false)
    }
}

/**
 * Opens to its own width or closes to none, clipped to it, fading and blurring as it goes on
 * `swap`: quickly out, so what the clip crosses is already faint, and in a little behind the opening.
 */
@Composable
private fun Opening(isOpen: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = isOpen,
        enter = expandHorizontally(BackgroundWorkMotion.resize(), expandFrom = Alignment.Start) +
            fadeIn(Motion.swap.animation(MotionPhase.Enter)),
        exit = shrinkHorizontally(BackgroundWorkMotion.resize(), shrinkTowards = Alignment.Start) +
            fadeOut(Motion.swap.animation(MotionPhase.Exit)),
    ) {
        Box(Modifier.blurringWith(this)) { content() }
    }
}

/** Blurs 3 dp while arriving or leaving, never at rest. */
private fun Modifier.blurringWith(scope: AnimatedVisibilityScope): Modifier = composed {
    val blur by scope.transition.animateFloat(label = "blur") { if (it == EnterExitState.Visible) 0f else 3f }
    transitionBlur(blur.dp)
}

/**
 * The capsule's glyph: its most telling subagent's orb, the shell glyph driven by its shells'
 * combined output, or both side by side, the same disc placed alike.
 */
@Composable
fun BackgroundWorkGlyph(
    agents: List<OpenCodeSession>,
    shells: List<OpenCodeShell>,
    waiting: Set<String>,
    probe: ShellGlyphProbe?,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    // Swapped in place as subagents come and go, like the words beside it. The disc's dot field
    // sits a little high in its frame: dropped, it is concentric with the capsule's end.
    Row(modifier.offset(y = BackgroundWorkTunables.orbGlyphNudgeY.dp), horizontalArrangement = Arrangement.spacedBy(BackgroundWorkTunables.pairGap.dp)) {
        val swap = MotionTransition.swap(reduceMotion)
        AnimatedVisibility(agents.isNotEmpty(), enter = swap.enter, exit = swap.exit) {
            AgentActivityGlyph(
                source = AgentActivitySource.summary(agents, emptyList(), waiting),
            )
        }
        AnimatedVisibility(shells.isNotEmpty(), enter = swap.enter, exit = swap.exit) {
            val ids = shells.map { it.id }
            ShellGlyph(
                shellIDs = ids,
                key = "capsule|" + ids.joinToString(","),
                probe = probe,
            )
        }
    }
}

// MARK: Sheet

/**
 * Running subagents and background shells, one row each, in the app's bottom sheet. A row opens
 * what it shows and the sheet closes; the work ending closes it over the rows it last had.
 *
 * @param waitingSessionIDs subagents waiting on the user: their orbs show Waiting for Input.
 * @param probe where the shells' output is read, for their glyphs.
 * @param onOpenSubagent a subagent row tapped (`.subagentTapped(id)`).
 * @param onOpenShell a shell row tapped (`.shellOutputTapped(id)`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundWorkSheet(
    agents: List<OpenCodeSession>,
    shells: List<OpenCodeShell>,
    waitingSessionIDs: Set<String>,
    probe: ShellGlyphProbe?,
    onDismiss: () -> Unit,
    onOpenSubagent: (String) -> Unit,
    onOpenShell: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val last = remember { LastWork() }
    last.update(agents, shells)
    val hasWork = agents.isNotEmpty() || shells.isNotEmpty()
    DisposableEffect(Unit) {
        onDispose { if (InternalBuild.isDebug) LabMarks.mark("background_work_close") }
    }
    LaunchedEffect(hasWork) {
        if (hasWork) return@LaunchedEffect
        sheetState.hide()
        onDismiss()
    }
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    OpenCodeBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("background-work-panel"),
    ) {
        BackgroundWorkRows(
            agents = last.agents,
            waitingSessionIDs = waitingSessionIDs,
            shells = last.shells,
            probe = probe,
            onOpenSubagent = { id ->
                onOpenSubagent(id)
                close()
            },
            onOpenShell = { id ->
                onOpenShell(id)
                close()
            },
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun BackgroundWorkRows(
    agents: List<OpenCodeSession>,
    waitingSessionIDs: Set<String>,
    shells: List<OpenCodeShell>,
    probe: ShellGlyphProbe?,
    onOpenSubagent: (String) -> Unit,
    onOpenShell: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        if (agents.isNotEmpty()) {
            SectionHeader(stringResource(R.string.session_background_work_subagents_title))
            for (agent in agents) {
                androidx.compose.runtime.key(agent.id) {
                    BackgroundWorkRow(
                        title = agent.title,
                        accessibilityHint = stringResource(R.string.session_background_work_open_subagent_label),
                        onTap = { onOpenSubagent(agent.id) },
                        modifier = Modifier.testTag("background-subagent-${agent.id}"),
                    ) {
                        AgentActivityGlyph(
                            source = AgentActivitySource.Subagent(
                                SubagentActivity(sessionID = agent.id, phase = SubagentActivity.Phase.running, needsInput = agent.id in waitingSessionIDs),
                            ),
                        )
                    }
                }
            }
        }
        if (agents.isNotEmpty() && shells.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = OpenCodeTheme.colors.border)
        }
        if (shells.isNotEmpty()) {
            SectionHeader(stringResource(R.string.session_background_work_shells_title))
            for (shell in shells) {
                androidx.compose.runtime.key(shell.id) {
                    BackgroundWorkRow(
                        title = shell.title,
                        isCommand = true,
                        startedAt = shell.startedAt,
                        accessibilityHint = stringResource(R.string.session_background_work_show_shell_label),
                        onTap = { onOpenShell(shell.id) },
                        modifier = Modifier.testTag("background-shell-${shell.id}"),
                    ) {
                        ShellGlyph(shellIDs = listOf(shell.id), probe = probe)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = OpenCodeTheme.colors.secondaryText,
        modifier = modifier
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp)
            .semantics { heading() },
    )
}

@Composable
private fun BackgroundWorkRow(
    title: String,
    accessibilityHint: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    isCommand: Boolean = false,
    startedAt: Instant? = null,
    leading: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // A ripple inset from the panel's edges answers the press.
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = accessibilityHint, onClick = onTap)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                onClick(label = accessibilityHint) { onTap(); true }
            }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(20.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) { leading() }
        Text(
            title,
            style = if (isCommand) OpenCodeTextStyles.subheadline.copy(fontFamily = OpenCodeFonts.monospace) else OpenCodeTextStyles.body,
            maxLines = 1,
            overflow = if (isCommand) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clearAndSetSemantics { },
        )
        if (startedAt != null) {
            Box(Modifier.clearAndSetSemantics { }) {
                ShellElapsedTime(
                    since = startedAt,
                    style = OpenCodeTextStyles.subheadline.copy(fontFeatureSettings = "tnum"),
                    color = OpenCodeTheme.colors.secondaryText,
                )
            }
        }
        OpenCodeIcon(SFSymbol.ChevronRight, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.tertiaryText)
    }
}

// MARK: Elapsed time

/**
 * Running time as a clock (`0:42`, `12:03`, `1:02:03`), ticking each second.
 *
 * @param since when the shell started.
 * @param until when it ended; a fixed time then.
 * @param isPaused hidden or moving: shows the time once instead of ticking.
 */
@Composable
fun ShellElapsedTime(
    since: Instant,
    modifier: Modifier = Modifier,
    until: Instant? = null,
    isPaused: Boolean = false,
    style: TextStyle = OpenCodeTextStyles.subheadline.copy(fontFeatureSettings = "tnum"),
    color: Color = Color.Unspecified,
) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(since, until, isPaused) {
        now = Instant.now()
        if (until != null || isPaused) return@LaunchedEffect
        while (true) {
            // Ticks on the whole seconds since it started.
            val elapsedMillis = Duration.between(since, Instant.now()).toMillis()
            delay(1000 - Math.floorMod(elapsedMillis, 1000L) + 1)
            now = Instant.now()
        }
    }
    val seconds = max(0L, Duration.between(since, until ?: now).seconds)
    Text(
        ShellElapsedTimeFormat.clock(seconds),
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier.clearAndSetSemantics { contentDescription = ShellElapsedTimeFormat.spoken(seconds) },
    )
}

object ShellElapsedTimeFormat {
    fun clock(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val locale = appFormattingLocale()
        return if (seconds >= 3600) "%d:%02d:%02d".format(locale, h, m, s) else "%d:%02d".format(locale, seconds / 60, s)
    }

    fun spoken(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        val parts = listOfNotNull(
            if (h > 0) localizedPlural(R.plurals.session_shell_elapsed_hours, h.toInt(), h.toInt()) else null,
            if (m > 0) localizedPlural(R.plurals.session_shell_elapsed_minutes, m.toInt(), m.toInt()) else null,
            if (s > 0) localizedPlural(R.plurals.session_shell_elapsed_seconds, s.toInt(), s.toInt()) else null,
        )
        return when (parts.size) {
            0 -> localizedPlural(R.plurals.session_shell_elapsed_seconds, 0, 0)
            1 -> parts[0]
            2 -> localized(R.string.session_list_two, parts[0], parts[1])
            else -> localized(R.string.session_list_three, parts[0], parts[1], parts[2])
        }
    }
}
