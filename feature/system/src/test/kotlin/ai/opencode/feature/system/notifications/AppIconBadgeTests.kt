package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.testing.TestIsolationRule
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AppIconBadgeTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun aServersRefIsMadeOnceAndKept() {
        val values = DependencyValues.testing().update { this[SharedStore] = SharedStore.inMemory() }
        DependencyValues.withValues(values) {
            val notifications = SessionNotifications(PushTokenProvider { _, _ -> })
            val first = notifications.ref("server")
            // Saved as soon as it's made: another instance over the same storage reads it back.
            assertEquals("Saved as soon as it's made, before any relay registration", first, SessionNotifications(PushTokenProvider { _, _ -> }).ref("server"))
            assertEquals("The callback minted later seals the same one", first, notifications.ref("server"))
            assertNotEquals(first, notifications.ref("other"))
            UUID.fromString(first)
        }
    }

    // MARK: Pushes while the app isn't live

    // Node crypto ECDH/HKDF/AES-GCM vector: private scalars 1 and 2, nonce 00…0b.
    // Synthetic keys only; this also pins UTF-8, padded base64 and wire ordering.
    private val encryptedRef = "12345678-1234-1234-1234-123456789abc"
    private val encryptedBox = "AAECAwQFBgcICQoLC4dLgkSzfl+g7LggBOtf+eL9xUfFuLf1Ai72Az6QxvbU2YWVm9hclKaJq8v2Yxcnmgv5Cdv/yeOoUC/38P5ewf6O/jRfz49VMw8i5kbLquE3b9mMZsvK58cOO5Gb8X5Br4bkiHlIpDXU0fXcyUJ3L2zNkhs0A/4Nem+tPmj/kMT3E94fwLruWg5HtDOzYER4G/X/NjZbdtArchD7gAnz/YWFd2e01JwZ"

    private fun notificationKey(): ByteArray = NotificationCrypto.sharedKey(
        privateKey = NotificationCrypto.privateKey(ByteArray(31) + byteArrayOf(1)),
        publicKey = "BHzyexiNA09+ilI4AwS1GsPAiWnid/IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E=",
        ref = encryptedRef,
    )

    private fun message(type: String, sessionID: String, title: String, body: String): String =
        """{"v":1,"id":"frm_1","type":"$type","sessionID":"$sessionID","title":"$title","body":"$body","data":{}}"""

    /** Nonce, ciphertext and tag. */
    private fun seal(text: String, key: ByteArray, ref: String): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("${NotificationCrypto.CONTEXT}:$ref".toByteArray(Charsets.UTF_8))
        return nonce + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    private fun base64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun envelope(ref: String, box: String?, vararg extra: Pair<String, String>): Map<String, String> {
        val fields = listOfNotNull("\"v\":2", "\"ref\":\"$ref\"", box?.let { "\"box\":\"$it\"" }) + extra.map { (k, v) -> "\"$k\":\"$v\"" }
        return mapOf(NotificationContent.ENVELOPE_KEY to fields.joinToString(",", "{", "}"))
    }

    private fun assertFails(block: () -> Unit) {
        val failed = runCatching(block).isFailure
        assertTrue("Expected a failure", failed)
    }

    /**
     * The JVM has no `AndroidKeyStore`, so the store runs over
     * [NotificationKeyStore.Storage.inMemory] with the live cache.
     */
    @Test
    fun deviceBoundNotificationKeySurvivesRepeatingEnrollmentAndIsForgotten() {
        val ref = UUID.randomUUID().toString().lowercase()
        val store = NotificationKeyStore(NotificationKeyStore.Storage.inMemory(), NotificationKeyCache())
        val phone = store.prepare(ref)
        assertEquals(phone, store.prepare(ref))
        val (senderRaw, senderPublic) = NotificationCrypto.generateKeyPair()
        val sender = NotificationCrypto.privateKey(senderRaw)
        store.establish(ref, base64(senderPublic))
        val peer = NotificationCrypto.sharedKey(sender, phone, ref)
        assertArrayEquals(peer, store.sharedKey(ref))
        val remote = envelope(ref, base64(seal(message("question", "ses_device", "Ready", "Review the change"), peer, ref)))
        val key: (String) -> ByteArray = { store.sharedKey(it) }
        assertEquals(
            NotificationCrypto.Payload(type = "question", sessionID = "ses_device", title = "Ready", body = "Review the change", id = "frm_1"),
            NotificationContent.verifiedPayload(remote, key)?.payload,
        )
        // Pairing again replaces the cached key.
        val (resenderRaw, resenderPublic) = NotificationCrypto.generateKeyPair()
        store.establish(ref, base64(resenderPublic))
        assertArrayEquals(NotificationCrypto.sharedKey(NotificationCrypto.privateKey(resenderRaw), phone, ref), store.sharedKey(ref))
        assertNull(NotificationContent.verifiedPayload(remote, key))
        store.remove(ref)
        assertNull(NotificationContent.verifiedPayload(remote, key))
        assertThrows(NotificationKeyStore.Failure::class.java) { store.sharedKey(ref) }
    }

    @Test
    fun encryptedNotificationMatchesNodeCrypto() {
        val payload = NotificationCrypto.open(encryptedBox, encryptedRef, notificationKey())
        assertEquals(NotificationCrypto.Payload(type = "session.idle", sessionID = "ses_interop", title = "Ready ✓", body = "A private result.", id = "ses_interop"), payload)
    }

    @Test
    fun encryptedNotificationRejectsWrongKeyRefAndTampering() {
        val key = notificationKey()
        assertFails { NotificationCrypto.open(encryptedBox, encryptedRef, ByteArray(32).also { SecureRandom().nextBytes(it) }) }
        assertFails { NotificationCrypto.open(encryptedBox, "12345678-1234-1234-1234-123456789abd", key) }
        val tampered = Base64.getDecoder().decode(encryptedBox)
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        assertFails { NotificationCrypto.open(base64(tampered), encryptedRef, key) }
        for (box in listOf("", "not base64", base64(ByteArray(27)), "A".repeat(3604))) {
            assertFails { NotificationCrypto.open(box, encryptedRef, key) }
        }
        assertFails { NotificationCrypto.validate(encryptedRef.uppercase()) }
    }

    @Test
    fun notificationBoxLimitCountsDecodedBytes() {
        val key = notificationKey()
        val overhead = message("session.idle", "ses_limit", "Ready", "x").toByteArray().size - 1
        var body = "x".repeat(2700 - 28 - overhead)
        val bytes = seal(message("session.idle", "ses_limit", "Ready", body), key, encryptedRef)
        assertEquals(2700, bytes.size)
        assertEquals(3600, base64(bytes).toByteArray().size)
        assertEquals(body, NotificationCrypto.open(base64(bytes), encryptedRef, key).body)
        body += "x"
        val box = base64(seal(message("session.idle", "ses_limit", "Ready", body), key, encryptedRef))
        assertFails { NotificationCrypto.open(box, encryptedRef, key) }
    }

    /** Android's notification is built from [NotificationContent.Shown] alone, so a forged sibling or badge has nowhere to go. */
    @Test
    fun onlyAuthenticatedFieldsRenderAndTheEnvelopeStaysEncrypted() {
        val original = envelope(encryptedRef, encryptedBox, "sessionID" to "ses_forged", "status" to "failed") +
            mapOf("title" to "Untrusted title", "opencode-share" to "{\"sessionID\":\"ses_forged\"}")
        val key = notificationKey()
        val verified = requireNotNull(NotificationContent.verifiedPayload(original) { key })
        assertEquals(encryptedRef, verified.ref)
        assertEquals("ses_interop", verified.payload.sessionID)
        val content = requireNotNull(NotificationContent.content(original, verified))
        assertEquals("Ready ✓", content.title)
        assertEquals("A private result.", content.body)
        assertEquals("$encryptedRef:ses_interop", content.thread)
        val envelope = requireNotNull(content.envelope)
        assertEquals(setOf("v", "ref", "box"), envelope.keys)
        assertEquals(encryptedBox, envelope["box"]?.jsonPrimitive?.content)
        val again = mapOf(NotificationContent.ENVELOPE_KEY to envelope.toString())
        assertEquals(verified.payload, NotificationContent.verifiedPayload(again) { key }?.payload)
    }

    @Test
    fun unavailableKeysMissingBoxesAndTimeoutStayGeneric() {
        val original = envelope(encryptedRef, encryptedBox, "sessionID" to "ses_forged") + mapOf("title" to "Unverified title")
        val blocked = NotificationContent.verifiedPayload(original) { throw NotificationKeyStore.Failure.MissingKey }
        assertNull(blocked)
        // The same fallback is delivered on timeout; the app can retry the box later.
        val fallback = requireNotNull(NotificationContent.content(original, blocked))
        assertEquals("OpenCode", fallback.title)
        assertEquals("New activity", fallback.body)
        assertNull(fallback.thread)
        val envelope = requireNotNull(fallback.envelope)
        assertNull(envelope["sessionID"])
        assertEquals(encryptedBox, envelope["box"]?.jsonPrimitive?.content)
        val key = notificationKey()
        assertNull(NotificationContent.verifiedPayload(envelope(encryptedRef, null, "sessionID" to "ses_forged")) { key })
    }

    @Test
    fun notificationKeysAreReadOnceUntilTheKeyChanges() {
        val cache = NotificationKeyCache()
        var loads = 0
        fun key(): ByteArray = cache.key(encryptedRef) {
            loads += 1
            ByteArray(32) { loads.toByte() }
        }
        val first = key()
        assertArrayEquals(first, key())
        assertEquals(1, loads)
        cache.invalidate(encryptedRef)
        assertFalse(key().contentEquals(first))
        assertEquals(2, loads)
        // A read that overlaps a rewrite may hold the old key, so it isn't kept.
        val stale = cache.key(encryptedRef + "x") {
            cache.invalidate(encryptedRef + "x")
            first
        }
        assertArrayEquals(first, stale)
        val fresh = cache.key(encryptedRef + "x") { ByteArray(32).also { SecureRandom().nextBytes(it) } }
        assertFalse(fresh.contentEquals(first))
    }

    @Test
    fun messagesFollowTheContract() {
        val idle = NotificationCrypto.Payload.parse(
            """{"v":1,"id":"ses_a","type":"session.idle","sessionID":"ses_a","title":"Fix the login bug","body":"Finished","data":{"outcome":"succeeded"},"extra":true}""",
        )
        assertEquals(NotificationCrypto.Payload("session.idle", "ses_a", "Fix the login bug", "Finished", "ses_a"), idle)
        val permission = NotificationCrypto.Payload.parse("""{"v":1,"id":"per_1","type":"permission","sessionID":"ses_a","title":"Run tests?","body":"bun test","data":{"requestID":"per_1","action":"bash"}}""")
        assertEquals("permission", permission?.type)
        assertEquals("per_1", permission?.id)
        // A clear has no title or body; it names the message it removes.
        assertEquals(
            NotificationCrypto.Payload("clear", "ses_a", "", "", "per_1"),
            NotificationCrypto.Payload.parse("""{"v":1,"id":"per_1","type":"clear","sessionID":"ses_a"}"""),
        )
        // Every message names itself, and a known type needs a session and a title.
        assertNull(NotificationCrypto.Payload.parse("""{"v":1,"type":"question","sessionID":"ses_a","title":"Which one?","body":""}"""))
        assertNull(NotificationCrypto.Payload.parse("""{"v":1,"id":"frm_1","type":"question","title":"Which one?","body":""}"""))
        assertNull(NotificationCrypto.Payload.parse("""{"v":1,"id":"frm_1","type":"question","sessionID":"ses_a","title":"","body":"x"}"""))
        // Another version, or not JSON at all, can't be read.
        assertNull(NotificationCrypto.Payload.parse("""{"v":2,"type":"session.idle","sessionID":"ses_a","title":"t","body":"b"}"""))
        assertNull(NotificationCrypto.Payload.parse("not json"))

        // An unknown type shows its title and body, and nothing without a title.
        val verified = { text: String ->
            NotificationContent.Verified(encryptedRef, encryptedBox, requireNotNull(NotificationCrypto.Payload.parse(text)))
        }
        val unknown = NotificationContent.content(emptyMap(), verified("""{"v":1,"id":"x_1","type":"later.kind","title":"Deploy done","body":"prod"}"""))
        assertEquals("Deploy done", unknown?.title)
        assertEquals("prod", unknown?.body)
        assertNull(unknown?.thread)
        assertEquals(SystemNotificationChannels.SESSION_OTHER, SystemNotificationChannels.forType(unknown?.type))
        assertNull(NotificationContent.content(emptyMap(), verified("""{"v":1,"id":"x_1","type":"later.kind","body":"quiet"}""")))
        assertEquals(SystemNotificationChannels.SESSION_QUESTIONS, SystemNotificationChannels.forType("question"))
        assertEquals(SystemNotificationChannels.SESSION_PERMISSIONS, SystemNotificationChannels.forType("permission"))
        assertEquals(SystemNotificationChannels.SESSION_FINISHED, SystemNotificationChannels.forType("session.idle"))

        // A box that opens but isn't a message shows the generic notification.
        val key = notificationKey()
        val garbled = Base64.getEncoder().encodeToString(seal("not json", key, encryptedRef))
        val data = envelope(encryptedRef, garbled)
        val opened = NotificationContent.verifiedPayload(data) { key }
        assertNull(opened)
        assertEquals("New activity", NotificationContent.content(data, opened)?.body)
    }

    @Test
    fun clearingReadsTheVerifiedThreadWithoutTheKeychain() {
        fun envelopeOf(ref: String = encryptedRef) = NotificationContent.Envelope(ref, encryptedBox)
        fun content(thread: String, ref: String = encryptedRef) = SessionPushNotifications.Delivered(
            group = thread,
            envelope = "{\"v\":2,\"ref\":\"$ref\",\"box\":\"$encryptedBox\"}",
        )
        val noKeychain: (String) -> ByteArray = { error("The key store isn't read") }
        val verified = content(thread = "$encryptedRef:ses_interop")
        assertEquals("ses_interop", NotificationContent.threadSessionID("$encryptedRef:ses_interop", envelopeOf()))
        assertEquals("ses_interop", SessionPushNotifications.sessionID(verified, noKeychain))
        assertFalse(SessionPushNotifications.isNeedsInput(verified))
        assertTrue(SessionPushNotifications.isNeedsInput(verified.copy(status = "question")))
        // Only a thread the extension wrote for this envelope's ref counts.
        assertNull(NotificationContent.threadSessionID("ses_interop", envelopeOf()))
        assertNull(NotificationContent.threadSessionID("12345678-1234-1234-1234-123456789abd:ses_interop", envelopeOf()))
        assertNull(NotificationContent.threadSessionID("$encryptedRef:not-a-session", envelopeOf()))
        assertNull(NotificationContent.threadSessionID("$encryptedRef:", envelopeOf()))
        val v1 = NotificationContent.Envelope.from(mapOf(NotificationContent.ENVELOPE_KEY to "{\"sessionID\":\"ses_v1\"}"))
        assertNull(NotificationContent.threadSessionID("$encryptedRef:ses_interop", v1))
        val v1Content = SessionPushNotifications.Delivered(group = "$encryptedRef:ses_interop", sessionID = "ses_v1")
        assertEquals("ses_v1", SessionPushNotifications.sessionID(v1Content, noKeychain))
    }
}
