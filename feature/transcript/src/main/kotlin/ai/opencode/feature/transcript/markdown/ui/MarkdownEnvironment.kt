package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.server.model.AgentFileReference
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * iOS `SessionLinkContext`: the row's resolved session links. The row sets it from
 * `RowProps.sessionLinks`; IDs a block shows are reported once per set as
 * `TranscriptAction.SessionLinksAppeared`.
 */
@Immutable
data class SessionLinkContext(val targets: Map<String, SessionLinkTarget> = emptyMap(), val row: String? = null)

val LocalSessionLinks = staticCompositionLocalOf { SessionLinkContext() }

/** Link Icons (iOS `InlineLinkStyle`): how a web link in a reply's text shows. */
enum class InlineLinkStyle {
    /** The link's text alone, as shipped (other Markdown surfaces). */
    Today,
    /** The site's icon before every web link. */
    Icon,
    /** The icon; a bare URL folds to its domain and a short path, on one line. */
    Chip,
    /** The icon; a bare URL drops `https://` and keeps the rest of its address (the default). */
    Address,
}

/**
 * The row's stable list identity (iOS `EnvironmentValues.linkPreviewRow`): Link Icons report
 * their links under it, and image links name their media with it. The list provides it around
 * each row; null outside one. The store and the row's revision are media's
 * (`LocalLinkPreviewStore`, `LocalLinkPreviewRevision`).
 */
val LocalLinkPreviewRow = staticCompositionLocalOf<String?> { null }

/** iOS `transcriptLayoutTuning.linkStyle`: Clean Address in the transcript; surfaces outside it set Today. */
val LocalInlineLinkStyle = staticCompositionLocalOf { InlineLinkStyle.Address }

/**
 * iOS `.foregroundStyle(.secondary)` around a `MarkdownBlocksView` (the expanded compaction
 * summary): headings, prose and list items all draw in secondary text.
 */
val LocalMarkdownSecondary = staticCompositionLocalOf { false }

/** iOS `QuoteTextHandler`: selected reply text offers Quote beside Copy. Null where quoting isn't offered. */
@Stable
class QuoteTextHandler(val quote: (String) -> Unit)

/** iOS `EnvironmentValues.quoteText`. The reply row sets it to send `TranscriptAction.QuoteTextTapped`. */
val LocalQuoteTextHandler = staticCompositionLocalOf<QuoteTextHandler?> { null }

/** iOS `AgentImageScope`: a whole reply's images, for blocks shown in parts (Messages bubbles). */
@Immutable
data class AgentImageScope(val occurrence: String, val siblings: List<AgentFileReference>)
