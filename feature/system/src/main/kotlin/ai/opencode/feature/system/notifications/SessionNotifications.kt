package ai.opencode.feature.system.notifications

import ai.opencode.feature.system.NotificationDestination
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

enum class NotificationPermission { NotDetermined, Disabled, Enabled }

/** What a server notifies this phone about; [wire] is the server's push level. */
@Serializable
enum class PushLevel(val wire: String) { All("all"), Attention("attention") }

/** This phone's choice for one server. */
@Serializable
data class PushSetting(val isOn: Boolean = true, val level: PushLevel = PushLevel.All)

class NotificationPermissionClient(
    val status: suspend () -> NotificationPermission,
    val request: suspend () -> NotificationPermission,
    /** Android's notification settings for the app. */
    val openSettings: suspend () -> Unit,
) {
    companion object Key : DependencyKey<NotificationPermissionClient> {
        override val liveValue: NotificationPermissionClient by lazy {
            NotificationPermissionClient(
                status = { SessionNotifications.shared.permissionStatus() },
                request = { SessionNotifications.shared.requestPermission() },
                openSettings = {
                    val context = dependency(AppContext)
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
            )
        }
        override val testValue = NotificationPermissionClient(
            status = { NotificationPermission.Enabled },
            request = { NotificationPermission.Enabled },
            openSettings = {},
        )
    }
}

/**
 * This phone's notification keys, off the main thread. The live value is
 * [NotificationKeyStore.live]; the test value has no keys.
 */
class NotificationKeysClient(
    /** This phone's public key for `ref`, made the first time. */
    val prepare: suspend (ref: String) -> String,
    val establish: suspend (ref: String, serverPublicKey: String) -> Unit,
    val remove: suspend (ref: String) -> Unit,
) {
    companion object Key : DependencyKey<NotificationKeysClient> {
        override val liveValue: NotificationKeysClient by lazy {
            NotificationKeysClient(
                prepare = { ref -> withContext(Dispatchers.IO) { NotificationKeyStore.live.prepare(ref) } },
                establish = { ref, serverKey -> withContext(Dispatchers.IO) { NotificationKeyStore.live.establish(ref, serverKey) } },
                remove = { ref -> withContext(Dispatchers.IO) { NotificationKeyStore.live.remove(ref) } },
            )
        }
        override val testValue = NotificationKeysClient(
            prepare = { throw NotificationKeyStore.Failure.MissingKey },
            establish = { _, _ -> throw NotificationKeyStore.Failure.MissingKey },
            remove = {},
        )
    }
}

/** This phone's notifications from a server: its choice, and its registration for the server's pushes. */
class SessionNotificationsClient(
    /** Whether this build has a relay and a push channel to offer at all. */
    val isAvailable: Boolean,
    /** This phone's choice for a server (by persistence ID). */
    val setting: (String) -> PushSetting = { PushSetting() },
    /** Saves the choice and tells the server. False when the server couldn't be told; the choice is then unchanged. */
    val setSetting: suspend (OpenCodeServer, PushSetting) -> Boolean = { _, _ -> true },
    /** Registers this phone with a server it's connected to, when notifications are on and allowed. */
    val register: suspend (OpenCodeServer) -> Unit = {},
    /** Whether the server sends notifications at all; null when it couldn't be asked. */
    val isOffered: suspend (OpenCodeServer) -> Boolean? = { true },
    /** Drops a removed server's ref, callback and choice (by persistence ID). */
    val forget: suspend (String) -> Unit = {},
    /** The random `ref` a server's pushes carry, made and saved the first time it's asked for. */
    val ref: (String) -> String = { "" },
    /** Android grants the badge count with notifications. */
    val includeBadges: suspend () -> Unit = {},
    /** Tells notifications which session is on screen so its own don't show. */
    val setVisibleSession: (shown: Boolean, sessionID: String?) -> Unit = { _, _ -> },
) {
    companion object Key : DependencyKey<SessionNotificationsClient> {
        override val liveValue: SessionNotificationsClient by lazy {
            SessionNotificationsClient(
                isAvailable = SessionNotifications.isAvailable,
                setting = { SessionNotifications.shared.setting(it) },
                setSetting = { server, setting -> SessionNotifications.shared.setSetting(server, setting) },
                register = { SessionNotifications.shared.register(it) },
                isOffered = { SessionNotifications.shared.isOffered(it) },
                forget = { SessionNotifications.shared.forget(it) },
                ref = { SessionNotifications.shared.ref(it) },
                setVisibleSession = { shown, sessionID ->
                    val id = if (shown) sessionID else null
                    if (shown || SessionNotifications.shared.visibleSessionID == sessionID) {
                        SessionNotifications.shared.visibleSessionID = id
                    }
                },
            )
        }
        override val testValue = SessionNotificationsClient(isAvailable = true)
    }
}

/** How a session notification arriving in the foreground shows. The session on screen shows nothing. */
enum class NotificationPresentation { None, Banner }

/**
 * This phone's registration for a server's pushes.
 *
 * The relay is stateless. It seals the FCM token, the build's push environment and a random
 * per-server `ref` into a callback URL, so the OpenCode server never sees the token and the relay
 * keeps nothing. The `ref` is this phone's device ID on that server and how a push finds its saved
 * server; it is random, never derived from the server's address or credentials.
 *
 * Registering ([OpenCodeClient.registerPushDevice]) gives the server the callback, this phone's
 * level and its P-256 public key, and returns the server's. The server seals each push with the
 * derived key, so the relay and FCM only carry a `v2` envelope.
 */
class SessionNotifications internal constructor(private val tokens: PushTokenProvider) {
    /** The session on screen while the app is in the foreground. Its own "finished" notifications aren't shown. */
    @Volatile var visibleSessionID: String? = null

    fun presentation(sessionID: String?): NotificationPresentation {
        sessionID ?: return NotificationPresentation.Banner
        if (sessionID == visibleSessionID) return NotificationPresentation.None
        return NotificationPresentation.Banner
    }

    /** A tapped notification's destination, delivered once something routes it. */
    var onOpen: ((NotificationDestination) -> Unit)? = null
        set(value) {
            field = value
            val pending = pendingDestination
            if (value != null && pending != null) {
                pendingDestination = null
                value(pending)
            }
        }

    private var pendingDestination: NotificationDestination? = null
    @Volatile private var confirmedToken: String? = null
    private val lock = Any()
    private val registering = Mutex()
    /** Server key → what it was last told, held under [registering]. */
    private val registered = HashMap<String, Registered>()
    private val keys get() = dependency(NotificationKeysClient)
    private val saved get() = shared(savedKey)
    private var permissionRequest: CompletableDeferred<Boolean>? = null

    @Serializable
    data class Saved(
        /** Server persistence ID → its random ref. */
        val refs: Map<String, String> = emptyMap(),
        /** Server persistence ID → its current callback. */
        val callbacks: Map<String, Callback> = emptyMap(),
        /** Server persistence ID → this phone's choice, where it isn't the default. */
        val settings: Map<String, PushSetting> = emptyMap(),
    )

    @Serializable
    data class Callback(
        val url: String,
        @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
        val token: String,
        val environment: String,
        /** The device public key this callback was registered with. */
        val phonePublicKey: String? = null,
    ) {
        fun isReusable(token: String, environment: String, publicKey: String, relay: HttpUrl?, now: Instant): Boolean =
            this.token == token && this.environment == environment && phonePublicKey == publicKey &&
                expiresAt.toEpochMilli() - now.toEpochMilli() > 3.days.inWholeMilliseconds &&
                isEncryptedCallback(url, relay)
    }

    data class Minted(val url: String, val expiresAt: Instant)

    private data class Registered(val callbackURL: String, val level: PushLevel, val checkAt: Instant)

    fun permissionStatus(): NotificationPermission {
        val context = dependency(AppContext)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return NotificationPermission.Enabled
        val asked = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ASKED, false)
        return if (asked) NotificationPermission.Disabled else NotificationPermission.NotDetermined
    }

    /** Asks once (Android's dialog, from a see-through activity). */
    suspend fun requestPermission(): NotificationPermission {
        if (permissionStatus() == NotificationPermission.NotDetermined) {
            val request = synchronized(lock) {
                permissionRequest ?: CompletableDeferred<Boolean>().also { created ->
                    permissionRequest = created
                    val context = dependency(AppContext)
                    context.startActivity(
                        Intent(context, NotificationPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            request.await()
        }
        return permissionStatus()
    }

    internal fun permissionAnswered(granted: Boolean) {
        val context = dependency(AppContext)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
        val request = synchronized(lock) { permissionRequest.also { permissionRequest = null } }
        request?.complete(granted)
    }

    /** The random `ref` a server's pushes carry, made and saved the first time it's asked for. */
    fun ref(key: String): String {
        var made: String? = null
        saved.update { current ->
            val existing = current.refs[key]
            if (existing != null) {
                made = existing
                current
            } else {
                val ref = UUID.randomUUID().toString().lowercase()
                made = ref
                current.copy(refs = current.refs + (key to ref))
            }
        }
        return made ?: saved.value.refs[key].orEmpty()
    }

    fun setting(serverKey: String): PushSetting = saved.value.settings[serverKey] ?: PushSetting()

    /** Saves the choice and tells [server]; off removes this phone from it. False when the server couldn't be told. */
    suspend fun setSetting(server: OpenCodeServer, setting: PushSetting): Boolean {
        val key = server.persistenceID
        return registering.withLock {
            val previous = setting(key)
            saved.update { it.copy(settings = it.settings + (key to setting)) }
            var told = false
            try {
                told = tell(server, key, force = true)
            } finally {
                if (!told) saved.update { it.copy(settings = it.settings + (key to previous)) }
            }
            told
        }
    }

    /**
     * Registers this phone with [server] at its saved level. A server already told the same thing
     * is asked again a day later. False when notifications are off or not allowed, or a step failed.
     */
    suspend fun register(server: OpenCodeServer): Boolean {
        val key = server.persistenceID
        return registering.withLock { setting(key).isOn && tell(server, key, force = false) }
    }

    private suspend fun tell(server: OpenCodeServer, key: String, force: Boolean): Boolean = try {
        withContext(Dispatchers.IO) {
            val setting = setting(key)
            if (setting.isOn) {
                register(server, key, setting.level, force)
            } else {
                OpenCodeClient.removePushDevice(server, ref(key))
                registered.remove(key)
                true
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        AppLog.app.info { "Push device not updated: ${error::class.java.simpleName}" }
        false
    }

    private suspend fun register(server: OpenCodeServer, key: String, level: PushLevel, force: Boolean): Boolean {
        val environment = environment ?: return false
        if (permissionStatus() != NotificationPermission.Enabled) return false
        val token = token() ?: return false
        val ref = ref(key)
        val publicKey = keys.prepare(ref)
        var callback = saved.value.callbacks[key]
        if (callback == null || !callback.isReusable(token, environment, publicKey, RELAY_URL.toHttpUrlOrNull(), Instant.now())) {
            val minted = mint(token, environment, ref) ?: return false
            val made = Callback(minted.url, minted.expiresAt, token, environment, publicKey)
            saved.update { it.copy(callbacks = it.callbacks + (key to made)) }
            callback = made
        }
        val last = registered[key]
        if (!force && last != null && last.callbackURL == callback.url && last.level == level && last.checkAt.isAfter(Instant.now())) return true
        val serverKey = OpenCodeClient.registerPushDevice(
            server, ref, callback.url, callback.expiresAt.toEpochMilli(), publicKey, Build.MODEL ?: "Android", level.wire,
        )
        keys.establish(ref, serverKey)
        registered[key] = Registered(callback.url, level, Instant.now().plusSeconds(86_400))
        AppLog.app.info { "Push device registered" }
        return true
    }

    suspend fun isOffered(server: OpenCodeServer): Boolean? =
        runCatching { OpenCodeClient.offersPush(server) }.onFailure { if (it is CancellationException) throw it }.getOrNull()

    /** Forgets a removed server's ref, callback, choice and notification key. */
    suspend fun forget(serverKey: String) {
        registering.withLock {
            registered.remove(serverKey)
            val ref = saved.value.refs[serverKey]
            saved.update { it.copy(refs = it.refs - serverKey, callbacks = it.callbacks - serverKey, settings = it.settings - serverKey) }
            if (ref != null) runCatching { keys.remove(ref) }
        }
    }

    fun didRegister(token: String) {
        if (token.isNotEmpty()) confirmedToken = token
    }

    /** The saved server a tapped notification's `ref` belongs to, with its session; null when unknown or invalid. */
    fun destination(ref: String, sessionID: String): NotificationDestination? {
        val serverKey = saved.value.refs.entries.firstOrNull { it.value == ref }?.key ?: return null
        return NotificationDestination(serverKey, sessionID).takeIf { it.isValid }
    }

    /** The saved server a push's `ref` belongs to. */
    fun server(ref: String): OpenCodeServer? {
        val key = saved.value.refs.entries.firstOrNull { it.value == ref }?.key ?: return null
        val servers = shared(ServerSharedKeys.savedServers).value + listOfNotNull(shared(ServerSharedKeys.savedServer).value)
        return servers.firstOrNull { it.persistenceID == key }
    }

    private suspend fun token(): String? {
        confirmedToken?.let { return it }
        val token = withTimeoutOrNull(10.seconds) {
            suspendCancellableCoroutine { continuation ->
                tokens.register({ continuation.resume(it) }, { continuation.resume(null) })
            }
        }
        if (token != null) confirmedToken = token
        return token
    }

    companion object {
        /** The relay's origin (`NotifyRelayURL`). */
        const val RELAY_URL = "https://opencode-notify-relay.anomalyco.workers.dev"

        /** The push environment an Android registration asks the relay to seal. */
        const val FCM_ENVIRONMENT = "fcm"

        /**
         * The push environment the relay seals: [FCM_ENVIRONMENT] when the build carries a Firebase
         * configuration (FCM can mint tokens), else null and the build offers no notifications.
         */
        val environment: String? by lazy {
            FCM_ENVIRONMENT.takeIf { FcmPushTokenProvider.isConfigured(dependency(AppContext).applicationContext) }
        }

        /** Whether this build can offer notifications at all. */
        val isAvailable: Boolean get() = environment != null && RELAY_URL.toHttpUrlOrNull()?.isHttps == true

        private const val PREFERENCES = "session-notifications"
        private const val ASKED = "permission-asked"

        private val savedKey = SharedKey.keychain("session-notification-callbacks", Saved())

        val shared: SessionNotifications by lazy { SessionNotifications(FcmPushTokenProvider(dependency(AppContext).applicationContext)) }

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Only a `/v2/notify/<token>` URL on the relay's own HTTPS origin, with no user, password,
         * query or fragment, is ever given to a server as this phone's callback.
         */
        fun isEncryptedCallback(url: String, relay: HttpUrl? = RELAY_URL.toHttpUrlOrNull()): Boolean {
            relay ?: return false
            val parts = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            if (parts.scheme != "https" || parts.host?.lowercase() != relay.host.lowercase()) return false
            if ((parts.port.takeIf { it != -1 } ?: 443) != relay.port) return false
            if (parts.rawUserInfo != null || parts.rawQuery != null || parts.rawFragment != null) return false
            val prefix = "/v2/notify/"
            val path = parts.rawPath ?: return false
            if (!path.startsWith(prefix)) return false
            val token = path.removePrefix(prefix).toByteArray(Charsets.UTF_8)
            return token.size in 1..1024 && token.all {
                it in 65..90 || it in 97..122 || it in 48..57 || it.toInt() == 45 || it.toInt() == 95
            }
        }

        /**
         * `POST /v2/register` on the relay. Fails open within three seconds, never follows a
         * redirect, and sends nothing but the token, environment, and ref.
         */
        internal fun mint(token: String, environment: String, ref: String): Minted? {
            val relay = RELAY_URL.toHttpUrlOrNull() ?: return null
            val body = buildJsonObject {
                put("token", token)
                put("environment", environment)
                put("ref", ref)
            }.toString()
            val client = OkHttpClient.Builder()
                .callTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .cache(null)
                .build()
            val request = Request.Builder()
                .url(relay.newBuilder().addPathSegments("v2/register").build())
                .header("Content-Type", "application/json")
                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            return try {
                client.newCall(request).execute().use { response ->
                    if (response.code != 200) return null
                    val output = json.parseToJsonElement(response.body.string()) as? JsonObject ?: return null
                    val url = output["callbackURL"]?.jsonPrimitive?.content ?: return null
                    val expiresAt = output["expiresAt"]?.jsonPrimitive?.content?.let(::parseDate) ?: return null
                    if (!isEncryptedCallback(url, relay) || !expiresAt.isAfter(Instant.now())) return null
                    Minted(url, expiresAt)
                }
            } catch (_: Exception) {
                // Never log the token, the callback URL, or the response.
                AppLog.app.debug("Notification relay unavailable")
                null
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

        /** The relay writes `Date.toISOString()`, which carries milliseconds. */
        private fun parseDate(value: String): Instant? =
            runCatching { Instant.parse(value) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }
}

/**
 * The push channel's token, behind an interface. The live provider is FCM's
 * ([FcmPushTokenProvider]); it reports failure at once in a build with no Firebase configuration.
 */
fun interface PushTokenProvider {
    /** Asks for the token; exactly one of the callbacks runs, now or later. */
    fun register(onToken: (String) -> Unit, onFailure: () -> Unit)

    companion object {
        /** No push channel: every registration fails at once. */
        val inert: PushTokenProvider = PushTokenProvider { _, onFailure -> onFailure() }
    }
}
