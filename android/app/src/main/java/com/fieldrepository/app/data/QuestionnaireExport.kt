package com.fieldrepository.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder

/**
 * The artisan record CSV (`GET /artisans/{id}/record.csv`, missing on Android until 21 Oct 2026).
 * The server names the file (NAMING: the app never invents an export name); this only reads the name
 * from `Content-Disposition` and saves the bytes into Downloads.
 */
object QuestionnaireExport {
    /**
     * The file name a `Content-Disposition` header carries: RFC 5987 `filename*=UTF-8''…` first, else
     * `filename="…"`. Path separators are dropped, so a name can never leave Downloads.
     */
    fun dispositionFileName(header: String?): String? {
        header ?: return null
        val star = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(header)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it.trim().trim('"'), "UTF-8") }.getOrNull() }
        val plain = Regex("""filename\s*=\s*"([^"]+)"""").find(header)?.groupValues?.get(1)
            ?: Regex("""filename\s*=\s*([^;\s]+)""").find(header)?.groupValues?.get(1)
        return (star ?: plain)?.substringAfterLast('/')?.substringAfterLast('\\')?.trim()?.ifBlank { null }
    }

    /** Fetches the record CSV and saves it to Downloads; returns where it landed. */
    suspend fun saveArtisanRecordCsv(context: Context, api: QuestionnaireApi, artisanId: String, questionnaireId: String? = null): String {
        val response = api.artisanRecordCsv(artisanId, questionnaireId)
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        val name = dispositionFileName(response.headers()["Content-Disposition"]) ?: throw IllegalStateException("The server sent no file name.")
        val bytes = withContext(Dispatchers.IO) { response.body()?.bytes() } ?: throw IllegalStateException("The download didn't finish.")
        return withContext(Dispatchers.IO) { saveToDownloads(context, bytes, name, "text/csv") }
    }

    private fun saveToDownloads(context: Context, bytes: ByteArray, name: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Couldn't create the file in Downloads")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Couldn't open the file in Downloads")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
            return "Downloads/$name"
        }
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()
        val dest = File(downloads, name)
        dest.writeBytes(bytes)
        return dest.absolutePath
    }
}
