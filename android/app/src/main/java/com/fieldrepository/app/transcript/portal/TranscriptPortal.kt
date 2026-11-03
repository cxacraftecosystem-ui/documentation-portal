package com.fieldrepository.app.transcript.portal

import android.content.Context
import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.OutboxSyncWorker
import com.fieldrepository.app.data.TokenStore
import com.fieldrepository.app.transcript.data.RetrofitTranscriptBackend
import com.fieldrepository.app.transcript.data.TranscriptApi
import com.fieldrepository.app.transcript.data.TranscriptBackend
import com.fieldrepository.app.transcript.data.TranscriptRepository
import com.fieldrepository.app.transcript.data.TranscriptStore
import kotlinx.serialization.json.Json

object TranscriptPortal {
    const val NAME = "documentation-portal"

    val json: Json get() = ApiClient.json

    fun backend(context: Context): TranscriptBackend =
        RetrofitTranscriptBackend(ApiClient.retrofit(TokenStore(context.applicationContext)).create(TranscriptApi::class.java))

    fun userId(context: Context): String? = TokenStore(context.applicationContext).getUser()?.id

    fun store(context: Context): TranscriptStore? =
        userId(context)?.let { TranscriptStore(context.applicationContext.filesDir, it, json) }

    fun repository(context: Context): TranscriptRepository? =
        store(context)?.let { TranscriptRepository(backend(context), it, json) }

    fun requestSync(context: Context) = OutboxSyncWorker.schedule(context.applicationContext)

    fun fileProviderAuthority(context: Context): String = context.packageName + ".fileprovider"
}
