package com.fieldrepository.app.data

import com.fieldrepository.app.transcript.data.TranscribeChoices
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

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
    val linkedType: String? = null,
    val stepIndex: Int? = null,
    val location: LocationRequest? = null,
    val capturedAt: String? = null
)

@Serializable
data class PendingEntry(
    val id: String,
    val type: String,
    val payloadJson: String,
    val label: String,
    val media: List<PendingMedia> = emptyList(),
    val createdAt: String,
    val kind: String = OutboxKind.CREATE,
    val targetId: String? = null,
    val ownerUserId: String? = null,
    val createdId: String? = null,
    val createdStepIds: List<String> = emptyList(),
    val uploadedMedia: List<Int> = emptyList(),
    val failure: String? = null,
    val failedAt: String? = null,
    val idempotencyKey: String? = null,
    val steps: List<OutboxStep> = emptyList(),
    val completedSteps: Int = 0,
    val needsReview: Boolean = false,
    val failureFields: List<String> = emptyList(),
    val failureCode: String? = null,
    val existingRecordId: String? = null,
    val existingRecordLabel: String? = null,
    val duplicateCandidates: List<OutboxDuplicateCandidate> = emptyList(),
    val attachedToExisting: Boolean = false
)

@Serializable
data class OutboxDuplicateCandidate(
    val id: String,
    val name: String,
    val place: String? = null,
    val craft: String? = null
)

fun PendingEntry.isPossibleDuplicate(): Boolean = duplicateCandidates.isNotEmpty() && createdId == null

fun possibleDuplicateTrayText(entry: PendingEntry): String? {
    val first = entry.duplicateCandidates.firstOrNull() ?: return null
    val place = first.place?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
    return "Not saved yet. This looks like ${first.name}$place."
}

fun PendingEntry.asSamePerson(candidateId: String = duplicateCandidates.first().id): PendingEntry = copy(
    createdId = candidateId,
    attachedToExisting = true,
    duplicateCandidates = emptyList(),
    failure = null,
    failedAt = null,
    needsReview = false
)

