package com.fieldrepository.app.recording

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QCompleteSubject
import com.fieldrepository.app.questionnaire.data.QTargetRef
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import com.fieldrepository.app.questionnaire.ui.replaceRemovesEarlier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

/**
 * A take is a typed recording (DESIGN D2): its targets, its subjects and their roles travel with it;
 * its bytes are hashed on the phone so an identical file is never sent twice; a replace removes the
 * earlier take only for a content admin; and a voice note on a question is a take with that target.
 */
class RecordingRulesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val draft = RecordingDraft(
        clientClipKey = "k1", sessionKey = "c1", questionnaireId = "f1", adtsPath = "/x.aac",
        deviceCapturedAt = "2026-07-01T10:00:00Z", targets = listOf(QTargetRef(questionLineageId = "lin7")),
        subjects = listOf(QCompleteSubject("a1"), QCompleteSubject("a2"), QCompleteSubject("R:g1")), label = "F7",
        m4aPath = "/x.m4a", sha256 = "abc", sizeBytes = 100, durationMs = 42_000
    )

    @QFeature("C1")
    @Test fun `a voice note on one question is a take with that question as its target`() {
        val clip = QuestionnaireV2.clipFrom(draft)
        assertEquals(listOf(QTargetRef(questionLineageId = "lin7")), clip.recording.targets)
        assertEquals("Q:lin7", clip.recording.targets.single().targetKey)
        assertEquals(3, clip.recording.rosterCount)
        assertTrue("never a file name: the server mints it", clip.label.startsWith("F7 ("))
    }

    @QFeature("B10")
    @Test fun `Answered by turns an unticked person PRESENT, never removed, and names the ones who answered`() {
        val subjects = SessionOutbox.withAnswered(draft.subjects, setOf("a1", "R:g1"))
        assertEquals(listOf("ANSWERED", "PRESENT", "ANSWERED"), subjects.map { it.role })
        assertTrue(subjects.filter { it.role == "ANSWERED" }.all { it.named })
        assertFalse("everyone ticked is not a subset", SessionOutbox.withAnswered(draft.subjects, setOf("a1", "a2", "R:g1")).any { it.named })
    }

    @QFeature("B14")
    @Test fun `a take is hashed on the phone exactly as the server hashes the object`() {
        val file = tmp.newFile("take.m4a").apply { writeBytes(ByteArray(200_000) { (it % 251).toByte() }) }
        val expected = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, Remuxer.sha256(file))
        assertEquals("abc", QuestionnaireV2.clipFrom(draft).contentSha256)
    }

    @QFeature("B18")
    @Test fun `a replace removes the earlier take only for a content admin, for anyone else both stay`() {
        assertTrue(replaceRemovesEarlier(isContentAdmin = true))
        assertFalse(replaceRemovesEarlier(isContentAdmin = false))
        assertEquals("r0", QuestionnaireV2.clipFrom(draft.copy(replacesRecordingId = "r0")).recording.replacesRecordingId)
        assertEquals("r9", QuestionnaireV2.clipFrom(draft.copy(continuationOfId = "r9")).recording.continuationOfId)
    }

    @QFeature("B15")
    @Test fun `a take cut short by a kill is offered back, and an empty one is forgotten`() {
        val audio = tmp.newFile("cut.aac").apply { writeBytes(ByteArray(64_000)) }
        val cut = draft.copy(clientClipKey = "cut", adtsPath = audio.absolutePath, m4aPath = null, durationMs = 0)
        val empty = draft.copy(clientClipKey = "none", adtsPath = tmp.root.absolutePath + "/missing.aac", m4aPath = null)
        assertEquals(listOf("cut"), RecoveryScanner.recoverable(listOf(cut, empty), activeClipKey = null).map { it.clientClipKey })
        assertEquals(listOf("none"), RecoveryScanner.empty(listOf(cut, empty), activeClipKey = null).map { it.clientClipKey })
        assertTrue("the running take is never offered", RecoveryScanner.recoverable(listOf(cut), activeClipKey = "cut").isEmpty())
    }
}
