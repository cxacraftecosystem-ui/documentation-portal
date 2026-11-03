package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.QFeature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptInsightEditingTest {

    private fun view(
        state: String = "READY",
        edited: Boolean = false,
        canEdit: Boolean = true,
        canRegenerate: Boolean = true,
        withInsight: Boolean = true
    ) = TranscriptInsightViewDto(
        mediaId = "m1",
        state = state,
        canEdit = canEdit,
        canRegenerate = canRegenerate,
        insight = if (withInsight) {
            TranscriptInsightDto(
                id = "i1",
                version = 3,
                summaryLines = listOf("One.", "Two."),
                quotes = listOf(InsightQuoteDto(original = "मिट्टी", english = "Mud", speaker = "Artisan")),
                translation = InsightTranslationDto(status = "COMPLETED", text = "We print."),
                edited = if (edited) InsightEditedDto(at = "2026-11-04T10:00:00Z") else null
            )
        } else {
            null
        }
    )

    private fun encode(body: TranscriptInsightPatchRequest): JsonObject =
        Json.parseToJsonElement(ApiClient.json.encodeToString(TranscriptInsightPatchRequest.serializer(), body)).jsonObject

    @Test
    @QFeature("H1")
    fun `the patch carries the version, the cleaned lines and quotes, and the English only when edited`() {
        val insight = view().insight!!
        val body = TranscriptInsightEditing.patch(
            insight,
            listOf(" First ", "", "Second", "", ""),
            listOf(InsightQuoteDto(original = " मिट्टी ", english = " Mud ", speaker = " "), InsightQuoteDto(original = "  ")),
            null
        )!!
        val json = encode(body)
        assertEquals(3, json["version"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("First", "Second"), json["summaryLines"]!!.jsonArray.map { it.jsonPrimitive.content })
        val quote = json["quotes"]!!.jsonArray.single().jsonObject
        assertEquals("मिट्टी", quote["original"]!!.jsonPrimitive.content)
        assertEquals("Mud", quote["english"]!!.jsonPrimitive.content)
        assertFalse(quote.containsKey("speaker"))
        assertFalse(json.containsKey("translationText"))
        val withEnglish = encode(TranscriptInsightEditing.patch(insight, listOf("A"), emptyList(), "We print dabu.")!!)
        assertEquals("We print dabu.", withEnglish["translationText"]!!.jsonPrimitive.content)
    }

    @Test
    @QFeature("H1")
    fun `a summary with no line is refused before it is sent`() {
        assertNull(TranscriptInsightEditing.patch(view().insight!!, listOf(" ", ""), emptyList(), null))
        assertEquals("Add at least one line.", TranscriptInsightEditing.NEED_A_LINE)
    }

    @Test
    @QFeature("H1")
    fun `regenerate always says whether edits may be discarded`() {
        val keep = ApiClient.json.encodeToString(TranscriptInsightRegenerateRequest.serializer(), TranscriptInsightRegenerateRequest(false))
        val replace = ApiClient.json.encodeToString(TranscriptInsightRegenerateRequest.serializer(), TranscriptInsightRegenerateRequest(true))
        assertEquals("""{"discardEdits":false}""", keep)
        assertEquals("""{"discardEdits":true}""", replace)
    }

    @Test
    @QFeature("H1")
    fun `the edit form keeps at most five lines of four hundred and eight quotes`() {
        assertEquals(listOf("1", "2", "3", "4", "5"), TranscriptInsightEditing.cleanSummaryLines(listOf("1", " ", "2", "3", "4", "5", "6")))
        assertEquals(400, TranscriptInsightEditing.cleanSummaryLines(listOf("x".repeat(500))).single().length)
        assertEquals(listOf("a", "b", "", "", ""), TranscriptInsightEditing.editRows(listOf("a", "b")))
        assertEquals(5, TranscriptInsightEditing.editRows(listOf("1", "2", "3", "4", "5", "6")).size)
        val many = List(10) { InsightQuoteDto(original = "q$it") }
        assertEquals(8, TranscriptInsightEditing.cleanQuotes(many).size)
    }

    @Test
    @QFeature("H1")
    fun `regenerating asks first only over a researcher's edits`() {
        assertTrue(TranscriptInsightEditing.needsRegenerateConfirm(view(edited = true)))
        assertFalse(TranscriptInsightEditing.needsRegenerateConfirm(view()))
        assertEquals("Regenerating replaces your edits.", TranscriptInsightEditing.REPLACE_EDITS)
    }

    @Test
    @QFeature("H1")
    fun `the controls follow the server's permissions and stand aside while a summary is written`() {
        assertTrue(TranscriptInsightEditing.mayEdit(view(), editable = true))
        assertFalse(TranscriptInsightEditing.mayEdit(view(), editable = false))
        assertFalse(TranscriptInsightEditing.mayEdit(view(canEdit = false), editable = true))
        assertFalse(TranscriptInsightEditing.mayEdit(view(state = "PROCESSING"), editable = true))
        assertFalse(TranscriptInsightEditing.mayRegenerate(view(state = "QUEUED"), editable = true))
        assertFalse(TranscriptInsightEditing.mayRegenerate(view(canRegenerate = false), editable = true))
        assertEquals("Regenerate", TranscriptInsightEditing.regenerateLabel(view()))
        assertEquals("Summarise", TranscriptInsightEditing.regenerateLabel(view(state = "NOT_GENERATED", withInsight = false)))
    }

    @Test
    @QFeature("H1")
    fun `a transcript never summarised shows its Summarise button only to someone who may run it`() {
        val fresh = view(state = "NOT_GENERATED", withInsight = false)
        assertTrue(TranscriptInsightEditing.isShown(fresh, editable = true))
        assertFalse(TranscriptInsightEditing.isShown(fresh, editable = false))
        assertFalse(TranscriptInsightEditing.isShown(view(state = "NOT_GENERATED", withInsight = false, canRegenerate = false), editable = true))
        assertFalse(TranscriptInsightEditing.isShown(view(state = "NO_TRANSCRIPT", withInsight = false), editable = true))
    }
}
