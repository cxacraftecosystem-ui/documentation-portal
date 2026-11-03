package com.fieldrepository.app.data

import com.fieldrepository.app.questionnaire.QFeature
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.POST

class TranscriptRefineWireTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    @QFeature("R1")
    fun theRequestCarriesOnlyTheTranslateChoice() {
        assertEquals("""{"translate":true}""", json.encodeToString(TranscriptRefineRequest(translate = true)))
        assertEquals("""{"text":"**Interviewer:** Hello"}""", json.encodeToString(TranscriptUpdateRequest(text = "**Interviewer:** Hello")))
    }

    @Test
    @QFeature("R1")
    fun aRefinedAnswerAndAnUnavailableOneBothDecode() {
        val done = json.decodeFromString<TranscriptRefineResponse>(
            """{"available":true,"status":"COMPLETED","refined":"**Interviewer:** Hello","model":"gemini-3.8-flash","translated":true}"""
        )
        assertEquals("**Interviewer:** Hello", done.refined)
        assertTrue(done.translated)
        val off = json.decodeFromString<TranscriptRefineResponse>(
            """{"available":false,"status":"UNAVAILABLE","refined":null,"message":"Refinement isn't available right now."}"""
        )
        assertFalse(off.available)
        assertNull(off.refined)
        assertEquals("Refinement isn't available right now.", off.message)
    }

    @Test
    @QFeature("R1")
    fun theRoutesAreTheServersRefineAndSave() {
        val paths = FieldRepositoryApi::class.java.methods.mapNotNull { it.getAnnotation(POST::class.java)?.value }
        assertTrue(paths.contains("media/{id}/refine-transcript"))
        assertTrue(paths.contains("media/{id}/transcript"))
    }
}
