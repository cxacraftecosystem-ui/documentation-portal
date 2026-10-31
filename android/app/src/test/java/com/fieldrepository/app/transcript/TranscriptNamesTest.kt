package com.fieldrepository.app.transcript

import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.example
import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.json
import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.repoRoot
import com.fieldrepository.app.transcript.data.TranscriptNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every transcript row of `shared/naming-golden.json` — Transcript, Subtitles, Subtitles-With-Speakers,
 * Quote-Card, whoever added it (the web stream adds the viewer's download rows there) — comes out of
 * [TranscriptNames] exactly, so the phone, the web and the server name one file one way.
 */
class TranscriptNamesTest {
    private val transcriptWhats = setOf("Transcript", "Subtitles", "Subtitles-With-Speakers", "Quote-Card")

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    @Test fun `the golden table's transcript rows are the phone's names`() {
        val golden = json.parseToJsonElement(File(repoRoot(), "shared/naming-golden.json").readText()).jsonObject
        val rows = golden["fileNames"]!!.jsonArray.map { it.jsonObject }.filter { it["input"]!!.jsonObject.str("what") in transcriptWhats }
        assertTrue("the golden table has transcript rows", rows.size >= 2)
        for (row in rows) {
            val input = row["input"]!!.jsonObject
            val got = TranscriptNames.fileName(
                what = input.str("what")!!,
                ext = input.str("ext").orEmpty(),
                entity = input.str("entity") ?: "Interview",
                portal = input.str("portal"),
                date = input.str("date"),
                time = input["time"]?.jsonPrimitive?.booleanOrNull ?: false,
                place = input.str("place"),
                craft = input.str("craft"),
                subject = input.str("subject"),
                sequence = input.str("sequence"),
                n = input["n"]?.jsonPrimitive?.intOrNull,
            )
            assertEquals(row.str("name"), got)
        }
    }

    @Test fun `a name built from the view leaves out what the view does not know`() {
        val portal = com.fieldrepository.app.data.NAMING_PORTAL
        assertEquals("${portal}_Interview_2026-06-24_Giriraj-Prasad-Chhipa_Transcript.md", TranscriptNames.forView(example, "md"))
        assertEquals("${portal}_Interview_2026-06-24_Giriraj-Prasad-Chhipa_Subtitles-With-Speakers.vtt", TranscriptNames.forView(example, "vtt"))
        assertEquals("${portal}_Interview_2026-06-24_Giriraj-Prasad-Chhipa_Quote-Card.png", TranscriptNames.forView(example, "png"))
    }
}
