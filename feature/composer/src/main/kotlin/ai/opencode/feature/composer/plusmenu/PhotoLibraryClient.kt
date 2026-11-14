package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/** How much of the photo library the app may read (iOS `PhotoLibraryAccess`). */
enum class PhotoLibraryAccess {
    NotDetermined,

    /** Every photo. */
    Full,

    /**
     * Only the photos the person picked: iOS's limited library, Android 14+'s “Selected
     * photos” (`READ_MEDIA_VISUAL_USER_SELECTED` without `READ_MEDIA_IMAGES`).
     */
    Limited,
    Denied,

    /**
     * Android only: the app has no library access to ask for (Google Play's photo and video
     * permissions policy), so the system photo picker stands in for the sheet and the peek.
     */
    Unavailable,
    ;

    val canRead: Boolean get() = this == Full || this == Limited
}

/** One photo or video in the library, by its MediaStore content URI (iOS `LibraryPhoto`). */
data class LibraryPhoto(
    val id: String,
    val created: Instant?,
    val modified: Instant? = null,
    /** Non-null for videos; its duration in seconds. */
    val videoDuration: Double? = null,
) {
    val isVideo: Boolean get() = videoDuration != null
    val formattedDuration: String? get() = videoDuration?.let(::formatDuration)

    companion object {
        fun formatDuration(seconds: Double): String {
            if (!seconds.isFinite()) return "0:00"
            val total = max(0, seconds.roundToInt())
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            val secs = total % 60
            if (hours > 0) return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
            return String.format(Locale.US, "%d:%02d", minutes, secs)
        }
    }
}

/**
 * Asks the system for photo permissions from the visible activity. Installed while the UI
 * that may ask is composed ([PhotoLibraryAccessHost]); the client never holds an activity
 * otherwise. Returns when the system dialog answered.
 */
fun interface PhotoPermissionRequester {
    suspend fun request(permissions: Array<String>)
}

/**
 * The photo library for the New Plus Menu's grid and recent strip: access, the newest photos,
 * thumbnails, and the full image to attach (iOS `PhotoLibraryClient`). Nothing else touches
 * MediaStore.
 */
class PhotoLibraryClient(
    val access: () -> PhotoLibraryAccess,
    /** Asks for read access if it was never asked; returns what it is now. */
    val requestAccess: suspend () -> PhotoLibraryAccess,
    /** The newest photos, newest first. */
    val recent: suspend (limit: Int) -> List<LibraryPhoto>,
    /**
     * A thumbnail at least `side` dp square at `scale` px/dp, or null. `onDegraded` receives any
     * low-resolution preview first (MediaStore has none).
     */
    val thumbnail: suspend (id: String, side: Float, scale: Float, onDegraded: ((ImageBitmap) -> Unit)?) -> ImageBitmap?,
    /** Stops any thumbnail preheating and drops cached assets. */
    val stopCaching: () -> Unit = {},
    /** The image's data, as stored (HEIC or JPEG; a 1600 px JPEG frame for a video). */
    val imageData: suspend (id: String) -> ByteArray?,
    /** Ticks whenever the library changes (a selected-photos edit too). */
    val changes: () -> Flow<Unit>,
    /** Lets the person choose which photos a limited library shares (Android 14 re-request). */
    val manageLimitedSelection: () -> Unit,
    /** Installs (or, with null, removes) the activity seam permission requests go through. */
    val installRequester: (PhotoPermissionRequester?) -> Unit = {},
    /** iOS `UIApplication.openSettingsURLString`: this app's system settings page. */
    val openSettings: () -> Unit = {},
) {
    companion object Key : DependencyKey<PhotoLibraryClient> {
        /**
         * The live library: none. Google Play allows `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` only to
         * apps whose core purpose is photos, so the + menu's Photos opens the system photo picker
         * instead.
         */
        override val liveValue: PhotoLibraryClient = PhotoLibraryClient(
            access = { PhotoLibraryAccess.Unavailable },
            requestAccess = { PhotoLibraryAccess.Unavailable },
            recent = { emptyList() },
            thumbnail = { _, _, _, _ -> null },
            stopCaching = {},
            imageData = { null },
            changes = { emptyFlow() },
            manageLimitedSelection = {},
        )

        override val testValue: PhotoLibraryClient = PhotoLibraryClient(
            access = { PhotoLibraryAccess.Denied },
            requestAccess = { PhotoLibraryAccess.Denied },
            recent = { emptyList() },
            thumbnail = { _, _, _, _ -> null },
            stopCaching = {},
            imageData = { null },
            changes = { emptyFlow() },
            manageLimitedSelection = {},
        )

        /** iOS `labVideoDuration(forIndex:)`. */
        fun labVideoDuration(index: Int): Double? = when (index) {
            1 -> 14.0
            4 -> 8.0
            7 -> 31.0
            else -> if (index >= 10 && index % 4 == 1) ((index * 7) % 95 + 5).toDouble() else null
        }

        /**
         * iOS `-lab-photo-fixture`: a library of drawn swatches, so previews and lab runs never
         * wait on the device's photos or its access dialog. [limited] makes it a limited library.
         */
        fun labFixture(limited: Boolean): PhotoLibraryClient {
            val count = if (limited) 7 else 48
            val photos = (0 until count).map { index ->
                LibraryPhoto(
                    id = "lab-photo-$index",
                    created = Instant.ofEpochSecond(1_790_000_000L - index * 3_600L),
                    videoDuration = labVideoDuration(index),
                )
            }
            fun index(id: String): Int = id.substringAfterLast('-').toIntOrNull() ?: 0
            fun render(id: String, side: Int): Bitmap {
                val i = index(id)
                val hue = ((i * 37) % 360).toFloat()
                val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f))
                val bottom = Color.HSVToColor(floatArrayOf((hue + 0.12f * 360f) % 360f, 0.7f, 0.45f))
                val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.shader = LinearGradient(0f, 0f, side.toFloat(), side.toFloat(), top, bottom, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
                paint.shader = null
                paint.color = Color.argb(217, 255, 255, 255)
                paint.textSize = side * 0.28f
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textAlign = Paint.Align.CENTER
                val y = side / 2f - (paint.descent() + paint.ascent()) / 2f
                canvas.drawText("${i + 1}", side / 2f, y, paint)
                return bitmap
            }
            val access = if (limited) PhotoLibraryAccess.Limited else PhotoLibraryAccess.Full
            return PhotoLibraryClient(
                access = { access },
                requestAccess = { access },
                recent = { limit -> photos.take(limit) },
                thumbnail = { id, side, scale, _ -> render(id, max(1, (side * scale).roundToInt())).asImageBitmap() },
                stopCaching = {},
                imageData = { id ->
                    ByteArrayOutputStream().use { out ->
                        render(id, 600).compress(Bitmap.CompressFormat.JPEG, 80, out)
                        out.toByteArray()
                    }
                },
                changes = { emptyFlow() },
                manageLimitedSelection = {},
            )
        }
    }
}

