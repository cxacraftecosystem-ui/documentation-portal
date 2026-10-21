package com.fieldrepository.app.data

import android.media.AudioManager
import androidx.work.ListenableWorker
import com.fieldrepository.app.recording.RecordingDraft
import com.fieldrepository.app.recording.RecordingService
import com.fieldrepository.app.recording.RecoveryScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit M23 (21 Oct 2026), the Android gaps that strand or lose takes:
 * - one stuck legacy entry held every v2 take behind it;
 * - a pass that threw ended the worker with nothing scheduled;
 * - any brief audio-focus loss (a notification, the assistant) ended the take;
 * - P13 Discard could delete a take that had been queued in the meantime.
 */
class SyncPassResilienceTest {
    @Test fun `a legacy entry the server keeps answering without taking no longer holds the v2 queue`() = runBlocking {
        var ran = false
        val outcome = QuestionnaireSync.pass(
            legacyDrain = { /* the server answered; the entry neither landed nor was refused */ },
            legacyQuestionnaireWaiting = { 1 },
            v2 = { ran = true; SessionOutbox.PassResult(1, 0, 0) },
            serverAnswering = { true }
        )
        assertTrue(ran)
        assertTrue(outcome.v2Ran)
    }

    @Test fun `while the old queue is still draining, v2 waits its turn`() = runBlocking {
        var waiting = 3
        var ran = false
        QuestionnaireSync.pass(
            legacyDrain = { waiting = 1 },
            legacyQuestionnaireWaiting = { waiting },
            v2 = { ran = true; SessionOutbox.PassResult(0, 0, 0) },
            serverAnswering = { true }
        )
        assertFalse(ran)
    }

    @Test fun `the worker retries while anything is left, including a v2 queue that did not run`() {
        assertTrue(OutboxSyncWorker.resultFor(0, QuestionnaireSync.Outcome(1, v2Ran = false, v2Remaining = -1)) is ListenableWorker.Result.Retry)
        assertTrue(OutboxSyncWorker.resultFor(0, QuestionnaireSync.Outcome(0, v2Ran = true, v2Remaining = 2)) is ListenableWorker.Result.Retry)
        assertTrue(OutboxSyncWorker.resultFor(0, QuestionnaireSync.Outcome(0, v2Ran = true, v2Remaining = 0)) is ListenableWorker.Result.Success)
        assertTrue(OutboxSyncWorker.resultFor(2, QuestionnaireSync.Outcome(0, v2Ran = true, v2Remaining = 0)) is ListenableWorker.Result.Retry)
    }

    @Test fun `a brief focus loss pauses the take and focus back resumes it; only a permanent loss ends it`() {
        assertEquals(RecordingService.FocusAction.PAUSE, RecordingService.focusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, pausedForFocus = false))
        assertEquals(RecordingService.FocusAction.RESUME, RecordingService.focusAction(AudioManager.AUDIOFOCUS_GAIN, pausedForFocus = true))
        assertEquals(RecordingService.FocusAction.NONE, RecordingService.focusAction(AudioManager.AUDIOFOCUS_GAIN, pausedForFocus = false))
        assertEquals(RecordingService.FocusAction.NONE, RecordingService.focusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, pausedForFocus = false))
        assertEquals(RecordingService.FocusAction.END, RecordingService.focusAction(AudioManager.AUDIOFOCUS_LOSS, pausedForFocus = false))
    }

    @Test fun `P13 Discard never deletes a take that has been queued since the prompt opened`() {
        val draft = RecordingDraft(clientClipKey = "k1", sessionKey = "c", questionnaireId = "q", adtsPath = "/x.aac", deviceCapturedAt = "2026-07-01T10:00:00Z", label = "D3")
        assertTrue(RecoveryScanner.safeToDiscard(draft, emptySet()))
        assertFalse("queued since", RecoveryScanner.safeToDiscard(draft.copy(queued = true), emptySet()))
        assertFalse("already in the outbox", RecoveryScanner.safeToDiscard(draft, setOf("k1")))
        assertFalse("gone from disk", RecoveryScanner.safeToDiscard(null, emptySet()))
    }
}
