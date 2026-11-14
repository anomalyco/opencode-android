package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.core.files.AttachmentFileType
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * One open file's loading, playback and Share (iOS `AgentFileViewerModel`). Its state is snapshot
 * state the page reads; the page calls [open] when it appears and [stop] when it goes, and the
 * viewer calls [release] when the page is gone for good.
 */
class AgentFileViewerModel(
    val reference: AgentFileReference,
    val server: OpenCodeServer,
    val directory: String?,
    private val playbackAudio: MediaPlaybackAudio = dependency(MediaPlaybackAudio),
    private val shareClient: AgentFileShareClient = dependency(AgentFileShareClient),
    private val context: Context = dependency(AppContext),
) {
    sealed interface Content {
        class Player(val player: ExoPlayer, val isAudio: Boolean, val isStreaming: Boolean) : Content

        /** Shown like a transcript image: zoomable. */
        class Image(val image: Bitmap) : Content

        /**
         * iOS Quick Look. Android has none: a PDF renders its pages ([pdf]); any other document
         * offers another app (`ACTION_VIEW` through the FileProvider).
         */
        class QuickLook(val file: File, val pdf: AgentPdfDocument?) : Content

        class Text(val document: AgentTextDocument) : Content

        /**
         * Rendered like a reply, with the source a toggle away. The Markdown renderer lives in
         * `:feature:transcript` (above media), which renders [source] through the viewer's slot.
         */
        class Markdown(val source: String, val document: AgentTextDocument) : Content
    }

    sealed interface Phase {
        data object Opening : Phase
        data class Downloading(val received: Long, val expected: Long?) : Phase
        data class ConfirmLarge(val size: Long) : Phase

        /** [localFile] is the downloaded copy, for Share; null while streaming. */
        data class Ready(val content: Content, val localFile: File?) : Phase
        data class Failed(val error: AgentFileError) : Phase
    }

    /** A streamed file downloading for Share. */
    data class ShareProgress(val received: Long, val expected: Long?)

    var phase: Phase by mutableStateOf(Phase.Opening)
        private set

    var shareProgress: ShareProgress? by mutableStateOf(null)
        private set

    var shareError: AgentFileError? by mutableStateOf(null)

    /**
     * No app on this device opened the document (Android only: iOS previews every document in
     * Quick Look). Cleared by the next try.
     */
    var noAppOpens: Boolean by mutableStateOf(false)
        private set

    private val wrapsLinesShared = shared(SharedKeys.fileViewerWrapsLines)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var streamLoader: StreamingFileLoader? = null
    private var probe: AgentFileProbe? = null
    private var work: Job? = null
    private var shareWork: Job? = null
    private var audioHolder: AudioHolder? = null

    /** One activation; `holder` is set once activation succeeded off the main thread. */
    private class AudioHolder {
        @Volatile var holder: MediaPlaybackAudio.Holder? = null
        @Volatile var released = false
    }

    fun toggleWrapLines() {
        val next = !wrapsLinesShared.value
        if (wrapsLinesShared.value == next) return
        wrapsLinesShared.set(next)
    }

    val target: AgentFileReference.Target? get() = reference.target(directory)

    /** The open file's own folder: a document's relative links and images resolve against it. */
    val fileDirectory: String? get() = target?.let { deletingLastPathComponent(it.absolutePath) }

    /** "opencode-ios/OpenCode", "~/Downloads", or "/tmp": where it lives. */
    val locationLabel: String?
        get() {
            val target = target ?: return null
            val parent = deletingLastPathComponent(target.absolutePath)
            // A Windows target's absolute path is normalized; compare it with the directory normalized the same way.
            val directory = AgentFileReference.comparableDirectory(this.directory)
            if (directory != null && (parent == directory || parent.startsWith("$directory/"))) {
                val project = lastPathComponent(directory)
                return if (parent == directory) project else project + parent.drop(directory.length)
            }
            val home = AgentFileReference.home(this.directory)
            if (home != null && (parent == home || parent.startsWith("$home/"))) return "~" + parent.drop(home.length)
            return parent
        }

    fun open() {
        (phase as? Phase.Ready)?.let { if (it.localFile != null) return }
        work?.cancel()
        work = scope.launch { run(confirmed = false) }
    }

    fun confirmDownload() {
        work?.cancel()
        work = scope.launch { run(confirmed = true) }
    }

    fun stop() {
        work?.cancel()
        shareWork?.cancel()
        ((phase as? Phase.Ready)?.content as? Content.Player)?.player?.pause()
        releaseAudio()
        streamLoader?.invalidate()
        streamLoader = null
    }

    /** The page is gone for good: frees the player and the PDF renderer. */
    fun release() {
        stop()
        when (val content = (phase as? Phase.Ready)?.content) {
            is Content.Player -> content.player.release()
            is Content.QuickLook -> content.pdf?.close()
            else -> Unit
        }
        scope.cancel()
    }

    private suspend fun run(confirmed: Boolean) {
        val target = target
        val request = target?.let { server.fileReadRequest(it) }
        if (target == null || request == null) {
            phase = Phase.Failed(AgentFileError.Unreadable)
            return
        }
        try {
            if (!confirmed || probe == null) {
                phase = Phase.Opening
                probe = AgentFileLoader.probe(request, server)
            }
            val probe = probe ?: return
            if (!scope.isActive) return
            if (reference.kind.isTimeBased && probe.supportsRanges) {
                startStreaming(request, probe.mime)
                return
            }
            val size = probe.size
            if (!confirmed && size != null && size > AgentFileLoader.confirmationBytes) {
                phase = Phase.ConfirmLarge(size)
                return
            }
            phase = Phase.Downloading(0, probe.size)
            val destination = AgentFileLoader.cacheURL(server, target.absolutePath, reference.name, context.cacheDir)
            val file = AgentFileLoader.download(request, destination, server) { received, expected ->
                scope.launch { downloaded(received, expected) }
            }
            present(file)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AgentFileError) {
            phase = Phase.Failed(error)
        } catch (error: Exception) {
            AppLog.network.notice { "Agent file: ${error::class.java.simpleName}" }
            phase = Phase.Failed(AgentFileError.network(error))
        }
    }

    /**
     * Every file that opens can be shared, and so saved. A downloaded one shares at once; a
     * streamed one downloads first, keeps playing meanwhile, and opens the share sheet when it
     * lands.
     */
    fun share() {
        val ready = phase as? Phase.Ready ?: return
        val file = ready.localFile
        if (file != null) {
            shareClient.share(file, reference.mime)
            return
        }
        shareStreamed()
    }

    fun shareStreamed() {
        val ready = phase as? Phase.Ready ?: return
        if (ready.localFile != null || shareWork != null) return
        val target = target ?: return
        val request = server.fileReadRequest(target) ?: return
        val content = ready.content
        shareProgress = ShareProgress(0, probe?.size)
        val destination = AgentFileLoader.cacheURL(server, target.absolutePath, reference.name, context.cacheDir)
        shareWork = scope.launch {
            try {
                val file = AgentFileLoader.download(request, destination, server) { received, expected ->
                    scope.launch { shareDownloaded(received, expected) }
                }
                val current = phase as? Phase.Ready
                if (current == null || current.content !== content || current.localFile != null) return@launch
                phase = Phase.Ready(content, file)
                shareClient.share(file, reference.mime)
            } catch (error: CancellationException) {
                throw error
            } catch (error: AgentFileError) {
                shareError = error
            } catch (error: Exception) {
                shareError = AgentFileError.network(error)
            } finally {
                shareWork = null
                shareProgress = null
            }
        }
    }

    /** Opens a document that can't show here (not a PDF) in another app. */
    fun openExternally() {
        val file = ((phase as? Phase.Ready)?.content as? Content.QuickLook)?.file ?: return
        noAppOpens = !shareClient.view(file, reference.mime)
    }

    private fun shareDownloaded(received: Long, expected: Long?) {
        if (shareProgress == null) return
        shareProgress = ShareProgress(received, expected ?: probe?.size)
    }

    private fun downloaded(received: Long, expected: Long?) {
        if (phase !is Phase.Downloading) return
        phase = Phase.Downloading(received, expected ?: probe?.size)
    }

    @OptIn(UnstableApi::class)
    private fun startStreaming(request: Request, mime: String?) {
        streamLoader?.invalidate()
        val loader = StreamingFileLoader(request, server, mime, reference.name)
        streamLoader = loader
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(loader.dataSourceFactory))
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setMediaItem(loader.makeMediaItem())
        play(player, localFile = null)
    }

    private suspend fun present(file: File) {
        when (reference.kind) {
            AgentFileReference.Kind.Video, AgentFileReference.Kind.Audio -> {
                val player = ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build()
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)))
                play(player, localFile = file)
            }
            AgentFileReference.Kind.Text -> {
                val name = reference.name
                val line = reference.line
                val isMarkdown = AttachmentFileType.fileExtension(name) in AgentTextDocument.markdownExtensions && line == null
                val content = loadTextContent(file, name, line, isMarkdown)
                phase = Phase.Ready(content ?: quickLook(file), file)
            }
            AgentFileReference.Kind.Image -> {
                // Formats Android can't decode (SVG, say) still open as a document.
                val image = loadImage(file)
                phase = Phase.Ready(image?.let(Content::Image) ?: quickLook(file), file)
            }
            AgentFileReference.Kind.Pdf, AgentFileReference.Kind.Document -> phase = Phase.Ready(quickLook(file), file)
        }
    }

    private suspend fun quickLook(file: File): Content.QuickLook {
        val isPDF = reference.kind == AgentFileReference.Kind.Pdf || reference.mime == AttachmentFileType.pdfMime
        return Content.QuickLook(file, if (isPDF) AgentPdfDocument.open(file) else null)
    }

    /**
     * Shows the player at once and starts it once audio is active, activated off the main
     * thread. Plays even if activation fails.
     */
    private fun play(player: ExoPlayer, localFile: File?) {
        ((phase as? Phase.Ready)?.content as? Content.Player)?.player?.takeIf { it !== player }?.release()
        val audio = playbackAudio
        val holder = audioHolder ?: AudioHolder().also { audioHolder = it }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                AppLog.network.notice { "Agent file playback failed: ${error.errorCodeName}" }
                if (localFile == null && phase.let { it is Phase.Ready && it.content is Content.Player && it.content.player === player }) {
                    phase = Phase.Failed((cause as? StreamingFileLoader.StreamingFileException)?.error ?: AgentFileError.network(cause ?: error))
                }
            }
        })
        player.prepare()
        phase = Phase.Ready(Content.Player(player, reference.kind == AgentFileReference.Kind.Audio, localFile == null), localFile)
        audio.perform {
            val active = if (holder.holder != null) {
                true
            } else {
                runCatching {
                    audio.activate(audio.isShared()) { scope.launch { player.pause() } }
                }.onSuccess { holder.holder = it }.isSuccess
            }
            // Closed meanwhile: give it straight back.
            if (holder.released) holder.holder?.let { audio.deactivate(it); holder.holder = null }
            scope.launch { activated(holder, active, player) }
        }
    }

    private fun activated(holder: AudioHolder, active: Boolean, player: ExoPlayer) {
        // Closed meanwhile.
        if (audioHolder !== holder) return
        if (!active) audioHolder = null
        val current = ((phase as? Phase.Ready)?.content as? Content.Player)?.player
        if (current === player) player.play()
    }

    private fun releaseAudio() {
        val holder = audioHolder ?: return
        audioHolder = null
        holder.released = true
        val audio = playbackAudio
        audio.perform {
            holder.holder?.let(audio.deactivate)
            holder.holder = null
        }
    }

    companion object {
        private suspend fun loadImage(file: File): Bitmap? = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    // Android won't draw a bitmap over ~100 MB; keep the original otherwise.
                    val pixels = info.size.width.toLong() * info.size.height
                    var sample = 1
                    while (pixels / (sample.toLong() * sample) > MAX_IMAGE_PIXELS) sample *= 2
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                }
            }.getOrNull()
        }

        private const val MAX_IMAGE_PIXELS = 24L * 1024 * 1024

        suspend fun loadTextContent(file: File, fileName: String, focusLine: Int?, isMarkdown: Boolean): Content? =
            withContext(Dispatchers.Default) {
                val source = AgentFileLoader.readText(file) ?: return@withContext null
                val document = AgentTextDocument.of(source, fileName, focusLine)
                val renderMarkdown = isMarkdown && !document.usesLazyTextView
                if (renderMarkdown) Content.Markdown(source, document) else Content.Text(document)
            }

        /** NSString `deletingLastPathComponent`. */
        internal fun deletingLastPathComponent(path: String): String {
            val trimmed = if (path.length > 1) path.trimEnd('/') else path
            val slash = trimmed.lastIndexOf('/')
            return when {
                slash < 0 -> ""
                slash == 0 -> "/"
                else -> trimmed.substring(0, slash)
            }
        }

        /** NSString `lastPathComponent`. */
        internal fun lastPathComponent(path: String): String {
            val trimmed = if (path.length > 1) path.trimEnd('/') else path
            return if (trimmed == "/") "/" else trimmed.substringAfterLast('/')
        }
    }
}

