package com.fieldrepository.app.explorer.portal

import android.content.Context
import androidx.compose.runtime.Composable
import com.fieldrepository.app.BuildConfig
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.explorer.data.ExplorerApi
import com.fieldrepository.app.explorer.ui.ExplorerOffline
import com.fieldrepository.app.ui.rememberOfflinePreview
import com.fieldrepository.app.ui.DataStatusChoice
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object ExplorerPortal {
    const val NAME = "documentation-portal"
    const val ROOT_NAME = "All records"
    const val DOWNLOAD_FOLDER = "FieldRepository"
    const val PRODUCT = "Field Repository"
    const val WEB_BASE = "https://field-repository.vercel.app"
    const val REPORT_WAIT_SECONDS = 150L

    val statusOptions: List<Pair<String, String>> = DataStatusChoice.options.map { it.value to it.label }
    val defaultStatus: String? = DataStatusChoice.DEFAULT

    fun statusParam(choice: String?): String? = choice?.let { DataStatusChoice.param(it) }

    fun baseUrl(): String = BuildConfig.DEFAULT_API_BASE_URL

    private fun retrofit(context: Context): Retrofit = ApiClient.retrofit(TokenStore(context.applicationContext))

    private fun patient(context: Context): Retrofit {
        val base = retrofit(context)
        val client = (base.callFactory() as OkHttpClient).newBuilder()
            .readTimeout(REPORT_WAIT_SECONDS, TimeUnit.SECONDS)
            .build()
        return base.newBuilder().client(client).build()
    }

    fun api(context: Context): ExplorerApi = patient(context).create(ExplorerApi::class.java)

    fun client(context: Context): OkHttpClient = retrofit(context).callFactory() as OkHttpClient

    fun token(context: Context): String? = TokenStore(context.applicationContext).getToken()

    fun userId(context: Context): String? = runCatching { TokenStore(context.applicationContext).getUser()?.id }.getOrNull()

    @Composable
    fun rememberOffline(mediaId: String?): ExplorerOffline? {
        if (mediaId == null) return null
        val preview = rememberOfflinePreview(mediaId)
        return ExplorerOffline(preview.file, preview.keep)
    }
}
