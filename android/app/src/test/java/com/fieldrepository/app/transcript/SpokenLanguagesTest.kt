package com.fieldrepository.app.transcript

import com.fieldrepository.app.questionnaire.data.QCompleteRecording
import com.fieldrepository.app.questionnaire.data.QCompleteRequest
import com.fieldrepository.app.questionnaire.data.wire
import com.fieldrepository.app.recording.RecordingDraft
import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.example
import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.json
import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.repoRoot
import com.fieldrepository.app.transcript.data.RetranscribeBody
import com.fieldrepository.app.transcript.data.SpokenLanguageSuggested
import com.fieldrepository.app.transcript.data.SpokenLanguageSuggestion
import com.fieldrepository.app.transcript.data.SpokenLanguages
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptStrings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpokenLanguagesTest {
    private val drafts = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `the list is the web's list, code for code`() {
        val shared = json.parseToJsonElement(File(repoRoot(), "frontend/lib/transcript/spokenLanguages.json").readText()).jsonObject
        val expected = shared["languages"]!!.jsonArray.map { entry ->
            val o = entry.jsonObject
            Triple(o["code"]!!.jsonPrimitive.content, o["label"]!!.jsonPrimitive.content, o["featured"]!!.jsonPrimitive.boolean)
        }
        assertEquals(expected, SpokenLanguages.languages.map { Triple(it.code, it.label, it.featured) })
        assertEquals(SpokenLanguages.AUTO, shared["auto"]!!.jsonPrimitive.content)
        assertEquals(SpokenLanguages.MIXED, shared["mixed"]!!.jsonPrimitive.content)
        assertEquals(SpokenLanguages.UNCERTAIN_BELOW, shared["uncertainBelow"]!!.jsonPrimitive.double, 0.0)
    }

    @Test fun `automatic and mixed lead, then the featured languages`() {
        val codes = SpokenLanguages.options.map { it.code }
        assertEquals(listOf(SpokenLanguages.AUTO, SpokenLanguages.MIXED), codes.take(2))
        assertTrue(codes.indexOf("or") < codes.indexOf("af"))
        assertEquals(TranscriptStrings.spokenLanguageAuto, SpokenLanguages.label(null))
        assertEquals(TranscriptStrings.spokenLanguageMixed, SpokenLanguages.label("mixed"))
        assertEquals("Odia", SpokenLanguages.label("or-IN"))
        assertEquals(SpokenLanguages.AUTO, SpokenLanguages.normalise("klingon"))
    }

    @Test fun `search finds by name and says nothing when nothing matches`() {
        assertEquals("or", SpokenLanguages.filter("odi").first().code)
        assertEquals("bn", SpokenLanguages.filter("BENG").first().code)
        assertTrue(SpokenLanguages.filter("zzzz").isEmpty())
        assertEquals(SpokenLanguages.options, SpokenLanguages.filter("  "))
    }

    @Test fun `the artisan's last language wins, then the researcher's, then this phone's`() {
        val artisan = SpokenLanguages.suggest(SpokenLanguageSuggested("bn", "Bengali", SpokenLanguages.SOURCE_ARTISAN), "or")
        assertEquals("bn", artisan!!.code)
        assertEquals(TranscriptStrings.spokenLanguageFromArtisan, SpokenLanguages.hint(artisan.source))
        val researcher = SpokenLanguages.suggest(SpokenLanguageSuggested("hi", "Hindi", SpokenLanguages.SOURCE_RESEARCHER), null)
        assertEquals(TranscriptStrings.spokenLanguageFromResearcher, SpokenLanguages.hint(researcher!!.source))
        val device = SpokenLanguages.suggest(null, "or")
        assertEquals("or", device!!.code)
        assertEquals(TranscriptStrings.spokenLanguageFromResearcher, SpokenLanguages.hint(device.source))
        assertEquals("or", SpokenLanguages.suggest(SpokenLanguageSuggested("auto"), "or")!!.code)
        assertNull(SpokenLanguages.suggest(null, null))
        assertNull(SpokenLanguages.suggest(null, "auto"))
    }

    @Test fun `audio and video carry the choice in their metadata, a photo does not`() {
        val audio = SpokenLanguages.metadata(null, "or", "audio/mp4")!!
        assertEquals("or", audio["spokenLanguage"]!!.jsonPrimitive.content)
        val kept = SpokenLanguages.metadata(JsonObject(mapOf("purpose" to kotlinx.serialization.json.JsonPrimitive("dictation"))), "mixed", "VIDEO")!!
        assertEquals("mixed", kept["spokenLanguage"]!!.jsonPrimitive.content)
        assertEquals("dictation", kept["purpose"]!!.jsonPrimitive.content)
        assertNull(SpokenLanguages.metadata(null, "or", "image/jpeg"))
        assertNull(SpokenLanguages.metadata(null, null, "audio/mp4"))
    }

    @Test fun `the view reads what was asked, what was heard and how sure`() {
        val spoken = example.spokenLanguage!!
        assertTrue(spoken.uncertain)
        assertEquals("hi", spoken.detected)
        assertEquals(0.62, spoken.probability!!, 0.0)
        assertTrue(example.permissions.retranscribe)
        val older = json.decodeFromString(Transcript.serializer(), """{"mediaId":"m","status":"READY","permissions":{"edit":true}}""")
        assertNull(older.spokenLanguage)
        assertFalse(older.permissions.retranscribe)
        val suggestion = json.decodeFromString(SpokenLanguageSuggestion.serializer(), """{"suggested":{"code":"or","label":"Odia","source":"artisan"},"uncertainBelow":0.8}""")
        assertEquals("or", suggestion.suggested!!.code)
        assertEquals("""{"spokenLanguage":"or"}""", json.encodeToString(RetranscribeBody.serializer(), RetranscribeBody("or")))
    }

    @Test fun `a take keeps its language through the outbox, and an old take reads as none`() {
        val draft = RecordingDraft(
            clientClipKey = "k1", sessionKey = "c", questionnaireId = "q", adtsPath = "/x.aac",
            deviceCapturedAt = "2026-11-13T10:00:00Z", label = "D3", spokenLanguage = "or",
        )
        val back = drafts.decodeFromString(RecordingDraft.serializer(), drafts.encodeToString(RecordingDraft.serializer(), draft))
        assertEquals("or", back.spokenLanguage)
        val old = drafts.decodeFromString(
            RecordingDraft.serializer(),
            """{"clientClipKey":"k1","sessionKey":"c","questionnaireId":"q","adtsPath":"/x.aac","deviceCapturedAt":"x","label":"D3"}""",
        )
        assertNull(old.spokenLanguage)
        val request = QCompleteRequest(objectKey = "o", clientClipKey = "k1", linkedRecordId = "s1", recording = QCompleteRecording(deviceCapturedAt = "x", spokenLanguage = "mixed"))
        assertEquals("mixed", request.wire().recording.spokenLanguage)
    }
}
