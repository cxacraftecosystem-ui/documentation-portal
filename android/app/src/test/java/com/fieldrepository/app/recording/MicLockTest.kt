package com.fieldrepository.app.recording

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class MicLockTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun clear() = MicLock.reset()

    @Test fun `one recorder holds the microphone at a time`() {
        val legacy = Any()
        assertTrue(MicLock.acquire(MicLock.SESSION))
        assertFalse(MicLock.acquire(legacy))
        assertTrue(MicLock.acquire(MicLock.SESSION))
        MicLock.release(legacy)
        assertEquals(MicLock.SESSION, MicLock.holder.value)
        MicLock.release(MicLock.SESSION)
        assertNull(MicLock.holder.value)
        assertTrue(MicLock.acquire(legacy))
        assertTrue(MicLock.held)
    }

    @Test fun `a failed start names its cause`() {
        val plenty = 500L * 1024 * 1024
        assertEquals(MicFailure.NO_PERMISSION, MicFailure.of(RuntimeException("start failed"), permitted = false, freeBytes = plenty))
        assertEquals(MicFailure.NO_PERMISSION, MicFailure.of(SecurityException("denied"), permitted = true, freeBytes = plenty))
        assertEquals(MicFailure.STORAGE_FULL, MicFailure.of(RuntimeException("start failed"), permitted = true, freeBytes = 1024))
        assertEquals(MicFailure.STORAGE_FULL, MicFailure.of(IOException("write failed: ENOSPC (No space left on device)"), permitted = true, freeBytes = plenty))
        assertEquals(MicFailure.BUSY, MicFailure.of(MicBusyException(), permitted = true, freeBytes = plenty))
        assertEquals(MicFailure.BUSY, MicFailure.of(IllegalStateException(), permitted = true, freeBytes = plenty))
        assertEquals(MicFailure.BUSY, MicFailure.of(RuntimeException("start failed."), permitted = true, freeBytes = plenty))
        assertEquals(MicFailure.OTHER, MicFailure.of(IOException("prepare failed"), permitted = true, freeBytes = plenty))
        assertTrue(MicFailure.entries.all { it.sentence.isNotBlank() && !it.sentence.contains("HTTP") })
    }

    @Test fun `the session recorder reports a held microphone, a full phone and a refused permission by name`() {
        val out = tmp.newFile("take.aac")
        assertEquals(RecordingService.REASON_MIC_BUSY, RecordingService.startFailure(RuntimeException("start failed"), out))
        assertEquals(RecordingService.REASON_STORAGE_FULL, RecordingService.startFailure(IOException("ENOSPC"), out))
        assertEquals(RecordingService.REASON_NO_PERMISSION, RecordingService.startFailure(SecurityException(), out))
        assertEquals(RecordingService.REASON_START_FAILED, RecordingService.startFailure(IOException("prepare failed"), out))
        assertEquals(MicFailure.BUSY.sentence, RecordingService.failureSentence(RecordingService.REASON_MIC_BUSY))
        assertNull(RecordingService.failureSentence(RecordingService.REASON_START_FAILED))
    }
}
