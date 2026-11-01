package com.fieldrepository.app.transcript

import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.TranscriptPreview
import com.fieldrepository.app.transcript.data.TranscriptPreviewDto
import com.fieldrepository.app.transcript.data.TranscriptReading
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.transcript.data.TurnTranslation
import com.fieldrepository.app.transcript.ui.SpeakerPalette
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.0.6: list previews, speaker colours and reading mode. The preview and the colour rule are the
 * server's (`transcript/preview.py`); every case of the contract's `fixtures.preview` and
 * `fixtures.speakerColor` is reproduced here, and the palette is the contract's `speakerPalette`.
 */
class TranscriptPreviewTest {
    private val contract get() = TranscriptContractTest.contract
    private val fixtures: JsonObject get() = contract["fixtures"]!!.jsonObject

    @Test fun `every speakerColor case of the contract`() {
        val cases = fixtures["speakerColor"]!!.jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size >= 9)
        for (case in cases) {
            val (key, label, expected) = case.jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals("$key / $label", expected, TranscriptPreview.colorKey(key, label))
        }
    }

    @Test fun `every preview case of the contract`() {
        val cases = fixtures["preview"]!!.jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size >= 5)
        for (case in cases) {
            val c = case.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val got = if (c["markdown"] != null) {
                TranscriptPreview.fromMarkdown(c["markdown"]!!.jsonPrimitive.content)
            } else {
                TranscriptPreview.preview(c["turns"]!!.jsonArray.map { t ->
                    val o = t.jsonObject
                    TranscriptPreview.Turn(o["speakerKey"]?.jsonPrimitive?.content, o["speaker"]?.jsonPrimitive?.content, o["text"]!!.jsonPrimitive.content)
                })
            }
            val expected = c["expected"]!!.let { if (it is JsonNull) null else TranscriptContractTest.json.decodeFromJsonElement(TranscriptPreviewDto.serializer(), it) }
            assertEquals(name, expected, got)
        }
    }

    @Test fun `the palette is the contract's, light and dark`() {
        val palette = contract["speakerPalette"]!!.jsonObject
        for (key in listOf("s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "neutral")) {
            val entry = palette[key]!!.jsonObject
            assertEquals(key, entry["light"]!!.jsonPrimitive.content.uppercase(), hex(SpeakerPalette.argb(key, dark = false)))
            assertEquals(key, entry["dark"]!!.jsonPrimitive.content.uppercase(), hex(SpeakerPalette.argb(key, dark = true)))
        }
        assertEquals(SpeakerPalette.argb("neutral", false), SpeakerPalette.argb("nobody", false))
    }

    @Test fun `every speaker colour reads as text on the theme's surface`() {
        for ((key, argb) in SpeakerPalette.light) assertTrue("$key light", contrast(argb, 0xFFFFFFFF) >= 4.5)
        for ((key, argb) in SpeakerPalette.dark) assertTrue("$key dark", contrast(argb, 0xFF121212) >= 4.5)
    }

    @Test fun `the raw recordings-list markdown reads as speech`() {
        val p = TranscriptPreview.fromMarkdown("Transcript\n\n**Speaker 1:** Wow! That is *lovely*.\n**Speaker 2:** Thank you.")!!
        assertEquals(listOf("Speaker 1", "Speaker 2"), p.lines.map { it.speaker })
        assertEquals("Wow! That is lovely.", p.lines[0].text)
        assertFalse(p.text.contains("*"))
        assertFalse(p.text.startsWith("Transcript"))
        assertNull(TranscriptPreview.fromMarkdown("Transcript\n\n"))
    }

    @Test fun `a snippet with transcript markdown is cleaned, any other is left alone`() {
        assertEquals("Speaker 1: dyed with indigo", TranscriptPreview.cleanSnippet("**Speaker 1:** dyed with *indigo*"))
        assertEquals("plain words * stay", TranscriptPreview.cleanSnippet("plain words * stay"))
    }

    @Test fun `a turn's colour is the server's, else its key's`() {
        assertEquals("s5", TranscriptPreview.colorKeyOf(turn("a", "speaker_0", "x").copy(colorKey = "s5")))
        assertEquals("s1", TranscriptPreview.colorKeyOf(turn("a", "speaker_1", "x")))
        assertEquals("neutral", TranscriptPreview.colorKeyOf(turn("a", null, "x")))
    }

    @Test fun `reading mode joins a speaker's run into one paragraph`() {
        val turns = listOf(
            turn("a", "speaker_0", "How do you", english = "How do you"),
            turn("b", "speaker_0", "make these?", english = null),
            turn("c", "speaker_1", "With clay.", english = "With clay.", already = true),
            turn("d", "speaker_0", "And then?", english = "And then?"),
        )
        val paragraphs = TranscriptReading.paragraphs(turns)
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), paragraphs.map { it.turnIds })
        assertEquals("How do you make these?", paragraphs[0].original)
        // English falls back to the original turn by turn; an all-English run has nothing more to show.
        assertEquals("How do you make these?", paragraphs[0].english)
        assertNull(paragraphs[1].english)
        assertEquals("With clay.", TranscriptReading.text(paragraphs[1], CopyTrack.ENGLISH))
        assertEquals("s0", paragraphs[0].colorKey)
        assertEquals(1, TranscriptReading.paragraphOf(paragraphs, "c"))
        assertEquals(-1, TranscriptReading.paragraphOf(paragraphs, null))
    }

    private fun turn(id: String, key: String?, text: String, english: String? = null, already: Boolean = false) = TranscriptTurn(
        id = id, index = id[0] - 'a', speakerKey = key, speakerName = key?.let { "Speaker ${it.last().digitToInt() + 1}" } ?: "", startMs = 0, endMs = 1, text = text,
        translation = TurnTranslation(english, if (already) "ALREADY_ENGLISH" else if (english == null) "PENDING" else "TRANSLATED"),
    )

    private fun hex(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

    private fun luminance(argb: Long): Double {
        fun channel(c: Long): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    private fun contrast(a: Long, b: Long): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
