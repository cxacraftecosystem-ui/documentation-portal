package com.fieldrepository.app.ui.trace

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

private const val TRACE_EXPORT_CACHE_DIR = "trace-export"

private const val TRACE_ATTACH_DIR = "trace-attach"

internal suspend fun traceAttachFile(
    context: Context,
    svg: String,
    sourceName: String,
): Uri? = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val name = traceExportFileName(
        sourceName = sourceName,
        extension = "svg",
        suffix = TRACE_ATTACH_SUFFIX,
    )
    val dir = File(File(appContext.filesDir, TRACE_ATTACH_DIR), System.currentTimeMillis().toString())
    runCatching {
        dir.mkdirs()
        val target = File(dir, name)
        FileOutputStream(target).use { fos ->
            val buffered = BufferedOutputStream(fos)
            buffered.write(svg.toByteArray(Charsets.UTF_8))
            buffered.flush()
            fos.fd.sync()
        }
        FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", target)
    }.getOrNull()
}

const val TRACE_ATTACH_WRITE_FAILED: String =
    "Couldn't save the line art. Free some space and try again."

data class TraceExportSaved(
    val savedTo: String,
    val shareUri: Uri?,
    val storedName: String,
    val mime: String,
    val sizeBytes: Long,
)

suspend fun traceSaveExport(
    context: Context,
    bytes: ByteArray,
    fileName: String,
    mime: String,
): TraceExportSaved = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val staging = File(appContext.cacheDir, TRACE_EXPORT_CACHE_DIR).apply { mkdirs() }
    val tmp = File(staging, fileName)
    try {
        FileOutputStream(tmp).use { fos ->
            val buffered = BufferedOutputStream(fos)
            buffered.write(bytes)
            buffered.flush()
            fos.fd.sync()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("This device would not create a Downloads entry.")
            resolver.openOutputStream(target)?.use { out ->
                tmp.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("This device would not open the Downloads file.")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(target, values, null, null)

            val stored = traceStoredName(appContext, target) ?: fileName
            TraceExportSaved(
                savedTo = "Downloads/$stored",
                shareUri = target,
                storedName = stored,
                mime = mime,
                sizeBytes = bytes.size.toLong(),
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            tmp.inputStream().use { input ->
                FileOutputStream(outFile).use { out -> input.copyTo(out) }
            }
            TraceExportSaved(
                savedTo = outFile.absolutePath,
                shareUri = runCatching {
                    FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        outFile,
                    )
                }.getOrNull(),
                storedName = fileName,
                mime = mime,
                sizeBytes = bytes.size.toLong(),
            )
        }
    } finally {
        runCatching { tmp.delete() }
    }
}

private fun traceStoredName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(MediaStore.Downloads.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        val index = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()?.takeIf { it.isNotBlank() }

fun traceExportShareIntent(saved: TraceExportSaved): Intent? {
    val uri = saved.shareUri ?: return null
    return Intent(Intent.ACTION_SEND).apply {
        type = saved.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, saved.storedName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

const val TRACE_EXPORT_NO_SHARE_SENTENCE: String =
    "Find it in Downloads to send it."

const val TRACE_EXPORT_SHARE_CAVEAT: String =
    "No signal? Use Nearby Share, Bluetooth or a cable — chat apps wait until you're online."
