package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.test.runTest
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
import kotlinx.serialization.json.Json

class AppIconBadgeTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun aServersRefIsMadeOnceAndKept() {
        val values = DependencyValues.testing().update { this[SharedStore] = SharedStore.inMemory() }
        DependencyValues.withValues(values) {
            val notifications = SessionNotifications { _, _ -> }
            val first = notifications.ref("server")
            // Saved as soon as it's made: another instance over the same storage reads it back.
            assertEquals("Saved as soon as it's made, before any relay registration", first, SessionNotifications { _, _ -> }.ref("server"))
            assertEquals("The callback minted later seals the same one", first, notifications.ref("server"))
            assertNotEquals(first, notifications.ref("other"))
            UUID.fromString(first)
        }
    }

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    @Test
    fun turningOffAServerThisPhoneNeverRegisteredWithSendsNothing() = runTest {
        // The inert OpenCodeClient fails the test if a request is sent.
        withDependenciesSuspending({ this[SharedStore] = SharedStore.inMemory() }) {
            val notifications = SessionNotifications { _, _ -> }
            assertTrue(notifications.setSetting(server, PushSetting(isOn = false)))
            assertEquals(PushSetting(isOn = false), notifications.setting(server.persistenceID))
            assertNull(notifications.savedRef(server.persistenceID))
        }
    }

    @Test
    fun aChoiceTheServerDidntAcceptIsNotSaved() = runTest {
        withDependenciesSuspending({
            this[SharedStore] = SharedStore.inMemory()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(removePushDevice = { _, _ -> throw OpenCodeClientError("Unreachable") })
        }) {
            val notifications = SessionNotifications { _, _ -> }
            notifications.ref(server.persistenceID)
            assertFalse(notifications.setSetting(server, PushSetting(isOn = false)))
            assertEquals(PushSetting(), notifications.setting(server.persistenceID))
        }
    }

    // MARK: Pushes while the app isn't live

    // Node crypto ECDH/HKDF/AES-GCM vector: private scalars 1 and 2, nonce 00…0b.
    // Synthetic keys only; this also pins UTF-8, padded base64 and wire ordering.
    private val encryptedRef = "12345678-1234-1234-1234-123456789abc"
    private val encryptedBox = "AAECAwQFBgcICQoLC4dJyQruN1/zqsh/R/xDql8IOBGM6LrqRHv2TWWhg6SckoCRnM4Via3Ut8PuKBcnmgv5Cdv/yeOoUC/38P5ewf6O/jRfz49VMw8i4VvesvFmd8H8cN/N/YKJw2fd/yHT3bVOTwVKrJEpuEP3ljI3"

    private fun notificationKey(): ByteArray = NotificationCrypto.sharedKey(
        privateKey = NotificationCrypto.privateKey(ByteArray(31) + byteArrayOf(1)),
        publicKey = "BHzyexiNA09+ilI4AwS1GsPAiWnid/IbNaYLSPxHZpl4B3dVENuO0EApPZrGn3Qw27p9reY86YIpngS3nSJ4c9E=",
        ref = encryptedRef,
    )

    /** Nonce, ciphertext and tag. */
    private fun seal(text: String, key: ByteArray, ref: String): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("${NotificationCrypto.CONTEXT}:$ref".toByteArray(Charsets.UTF_8))
        return nonce + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    private fun base64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

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
        val box = base64(seal("Review the change", peer, ref))
        assertEquals("Review the change", NotificationCrypto.openText(box, ref, store.sharedKey(ref)))
        // Pairing again replaces the cached key.
        val (resenderRaw, resenderPublic) = NotificationCrypto.generateKeyPair()
        store.establish(ref, base64(resenderPublic))
        assertArrayEquals(NotificationCrypto.sharedKey(NotificationCrypto.privateKey(resenderRaw), phone, ref), store.sharedKey(ref))
        assertFails { NotificationCrypto.openText(box, ref, store.sharedKey(ref)) }
        store.remove(ref)
        assertThrows(NotificationKeyStore.Failure::class.java) { store.sharedKey(ref) }
    }

    @Test
    fun encryptedNotificationMatchesNodeCrypto() {
        val text = NotificationCrypto.openText(encryptedBox, encryptedRef, notificationKey())
        assertEquals(
            Json.parseToJsonElement("""{"title":"Ready ✓","body":"A private result.","sessionID":"ses_interop","status":"succeeded"}"""),
            Json.parseToJsonElement(text),
        )
    }

    @Test
    fun encryptedNotificationRejectsWrongKeyRefAndTampering() {
        val key = notificationKey()
        assertFails { NotificationCrypto.openText(encryptedBox, encryptedRef, ByteArray(32).also { SecureRandom().nextBytes(it) }) }
        assertFails { NotificationCrypto.openText(encryptedBox, "12345678-1234-1234-1234-123456789abd", key) }
        val tampered = Base64.getDecoder().decode(encryptedBox)
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        assertFails { NotificationCrypto.openText(base64(tampered), encryptedRef, key) }
        for (box in listOf("", "not base64", base64(ByteArray(27)), "A".repeat(3604))) {
            assertFails { NotificationCrypto.openText(box, encryptedRef, key) }
        }
        assertFails { NotificationCrypto.validate(encryptedRef.uppercase()) }
    }

    @Test
    fun notificationBoxLimitCountsDecodedBytes() {
        val key = notificationKey()
        val text = "x".repeat(2700 - 28)
        val bytes = seal(text, key, encryptedRef)
        assertEquals(2700, bytes.size)
        assertEquals(3600, base64(bytes).toByteArray().size)
        assertEquals(text, NotificationCrypto.openText(base64(bytes), encryptedRef, key))
        val box = base64(seal(text + "x", key, encryptedRef))
        assertFails { NotificationCrypto.openText(box, encryptedRef, key) }
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
}
