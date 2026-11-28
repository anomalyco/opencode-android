package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.testing.TestIsolationRule
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
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

/**
 * Dropped: `withoutAnAppGroupTheLedgerIsNeitherReadNorWritten` (the ledger lives in the app's own
 * preferences; there is no App Group), `badgesAreAddedOnlyToAFullGrantWithoutThem`,
 * `aPermissionGrantedLaterInTheSameRunStillGetsBadges`, `callsWhileOneRunsShareIt`
 * (`BadgeAuthorization`: Android has no separate badge authorization; the notification permission
 * covers the badge).
 */
class AppIconBadgeTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun ledger() = AppBadgeLedger(
        serverKey = "0123456789ab", ref = "ref", sessionIDs = listOf("a"), mutedSessionIDs = listOf("m"), viewingSessionIDs = listOf("v"),
    )

    @Test
    fun aPushCountsItsSessionOnce() {
        var ledger = ledger()
        fun push(id: String, ref: String): Int? = ledger.badgeAfterPush(id, ref)?.let { (next, badge) -> ledger = next; badge }
        assertEquals(2, push("b", "ref"))
        assertEquals(2, push("b", "ref"))
        assertEquals("Already counted", 2, push("a", "ref"))
        assertEquals(listOf("a", "b"), ledger.sessionIDs)
    }

    @Test
    fun pushesThatDontCountLeaveTheBadge() {
        var ledger = ledger()
        fun push(id: String, ref: String): Int? = ledger.badgeAfterPush(id, ref)?.let { (next, badge) -> ledger = next; badge }
        assertNull(push("b", "another server"))
        assertNull("Muted", push("m", "ref"))
        assertNull("On screen", push("v", "ref"))
        ledger = ledger.copy(ref = null)
        assertNull("No ref minted yet", push("b", "ref"))
        assertEquals(listOf("a"), ledger.sessionIDs)
    }

    /** An in-memory `SharedPreferences`. */
    private class MemoryPreferences : SharedPreferences {
        val values = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val changes = HashMap<String, Any?>()
            val removals = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { removals += key }
            override fun clear() = apply { removals += values.keys }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                removals.forEach(values::remove)
                values.putAll(changes)
            }
        }
    }

    @Test
    fun theLedgerIsSavedUnderAHashOnly() {
        val defaults = MemoryPreferences()
        val persistenceID = "kit|http://192.168.1.20:4096|https://box.tailnet.ts.net"
        val ledger = ledger().copy(serverKey = AppBadgeLedger.serverKey(persistenceID))
        ledger.save(defaults)
        val text = defaults.getString(AppBadgeLedger.KEY, null)!!
        assertFalse(text.contains("kit|") || text.contains("192.168") || text.contains("tailnet"))
        assertEquals(ledger, AppBadgeLedger.load(defaults))

        // A plaintext key is never written.
        val plaintext = ledger.copy(serverKey = persistenceID)
        AppBadgeLedger.remove(defaults)
        plaintext.save(defaults)
        assertNull(defaults.getString(AppBadgeLedger.KEY, null))
    }

    @Test
    fun aLedgerLeftWithAPlaintextKeyIsRemovedOnLoad() {
        val defaults = MemoryPreferences()
        val legacy = ledger().copy(serverKey = "kit|http://192.168.1.20:4096")
        defaults.edit().putString(AppBadgeLedger.KEY, Json.encodeToString(AppBadgeLedger.serializer(), legacy)).apply()
        assertNull(AppBadgeLedger.load(defaults))
        assertNull("Migrated by clearing", defaults.getString(AppBadgeLedger.KEY, null))
    }

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
