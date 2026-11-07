package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.data.AadhaarAtRest
import kotlinx.coroutines.runBlocking
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

class OutboxAadhaarAtRestTest {

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

    @Before
    fun useSoftwareKey() {
        AadhaarAtRest.sealerForTests = SoftwareSealer()
    }

    @After
    fun restore() {
        AadhaarAtRest.sealerForTests = null
    }

    private fun unit(): SessionUnit = SessionUnit(
        clientKey = "unit-1",
        ownerUserId = "researcher-a",
        createdAt = "2026-11-07T10:00:00Z",
        questionnaireId = "q-1",
        steps = listOf(
            SessionStep(
                id = "step-1",
                kind = StepKind.ARTISAN,
                deviceTime = "2026-11-07T10:00:00Z",
                idempotencyKey = "idem-1",
                bodyJson = """{"name":"Kalu Ram","place":"Bagru","aadhaarNumber":"$number"}""",
                localArtisanId = "local-1",
            )
        ),
    )

    @Test
    fun `a queued artisan's Aadhaar number is never written to the outbox file in plain text`() = runBlocking {
        val dir = File(folder.root, "questionnaire-outbox")
        val store = FileSessionUnitStore(dir)

        store.write(unit())

        val onDisk = dir.listFiles().orEmpty().joinToString("\n") { it.readText() }
        assertTrue(onDisk.isNotEmpty())
        assertFalse("the outbox file holds the Aadhaar number in plain text", onDisk.contains(number))
    }

    @Test
    fun `the queued artisan reads back with its number for sending`() = runBlocking {
        val store = FileSessionUnitStore(File(folder.root, "questionnaire-outbox"))
        store.write(unit())

        val back = store.read("unit-1")!!

        assertEquals(unit().steps.single().bodyJson, back.steps.single().bodyJson)
        assertEquals(unit().steps.single().bodyJson, store.all().single().steps.single().bodyJson)
    }

    @Test
    fun `the outbox folder is kept out of cloud backup and device transfer`() {
        val res = resDir()
        val extraction = File(res, "xml/data_extraction_rules.xml").readText()
        val cloud = extraction.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = extraction.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        val legacy = File(res, "xml/backup_rules.xml").readText()
        for ((name, rules) in listOf("cloud-backup" to cloud, "device-transfer" to transfer, "full-backup-content" to legacy)) {
            assertTrue(
                "$name does not exclude questionnaire-outbox/",
                rules.contains("""<exclude domain="file" path="questionnaire-outbox/" />"""),
            )
        }
    }

    private fun resDir(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in listOf("src/main/res", "app/src/main/res", "android/app/src/main/res")) {
                val candidate = File(dir, path)
                if (File(candidate, "xml/data_extraction_rules.xml").isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("res/xml not found from ${File(".").absolutePath}")
    }
}
