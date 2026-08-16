package com.fieldrepository.app.data

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import java.io.Reader

/**
 * Reading a download manifest one entry at a time, instead of one array at a time.
 *
 * THE DEFECT THIS EXISTS TO CLOSE. `GET /export/dataset` and `GET /data/manifest` answered with a
 * single JSON object holding every entry of the download, and the entries carry inline text — a
 * details.txt body per record, and the FULL transcript of every audio row in the subtree. The
 * server caps the entry COUNT (`MEDIA_TAKE = 20000` plus 6x`EXPORT_TAKE = 5000` in export.py;
 * `MAX_MANIFEST_FILES = 20000` in data_browser.py) and nothing caps the byte size, so
 * `docs/SCALABILITY.md:373-377` measures 476 kB today and models ~48 MB at 100x the media.
 *
 * `datasetManifest()` and `dataManifest()` were declared without `@Streaming` and with a
 * fully-materialised `@Serializable` return type, which routes them through Retrofit's
 * kotlinx-serialization converter. That converter is `Serializer.FromString`, and its
 * `fromResponseBody` is literally `format.decodeFromString(loader, body.string())` — verified
 * against the bytecode of retrofit2-kotlinx-serialization-converter 1.0.0, not from memory.
 * `ResponseBody.string()` bottoms out in okio's `Buffer.readByteArray(byteCount)`: ONE contiguous
 * `ByteArray` sized to the whole body, immediately copied into one contiguous `String`. So a 48 MB
 * manifest asks Android's allocator for a single 48 MB array while the heap is also holding
 * Compose, and the app dies with
 * `java.lang.OutOfMemoryError: Failed to allocate a 48000000 byte allocation`.
 *
 * `android:largeHeap="true"` is already set (AndroidManifest.xml:22), so that mitigation is spent —
 * and it would not have helped anyway: a large enough CONTIGUOUS allocation fails on a fragmented
 * heap however much total free memory is reported. The only real answer is to stop asking for the
 * whole thing at once, which is what this file does. Peak cost becomes one line: one manifest
 * entry, which for the worst case (an entry carrying a long transcript) is measured in hundreds of
 * kilobytes rather than tens of megabytes.
 *
 * Nothing here touches Android or OkHttp, deliberately: the line-splitting and the tolerance rules
 * below are the part worth asserting, and they are asserted on the JVM in `ManifestStreamTest`.
 */
internal object ManifestStream {

    /** What the server answers `?stream=1` with. Anything else is NOT this format — see [isNdjson]. */
    const val NDJSON_MEDIA_TYPE = "application/x-ndjson"

    /**
     * The counts and the truncation flag, sent as headers because NDJSON has nowhere else to put
     * them: a client showing "142 of 4,312" needs the total before the first entry arrives, and a
     * trailing summary line would be lost exactly when it matters most — on a dropped connection.
     * These three names must match `data_browser.MANIFEST_*_HEADER` on the server.
     */
    const val TOTAL_HEADER = "X-Dataset-Total"
    const val MEDIA_HEADER = "X-Dataset-Media"
    const val TRUNCATED_HEADER = "X-Dataset-Truncated"

    /**
     * Is this response the streamed format?
     *
     * NOT cosmetic, and not a nicety for old servers alone. [decodeLines] reads by line, and
     * `BufferedReader.readLine()` has no length limit — handed a response that is one enormous
     * JSON object with no newline in it, it would allocate that entire object as a single `String`
     * and reproduce the exact `OutOfMemoryError` this file exists to prevent. This check is the
     * thing that guarantees the body is newline-delimited before a line is read. A server that
     * predates `?stream=1` ignores the unknown query parameter and answers `application/json`, and
     * that is precisely the case that must not reach the line reader.
     *
     * Matched on the media type alone: the header may legitimately carry `; charset=utf-8`.
     */
    fun isNdjson(contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.lowercase() == NDJSON_MEDIA_TYPE

    /** Header value to entry count; -1 when the server did not say, which callers must tolerate. */
    fun count(header: String?): Int = header?.trim()?.toIntOrNull() ?: -1

    /** Header value to flag. Absent reads as false — an old server that cannot say is not claiming. */
    fun flag(header: String?): Boolean = header?.trim().equals("true", ignoreCase = true)
}

/**
 * The entries of one NDJSON manifest, decoded lazily from [reader].
 *
 * A SEQUENCE RATHER THAN A CALLBACK, AND THAT IS LOAD-BEARING. The caller's per-entry work is
 * suspending — a folder download asks the API to transcode each audio row before it goes into the
 * zip — and a suspend body cannot be passed to a plain `(T) -> Unit` parameter. Handing back a
 * sequence keeps the loop in the caller's coroutine, where it may suspend, while the decoding stays
 * here where it can be tested without one.
 *
 * ONE BAD LINE IS NOT A FAILED DOWNLOAD. The whole reason for NDJSON over a JSON array is that a
 * partial stream is still usable, and throwing on the first unparseable entry would give that
 * property away — turning one corrupt row into "the researcher's whole repository would not
 * download". Undecodable lines are skipped and counted in [unreadable] instead, so the caller can
 * add them to the files it could not fetch and report an honest total rather than quietly shipping
 * a short archive.
 *
 * Blank lines are skipped WITHOUT being counted: the server's chunked writer terminates every chunk
 * with `\n`, so a trailing empty line is normal output, not damage.
 *
 * Single pass. [reader] is consumed but NOT closed — the caller owns it, and on the download path
 * it is a spool file the caller also has to delete.
 */
internal class ManifestLines<T>(
    private val reader: Reader,
    private val json: Json,
    private val deserializer: DeserializationStrategy<T>
) {
    /** How many lines would not decode. Only final once [entries] has been fully consumed. */
    var unreadable: Int = 0
        private set

    fun entries(): Sequence<T> = reader.buffered().lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val entry = runCatching { json.decodeFromString(deserializer, line) }.getOrNull()
        if (entry == null) unreadable++
        entry
    }
}

/**
 * What a manifest turned out to hold, however it was read.
 *
 * [total] is the server's own count of entries — from the `X-Dataset-Total` header on the streamed
 * path, from `files.size` on the buffered one — so progress reads the same either way. [unreadable]
 * is the streamed path's tally of lines that would not decode; it is always 0 on the buffered path,
 * where a bad entry fails the whole response before this type exists.
 */
internal data class ManifestOutcome(
    val total: Int,
    val truncated: Boolean,
    val unreadable: Int
)
