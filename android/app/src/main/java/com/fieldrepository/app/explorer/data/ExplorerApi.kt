package com.fieldrepository.app.explorer.data

import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

interface ExplorerApi {
    @GET("data/tree")
    suspend fun tree(
        @Query("path") path: String = "",
        @Query("status") status: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("slim") slim: Int = 1,
    ): ExplorerTree

    @GET("data/locate")
    suspend fun locate(@Query("type") type: String, @Query("id") id: String): JsonElement

    @GET("data/media/{id}/transcript")
    suspend fun transcript(@Path("id") id: String): ExplorerTranscript

    @GET("data/stats")
    suspend fun stats(@Query("path") path: String, @Query("include") include: String? = null): ExplorerStats

    @POST("data/stats")
    suspend fun selectionStats(@Body body: ExplorerStatsBody): ExplorerStats

    @GET("data/transcripts/languages")
    suspend fun languages(): ExplorerLanguages

    @Streaming
    @GET("data/manifest")
    suspend fun manifestStream(
        @Query("path") path: String = "",
        @Query("include") include: String? = null,
        @Query("stream") stream: Int = 1,
    ): Response<ResponseBody>

    @Streaming
    @POST("data/manifest")
    suspend fun selectionManifestStream(@Body body: ExplorerSelectionBody): Response<ResponseBody>

    @Streaming
    @GET("data/media/{id}/download")
    suspend fun media(
        @Path("id") id: String,
        @Query("format") format: String? = null,
        @Query("fallback") fallback: String? = null,
        @Header("Range") range: String? = null,
    ): Response<ResponseBody>

    @Streaming
    @GET
    suspend fun path(@Url path: String, @Header("Range") range: String? = null): Response<ResponseBody>
}

object ExplorerPaths {
    fun api(path: String): String = when {
        absolute(path) -> path
        path.startsWith("/api/") -> path
        path.startsWith("/") -> "/api$path"
        else -> "/api/$path"
    }

    fun absolute(path: String?): Boolean = path != null && (path.startsWith("http://") || path.startsWith("https://"))

    fun resolve(base: String, path: String): String {
        if (absolute(path)) return path
        val root: HttpUrl = base.toHttpUrlOrNull() ?: return path
        return root.resolve(api(path))?.toString() ?: path
    }

    fun withQuery(path: String, key: String, value: String): String {
        val joiner = if ('?' in path) '&' else '?'
        return "$path$joiner$key=${java.net.URLEncoder.encode(value, "UTF-8")}"
    }

    fun mediaDownload(mediaId: String): String = "/api/data/media/$mediaId/download"

    fun preview(entry: ExplorerEntry): String? =
        entry.previewPath ?: entry.mediaId?.takeIf { entry.mediaType in PREVIEWABLE }?.let { "/api/data/media/$it/preview" }

    fun report(path: String, format: String = "xlsx"): String =
        withQuery(withQuery("/api/data/report", "format", format), "path", path)

    fun transcriptZip(mediaId: String): String = "/api/transcripts/$mediaId/download?format=zip&track=both"

    fun transcriptBundle(path: String): String = withQuery("/api/data/transcript-bundle?track=both", "path", path)

    val PREVIEWABLE = setOf("IMAGE", "VIDEO", "AUDIO", "PDF")
}
