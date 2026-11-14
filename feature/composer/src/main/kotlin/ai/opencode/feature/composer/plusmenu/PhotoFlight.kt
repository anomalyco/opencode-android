package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.motion.Motion
import androidx.compose.animation.core.FiniteAnimationSpec
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photos picked in the New Plus Menu fly into the composer: each leaves its tile, shrinks and
 * rounds on Kit's spring, and lands exactly on its attachment's thumbnail. Purely visual; a flight
 * only hides its attachment until it lands. iOS `PhotoFlightTunables` (`composer.flight.*`).
 */
object PhotoFlightTunables {
    /** The flight's spring (duration and bounce; bounce 0 never overshoots). `composer.flight`. */
    val spring = MotionSpring(duration = 0.34, bounce = 0.0)

    /** Between one photo leaving and the next. `composer.flight.stagger`. */
    const val stagger = 0.05

    /** The rise leads the sideways travel by this much, so the path bows into a gentle arc (1 is a straight line). `composer.flight.arc`. */
    const val arc = 1.45

    /** The composer grows (and its row reflows) on this; a flight lands only once its slot has been still this long. `composer.flight.rowSettle`. */
    const val rowSettle = 0.3

    /** The composer growing to make room for the photos, and its row reflowing, on `Motion.layout`. */
    fun <T> rowAnimation(): FiniteAnimationSpec<T> = Motion.layout.animation()

    /** Progress (0–1) along the flight at which the corner × badge starts appearing. `composer.flight.closeStart`. */
    const val closeStart = 0.2f

    /** Progress span (0–1) over which the corner × badge scales and fades to 1. `composer.flight.closeSpan`. */
    const val closeSpan = 0.55f

    /** Initial scale of the corner × badge when it begins appearing (and end scale on leave). `composer.flight.closeScale`. */
    const val closeScale = 0.45f
}

/**
 * The flights in the air, on one frame loop (iOS `PhotoFlights`). A plain object: it moves the
 * drawn flights each frame and changes observable state only when a flight starts or lands.
 * Geometry is in screen pixels, so the sheet (its own window), the composer and the
 * [PhotoFlightCanvas] agree. The canvas sets [density].
 */
class PhotoFlights {
    /** Attachments whose flight hasn't landed: drawn by the flight, not the composer. */
    var hiddenAttachments: Set<UUID> by mutableStateOf(emptySet())
        private set

    /** Incoming attachments whose 76×76 slot is reserved in the composer row on frame 0 while the importer decodes them. */
    var reservedAttachments: List<UUID> by mutableStateOf(emptyList())
        private set

    /**
     * A row of reserved slots that just mounted stays collapsed until the first frame after it
     * commits ([revealHeldRow]). Mounting it can stall the main thread, and a spring that starts
     * inside the stall is drawn most of the way along in its first frame: a leap, not a glide.
     */
    var holdsReservedRow: Boolean by mutableStateOf(false)
        private set

    /** Bumped by every reveal and release, so only the latest reveal opens the row. */
    private var revealGeneration = 0

    /** Tiles whose photo is in the air. */
    var hiddenPhotos: Set<String> by mutableStateOf(emptySet())
        private set

    /** Something is in the air. */
    var isFlying: Boolean by mutableStateOf(false)
        private set

    /** Where a photo can leave from: its tile on screen, its corners (px), a highlight ring's opacity, and what it shows. */
    data class Source(
        val frame: Rect,
        val radius: Float,
        val ring: Float = 0f,
        val image: ImageBitmap? = null,
        val isSheet: Boolean = false,
        /** One-off sources (like a captured camera photo) are retired once their flight lands or is cancelled. */
        val isEphemeral: Boolean = false,
    )

    /** One pick to fly: the library photo (or ephemeral source) and the attachment it becomes. */
    data class Pick(val photo: String, val attachment: UUID)

    /** Pixels per dp; the canvas keeps it current. */
    var density: Float = 1f

    private val sources = HashMap<String, Source>()
    var sheetViewport: Rect = Rect.Zero

    /** Each thumbnail's laid-out frame on screen, by attachment. */
    private val slots = HashMap<UUID, Rect>()

    /** Each reserved attachment's owning import batch. */
    private val reservationBatches = HashMap<UUID, UUID>()

