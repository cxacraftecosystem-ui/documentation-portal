package com.fieldrepository.app.transcript

import com.fieldrepository.app.transcript.data.AskAnswer
import com.fieldrepository.app.transcript.data.BudgetMeter
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.EnglishNotice
import com.fieldrepository.app.transcript.data.PipelineStatus
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptBadge
import com.fieldrepository.app.transcript.data.TranscriptFormat
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranslationStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        assertEquals(listOf("Scribe v3", "Gemini 3.8 Flash"), v.badges.map { it.label })
        assertTrue(v.permissions.edit)
        assertFalse(v.permissions.transcribeNow)
        assertEquals(3, v.revision)
    }

    @Test fun `a badge shows the server's label and names the current model only when the label is missing`() {
        assertEquals("Scribe v2", TranscriptBadge("stt", "elevenlabs", "scribe_v2", "Scribe v2").display())
        assertEquals("Scribe v3", TranscriptBadge("stt", "elevenlabs", "scribe_v3", "").display())
        assertEquals("Scribe v3", TranscriptStrings.badgeStt)
        assertEquals("Gemini 3.8 Flash", TranscriptBadge("translation", "gemini", "gemini-3.8-flash", "").display())
        assertEquals("nova-3", TranscriptBadge("other", "deepgram", "nova-3", "").display())
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

    @Test fun `copy all without timestamps equals the fixture and carries no time`() {
        val plain = TranscriptFormat.all(example, CopyTrack.BOTH, timestamps = false)
        assertEquals(copy("all_both_no_timestamps"), plain)
        assertFalse(plain.contains("`00:"))
    }

    @Test fun `each speaker carries a sample to be heard by, and the audio says when it is cleaned`() {
        assertEquals(listOf(4000L to 7600L, 9000L to 15200L), example.speakers.map { it.sample!!.startMs to it.sample!!.endMs })
        assertFalse(example.audio.cleaned)
        val cleaned = json.decodeFromString(
            Transcript.serializer(),
            """{"mediaId":"m","status":"READY","audio":{"url":"https://x/c.mp3","cleaned":true,"originalUrl":"https://x/o.m4a"}}"""
        )
        assertTrue(cleaned.audio.cleaned)
        assertEquals("https://x/o.m4a", cleaned.audio.originalUrl)
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

    @Test fun `English pasted whole copies exactly as fixtures manualEnglish`() {
        val manual = fixtures["manualEnglish"]!!.jsonObject
        fun fix(key: String) = manual[key]!!.jsonPrimitive.content
        val pasted = example.copy(manualEnglish = fix("text"))
        assertEquals(fix("all_english"), TranscriptFormat.all(pasted, CopyTrack.ENGLISH))
        assertEquals(fix("all_both"), TranscriptFormat.all(pasted, CopyTrack.BOTH))
        assertEquals(fix("all_both_no_timestamps"), TranscriptFormat.all(pasted, CopyTrack.BOTH, timestamps = false))
        assertEquals(copy("all_original"), TranscriptFormat.all(pasted, CopyTrack.ORIGINAL))
        assertEquals(copy("selected_both_turn_b"), TranscriptFormat.selected(pasted, listOf("turn_b"), CopyTrack.BOTH))
    }

    @Test fun `the example carries the contract 3 fields and a declined view decodes`() {
        assertEquals(null, example.englishNotice)
        assertEquals(null, example.manualEnglish)
        assertFalse(example.permissions.addEnglish)
        val declined = json.parseToJsonElement(
            """{"mediaId":"m","status":"READY","englishNotice":"DECLINED","manualEnglish":null,"permissions":{"edit":true,"addEnglish":true}}"""
        )
        val v = json.decodeFromJsonElement(Transcript.serializer(), declined)
        assertEquals(EnglishNotice.DECLINED, v.englishNotice)
        assertTrue(v.permissions.addEnglish)
        val notices = contract["enums"]!!.jsonObject["englishNotice"]!!.jsonArray.map { (it as? JsonPrimitive)?.contentOrNull }
        assertEquals(listOf(EnglishNotice.DECLINED, EnglishNotice.UNAVAILABLE, null), notices)
        val routes = contract["routes"]!!.jsonArray.map { it.jsonObject }
        assertTrue(routes.any { it["method"]!!.jsonPrimitive.content == "POST" && it["path"]!!.jsonPrimitive.content == "/api/transcripts/{mediaId}/english" })
    }

    @Test fun `a paste lines up with the turns by the server's rule`() {
        fun turn(status: String) = example.turns[0].copy(translation = example.turns[0].translation.copy(status = status, text = null))
        val turns = listOf(turn(TranslationStatus.FAILED), turn(TranslationStatus.ALREADY_ENGLISH), turn(TranslationStatus.FAILED))
        assertEquals(TranscriptFormat.EnglishPlan(2, 2, true), TranscriptFormat.englishPlan(turns, "One.\r\n\r\nTwo."))
        assertEquals(TranscriptFormat.EnglishPlan(3, 2, true), TranscriptFormat.englishPlan(turns, "One.\n\nTwo.\n\nThree."))
        assertEquals(TranscriptFormat.EnglishPlan(1, 2, false), TranscriptFormat.englishPlan(turns, "Just one."))
        assertFalse(TranscriptFormat.englishPlan(turns, "  \n\n ").aligned)
        assertEquals(listOf("One.", "Two\nstill two."), TranscriptFormat.paragraphs("One.\n\n\nTwo\nstill two.\n"))
    }

    @Test fun `the notice for other readers names no provider`() {
        assertTrue(TranscriptStrings.englishDeclined.contains("Gemini"))
        assertFalse(TranscriptStrings.englishUnavailable.contains("Gemini"))
        assertFalse(TranscriptStrings.englishUnavailable.lowercase().contains("declined"))
    }
}
