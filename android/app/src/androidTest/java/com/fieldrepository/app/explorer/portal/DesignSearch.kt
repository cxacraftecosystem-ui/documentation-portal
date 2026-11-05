package com.fieldrepository.app.explorer.portal

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.FieldRepositoryApi
import com.fieldrepository.app.ui.DataRecordSearch
import com.fieldrepository.app.ui.DataUnifiedSearch
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private fun repository(context: Context, unified: String?): FieldRepository {
    val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val body = if (unified != null && chain.request().url.encodedPath.endsWith("/search/unified")) unified else null
        okhttp3.Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(if (body == null) 404 else 200)
            .message(if (body == null) "Not Found" else "OK")
            .body((body ?: "{}").toResponseBody("application/json".toMediaType()))
            .build()
    }).build()
    val api = Retrofit.Builder()
        .baseUrl("http://design.invalid/")
        .client(client)
        .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(FieldRepositoryApi::class.java)
    return FieldRepository(api, TokenStore(context))
}

@Composable
fun DesignRecordSearch(onOpen: (recordType: String, recordId: String, title: String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val repo = remember { repository(context, null) }
    DataRecordSearch(repo, onOpen)
}

@Composable
fun DesignUnifiedSearch(initial: String, unified: String) {
    val context = LocalContext.current.applicationContext
    val repo = remember(unified) { repository(context, unified) }
    DataUnifiedSearch(repository = repo, initial = initial, onOpenInData = { _, _, _ -> }, onPlay = { _, _, _, _ -> }, onOpenRemote = { _, _ -> }, onClose = {})
}
