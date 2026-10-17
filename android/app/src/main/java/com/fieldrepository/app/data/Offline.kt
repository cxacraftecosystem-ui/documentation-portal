package com.fieldrepository.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

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
    val stepIndex: Int? = null,
    /**
     * Where the capture was made. Null on entries queued by builds before 2026-10-16, which dropped it
     * on the offline path while the online path sent it.
     */
    val location: LocationRequest? = null,
    /**
     * When the file was put in the outbox, ISO-8601. The upload's `recordedAt` used to be the moment
     * the sync ran, which for a week-old capture is a week wrong. Null on older entries.
     */
    val capturedAt: String? = null
)

/**
 * One queued write: the record type, its serialized request, and the media to attach after.
 *
 * [kind] says what the replay does with [payloadJson] — see [OutboxKind]. Every field added after
 * the first release is defaulted, so a queue written by an older build decodes unchanged after an
 * app update (`OfflineQueueFileTest` pins that).
 */
@Serializable
data class PendingEntry(
    val id: String,
    val type: String,
    val payloadJson: String,
    val label: String,
    val media: List<PendingMedia> = emptyList(),
    val createdAt: String,
    /** [OutboxKind.CREATE] for everything queued before edits, comments and attachments could queue. */
    val kind: String = OutboxKind.CREATE,
    /** The existing server record an UPDATE edits, an ATTACH attaches to, or an ACTION acts on. */
    val targetId: String? = null,
    /**
     * The account that queued this entry. The sync pass replays only the signed-in account's entries,
     * so a shared handset never files one researcher's fieldwork under another's name. Null on
     * entries queued before 2026-10-16; those replay under whoever is signed in, as they always did.
     */
    val ownerUserId: String? = null,
    /**
     * REPLAY PROGRESS, written back as each step lands. All defaulted, so an entry queued by an
     * older build decodes into "nothing has happened yet" and replays exactly as it used to.
     *
     * [createdId] is the load-bearing one: non-null means the record IS on the server, and re-sending
     * the body would make a SECOND one. A replay that finds it set skips the create and goes straight
     * to the media. Without it, every media failure re-created the record on the next pass — once per
     * pass, for as long as the signal stayed bad, which is the whole reason the entry is here.
     */
    val createdId: String? = null,
    /** A created process's step ids in submit order, for the media that attaches to a `processstep`. */
    val createdStepIds: List<String> = emptyList(),
    /** Indices into [media] whose bytes are already on the server. Never uploaded twice. */
    val uploadedMedia: List<Int> = emptyList(),
    /**
     * The server's final answer, when it refused this entry for a reason no retry will change.
     *
     * The entry and its copied files STAY on the device — being refused is not a reason to destroy
     * the only copy of a day's fieldwork — and the sync pass steps over it instead of stopping, so
     * one unacceptable record cannot strand the records queued behind it.
     */
    val failure: String? = null,
    /** When [failure] was recorded, ISO-8601. */
    val failedAt: String? = null,
    /**
     * Sent as the `Idempotency-Key` header on the create (or comment) this entry replays, so a create
     * that reached the server but whose answer was lost is answered from the first record instead of
     * making a second (backend `app/core/idempotency.py`, 30 days). Minted when the entry is queued,
     * or before the first send for an entry an older build queued without one.
     */
    val idempotencyKey: String? = null,
    /** A [OutboxKind.SEQUENCE] entry's requests, replayed in order. */
    val steps: List<OutboxStep> = emptyList(),
    /** How many of [steps] the server has accepted; the replay resumes at the next one. */
    val completedSteps: Int = 0,
    /**
     * The server answered this entry's create with "this Idempotency-Key was already used for a
     * different request": a record may already be on the server under it. Not a refusal of the
     * record. The entry, its body and its files are kept and shown for review, and "Send again"
     * resends the SAME body under a NEW key ([OfflineOutbox.clearFailure]).
     */
    val needsReview: Boolean = false
)

/**
 * One request of a [OutboxKind.SEQUENCE] entry. [op] names what the replay sends, for example
 * `questionnaire.update`; [payloadJson] is its serialized body.
 */
@Serializable
data class OutboxStep(
    val op: String,
    val targetId: String? = null,
    val payloadJson: String
)

