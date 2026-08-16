package com.fieldrepository.app

import com.fieldrepository.app.data.DatasetFileDto
import com.fieldrepository.app.data.ManifestLines
import com.fieldrepository.app.data.ManifestStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader

/**
 * The streamed download manifest — the reader that replaced a 48 MB allocation.
 *
 * WHAT THE DEFECT WAS. `datasetManifest()` and `dataManifest()` were plain typed Retrofit calls, so
 * the whole manifest went through `Serializer.FromString` — `decodeFromString(body.string())` — and
 * `ResponseBody.string()` allocates ONE contiguous `ByteArray` the size of the entire body and
 * copies it into ONE contiguous `String`. The manifest is unbounded in bytes (the caps are on the
 * entry COUNT, and every entry may inline a details.txt body or a full transcript), so on a large
 * repository the handset died with
 * `java.lang.OutOfMemoryError: Failed to allocate a N byte allocation`.
 *
 * WHY THESE ASSERTIONS AND NOT A DOWNLOAD TEST. The fix's whole value is a property of memory, and
 * a unit test cannot watch the allocator. What it CAN pin down is the three rules the fix depends
 * on, each of which silently reinstates the defect or breaks the download if it is ever "tidied":
 *
 *  1. [ManifestStream.isNdjson] must reject `application/json`. It is not politeness towards old
 *     servers — `BufferedReader.readLine()` has no length limit, so handed a single-line JSON
 *     object it allocates the whole body as one String and the OOM is back, now in a code path
 *     nobody is looking at. `test_the_reader_refuses_a_body_that_is_not_newline_delimited`.
 *  2. Entries must be decoded LAZILY, one line at a time. A `toList()` inside the decoder would
 *     make the whole change pointless while every test about content still passed —
 *     `a manifest is decoded one entry at a time`.
 *  3. One undecodable line must not fail the download, and must not be silently forgotten either.
 *
 * The wire shape asserted below is `export.dataset_manifest`'s and `data_browser.data_manifest`'s;
 * both go through `manifest_ndjson_response`, and `backend/tests/test_manifest_stream.py` asserts
 * the server end of the same contract.
 */
class ManifestStreamTest {

