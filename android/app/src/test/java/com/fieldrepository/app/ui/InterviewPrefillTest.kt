package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ArtisanDetailDto
import com.fieldrepository.app.data.CraftDto
import com.fieldrepository.app.data.MediaFileDto
import com.fieldrepository.app.data.QuestionnaireQuestionDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** B8 and A7 on the handset: the interview fills what the artisan's record and the recordings know. */
class InterviewPrefillTest {

    private val ramesh = ArtisanDetailDto(
        id = "a1",
        name = "Ramesh Chhipa",
        place = "Bagru",
        gender = "Male",
        phone = "",
        email = "ramesh@example.org",
        experienceYears = 22,
        craft = CraftDto(id = "c1", name = "Block printing"),
    )

    private fun q(id: String, code: String, n: Int, prompt: String, field: String? = null) =
        QuestionnaireQuestionDto(id = id, sectionCode = code, sectionTitle = code, prompt = prompt, sortOrder = n, artisanField = field)

    private val today = LocalDate.of(2026, 10, 17)

    @Test
    fun `a mapped question is answered from the artisan's field`() {
        val fills = artisanPrefill(
            listOf(q("q1", "B", 1, "Years in the craft", field = "experienceYears"), q("q2", "B", 2, "Gender", field = "gender")),
            ramesh, interviewer = "Asha", today = today
        )
        assertEquals(mapOf("q1" to "22", "q2" to "Male"), fills)
    }

    @Test
    fun `an unmapped respondent question still falls back to the keyword guess, and only there`() {
        val fills = artisanPrefill(
            listOf(
                q("r1", "RESP", 1, "Name of the respondent"),
                q("r2", "RESP", 2, "Contact number"),
                q("r3", "RESP", 3, "Date of interview"),
                q("x1", "B", 1, "Name of the main tool"),
            ),
            ramesh, interviewer = "Asha", today = today
        )
        assertEquals("Ramesh Chhipa", fills["r1"])
        assertEquals("an empty phone falls back to email", "ramesh@example.org", fills["r2"])
        assertEquals("17/10/2026", fills["r3"])
        assertNull("only RESP is guessed", fills["x1"])
    }

    @Test
    fun `the automatic title matches the server's`() {
        assertEquals("Ramesh Chhipa · Craft questionnaire · 17 Oct", automaticInterviewTitle("Ramesh Chhipa", 1, "Craft questionnaire", today))
        assertEquals("Ramesh Chhipa and others · 3 Sep", automaticInterviewTitle("Ramesh Chhipa", 3, null, LocalDate.of(2026, 9, 3)))
        assertEquals("17 Oct", automaticInterviewTitle(null, 0, " ", today))
    }

    private val vectors = listOf(
        "Clay comes from the river bank." to "Clay comes from the river bank.",
        "**Speaker 1:** We soak it for two days." to "Speaker 1: We soak it for two days.",
        "  First line\n\n---\n\nSecond line  " to "First line\n\nSecond line",
        "**A:** one\n\n\n\n**B:** two" to "A: one\n\nB: two",
        "Keep *single* stars and 3 - 4 dashes" to "Keep *single* stars and 3 - 4 dashes",
        "" to "",
        "---" to "",
    )

    @Test
    fun `a transcript becomes answer text with its Markdown taken out and its words kept`() {
        vectors.forEach { (transcript, answer) -> assertEquals(transcript, answer, transcriptAnswerText(transcript)) }
    }

    @Test
    fun `the shared vectors agree, when this checkout carries them`() {
        val file = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "shared/transcript-answer-vectors.json") }
            .firstOrNull { it.isFile } ?: return
        val rows = Json.parseToJsonElement(file.readText()).jsonObject["vectors"] as JsonArray
        rows.forEach { row ->
            val o = row.jsonObject
            assertEquals(o["answer"]!!.jsonPrimitive.content, transcriptAnswerText(o["transcript"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun `a typed answer is never offered a transcript`() {
        assertNull(transcriptOffer("They use river clay.", "**A:** Clay"))
        assertEquals("A: Clay", transcriptOffer("  ", "**A:** Clay"))
        assertNull(transcriptOffer("", "---"))
    }

    @Test
    fun `a clip finds its question by caption, and the newest transcript wins`() {
        val questions = listOf(q("d3", "D", 3, "Where is the clay from?"), q("d4", "D", 4, "How long is it soaked?"))
        fun clip(id: String, caption: String, text: String?, at: String) =
            MediaFileDto(id = id, originalFilename = "$id.m4a", mediaType = "AUDIO", caption = caption, transcriptText = text, createdAt = at)
        val found = questionTranscripts(
            listOf(
                clip("m1", "Question audio: D3 Where is the clay from?", "Old take", "2026-10-17T09:00:00Z"),
                clip("m2", "question audio: d3 - Where is the clay from?", "**A:** River bank", "2026-10-17T10:00:00Z"),
                clip("m3", "Section audio: D Clay", "A whole section", "2026-10-17T11:00:00Z"),
                clip("m4", "Question audio: D4", null, "2026-10-17T11:00:00Z"),
            ),
            questions
        )
        assertEquals(mapOf("d3" to "A: River bank"), found)
    }

    @Test
    fun `the builder offers the server's twelve artisan fields`() {
        assertEquals(
            listOf("name", "localName", "craft", "place", "gender", "phone", "email", "dateOfBirth", "experienceYears", "address", "interviewDate", "interviewer"),
            ARTISAN_FIELD_OPTIONS.map { it.first }
        )
        assertTrue(ARTISAN_FIELD_OPTIONS.all { it.second.isNotBlank() })
    }
}
