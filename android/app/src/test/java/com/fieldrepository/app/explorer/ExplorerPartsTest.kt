package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerBusy
import com.fieldrepository.app.explorer.data.ExplorerBusyException
import com.fieldrepository.app.explorer.data.ExplorerBytes
import com.fieldrepository.app.explorer.data.ExplorerCache
import com.fieldrepository.app.explorer.data.ExplorerDownloads
import com.fieldrepository.app.explorer.data.ExplorerJob
import com.fieldrepository.app.explorer.data.ExplorerJobState
import com.fieldrepository.app.explorer.data.ExplorerJobStore
import com.fieldrepository.app.explorer.data.ExplorerKind
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.explorer.data.ExplorerRequest
import com.fieldrepository.app.explorer.data.ExplorerSize
import com.fieldrepository.app.explorer.data.ExplorerSniff
import com.fieldrepository.app.explorer.data.ExplorerStats
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.explorer.data.ExplorerTransfer
import com.fieldrepository.app.explorer.data.ExplorerTree
import com.fieldrepository.app.explorer.data.ExplorerTypeStats
import com.fieldrepository.app.explorer.data.ExplorerZipNames
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExplorerPartsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `long folder paths never share a saved copy`() {
        val cache = ExplorerCache(temp.newFolder(), "user-1")
        val stem = "taxonomy/by-craft/" + "Hand-Block-Printing/".repeat(10)
        val a = ExplorerCache.key(stem + "Artisan-A", null)
        val b = ExplorerCache.key(stem + "Artisan-B", null)
        assertNotEquals(cache.fileFor(a), cache.fileFor(b))
        cache.write(a, ExplorerTree(path = "A"))
        cache.write(b, ExplorerTree(path = "B"))
        assertEquals("A", cache.read(a)?.tree?.path)
        assertEquals("B", cache.read(b)?.tree?.path)
        assertNotEquals(cache.fileFor(ExplorerCache.key("a/b", null)), cache.fileFor(ExplorerCache.key("a_b", null)))
    }

    @Test fun `signing out leaves no saved copies, scratch files, previews or download list behind`() {
        val files = temp.newFolder("files")
        val cache = temp.newFolder("cache")
        val saved = ExplorerCache(File(files, ExplorerCache.DIR), "user-1")
        saved.write(ExplorerCache.key("by-workshop", null), ExplorerTree(path = "by-workshop"))
        File(cache, "${ExplorerDownloads.WORK_DIR}/job-1").apply { mkdirs() }.resolve("part.bin").writeText("x")
        File(cache, ExplorerDownloads.PREVIEW_DIR).apply { mkdirs() }.resolve("m1.pdf").writeText("x")
        val other = File(cache, "updates").apply { mkdirs() }
        val store = ExplorerJobStore(File(files, "${ExplorerDownloads.WORK_DIR}/jobs.json"))
        store.put(ExplorerJob(id = "j", request = ExplorerRequest(title = "Kamla's folder", fileName = "Kamla.zip"), createdAt = 1))
        ExplorerDownloads.forgetFiles(files, cache)
        store.clear()
        assertTrue(!File(files, ExplorerCache.DIR).exists())
        assertTrue(!File(cache, ExplorerDownloads.WORK_DIR).exists())
        assertTrue(!File(cache, ExplorerDownloads.PREVIEW_DIR).exists())
        assertTrue(other.isDirectory)
        assertNull(saved.read(ExplorerCache.key("by-workshop", null)))
        assertTrue(store.jobs.value.isEmpty())
        assertTrue(ExplorerJobStore(File(files, "${ExplorerDownloads.WORK_DIR}/jobs.json")).jobs.value.isEmpty())
    }

    @Test fun `one person's saved copy is not another's`() {
        val root = temp.newFolder()
        ExplorerCache(root, "u1").write("k", ExplorerTree(path = "mine"))
        assertNull(ExplorerCache(root, "u2").read("k"))
        assertEquals("mine", ExplorerCache(root, "u1").read("k")?.tree?.path)
    }

    @QFeature("X2")
    @Test fun `sizes read the server's totals per type`() {
        val size = ExplorerSize.of(
            ExplorerStats(
                files = 926,
                bytes = 7_150_000_000,
                byType = mapOf("videos" to ExplorerTypeStats(57, 3_650_000_000), "images" to ExplorerTypeStats(800, 3_000_000_000)),
            ),
        )
        assertEquals("926 files · 6.7 GB", ExplorerBytes.line(size))
        assertEquals(57, size.byKind[ExplorerKind.VIDEOS]?.files)
        assertEquals("1 file", ExplorerBytes.line(1, 0, 1))
        assertEquals("3 files · at least 1.0 KB", ExplorerBytes.line(3, 1024, 1))
    }

    @Test fun `space needed covers the archive and the files being fetched`() {
        val needed = ExplorerBytes.spaceNeeded(1_000L, 10L, 4)
        assertTrue(needed >= 1_000L + 10L + 3 * 10L)
    }

    @Test fun `busy waits once with a countdown, then gives up`() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Int?>()
        val got = ExplorerBusy.retryOnce({ waits += it }, tickMs = 1) {
            calls++
            if (calls == 1) throw ExplorerBusyException(3, ExplorerText.busy(3)) else "ok"
        }
        assertEquals("ok", got)
        assertEquals(listOf(3, 2, 1, null), waits)
        assertEquals("Busy, retrying in 60 s", ExplorerText.busy(60))
        var second = 0
        val failure = runCatching { ExplorerBusy.retryOnce({ }, tickMs = 1) { second++; throw ExplorerBusyException(1, "busy") } }.exceptionOrNull()
        assertTrue(failure is ExplorerBusyException)
        assertEquals(2, second)
    }

    @Test fun `file names come from content disposition, star form first`() {
        assertEquals("a b.zip", ExplorerTransfer.dispositionName("attachment; filename=\"x.zip\"; filename*=UTF-8''a%20b.zip"))
        assertEquals("x.zip", ExplorerTransfer.dispositionName("attachment; filename=\"x.zip\""))
        assertEquals("y.xlsx", ExplorerTransfer.dispositionName("attachment; filename=../../y.xlsx"))
        assertNull(ExplorerTransfer.dispositionName(null))
    }

    @Test fun `audio is recognised by its first bytes`() {
        assertEquals(".mp4", ExplorerSniff.extension(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray()))
        assertEquals(".ogg", ExplorerSniff.extension("OggS".toByteArray()))
        assertEquals(".wav", ExplorerSniff.extension("RIFF0000WAVE".toByteArray()))
        assertEquals(".mp3", ExplorerSniff.extension("ID3".toByteArray()))
        assertEquals(".aac", ExplorerSniff.extension(byteArrayOf(0xFF.toByte(), 0xF1.toByte())))
        assertNull(ExplorerSniff.extension(ByteArray(0)))
    }

    @Test fun `zip names are unique and safe`() {
        val names = ExplorerZipNames()
        assertEquals("a/b.txt", names.claim("a/../b.txt".replace("../", "")))
        assertEquals("a/b_2.txt", names.claim("a/b.txt"))
        assertEquals("c/d-.txt", names.claim("c/d:.txt"))
    }

    @Test fun `paths resolve against the api root`() {
        assertEquals("https://h.example/api/data/media/1/thumbnail?size=256", ExplorerPaths.resolve("https://h.example/api/", ExplorerPaths.withQuery("/data/media/1/thumbnail", "size", "256")))
        assertEquals("/api/data/report?format=xlsx&path=w%2Fa+b", ExplorerPaths.report("w/a b"))
        assertEquals("/api/transcripts/m1/download?format=zip&track=both", ExplorerPaths.transcriptZip("m1"))
        assertTrue(ExplorerPaths.transcriptBundle("w").startsWith("/api/data/transcript-bundle?track=both&path="))
    }

    @Test fun `the download list survives a restart and keeps the newest thirty`() {
        val file = File(temp.newFolder(), "jobs.json")
        val store = ExplorerJobStore(file)
        repeat(40) { i -> store.put(ExplorerJob(id = "j$i", request = ExplorerRequest(title = "t", fileName = "f.zip"), createdAt = i.toLong(), state = ExplorerJobState.DONE)) }
        store.put(ExplorerJob(id = "live", request = ExplorerRequest(title = "t", fileName = "f.zip"), createdAt = 99))
        val reopened = ExplorerJobStore(file)
        assertEquals(ExplorerJobStore.KEEP, reopened.jobs.value.size)
        assertEquals("live", reopened.jobs.value.first().id)
        reopened.clearFinished()
        assertEquals(listOf("live"), reopened.jobs.value.map { it.id })
    }

    @QFeature("X10")
    @Test fun `progress reads in bytes with time left`() {
        val job = ExplorerJob(id = "j", request = ExplorerRequest(title = "t", fileName = "f.zip"), createdAt = 0, state = ExplorerJobState.RUNNING, doneBytes = 512L * 1024 * 1024, totalBytes = 1024L * 1024 * 1024, startedAt = 0)
        assertEquals(0.5f, job.fraction)
        assertEquals("512 MB of 1.0 GB", ExplorerBytes.progress(job.doneBytes, job.totalBytes))
        assertEquals("1 min left", ExplorerText.eta(job, now = 60_000))
    }

    @QFeature("X6")
    @Test fun `table cells read as text and a csv names its sheet`() {
        val sheet = com.fieldrepository.app.explorer.data.ExplorerJson.decodeFromString(
            com.fieldrepository.app.explorer.data.ExplorerReport.serializer(),
            """{"sheets":[{"name":"Artisans","columns":["Name","Age"],"rows":[["Giriraj",54,null,{"a":"x"},["p","q"]]],"totalRows":120,"matchedRows":1}]}""",
        ).sheets.single()
        assertEquals(listOf("Name", "Age"), sheet.headers)
        assertEquals(listOf("Giriraj", "54", "", "x", "p, q"), sheet.rows.single().map { com.fieldrepository.app.explorer.data.ExplorerCells.text(it) })
        assertEquals("1 of 120 rows match", ExplorerText.matches(sheet.matchedRows!!, sheet.totalRows!!))
        assertEquals("/api/data/report?format=csv&sheet=Artisans&path=w", ExplorerPaths.reportCsv("w", "Artisans"))
    }
}
