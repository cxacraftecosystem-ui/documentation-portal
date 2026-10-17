package com.fieldrepository.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A typed Aadhaar number never reaches this phone's storage in the clear (2026-10-17).
 *
 * The outbox and the artisan form's draft hold it sealed (AES-GCM under a Keystore key on a device;
 * a software AES key stands in for the Keystore here), the plaintext comes back only in memory for the
 * send, a sent entry leaves nothing behind, and when sealing is impossible the number is not written
 * at all.
 */
class AadhaarAtRestTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val number = "234567890124"

    private class SoftwareSealer : AadhaarAtRest.Sealer {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plain)
        }
        override fun open(sealed: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    private object BrokenKeystore : AadhaarAtRest.Sealer {
        override fun seal(plain: ByteArray): ByteArray = throw IllegalStateException("no keystore")
        override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("no keystore")
    }

    @Before
    fun useSoftwareKey() {
        AadhaarAtRest.sealerForTests = SoftwareSealer()
    }

    @After
    fun restore() {
        AadhaarAtRest.sealerForTests = null
    }

    private fun body(aadhaar: String?) =
        """{"name":"Kalu Ram","place":"Bagru",""" +
            (if (aadhaar != null) """"aadhaarNumber":"$aadhaar",""" else "") +
            """"formContract":2}"""

    @Test
    fun a_stored_outbox_body_holds_no_number_and_opens_to_the_same_bytes() {
        val original = body(number)
        val stored = AadhaarAtRest.sealPayload(original)
        assertFalse(stored.contains(number))
        assertFalse(stored.contains("\"aadhaarNumber\""))
        assertTrue(stored.contains(AadhaarAtRest.SEALED_FIELD))
        // Byte for byte, so a replay under the same Idempotency-Key is the same request.
        assertEquals(original, AadhaarAtRest.openPayload(stored))
    }

    @Test
    fun a_body_without_a_typed_number_is_left_exactly_as_it_was() {
        for (unchanged in listOf(body(null), body(""), body("XXXX XXXX 0124"), "not json", "[1,2]")) {
            assertEquals(unchanged, AadhaarAtRest.sealPayload(unchanged))
            assertEquals(unchanged, AadhaarAtRest.openPayload(unchanged))
        }
    }

    @Test
    fun without_a_keystore_the_number_is_withheld_and_never_written() {
        AadhaarAtRest.sealerForTests = BrokenKeystore
        val stored = AadhaarAtRest.sealPayload(body(number))
        assertFalse(stored.contains(number))
        assertTrue(AadhaarAtRest.numberWithheld(stored))
        assertTrue(stored.contains("\"${AadhaarAtRest.WITHHELD_FIELD}\":\"0124\""))
        // Sent without the number, so the server asks for it again rather than receiving a guess.
        assertEquals("""{"name":"Kalu Ram","place":"Bagru","formContract":2}""", AadhaarAtRest.openPayload(stored))
    }

    @Test
    fun a_sealed_number_whose_key_is_gone_is_left_out_rather_than_sent_wrong() {
        val stored = AadhaarAtRest.sealPayload(body(number))
        AadhaarAtRest.sealerForTests = SoftwareSealer() // a different key: the old one is gone
        assertFalse(AadhaarAtRest.openPayload(stored).contains("aadhaarNumber"))
    }

    @Test
    fun the_outbox_file_never_holds_the_number_and_a_sent_entry_leaves_nothing() {
        val file = File(folder.root, "queue.json")
        val entry = PendingEntry(
            id = "e1",
            type = "artisan",
            payloadJson = body(number),
            label = "Kalu Ram",
            createdAt = "2026-10-17T00:00:00Z",
        )
        OfflineQueueFile.write(file, listOf(entry))
        assertFalse("the queue file holds the typed number", file.readText().contains(number))
        // In memory, for the send, it is the number that was typed, byte for byte.
        assertEquals(body(number), OfflineQueueFile.read(file) {}.single().payloadJson)

        OfflineQueueFile.write(file, emptyList())
        assertFalse(file.readText().contains(number))
        assertFalse(file.readText().contains(AadhaarAtRest.SEALED_FIELD))
    }

    private fun draft(aadhaar: String) = FormDraftFile(
        formKind = "artisan",
        recordKey = "new-none-1",
        savedAt = "2026-10-17T00:00:00Z",
        fields = mapOf(
            "name" to DraftValues.encode("Kalu Ram")!!,
            "aadhaar" to DraftValues.encode(aadhaar)!!,
        ),
    )

    @Test
    fun the_artisan_form_draft_keeps_the_number_sealed_and_gives_it_back() {
        val root = folder.newFolder("drafts")
        DraftFiles.write(root, "u1", draft(number))
        val onDisk = root.walkTopDown().filter { it.isFile }.joinToString(" ") { it.readText() }
        assertFalse("the draft file holds the typed number", onDisk.contains(number))
        val back = DraftFiles.read(root, "u1", "artisan", "new-none-1")!!
        assertEquals(number, DraftValues.decode(back.fields.getValue("aadhaar")))
    }

    @Test
    fun without_a_keystore_the_draft_drops_the_number() {
        AadhaarAtRest.sealerForTests = BrokenKeystore
        val root = folder.newFolder("drafts")
        DraftFiles.write(root, "u1", draft(number))
        val onDisk = root.walkTopDown().filter { it.isFile }.joinToString(" ") { it.readText() }
        assertFalse(onDisk.contains(number))
        assertEquals(null, DraftFiles.read(root, "u1", "artisan", "new-none-1")!!.fields["aadhaar"])
    }
}