/**
 * A PDF's pages for the viewer (Android's stand-in for Quick Look's PDF preview). One page
 * renders at a time; [close] when the page goes.
 */
class AgentPdfDocument private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Each page's width over height. */
    val pageAspects: List<Float>,
) {
    private val mutex = Mutex()
    private var closed = false

    val pageCount: Int get() = pageAspects.size

    /** Page [index] [width] pixels wide, white behind it, or null once closed. */
    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || width <= 0) return@withLock null
            renderer.openPage(index).use { page ->
                val height = maxOf(1, (width / pageAspects[index]).toInt())
                val bitmap = createBitmap(width, height)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                renderer.close()
                descriptor.close()
            }
        }
    }

    companion object {
        /** Null when it isn't a PDF this device can render. */
        suspend fun open(file: File): AgentPdfDocument? = withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    val renderer = PdfRenderer(descriptor)
                    val aspects = (0 until renderer.pageCount).map { index ->
                        renderer.openPage(index).use { page -> page.width.toFloat() / maxOf(page.height, 1) }
                    }
                    AgentPdfDocument(descriptor, renderer, aspects)
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
            }.getOrNull()
        }
    }
}

/**
 * Hands a downloaded file to the share sheet, or to another app for a document Android can't
 * preview, through this module's FileProvider.
 */