/** What a queued entry's replay does. Strings, not an enum, so an unknown value still decodes. */
object OutboxKind {
    /** POST the payload as a new record, then attach the media to it. */
    const val CREATE = "create"
    /** PATCH/PUT the payload onto [PendingEntry.targetId], then attach the media to it. */
    const val UPDATE = "update"
    /** No payload: attach the media to the existing record [PendingEntry.targetId]. */
    const val ATTACH = "attach"
    /** A small write that is not a record (a comment, feedback, a task's progress). */
    const val ACTION = "action"
    /**
     * An edit that takes more than one request, replayed as one ordered unit: [PendingEntry.steps]
     * in order, each ticked off as it lands, stopping at the first one the server refuses. The media
     * attach to [PendingEntry.targetId] once every step is in.
     */
    const val SEQUENCE = "sequence"
}

/**
 * The entries [userId] may replay: their own, plus legacy entries that carry no owner.
 *
 * Another account's entries stay on the device, untouched, until that account signs in again.
 */
internal fun List<PendingEntry>.replayableBy(userId: String?): List<PendingEntry> =
    filter { it.ownerUserId == null || it.ownerUserId == userId }

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
    val stepIndex: Int? = null,
    val location: LocationRequest? = null
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
    private val mutex = Mutex()

    /**
     * Something the researcher has to be told about the queue itself, waiting to be said once.
     *
     * Only [read] raises one, and only when a queue file would not parse — a silent failure with no
     * other symptom, because the queue simply comes back empty and the banner counting entries drops
     * to zero. Taken (and cleared) by the sync pass, which has a Context and can say it out loud.
     */
    private val alert = AtomicReference<String?>(null)

    private fun dir(context: Context): File = File(context.filesDir, "outbox").apply { mkdirs() }
    private fun mediaDir(context: Context): File = File(dir(context), "media").apply { mkdirs() }
    private fun queueFile(context: Context): File = File(dir(context), "queue.json")

    /** The one pending queue-level alert, cleared as it is taken so it is reported exactly once. */
    fun takeAlert(): String? = alert.getAndSet(null)

    /** Read the queue; damage is quarantined and reported — see [OfflineQueueFile.read]. */
    private fun read(context: Context): List<PendingEntry> =
        OfflineQueueFile.read(queueFile(context)) { message -> alert.set(message) }

    private fun write(context: Context, entries: List<PendingEntry>) =
        OfflineQueueFile.write(queueFile(context), entries)

    // Every read and write below runs on the IO dispatcher. These are called from `LaunchedEffect`s
    // and save handlers that start on the main dispatcher, and reading the whole queue — every
    // record, every media descriptor — off flash on the main thread is how a form janks on the frame
    // it saves. The lock is taken INSIDE the dispatcher switch so a waiter never holds a main-thread
    // frame while another coroutine finishes its file IO.

    suspend fun all(context: Context): List<PendingEntry> =
        withContext(Dispatchers.IO) { mutex.withLock { read(context) } }

    suspend fun count(context: Context): Int =
        withContext(Dispatchers.IO) { mutex.withLock { read(context).size } }

    suspend fun enqueue(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, read(context) + entry) }
    }

    /**
     * Write [entry] back over the queued copy carrying the same id.
     *
     * Read-modify-write under the lock and addressed by id, never by writing a snapshot back
     * wholesale: a sync pass replays a snapshot taken at its start while the researcher may still be
     * saving new records into the same file, and a wholesale write would delete them. An entry that
     * is no longer queued is left alone rather than resurrected.
     */
    suspend fun update(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entry.id }) {
                write(context, current.map { if (it.id == entry.id) entry else it })
            }
        }
    }

    /**
     * Record why the server will never accept this entry. The entry and its files are KEPT: a refusal
     * is a reason to stop retrying and tell someone, not a reason to destroy the only copy.
     */
    suspend fun markFailure(context: Context, entryId: String, reason: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entryId }) {
                write(
                    context,
                    current.map {
                        if (it.id == entryId) it.copy(failure = reason, failedAt = Instant.now().toString()) else it
                    }
                )
            }
        }
    }

    /**
     * Forget the server's refusal so the next sync pass tries [entryId] again — for after the cause
     * has been fixed (an access level granted, a duplicate number corrected on the server).
     */
    suspend fun clearFailure(context: Context, entryId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entryId && it.failure != null }) {
                write(context, current.map { entry ->
                    if (entry.id != entryId) entry
                    else entry.copy(
                        failure = null,
                        failedAt = null,
                        needsReview = false,
                        // A key the server says belongs to a different request can never succeed
                        // again; the researcher reviewed it and chose to send, so it gets a new one.
                        idempotencyKey = if (entry.needsReview) java.util.UUID.randomUUID().toString() else entry.idempotencyKey
                    )
                })
            }
        }
    }

    /** Park an entry for review (see [PendingEntry.needsReview]). Kept, with its files. */
    suspend fun markReview(context: Context, entryId: String, reason: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entryId }) {
                write(context, current.map {
                    if (it.id == entryId) it.copy(failure = reason, failedAt = Instant.now().toString(), needsReview = true) else it
                })
            }
        }
    }

    /**
     * THE AADHAAR NUMBER TYPED AGAIN, for an entry held by [AadhaarAtRest.numberWithheld]: the
     * withheld marker replaced by [number] (sealed again as the queue is written), the hold cleared.
     * Every other answer in the payload is re-serialised untouched. False when the entry is gone or
     * its payload will not parse.
     */
    suspend fun retypeAadhaar(context: Context, entryId: String, number: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read(context)
                val entry = current.firstOrNull { it.id == entryId } ?: return@withLock false
                val rewritten = AadhaarAtRest.retype(entry.payloadJson, number) ?: return@withLock false
                write(context, current.map {
                    if (it.id == entryId) it.copy(payloadJson = rewritten, failure = null, failedAt = null) else it
                })
                true
            }
        }

    /** Every entry the server has refused, for a surface that wants to show or discard them. */
    suspend fun failed(context: Context): List<PendingEntry> =
        withContext(Dispatchers.IO) { mutex.withLock { read(context).filter { it.failure != null } } }

    /** Remove a synced entry and delete the local media copies it owned. */
    suspend fun remove(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, read(context).filterNot { it.id == entry.id }) }
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
        stepIndex: Int? = null,
        location: LocationRequest? = null
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
            stepIndex = stepIndex,
            location = location,
            capturedAt = Instant.now().toString()
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

