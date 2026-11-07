package com.fieldrepository.app.questionnaire.portal

import com.fieldrepository.app.data.AadhaarAtRest
import com.fieldrepository.app.questionnaire.ui.NewArtisan
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class QuestionnaireOutboxAadhaarTest {
    private val number = "999999990019"

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

    @Before
    fun useSoftwareKey() {
        AadhaarAtRest.sealerForTests = SoftwareSealer()
    }

    @After
    fun restore() {
        AadhaarAtRest.sealerForTests = null
    }

    private fun newArtisan() = NewArtisan(name = "Test Artisan", place = "Testville", aadhaar = number, dos = "c", donts = "d")

    @Test
    fun the_queued_artisan_body_holds_no_aadhaar_number() {
        val stored = QuestionnairePortal.artisanCreateJson(newArtisan(), null)
        assertFalse(stored, Regex("[0-9]{12}").containsMatchIn(stored))
    }

    @Test
    fun the_sent_artisan_body_carries_the_number_again() {
        val stored = QuestionnairePortal.artisanCreateJson(newArtisan(), null)
        assertTrue(QuestionnairePortal.openedArtisanBody(stored).contains(number))
    }

    @Test
    fun the_questionnaire_outbox_is_kept_out_of_every_backup() {
        val xml = File("src/main/res/xml")
        val rules = File(xml, "data_extraction_rules.xml").readText()
        assertTrue(Regex("""<cloud-backup>[\s\S]*questionnaire-outbox/[\s\S]*</cloud-backup>""").containsMatchIn(rules))
        assertTrue(Regex("""<device-transfer>[\s\S]*questionnaire-outbox/[\s\S]*</device-transfer>""").containsMatchIn(rules))
        assertTrue(File(xml, "backup_rules.xml").readText().contains("questionnaire-outbox/"))
    }
}
