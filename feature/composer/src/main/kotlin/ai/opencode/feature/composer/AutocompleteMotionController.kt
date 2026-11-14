package ai.opencode.feature.composer

import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * Drives [AutocompleteSimulation] per frame (iOS `AutocompleteMotionController`). Publishes
 * [currentFrame] as Compose state and asks for frames through [needsFrames];
 * [AutocompleteMotionDriver] runs the frame loop while it does.
 */
@Stable
class AutocompleteMotionController {
    var simulation: AutocompleteSimulation = AutocompleteSimulation()
        private set

    var currentFrame: AutocompleteSimulation.Frame by mutableStateOf(simulation.frame(350.0, 100.0, 1.0))
        private set

    /** True while the simulation is animating; [AutocompleteMotionDriver] steps it then. */
    var needsFrames: Boolean by mutableStateOf(false)
        private set

    var composerWidth: Double = 350.0
    var composerHeight: Double = 100.0
    var displayScale: Double = 1.0
        private set
    private var lastChooseGeneration = 0

    /** The landing slot of an inline chip in composer-local dp (iOS `chipSlotProvider`). */
    var chipSlotProvider: ((UUID) -> AutocompleteRect?)? = null

    /**
     * The composer's coordinates, the origin of flight rects, so an overlay mounted elsewhere
     * (over the whole surface, above the rows) can place flights in its own space.
     */
    var composerAnchor: (() -> androidx.compose.ui.layout.LayoutCoordinates?)? = null

    /** Called when a chip flight lands, so the text field shows the inline chip (iOS `onChipFlightLanded`). */
    var onChipFlightLanded: (() -> Unit)? = null

    val isOpen: Boolean get() = currentFrame.isOpen
    val isShown: Boolean get() = currentFrame.isShown
    val glassLift: Double get() = currentFrame.glassLift

    /** How far the composer's surface grows above the bar (dp); Reduce Motion uses the resting lift at once. */
    val glassExtent: Double
        get() = if (simulation.params.reducedMotion && currentFrame.isShown) currentFrame.restingLift else currentFrame.glassLift

    fun isChipHidden(id: UUID): Boolean = currentFrame.flights.any { it.chipID == id }

    fun updateDisplayScale(scale: Double) {
        val clamped = max(scale, 1.0)
        if (abs(clamped - displayScale) <= 0.01) return
        displayScale = clamped
        updateCurrentFrame()
    }

    /**
     * Mirrors the reducer's [state] into the simulation: launches a chip flight when a row was
     * chosen, then opens, updates or closes the list. [displayScale] is the density (px per dp).
     */
    fun sync(
        state: ComposerAutocompleteState,
        composerWidth: Double,
        composerHeight: Double = 100.0,
        displayScale: Double? = null,
        reducedMotion: Boolean,
    ) {
        if (composerWidth > 40) this.composerWidth = composerWidth
        if (composerHeight > 20) this.composerHeight = composerHeight
        if (displayScale != null && displayScale > 0) this.displayScale = displayScale
        simulation.params = AutocompleteParameters.current(reducedMotion)

        if (state.chooseGeneration != lastChooseGeneration) {
            lastChooseGeneration = state.chooseGeneration
            val chip = state.lastChosenChip
            val rowID = state.lastChosenRowID
            if (chip != null && rowID != null) {
                val slot = chipSlotProvider?.invoke(chip.id)
                    ?: AutocompleteRect(19.0, 16.0, AutocompleteChipMetrics.width(chip.item.title.length * 8.0), AutocompleteChipMetrics.height)
                simulation.launchFlight(
                    rowID = rowID,
                    chip = chip,
                    composerWidth = this.composerWidth,
                    composerHeight = this.composerHeight,
                    displayScale = this.displayScale,
                    targetRect = slot,
                )
            }
        }

        simulation.sync(
            isOpen = state.isOpen,
            mode = state.activeTrigger?.mode,
            query = state.activeTrigger?.query ?: "",
            key = state.activeTrigger?.key,
            items = state.rows,
            selectedRowID = state.selectedRowID,
            userMoved = state.userMovedSelection,
            composerWidth = this.composerWidth,
        )
        updateCurrentFrame()
        needsFrames = simulation.isAnimating
    }

    fun reset() {
        needsFrames = false
        simulation = AutocompleteSimulation()
        updateCurrentFrame()
    }

    fun scroll(deltaY: Double) {
        val listWidth = max(0.0, composerWidth - 2 * simulation.params.padding)
        simulation.scroll(deltaY, listWidth)
        updateCurrentFrame()
    }

    /** The finger let go: a list held past its end springs home (iOS `endScroll`). */
    fun endScroll() {
        simulation.endScroll()
        if (simulation.isAnimating) needsFrames = true
    }

    /** Steps one frame of [dt] seconds; returns whether more frames are needed (iOS `tick`). */
    fun tick(dt: Double): Boolean {
        // Keep in-flight chip targets synced with the text field's live layout.
        val provider = chipSlotProvider
        if (provider != null) {
            for (flight in simulation.flights.toList()) {
                provider(flight.chipID)?.let { simulation.updateFlightTarget(flight.chipID, it) }
            }
        }
        val previousFlightCount = simulation.flights.size
        simulation.step(dt, composerWidth)
        updateCurrentFrame()
        if (simulation.flights.size < previousFlightCount) onChipFlightLanded?.invoke()
        val animating = simulation.isAnimating
        if (!animating) needsFrames = false
        return animating
    }

    private fun updateCurrentFrame() {
        currentFrame = simulation.frame(composerWidth, composerHeight, displayScale)
    }
}

/** Remembers one [AutocompleteMotionController] (iOS `@State var autocompleteController`). */
@Composable
fun rememberAutocompleteMotionController(): AutocompleteMotionController = remember { AutocompleteMotionController() }

/**
 * Runs [controller]'s frame loop while it animates (iOS's `CADisplayLink` start/stop). Place
 * it once where the controller lives; leaving composition stops it.
 */
@Composable
fun AutocompleteMotionDriver(controller: AutocompleteMotionController) {
    val frameDuration = rememberFrameDurationSeconds()
    val needsFrames = controller.needsFrames
    LaunchedEffect(controller, needsFrames, frameDuration) {
        if (!needsFrames) return@LaunchedEffect
        // iOS clamps each tick to 1/20 s; let the loop pass that through (the simulation clamps too).
        val maxFrames = max(2.0, (1.0 / 20.0) / frameDuration)
        motionFrameLoop(frameDurationSeconds = frameDuration, maxFrames = maxFrames) { tick ->
            if (controller.tick(tick.dt)) MotionFrameAction.Continue else MotionFrameAction.Stop
        }
    }
}
