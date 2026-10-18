package com.fieldrepository.app.ui

import com.fieldrepository.app.data.QConsentVerdict
import com.fieldrepository.app.data.QRecording
import com.fieldrepository.app.data.QTranscript
import com.fieldrepository.app.ui.questionnaire.TranscriptStatus
import com.fieldrepository.app.ui.questionnaire.TranscriptTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Paused, waiting for consent, refused; Retry disabled while paused (owner correction d). */
class TranscriptStatusTest {
    private fun rec(status: String?, verdict: String?, paused: Boolean = true) =
        QRecording(id = "r", transcript = status?.let { QTranscript(it, paused) }, consent = verdict?.let { QConsentVerdict(it) })

    @Test
    fun `paused is neutral and cannot be retried`() {
        val line = TranscriptStatus.line(rec("QUEUED", "ALLOW"), paused = true)!!
        assertEquals("Transcription paused", line.text)
        assertEquals(TranscriptTone.NEUTRAL, line.tone)
        assertFalse(line.retryEnabled)
        assertEquals("Transcription is paused.", line.retryReason)
    }

    @Test
    fun `a failed job stays un-retryable while paused`() {
        assertFalse(TranscriptStatus.line(rec("FAILED", "ALLOW"), paused = true)!!.retryEnabled)
    }

    @Test
    fun `consent states read calmly`() {
        assertEquals("Waiting for consent to transcribe", TranscriptStatus.line(rec("AWAITING_CONSENT", "AWAITING"), paused = true)!!.text)
        assertEquals("Not sent: consent refused", TranscriptStatus.line(rec("REFUSED_CONSENT", "REFUSED"), paused = true)!!.text)
    }

    @Test
    fun `an owner-attested recording shows no consent line`() {
        val line = TranscriptStatus.line(QRecording(id = "r", transcript = QTranscript("QUEUED", true), consent = QConsentVerdict("ALLOW", "owner attestation")), paused = true)
        assertNotNull(line)
        assertEquals("Transcription paused", line!!.text)
    }

    @Test
    fun `no control in the app can start a transcription`() {
        val dir = repoFile("app/src/main/java/com/fieldrepository/app/ui/questionnaire/QuestionnairePrompts.kt").parentFile
        dir.listFiles { f -> f.extension == "kt" }!!.forEach { file ->
            assertFalse("${file.name} calls transcribe", file.readText().contains(".transcribe("))
        }
    }
}