    /** The attachments in the composer, in order. */
    private var present: List<UUID> = emptyList()
    private var rowChangedAt = 0.0

    /**
     * How far the message field is drawn from its laid-out place. Compose coordinates already
     * include layer transforms, so the default is none; the session may supply one for an
     * IME-driven offset.
     */
    var anchorOffset: () -> Offset = { Offset.Zero }

    var flights: List<Flight> = emptyList()
        private set

    /** Bumped every drawn frame; the canvas reads it in its draw phase only. */
    internal var frame by mutableLongStateOf(0L)
        private set

    /** The frame loop runs while true (the canvas owns the loop). */
    internal var awake by mutableStateOf(false)
        private set

    /** The canvas is mounted: there's somewhere to draw flights. */
    internal var canvasBounds: Rect? = null

    private var importing = false

    /** The composer's frame on screen, laid out in the same pass as the thumbnails. */
    private var composerFrame: Rect = Rect.Zero

    /** Where the composer will rest while the keyboard comes back. */
    var restingComposer: Rect? = null
        private set
    private var restingSince = 0.0

    val slotSidePx: Float get() = SLOT_SIDE * density
    val slotRadiusPx: Float get() = SLOT_RADIUS * density
    private val slotSpacingPx: Float get() = SLOT_SPACING * density

    fun hidesAttachment(id: UUID): Boolean = id in hiddenAttachments

    fun hidesPhoto(id: String): Boolean = id in hiddenPhotos

    // Reports from the views

    fun source(photoId: String, source: Source?) {
        if (source == null) sources.remove(photoId) else sources[photoId] = source
    }

    fun source(photoId: String): Source? = sources[photoId]

    fun composer(frame: Rect) {
        composerFrame = frame
    }

    fun slot(id: UUID, frame: Rect) {
        if (slots[id] == frame) return
        if (slots[id] != null) rowChangedAt = now()
        slots[id] = frame
    }

    fun reserve(ids: List<UUID>, batch: UUID = UUID.randomUUID(), holdingRow: Boolean = false) {
        val existing = present.toSet() + reservedAttachments
        val added = ids.filter { it !in existing }
        for (id in ids) reservationBatches[id] = batch
        if (added.isEmpty()) return
        reservedAttachments = reservedAttachments + added
        if (holdingRow) holdsReservedRow = true
        rowChangedAt = now()
    }

    /**
     * Opens a held row at the next frame, once the frame that mounted it has drawn (iOS
     * `revealHeldRow(_:)` on a display link). Call it from the row's effect; the row animates the
     * opening itself. A later reveal or a release supersedes this one.
     */
    suspend fun revealHeldRow() {
        if (!holdsReservedRow) return
        val generation = ++revealGeneration
        withFrameNanos { }
        if (generation == revealGeneration) holdsReservedRow = false
    }

    private fun releaseHeldRow() {
        revealGeneration += 1
        holdsReservedRow = false
    }

    /** Releases a single reserved slot (one source in a batch failed to load) and softens its flight away. */
    fun dropReservation(id: UUID) {
        reservedAttachments = reservedAttachments.filter { it != id }
        reservationBatches.remove(id)
        if (id !in present) slots.remove(id)
        for (flight in flights) if (flight.state == Flight.State.Flying && flight.id == id) leave(flight)
        wake()
    }

    /** The composer's attachments changed: one added, or one removed (a flight whose attachment went softens away; the others retarget). */
    fun attachmentsChanged(ids: List<UUID>) {
        if (ids == present) return
        val previousLayout = present + reservedAttachments.filter { it !in present }
        present = ids
        val kept = ids.toSet()
        reservedAttachments = reservedAttachments.filter { it !in kept }
        for (id in kept) reservationBatches.remove(id)
        val currentLayout = present + reservedAttachments
        if (currentLayout != previousLayout) rowChangedAt = now()
        val retained = kept + reservedAttachments
        slots.keys.retainAll(retained)
        for (flight in flights) {
            if (flight.state != Flight.State.Flying) continue
            if (flight.id in kept) {
                flight.arrived = true
            } else if (flight.arrived || !importing) {
                leave(flight)
            }
        }
        wake()
    }

