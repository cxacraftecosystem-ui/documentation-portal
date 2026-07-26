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

/** One key in the bucket that no record points at yet, and the process that put it there. */
@Serializable
private data class StagedObject(
    val objectKey: String,
    val owner: String,
    val checksum: String? = null,
    /**
     * The S3 multipart upload this key belongs to, when it is one.
     *
     * WITHOUT IT THE SWEEP CANNOT RECLAIM A LARGE FILE. Until a multipart upload is completed there
     * is no object at the key at all — only uploaded parts, which are billed and which nothing but
     * `AbortMultipartUpload` can remove, and which that call needs the uploadId to name. The journal
     * recorded the key alone, so a kill part-way through a long video (exactly the transfer most
     * likely to be interrupted, on exactly the connection most likely to interrupt it) left parts in
     * the bucket that no sweep, then or ever, could find its way back to.
     */
    val uploadId: String? = null
)

/**
 * Journal of staged objects — bytes already in the bucket that no record points at yet — and the
 * sweep that reclaims the ones nothing ever claimed.
 *
 * Every upload presigns a key and pushes the bytes well before `/media/complete` links them to a
 * record; eager pre-upload widens that gap to however long the researcher spends typing. If the
 * process dies in between (app swiped away mid-transfer, a low-memory kill, a flat battery in a
 * workshop) the object stays in storage and nothing in memory knows it exists. So the key goes to
 * disk the moment it is presigned, with the same file + Mutex + kotlinx mechanism the offline outbox
 * uses, and the next launch deletes whatever is still unclaimed.
 *
 * Ownership is per process, not per object age: an entry written by THIS process belongs to a form
 * the user may still be filling in, so only entries carrying some earlier [owner] are swept. (The
 * web has to approximate that with a heartbeat because one browser has many tabs; a phone has one
 * process, and a process that is gone cannot still be waiting to save.)
 */
object StagedJournal {
    /** Identifies this process's entries; anything else in the file was left behind by a dead one. */
    private val owner = UUID.randomUUID().toString()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    // Captured on the first record/sweep so [drop] and [checksumFor] — reached from save paths that
    // carry no Context — can still reach the file. An applicationContext, so nothing is leaked.
    @Volatile
    private var appContext: Context? = null

    private fun file(context: Context): File = File(context.filesDir, "staged-objects.json")

    private fun read(context: Context): List<StagedObject> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<StagedObject>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun write(context: Context, entries: List<StagedObject>) {
        if (entries.isEmpty()) file(context).delete() else file(context).writeText(json.encodeToString(entries))
    }

    /**
     * Note a presigned key as staged, before the bytes move, so a kill mid-transfer is still covered.
     * Called again with the [checksum] once the transfer has hashed the content — the staged object's
     * hash has to outlive the upload coroutine to reach the save that eventually claims it.
     *
     * MERGES rather than replaces. Each call knows one thing: the presign knows the [uploadId], the
     * finished transfer knows the [checksum], and neither knows the other's. Overwriting the row
     * would mean whichever came second erased what the first had learned — and for a multipart that
     * is the only handle on its uploaded parts.
     */
    suspend fun record(
        context: Context,
        objectKey: String,
        checksum: String? = null,
        uploadId: String? = null
    ): Unit =
        // On IO throughout: these are reached from save paths running on the main dispatcher, and the
        // journal is worthless if writing it stutters the form the researcher is filling in.
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

    /** The content hash recorded for a staged object while its bytes were uploaded, if any. */
    suspend fun checksumFor(objectKey: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context = appContext ?: return@withLock null
            read(context).firstOrNull { it.objectKey == objectKey }?.checksum
        }
    }

    /** Forget a key: it is now attached to a record, or has been deliberately deleted. */
    suspend fun drop(objectKey: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val context = appContext ?: return@withLock
            val entries = read(context)
            if (entries.any { it.objectKey == objectKey }) write(context, entries.filterNot { it.objectKey == objectKey })
        }
    }

    /**
     * Delete every object a previous run left staged, returning how many were reclaimed. [delete] is
     * handed the key and, for an interrupted multipart, the [StagedObject.uploadId] its parts belong
     * to; it reports whether the server settled the key — removed it, or refused because a record now
     * owns it. Anything unsettled (no signal, a gateway failure) stays journalled for the next launch
     * rather than being silently abandoned in the bucket.
     */
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
