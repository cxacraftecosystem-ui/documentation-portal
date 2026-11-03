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

class ManifestStreamTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    private fun lines(body: String) = ManifestLines(StringReader(body), json, DatasetFileDto.serializer())

    @Test
    fun `the streamed media type is recognised with and without a charset`() {
        assertTrue(ManifestStream.isNdjson("application/x-ndjson"))
        assertTrue(ManifestStream.isNdjson("application/x-ndjson; charset=utf-8"))
        assertTrue(ManifestStream.isNdjson("Application/X-NDJSON"))
    }

    @Test
    fun `the reader refuses a body that is not newline-delimited`() {
        assertFalse(ManifestStream.isNdjson("application/json"))
        assertFalse(ManifestStream.isNdjson("application/json; charset=utf-8"))
        assertFalse(ManifestStream.isNdjson("text/html"))
        assertFalse(ManifestStream.isNdjson(null))
    }

    @Test
    fun `a manifest is decoded one entry at a time`() {
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
        assertTrue(
            "the decoder read $maxLive of ${body.length} characters before yielding entry 1",
            maxLive < body.length
        )
    }

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
        assertEquals(1, reader.unreadable)
    }

    @Test
    fun `blank lines are not damage`() {
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
        val reader = lines("""{"path":"a.jpg","url":"https://s3/a","checksum":"deadbeef"}""")

        assertEquals("a.jpg", reader.entries().single().path)
        assertEquals(0, reader.unreadable)
    }

    @Test
    fun `the counts and the truncation flag survive the headers`() {
        assertEquals(4312, ManifestStream.count("4312"))
        assertEquals(4312, ManifestStream.count(" 4312 "))
        assertTrue(ManifestStream.flag("true"))
        assertFalse(ManifestStream.flag("false"))
    }

    @Test
    fun `a missing count reads as unknown rather than as zero`() {
        assertEquals(-1, ManifestStream.count(null))
        assertEquals(-1, ManifestStream.count("not a number"))
        assertFalse(ManifestStream.flag(null))
    }
}
