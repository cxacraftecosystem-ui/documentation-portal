package com.fieldrepository.app.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** GET /media/{id}/insights: decoding, nulls, unknown states, and a 404 hiding the card. */
class TranscriptInsightsDtoTest {

    private val full = """
        {"mediaId":"m1","state":"READY","stale":false,"canEdit":true,"canRegenerate":true,"error":null,
         "insight":{"id":"i1","version":2,"sourceLanguage":"hi",
           "summaryLines":["One.","Two."],
           "quotes":[{"original":"मिट्टी रात भर भिगोई जाती है।","english":"The mud is soaked overnight.","speaker":"Artisan"}],
           "translation":{"status":"COMPLETED","text":"We do dabu printing.","note":null},
           "provenance":{"provider":"openai","modelId":"gpt-4o-mini","modelVersion":null,
                         "promptVersion":"transcript-insights/1","generatedAt":"2026-10-30T10:00:00Z"},
           "edited":null,"futureField":1}}
    """.trimIndent()

    private fun decode(text: String) = ApiClient.json.decodeFromString(TranscriptInsightViewDto.serializer(), text)

    @Test
    fun `a full answer decodes`() {
        val view = decode(full)
        val insight = view.insight!!
        assertEquals(2, insight.version)
        assertEquals(listOf("One.", "Two."), insight.summaryLines)
        assertEquals("Artisan", insight.quotes.single().speaker)
        assertTrue(TranscriptInsightStates.isShown(view))
        assertTrue(TranscriptInsightStates.hasTranslation(insight))
        assertEquals(
            "Machine-generated · gpt-4o-mini · 30 Oct",
            TranscriptInsightStates.provenanceLine(insight) { "30 Oct" }
        )
    }

    @Test
    fun `nulls and missing fields fall back to defaults`() {
        val view = decode("""{"mediaId":"m2","state":"QUEUED","insight":null,"error":null}""")
        assertNull(view.insight)
        assertTrue(TranscriptInsightStates.isWorking(view))
        assertTrue(TranscriptInsightStates.isShown(view))
        val sparse = decode("""{"state":"READY","insight":{"summaryLines":["x"],"edited":{"at":"2026-10-30T11:00:00Z"}}}""")
        val sparseInsight = sparse.insight!!
        assertEquals("NOT_NEEDED", sparseInsight.translation.status)
        assertFalse(TranscriptInsightStates.hasTranslation(sparseInsight))
        assertEquals(
            "Edited by a researcher · 30 Oct",
            TranscriptInsightStates.provenanceLine(sparseInsight) { "30 Oct" }
        )
    }

    @Test
    fun `an unknown state, no transcript and not generated draw nothing`() {
        assertFalse(TranscriptInsightStates.isShown(decode("""{"state":"SOMETHING_NEW"}""")))
        assertFalse(TranscriptInsightStates.isShown(decode("""{"state":"NO_TRANSCRIPT"}""")))
        assertFalse(TranscriptInsightStates.isShown(decode("""{"state":"NOT_GENERATED"}""")))
        assertFalse(TranscriptInsightStates.isShown(decode("""{"state":"FAILED","error":null}""")))
        assertTrue(TranscriptInsightStates.isShown(decode("""{"state":"FAILED","error":"Try again."}""")))
        assertFalse(TranscriptInsightStates.isShown(null))
    }

    @Test
    fun `a 404 and being offline hide the card`() = runBlocking {
        val notFound = HttpException(
            Response.error<Any>(404, """{"detail":"Not found"}""".toResponseBody("application/json".toMediaType()))
        )
        assertNull(insightOrHidden<TranscriptInsightViewDto> { throw notFound })
        assertNull(insightOrHidden<TranscriptInsightViewDto> { throw IOException("offline") })
        assertEquals("m1", insightOrHidden { decode(full) }?.mediaId)
    }
}