    /** The import ended: a flight whose attachment never came softens away. */
    fun importFinished(batch: UUID? = null) {
        val kept = present.toSet()
        if (batch != null) {
            reservedAttachments = reservedAttachments.filter { reservationBatches[it] != batch }
            reservationBatches.entries.removeAll { it.value == batch }
            for (flight in flights) {
                if (flight.state == Flight.State.Flying && flight.batchId == batch && flight.id !in kept) leave(flight)
            }
            importing = reservationBatches.isNotEmpty() || flights.any { it.state == Flight.State.Flying && !it.arrived }
        } else {
            importing = false
            reservedAttachments = emptyList()
            reservationBatches.clear()
            for (flight in flights) if (flight.state == Flight.State.Flying && flight.id !in kept) leave(flight)
        }
        val retained = kept + reservedAttachments
        slots.keys.retainAll(retained)
        wake()
    }

    // Flights

    /** Sends [picks] into the composer, in order, from their tiles. Picks without a tile on screen just arrive with the composer's own motion. */
    fun launch(picks: List<Pick>, batch: UUID = UUID.randomUUID(), restingComposer: Rect? = null) {
        val bounds = canvasBounds
        // Nowhere to draw them: they arrive with the composer's own motion.
        if (bounds == null) {
            for (pick in picks) if (sources[pick.photo]?.isEphemeral == true) sources.remove(pick.photo)
            return
        }
        prepare(picks, batch, bounds, restingComposer)
        if (flights.isEmpty()) return
        wake()
    }

    /** Admit a batch before mounting its presentation. Attachment membership, rather than a layout callback, owns whether an import has arrived. */
    fun prepare(picks: List<Pick>, batch: UUID = UUID.randomUUID(), windowBounds: Rect, restingComposer: Rect? = null) {
        for (flight in flights) if (flight.state == Flight.State.Flying && !flight.arrived) leave(flight)
        val now = now()
        importing = true
        var index = 0
        val added = ArrayList<Flight>()
        for (pick in picks) {
            val source = sources[pick.photo]
            if (source == null || source.frame.width <= 0f || !windowBounds.containsRect(source.frame) ||
                (source.isSheet && !sheetViewport.containsRect(source.frame))
            ) {
                if (source?.isEphemeral == true) sources.remove(pick.photo)
                continue
            }
            added.add(Flight(id = pick.attachment, batchId = batch, photo = pick.photo, source = source, start = now + index * PhotoFlightTunables.stagger, density = density))
            index += 1
        }
        if (index == 0) return
        flights = flights + added
        hiddenAttachments = hiddenAttachments + added.map { it.id }
        hiddenPhotos = hiddenPhotos + added.map { it.photo }
        isFlying = true
        rowChangedAt = now
        if (restingComposer != null) {
            this.restingComposer = restingComposer
            restingSince = now
        }
    }

    /** Gone with the screen (a session switch): each softens away where it is. */
    fun cancelAll() {
        restingComposer = null
        reservedAttachments = emptyList()
        reservationBatches.clear()
        releaseHeldRow()
        sources.entries.removeAll { it.value.isEphemeral }
        for (flight in flights) if (flight.state == Flight.State.Flying) leave(flight)
        importing = false
        wake()
    }

    fun clearRestingComposer() {
        restingComposer = null
    }

    /** No presentation survives an inactive or unmounted session. */
    fun reset() {
        flights = emptyList()
        sources.entries.removeAll { it.value.isEphemeral }
        restingComposer = null
        hiddenAttachments = emptySet()
        reservedAttachments = emptyList()
        reservationBatches.clear()
        releaseHeldRow()
        hiddenPhotos = emptySet()
        importing = false
        sleep()
    }

    private fun leave(flight: Flight) {
        flight.state = Flight.State.Leaving
        hiddenAttachments = hiddenAttachments - flight.id
        reservedAttachments = reservedAttachments.filter { it != flight.id }
        reservationBatches.remove(flight.id)
    }

