package com.fieldrepository.app.transcript

import com.fieldrepository.app.transcript.data.AskAnswer
import com.fieldrepository.app.transcript.data.BudgetMeter
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.PipelineStatus
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptFormat
import com.fieldrepository.app.transcript.data.TranslationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE PHONE'S COPY TEXT IS THE SERVER'S, BYTE FOR BYTE (`shared/transcript-contract.json`).
 *
 * The contract lets a client build copy text itself only if it reproduces `fixtures.copy` exactly;
 * every fixture is checked here against [TranscriptFormat] over the contract's own example view,
 * which is decoded through the app's models (so a renamed field fails here, not on a handset).
 */
class TranscriptContractTest {
    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true; coerceInputValues = true }

        fun repoRoot(): File = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "shared/transcript-contract.json").isFile }
            ?: error("shared/transcript-contract.json not found above ${File(".").absolutePath}")

        val contract: JsonObject by lazy { json.parseToJsonElement(File(repoRoot(), "shared/transcript-contract.json").readText()).jsonObject }

        val example: Transcript by lazy {
            json.decodeFromJsonElement(Transcript.serializer(), contract["examples"]!!.jsonObject["TranscriptView"]!!)
        }
    }

    private val fixtures get() = contract["fixtures"]!!.jsonObject
    private fun copy(key: String) = fixtures["copy"]!!.jsonObject[key]!!.jsonPrimitive.content

    @Test fun `the example view decodes through the app's models`() {
        val v = example
        assertEquals("cm_media_1", v.mediaId)
        assertEquals(PipelineStatus.READY, v.status)
        assertEquals(listOf("hi", "en"), v.languages.map { it.code })
        assertEquals(2, v.turns.size)
        assertEquals(6, v.turns[0].words.size)
        assertEquals(listOf(listOf(3, 7)), v.turns[1].lowConfidence)
        assertEquals(TranslationStatus.TRANSLATED, v.turns[0].translation.status)
        assertEquals(listOf("Scribe v2", "Gemini 3.8 Flash"), v.badges.map { it.label })
        assertTrue(v.permissions.edit)
        assertFalse(v.permissions.transcribeNow)
        assertEquals(3, v.revision)
    }

    @Test fun `a read-only view decodes with its reason and offers no edit`() {
        assertEquals(null, example.readOnly)
        val held = json.parseToJsonElement(
            """{"mediaId":"m","status":"READY","permissions":{"edit":false,"ask":true},""" +
                """"readOnly":{"code":"HELD_POST","message":"Read-only: part of a workshop you oversee."}}"""
        )
        val v = json.decodeFromJsonElement(Transcript.serializer(), held)
        assertEquals("HELD_POST", v.readOnly?.code)
        assertEquals("Read-only: part of a workshop you oversee.", v.readOnly?.message)
        assertFalse(v.permissions.edit)
        assertFalse(v.permissions.mapSpeakers)
        assertTrue(v.permissions.ask)
    }

    @Test fun `the other examples decode too`() {
        val examples = contract["examples"]!!.jsonObject
        val budget = json.decodeFromJsonElement(BudgetMeter.serializer(), examples["BudgetMeter"]!!)
        assertEquals("ALERT", budget.state)
        assertEquals(81.5, budget.spentUsd, 0.0)
        val ask = json.decodeFromJsonElement(AskAnswer.serializer(), examples["AskAnswer"]!!)
        assertTrue(ask.covered)
        assertEquals(listOf("turn_b"), ask.citations.map { it.turnId })
        assertEquals(9000L, ask.citations[0].startMs)
        assertFalse(json.decodeFromJsonElement(AskAnswer.serializer(), examples["AskAnswerNotCovered"]!!).covered)
    }

    @Test fun `timestamps and lengths match every case`() {
        for (case in fixtures["timestamp"]!!.jsonObject["cases"]!!.jsonArray) {
            val (ms, text) = (case as JsonArray).let { it[0].jsonPrimitive.long to it[1].jsonPrimitive.content }
            assertEquals("timestamp($ms)", text, TranscriptFormat.timestamp(ms))
        }
        for (case in fixtures["length"]!!.jsonObject["cases"]!!.jsonArray) {
            val (ms, text) = (case as JsonArray).let { it[0].jsonPrimitive.long to it[1].jsonPrimitive.content }
            assertEquals("length($ms)", text, TranscriptFormat.length(ms))
        }
    }

    @Test fun `copy all in each track equals the fixture`() {
        assertEquals(copy("all_original"), TranscriptFormat.all(example, CopyTrack.ORIGINAL))
        assertEquals(copy("all_english"), TranscriptFormat.all(example, CopyTrack.ENGLISH))
        assertEquals(copy("all_both"), TranscriptFormat.all(example, CopyTrack.BOTH))
    }

    @Test fun `copy selected has no header`() {
        assertEquals(copy("selected_both_turn_b"), TranscriptFormat.selected(example, listOf("turn_b"), CopyTrack.BOTH))
    }

    @Test fun `copy selected keeps transcript order whatever the ticking order`() {
        assertEquals(
            TranscriptFormat.selected(example, listOf("turn_a", "turn_b"), CopyTrack.ORIGINAL),
            TranscriptFormat.selected(example, listOf("turn_b", "turn_a"), CopyTrack.ORIGINAL)
        )
    }

    @Test fun `a single turn's copy is its text only`() {
        val a = example.turns.first { it.id == "turn_a" }
        assertEquals(copy("single_original_turn_a"), TranscriptFormat.body(a, CopyTrack.ORIGINAL))
        assertEquals(copy("single_english_turn_a"), TranscriptFormat.body(a, CopyTrack.ENGLISH))
        assertEquals(copy("single_both_turn_a"), TranscriptFormat.body(a, CopyTrack.BOTH))
    }

    @Test fun `subtitles equal the fixtures`() {
        assertEquals(fixtures["srt_original"]!!.jsonPrimitive.content, TranscriptFormat.srt(example, CopyTrack.ORIGINAL))
        assertEquals(fixtures["vtt_english"]!!.jsonPrimitive.content, TranscriptFormat.vtt(example, CopyTrack.ENGLISH))
    }

    // ── The rules under fixtures.copy, each one ──────────────────────────────────────────────

    private val a get() = example.turns.first { it.id == "turn_a" }

    @Test fun `already English prints once under both, and is the English`() {
        val t = a.copy(text = "We use indigo.", translation = a.translation.copy(text = null, status = TranslationStatus.ALREADY_ENGLISH))
        assertEquals("We use indigo.", TranscriptFormat.body(t, CopyTrack.BOTH))
        assertEquals("We use indigo.", TranscriptFormat.body(t, CopyTrack.ENGLISH))
    }

    @Test fun `no English text prints the original under english and nothing under both`() {
        for (status in listOf(TranslationStatus.PENDING, TranslationStatus.FAILED)) {
            val t = a.copy(translation = a.translation.copy(text = null, status = status))
            assertEquals(a.text, TranscriptFormat.body(t, CopyTrack.ENGLISH))
            assertEquals(a.text, TranscriptFormat.body(t, CopyTrack.BOTH))
        }
    }

    @Test fun `stale English still prints`() {
        val t = a.copy(translation = a.translation.copy(status = TranslationStatus.STALE))
        assertEquals("How do you make these colours?", TranscriptFormat.body(t, CopyTrack.ENGLISH))
    }

    @Test fun `multi-line English quotes every line`() {
        val t = a.copy(translation = a.translation.copy(text = "One.\nTwo."))
        assertEquals("${a.text}\n> One.\n> Two.", TranscriptFormat.body(t, CopyTrack.BOTH))
    }

    @Test fun `turn text is never escaped and speaker names lose star, backtick and bar`() {
        val t = a.copy(text = "a *b* `c`", speakerName = "*Na`n|dini*")
        assertEquals("**Nandini** · `00:00:04`\na *b* `c`", TranscriptFormat.block(t, CopyTrack.ORIGINAL))
    }

    @Test fun `no fixture ends in a newline and neither does the phone`() {
        for (track in CopyTrack.entries) assertFalse(TranscriptFormat.all(example, track).endsWith("\n"))
        assertTrue(contract["enums"]!!.jsonObject["copyTrack"]!!.jsonArray.map { (it as JsonPrimitive).content } == CopyTrack.entries.map { it.wire })
    }
}
