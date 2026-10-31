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

/**
 * FIELD REPOSITORY'S HALF OF THE SHARED TRANSCRIPT TREES. NOT SHARED.
 *
 * `transcript/data`, `transcript/ui` and their tests are the same Kotlin in both portals
 * (`scripts/transcript_android.py`). What differs — the HTTP stack, who is signed in, how a
 * background pass is asked for — is reached through this object, which designer-portal
 * declares with the same members over its own code.
 */
object TranscriptPortal {
    const val NAME = "documentation-portal"

    val json: Json get() = ApiClient.json

    fun backend(context: Context): TranscriptBackend =
        RetrofitTranscriptBackend(ApiClient.retrofit(TokenStore(context.applicationContext)).create(TranscriptApi::class.java))

    fun userId(context: Context): String? = TokenStore(context.applicationContext).getUser()?.id

    /** The signed-in account's store, or null when nobody is signed in. */
    fun store(context: Context): TranscriptStore? =
        userId(context)?.let { TranscriptStore(context.applicationContext.filesDir, it, json) }

    fun repository(context: Context): TranscriptRepository? =
        store(context)?.let { TranscriptRepository(backend(context), it, json) }

    /** Send queued edits as soon as there is a network, app open or not (WorkManager, `data/OutboxSyncWorker.kt`). */
    fun requestSync(context: Context) = OutboxSyncWorker.schedule(context.applicationContext)

    fun fileProviderAuthority(context: Context): String = context.packageName + ".fileprovider"
}