    /**
     * Horizontal overflow retires immediately. Vertical exclusion can be the composer growing
     * around a newly laid-out row: keep its measured target for a bounded grace period, but
     * containment still gates landing in [tick].
     */
    fun resolvedSlot(flight: Flight, now: Double = now()): Rect? {
        val slot = slots[flight.id] ?: return null
        val bounds = composerFrame.inflate(density)
        val hasComposer = composerFrame != Rect.Zero
        val horizontallyClipped = hasComposer && (slot.left < bounds.left || slot.right > bounds.right)
        val contained = hasComposer && bounds.containsRect(slot)
        // The composer patience, measured from this flight's start, not rowChangedAt:
        // repeated layout reports must never extend it forever.
        if (horizontallyClipped || (!contained && now - flight.start >= RESTING_PATIENCE)) {
            leave(flight)
            return null
        }
        return slot
    }

    private fun wake() {
        if (flights.isEmpty() || awake) return
        awake = true
    }

    private fun sleep() {
        awake = false
        isFlying = false
        restingComposer = null
        frame += 1
    }

    /** One frame of the loop; returns whether to keep running. iOS `tick(_:)`. */
    internal fun tick(rawDt: Double): Boolean {
        val dt = min(rawDt, 1.0 / 40)
        val params = SpringSolver.parameters(PhotoFlightTunables.spring.duration, PhotoFlightTunables.spring.bounce)
        val omega = params.omega
        val zeta = params.zeta
        // A photo whose attachment never arrives softens away on `microOut`.
        val exitParams = Motion.microOut.curve().springParameters
        val lead = PhotoFlightTunables.arc
        val geometryTime = now()
        val (shift, offset) = aim(geometryTime)
        val settled = geometryTime - rowChangedAt >= PhotoFlightTunables.rowSettle
        var unresolved = 0
        val epsilon = 0.5f * density

        for (flight in flights) {
            when (flight.state) {
                Flight.State.Flying -> {
                    val resolved = resolvedSlot(flight, geometryTime)
                    if (flight.state != Flight.State.Flying) continue
                    val contained = composerFrame != Rect.Zero && resolved != null && composerFrame.inflate(density).containsRect(resolved)
                    var model = resolved ?: predictedSlot(unresolved)
                    // The composer grows up to take the row from below: until it's still, a
                    // slot is no lower than where the row rests, so no flight dips to meet a
                    // row on its way up.
                    if (!settled && composerFrame != Rect.Zero) {
                        val top = min(model.top, composerFrame.bottom - PhotoFlightLayout.FIRST_SLOT_ABOVE_FOOT * density - slotSidePx)
                        model = Rect(Offset(model.left, top), model.size)
                    }
                    if (resolved == null) unresolved += 1
                    flight.modelTarget?.let { previous ->
                        if (abs(previous.left - model.left) + abs(previous.top - model.top) + abs(previous.width - model.width) > epsilon) {
                            flight.retargets += 1
                        }
                    }
                    flight.modelTarget = model
                    val target = model.translate(shift.x + offset.x, shift.y + offset.y)
                    flight.target = target
                    flight.arrived = flight.arrived || flight.id in present
                    // Waiting its turn, it holds on its tile.
                    if (geometryTime < flight.start && !flight.departed) continue
                    flight.departed = true
                    flight.x.step(target.center.x.toDouble(), omega, zeta, dt)
                    flight.y.step(target.center.y.toDouble(), omega * lead, zeta, dt)
                    flight.side.step(target.width.toDouble(), omega, zeta, dt)
                    flight.ring.step(0.0, omega * 2, 1.0, dt)
                    val close = abs(flight.x.value - target.center.x) < epsilon &&
                        abs(flight.y.value - target.center.y) < epsilon &&
                        abs(flight.side.value - target.width) < epsilon
                    val still = abs(flight.x.velocity) + abs(flight.y.velocity) + abs(flight.side.velocity) < 24 * density
                    if (flight.arrived && contained && close && still && settled && restingComposer == null) {
                        // Exactly on the thumbnail, which shows in this same frame.
                        flight.x.value = target.center.x.toDouble()
                        flight.y.value = target.center.y.toDouble()
                        flight.side.value = target.width.toDouble()
                        flight.x.velocity = 0.0
                        flight.y.velocity = 0.0
                        flight.side.velocity = 0.0
                        flight.state = Flight.State.Landed
                        hiddenAttachments = hiddenAttachments - flight.id
                    }
                }
                // Drawn one more frame over the thumbnail it now matches, then gone.
                Flight.State.Landed -> flight.state = Flight.State.Done
                Flight.State.Leaving -> {
                    flight.fade.step(0.0, exitParams.omega, exitParams.zeta, dt)
                    if (flight.fade.value < 0.01) flight.state = Flight.State.Done
                }
                Flight.State.Done -> Unit
            }
        }
        frame += 1
        val finished = flights.filter { it.state == Flight.State.Done }
        if (finished.isNotEmpty()) {
            flights = flights.filter { it.state != Flight.State.Done }
            val flying = flights.map { it.photo }.toSet()
            val returning = finished.map { it.photo }.filter { it !in flying }.toSet()
            if (returning.isNotEmpty()) hiddenPhotos = hiddenPhotos - returning
            for (photo in returning) if (sources[photo]?.isEphemeral == true) sources.remove(photo)
        }
        if (flights.isEmpty()) {
            sleep()
            return false
        }
        return true
    }

