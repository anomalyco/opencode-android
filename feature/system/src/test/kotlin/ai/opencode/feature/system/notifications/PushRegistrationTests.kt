package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodePushBell
import ai.opencode.server.model.OpenCodePushDevice
import ai.opencode.server.model.OpenCodePushLevel
import ai.opencode.server.model.OpenCodePushState
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import android.app.NotificationManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.days

/** Registration once per server: one token request, a callback reused until three days are left. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushRegistrationTests {
    @get:Rule val isolation = TestIsolationRule()

    private val start = Instant.parse("2026-11-01T00:00:00Z")
    private val relay = SessionNotifications.RELAY_URL.trimEnd('/')

    @Test
    fun aClearRemovesOnlyTheNotificationPostedForItsID() {
        val context = RuntimeEnvironment.getApplication()
        val ref = "12345678-1234-1234-1234-123456789abc"
        val key = ByteArray(32) { it.toByte() }
        fun handle(message: String) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val nonce = ByteArray(12)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD("${NotificationCrypto.CONTEXT}:$ref".toByteArray(Charsets.UTF_8))
            val box = Base64.getEncoder().encodeToString(nonce + cipher.doFinal(message.toByteArray(Charsets.UTF_8)))
            val data = mapOf(NotificationContent.ENVELOPE_KEY to """{"v":2,"ref":"$ref","box":"$box"}""")
            withDependencies({
                this[SharedStore] = SharedStore.inMemory()
                this[AppContext] = context
            }) { SessionPushNotifications.handle(context, data) { key } }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        fun shown() = manager.activeNotifications.map { it.tag }.sorted()

        handle("""{"v":1,"id":"per_a","type":"permission","sessionID":"ses_a","title":"Fix","body":"Needs permission: bash","data":{}}""")
        handle("""{"v":1,"id":"ses_b","type":"session.idle","sessionID":"ses_b","title":"Docs","body":"Finished","data":{}}""")
        assertEquals(listOf("ses_a", "ses_b"), shown())
        // A clear for something no longer showing posts nothing.
        handle("""{"v":1,"id":"per_gone","type":"clear","sessionID":"ses_a"}""")
        assertEquals(listOf("ses_a", "ses_b"), shown())
        handle("""{"v":1,"id":"per_a","type":"clear","sessionID":"ses_a"}""")
        assertEquals(listOf("ses_b"), shown())
        // A later notification for the session replaced the one the clear names, so it stays.
        handle("""{"v":1,"id":"frm_c","type":"question","sessionID":"ses_b","title":"Docs","body":"Which one?","data":{}}""")
        handle("""{"v":1,"id":"ses_b","type":"clear","sessionID":"ses_b"}""")
        assertEquals(listOf("ses_b"), shown())
        handle("""{"v":1,"id":"frm_c","type":"clear","sessionID":"ses_b"}""")
        assertEquals(emptyList<String>(), shown())
    }

    @Test
    fun registrationReusesTheCallbackUntilItNeedsRenewing() = runBlocking {
        val tokenRequests = AtomicInteger(0)
        val mints = AtomicInteger(0)
        val established = mutableListOf<String>()
        val notifications = SessionNotifications(
            tokens = PushTokenProvider { onToken, _ ->
                tokenRequests.incrementAndGet()
                onToken("token-1")
            },
            mintCallback = { _, _ ->
                val count = mints.incrementAndGet()
                SessionNotifications.Minted("$relay/v2/notify/sealed$count", start.plusMillis(30.days.inWholeMilliseconds))
            },
        )
        val fixture = WireFixture { request ->
            if (request.method == "PUT") Reply.Json(200, """{"data":{"publicKey":"server-key"}}""") else Reply.status(404)
        }
        var now = start
        try {
            withDependenciesSuspending({
                this[SharedStore] = SharedStore.inMemory()
                this[AppContext] = RuntimeEnvironment.getApplication()
                this[DateClient] = DateClient { now }
                this[OpenCodeClient] = OpenCodeClient.liveValue
                this[NotificationKeysClient] = NotificationKeysClient(
                    prepare = { "phone-key" },
                    establish = { _, key -> established += key },
                    remove = {},
                    verify = { null },
                )
            }) {
                // Four callers at once ask the push channel for one token.
                val results = coroutineScope {
                    val calls = (1..4).map { async { notifications.register(fixture.server, OpenCodePushLevel.all) } }
                    calls.awaitAll()
                }
                assertEquals(listOf(true, true, true, true), results)
                assertEquals(1, tokenRequests.get())
                assertEquals(1, mints.get())

                // A later connect keeps the callback while more than three days are left.
                now = start.plusMillis(26.days.inWholeMilliseconds)
                assertTrue(notifications.register(fixture.server, OpenCodePushLevel.none))
                assertEquals(1, mints.get())

                now = start.plusMillis(28.days.inWholeMilliseconds)
                assertTrue(notifications.register(fixture.server, OpenCodePushLevel.none))
                assertEquals(2, mints.get())

                // A new token needs a new callback.
                notifications.didChangeToken("token-2")
                assertTrue(notifications.register(fixture.server, OpenCodePushLevel.none))
                assertEquals(3, mints.get())
            }
            val puts = fixture.requests.filter { it.method == "PUT" }
            assertEquals(7, puts.size)
            val path = puts.first().path
            assertTrue(path.startsWith("/api/experimental/push/device/"))
            for (put in puts) assertEquals(path, put.path)
            val last = puts.last().bodyText
            assertTrue(last, last.contains("\"callbackURL\":\"$relay/v2/notify/sealed3\""))
            assertTrue(last, last.contains("\"settings\":{\"default\":\"none\"}"))
            assertEquals(7, established.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun aFailedRegistrationReportsFalse() = runBlocking {
        val notifications = SessionNotifications(
            tokens = PushTokenProvider { onToken, _ -> onToken("token-1") },
            mintCallback = { _, _ -> null },
        )
        withDependenciesSuspending({
            this[SharedStore] = SharedStore.inMemory()
            this[AppContext] = RuntimeEnvironment.getApplication()
            this[NotificationKeysClient] = NotificationKeysClient(prepare = { "phone-key" }, establish = { _, _ -> }, remove = {}, verify = { null })
        }) {
            val server = OpenCodeServer(url = "https://unused.invalid", username = "", password = "")
            assertFalse(notifications.register(server, OpenCodePushLevel.all))
        }
    }
}

/** The bell a send turns on. */
class PushBellTests {
    private val device = OpenCodePushDevice(
        id = "ref-1", name = "Pixel", platform = "android", expiresAt = 1,
        defaultLevel = OpenCodePushLevel.attention, paused = false, sessions = emptyMap(),
    )

