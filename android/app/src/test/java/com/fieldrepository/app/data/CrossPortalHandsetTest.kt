package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C27 and C28 on the handset: the wire shapes decode, the pure rules hold, and the words are the web's.
 */
class CrossPortalHandsetTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun theConsentStatusLineSaysWhatWasRecorded() {
        assertEquals("Not asked yet", SharingConsentCopy.status(SharingConsentDto(), null))
        assertEquals(
            "Agreed on 2026-10-17 · linked to 2 Designer Portal records",
            SharingConsentCopy.status(SharingConsentDto(granted = true, recorded = true, linkedRecords = 2), "2026-10-17"),
        )
        assertEquals("Declined", SharingConsentCopy.status(SharingConsentDto(granted = false, recorded = true), null))
    }

    @Test
    fun machineSuggestedAnswersAreLabelledAndTypedOnesAreNot() {
        assertEquals("Machine-suggested", AnswerDrafts.badge(AnswerDrafts.ORIGIN_MACHINE))
        assertEquals("Machine-suggested · edited", AnswerDrafts.badge(AnswerDrafts.ORIGIN_MACHINE_EDITED))
        assertNull(AnswerDrafts.badge(null))
        assertTrue(AnswerDrafts.isMachineSuggested("MACHINE_SUGGESTED_EDITED"))
        assertFalse(AnswerDrafts.isMachineSuggested("TYPED"))
    }

    @Test
    fun acceptingSendsTheEditOrTheDraftAsWritten() {
        val pending = listOf(AnswerDraftDto("d1", "q1", "s1", "As drafted"), AnswerDraftDto("d2", "q2", "s1", "Second"))
        val answers = AnswerDrafts.answersToAccept(pending, mapOf("q2" to "Edited"))
        assertEquals(listOf("As drafted", "Edited"), answers.map { it.answerText })
    }

    @Test
    fun theWireShapesDecode() {
        val list = json.decodeFromString(
            AnswerDraftListDto.serializer(),
            """{"items":[{"id":"d1","questionId":"q1","sectionId":"s1","draftText":"x","status":"PENDING","provider":"anthropic"}],"transcripts":[{"id":"m1","name":"a.webm"}]}""",
        )
        assertEquals("q1", list.items.single().questionId)
        val response = json.decodeFromString(InterviewResponseDto.serializer(), """{"questionId":"q1","answerOrigin":"MACHINE_SUGGESTED"}""")
        assertEquals("MACHINE_SUGGESTED", response.answerOrigin)
        assertNull(json.decodeFromString(InterviewResponseDto.serializer(), """{"questionId":"q1"}""").answerOrigin)
    }

    @Test
    fun theWordsAreTheWebsWords() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "frontend/components/forms/SharingConsentToggle.tsx").isFile } ?: return
        val consent = File(root, "frontend/components/forms/SharingConsentToggle.tsx").readText()
        assertTrue(consent.contains(SharingConsentCopy.LABEL))
        assertTrue(consent.contains(SharingConsentCopy.DESCRIPTION))
        val drafts = File(root, "frontend/components/questionnaire/AnswerDraftsPanel.tsx").readText()
        for (phrase in listOf(AnswerDrafts.LABEL, AnswerDrafts.ACCEPT, AnswerDrafts.DRAFT, AnswerDrafts.PASTE, AnswerDrafts.NOTHING_FOUND)) {
            assertTrue("web lacks: $phrase", drafts.contains(phrase))
        }
    }
}
