package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerJobKind
import com.fieldrepository.app.explorer.data.ExplorerJobState
import com.fieldrepository.app.explorer.data.ExplorerJobStore
import com.fieldrepository.app.explorer.data.ExplorerNotifications
import com.fieldrepository.app.explorer.data.ExplorerProgress
import com.fieldrepository.app.explorer.data.ExplorerRate
import com.fieldrepository.app.explorer.data.ExplorerRecent
import com.fieldrepository.app.explorer.data.ExplorerRecentStatus
import com.fieldrepository.app.explorer.data.ExplorerRequest
import com.fieldrepository.app.explorer.data.ExplorerResult
import com.fieldrepository.app.explorer.data.ExplorerSniff
import com.fieldrepository.app.explorer.data.ExplorerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExplorerProgressTest {
    @get:Rule val temp = TemporaryFolder()

    private val mb = 1024L * 1024L

    private fun job(
        done: Long = 0,
        total: Long = 0,
        files: Int = 0,
        totalFiles: Int = 0,
        speed: Long? = null,
        startedAt: Long? = null,
        state: ExplorerJobState = ExplorerJobState.RUNNING,
        systemId: Long? = null,
        kind: ExplorerJobKind = ExplorerJobKind.FOLDER,
    ) = ExplorerJob(
        id = "j",
        request = ExplorerRequest(title = "3rd Craft Toolkit", fileName = "3rd-Craft-Toolkit.zip", kind = kind),
        createdAt = 0,
        state = state,
        systemId = systemId,
        doneBytes = done,
        totalBytes = total,
        doneFiles = files,
        totalFiles = totalFiles,
        speedBps = speed,
        startedAt = startedAt,
    )

    @Test fun `speed comes from recent bytes, not the average since the start`() {
        val rate = ExplorerRate()
        assertNull(rate.sample(0, 0))
        assertNull(rate.sample(500, 1 * mb))
        val first = rate.sample(1_000, 2 * mb)!!
        assertEquals(2 * mb, first)
        var now = 1_000L
        var bytes = 2 * mb
        repeat(20) {
            now += 1_000
            bytes += 6 * mb
            rate.sample(now, bytes)
        }
        val settled = rate.sample(now + 1_000, bytes + 6 * mb)!!
        assertTrue("settled at $settled", settled in (5 * mb)..(7 * mb))
    }

    @Test fun `a restart from zero resets the speed`() {
        val rate = ExplorerRate()
        rate.sample(0, 0)
        rate.sample(1_000, 10 * mb)
        assertNull(rate.sample(2_000, 0))
        assertEquals(mb, rate.sample(3_000, mb))
    }

    @Test fun `time left uses the speed, and falls back to the average rate`() {
        assertEquals(100L, job(done = 400 * mb, total = 1000 * mb, speed = 6 * mb).secondsLeft(0))
        val averaged = job(done = 100 * mb, total = 300 * mb, startedAt = 0).secondsLeft(10_000)
        assertEquals(20L, averaged)
        assertNull(job(done = 0, total = 300 * mb, startedAt = 0).secondsLeft(10_000))
        assertNull(job(done = 10 * mb, total = 300 * mb, startedAt = 0).secondsLeft(1_000))
        assertNull(job(done = 300 * mb, total = 300 * mb, speed = mb).secondsLeft(0))
    }

    @Test fun `the progress line reads bytes, files and time left`() {
        val line = ExplorerText.progressLine(job(done = 4536 * mb, total = 7322 * mb, files = 574, totalFiles = 926, speed = 6 * mb + mb / 5), 0)
        assertTrue(line, line.startsWith("4.43 of 7.15 GB · 574 of 926 files · "))
        assertTrue(line, line.endsWith("min left"))
        assertEquals("6.2 MB/s", ExplorerText.speed(6 * mb + mb / 5))
        assertEquals("3rd Craft Toolkit · 62%", ExplorerText.percentOf("3rd Craft Toolkit", ExplorerProgress.percent(job(done = 62, total = 100))))
        assertEquals("1 file failed and will retry at the end.", ExplorerText.retryNote(1))
        assertEquals("Saved 47 files", ExplorerText.savedFiles(47))
        assertEquals(ExplorerText.preparing, ExplorerText.progressLine(job()))
    }

    @Test fun `recent files keep the newest first, update in place and stop at six`() {
        var recent = emptyList<ExplorerRecent>()
        recent = ExplorerProgress.running(recent, "W/Interview_Take-1.mp4", 701 * mb, 0.1f)
        recent = ExplorerProgress.running(recent, "W/Interview_Take-1.mp4", 701 * mb, 0.38f)
        assertEquals(1, recent.size)
        assertEquals(0.38f, recent.first().fraction)
        assertEquals("Interview_Take-1.mp4", recent.first().name)
        recent = ExplorerProgress.finished(recent, "W/Block-carving_03.jpg", 3 * mb, ExplorerRecentStatus.SAVED)
        recent = ExplorerProgress.finished(recent, "W/Kiln-firing_07.mov", 220 * mb, ExplorerRecentStatus.RETRYING)
        assertEquals(listOf("Kiln-firing_07.mov", "Block-carving_03.jpg", "Interview_Take-1.mp4"), recent.map { it.name })
        assertEquals(1f, recent[1].fraction)
        recent = ExplorerProgress.finished(recent, "W/Interview_Take-1.mp4", 701 * mb, ExplorerRecentStatus.SAVED)
        assertEquals(3, recent.size)
        assertEquals(ExplorerRecentStatus.SAVED, recent.first().status)
        repeat(10) { recent = ExplorerProgress.finished(recent, "W/n$it.jpg", mb, ExplorerRecentStatus.SAVED) }
        assertEquals(ExplorerProgress.RECENT, recent.size)
        assertEquals("n9.jpg", recent.first().name)
    }

    @Test fun `a retrying file shows as retrying until bytes arrive again`() {
        var recent = ExplorerProgress.finished(emptyList(), "W/Kiln.mov", 220 * mb, ExplorerRecentStatus.RETRYING)
        recent = ExplorerProgress.running(recent, "W/Kiln.mov", null, null)
        assertEquals(ExplorerRecentStatus.RETRYING, recent.single().status)
        recent = ExplorerProgress.running(recent, "W/Kiln.mov", 220 * mb, 0.2f)
        assertEquals(ExplorerRecentStatus.RUNNING, recent.single().status)
    }

    @Test fun `only background jobs can be paused, and paused jobs survive clearing and trimming`() {
        assertTrue(job().pausable)
        assertFalse(job(systemId = 7).pausable)
        assertFalse(job(state = ExplorerJobState.DONE).pausable)
        assertFalse(job(state = ExplorerJobState.PAUSED).active)
        val store = ExplorerJobStore(File(temp.newFolder(), "jobs.json"))
        store.put(job(state = ExplorerJobState.PAUSED).copy(id = "paused"))
        store.put(job(state = ExplorerJobState.DONE).copy(id = "done"))
        store.clearFinished()
        assertEquals(listOf("paused"), store.jobs.value.map { it.id })
        val many = (1..40).map { job(state = ExplorerJobState.DONE).copy(id = "d$it") } + job(state = ExplorerJobState.PAUSED).copy(id = "p")
        assertTrue(ExplorerJobStore.trim(many).any { it.id == "p" })
    }

    @Test fun `the current file has its own bar`() {
        val running = job(total = 7322 * mb).copy(current = "W/Interview_Take-1.mp4", currentBytes = 266 * mb, currentTotal = 701 * mb, speedBps = 6 * mb)
        assertEquals(266f / 701f, running.currentFraction!!, 0.0001f)
        assertNull(job().currentFraction)
        assertEquals("Interview_Take-1.mp4 · 6 MB/s", ExplorerNotifications.currentText(running))
    }

    @Test fun `finished notifications say what was saved, and failed ones name the file`() {
        val done = job(state = ExplorerJobState.DONE).copy(result = ExplorerResult(saved = 47, total = 47, bytes = 1260 * mb))
        assertEquals("Saved 3rd Craft Toolkit", ExplorerNotifications.finishedTitle(done))
        assertEquals("47 files · 1.23 GB", ExplorerNotifications.finishedText(done))
        val failed = ExplorerJob(
            id = "f",
            request = ExplorerRequest(title = "Kiln-firing_wide_07.mov", fileName = "Kiln-firing_wide_07.mov", single = true),
            createdAt = 0,
            state = ExplorerJobState.FAILED,
        )
        assertEquals("Kiln-firing_wide_07.mov didn't download", ExplorerNotifications.finishedText(failed))
    }

    @Test fun `reports and transcript bundles are rebuilt, files are not`() {
        assertTrue(ExplorerRequest(title = "r", fileName = "r.xlsx", kind = ExplorerJobKind.REPORT, single = true).rebuilt)
        assertTrue(ExplorerRequest(title = "t", fileName = "t.zip", kind = ExplorerJobKind.TRANSCRIPTS, single = true).rebuilt)
        assertFalse(ExplorerRequest(title = "f", fileName = "f.mp4", kind = ExplorerJobKind.FILE, single = true).rebuilt)
    }

    @Test fun `a workbook must start like a zip before it is saved`() {
        val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        assertTrue(ExplorerSniff.valid(xlsx, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14), "application/octet-stream"))
        assertFalse(ExplorerSniff.valid(xlsx, "{\"detail\"".toByteArray(), "application/json"))
        assertFalse(ExplorerSniff.valid("application/zip", "<html>".toByteArray(), "text/html"))
        assertTrue(ExplorerSniff.valid("text/csv", "Name,Craft".toByteArray(), "text/csv"))
        assertFalse(ExplorerSniff.valid("text/csv", "{\"detail\":1}".toByteArray(), "application/json"))
        assertTrue(ExplorerSniff.valid("video/mp4", byteArrayOf(0, 0, 0, 0x18), null))
    }
}
