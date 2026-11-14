package com.fieldrepository.app.transcript

import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.json
import com.fieldrepository.app.transcript.data.AudioIsolationEnvelope
import com.fieldrepository.app.transcript.data.EnglishNotice
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranscriptionSwitchBody
import com.fieldrepository.app.transcript.data.TranslationStatus
import com.fieldrepository.app.transcript.data.switches
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscribeOnlyTest {
    @Test fun `the switches carry the transcribe only choice both ways`() {
        val on = json.decodeFromString(AudioIsolationEnvelope.serializer(), """{"transcribeOnly":true}""")
        assertEquals(true, on.switches().transcribeOnly)
        val off = json.decodeFromString(AudioIsolationEnvelope.serializer(), """{"transcribeOnly":false}""")
        assertEquals(false, off.switches().transcribeOnly)
        assertNull("an older server says nothing, so no choice is drawn", AudioIsolationEnvelope().switches().transcribeOnly)
        val body = json.parseToJsonElement(
            json.encodeToString(TranscriptionSwitchBody.serializer(), TranscriptionSwitchBody(transcribeOnly = true))
        ).jsonObject
        assertEquals("true", body["transcribeOnly"].toString())
        assertFalse("autoTranscribeVideo" in body)
    }

    @Test fun `a transcribe only transcript reads ready with the neutral notice and untranslated turns`() {
        val view = json.decodeFromString(
            Transcript.serializer(),
            """{"mediaId":"m","status":"READY","englishNotice":"NOT_AUTOMATIC","permissions":{"edit":true,"addEnglish":true},""" +
                """"turns":[{"id":"t1","index":0,"speakerName":"Speaker 1","startMs":0,"endMs":1000,"text":"नमस्ते","translation":{"text":null,"status":"NOT_REQUESTED"}}]}""",
        )
        assertEquals("READY", view.status)
        assertEquals(EnglishNotice.NOT_AUTOMATIC, view.englishNotice)
        assertEquals(TranslationStatus.NOT_REQUESTED, view.turns.single().translation.status)
        assertTrue(view.permissions.addEnglish)
    }

    @Test fun `the copy is neutral and names the two choices`() {
        assertEquals("Transcribe and translate", TranscriptStrings.settingsModeTranslate)
        assertEquals("Transcribe only", TranscriptStrings.settingsModeTranscribeOnly)
        assertFalse("gemini" in TranscriptStrings.englishNotAutomatic.lowercase())
        assertFalse("declined" in TranscriptStrings.englishNotAutomatic.lowercase())
        assertEquals("Not translated", TranscriptStrings.translationNotRequested)
    }
}