    /** Where the next thumbnail will be laid out before it is: after the last one in the row, or above the message field. */
    private fun predictedSlot(waiting: Int): Rect {
        val side = slotSidePx
        val step = side + slotSpacingPx
        val ordered = present + reservedAttachments.filter { it !in present }
        val last = ordered.mapNotNull { slots[it] }.maxByOrNull { it.right }
        if (last != null) {
            return Rect(Offset(last.right + slotSpacingPx + waiting * step, last.top), Size(side, side))
        }
        if (composerFrame == Rect.Zero) return Rect.Zero
        return Rect(
            Offset(
                composerFrame.left + PhotoFlightLayout.FIRST_SLOT_X * density + waiting * step,
                composerFrame.bottom - PhotoFlightLayout.FIRST_SLOT_ABOVE_FOOT * density - side,
            ),
            Size(side, side),
        )
    }

    /**
     * How far to move a laid-out slot to aim at it: to where the composer will rest while
     * it's on its way there, otherwise by how far the field is drawn from its layout. Once
     * it's at rest both are nothing, so letting go of the rest moves nothing.
     */
    private fun aim(now: Double): Pair<Offset, Offset> {
        val offset = anchorOffset()
        val rest = restingComposer
        if (rest == null || composerFrame == Rect.Zero) return Offset.Zero to offset
        val shift = Offset(rest.left - composerFrame.left, rest.bottom - composerFrame.bottom)
        val half = 0.5f * density
        val atRest = abs(shift.x) < half && abs(shift.y) < half && abs(offset.x) < half && abs(offset.y) < half
        if (atRest || now - restingSince > RESTING_PATIENCE) {
            restingComposer = null
            return Offset.Zero to offset
        }
        return shift to Offset.Zero
    }