    @Test
    fun aSendTurnsThisPhonesBellOnUnlessItHasOneThatStillHolds() = runBlocking {
        val testServer = OpenCodeServer(url = "https://unused.invalid", username = "", password = "")
        val sessions = mapOf(
            "ses_own" to OpenCodePushBell(OpenCodePushLevel.none),
            "ses_ended" to OpenCodePushBell(OpenCodePushLevel.none, until = 500),
        )
        val written = mutableListOf<String>()
        withDependenciesSuspending({
            this[SharedStore] = SharedStore.inMemory()
            this[DateClient] = DateClient { Instant.ofEpochMilli(1_000) }
            this[SessionNotificationsClient] = SessionNotificationsClient(isAvailable = true, ref = { "ref-1" })
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                pushState = { OpenCodePushState(devices = listOf(device.copy(sessions = sessions))) },
                setPushSessionBell = { _, _, sessionID, bell ->
                    assertEquals(OpenCodePushBell(OpenCodePushLevel.all), bell)
                    written += sessionID
                },
            )
        }) {
            PushSync.bellAfterSend(testServer, "ses_new")
            assertEquals(emptyList<String>(), written)

            val choices = shared(PushSharedKeys.choices)
            choices.set(mapOf(testServer.persistenceID to PushChoice(mode = PushMode.fromThisPhone)))
            for (sessionID in listOf("ses_new", "ses_own", "ses_ended")) PushSync.bellAfterSend(testServer, sessionID)
            assertEquals(listOf("ses_new", "ses_ended"), written)
        }
    }
}