/**
 * The queue file's format and its two failure rules, kept apart from [OfflineOutbox] so a JVM test
 * can drive them with a temp file and no Android `Context`.
 */
internal object OfflineQueueFile {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Read the queue, or quarantine it and say so through [onDamage].
     *
     * A FILE THAT WILL NOT PARSE IS NEVER TREATED AS AN EMPTY QUEUE. It used to be — a bare
     * `getOrDefault(emptyList())` — and the next write then overwrote the damage with whatever was
     * left, so a queue full of unsent fieldwork became zero entries, with no error, no trace and no
     * way back. The bytes are moved aside under a name a support request can ask for instead, the
     * researcher is told the count they can see is not the whole story, and the app carries on able
     * to queue new work (refusing to would turn one bad file into a device that cannot save at all).
     *
     * A ZERO-LENGTH file counts as damage too: [write] never produces one — an empty queue is the two
     * bytes `[]` — so a truncated file is the fingerprint of an interrupted non-atomic write.
     */
    fun read(file: File, onDamage: (String) -> Unit): List<PendingEntry> {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrElse { error ->
            quarantine(file, "could not be read (${error.javaClass.simpleName})", onDamage)
            return emptyList()
        }
        if (text.isBlank()) {
            quarantine(file, "was empty — a save was interrupted", onDamage)
            return emptyList()
        }
        return runCatching { json.decodeFromString<List<PendingEntry>>(text) }
            // Opened in memory only: on flash a typed Aadhaar is sealed (AadhaarAtRest).
            .map { entries -> entries.map { it.copy(payloadJson = AadhaarAtRest.openPayload(it.payloadJson)) } }
            .getOrElse { error ->
                quarantine(file, "could not be understood (${error.javaClass.simpleName})", onDamage)
                emptyList()
            }
    }

    /** Replace the queue file with [entries] atomically — see [writeFileAtomically]. */
    fun write(file: File, entries: List<PendingEntry>) {
        // A typed Aadhaar is sealed under the Keystore before it touches flash, or not written at
        // all (AadhaarAtRest). A sent entry leaves the queue, so nothing of it outlives the send.
        val sealed = entries.map { it.copy(payloadJson = AadhaarAtRest.sealPayload(it.payloadJson)) }
        writeFileAtomically(file, json.encodeToString(sealed).toByteArray())
    }

    /** Move a damaged queue file out of the way, keeping every byte, and raise the alert. */
    private fun quarantine(file: File, reason: String, onDamage: (String) -> Unit) {
        val kept = File(file.parentFile, "queue.damaged-${System.currentTimeMillis()}.json")
        val moved = runCatching { file.renameTo(kept) }.getOrDefault(false)
        onDamage(
            if (moved) {
                "Saved records on this phone $reason. Nothing was deleted (kept as ${kept.name}). Report this before uninstalling."
            } else {
                "Saved records on this phone $reason and could not be set aside. Report this before uninstalling."
            }
        )
    }
}
