package com.fieldrepository.app.data

import com.fieldrepository.app.data.LegacyQuestionnaireSave.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * H21 (audit 2026-10-21): a legacy save that created the interview and then lost a clip must not
 * create a second interview, or upload the clips that did arrive, when the researcher saves again.
 */
class LegacyQuestionnaireSaveTest {

    @Test
    fun `a new form creates, then every later save updates what it created`() {
        assertEquals(Target.Create, LegacyQuestionnaireSave.target(editingId = null, createdId = null))
        assertEquals(Target.Update("iv-1"), LegacyQuestionnaireSave.target(editingId = null, createdId = "iv-1"))
        assertEquals(Target.Update("iv-9"), LegacyQuestionnaireSave.target(editingId = "iv-9", createdId = null))
    }

    @Test
    fun `a linked clip leaves the pending map, so a retry sends only the failures`() {
        val pending = mapOf("q1" to listOf("a", "b"), "section:s1" to listOf("c"))
        val afterA = LegacyQuestionnaireSave.withoutClip(pending, "q1", "a")
        assertEquals(mapOf("q1" to listOf("b"), "section:s1" to listOf("c")), afterA)
        val afterC = LegacyQuestionnaireSave.withoutClip(afterA, "section:s1", "c")
        assertEquals(mapOf("q1" to listOf("b")), afterC)
        assertEquals(afterC, LegacyQuestionnaireSave.withoutClip(afterC, "missing", "z"))
    }

    @Test
    fun `the form keeps the created id and drops each clip once linked`() {
        val source = File("src/main/java/com/fieldrepository/app/MainActivity.kt").readText()
        assertTrue(source.contains("{ key -> onSubmit(request, key) }.also { createdId = it }"))
        assertTrue(source.contains("repository.updateQuestionnaireInterview(updateId, updateRequestFor())"))
        assertTrue(source.contains("questionAudio = com.fieldrepository.app.data.LegacyQuestionnaireSave.withoutClip(questionAudio, key, uri)"))
        assertTrue(source.contains("idleLabel = if (isEdit || createdId != null) \"Update interview\" else \"Save questionnaire\""))
        // Attachments too: the ones that reached the record leave the batch when others fail.
        assertTrue(source.contains("media.uris = media.uris.filter { it in failed }"))
    }
}
