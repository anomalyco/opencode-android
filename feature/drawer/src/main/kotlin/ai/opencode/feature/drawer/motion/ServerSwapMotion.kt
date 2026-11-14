package ai.opencode.feature.drawer.motion

import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.retarget
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.feature.drawer.ServerSwapTarget
import ai.opencode.feature.session.ServerFlowTunables
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Port of iOS `ServerSwapMotion` (Server Flow), "Title Leads": the drawer's name rolls to the
 * server picked and its rows hang off it like a chain, each springing after the one above. Every
 * spring keeps its velocity, so picking again mid-swap turns round from where it is. A plain motion
 * object stepped by [RunServerSwapMotion]'s frame loop. Positions are in travel units: 0 at rest,
 * ±1 a full travel. Only the chain's lag ([ServerFlowTunables.chainLag]) is its own; the rest run
 * on motion roles (`Reduced` under Reduce Motion).
 */
@Stable
class ServerSwapMotion {
    data class NamePose(val id: String, val name: String, val position: Float, val opacity: Float)

    data class RowPose(val position: Float, val opacity: Float)

    /** Every name on screen, the one shown last. */
    var names: List<NamePose> by mutableStateOf(emptyList())
        private set

    /** The title's width in px, springing to the shown name's. */
    var width: Float? by mutableStateOf(null)
        private set

    /** The underline's presence and how far it has filled. */
    var underline: Float by mutableFloatStateOf(0f)
        private set
    var underlineFill: Float by mutableFloatStateOf(0f)
        private set

    /** Bumped each frame the rows move (iOS `onFrame`). */
    var frame: Long by mutableLongStateOf(0L)
        private set

    /** Bumped to start the frame loop (iOS `link.start()`). */
    internal var wake: Int by mutableIntStateOf(0)
        private set

    var isMoving: Boolean = false
        private set

    var shown: ServerSwapTarget? = null
        private set

    private class Entry(
        val id: String,
        var name: String,
        val position: SpringSolver,
        /** The rows' fade: 0 once a server's list has left. */
        val rows: SpringSolver,
        var exiting: Boolean = false,
    ) {
        val opacity: Float get() = opacity(position.value.toFloat())
    }

    private val entries = mutableListOf<Entry>()
    private val chains = HashMap<String, MutableList<SpringSolver>>()
    private val widths = HashMap<String, Float>()
    private var widthSpring: SpringSolver? = null
    private var reduceMotion = false
    private var cue = spring(MotionRole.Dim, 0.0, 0.0, epsilon = 0.002)
    private var fill = spring(MotionRole.Layout, 0.0, 0.0, epsilon = 0.002)

    /**
     * Shows a server. Idempotent: the title and the list both call it with what they're about to
     * draw, whichever comes first.
     */
    fun sync(target: ServerSwapTarget, animated: Boolean, reduceMotion: Boolean = false) {
        this.reduceMotion = reduceMotion
        if (target == shown) return
        val previous = shown
        shown = target
        if (previous == null || !animated) {
            snap(target)
            return
        }
        if (previous.id != target.id) {
            val direction = if (target.order < previous.order) -1.0 else 1.0
            val index = entries.indexOfFirst { it.id == target.id }
            if (index >= 0) {
                // Came back before it left: it turns round from where it is.
                val entry = entries.removeAt(index)
                entry.exiting = false
                entry.name = target.name
                entry.position.retarget(0.0, MotionRole.PanelOpen, reduceMotion = reduceMotion)
                entry.rows.retarget(1.0, MotionRole.Layout, reduceMotion = reduceMotion)
                entries += entry
            } else {
                entries += Entry(
                    id = target.id,
                    name = target.name,
                    position = spring(MotionRole.PanelOpen, from = direction, to = 0.0),
                    rows = spring(MotionRole.Layout, from = 1.0, to = 1.0),
                )
                chains.remove(target.id)
            }
            for (entry in entries) {
                if (entry.id == target.id || entry.exiting) continue
                entry.exiting = true
                entry.position.retarget(-direction, MotionRole.Collapse, reduceMotion = reduceMotion)
                entry.rows.retarget(0.0, MotionRole.Collapse, reduceMotion = reduceMotion)
            }
            widths[target.id]?.let { retargetWidth(it) }
        } else {
            entries.firstOrNull { it.id == target.id }?.name = target.name
        }
        if (target.isConnecting) {
            if (cue.target == 0.0) fill = spring(MotionRole.Layout, 0.0, 0.0, epsilon = 0.002)
            cue.retarget(1.0, MotionRole.Dim, reduceMotion = reduceMotion)
        } else if (cue.target != 0.0) {
            if (previous.isConnecting && previous.id == target.id) {
                // It answered: the underline fills, then fades.
                fill.retarget(1.0, MotionRole.Layout, reduceMotion = reduceMotion)
                cue.retarget(0.0, MotionRole.Dim, reduceMotion = reduceMotion)
            } else {
                cue.retarget(0.0, MotionRole.Collapse, reduceMotion = reduceMotion)
            }
        }
        publish()
        start()
    }

    /** A name's natural width in px, measured by the title. */
    fun measured(id: String, width: Float) {
        if (widths[id] == width) return
        widths[id] = width
        if (id != shown?.id) return
        if (this.width == null || !isMoving) {
            widthSpring = spring(MotionRole.Layout, width.toDouble(), width.toDouble(), epsilon = 0.1)
            this.width = width
        } else {
            retargetWidth(width)
        }
    }

