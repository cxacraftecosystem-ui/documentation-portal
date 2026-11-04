package com.fieldrepository.app.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

fun captureTimeMillis(dateTakenMs: Long?, lastModifiedMs: Long?, dateModifiedSec: Long?): Long? =
    dateTakenMs?.takeIf { it > 0 }
        ?: lastModifiedMs?.takeIf { it > 0 }
        ?: dateModifiedSec?.takeIf { it > 0 }?.let { it * 1000 }

fun captureOrder(times: List<Long?>): List<Int> =
    times.withIndex()
        .sortedWith(compareBy<IndexedValue<Long?>>({ it.value ?: Long.MAX_VALUE }, { it.index }))
        .map { it.index }

private fun Context.longColumn(uri: Uri, column: String): Long? = runCatching {
    contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
        val at = cursor.getColumnIndex(column)
        if (at >= 0 && cursor.moveToFirst() && !cursor.isNull(at)) cursor.getLong(at) else null
    }
}.getOrNull()

fun orderByCaptureTime(context: Context, uris: List<Uri>): List<Uri> {
    val times = uris.map { uri ->
        captureTimeMillis(context.longColumn(uri, MediaStore.MediaColumns.DATE_TAKEN), null, null)
            ?: captureTimeMillis(null, context.longColumn(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null)
            ?: captureTimeMillis(null, null, context.longColumn(uri, MediaStore.MediaColumns.DATE_MODIFIED))
    }
    return captureOrder(times).map { uris[it] }
}
