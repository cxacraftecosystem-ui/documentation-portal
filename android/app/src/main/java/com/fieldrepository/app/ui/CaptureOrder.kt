package com.fieldrepository.app.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

/*
 * C22: "STEPS FROM PHOTOS" IN THE ORDER THE PHOTOGRAPHS WERE TAKEN.
 *
 * The picker (`GetMultipleContents`) hands back DocumentsProvider URIs as often as media-store
 * ones (media.documents, Drive, Downloads), and those frequently expose no `DATE_TAKEN`. Asking for
 * that column alone left such a pick silently in tap order. The time is now looked for in three
 * places, in this order, the way the web falls back from EXIF to the file's modified time:
 *
 *   1. MediaStore `DATE_TAKEN` (milliseconds) — the capture time, where the store has it;
 *   2. DocumentsContract `COLUMN_LAST_MODIFIED` (milliseconds) — what a document provider offers;
 *   3. MediaStore `DATE_MODIFIED` (SECONDS, scaled here) — the store's own last resort.
 *
 * A file none of them can date keeps its tap place after every dated one.
 */

/** The first usable time of the three, in milliseconds; null when none of them is a real time. */
fun captureTimeMillis(dateTakenMs: Long?, lastModifiedMs: Long?, dateModifiedSec: Long?): Long? =
    dateTakenMs?.takeIf { it > 0 }
        ?: lastModifiedMs?.takeIf { it > 0 }
        ?: dateModifiedSec?.takeIf { it > 0 }?.let { it * 1000 }

/** Indices in capture order: dated first, oldest first; undated after, in the order they came. */
fun captureOrder(times: List<Long?>): List<Int> =
    times.withIndex()
        .sortedWith(compareBy<IndexedValue<Long?>>({ it.value ?: Long.MAX_VALUE }, { it.index }))
        .map { it.index }

/** One column of one URI, or null when the provider does not have it (each asked separately). */
private fun Context.longColumn(uri: Uri, column: String): Long? = runCatching {
    contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
        val at = cursor.getColumnIndex(column)
        if (at >= 0 && cursor.moveToFirst() && !cursor.isNull(at)) cursor.getLong(at) else null
    }
}.getOrNull()

fun orderByCaptureTime(context: Context, uris: List<Uri>): List<Uri> {
    // Asked lazily: the next source only when the one before had no usable time.
    val times = uris.map { uri ->
        captureTimeMillis(context.longColumn(uri, MediaStore.MediaColumns.DATE_TAKEN), null, null)
            ?: captureTimeMillis(null, context.longColumn(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null)
            ?: captureTimeMillis(null, null, context.longColumn(uri, MediaStore.MediaColumns.DATE_MODIFIED))
    }
    return captureOrder(times).map { uris[it] }
}
