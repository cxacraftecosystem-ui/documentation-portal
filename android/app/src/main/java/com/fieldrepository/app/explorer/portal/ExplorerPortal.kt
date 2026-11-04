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

object ExplorerPortal {
    const val NAME = "documentation-portal"
    const val ROOT_NAME = "All records"

    val statusOptions: List<Pair<String, String>> = DataStatusChoice.options.map { it.value to it.label }
    val defaultStatus: String? = DataStatusChoice.DEFAULT

    fun statusParam(choice: String?): String? = choice?.let { DataStatusChoice.param(it) }

    fun baseUrl(): String = BuildConfig.DEFAULT_API_BASE_URL

    private fun retrofit(context: Context): Retrofit = ApiClient.retrofit(TokenStore(context.applicationContext))

    fun api(context: Context): ExplorerApi = retrofit(context).create(ExplorerApi::class.java)

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
