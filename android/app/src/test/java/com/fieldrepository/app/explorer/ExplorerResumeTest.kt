package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.FakeExplorerApi.Companion.bytes
import com.fieldrepository.app.explorer.FakeExplorerApi.Companion.ndjson
import com.fieldrepository.app.explorer.data.ExplorerCheckpoint
import com.fieldrepository.app.explorer.data.ExplorerEngine
import com.fieldrepository.app.explorer.data.ExplorerFileLog
import com.fieldrepository.app.explorer.data.ExplorerJson
import com.fieldrepository.app.explorer.data.ExplorerManifestFile
import com.fieldrepository.app.explorer.data.ExplorerOutput
import com.fieldrepository.app.explorer.data.ExplorerRecent
import com.fieldrepository.app.explorer.data.ExplorerRecentStatus
import com.fieldrepository.app.explorer.data.ExplorerRequest
import com.fieldrepository.app.explorer.data.ExplorerSource
import com.fieldrepository.app.explorer.data.ExplorerTick
import com.fieldrepository.app.explorer.data.ExplorerZipRecord
import com.fieldrepository.app.explorer.data.ExplorerZipWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class ExplorerResumeTest {
    @get:Rule val temp = TemporaryFolder()

    private inner class Disk {
        val file: File = temp.newFile("out.zip")
        var finished = false
        var aborted = false
        var reopened = 0

        fun open(): ExplorerOutput = output(FileOutputStream(file))

        fun reopen(handle: String, at: Long): ExplorerOutput? {
            if (handle != file.toURI().toString() || file.length() < at) return null
            RandomAccessFile(file, "rw").use { it.setLength(at) }
            reopened++
            return output(FileOutputStream(file, true))
        }

        private fun output(stream: FileOutputStream) = ExplorerOutput(
            stream,
            onFinish = { finished = true; "Downloads/out.zip" to "content://downloads/9" },
            onAbort = { aborted = true },
            handle = file.toURI().toString(),
        )
    }

    private fun line(f: ExplorerManifestFile) = ExplorerJson.encodeToString(ExplorerManifestFile.serializer(), f)

    private fun request() = ExplorerRequest(title = "W", fileName = "W.zip", sources = listOf(ExplorerSource("Workshop")))

    private fun engine(api: FakeExplorerApi, root: File, disk: Disk) = ExplorerEngine(
        api = api,
        json = ExplorerJson,
        workRoot = root,
        saver = { _, _ -> disk.open() },
        backoffMs = 1,
        parallel = 1,
        busyTickMs = 1,
        reopen = disk::reopen,
    )

    @Test fun `a paused folder zip picks up after the last file it finished`() = runBlocking {
        val api = FakeExplorerApi()
        api.manifest = {
            ndjson(
                listOf(
                    line(ExplorerManifestFile(path = "W/notes.txt", content = "hello", sha256 = "t1")),
                    line(ExplorerManifestFile(path = "W/a.jpg", url = "/api/m/a", mediaId = "a", mediaType = "IMAGE", sizeBytes = 3, sha256 = "a1")),
                    line(ExplorerManifestFile(path = "W/b.mp4", url = "/api/m/b", mediaId = "b", mediaType = "VIDEO", sizeBytes = 4, sha256 = "b1")),
                ),
            )
        }
        val fetched = mutableListOf<String>()
        val reachedB = CompletableDeferred<Unit>()
        var block = true
        api.paths = { path, _ ->
            fetched += path
            if (path.endsWith("/b") && block) {
                reachedB.complete(Unit)
                awaitCancellation()
            }
            bytes(if (path.endsWith("/a")) byteArrayOf(1, 2, 3) else byteArrayOf(4, 5, 6, 7))
        }
        val root = temp.newFolder()
        val disk = Disk()
        val first = async { engine(api, root, disk).run("job", request()) { } }
        withTimeout(10_000) { reachedB.await() }
        first.cancel()
        try { first.await() } catch (expected: CancellationException) { }
        assertFalse(disk.aborted)
        assertFalse(disk.finished)
        val saved = ExplorerCheckpoint(File(root, "job")).read()
        assertNotNull(saved)
        assertEquals(listOf("W/notes.txt", "W/a.jpg"), saved!!.records.map { it.source })
        assertEquals(disk.file.length(), saved.committed)

        block = false
        fetched.clear()
        val ticks = mutableListOf<ExplorerTick>()
        val result = engine(api, root, disk).run("job", request(), resume = true) { ticks += it }
        assertEquals(1, disk.reopened)
        assertEquals(listOf("/api/m/b"), fetched)
        assertTrue(disk.finished)
        assertEquals(3, result.saved)
        assertTrue(ticks.any { it.resumedFrom == 2 && it.doneFiles == 2 })
        ZipFile(disk.file).use { zip ->
            assertEquals(listOf("W/notes.txt", "W/a.jpg", "W/b.mp4"), zip.entries().toList().map { it.name })
            assertEquals("hello", zip.getInputStream(zip.getEntry("W/notes.txt")).readBytes().decodeToString())
            assertArrayEquals(byteArrayOf(4, 5, 6, 7), zip.getInputStream(zip.getEntry("W/b.mp4")).readBytes())
        }
        assertFalse(File(root, "job").exists())
    }

    @Test fun `a file that changed since the pause is fetched again and the stale copy is cut away`() = runBlocking {
        val api = FakeExplorerApi()
        var digest = "a1"
        api.manifest = { ndjson(listOf(line(ExplorerManifestFile(path = "W/a.jpg", url = "/api/m/a", mediaId = "a", mediaType = "IMAGE", sizeBytes = 3, sha256 = digest)))) }
        val fetched = mutableListOf<String>()
        api.paths = { path, _ -> fetched += path; bytes(byteArrayOf(1, 2, 3)) }
        val root = temp.newFolder()
        val work = File(root, "job").apply { mkdirs() }
        val disk = Disk()
        val out = disk.open()
        ExplorerCheckpoint(work).start(out.handle!!)
        val zip = ExplorerZipWriter(out.stream, onEntry = { r, end -> ExplorerCheckpoint(work).append(r, end) })
        val part = File(work, "seed").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        zip.file("W/a.jpg", part, compress = false, tag = "old")
        zip.detach()
        out.stream.close()
        digest = "a2"
        val result = engine(api, root, disk).run("job", request(), resume = true) { }
        assertEquals(listOf("/api/m/a"), fetched)
        assertEquals(1, result.saved)
        ZipFile(disk.file).use { assertEquals(listOf("W/a.jpg"), it.entries().toList().map { e -> e.name }) }
        assertEquals(1, disk.reopened)
    }

    @Test fun `a bundle downloads from its own manifest and resumes like a folder`() = runBlocking {
        val api = FakeExplorerApi()
        api.manifest = { error("a bundle never asks for the folder manifest") }
        val asked = mutableListOf<String>()
        var block = true
        val reachedB = CompletableDeferred<Unit>()
        api.paths = { path, _ ->
            asked += path
            when {
                path.startsWith("/api/data/bundles/") -> ndjson(
                    listOf(
                        line(ExplorerManifestFile(path = "B/a.jpg", url = "/api/m/a", mediaId = "a", mediaType = "IMAGE", sizeBytes = 3, sha256 = "a1")),
                        line(ExplorerManifestFile(path = "B/b.jpg", url = "/api/m/b", mediaId = "b", mediaType = "IMAGE", sizeBytes = 3, sha256 = "b1")),
                    ),
                )
                path.endsWith("/b") && block -> { reachedB.complete(Unit); awaitCancellation() }
                else -> bytes(byteArrayOf(1, 2, 3))
            }
        }
        val request = ExplorerRequest(
            title = "Sheet: Tools",
            fileName = "Tools.zip",
            sources = listOf(ExplorerSource(path = "by-workshop/w1", manifestUrl = "/api/data/bundles/bd1/manifest?stream=1")),
        )
        val root = temp.newFolder()
        val disk = Disk()
        val first = async { engine(api, root, disk).run("bundle", request) { } }
        withTimeout(10_000) { reachedB.await() }
        first.cancel()
        try { first.await() } catch (expected: CancellationException) { }
        block = false
        asked.clear()
        val result = engine(api, root, disk).run("bundle", request, resume = true) { }
        assertEquals(listOf("/api/data/bundles/bd1/manifest?stream=1", "/api/m/b"), asked)
        assertEquals(2, result.saved)
        ZipFile(disk.file).use { assertEquals(listOf("B/a.jpg", "B/b.jpg"), it.entries().toList().map { e -> e.name }) }
    }

    @Test fun `without a checkpoint a resume starts a fresh zip`() = runBlocking {
        val api = FakeExplorerApi()
        api.manifest = { ndjson(listOf(line(ExplorerManifestFile(path = "W/x.txt", content = "x")))) }
        val disk = Disk()
        val result = engine(api, temp.newFolder(), disk).run("job", request(), resume = true) { }
        assertEquals(0, disk.reopened)
        assertEquals(1, result.saved)
    }

    @Test fun `the zip writer's output opens in both readers, deflated and stored`() {
        val sink = ByteArrayOutputStream()
        val seen = mutableListOf<Pair<ExplorerZipRecord, Long>>()
        val big = File(temp.root, "doc.txt").apply { writeText("line\n".repeat(2_000)) }
        val photo = File(temp.root, "p.jpg").apply { writeBytes(ByteArray(500) { (it * 7).toByte() }) }
        ExplorerZipWriter(sink, scratch = temp.root, onEntry = { r, end -> seen += r to end }).use { zip ->
            zip.text("Ä/notes.txt", "नमस्ते")
            zip.file("Ä/doc.txt", big, compress = true)
            zip.file("Ä/p.jpg", photo, compress = false)
            zip.file("Ä/p.jpg", photo, compress = false)
        }
        assertEquals(4, seen.size)
        assertEquals(ExplorerZipWriter.DEFLATED, seen[1].first.method)
        assertTrue(seen[1].first.compressed < big.length())
        assertEquals(ExplorerZipWriter.STORED, seen[2].first.method)
        val names = mutableListOf<String>()
        ZipInputStream(sink.toByteArray().inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                names += e.name
                val body = z.readBytes()
                if (e.name.endsWith("notes.txt")) assertEquals("नमस्ते", body.decodeToString())
                if (e.name.endsWith("doc.txt")) assertEquals(big.readText(), body.decodeToString())
            }
        }
        assertEquals(listOf("Ä/notes.txt", "Ä/doc.txt", "Ä/p.jpg", "Ä/p_2.jpg"), names)
        val file = File(temp.root, "w.zip").apply { writeBytes(sink.toByteArray()) }
        ZipFile(file).use { assertEquals(4, it.size()) }
    }

    @Test fun `past four gigabytes the zip carries zip64 records`() {
        val sink = ByteArrayOutputStream()
        ExplorerZipWriter(sink, start = 5L * 1024 * 1024 * 1024).use { it.text("a.txt", "a") }
        val bytes = sink.toByteArray()
        fun has(sig: Int) = (0..bytes.size - 4).any { i ->
            (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt() and 0xFF) shl 24) == sig
        }
        assertTrue(has(0x06064b50))
        assertTrue(has(0x07064b50))
        assertTrue(has(0x06054b50))
    }

    @Test fun `a torn checkpoint line is ignored and the commit stays at the last whole entry`() {
        val dir = temp.newFolder()
        val cp = ExplorerCheckpoint(dir)
        cp.start("file:///x.zip")
        val r = ExplorerZipRecord("a", "W/a", 0, 1, 3, 3, 0, 0, 0)
        cp.append(r, 40)
        File(dir, ExplorerCheckpoint.ENTRIES).appendText("{\"record\":{\"name\":")
        val saved = cp.read()!!
        assertEquals(40L, saved.committed)
        assertEquals(1, saved.records.size)
        cp.clear()
        assertEquals(null, cp.read())
    }

    @Test fun `every file of a job is kept with its latest status, newest last`() {
        val log = ExplorerFileLog(temp.newFolder())
        log.append("j", ExplorerRecent("a.jpg", 3, ExplorerRecentStatus.SAVED, 1f))
        log.append("j", ExplorerRecent("b.mov", 9, ExplorerRecentStatus.RETRYING))
        log.append("j", ExplorerRecent("c.txt", 1, ExplorerRecentStatus.SAVED, 1f))
        log.append("j", ExplorerRecent("b.mov", 9, ExplorerRecentStatus.FAILED))
        log.append("k", ExplorerRecent("z.jpg", 1, ExplorerRecentStatus.SAVED, 1f))
        val files = log.read("j")
        assertEquals(listOf("a.jpg", "c.txt", "b.mov"), files.map { it.name })
        assertEquals(ExplorerRecentStatus.FAILED, files.last().status)
        log.keepOnly(setOf("k"))
        assertTrue(log.read("j").isEmpty())
        assertEquals(1, log.read("k").size)
        log.delete("k")
        assertTrue(log.read("k").isEmpty())
    }

    @Test fun `the file list stops growing at its cap`() {
        val log = ExplorerFileLog(temp.newFolder())
        repeat(ExplorerFileLog.MAX_LINES + 5) { log.append("j", ExplorerRecent("f$it", 1, ExplorerRecentStatus.SAVED, 1f)) }
        assertEquals(ExplorerFileLog.MAX_FILES, log.read("j").size)
    }
}