/**
 * Serializes the asset cache, preheat start/stop, and lifecycle generation under one lock so
 * [stopCaching] can't race a fetch. iOS `PhotoCacheGate`.
 */
class PhotoCacheGate<Asset>(
    private val stopAll: () -> Unit,
    private val startWarmup: (List<Asset>, Float) -> Unit,
) {
    private val lock = Any()
    private var generation: Long = 0
    private var assets: Map<String, Asset> = emptyMap()

    fun currentGeneration(): Long = synchronized(lock) { generation }

    fun isCurrent(expectedGeneration: Long): Boolean = synchronized(lock) { generation == expectedGeneration }

    fun cachedAsset(id: String): Asset? = synchronized(lock) { assets[id] }

    fun commit(expectedGeneration: Long, assetsById: Map<String, Asset>, warmupAssets: List<Asset>, scale: Float, cancelled: Boolean = false): Boolean =
        synchronized(lock) {
            if (cancelled || generation != expectedGeneration) return false
            assets = assetsById
            stopAll()
            if (warmupAssets.isNotEmpty()) startWarmup(warmupAssets, scale)
            true
        }

    fun stopCaching() = synchronized(lock) {
        generation += 1
        assets = emptyMap()
        stopAll()
    }
}

/** The activity hosting [this] context, unwrapping wrappers (theme, dialog). */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

/**
 * Requests [permissions] from [activity] and returns once the system answered (on resume, or after
 * [IMMEDIATE_ANSWER_MS] when it answers without a screen). Uses the framework call because
 * `androidx.activity`'s result registry isn't on this module's classpath.
 */
internal suspend fun requestPermissionsAndAwait(activity: Activity, lifecycle: Lifecycle, permissions: Array<String>) {
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var paused = false
            lateinit var observer: LifecycleEventObserver
            fun finish() {
                lifecycle.removeObserver(observer)
                handler.removeCallbacksAndMessages(null)
                if (continuation.isActive) continuation.resume(Unit)
            }
            observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> paused = true
                    Lifecycle.Event.ON_RESUME -> if (paused) finish()
                    Lifecycle.Event.ON_DESTROY -> finish()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            continuation.invokeOnCancellation {
                handler.post {
                    lifecycle.removeObserver(observer)
                    handler.removeCallbacksAndMessages(null)
                }
            }
            activity.requestPermissions(permissions, PERMISSION_REQUEST_CODE)
            handler.postDelayed({ if (!paused) finish() }, IMMEDIATE_ANSWER_MS)
        }
    }
}

private const val PERMISSION_REQUEST_CODE = 0x0C4D
private const val IMMEDIATE_ANSWER_MS = 700L

/**
 * Installs the activity seam [PhotoLibraryClient.requestAccess] and
 * [PhotoLibraryClient.manageLimitedSelection] ask through, while composed. Mount it once in
 * any UI that may ask (the photo sheet does).
 */
@Composable
fun PhotoLibraryAccessHost(client: PhotoLibraryClient = remember { dependency(PhotoLibraryClient) }) {
    val activity = LocalContext.current.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle, client) {
        if (activity == null) return@DisposableEffect onDispose {}
        client.installRequester(PhotoPermissionRequester { permissions -> requestPermissionsAndAwait(activity, lifecycle, permissions) })
        onDispose { client.installRequester(null) }
    }
}
