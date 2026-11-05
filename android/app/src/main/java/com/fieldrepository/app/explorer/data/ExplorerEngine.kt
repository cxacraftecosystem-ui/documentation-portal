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
    val currentBytes: Long = 0,
    val currentTotal: Long = 0,
    val finished: ExplorerRecent? = null,
    val retrying: Int = 0,
    val resumedFrom: Int = 0,
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
    private val reopen: (handle: String, at: Long) -> ExplorerOutput? = { _, _ -> null },
) {
    private class Spool(val file: File, val prefix: String?, val ndjson: Boolean)

    private class Gathered(val spools: List<Spool>, val truncated: Boolean, val announced: Int)

    private class Pending(val item: ExplorerItem, val text: Boolean, val temp: File?, val result: CompletableDeferred<Result<String>>)

    suspend fun run(id: String, request: ExplorerRequest, resume: Boolean = false, tick: (ExplorerTick) -> Unit): ExplorerResult = withContext(Dispatchers.IO) {
        val keepPart = request.single && !request.rebuilt
        val work = File(workRoot, id).apply {
            if (!resume || request.rebuilt) deleteRecursively()
            mkdirs()
        }
        var output: ExplorerOutput? = null
        var keepWork = false
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
                var contentType: String? = null
                val named = if (item.content != null) {
                    temp.writeText(item.content, Charsets.UTF_8)
                    item.path
                } else {
                    try {
                        ExplorerBusy.retry(if (request.rebuilt) ExplorerBusy.REBUILT_ATTEMPTS else 2, { left -> tick(ExplorerTick(0, totalBytes, 0, 1, item.path, left)) }, busyTickMs) {
                            val got = fetchNamed(item, temp, resumable = !request.rebuilt, keep = keepPart && resume) { bytes ->
                                tick(ExplorerTick(bytes, maxOf(totalBytes, bytes), 0, 1, item.path, currentBytes = bytes, currentTotal = maxOf(totalBytes, bytes)))
                            }
                            contentType = got.second
                            got.first
                        }
                    } catch (cancelled: CancellationException) {
                        keepWork = keepPart
                        throw cancelled
                    } catch (lost: java.io.IOException) {
                        keepWork = keepPart
                        throw lost
                    }
                }
                val leaf = leafOf(named.ifBlank { request.fileName })
                val mime = if (request.mimeType == "application/octet-stream") mimeFor(leaf) else request.mimeType
                if (item.content == null && !ExplorerSniff.valid(mime, ExplorerTransfer.head(temp), contentType)) {
                    throw ExplorerInvalidException(if (request.kind == ExplorerJobKind.REPORT) ExplorerText.reportFailed else ExplorerText.fileFailed)
                }
                val out = saver(leaf, mime)
                output = out
                temp.inputStream().use { it.copyTo(out.stream, 256 * 1024) }
                val (location, uri) = out.finish()
                output = null
                val length = temp.length()
                tick(ExplorerTick(length, maxOf(totalBytes, length), 1, 1, null))
                return@withContext ExplorerResult(location, uri, mime, saved = 1, total = 1, bytes = length)
            }
            val checkpoint = ExplorerCheckpoint(work, json)
            var restored = if (resume) checkpoint.read() else null
            restored?.let { saved ->
                val current = HashMap<String, String?>()
                items(request, gathered).forEach { current[it.path] = it.sha256 }
                val cut = saved.records.indexOfFirst { r -> !current.containsKey(r.source) || current[r.source] != r.tag }
                if (cut >= 0) restored = checkpoint.trim(saved, cut)
            }
            var out: ExplorerOutput? = restored?.let { reopen(it.handle, it.committed) }
            if (out == null) {
                restored = null
                checkpoint.clear()
                out = saver(request.fileName, request.mimeType)
                out.handle?.let { checkpoint.start(it) }
            }
            output = out
            val done = restored?.records.orEmpty().associateBy { it.source }
            fun alreadyZipped(item: ExplorerItem): Boolean = done[item.path]?.let { it.tag == item.sha256 } == true
            val failed = ArrayList<String>()
            var failedCount = 0
            var saved = 0
            var doneFiles = 0
            val completed = AtomicLong(0)
            var skipped = 0
            if (done.isNotEmpty()) {
                items(request, gathered).forEach { item ->
                    if (alreadyZipped(item)) {
                        skipped++
                        completed.addAndGet(sizeOf(item))
                    }
                }
                saved = skipped
                doneFiles = skipped
            }
            val inflight = ConcurrentHashMap<String, Long>()
            val retry = ArrayList<ExplorerItem>()
            fun report(current: String?, currentBytes: Long = 0, currentTotal: Long = 0, finished: ExplorerRecent? = null) {
                val bytes = completed.get() + inflight.values.sum()
                tick(
                    ExplorerTick(
                        bytes, maxOf(totalBytes, bytes), doneFiles, totalFiles, current,
                        currentBytes = currentBytes, currentTotal = currentTotal, finished = finished, retrying = retry.size,
                        resumedFrom = skipped,
                    ),
                )
            }
            report(null)
            val resumable = out.handle != null
            val zip = ExplorerZipWriter(
                out.stream,
                start = restored?.committed ?: 0L,
                restored = restored?.records.orEmpty(),
                scratch = work,
                onEntry = { record, end -> if (resumable) checkpoint.append(record, end) },
            )
            try {
                coroutineScope {
                    val slots = Semaphore(parallel)
                    val outstanding = MutableStateFlow(0)
                    val queue = Channel<Pending>(capacity = parallel * 2)
                    launch {
                        var index = 0
                        for (item in items(request, gathered)) {
                            if (alreadyZipped(item)) continue
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
                                    Result.success(fetch(item, temp) { bytes -> inflight[key] = bytes; report(item.path, bytes, sizeOf(item)) })
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
                        val finished: ExplorerRecent
                        try {
                            val name = got.getOrNull()
                            finished = if (name == null) {
                                retry += pending.item
                                ExplorerRecent(ExplorerProgress.leaf(pending.item.path), pending.item.sizeBytes, ExplorerRecentStatus.RETRYING)
                            } else if (pending.text) {
                                zip.text(pending.item.path, pending.item.content.orEmpty(), tag = pending.item.sha256)
                                saved++
                                ExplorerRecent(ExplorerProgress.leaf(pending.item.path), sizeOf(pending.item), ExplorerRecentStatus.SAVED, 1f)
                            } else {
                                val temp = pending.temp!!
                                zip.file(name, temp, ExplorerZipWriter.compressible(ExplorerKind.ofMediaType(pending.item.mediaType)), origin = pending.item.path, tag = pending.item.sha256)
                                saved++
                                ExplorerRecent(ExplorerProgress.leaf(name), sizeOf(pending.item), ExplorerRecentStatus.SAVED, 1f)
                            }
                        } finally {
                            pending.temp?.let { temp ->
                                inflight.remove(temp.name)
                                temp.delete()
                                slots.release()
                            }
                            outstanding.value = outstanding.value - 1
                        }
                        if (finished.status == ExplorerRecentStatus.SAVED) {
                            doneFiles++
                            completed.addAndGet(sizeOf(pending.item))
                        }
                        report(null, finished = finished)
                    }
                }
                for ((index, item) in ArrayList(retry).withIndex()) {
                    val temp = File(work, "retry-$index.part")
                    val key = temp.name
                    val name = try {
                        fetch(item, temp) { bytes -> inflight[key] = bytes; report(item.path, bytes, sizeOf(item)) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        null
                    }
                    inflight.remove(key)
                    retry.remove(item)
                    val finished = if (name == null) {
                        failedCount++
                        if (failed.size < ExplorerJobStore.FAILED_NAMES) failed += item.path
                        ExplorerRecent(ExplorerProgress.leaf(item.path), item.sizeBytes, ExplorerRecentStatus.FAILED)
                    } else {
                        zip.file(name, temp, ExplorerZipWriter.compressible(ExplorerKind.ofMediaType(item.mediaType)), origin = item.path, tag = item.sha256)
                        saved++
                        ExplorerRecent(ExplorerProgress.leaf(name), sizeOf(item), ExplorerRecentStatus.SAVED, 1f)
                    }
                    temp.delete()
                    doneFiles++
                    completed.addAndGet(sizeOf(item))
                    report(null, finished = finished)
                }
                zip.close()
            } catch (stopped: Throwable) {
                if (resumable && stopped !is ExplorerSpaceException) {
                    zip.detach()
                    runCatching { out.stream.close() }
                    output = null
                    keepWork = true
                } else {
                    runCatching { zip.detach() }
                }
                throw stopped
            }
            val (location, uri) = out.finish()
            output = null
            checkpoint.clear()
            ExplorerResult(
                location = location,
                uri = uri,
                mimeType = request.mimeType,
                saved = saved,
                total = maxOf(totalFiles, gathered.announced),
                failed = failed,
                failedCount = failedCount,
                truncated = gathered.truncated,
                bytes = completed.get(),
            )
        } catch (failure: Throwable) {
            if (!request.single && failure !is ExplorerSpaceException && output == null && ExplorerCheckpoint(work, json).handle() != null) keepWork = true
            throw failure
        } finally {
            output?.abort()
            if (!keepWork) work.deleteRecursively()
        }
    }

    private suspend fun gather(request: ExplorerRequest, work: File): Gathered {
        val spools = ArrayList<Spool>()
        var truncated = false
        var announced = 0
        val calls = ArrayList<Pair<String?, suspend () -> Response<ResponseBody>>>()
        request.sources.forEach { source ->
            val url = source.manifestUrl?.takeIf { it.isNotBlank() }
            calls += source.prefix to { if (url != null) api.path(ExplorerPaths.api(url)) else api.manifestStream(source.path, source.include) }
        }
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
        sha256 = f.sha256,
    )

    suspend fun fetch(item: ExplorerItem, temp: File, onBytes: (Long) -> Unit): String = fetchNamed(item, temp, onBytes = onBytes).first

    private suspend fun fetchNamed(item: ExplorerItem, temp: File, resumable: Boolean = true, keep: Boolean = false, onBytes: (Long) -> Unit): Pair<String, String?> {
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
                } to got.contentType
            }
            temp.delete()
            onBytes(0)
            val original = fetchOriginal(item, temp, onBytes, ORIGINAL)
            return originalName(item, original.head) to original.contentType
        }
        val got = fetchOriginal(item, temp, onBytes, resumable = resumable, keep = keep)
        return (if (item.path.isBlank()) got.filename.orEmpty() else item.path) to got.contentType
    }

    private suspend fun fetchOriginal(
        item: ExplorerItem,
        temp: File,
        onBytes: (Long) -> Unit,
        format: String? = null,
        resumable: Boolean = true,
        keep: Boolean = false,
    ): Fetched {
        val url = item.url
        val mediaId = item.mediaId
        if (url.isNullOrBlank()) {
            mediaId ?: throw IllegalStateException(ExplorerText.fileFailed)
            return ExplorerTransfer.fetchTo(temp, resumable = resumable, keep = keep, backoffMs = backoffMs, open = { offset -> openApi(api.media(mediaId, format, null, ExplorerTransfer.range(offset))) }, onBytes = onBytes)
        }
        if (!ExplorerPaths.absolute(url)) {
            return ExplorerTransfer.fetchTo(temp, resumable = resumable, keep = keep, backoffMs = backoffMs, open = { offset -> openApi(api.path(ExplorerPaths.api(url), ExplorerTransfer.range(offset))) }, onBytes = onBytes)
        }
        return try {
            ExplorerTransfer.fetchTo(temp, resumable = resumable, keep = keep, backoffMs = backoffMs, open = { offset -> openStorage(url, offset) }, onBytes = onBytes)
        } catch (failure: TransferFailed) {
            if (!ExplorerTransfer.expired(failure.code) || mediaId == null) throw failure
            ExplorerTransfer.fetchTo(temp, resumable = resumable, backoffMs = backoffMs, open = { offset -> openApi(api.media(mediaId, format, null, ExplorerTransfer.range(offset))) }, onBytes = onBytes)
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
            contentType = response.header("Content-Type"),
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
            contentType = response.headers()["Content-Type"] ?: body.contentType()?.toString(),
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

class ExplorerCheckpoint(private val dir: File, private val json: Json = ExplorerJson) {
    @kotlinx.serialization.Serializable
    private data class Line(val record: ExplorerZipRecord, val end: Long)

    data class Saved(val handle: String, val committed: Long, val records: List<ExplorerZipRecord>)

    private val handleFile get() = File(dir, HANDLE)
    private val entriesFile get() = File(dir, ENTRIES)

    fun start(handle: String) {
        dir.mkdirs()
        entriesFile.delete()
        handleFile.writeText(handle, Charsets.UTF_8)
    }

    fun append(record: ExplorerZipRecord, end: Long) {
        entriesFile.appendText(json.encodeToString(Line.serializer(), Line(record, end)) + "\n", Charsets.UTF_8)
    }

    fun handle(): String? = runCatching { handleFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

    fun read(): Saved? {
        val handle = handle() ?: return null
        val records = ArrayList<ExplorerZipRecord>()
        var committed = 0L
        if (entriesFile.isFile) {
            entriesFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    if (raw.isBlank()) continue
                    val line = runCatching { json.decodeFromString(Line.serializer(), raw) }.getOrNull() ?: break
                    records += line.record
                    committed = line.end
                }
            }
        }
        return Saved(handle, committed, records)
    }

    fun trim(saved: Saved, keep: Int): Saved {
        val kept = saved.records.take(keep)
        val committed = saved.records.getOrNull(keep)?.offset ?: saved.committed
        entriesFile.delete()
        kept.forEachIndexed { i, r -> append(r, kept.getOrNull(i + 1)?.offset ?: committed) }
        return Saved(saved.handle, committed, kept)
    }

    fun clear() {
        handleFile.delete()
        entriesFile.delete()
    }

    companion object {
        const val HANDLE = "zip.handle"
        const val ENTRIES = "zip.entries"
    }
}

class ExplorerInvalidException(message: String) : IllegalStateException(message)