    /** The app's decoder settings, which is what `ApiClient.json` hands the real reader. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private fun lines(body: String) = ManifestLines(StringReader(body), json, DatasetFileDto.serializer())

    // -----------------------------------------------------------------------
    // Rule 1: what may reach the line reader at all
    // -----------------------------------------------------------------------

    @Test
    fun `the streamed media type is recognised with and without a charset`() {
        assertTrue(ManifestStream.isNdjson("application/x-ndjson"))
        assertTrue(ManifestStream.isNdjson("application/x-ndjson; charset=utf-8"))
        assertTrue(ManifestStream.isNdjson("Application/X-NDJSON"))
    }

    @Test
    fun `the reader refuses a body that is not newline-delimited`() {
        // A server that predates `?stream=1` ignores the parameter and answers the JSON object.
        // If this ever returns true, readLine() swallows the whole body into one String and the
        // OutOfMemoryError this class exists to prevent comes straight back.
        assertFalse(ManifestStream.isNdjson("application/json"))
        assertFalse(ManifestStream.isNdjson("application/json; charset=utf-8"))
        assertFalse(ManifestStream.isNdjson("text/html"))
        assertFalse(ManifestStream.isNdjson(null))
    }

    // -----------------------------------------------------------------------
    // Rule 2: laziness — the property the whole fix is
    // -----------------------------------------------------------------------

    @Test
    fun `a manifest is decoded one entry at a time`() {
        // A Reader that refuses to give up more than the caller has consumed. If the decoder ever
        // materialises the manifest — a `toList()`, a `readText()`, a `.lines()` — this reader is
        // asked for everything up front and the test fails with the demand it saw.
        var maxLive = 0
        val body = (1..500).joinToString("\n") { """{"path":"f$it.jpg","url":"https://s3/$it"}""" }
        val gate = object : Reader() {
            private val inner = StringReader(body)
            var served = 0
                private set

            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                val n = inner.read(cbuf, off, len)
                if (n > 0) served += n
                return n
            }

            override fun close() = inner.close()
        }

        var consumed = 0
        val reader = ManifestLines(gate, json, DatasetFileDto.serializer())
        for (entry in reader.entries()) {
            consumed++
            if (consumed == 1) maxLive = gate.served
            assertEquals("f$consumed.jpg", entry.path)
        }

        assertEquals(500, consumed)
        // After the FIRST entry the decoder must not have pulled the whole body. BufferedReader
        // fills in 8 KB blocks, so the honest assertion is "one buffer, not 500 entries".
        assertTrue(
            "the decoder read $maxLive of ${body.length} characters before yielding entry 1",
            maxLive < body.length
        )
    }

    // -----------------------------------------------------------------------
    // Rule 3: tolerance, and honesty about what it cost
    // -----------------------------------------------------------------------

    @Test
    fun `one unreadable line loses that file and not the download`() {
        val reader = lines(
            """
            {"path":"a.txt","content":"first"}
            {"path":"b.jpg",  <-- this line is damage
            {"path":"c.txt","content":"third"}
            """.trimIndent()
        )

        val paths = reader.entries().map { it.path }.toList()

        assertEquals(listOf("a.txt", "c.txt"), paths)
        // Counted, not swallowed: the caller adds this to its fetch failures so the archive is
        // reported as short instead of presenting itself as complete.
        assertEquals(1, reader.unreadable)
    }

    @Test
    fun `blank lines are not damage`() {
        // The server writes chunks of 200 entries each terminated with a newline, so a trailing
        // empty line is ordinary output. Counting it would make every download report a phantom
        // missing file.
        val reader = lines("{\"path\":\"a.txt\",\"content\":\"x\"}\n\n")

        assertEquals(1, reader.entries().count())
        assertEquals(0, reader.unreadable)
    }

    @Test
    fun `an entry keeps its inline content and its url`() {
        val reader = lines(
            """
            {"path":"Workshops/W/details.txt","content":"Title: W\nPlace: Bagru"}
            {"path":"Workshops/W/photo.jpg","url":"https://bucket/photo.jpg"}
            """.trimIndent()
        )

        val entries = reader.entries().toList()

        assertEquals("Title: W\nPlace: Bagru", entries[0].content)
        assertEquals(null, entries[0].url)
        assertEquals("https://bucket/photo.jpg", entries[1].url)
        assertEquals(null, entries[1].content)
    }

    @Test
    fun `an unknown field a newer server adds does not break the reader`() {
        // `ignoreUnknownKeys` is ApiClient's setting and this path has to inherit it: a manifest
        // entry gaining a field must not turn every installed handset's download into 20,000
        // unreadable lines.
        val reader = lines("""{"path":"a.jpg","url":"https://s3/a","checksum":"deadbeef"}""")

        assertEquals("a.jpg", reader.entries().single().path)
        assertEquals(0, reader.unreadable)
    }

    // -----------------------------------------------------------------------
    // The headers, which carry everything NDJSON has no wrapper object for
    // -----------------------------------------------------------------------

    @Test
    fun `the counts and the truncation flag survive the headers`() {
        assertEquals(4312, ManifestStream.count("4312"))
        assertEquals(4312, ManifestStream.count(" 4312 "))
        assertTrue(ManifestStream.flag("true"))
        assertFalse(ManifestStream.flag("false"))
    }

    @Test
    fun `a missing count reads as unknown rather than as zero`() {
        // -1, never 0. A total of 0 would render as "142 of 0 files" and, worse, would let the
        // caller report a complete archive as empty.
        assertEquals(-1, ManifestStream.count(null))
        assertEquals(-1, ManifestStream.count("not a number"))
        // An old server saying nothing is not a server claiming the archive is whole.
        assertFalse(ManifestStream.flag(null))
    }
}