fun PendingEntry.asDifferentPerson(): PendingEntry {
    val ids = duplicateCandidates.map { it.id }
    val parsed = runCatching { Json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
    val body = if (parsed == null) payloadJson else {
        val previous = (parsed["duplicateAcknowledged"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        val acknowledged = (previous + ids).distinct().take(20)
        JsonObject(parsed + ("duplicateAcknowledged" to JsonArray(acknowledged.map { JsonPrimitive(it) }))).toString()
    }
    return copy(
        payloadJson = body,
        duplicateCandidates = emptyList(),
        failure = null,
        failedAt = null,
        needsReview = false
    )
}

@Serializable
data class OutboxStep(
    val op: String,
    val targetId: String? = null,
    val payloadJson: String
)

object OutboxKind {
    const val CREATE = "create"
    const val UPDATE = "update"
    const val ATTACH = "attach"
    const val ACTION = "action"
    const val SEQUENCE = "sequence"
}

internal fun List<PendingEntry>.replayableBy(userId: String?): List<PendingEntry> =
    filter { it.ownerUserId == null || it.ownerUserId == userId }

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

    private val alert = AtomicReference<String?>(null)

    private fun dir(context: Context): File = File(context.filesDir, "outbox").apply { mkdirs() }
    private fun mediaDir(context: Context): File = File(dir(context), "media").apply { mkdirs() }
    private fun queueFile(context: Context): File = File(dir(context), "queue.json")

    val revision: StateFlow<Long> get() = OfflineQueueFile.revision

    fun takeAlert(): String? = alert.getAndSet(null)

    private fun read(context: Context): List<PendingEntry> =
        OfflineQueueFile.read(queueFile(context)) { message -> alert.set(message) }

    private fun write(context: Context, entries: List<PendingEntry>) =
        OfflineQueueFile.write(queueFile(context), entries)

    suspend fun all(context: Context): List<PendingEntry> =
        withContext(Dispatchers.IO) { mutex.withLock { read(context) } }

    suspend fun count(context: Context): Int =
        withContext(Dispatchers.IO) { mutex.withLock { read(context).size } }

    suspend fun enqueue(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, read(context) + entry) }
    }

    suspend fun update(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entry.id }) {
                write(context, current.map { if (it.id == entry.id) entry else it })
            }
        }
    }

    suspend fun markPossibleDuplicate(
        context: Context,
        entryId: String,
        candidates: List<OutboxDuplicateCandidate>
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entryId }) {
                write(
                    context,
                    current.map {
                        if (it.id != entryId) it else {
                            val held = it.copy(duplicateCandidates = candidates)
                            held.copy(
                                failure = possibleDuplicateTrayText(held) ?: "Not saved yet. This may already be recorded.",
                                failedAt = Instant.now().toString()
                            )
                        }
                    }
                )
            }
        }
    }

    suspend fun markFailure(
        context: Context,
        entryId: String,
        reason: String,
        fields: List<String> = emptyList(),
        code: String? = null,
        existingId: String? = null,
        existingLabel: String? = null
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entryId }) {
                write(
                    context,
                    current.map {
                        if (it.id == entryId) {
                            it.copy(
                                failure = reason,
                                failedAt = Instant.now().toString(),
                                failureFields = fields,
                                failureCode = code,
                                existingRecordId = existingId,
                                existingRecordLabel = existingLabel
                            )
                        } else it
                    }
                )
            }
        }
    }

    suspend fun fixAndResend(context: Context, entryId: String, payloadJson: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.none { it.id == entryId }) return@withLock false
            write(context, current.map { entry ->
                if (entry.id != entryId) entry
                else {
                    val fixed = if (entry.kind == OutboxKind.SEQUENCE) {
                        entry.copy(steps = entry.steps.mapIndexed { i, step ->
                            if (i == entry.completedSteps) step.copy(payloadJson = payloadJson) else step
                        })
                    } else {
                        entry.copy(payloadJson = payloadJson)
                    }
                    fixed.cleared().copy(
                        idempotencyKey = if (entry.kind == OutboxKind.CREATE || entry.kind == OutboxKind.ACTION) {
                            UUID.randomUUID().toString()
                        } else entry.idempotencyKey
                    )
                }
            })
            true
        }
    }

    suspend fun useExisting(context: Context, entryId: String): Boolean = withContext(Dispatchers.IO) {
        var dropped: PendingEntry? = null
        val ok = mutex.withLock {
            val current = read(context)
            val entry = current.firstOrNull { it.id == entryId } ?: return@withLock false
            val target = entry.existingRecordId?.takeIf { it.isNotBlank() } ?: return@withLock false
            if (entry.media.isEmpty()) {
                write(context, current.filterNot { it.id == entryId })
                dropped = entry
            } else {
                write(context, current.map {
                    if (it.id != entryId) it
                    else it.cleared().copy(kind = OutboxKind.ATTACH, targetId = target, createdId = target, payloadJson = "")
                })
            }
            true
        }
        dropped?.media?.forEach { runCatching { File(it.localPath).delete() } }
        ok
    }

    suspend fun discard(context: Context, entryId: String): Boolean = withContext(Dispatchers.IO) {
        val entry = mutex.withLock {
            val current = read(context)
            val found = current.firstOrNull { it.id == entryId } ?: return@withLock null
            write(context, current.filterNot { it.id == entryId })
            found
        } ?: return@withContext false
        entry.media.forEach { runCatching { File(it.localPath).delete() } }
        true
    }

    private fun PendingEntry.cleared(): PendingEntry = copy(
        failure = null,
        failedAt = null,
        needsReview = false,
        failureFields = emptyList(),
        failureCode = null,
        existingRecordId = null,
        existingRecordLabel = null
    )

    suspend fun clearFailure(context: Context, entryId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = read(context)
            if (current.any { it.id == entryId && it.failure != null }) {
                write(context, current.map { entry ->
                    if (entry.id != entryId) entry
                    else entry.cleared().copy(
                        idempotencyKey = if (entry.needsReview) java.util.UUID.randomUUID().toString() else entry.idempotencyKey
                    )
                })
            }
        }
    }

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

    suspend fun failed(context: Context): List<PendingEntry> =
        withContext(Dispatchers.IO) { mutex.withLock { read(context).filter { it.failure != null } } }

    suspend fun remove(context: Context, entry: PendingEntry) = withContext(Dispatchers.IO) {
        mutex.withLock { write(context, read(context).filterNot { it.id == entry.id }) }
        entry.media.forEach { runCatching { File(it.localPath).delete() } }
    }

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
            processing = processing ?: TranscribeChoices.processingFor(uri.toString(), inferMediaType(mimeType)),
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

internal object OfflineQueueFile {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val written = MutableStateFlow(0L)

    val revision: StateFlow<Long> = written.asStateFlow()

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
            .map { entries -> entries.map { it.copy(payloadJson = AadhaarAtRest.openPayload(it.payloadJson)) } }
            .getOrElse { error ->
                quarantine(file, "could not be understood (${error.javaClass.simpleName})", onDamage)
                emptyList()
            }
    }

    fun write(file: File, entries: List<PendingEntry>) {
        val sealed = entries.map { it.copy(payloadJson = AadhaarAtRest.sealPayload(it.payloadJson)) }
        writeFileAtomically(file, json.encodeToString(sealed).toByteArray())
        written.update { it + 1 }
    }

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
