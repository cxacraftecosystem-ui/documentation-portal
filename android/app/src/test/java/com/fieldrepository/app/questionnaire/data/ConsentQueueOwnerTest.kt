package com.fieldrepository.app.questionnaire.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConsentQueueOwnerTest {

    private fun heldForAnotherAccount(ownerId: String?, signedInUserId: String?): Boolean {
        val rules = Class.forName(QuestionnaireV2::class.java.name + "Kt")
        val method = rules.getDeclaredMethod("consentHeldForAnotherAccount", String::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(null, ownerId, signedInUserId) as Boolean
    }

    @Test
    fun `a queued consent records the account that decided it`() {
        val fields = QuestionnaireV2.PendingConsent::class.java.declaredFields.map { it.name }
        assertTrue("PendingConsent has no owner, so the next account sends it: $fields", "ownerId" in fields)
    }

    @Test
    fun `a consent queued by one account is held from the next account`() {
        assertTrue(heldForAnotherAccount("researcher-a", "researcher-b"))
        assertTrue(heldForAnotherAccount("researcher-a", null))
        assertTrue(heldForAnotherAccount(null, "researcher-b"))
        assertFalse(heldForAnotherAccount("researcher-a", "researcher-a"))
    }

    @Test
    fun `the consent flush skips rows held for another account`() {
        val source = sourceOf("questionnaire/data/QuestionnaireV2.kt").readText()
        val flush = source.substringAfter("suspend fun flushConsents()").substringBefore("suspend fun consentWaiting")
        assertTrue("flushConsents never checks whose consent it is sending", flush.contains("consentHeldForAnotherAccount("))
    }

    private fun sourceOf(relative: String): File {
        val pkg = QuestionnaireV2::class.java.packageName.removeSuffix(".questionnaire.data").replace('.', '/')
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in listOf("src/main/java", "app/src/main/java", "android/app/src/main/java")) {
                val candidate = File(dir, "$path/$pkg/$relative")
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("$relative not found from ${File(".").absolutePath}")
    }
}
