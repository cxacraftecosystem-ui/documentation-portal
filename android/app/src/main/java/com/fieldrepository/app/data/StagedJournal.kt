package com.fieldrepository.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
private data class StagedObject(
    val objectKey: String,
    val owner: String,
    val checksum: String? = null,
    val uploadId: String? = null
)

object StagedJournal {
    private val owner = UUID.randomUUID().toString()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    @Volatile
    private var appContext: Context? = null

    private fun file(context: Context): File = File(context.filesDir, "staged-objects.json")

    private fun read(context: Context): List<StagedObject> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<StagedObject>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun write(context: Context, entries: List<StagedObject>) {
        if (entries.isEmpty()) file(context).delete()
        else writeFileAtomically(file(context), json.encodeToString(entries).toByteArray())
    }

    suspend fun record(
        context: Context,
        objectKey: String,
        checksum: String? = null,
        uploadId: String? = null
    ): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                appContext = context.applicationContext
                val entries = read(context)
                val existing = entries.firstOrNull { it.objectKey == objectKey }
                write(
                    context,
                    entries.filterNot { it.objectKey == objectKey } + StagedObject(
                        objectKey = objectKey,
                        owner = owner,
                        checksum = checksum ?: existing?.checksum,
                        uploadId = uploadId ?: existing?.uploadId
                    )
                )
            }
        }

    suspend fun checksumFor(objectKey: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context = appContext ?: return@withLock null
            read(context).firstOrNull { it.objectKey == objectKey }?.checksum
        }
    }

    suspend fun drop(objectKey: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context = appContext ?: return@withLock
            val entries = read(context)
            if (entries.any { it.objectKey == objectKey }) write(context, entries.filterNot { it.objectKey == objectKey })
        }
    }

    suspend fun sweep(context: Context, delete: suspend (objectKey: String, uploadId: String?) -> Boolean): Int {
        val orphans = withContext(Dispatchers.IO) {
            mutex.withLock {
                appContext = context.applicationContext
                read(context).filter { it.owner != owner }.map { it.objectKey to it.uploadId }
            }
        }
        var reclaimed = 0
        for ((objectKey, uploadId) in orphans) {
            if (delete(objectKey, uploadId)) {
                drop(objectKey)
                reclaimed++
            }
        }
        return reclaimed
    }
}
