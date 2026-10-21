package com.fieldrepository.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * The real [SessionTransport]: [QuestionnaireApi] for the API, a plain OkHttp client for the signed
 * S3 PUTs (no auth header may reach S3), and the existing artisan create for offline-registered
 * artisans. Every HTTP refusal that a retry cannot change becomes [PermanentStepFailure]; everything
 * else (no signal, 5xx, 408, 429, the shims' 503) stays an [IOException] so the step waits.
 */
class QuestionnaireTransport(
    private val api: QuestionnaireApi,
    private val createArtisanCall: suspend (idempotencyKey: String, bodyJson: String) -> String,
    /**
     * Whether the server has v2 switched off right now. Every v2 route answers 404 then, and that is
     * a switch, not a refusal: the step waits for v2 to come back instead of failing for good (audit
     * H20). A real 404 on a v2 server still fails the step into the fix tray.
     */
    private val v2SwitchedOff: suspend () -> Boolean = { false }
) : SessionTransport {

    private val storage = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (http: HttpException) {
        val code = http.code()
        val detail = if (code in 400..499) runCatching {
            http.response()?.errorBody()?.string()?.let { ApiClient.json.decodeFromString(QErrorBody.serializer(), it).detail }
        }.getOrNull() else null
        // `/complete` found no bytes in storage: upload again, never drop the take.
        if (detail != null && detail.reason == UploadMissing.REASON) throw UploadMissing(detail.message.ifBlank { "The upload is missing from storage." })
        if (code == 404 && v2SwitchedOff()) throw IOException("Questionnaire v2 is switched off on the server", http)
        if (code in 400..499 && code != 408 && code != 429) {
            throw PermanentStepFailure(detail?.message?.ifBlank { null } ?: "The server refused this ($code).", detail?.code)
        }
        throw IOException("HTTP $code", http)
    }

    override suspend fun createArtisan(idempotencyKey: String, bodyJson: String): String = call { createArtisanCall(idempotencyKey, bodyJson) }
    override suspend fun createSession(idempotencyKey: String, body: QSessionCreate): QSession = call { api.createSession(idempotencyKey, body) }
    override suspend fun addParticipant(idempotencyKey: String, sessionId: String, artisanId: String) {
        call { api.addParticipant(idempotencyKey, sessionId, QArtisanRef(artisanId)) }
    }
    override suspend fun patchParticipant(sessionId: String, artisanId: String, patch: QParticipantPatch) {
        call { api.patchParticipant(sessionId, artisanId, patch) }
    }
    override suspend fun uploadState(clientClipKey: String): QUploadState? = call {
        val response = api.uploadState(clientClipKey)
        when {
            response.isSuccessful -> response.body()
            response.code() == 404 -> null
            else -> throw HttpException(response)
        }
    }
    override suspend fun presign(body: QPresignRequest): QPresignResponse = call { api.presign(body) }
    override suspend fun multipartCreate(body: QPresignRequest): QMultipartCreateResponse = call { api.multipartCreate(body) }
    override suspend fun presignParts(objectKey: String, uploadId: String, partNumbers: List<Int>): Map<Int, String> = call {
        api.multipartPresignParts(QMultipartPresignParts(objectKey, uploadId, partNumbers)).urls.mapKeys { it.key.toInt() }
    }
    override suspend fun multipartComplete(objectKey: String, uploadId: String, parts: List<QPartDone>) {
        call { api.multipartComplete(QMultipartComplete(objectKey, uploadId, parts)) }
    }
    override suspend fun complete(idempotencyKey: String, body: QCompleteRequest): QRecording = call { api.complete(idempotencyKey, body) }
    override suspend fun putAnswers(body: QAnswersPut): QAnswersPutResponse = call { api.putAnswers(body) }
    override suspend fun finish(sessionId: String) {
        call { api.finish(sessionId) }
    }

    override suspend fun put(url: String, headers: Map<String, String>, file: File, offset: Long, length: Long, contentType: String): String =
        withContext(Dispatchers.IO) {
            val body = FileRangeBody(file, offset, length, contentType)
            val request = Request.Builder().url(url).put(body).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            storage.newCall(request).execute().use { response ->
                // An expired (or otherwise refused) signature: the outbox signs again once (audit M23).
                if (response.code == 403) throw SignedUrlExpired("Storage refused the signed upload URL (403).")
                if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                    throw PermanentStepFailure("Storage refused the upload (${response.code}).")
                }
                if (!response.isSuccessful) throw IOException("Storage answered ${response.code}")
                response.header("ETag") ?: ""
            }
        }

    /** Streams [length] bytes from [offset]; never the whole file in memory. */
    private class FileRangeBody(val file: File, val offset: Long, val length: Long, val type: String) : RequestBody() {
        override fun contentType() = type.toMediaTypeOrNull()
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val stream = java.io.FileInputStream(raf.fd)
                stream.source().use { source ->
                    var left = length
                    val buffer = okio.Buffer()
                    while (left > 0) {
                        val read = source.read(buffer, minOf(left, 64L * 1024))
                        if (read == -1L) break
                        sink.write(buffer, read)
                        left -= read
                    }
                }
            }
        }
    }
}