    /**
     * Where a server's first [count] rows are, extending its chain as rows appear (they start where
     * the row above them is).
     */
    fun rows(server: String, count: Int): List<RowPose> {
        val entry = entries.firstOrNull { it.id == server } ?: return List(count) { RowPose(0f, 1f) }
        val chain = chains.getOrPut(server) { mutableListOf() }
        while (chain.size < count) {
            val above = chain.lastOrNull()?.value ?: entry.position.value
            // Stepped toward the row above at the chain's lag ([step]).
            chain += spring(MotionRole.Layout, from = above, to = above)
        }
        val fade = entry.rows.value.toFloat().coerceIn(0f, 1f)
        return chain.take(count).map { RowPose(it.value.toFloat(), opacity(it.value.toFloat()) * fade) }
    }

    /** Where one row is: the [index]th of its server's rows on screen. */
    fun row(server: String, index: Int): RowPose = rows(server, index + 1)[index]

    /** Servers whose rows are still leaving. */
    val leaving: List<String> get() = entries.filter { it.exiting }.map { it.id }

    private fun retargetWidth(width: Float) {
        val spring = widthSpring ?: spring(MotionRole.Layout, width.toDouble(), width.toDouble(), epsilon = 0.1)
            .also { widthSpring = it }
        spring.retarget(width.toDouble(), MotionRole.Layout, reduceMotion = reduceMotion)
    }

    private fun spring(role: MotionRole, from: Double, to: Double, epsilon: Double = 0.001): SpringSolver =
        SpringSolver(role, value = from, target = to, reduceMotion = reduceMotion, positionEpsilon = epsilon, velocityEpsilon = epsilon * 10)

    private fun snap(target: ServerSwapTarget) {
        isMoving = false
        entries.clear()
        entries += Entry(
            id = target.id,
            name = target.name,
            position = spring(MotionRole.PanelOpen, 0.0, 0.0),
            rows = spring(MotionRole.Layout, 1.0, 1.0),
        )
        chains.clear()
        widths[target.id]?.let { widthSpring = spring(MotionRole.Layout, it.toDouble(), it.toDouble(), epsilon = 0.1) }
        val presence = if (target.isConnecting) 1.0 else 0.0
        cue = spring(MotionRole.Dim, presence, presence, epsilon = 0.002)
        fill = spring(MotionRole.Layout, 0.0, 0.0, epsilon = 0.002)
        publish()
        frame++
    }

    private fun start() {
        if (isMoving) return
        isMoving = true
        wake++
    }

    /** Steps every spring; false once all have settled. */
    internal fun step(dt: Double): Boolean {
        var moving = false
        for (entry in entries) {
            if (!entry.position.step(dt).isSettled) moving = true
            if (!entry.rows.step(dt).isSettled) moving = true
        }
        // Each row trails the one above by the chain's lag, critically damped; under Reduce Motion
        // the rows fade together with the name.
        val follow = if (reduceMotion) {
            MotionRole.Reduced.curve().springParameters
        } else {
            SpringSolver.parameters(ServerFlowTunables.chainLag, 0.0)
        }
        for (entry in entries) {
            val chain = chains[entry.id] ?: continue
            var leader = entry.position.value
            for (link in chain) {
                // Each row springs after the one above it; the first after the name.
                if (!link.step(leader, follow.omega, follow.zeta, dt, snapWhenSettled = true).isSettled) moving = true
                leader = link.value
            }
        }
        widthSpring?.let { if (!it.step(dt).isSettled) moving = true }
        if (!cue.step(dt).isSettled) moving = true
        if (!fill.step(dt).isSettled) moving = true
        // A name gone from sight takes its rows with it.
        val gone = entries.filter { it.exiting && it.opacity < 0.01f && it.rows.value < 0.01 }.map { it.id }.toSet()
        if (gone.isNotEmpty()) {
            entries.removeAll { it.id in gone }
            gone.forEach { chains.remove(it) }
        }
        publish()
        frame++
        isMoving = moving
        return moving
    }

    private fun publish() {
        val poses = entries.map { NamePose(it.id, it.name, it.position.value.toFloat(), it.opacity) }
        if (poses != names) names = poses
        widthSpring?.let { if (width != it.value.toFloat()) width = it.value.toFloat() }
        val presence = cue.value.toFloat().coerceIn(0f, 1f)
        if (underline != presence) underline = presence
        val filled = fill.value.toFloat().coerceIn(0f, 1f)
        if (underlineFill != filled) underlineFill = filled
    }

    companion object {
        /** A position this far from rest is fully faded. */
        const val fadeAt = 0.7f

        fun opacity(position: Float): Float = (1 - abs(position) / fadeAt).coerceIn(0f, 1f)

    }
}

/** Steps [motion] on frames while it moves (iOS `MotionDisplayLink(surface: "drawer.server-swap")`). */
@Composable
fun RunServerSwapMotion(motion: ServerSwapMotion) {
    val frameDuration = rememberFrameDurationSeconds()
    LaunchedEffect(motion, motion.wake) {
        if (!motion.isMoving) return@LaunchedEffect
        motionFrameLoop(frameDuration) { tick ->
            if (motion.step(tick.dt)) MotionFrameAction.Continue else MotionFrameAction.Stop
        }
    }
}
