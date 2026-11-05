package com.fieldrepository.app.explorer.data

import android.content.Context
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class ExplorerReportOptions(
    val sheets: List<String>? = null,
    val answers: String = "both",
    val rows: String = "all",
    val links: Boolean = true,
)

@Serializable
data class ExplorerReportOptionsBody(val options: ExplorerReportOptions? = null)

@Serializable
data class ExplorerVisitBody(val path: String)

@Serializable
data class ExplorerVisit(
    val path: String = "",
    val visitedAt: String? = null,
    val previousVisitAt: String? = null,
    val newFiles: Int = 0,
    val newTranscripts: Int = 0,
)

@Serializable
data class ExplorerFilled(val filled: Int = 0, val total: Int = 0)

@Serializable
data class ExplorerTranscriptTotals(val total: Int = 0, val ready: Int = 0, val translating: Int = 0, val failed: Int = 0)

@Serializable
data class ExplorerReviewTotals(val approved: Int = 0, val pending: Int = 0, val total: Int = 0)

@Serializable
data class ExplorerOverviewTotals(
    val files: Int = 0,
    val bytes: Long = 0,
    val fields: ExplorerFilled = ExplorerFilled(),
    val transcripts: ExplorerTranscriptTotals = ExplorerTranscriptTotals(),
    val review: ExplorerReviewTotals = ExplorerReviewTotals(),
)

@Serializable
data class ExplorerSectionHead(val key: String = "", val label: String = "")

@Serializable
data class ExplorerSectionState(val key: String = "", val state: String = "missing", val missing: List<String> = emptyList())

@Serializable
data class ExplorerFileTotals(val files: Int = 0, val bytes: Long = 0)

@Serializable
data class ExplorerOverviewArtisan(
    val id: String = "",
    val name: String = "",
    val craft: String? = null,
    val path: String = "",
    val fields: ExplorerFilled = ExplorerFilled(),
    val media: ExplorerFileTotals = ExplorerFileTotals(),
    val transcripts: Int = 0,
    val review: String? = null,
    val sections: List<ExplorerSectionState> = emptyList(),
) {
    val completeness: Float get() = if (fields.total > 0) fields.filled.toFloat() / fields.total else 0f
}

@Serializable
data class ExplorerOverview(
    val path: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val totals: ExplorerOverviewTotals = ExplorerOverviewTotals(),
    val sections: List<ExplorerSectionHead> = emptyList(),
    val artisans: List<ExplorerOverviewArtisan> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class ExplorerPeaks(val peaks: List<Float> = emptyList(), val durationMs: Long? = null)

@Serializable
data class ExplorerViewState(
    val view: String = "files",
    val sheet: String? = null,
    val q: String? = null,
    val filters: Map<String, String> = emptyMap(),
)

@Serializable
data class ExplorerView(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val state: ExplorerViewState = ExplorerViewState(),
    val shared: Boolean = false,
    val mine: Boolean = true,
    val ownerName: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class ExplorerViews(val mine: List<ExplorerView> = emptyList(), val shared: List<ExplorerView> = emptyList())

@Serializable
data class ExplorerViewBody(val name: String, val path: String, val state: ExplorerViewState, val shared: Boolean)

@Serializable
data class ExplorerTranscriptItem(
    val mediaId: String = "",
    val name: String = "",
    val path: String = "",
    val mediaType: String = "AUDIO",
    val hasText: Boolean = false,
    val capturedAt: String? = null,
    val snippet: String? = null,
)

@Serializable
data class ExplorerTranscriptList(
    val items: List<ExplorerTranscriptItem> = emptyList(),
    val total: Int = 0,
    val truncated: Boolean = false,
    val query: String? = null,
)

interface ExplorerMore {
    @GET("data/transcripts/list")
    suspend fun transcripts(@Query("path") path: String, @Query("q") q: String? = null, @Query("status") status: String? = null): ExplorerTranscriptList

    @GET("data/report-options")
    suspend fun reportOptions(): ExplorerReportOptionsBody

    @PUT("data/report-options")
    suspend fun saveReportOptions(@Body body: ExplorerReportOptionsBody): ExplorerReportOptionsBody

    @POST("data/visits")
    suspend fun visit(@Body body: ExplorerVisitBody): ExplorerVisit

    @GET("data/overview")
    suspend fun overview(@Query("path") path: String, @Query("status") status: String? = null): ExplorerOverview

    @GET("data/media/{id}/peaks")
    suspend fun peaks(@Path("id") id: String): ExplorerPeaks

    @GET("data/views")
    suspend fun views(): ExplorerViews

    @POST("data/views")
    suspend fun saveView(@Body body: ExplorerViewBody): ExplorerView
}

object ExplorerMoreApi {
    @Volatile var override: ExplorerMore? = null

    @Volatile private var made: ExplorerMore? = null

    fun of(context: Context): ExplorerMore = override ?: made ?: synchronized(this) {
        made ?: Retrofit.Builder()
            .baseUrl(ExplorerPortal.baseUrl())
            .client(ExplorerPortal.client(context.applicationContext))
            .addConverterFactory(ExplorerJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ExplorerMore::class.java)
            .also { made = it }
    }

    fun forget() {
        made = null
    }

    fun reportUrl(path: String, options: ExplorerReportOptions, query: String?, name: String?, status: String? = null): String {
        var url = ExplorerPaths.report(path)
        status?.let { url = ExplorerPaths.withQuery(url, "status", it) }
        options.sheets?.takeIf { it.isNotEmpty() }?.let { url = ExplorerPaths.withQuery(url, "sheets", it.joinToString(",")) }
        url = ExplorerPaths.withQuery(url, "answers", options.answers)
        url = ExplorerPaths.withQuery(url, "rows", options.rows)
        url = ExplorerPaths.withQuery(url, "links", if (options.links) "1" else "0")
        if (options.rows == "filtered" && !query.isNullOrBlank()) url = ExplorerPaths.withQuery(url, "q", query)
        name?.trim()?.removeSuffix(".xlsx")?.takeIf { it.isNotBlank() }?.let { url = ExplorerPaths.withQuery(url, "name", it) }
        return url
    }
}