    /** One photo in the air. iOS `PhotoFlights.Flight`. */
    class Flight(
        val id: UUID,
        val batchId: UUID = UUID.randomUUID(),
        val photo: String,
        val source: Source,
        val start: Double,
        /** Pixels per dp when it left. */
        val density: Float = 1f,
    ) {
        private val slotSidePx: Float get() = SLOT_SIDE * density
        private val slotRadiusPx: Float get() = SLOT_RADIUS * density

        enum class State { Flying, Landed, Leaving, Done }

        val image: ImageBitmap? = source.image
        var state = State.Flying
        var departed = false

        /** Its slot is laid out (not predicted). */
        var arrived = false
        var retargets = 0
        val x = SpringSolver(value = source.frame.center.x.toDouble(), omega = 1.0)
        val y = SpringSolver(value = source.frame.center.y.toDouble(), omega = 1.0)
        val side = SpringSolver(value = source.frame.width.toDouble(), omega = 1.0)
        val ring = SpringSolver(value = source.ring.toDouble(), omega = 1.0)
        val fade = SpringSolver(value = 1.0, omega = 1.0)
        var target: Rect? = null
        var modelTarget: Rect? = null

        /** Drawn this frame, in screen px. */
        val rect: Rect
            get() {
                val s = (side.value * if (state == State.Leaving) 0.96 + 0.04 * fade.value else 1.0).toFloat()
                return Rect(Offset(x.value.toFloat() - s / 2, y.value.toFloat() - s / 2), Size(s, s))
            }

        /** Square corners grow round as it shrinks into its slot. */
        val radius: Float
            get() {
                val from = source.frame.width
                val to = slotSidePx
                if (abs(from - to) <= 0.5f * density) return slotRadiusPx
                val t = ((from - side.value.toFloat()) / (from - to)).coerceIn(0f, 1f)
                return source.radius + (slotRadiusPx - source.radius) * t
            }

        /** Progress from its source (0) to its slot (1). */
        val progress: Float
            get() {
                if (state == State.Landed || state == State.Done) return 1f
                if (!departed) return 0f
                val fromSide = source.frame.width
                val toSide = slotSidePx
                if (abs(fromSide - toSide) > 2 * density) {
                    return ((fromSide - side.value.toFloat()) / (fromSide - toSide)).coerceIn(0f, 1f)
                }
                val t = target ?: return 0f
                val total = hypot(t.center.x - source.frame.center.x, t.center.y - source.frame.center.y)
                if (total <= density) return 1f
                val remaining = hypot(t.center.x - x.value.toFloat(), t.center.y - y.value.toFloat())
                return (1 - remaining / total).coerceIn(0f, 1f)
            }

        /** Centre of the corner × badge, matching the attachment's 48 dp remove button offset (8, −8). */
        val closeBadgeCenter: Offset
            get() {
                val current = rect
                val inset = PhotoFlightLayout.CLOSE_BADGE_INSET * density
                return Offset(current.right - inset, current.top + inset)
            }

        /** The × badge's opacity and scale this frame. */
        fun closeBadgeAppearance(
            start: Float = PhotoFlightTunables.closeStart,
            span: Float = PhotoFlightTunables.closeSpan,
            initialScale: Float = PhotoFlightTunables.closeScale,
        ): Pair<Float, Float> {
            val clampedStart = start.coerceIn(0f, 0.95f)
            val clampedSpan = max(min(span, 1 - clampedStart), 0.05f)
            val raw = ((progress - clampedStart) / clampedSpan).coerceIn(0f, 1f)
            val smooth = raw * raw * (3 - 2 * raw)
            val effective = when (state) {
                State.Landed, State.Done -> 1f
                State.Leaving -> smooth * fade.value.toFloat().coerceIn(0f, 1f)
                State.Flying -> smooth
            }
            val clampedInitial = initialScale.coerceIn(0.05f, 1f)
            return effective to (clampedInitial + (1 - clampedInitial) * effective)
        }

        val opacity: Float get() = if (state == State.Leaving) fade.value.toFloat() else 1f
    }

    companion object {
        /** How long a flight waits for the composer to come to rest before it follows the composer where it is. */
        const val RESTING_PATIENCE = 1.2

        /** The thumbnails' size, corners and spacing in dp (`SessionView.attachmentThumbnail`). */
        const val SLOT_SIDE = 76f
        /** iOS `ComposerTunables.attachmentRadius`: the composer's 28 corner, 19 (`attachmentInset`) in. */
        const val SLOT_RADIUS = 9f
        const val SLOT_SPACING = 8f

        /** iOS `AnimationTimeScale.shared.now()`: geometry time follows Developer Mode slow motion. */
        private fun now(): Double = AnimationTimeScale.shared.now()
    }
}

/** iOS `PhotoFlightLayout` (dp). `drawnOrigin(of:in:)` has no Compose counterpart: coordinates already include layer transforms. */
object PhotoFlightLayout {
    /** The first thumbnail in the composer, before any is laid out: its leading edge from the composer's. */
    const val FIRST_SLOT_X = 6f

    /** …and its foot above the composer's. */
    const val FIRST_SLOT_ABOVE_FOOT = 108f

    /** Centre of the 44 dp remove (×) button inset from the thumbnail's top-trailing corner (`44 / 2 − 8 = 14`). */
    const val CLOSE_BADGE_INSET = 14f
    const val CLOSE_BADGE_SIZE = 44f
}

private fun Rect.containsRect(other: Rect): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

/**
 * The flights on screen, drawn directly each frame above the composer (iOS `PhotoFlightCanvas`).
 * Mount it full-screen as the session's topmost overlay; it takes no touches, is hidden from
 * TalkBack, and runs the flights' frame loop while they're awake.
 */