class AgentFileShareClient(
    val share: (file: File, mime: String) -> Boolean,
    val view: (file: File, mime: String) -> Boolean,
) {
    companion object Key : DependencyKey<AgentFileShareClient> {
        override val liveValue: AgentFileShareClient by lazy {
            val context = dependency(AppContext)
            fun uri(file: File): Uri = FileProvider.getUriForFile(context, AgentFileProvider.authority(context), file)
            fun start(intent: Intent): Boolean = try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) {
                AppLog.app.notice { "Agent file: no activity for ${intent.action}" }
                false
            } catch (_: IllegalArgumentException) {
                AppLog.app.notice { "Agent file: outside the shared folder" }
                false
            }
            AgentFileShareClient(
                share = { file, mime ->
                    runCatching { uri(file) }.getOrNull()?.let { uri ->
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(mime)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = ClipData.newRawUri(file.name, uri)
                        start(Intent.createChooser(send, null))
                    } ?: false
                },
                view = { file, mime ->
                    runCatching { uri(file) }.getOrNull()?.let { uri ->
                        start(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } ?: false
                },
            )
        }

        override val testValue: AgentFileShareClient get() = AgentFileShareClient(share = { _, _ -> false }, view = { _, _ -> false })
    }
}

/** Shares the file viewer's downloads (`<cache>/AgentFiles/`) and nothing else. */
class AgentFileProvider : FileProvider(R.xml.agent_file_paths) {
    companion object {
        fun authority(context: Context): String = "${context.packageName}.agentfiles"
    }
}
