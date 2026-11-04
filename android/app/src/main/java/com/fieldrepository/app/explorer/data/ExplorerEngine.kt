package com.fieldrepository.app.explorer.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ExplorerTick(
    val doneBytes: Long,
    val totalBytes: Long,
    val doneFiles: Int,
    val totalFiles: Int,
    val current: String?,
    val busySeconds: Int? = null,
)

class ExplorerEngine(
    private val api: ExplorerApi,
    private val json: Json,
    private val workRoot: File,
    private val saver: (name: String, mimeType: String) -> ExplorerOutput,
    private val freeBytes: () -> Long? = { null },
    private val storage: OkHttpClient = defaultStorage(),
    private val backoffMs: Long = 2_000,
    private val parallel: Int = PARALLEL,
    private val busyTickMs: Long = 1_000,
) {
    private class Spool(val file: File, val prefix: String?, val ndjson: Boolean)

    private class Gathered(val spools: List<Spool>, val truncated: Boolean, val announced: Int)

    private class Pending(val item: ExplorerItem, val text: Boolean, val temp: File?, val result: CompletableDeferred<Result<String>>)

    suspend fun run(id: String, request: ExplorerRequest, tick: (ExplorerTick) -> Unit): ExplorerResult = withContext(Dispatchers.IO) {
        val work = File(workRoot, id).apply { deleteRecursively(); mkdirs() }
        var output: ExplorerOutput? = null
        try {
            val gathered = ExplorerBusy.retryOnce({ left -> tick(ExplorerTick(0, 0, 0, 0, null, left)) }, busyTickMs) { gather(request, work) }
            var totalFiles = 0
            var totalBytes = 0L
            var largest = 0L
            items(request, gathered).forEach { item ->
                totalFiles++
                val size = sizeOf(item)
                totalBytes += size
                if (size > largest) largest = size
            }
            if (totalFiles == 0) {
                return@withContext ExplorerResult(total = 0, truncated = gathered.truncated, message = ExplorerText.nothingToSave)
            }
            val free = freeBytes()
            val needed = ExplorerBytes.spaceNeeded(totalBytes, largest, if (request.single) 1 else parallel)
            if (free != null && totalBytes > 0 && free < needed) {
                throw ExplorerSpaceException(ExplorerText.noSpace(ExplorerBytes.format(needed), ExplorerBytes.format(free)))
            }
            tick(ExplorerTick(0, totalBytes, 0, totalFiles, null))
            if (request.single) {
                val item = items(request, gathered).first()
                val temp = File(work, "single.part")
                val named = if (item.content != null) {
                    temp.writeText(item.content, Charsets.UTF_8)
                    item.path
                } else {
                    ExplorerBusy.retryOnce({ left -> tick(ExplorerTick(0, totalBytes, 0, 1, item.path, left)) }, busyTickMs) {
                        fetch(item, temp) { bytes -> tick(ExplorerTick(bytes, maxOf(totalBytes, bytes), 0, 1, item.path)) }
                    }
                }
                val leaf = leafOf(named.ifBlank { request.fileName })
                val mime = if (request.mimeType == "application/octet-stream") mimeFor(leaf) else request.mimeType
                val out = saver(leaf, mime)
                output = out
                temp.inputStream().use { it.copyTo(out.stream, 256 * 1024) }
                val (location, uri) = out.finish()
                output = null
                val length = temp.length()
                tick(ExplorerTick(length, maxOf(totalBytes, length), 1, 1, null))
                return@withContext ExplorerResult(location, uri, mime, saved = 1, total = 1)
            }
            val out = saver(request.fileName, request.mimeType)
            output = out
            val failed = ArrayList<String>()
            var failedCount = 0
            var saved = 0
            var doneFiles = 0
            val completed = AtomicLong(0)
            val inflight = ConcurrentHashMap<String, Long>()
            fun report(current: String?) {
                val bytes = completed.get() + inflight.values.sum()
                tick(ExplorerTick(bytes, maxOf(totalBytes, bytes), doneFiles, totalFiles, current))
            }
            ExplorerZipWriter(out.stream).use { zip ->
                coroutineScope {
                    val slots = Semaphore(parallel)
                    val outstanding = MutableStateFlow(0)
                    val queue = Channel<Pending>(capacity = parallel * 2)
                    launch {
                        var index = 0
                        for (item in items(request, gathered)) {
                            val big = sizeOf(item) >= BIG_BYTES
                            if (big) outstanding.first { it == 0 }
                            if (item.content != null) {
                                outstanding.value = outstanding.value + 1
                                queue.send(Pending(item, true, null, CompletableDeferred(Result.success(item.path))))
                                continue
                            }
                            slots.acquire()
                            outstanding.value = outstanding.value + 1
                            val temp = File(work, "item-${index++}.part")
                            val result = CompletableDeferred<Result<String>>()
                            val key = temp.name
                            launch {
                                val got = try {
                                    Result.success(fetch(item, temp) { bytes -> inflight[key] = bytes; report(item.path) })
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Throwable) {
                                    Result.failure(failure)
                                }
                                result.complete(got)
                            }
                            queue.send(Pending(item, false, temp, result))
                            if (big) outstanding.first { it == 0 }
                        }
                        queue.close()
                    }
                    for (pending in queue) {
                        val got = pending.result.await()
                        try {
                            val name = got.getOrNull()
                            if (name == null) {
                                failedCount++
                                if (failed.size < ExplorerJobStore.FAILED_NAMES) failed += pending.item.path
                            } else if (pending.text) {
                                zip.text(pending.item.path, pending.item.content.orEmpty())
                                saved++
                            } else {
                                val temp = pending.temp!!
                                zip.file(name, temp, ExplorerZipWriter.compressible(ExplorerKind.ofMediaType(pending.item.mediaType)))
                                saved++
                            }
                        } finally {
                            pending.temp?.let { temp ->
                                inflight.remove(temp.name)
                                temp.delete()
                                slots.release()
                            }
                            outstanding.value = outstanding.value - 1
                        }
                        doneFiles++
                        completed.addAndGet(sizeOf(pending.item))
                        report(null)
                    }
                }
            }
            val (location, uri) = out.finish()
            output = null
            ExplorerResult(
                location = location,
                uri = uri,
                mimeType = request.mimeType,
                saved = saved,
                total = maxOf(totalFiles, gathered.announced),
                failed = failed,
                failedCount = failedCount,
                truncated = gathered.truncated,
            )
        } finally {
            output?.abort()
            work.deleteRecursively()
        }
    }

    private suspend fun gather(request: ExplorerRequest, work: File): Gathered {
        val spools = ArrayList<Spool>()
        var truncated = false
        var announced = 0
        val calls = ArrayList<Pair<String?, suspend () -> Response<ResponseBody>>>()
        request.sources.forEach { source -> calls += source.prefix to { api.manifestStream(source.path, source.include) } }
        request.selection?.let { body -> calls += null to { api.selectionManifestStream(body.copy(stream = true)) } }
        calls.forEachIndexed { index, (prefix, call) ->
            val response = call()
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                runCatching { body?.close() }
                val code = response.code()
                val detail = ExplorerText.errorDetail(response.errorBody()?.string(), json)
                if (ExplorerBusy.isBusy(code)) {
                    val seconds = ExplorerBusy.retryAfter(response.headers()["Retry-After"])
                    throw ExplorerBusyException(seconds, ExplorerText.busy(seconds))
                }
                throw IllegalStateException(detail ?: ExplorerText.manifestFailed)
            }
            val ndjson = response.headers()["Content-Type"]?.substringBefore(';')?.trim()?.lowercase() == NDJSON
            truncated = truncated || response.headers()[TRUNCATED_HEADER]?.trim().equals("true", ignoreCase = true)
            announced += response.headers()[TOTAL_HEADER]?.trim()?.toIntOrNull() ?: 0
            val file = File(work, "manifest-$index.json")
            body.use { b -> b.byteStream().use { input -> FileOutputStream(file).use { input.copyTo(it, 64 * 1024) } } }
            if (!ndjson) {
                val parsed = runCatching { json.decodeFromString(ExplorerManifest.serializer(), file.readText()) }.getOrNull()
                truncated = truncated || parsed?.truncated == true
            }
            spools += Spool(file, prefix, ndjson)
        }
        return Gathered(spools, truncated, announced)
    }

    private fun items(request: ExplorerRequest, gathered: Gathered): Sequence<ExplorerItem> = sequence {
        for (spool in gathered.spools) {
            if (spool.ndjson) {
                spool.file.bufferedReader(Charsets.UTF_8).use { reader ->
                    for (line in reader.lineSequence()) {
                        if (line.isBlank()) continue
                        val f = runCatching { json.decodeFromString(ExplorerManifestFile.serializer(), line) }.getOrNull() ?: continue
                        if (f.path.isBlank()) continue
                        yield(itemOf(f, spool.prefix))
                    }
                }
            } else {
                val parsed = runCatching { json.decodeFromString(ExplorerManifest.serializer(), spool.file.readText()) }.getOrNull()
                parsed?.files?.forEach { yield(itemOf(it, spool.prefix)) }
            }
        }
        yieldAll(request.items)
    }

    private fun itemOf(f: ExplorerManifestFile, prefix: String?) = ExplorerItem(
        path = ExplorerZipNames.prefixed(prefix, f.path),
        url = f.url,
        content = f.content,
        mediaId = f.mediaId,
        mediaType = f.mediaType,
        convertToMp4 = f.convertToMp4,
        originalPath = f.originalPath?.let { ExplorerZipNames.prefixed(prefix, it) },
        originalFilename = f.originalFilename,
        sizeBytes = f.sizeBytes,
    )

    suspend fun fetch(item: ExplorerItem, temp: File, onBytes: (Long) -> Unit): String {
        val mediaId = item.mediaId
        if (item.convertToMp4 && mediaId != null) {
            val converted = runCatching {
                ExplorerTransfer.fetchTo(temp, resumable = false, attempts = 1, backoffMs = backoffMs, open = { openApi(api.media(mediaId, MP4, ORIGINAL)) }, onBytes = onBytes)
            }
            converted.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            val got = converted.getOrNull()
            if (got != null && got.bytes > 0) {
                return when {
                    got.conversion.equals(CONVERSION_ORIGINAL, ignoreCase = true) -> originalName(item, got.head)
                    got.conversion.equals(CONVERSION_CONVERTED, ignoreCase = true) -> ExplorerZipNames.withExtension(item.path, ".mp4")
                    ExplorerSniff.isMp4(got.head) -> ExplorerZipNames.withExtension(item.path, ".mp4")
                    else -> originalName(item, got.head)
                }
            }
            temp.delete()
            onBytes(0)
            val original = fetchOriginal(item, temp, onBytes, ORIGINAL)
            return originalName(item, original.head)
        }
        val got = fetchOriginal(item, temp, onBytes)
        return if (item.path.isBlank()) got.filename.orEmpty() else item.path
    }

    private suspend fun fetchOriginal(item: ExplorerItem, temp: File, onBytes: (Long) -> Unit, format: String? = null): Fetched {
        val url = item.url
        val mediaId = item.mediaId
        if (url.isNullOrBlank()) {
            mediaId ?: throw IllegalStateException(ExplorerText.fileFailed)
            return ExplorerTransfer.fetchTo(temp, resumable = true, backoffMs = backoffMs, open = { offset -> openApi(api.media(mediaId, format, null, ExplorerTransfer.range(offset))) }, onBytes = onBytes)
        }
        if (!ExplorerPaths.absolute(url)) {
            return ExplorerTransfer.fetchTo(temp, resumable = true, backoffMs = backoffMs, open = { offset -> openApi(api.path(ExplorerPaths.api(url), ExplorerTransfer.range(offset))) }, onBytes = onBytes)
        }
        return try {
            ExplorerTransfer.fetchTo(temp, resumable = true, backoffMs = backoffMs, open = { offset -> openStorage(url, offset) }, onBytes = onBytes)
        } catch (failure: TransferFailed) {
            if (!ExplorerTransfer.expired(failure.code) || mediaId == null) throw failure
            ExplorerTransfer.fetchTo(temp, resumable = true, backoffMs = backoffMs, open = { offset -> openApi(api.media(mediaId, format, null, ExplorerTransfer.range(offset))) }, onBytes = onBytes)
        }
    }

    private fun openStorage(url: String, offset: Long): Opened {
        val builder = Request.Builder().url(url)
        ExplorerTransfer.range(offset)?.let { builder.header("Range", it) }
        val response = storage.newCall(builder.build()).execute()
        return Opened(
            response.code,
            response.code == 206,
            response.body.byteStream(),
            filename = ExplorerTransfer.dispositionName(response.header("Content-Disposition")),
        ) { response.close() }
    }

    private fun openApi(response: Response<ResponseBody>): Opened {
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            runCatching { response.errorBody()?.close() }
            return Opened(response.code(), false, null, retryAfter = response.headers()["Retry-After"])
        }
        return Opened(
            response.code(),
            response.code() == 206,
            body.byteStream(),
            conversion = response.headers()[CONVERSION_HEADER] ?: response.raw().priorResponse?.header(CONVERSION_HEADER),
            filename = ExplorerTransfer.dispositionName(response.headers()["Content-Disposition"]),
        ) { body.close() }
    }

    companion object {
        const val PARALLEL = 4
        const val BIG_BYTES = 64L * 1024L * 1024L
        const val MP4 = "mp4"
        const val ORIGINAL = "original"
        const val NDJSON = "application/x-ndjson"
        const val TOTAL_HEADER = "X-Dataset-Total"
        const val TRUNCATED_HEADER = "X-Dataset-Truncated"
        const val CONVERSION_HEADER = "X-Conversion"
        const val CONVERSION_ORIGINAL = "original"
        const val CONVERSION_CONVERTED = "converted"

        fun sizeOf(item: ExplorerItem): Long = item.sizeBytes ?: item.content?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L

        fun originalName(item: ExplorerItem, head: ByteArray = ByteArray(0)): String {
            item.originalPath?.takeIf { it.isNotBlank() }?.let { return it }
            val ext = ExplorerZipNames.extensionOf(item.originalFilename)
                ?: ExplorerZipNames.extensionOf(item.url?.substringBefore('?'))
                ?: ExplorerSniff.extension(head)?.let { if (it == ".mp4") ".m4a" else it }
                ?: ".m4a"
            return ExplorerZipNames.withExtension(item.path, ext)
        }

        fun leafOf(path: String): String = path.substringAfterLast('/').ifBlank { "file" }

        fun mimeFor(name: String): String = when (ExplorerZipNames.extensionOf(name)) {
            ".mp4" -> "video/mp4"
            ".m4a" -> "audio/mp4"
            ".mp3" -> "audio/mpeg"
            ".aac" -> "audio/aac"
            ".amr" -> "audio/amr"
            ".flac" -> "audio/flac"
            ".webm" -> "video/webm"
            ".wav" -> "audio/wav"
            ".ogg" -> "audio/ogg"
            ".jpg", ".jpeg" -> "image/jpeg"
            ".png" -> "image/png"
            ".webp" -> "image/webp"
            ".heic" -> "image/heic"
            ".pdf" -> "application/pdf"
            ".zip" -> "application/zip"
            ".txt", ".md" -> "text/plain"
            ".csv" -> "text/csv"
            ".json" -> "application/json"
            ".vtt" -> "text/vtt"
            ".srt" -> "application/x-subrip"
            ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "application/octet-stream"
        }

        fun defaultStorage(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}

class ExplorerSpaceException(message: String) : IllegalStateException(message)
