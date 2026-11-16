package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class RecordingSortTest {
    private fun api(seen: MutableList<String?>, reply: String): QuestionnaireApi = Retrofit.Builder()
        .baseUrl("http://localhost/api/")
        .client(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen += request.url.encodedQuery
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build())
        .addConverterFactory(QuestionnairePortal.json.asConverterFactory("application/json".toMediaType()))
        .build().create(QuestionnaireApi::class.java)

    private fun rec(id: String, captured: String, created: String?) = QRecording(id = id, capturedAt = captured, createdAt = created)

    @QFeature("E5")
    @Test fun `the recordings list asks the server for its order and reads a page the server ignored it on`() = runBlocking {
        val seen = mutableListOf<String?>()
        val api = api(seen, """{"items":[{"id":"r1","capturedAt":"2026-11-13T05:00:00Z"},{"id":"r2","capturedAt":"2026-11-16T05:00:00Z"}],"nextCursor":"c2"}""")
        val filters = QuestionnaireFilters().withQ("loom")
        val added = api.recordings(filters.toParams(), cursor = null, limit = 30, sort = RecordingSort.ADDED)
        assertEquals("q=loom&limit=30&sort=added", seen.last())
        assertEquals(listOf("r1", "r2"), added.items.map { it.id })
        api.recordings(filters.toParams(), cursor = "c2", limit = 30, sort = RecordingSort.RECORDED)
        assertEquals("q=loom&cursor=c2&limit=30&sort=recorded", seen.last())
        api.recordings(emptyMap(), limit = 200, sessionId = "s1")
        assertEquals("limit=200&sessionId=s1", seen.last())
    }

    @QFeature("E5")
    @Test fun `Added is the default, newest upload first, and Recorded orders by when it was recorded`() {
        assertEquals(RecordingSort.ADDED, RecordingSort.of(null))
        assertEquals(RecordingSort.ADDED, RecordingSort.of("newest"))
        assertEquals(RecordingSort.RECORDED, RecordingSort.of("recorded"))
        val rows = listOf(
            rec("old-upload", "2026-11-15T05:00:00Z", "2026-11-15T06:00:00Z"),
            rec("new-upload-of-old-take", "2026-10-01T05:00:00Z", "2026-11-16T06:00:00Z"),
            rec("no-upload-time", "2026-11-14T05:00:00Z", null)
        )
        assertEquals(listOf("new-upload-of-old-take", "old-upload", "no-upload-time"), RecordingSort.sorted(rows, RecordingSort.ADDED).map { it.id })
        assertEquals(listOf("old-upload", "no-upload-time", "new-upload-of-old-take"), RecordingSort.sorted(rows, RecordingSort.RECORDED).map { it.id })
    }
}
