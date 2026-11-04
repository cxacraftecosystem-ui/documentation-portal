package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.data.ExplorerLanguages
import com.fieldrepository.app.explorer.data.ExplorerSelectionBody
import com.fieldrepository.app.explorer.data.ExplorerStats
import com.fieldrepository.app.explorer.data.ExplorerStatsBody
import com.fieldrepository.app.explorer.data.ExplorerTranscript
import com.fieldrepository.app.explorer.data.ExplorerTree
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

open class FakeExplorerApi : ExplorerApi {
    val calls = mutableListOf<String>()
    val transcriptCalls = AtomicInteger(0)
    var trees: suspend (path: String, status: String?, cursor: String?) -> ExplorerTree = { path, _, _ -> ExplorerTree(path = path) }
    var manifest: suspend (path: String) -> Response<ResponseBody> = { ndjson(emptyList()) }
    var selection: suspend (ExplorerSelectionBody) -> Response<ResponseBody> = { ndjson(emptyList()) }
    var mediaResponse: suspend (id: String, format: String?, fallback: String?, range: String?) -> Response<ResponseBody> = { _, _, _, _ -> notFound() }
    var paths: suspend (path: String, range: String?) -> Response<ResponseBody> = { _, _ -> notFound() }
    var stats: ExplorerStats = ExplorerStats()
    var transcripts: suspend (String) -> ExplorerTranscript = { ExplorerTranscript(mediaId = it, content = "# Transcript $it") }

    override suspend fun tree(path: String, status: String?, cursor: String?, slim: Int): ExplorerTree {
        calls += "tree:$path:${status.orEmpty()}:${cursor.orEmpty()}"
        return trees(path, status, cursor)
    }

    override suspend fun locate(type: String, id: String): JsonElement {
        calls += "locate:$type:$id"
        return JsonObject(mapOf("path" to JsonPrimitive("located/$id")))
    }

    override suspend fun transcript(id: String): ExplorerTranscript {
        transcriptCalls.incrementAndGet()
        calls += "transcript:$id"
        return transcripts(id)
    }

    override suspend fun stats(path: String, include: String?): ExplorerStats {
        calls += "stats:$path"
        return stats
    }

    override suspend fun selectionStats(body: ExplorerStatsBody): ExplorerStats {
        calls += "stats-selection:${body.paths.size}:${body.mediaIds.size}"
        return stats
    }

    override suspend fun languages(): ExplorerLanguages = ExplorerLanguages()

    override suspend fun manifestStream(path: String, include: String?, stream: Int): Response<ResponseBody> {
        calls += "manifest:$path:${include.orEmpty()}"
        return manifest(path)
    }

    override suspend fun selectionManifestStream(body: ExplorerSelectionBody): Response<ResponseBody> {
        calls += "manifest-selection:${body.paths.joinToString(",")}:${body.mediaIds.joinToString(",")}:${body.stream}"
        return selection(body)
    }

    override suspend fun media(id: String, format: String?, fallback: String?, range: String?): Response<ResponseBody> {
        calls += "media:$id:${format.orEmpty()}:${fallback.orEmpty()}:${range.orEmpty()}"
        return mediaResponse(id, format, fallback, range)
    }

    override suspend fun path(path: String, range: String?): Response<ResponseBody> {
        calls += "path:$path:${range.orEmpty()}"
        return paths(path, range)
    }

    companion object {
        fun ndjson(lines: List<String>, headers: Map<String, String> = emptyMap()): Response<ResponseBody> {
            val body = lines.joinToString("\n").toResponseBody("application/x-ndjson".toMediaType())
            return Response.success(body, Headers.Builder().apply {
                add("Content-Type", "application/x-ndjson")
                headers.forEach { (k, v) -> add(k, v) }
            }.build())
        }

        fun bytes(data: ByteArray, code: Int = 200, headers: Map<String, String> = emptyMap()): Response<ResponseBody> {
            val raw = okhttp3.Response.Builder()
                .code(code)
                .message("OK")
                .protocol(Protocol.HTTP_1_1)
                .request(okhttp3.Request.Builder().url("http://localhost/").build())
                .headers(Headers.Builder().apply { headers.forEach { (k, v) -> add(k, v) } }.build())
                .build()
            return Response.success(data.toResponseBody("application/octet-stream".toMediaType()), raw)
        }

        fun error(code: Int, headers: Map<String, String> = emptyMap()): Response<ResponseBody> {
            val raw = okhttp3.Response.Builder()
                .code(code)
                .message("Error")
                .protocol(Protocol.HTTP_1_1)
                .request(okhttp3.Request.Builder().url("http://localhost/").build())
                .headers(Headers.Builder().apply { headers.forEach { (k, v) -> add(k, v) } }.build())
                .build()
            return Response.error("{}".toResponseBody("application/json".toMediaType()), raw)
        }

        fun notFound(): Response<ResponseBody> = error(404)

        fun httpError(code: Int): HttpException = HttpException(error(code))
    }
}
