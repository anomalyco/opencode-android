package ai.opencode.core.sharing

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Port of the primitive-typed keys in iOS `SharedKeys.swift`. Keys whose values are server or voice
 * types live with those types.
 */
object SharedKeys {
    /** Whether an iPad-wide window shows the sessions sidebar (large-screen Android: list-detail pane). On by default. */
    val sidebarShown = SharedKey.appStorage("sidebar-shown", true)

    /**
     * Whether the Return key sends the message (`true`) or inserts a new line (`false`, default).
     * On a hardware keyboard, Ctrl/Cmd-Enter always sends and Shift-Enter always inserts a new line.
     */
    val returnKeySends = SharedKey.appStorage("return-key-sends", false)

    /** The file viewer's Wrap Lines choice, kept across files and launches. Off by default. */
    val fileViewerWrapsLines = SharedKey.appStorage("file-viewer-wraps-lines", false)

    /** Gates experimental Voice Mode inside Settings → Experimental. Off by default. */
    val voiceModeEnabled = SharedKey.appStorage("voice-mode-enabled", false)

    /** Developer Mode: upload voice calls and traces to the log server. Off by default. Keeps the voice-only switch's key. */
    val developerMode = SharedKey.appStorage("voice-call-uploads", false)

    /** Developer Mode's Motion Watch (iOS-only tool; kept so the key list matches). Off by default. */
    val motionWatch = SharedKey.appStorage("developer-motion-watch", false)

    /** Touch Probe (iOS-only tool; kept so the key list matches). Off by default. */
    val touchProbe = SharedKey.appStorage("diagnostics-touch-probe", false)

    /** Demo Mode: voice calls play as media so a recording captures them. Off by default. */
    val voiceDemoMode = SharedKey.appStorage("voice-demo-mode", false)

    /** Raise to Talk: raising the phone to your ear on a session starts an earpiece call. Off by default. */
    val raiseToTalk = SharedKey.appStorage("raise-to-talk", false)

    /** Agent Selection: the agent new sessions start with (an agent ID); empty is the server's default. */
    val defaultAgentID = SharedKey.appStorage("default-agent", "")

    /** Whether the one-time migration moving saved voice engines to GPT-Live has run on this install. */
    val voiceEngineMigratedToGPTLive = SharedKey.appStorage("voice-engine-migrated-to-gpt-live", false)

    /** Whether the OpenCode Console sign-in earlier builds kept has been deleted (no-op on Android; kept for parity). */
    val consoleSignInRemoved = SharedKey.appStorage("console-sign-in-removed", false)

    /** Your own OpenAI project key for voice calls; never sent to the OpenCode server. Empty/null disables Voice. */
    val openAIAPIKey = SharedKey.keychain<String?>("openai-api-key", null)

    /** Your own xAI key for Grok Voice calls; never sent to the OpenCode server. */
    val xaiAPIKey = SharedKey.keychain<String?>("xai-api-key", null)

    /** The random key that owns this phone's uploaded voice calls; whoever has it can view them. Made on first upload. */
    val developerModeOwner = SharedKey.keychain<String?>("voice-call-upload-owner", null)

    /**
     * SHA-256 fingerprint of the last voice key and model accepted by the preflight. Not a
     * credential; only avoids redundant checks. Keeps its OpenAI-only file name.
     */
    val verifiedVoiceKeyFingerprint = SharedKey.fileStorage<String?>(FileDirectory.Caches, "verified-openai-key.json", null)

    /** Starred models pinned to the top of the model picker. */
    val favoriteModelIDs = SharedKey.fileStorage<Set<String>>(FileDirectory.Documents, "favorite-models.json", emptySet())

    val manuallyUnreadSessionKeys =
        SharedKey.fileStorage<Set<String>>(FileDirectory.Documents, "manually-unread-sessions.json", emptySet())

    /** Phone-local, server-scoped (`serverID|sessionID`) like pins: muted sessions never reach Catch Up or an unread badge. */
    val mutedSessionKeys = SharedKey.fileStorage<Set<String>>(FileDirectory.Documents, "muted-sessions.json", emptySet())

    val initializedInboxServerIDs =
        SharedKey.fileStorage<Set<String>>(FileDirectory.Documents, "initialized-inbox-servers.json", emptySet())

    /** Servers (by persistence ID) whose upload route is missing this launch. In memory only. */
    val uploadUnsupportedServerIDs = SharedKey.inMemory<Set<String>>("upload-unsupported-servers", emptySet())

    val sessionReadDates = SharedKey(
        id = "session-read-dates.json",
        default = emptyMap(),
        serializer = MapSerializer(String.serializer(), InstantSerializer),
        storage = SharedStorageKind.File(FileDirectory.Documents),
    )

    /** Last root chat opened on each server. The transcript cache provides its first frame while the server revalidates. */
    val lastViewedSessionIDs =
        SharedKey.fileStorage<Map<String, String>>(FileDirectory.Documents, "last-viewed-sessions.json", emptyMap())

    /** Remembered reasoning variant per model (`model.id` → variant), restored when picking that model again. */
    val modelVariants = SharedKey.fileStorage<Map<String, String>>(FileDirectory.Documents, "model-variants.json", emptyMap())

    /** Pinned sessions, under iOS's file name. */
    val pinnedSessionKeys = SharedKey.fileStorage<List<String>>(FileDirectory.Documents, "followed-sessions.json", emptyList())

    /** Recently picked models (`model.id`), most recent first. */
    val recentModelIDs = SharedKey.fileStorage<List<String>>(FileDirectory.Documents, "recent-models.json", emptyList())
}

/** ISO-8601 instants, for keys that store dates. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
