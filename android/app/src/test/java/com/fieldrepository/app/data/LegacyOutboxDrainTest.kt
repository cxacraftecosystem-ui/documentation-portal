package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESIGN §9 test 57: after an upgrade, entries the old build queued drain through the shim endpoints
 * (the legacy outbox's own replay) BEFORE the v2 queue starts. A legacy entry the server refused
 * waits for a person and does not hold the new queue.
 */
class LegacyOutboxDrainTest {
    private fun legacy(type: String, failure: String? = null) = PendingEntry(
        id = "e-$type-${failure ?: "ok"}", type = type, payloadJson = "{}", label = "Interview", createdAt = "2026-10-17T10:00:00Z", failure = failure
    )

    @Test
    fun `legacy questionnaire entries drain first, then the v2 queue runs`() = runBlocking {
        val order = mutableListOf<String>()
        var queue = listOf(legacy("questionnaire"), legacy("artisan"))
        val outcome = QuestionnaireSync.pass(
            legacyDrain = { order += "legacy"; queue = emptyList() },
            legacyQuestionnaireWaiting = { com.fieldrepository.app.questionnaire.portal.QuestionnairePortal.legacyQuestionnaireEntries(queue) },
            v2 = { order += "v2"; SessionOutbox.PassResult(1, 0, 0) }
        )
        assertEquals(listOf("legacy", "v2"), order)
        assertTrue(outcome.v2Ran)
    }

    @Test
    fun `the v2 queue waits while an old interview is still waiting for a signal`() = runBlocking {
        var ran = false
        val outcome = QuestionnaireSync.pass(
            legacyDrain = { /* no signal: nothing drained */ },
            legacyQuestionnaireWaiting = { com.fieldrepository.app.questionnaire.portal.QuestionnairePortal.legacyQuestionnaireEntries(listOf(legacy("questionnaire"))) },
            v2 = { ran = true; SessionOutbox.PassResult(0, 0, 0) }
        )
        assertFalse(ran)
        assertEquals(1, outcome.legacyWaiting)
    }

    @Test
    fun `refused legacy entries and other record types never hold the new queue`() {
        assertEquals(0, com.fieldrepository.app.questionnaire.portal.QuestionnairePortal.legacyQuestionnaireEntries(listOf(legacy("questionnaire", failure = "refused"), legacy("product"))))
        assertEquals(1, com.fieldrepository.app.questionnaire.portal.QuestionnairePortal.legacyQuestionnaireEntries(listOf(legacy("questionnaireinterview"))))
    }

    @Test
    fun `an entry an older build wrote still decodes`() {
        val old = """{"id":"x","type":"questionnaire","payloadJson":"{}","label":"Interview","createdAt":"2026-09-01T00:00:00Z"}"""
        val entry = ApiClient.json.decodeFromString(PendingEntry.serializer(), old)
        assertEquals(1, com.fieldrepository.app.questionnaire.portal.QuestionnairePortal.legacyQuestionnaireEntries(listOf(entry)))
    }
}
