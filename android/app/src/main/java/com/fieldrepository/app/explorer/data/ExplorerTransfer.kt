package com.fieldrepository.app.explorer.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

class Opened(
    val code: Int,
    val partial: Boolean,
    val stream: InputStream?,
    val conversion: String? = null,
    val filename: String? = null,
    val retryAfter: String? = null,
    val closer: () -> Unit = {},
)

class TransferFailed(val code: Int, message: String) : IOException(message)

data class Fetched(val bytes: Long, val conversion: String?, val filename: String?, val head: ByteArray)

object ExplorerTransfer {
    const val ATTEMPTS = 3
    const val HEAD_BYTES = 16
    private const val CHUNK = 64 * 1024

    suspend fun fetchTo(
        target: File,
        resumable: Boolean,
        attempts: Int = ATTEMPTS,
        backoffMs: Long = 2_000,
        open: suspend (offset: Long) -> Opened,
        onBytes: (Long) -> Unit = {},
    ): Fetched {
        var lastError: Throwable? = null
        if (target.exists()) target.delete()
        for (attempt in 1..attempts) {
            currentCoroutineContext().ensureActive()
            val offset = if (resumable && target.isFile) target.length() else 0L
            if (!resumable && target.exists()) target.delete()
            try {
                val opened = open(offset)
                try {
                    if (ExplorerBusy.isBusy(opened.code) && opened.retryAfter != null) {
                        throw ExplorerBusyException(ExplorerBusy.retryAfter(opened.retryAfter), ExplorerText.busy(ExplorerBusy.retryAfter(opened.retryAfter)))
                    }
                    if (opened.code !in 200..299 || opened.stream == null) throw TransferFailed(opened.code, "HTTP ${opened.code}")
                    val append = offset > 0 && opened.partial
                    if (!append && target.exists()) target.delete()
                    var written = if (append) offset else 0L
                    onBytes(written)
                    FileOutputStream(target, append).use { out ->
                        val buffer = ByteArray(CHUNK)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = opened.stream.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            onBytes(written)
                        }
                    }
                    return Fetched(written, opened.conversion, opened.filename, head(target))
                } finally {
                    runCatching { opened.closer() }
                }
            } catch (failure: TransferFailed) {
                lastError = failure
                if (!retryable(failure.code)) break
            } catch (failure: IOException) {
                lastError = failure
            }
            if (attempt < attempts) delay(backoffMs * attempt)
        }
        throw lastError ?: IOException(ExplorerText.fileFailed)
    }

    fun head(file: File): ByteArray = runCatching {
        file.inputStream().use { input ->
            val buffer = ByteArray(HEAD_BYTES)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

    fun retryable(code: Int): Boolean = code == 0 || code == 408 || code == 429 || code >= 500

    fun expired(code: Int): Boolean = code == 401 || code == 403 || code == 410

    fun range(offset: Long): String? = if (offset > 0) "bytes=$offset-" else null

    fun dispositionName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = Regex("filename\\*\\s*=\\s*(?:UTF-8|utf-8)''([^;]+)").find(header)?.groupValues?.get(1)
        val decoded = star?.let { runCatching { java.net.URLDecoder.decode(it.trim(), "UTF-8") }.getOrNull() }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?").find(header)?.groupValues?.get(1)
        return (decoded ?: plain)?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
    }
}

object ExplorerSniff {
    fun extension(head: ByteArray): String? {
        fun at(i: Int, s: String) = head.size >= i + s.length && s.indices.all { head[i + it] == s[it].code.toByte() }
        return when {
            at(4, "ftyp") -> ".mp4"
            at(0, "RIFF") && at(8, "WAVE") -> ".wav"
            at(0, "OggS") -> ".ogg"
            head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> ".webm"
            at(0, "ID3") -> ".mp3"
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xF6) == 0xF0 -> ".aac"
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> ".mp3"
            at(0, "#!AMR") -> ".amr"
            at(0, "fLaC") -> ".flac"
            else -> null
        }
    }

    fun isMp4(head: ByteArray): Boolean = extension(head) == ".mp4"
}
