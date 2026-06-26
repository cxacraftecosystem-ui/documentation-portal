package com.fieldrepository.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Offline outbox. When the device has no connection, a new record (and copies of its captured media)
 * is persisted to local app storage instead of being sent to the server. A sync pass (on reconnect or
 * app start) replays every queued entry — creating the record, then uploading its media — and only
 * then removes the local copy, so field data is never lost in transit.
 */

/** A captured file copied into local app storage, plus the params needed to upload it later. */
@Serializable
data class PendingMedia(
    val localPath: String,
    val originalFilename: String,
    val mimeType: String,
    val mediaType: String,
    val caption: String? = null,
    val recordName: String? = null,
    val customSegment: String? = null,
    val overrideBaseName: String? = null,
    val batchIndex: Int = 1,
    val stageStep: Int? = null,
    val processing: List<String>? = null,
    // Override the link target type (e.g. "processstep" for a process step's media). Null = the entry's
    // own type. `stepIndex` (process only) selects which created step's server id to attach to on sync.
    val linkedType: String? = null,
    val stepIndex: Int? = null
)

/** One queued create: the record type, its serialized create request, and the media to attach after. */
@Serializable
data class PendingEntry(
    val id: String,
    val type: String,
    val payloadJson: String,
    val label: String,
    val media: List<PendingMedia> = emptyList(),
    val createdAt: String
)

/** One captured media item to stage for an offline entry (input form for staging). */
data class OfflineMediaSpec(
    val uri: Uri,
    val caption: String? = null,
    val recordName: String? = null,
    val customSegment: String? = null,
    val overrideBaseName: String? = null,
    val batchIndex: Int = 1,
    val stageStep: Int? = null,
    val processing: List<String>? = null,
    val linkedType: String? = null,
    val stepIndex: Int? = null
)

/** Live connectivity check (validated internet, not just an attached interface). */
object ConnectivityObserver {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

object OfflineOutbox {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private fun dir(context: Context): File = File(context.filesDir, "outbox").apply { mkdirs() }
    private fun mediaDir(context: Context): File = File(dir(context), "media").apply { mkdirs() }
    private fun queueFile(context: Context): File = File(dir(context), "queue.json")

    private fun read(context: Context): List<PendingEntry> {
        val file = queueFile(context)
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<PendingEntry>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun write(context: Context, entries: List<PendingEntry>) {
        queueFile(context).writeText(json.encodeToString(entries))
    }

    suspend fun all(context: Context): List<PendingEntry> = mutex.withLock { read(context) }

    suspend fun count(context: Context): Int = mutex.withLock { read(context).size }

    suspend fun enqueue(context: Context, entry: PendingEntry) = mutex.withLock {
        write(context, read(context) + entry)
    }

    /** Remove a synced entry and delete the local media copies it owned. */
    suspend fun remove(context: Context, entry: PendingEntry) = mutex.withLock {
        write(context, read(context).filterNot { it.id == entry.id })
        entry.media.forEach { runCatching { File(it.localPath).delete() } }
    }

    /** Copy a captured content Uri into local app storage so it survives offline until uploaded. */
    fun stageMedia(
        context: Context,
        uri: Uri,
        caption: String?,
        recordName: String?,
        customSegment: String?,
        overrideBaseName: String?,
        batchIndex: Int,
        processing: List<String>?,
        stageStep: Int? = null,
        linkedType: String? = null,
        stepIndex: Int? = null
    ): PendingMedia {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val extension = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val target = File(mediaDir(context), UUID.randomUUID().toString() + (extension?.let { ".$it" } ?: ""))
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
        } ?: throw IllegalStateException("Unable to read the captured media for offline storage")
        return PendingMedia(
            localPath = target.absolutePath,
            originalFilename = originalName,
            mimeType = mimeType,
            mediaType = inferMediaType(mimeType),
            caption = caption,
            recordName = recordName,
            customSegment = customSegment,
            overrideBaseName = overrideBaseName,
            batchIndex = batchIndex,
            stageStep = stageStep,
            processing = processing,
            linkedType = linkedType,
            stepIndex = stepIndex
        )
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    private fun inferMediaType(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "IMAGE"
        mimeType.startsWith("video/") -> "VIDEO"
        mimeType.startsWith("audio/") -> "AUDIO"
        mimeType == "application/pdf" -> "PDF"
        else -> "DOCUMENT"
    }
}
