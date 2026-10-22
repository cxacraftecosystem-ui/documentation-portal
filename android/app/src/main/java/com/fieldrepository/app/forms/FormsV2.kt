package com.fieldrepository.app.forms

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.questionnaire.data.QuestionnaireExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Field Repository's forms library on Android (UNIFY 4.2, stream U4): the forms API, the offline
 * authoring outbox (A15), the owner-stamped library copy (A16). FR-only: designer-portal Android keeps
 * its own library and serves no admin authoring (owner decision 5). The sessions are the shared
 * tree's (`questionnaire/`), which reaches this through `QuestionnairePortal`.
 */
class FormsV2 private constructor(context: Context) {
    private val app = context.applicationContext
    private val tokens = TokenStore(app)
    val api: QuestionnaireFormsApi = ApiClient.retrofit(tokens, OutboxSyncWorker.readCacheDir(app)).create(QuestionnaireFormsApi::class.java)
    private val liveApi: QuestionnaireFormsApi = ApiClient.retrofit(tokens).create(QuestionnaireFormsApi::class.java)
    val outbox = FormAuthoringOutbox(FileFormQueueStore(File(app.filesDir, "questionnaire-forms-outbox.json")))
    val transport: FormTransport = ApiFormTransport(liveApi)
    val cache = FormLibraryCache(File(app.noBackupFilesDir, "questionnaire-forms.json"))

    /** One pass of the forms outbox; the background job runs it before the session queue. */
    suspend fun sync(): FormPassResult = outbox.sync(transport, tokens.getUser()?.id)

    companion object {
        @Volatile private var instance: FormsV2? = null
        fun get(context: Context): FormsV2 = instance ?: synchronized(this) { instance ?: FormsV2(context).also { instance = it } }
    }
}

/** The home's "New form" button, held until the forms tab takes it. */
object FormsNav {
    val newForm = MutableStateFlow(false)
}

/** Any server-named download (workbook, pro-forma, corpus, annexure), saved to Downloads. */
object FormDownloads {
    suspend fun save(context: Context, response: retrofit2.Response<okhttp3.ResponseBody>, mimeType: String, fallbackName: String? = null): String {
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
        val name = QuestionnaireExport.dispositionFileName(response.headers()["Content-Disposition"]) ?: fallbackName
            ?: throw IllegalStateException("The server sent no file name.")
        val bytes = withContext(Dispatchers.IO) { response.body()?.bytes() } ?: throw IllegalStateException("The download didn't finish.")
        return withContext(Dispatchers.IO) { toDownloads(context, bytes, name, mimeType) }
    }

    private fun toDownloads(context: Context, bytes: ByteArray, name: String, mimeType: String): String {
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