@Composable
fun PhotoFlightCanvas(flights: PhotoFlights, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    flights.density = density
    val frameDuration = rememberFrameDurationSeconds()
    DisposableEffect(flights) {
        onDispose { flights.canvasBounds = null }
    }
    LaunchedEffect(flights, flights.awake) {
        if (!flights.awake) return@LaunchedEffect
        motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
            if (flights.tick(tick.dt)) MotionFrameAction.Continue else MotionFrameAction.Stop
        }
    }
    var origin by androidx.compose.runtime.remember { mutableStateOf(Offset.Zero) }
    Canvas(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .onGloballyPositioned { coordinates ->
                val topLeft = coordinates.positionOnScreen()
                origin = topLeft
                flights.canvasBounds = Rect(topLeft, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
            },
    ) {
        @Suppress("UNUSED_VARIABLE")
        val frame = flights.frame
        for (flight in flights.flights) drawFlight(flight, origin, density)
    }
}

private fun DrawScope.drawFlight(flight: PhotoFlights.Flight, origin: Offset, density: Float) {
    val rect = flight.rect.translate(-origin.x, -origin.y)
    if (rect.width <= 0f) return
    val radius = flight.radius
    val alpha = flight.opacity.coerceIn(0f, 1f)
    val shape = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
    clipPath(shape) {
        drawRect(Color(0x29787880), topLeft = rect.topLeft, size = rect.size, alpha = alpha)
        flight.image?.let { image -> drawAspectFill(image, rect, alpha) }
    }
    val ring = flight.ring.value.toFloat().coerceIn(0f, 1f)
    if (ring > 0.001f) {
        val inset = density
        drawRoundRect(
            Color.White,
            topLeft = Offset(rect.left + inset, rect.top + inset),
            size = Size(rect.width - inset * 2, rect.height - inset * 2),
            cornerRadius = CornerRadius(max(radius - inset, 0f)),
            style = Stroke(width = 2 * density),
            alpha = ring * alpha,
        )
    }
    val (badgeOpacity, badgeScale) = flight.closeBadgeAppearance()
    val badgeAlpha = alpha * badgeOpacity
    if (badgeAlpha > 0.001f) {
        val inset = PhotoFlightLayout.CLOSE_BADGE_INSET * density
        val center = Offset(rect.right - inset, rect.top + inset)
        withTransform({ scale(badgeScale, badgeScale, center) }) {
            drawCloseBadge(center, density, badgeAlpha)
        }
    }
}

/** `xmark.circle.fill` in white on black 65 %, at body size. */
internal fun DrawScope.drawCloseBadge(center: Offset, density: Float, alpha: Float) {
    val r = 10f * density
    drawCircle(Color.Black.copy(alpha = 0.65f), radius = r, center = center, alpha = alpha)
    val arm = 3.6f * density
    val stroke = 1.8f * density
    drawLine(Color.White, Offset(center.x - arm, center.y - arm), Offset(center.x + arm, center.y + arm), stroke, StrokeCap.Round, alpha = alpha)
    drawLine(Color.White, Offset(center.x - arm, center.y + arm), Offset(center.x + arm, center.y - arm), stroke, StrokeCap.Round, alpha = alpha)
}

/** Draws [image] scaled to fill [dst] (centre-cropped), like `.scaledToFill()`. */
internal fun DrawScope.drawAspectFill(image: ImageBitmap, dst: Rect, alpha: Float = 1f) {
    val iw = image.width.toFloat()
    val ih = image.height.toFloat()
    if (iw <= 0f || ih <= 0f || dst.width <= 0f || dst.height <= 0f) return
    val scale = max(dst.width / iw, dst.height / ih)
    val cropW = dst.width / scale
    val cropH = dst.height / scale
    val srcX = ((iw - cropW) / 2).roundToInt().coerceAtLeast(0)
    val srcY = ((ih - cropH) / 2).roundToInt().coerceAtLeast(0)
    drawImage(
        image,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(cropW.roundToInt().coerceIn(1, image.width - srcX), cropH.roundToInt().coerceIn(1, image.height - srcY)),
        dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
        dstSize = IntSize(dst.width.roundToInt().coerceAtLeast(1), dst.height.roundToInt().coerceAtLeast(1)),
        alpha = alpha,
    )
}
