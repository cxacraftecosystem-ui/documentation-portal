package com.fieldrepository.app.explorer.data

import android.content.Context
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
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
    val thumbnails: Boolean = false,
    val locale: String = "en",
    val extras: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class ExplorerReportSheetInfo(
    val key: String = "",
    val name: String = "",
    val plugin: String? = null,
    val count: Int? = null,
    val files: Int? = null,
    val group: String? = null,
    val description: String? = null,
    val record: Boolean = false,
)

@Serializable
data class ExplorerReportExtra(val key: String = "", val title: String = "")

@Serializable
data class ExplorerReportSheets(
    val path: String = "",
    val sheets: List<ExplorerReportSheetInfo> = emptyList(),
    val extras: List<ExplorerReportExtra> = emptyList(),
    val locales: List<String> = listOf("en"),
    val records: Int? = null,
)

@Serializable
data class ExplorerShareBody(val path: String, val options: ExplorerReportOptions? = null, val filters: Map<String, String>? = null, val expiresInDays: Int = 7)

@Serializable
data class ExplorerShare(
    val id: String = "",
    val url: String = "",
    val expiresAt: String? = null,
    val state: String = "active",
    val createdAt: String? = null,
    val opens: Int? = null,
)

@Serializable
data class ExplorerShares(val items: List<ExplorerShare> = emptyList())

@Serializable
data class ExplorerGoMedia(val url: String? = null, val fileName: String? = null, val mediaId: String = "", val mediaType: String? = null)

@Serializable
data class ExplorerGoOpen(val kind: String = "", val id: String = "", val href: String? = null)

@Serializable
data class ExplorerGoTranscript(val mediaId: String = "", val format: String = "", val apiPath: String? = null, val openHref: String? = null)

@Serializable
data class ExplorerBundle(
    val id: String = "",
    val label: String = "",
    val path: String = "",
    val fileCount: Int = 0,
    val bytes: Long = 0,
    val listedCount: Int? = null,
    val hiddenCount: Int? = null,
    val mediaIds: List<String> = emptyList(),
    val manifestPath: String? = null,
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
data class ExplorerPeaks(val peaks: List<Float> = emptyList(), val durationMs: Long? = null, val isolated: Boolean = false)

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
    val offset: Int = 0,
    val nextOffset: Int? = null,
)

interface ExplorerMore {
    @GET("data/report/sheets")
    suspend fun reportSheets(@Query("path") path: String, @Query("status") status: String? = null, @Query("q") q: String? = null): ExplorerReportSheets

    @POST("data/report/shares")
    suspend fun createShare(@Body body: ExplorerShareBody): ExplorerShare

    @GET("data/report/shares")
    suspend fun shares(@Query("path") path: String): ExplorerShares

    @DELETE("data/report/shares/{id}")
    suspend fun revokeShare(@Path("id") id: String): retrofit2.Response<Unit>

    @GET("data/go/media/{id}/download")
    suspend fun goMedia(@Path("id") id: String, @Query("as") form: String = "json"): ExplorerGoMedia

    @GET("data/go/open/{kind}/{id}")
    suspend fun goOpen(@Path("kind") kind: String, @Path("id") id: String): ExplorerGoOpen

    @GET("data/go/transcript/{id}/{format}")
    suspend fun goTranscript(@Path("id") id: String, @Path("format") format: String): ExplorerGoTranscript

    @GET("data/bundles/{id}")
    suspend fun bundle(@Path("id") id: String): ExplorerBundle

    @GET("data/transcripts/list")
    suspend fun transcripts(@Query("path") path: String, @Query("q") q: String? = null, @Query("status") status: String? = null, @Query("offset") offset: Int? = null): ExplorerTranscriptList

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

    @GET("data/views/{id}")
    suspend fun view(@Path("id") id: String): ExplorerView

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

    fun reportUrl(path: String, options: ExplorerReportOptions, query: String?, name: String?, status: String? = null, pack: Boolean = false): String {
        var url = if (pack) ExplorerPaths.withQuery("/api/data/report/pack", "path", path) else ExplorerPaths.report(path)
        status?.let { url = ExplorerPaths.withQuery(url, "status", it) }
        options.sheets?.takeIf { it.isNotEmpty() }?.let { url = ExplorerPaths.withQuery(url, "sheets", it.joinToString(",")) }
        url = ExplorerPaths.withQuery(url, "answers", options.answers)
        url = ExplorerPaths.withQuery(url, "rows", options.rows)
        url = ExplorerPaths.withQuery(url, "links", if (options.links) "1" else "0")
        url = ExplorerPaths.withQuery(url, "thumbnails", if (options.thumbnails) "1" else "0")
        url = ExplorerPaths.withQuery(url, "locale", options.locale)
        options.extras.filterValues { it }.keys.sorted().takeIf { it.isNotEmpty() }?.let { url = ExplorerPaths.withQuery(url, "extras", it.joinToString(",")) }
        if (options.rows == "filtered" && !query.isNullOrBlank()) url = ExplorerPaths.withQuery(url, "q", query)
        name?.trim()?.removeSuffix(".xlsx")?.removeSuffix(".zip")?.takeIf { it.isNotBlank() }?.let { url = ExplorerPaths.withQuery(url, "name", it) }
        return url
    }
}
