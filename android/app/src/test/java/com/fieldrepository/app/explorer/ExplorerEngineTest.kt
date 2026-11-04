package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.FakeExplorerApi.Companion.bytes
import com.fieldrepository.app.explorer.FakeExplorerApi.Companion.error
import com.fieldrepository.app.explorer.FakeExplorerApi.Companion.ndjson
import com.fieldrepository.app.explorer.data.ExplorerEngine
import com.fieldrepository.app.explorer.data.ExplorerItem
import com.fieldrepository.app.explorer.data.ExplorerJson
import com.fieldrepository.app.explorer.data.ExplorerManifestFile
import com.fieldrepository.app.explorer.data.ExplorerOutput
import com.fieldrepository.app.explorer.data.ExplorerRequest
import com.fieldrepository.app.explorer.data.ExplorerSelectionBody
import com.fieldrepository.app.explorer.data.ExplorerSource
import com.fieldrepository.app.explorer.data.ExplorerSpaceException
import com.fieldrepository.app.explorer.data.ExplorerTick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

class ExplorerEngineTest {
    @get:Rule val temp = TemporaryFolder()

    private class Sink {
        val bytes = ByteArrayOutputStream()
        var name: String? = null
        var finished = false
        var aborted = false
        fun open(name: String): ExplorerOutput {
            this.name = name
            return ExplorerOutput(bytes, onFinish = { finished = true; "Downloads/$name" to "content://downloads/1" }, onAbort = { aborted = true })
        }
        fun entries(): Map<String, ByteArray> {
            val out = LinkedHashMap<String, ByteArray>()
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    out[entry.name] = zip.readBytes()
                }
            }
            return out
        }
    }

    private fun line(f: ExplorerManifestFile) = ExplorerJson.encodeToString(ExplorerManifestFile.serializer(), f)

    private fun engine(api: FakeExplorerApi, sink: Sink, free: Long? = null, parallel: Int = 4) = ExplorerEngine(
        api = api,
        json = ExplorerJson,
        workRoot = temp.newFolder(),
        saver = { name, _ -> sink.open(name) },
        freeBytes = { free },
        backoffMs = 1,
        parallel = parallel,
        busyTickMs = 1,
    )

    private fun folder(path: String = "Workshop") = ExplorerRequest(title = "W", fileName = "FieldRepository_Dataset_Export.zip", sources = listOf(ExplorerSource(path)))

    @QFeature("X10")
    @Test fun `a folder zip holds every file and lists the one that failed three times`() = runBlocking {
        val api = FakeExplorerApi()
        api.manifest = {
            ndjson(
                listOf(
                    line(ExplorerManifestFile(path = "W/details.txt", content = "hello")),
                    line(ExplorerManifestFile(path = "W/photo.jpg", url = "/api/media/a", mediaId = "a", mediaType = "IMAGE", sizeBytes = 3)),
                    line(ExplorerManifestFile(path = "W/broken.jpg", url = "/api/media/b", mediaId = "b", mediaType = "IMAGE", sizeBytes = 3)),
                ),
                mapOf("X-Dataset-Total" to "3"),
            )
        }
        var brokenTries = 0
        api.paths = { path, _ ->
            if (path.endsWith("/b")) { brokenTries++; error(502) } else bytes(byteArrayOf(1, 2, 3))
        }
        val sink = Sink()
        val ticks = mutableListOf<ExplorerTick>()
        val result = engine(api, sink).run("job1", folder()) { ticks += it }
        val entries = sink.entries()
        assertEquals(setOf("W/details.txt", "W/photo.jpg"), entries.keys)
        assertEquals("hello", String(entries.getValue("W/details.txt")))
        assertEquals(listOf("W/broken.jpg"), result.failed)
        assertEquals(1, result.failedCount)
        assertEquals(2, result.saved)
        assertEquals(3, result.total)
        assertEquals(3, brokenTries)
        assertTrue(sink.finished)
        assertTrue(ticks.last().doneFiles == 3)
    }

    @QFeature("X10")
    @Test fun `a dropped connection resumes from the byte it stopped at`() = runBlocking {
        val api = FakeExplorerApi()
        val whole = ByteArray(200_000) { (it % 251).toByte() }
        api.manifest = { ndjson(listOf(line(ExplorerManifestFile(path = "W/video.mp4", url = "/api/v/1", mediaId = "v", mediaType = "VIDEO", sizeBytes = whole.size.toLong())))) }
        val ranges = mutableListOf<String?>()
        api.paths = { _, range ->
            ranges += range
            if (range == null) {
                bytes(ByteArray(0)).let { ok ->
                    val failing = object : ResponseBody() {
                        override fun contentType() = null
                        override fun contentLength() = whole.size.toLong()
                        override fun source(): BufferedSource = object : ForwardingSource(Buffer().write(whole, 0, 70_000)) {
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                val read = super.read(sink, byteCount)
                                if (read < 0) throw IOException("reset")
                                return read
                            }
                        }.buffer()
                    }
                    Response.success(failing, ok.raw())
                }
            } else {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                bytes(whole.copyOfRange(from, whole.size), code = 206)
            }
        }
        val sink = Sink()
        engine(api, sink).run("job2", folder()) { }
        assertEquals("bytes=70000-", ranges.last())
        assertTrue(whole.contentEquals(sink.entries().getValue("W/video.mp4")))
    }

    @QFeature("X11")
    @Test fun `a failed audio conversion saves the original under its real name`() = runBlocking {
        val api = FakeExplorerApi()
        api.manifest = {
            ndjson(listOf(line(ExplorerManifestFile(path = "W/clip.mp4", url = "/api/a/1", mediaId = "a1", mediaType = "AUDIO", convertToMp4 = true, originalPath = "W/clip.ogg", sizeBytes = 8))))
        }
        api.mediaResponse = { _, format, fallback, _ ->
            assertEquals("mp4", format)
            assertEquals("original", fallback)
            bytes("OggS0000".toByteArray(), headers = mapOf("X-Conversion" to "original"))
        }
        val sink = Sink()
        engine(api, sink).run("job3", folder()) { }
        assertEquals(setOf("W/clip.ogg"), sink.entries().keys)
    }

    @QFeature("X11")
    @Test fun `an older server that sends the original without saying so is named by its bytes`() = runBlocking {
        val api = FakeExplorerApi()
        api.manifest = { ndjson(listOf(line(ExplorerManifestFile(path = "W/talk.mp4", url = "/api/a/2", mediaId = "a2", mediaType = "AUDIO", convertToMp4 = true, sizeBytes = 8)))) }
        api.mediaResponse = { _, _, _, _ -> bytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0)) }
        val sink = Sink()
        engine(api, sink).run("job4", folder()) { }
        assertEquals(setOf("W/talk.webm"), sink.entries().keys)
    }

    @QFeature("X11")
    @Test fun `a converted file keeps the mp4 name`() = runBlocking {
        val api = FakeExplorerApi()
        api.manifest = { ndjson(listOf(line(ExplorerManifestFile(path = "W/talk.mp4", url = "/api/a/3", mediaId = "a3", mediaType = "AUDIO", convertToMp4 = true, sizeBytes = 12)))) }
        api.mediaResponse = { _, _, _, _ -> bytes(byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray(), headers = mapOf("X-Conversion" to "converted")) }
        val sink = Sink()
        engine(api, sink).run("job5", folder()) { }
        assertEquals(setOf("W/talk.mp4"), sink.entries().keys)
    }

    @QFeature("X11")
    @Test fun `a conversion answered by a redirect to the stored original keeps the original's name`() = runBlocking {
        val api = FakeExplorerApi()
        api.manifest = { ndjson(listOf(line(ExplorerManifestFile(path = "W/note.mp4", url = null, mediaId = "a6", mediaType = "AUDIO", convertToMp4 = true, originalPath = "W/note.3gp", sizeBytes = 12)))) }
        val data = byteArrayOf(0, 0, 0, 0x18) + "ftyp3gp4".toByteArray()
        api.mediaResponse = { _, _, _, _ ->
            val asked = okhttp3.Request.Builder().url("http://localhost/api/data/media/a6/download").build()
            val prior = okhttp3.Response.Builder()
                .code(307)
                .message("Temporary Redirect")
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .request(asked)
                .header("X-Conversion", "original")
                .header("Location", "https://storage.example/note.3gp")
                .build()
            val raw = okhttp3.Response.Builder()
                .code(200)
                .message("OK")
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .request(okhttp3.Request.Builder().url("https://storage.example/note.3gp").build())
                .priorResponse(prior)
                .build()
            Response.success(bytes(data).body()!!, raw)
        }
        val sink = Sink()
        engine(api, sink).run("job16", folder()) { }
        assertEquals(setOf("W/note.3gp"), sink.entries().keys)
        assertTrue(data.contentEquals(sink.entries().getValue("W/note.3gp")))
    }

    @QFeature("X11")
    @Test fun `after a broken conversion the original is asked for as the original`() = runBlocking {
        val api = FakeExplorerApi()
        api.manifest = { ndjson(listOf(line(ExplorerManifestFile(path = "W/talk.mp4", url = null, mediaId = "a7", mediaType = "AUDIO", convertToMp4 = true, originalPath = "W/talk.wav", sizeBytes = 8)))) }
        val asked = mutableListOf<String?>()
        api.mediaResponse = { _, format, _, _ ->
            asked += format
            if (format == "mp4") error(503) else bytes("RIFF0000".toByteArray())
        }
        val sink = Sink()
        engine(api, sink).run("job17", folder()) { }
        assertEquals(listOf("mp4", "original"), asked)
        assertEquals(setOf("W/talk.wav"), sink.entries().keys)
    }

    @Test fun `too little free space stops before anything is written`() = runBlocking {
        val api = FakeExplorerApi()
        api.manifest = { ndjson(listOf(line(ExplorerManifestFile(path = "W/big.mp4", url = "/api/v/2", mediaId = "v2", mediaType = "VIDEO", sizeBytes = 5_000_000_000)))) }
        val sink = Sink()
        try {
            engine(api, sink, free = 1_000_000).run("job6", folder()) { }
            fail("expected a space error")
        } catch (expected: ExplorerSpaceException) {
            assertTrue(expected.message!!.startsWith("Not enough space"))
        }
        assertEquals(null, sink.name)
        assertFalse(api.calls.any { it.startsWith("path:") })
    }

    @Test fun `a cancelled download removes its partial file and its scratch folder`() = runBlocking {
        val api = FakeExplorerApi()
        api.manifest = { ndjson((1..3).map { line(ExplorerManifestFile(path = "W/f$it.bin", url = "/api/f/$it", mediaId = "f$it", sizeBytes = 10)) }) }
        api.paths = { _, _ -> delay(60_000); bytes(ByteArray(10)) }
        val sink = Sink()
        val root = temp.newFolder()
        val engine = ExplorerEngine(api, ExplorerJson, root, { name, _ -> sink.open(name) }, backoffMs = 1)
        val running = async { engine.run("job7", folder()) { } }
        delay(200)
        running.cancel()
        try { running.await() } catch (expected: CancellationException) { }
        assertTrue(sink.aborted)
        assertFalse(sink.finished)
        assertFalse(File(root, "job7").exists())
    }

    @QFeature("X3")
    @Test fun `a selection posts its folders and files as one manifest`() = runBlocking {
        val api = FakeExplorerApi()
        api.selection = { ndjson(listOf(line(ExplorerManifestFile(path = "A/x.txt", content = "x")), line(ExplorerManifestFile(path = "y.txt", content = "y")))) }
        val sink = Sink()
        val request = ExplorerRequest(title = "2 selected", fileName = "sel.zip", selection = ExplorerSelectionBody(listOf("w/a"), listOf("m1"), null, true))
        engine(api, sink).run("job8", request) { }
        assertEquals(listOf("manifest-selection:w/a:m1:true"), api.calls.filter { it.startsWith("manifest") })
        assertEquals(setOf("A/x.txt", "y.txt"), sink.entries().keys)
    }

    @Test fun `a busy report waits once and then saves under the server's name`() = runBlocking {
        val api = FakeExplorerApi()
        var first = true
        api.paths = { _, _ ->
            if (first) { first = false; error(503, mapOf("Retry-After" to "2")) } else bytes("xlsx".toByteArray(), headers = mapOf("Content-Disposition" to "attachment; filename=\"DesignWorkshop_Dataset_2026-11-05_Report.xlsx\""))
        }
        val sink = Sink()
        val ticks = mutableListOf<ExplorerTick>()
        val request = ExplorerRequest(title = "Report", fileName = "fallback.xlsx", single = true, mimeType = "application/xlsx", items = listOf(ExplorerItem(path = "", url = "/api/data/report?format=xlsx")))
        val result = engine(api, sink).run("job9", request) { ticks += it }
        assertEquals("DesignWorkshop_Dataset_2026-11-05_Report.xlsx", sink.name)
        assertTrue(ticks.any { it.busySeconds == 2 })
        assertEquals(1, result.saved)
    }

    @Test fun `a manifest of twenty thousand rows streams without holding them`() = runBlocking {
        val api = FakeExplorerApi()
        val count = 20_000
        api.manifest = {
            val body = object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = -1L
                override fun source(): BufferedSource {
                    val buffer = Buffer()
                    for (i in 0 until count) buffer.writeUtf8(line(ExplorerManifestFile(path = "W/n$i.txt", content = "row $i")) + "\n")
                    return buffer
                }
            }
            Response.success(body, okhttp3.Headers.headersOf("Content-Type", "application/x-ndjson"))
        }
        val sink = Sink()
        System.gc()
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val result = engine(api, sink).run("job10", folder()) { }
        System.gc()
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertEquals(count, result.saved)
        assertTrue("heap grew by ${(after - before) / 1_048_576} MB", after - before < 64L * 1024 * 1024)
    }

    @Test fun `a single file is saved under its own name`() = runBlocking {
        val api = FakeExplorerApi()
        api.mediaResponse = { _, format, _, _ -> assertEquals(null, format); bytes("pdf!".toByteArray()) }
        val sink = Sink()
        val request = ExplorerRequest(title = "Notes.pdf", fileName = "Notes.pdf", single = true, items = listOf(ExplorerItem(path = "Notes.pdf", mediaId = "p1", sizeBytes = 4)))
        val result = engine(api, sink).run("job11", request) { }
        assertEquals("Notes.pdf", sink.name)
        assertEquals("application/pdf", result.mimeType)
        assertEquals("pdf!", sink.bytes.toString())
    }

    @Test fun `mime types follow the extension`() {
        assertEquals("audio/mp4", ExplorerEngine.mimeFor("a.m4a"))
        assertEquals("application/zip", ExplorerEngine.mimeFor("a.zip"))
        assertEquals("application/octet-stream", ExplorerEngine.mimeFor("noext"))
        assertEquals("W/a.ogg", ExplorerEngine.originalName(ExplorerItem(path = "W/a.mp4", originalFilename = "rec.ogg")))
        assertEquals("W/a.m4a", ExplorerEngine.originalName(ExplorerItem(path = "W/a.mp4")))
    }
}
