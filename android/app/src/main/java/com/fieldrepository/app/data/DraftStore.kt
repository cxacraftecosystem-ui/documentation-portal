package com.fieldrepository.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.LocalDate

/**
 * An unsaved form, on disk: what the researcher has typed and captured before tapping Save.
 *
 * The outbox protects a record from the moment it is saved. This protects it before that: a form
 * held only in Compose memory is lost when the process dies (the camera app pushing it out of
 * memory, a flat battery, the app swiped away). Each record form autosaves here, debounced, and on
 * the next open offers "Continue your draft" or "Discard".
 *
 * Keyed by (account, form, record): an edit is keyed by the record's id, a new record by a
 * `new-<uuid>` minted when the form opens. Drafts are per account and are kept across sign-out, like
 * the outbox, so a researcher who signs out and back in finds their own drafts and nobody else's.
 */
@Serializable
data class FormDraftFile(
    val formKind: String,
    val recordKey: String,
    val label: String = "",
    /** ISO-8601. */
    val savedAt: String,
    /** One tagged value per bound field (see [DraftValues]). */
    val fields: Map<String, JsonElement> = emptyMap(),
    /** Files this draft copied into app storage, deleted with it. */
    val ownedFiles: List<String> = emptyList()
)

/** The draft files themselves, against a root directory, so a JVM test can drive them. */
internal object DraftFiles {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun safe(part: String): String = part.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifBlank { "_" }

    fun accountDir(root: File, account: String): File = File(root, safe(account))

    fun file(root: File, account: String, kind: String, recordKey: String): File =
        File(accountDir(root, account), "${safe(kind)}__${safe(recordKey)}.json")

    fun write(root: File, account: String, draft: FormDraftFile) {
        writeFileAtomically(file(root, account, draft.formKind, draft.recordKey), json.encodeToString(FormDraftFile.serializer(), draft).toByteArray())
    }

    /** A draft that will not parse is set aside (never deleted) and reads as absent. */
    fun read(file: File): FormDraftFile? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(FormDraftFile.serializer(), file.readText()) }.getOrElse {
            runCatching { file.renameTo(File(file.parentFile, file.name + ".damaged-${System.currentTimeMillis()}")) }
            null
        }
    }

    fun read(root: File, account: String, kind: String, recordKey: String): FormDraftFile? =
        read(file(root, account, kind, recordKey))

    /** The most recently saved draft of a NEW record of [kind] for [account], if any. */
    fun latestNew(root: File, account: String, kind: String): FormDraftFile? =
        accountDir(root, account).listFiles { f -> f.name.startsWith("${safe(kind)}__new-") && f.name.endsWith(".json") }
            ?.mapNotNull { read(it) }
            ?.maxByOrNull { it.savedAt }

    /** Every draft of [account], newest first. */
    fun all(root: File, account: String): List<FormDraftFile> =
        accountDir(root, account).listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.savedAt }
            .orEmpty()

    fun delete(root: File, account: String, kind: String, recordKey: String): FormDraftFile? {
        val target = file(root, account, kind, recordKey)
        val existing = read(target)
        target.delete()
        return existing
    }

    /** Every string any draft of any account holds, for the sweep of unreferenced capture files. */
    fun referencedStrings(root: File): Set<String> {
        val out = HashSet<String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonPrimitive -> element.contentOrNull?.let { out.add(it) }
                is JsonArray -> element.forEach(::walk)
                is JsonObject -> element.values.forEach(::walk)
                else -> Unit
            }
        }
        root.listFiles()?.forEach { dir ->
            dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { file ->
                read(file)?.let { draft ->
                    draft.fields.values.forEach(::walk)
                    out.addAll(draft.ownedFiles)
                }
            }
        }
        return out
    }
}

/**
 * The tagged encoding of one form field. Tags keep the type, so a field whose value is null today
 * restores to the right kind of value tomorrow. A type with no tag is not saved ([encode] answers
 * null): server-loaded lists and UI flags have no business in a draft.
 */
internal object DraftValues {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun tagged(tag: String, value: JsonElement) = JsonObject(mapOf("t" to JsonPrimitive(tag), "v" to value))

    fun encode(value: Any?): JsonElement? = when (value) {
        null -> tagged("n", JsonNull)
        is String -> tagged("s", JsonPrimitive(value))
        is Boolean -> tagged("b", JsonPrimitive(value))
        is Int -> tagged("i", JsonPrimitive(value))
        is Long -> tagged("l", JsonPrimitive(value))
        is Double -> tagged("d", JsonPrimitive(value))
        is LocalDate -> tagged("date", JsonPrimitive(value.toString()))
        is LocationRequest -> tagged("loc", json.encodeToJsonElement(LocationRequest.serializer(), value))
        is Set<*> -> if (value.all { it is String }) tagged("ss", JsonArray(value.map { JsonPrimitive(it as String) })) else null
        is List<*> -> if (value.all { it is String }) tagged("ls", JsonArray(value.map { JsonPrimitive(it as String) })) else null
        is Map<*, *> -> encodeMap(value)
        else -> null
    }

    private fun encodeMap(value: Map<*, *>): JsonElement? {
        if (!value.keys.all { it is String }) return null
        return when {
            value.values.all { it is String } ->
                tagged("ms", JsonObject(value.entries.associate { (k, v) -> k as String to JsonPrimitive(v as String) }))
            value.values.all { v -> v is List<*> && v.all { it is String } } ->
                tagged("mls", JsonObject(value.entries.associate { (k, v) -> k as String to JsonArray((v as List<*>).map { JsonPrimitive(it as String) }) }))
            else -> null
        }
    }

    /** The value [element] encodes; [Unparsed] when it is not a draft value this build understands. */
    fun decode(element: JsonElement): Any? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return Unparsed
        val tag = obj["t"]?.jsonPrimitive?.contentOrNull ?: return Unparsed
        val v = obj["v"] ?: return Unparsed
        return runCatching {
            when (tag) {
                "n" -> null
                "s" -> v.jsonPrimitive.content
                "b" -> v.jsonPrimitive.booleanOrNull ?: Unparsed
                "i" -> v.jsonPrimitive.intOrNull ?: Unparsed
                "l" -> v.jsonPrimitive.longOrNull ?: Unparsed
                "d" -> v.jsonPrimitive.doubleOrNull ?: Unparsed
                "date" -> LocalDate.parse(v.jsonPrimitive.content)
                "loc" -> json.decodeFromJsonElement(LocationRequest.serializer(), v)
                "ss" -> LinkedHashSet(v.jsonArray.map { it.jsonPrimitive.content })
                "ls" -> v.jsonArray.map { it.jsonPrimitive.content }
                "ms" -> v.jsonObject.mapValues { it.value.jsonPrimitive.content }
                "mls" -> v.jsonObject.mapValues { e -> e.value.jsonArray.map { it.jsonPrimitive.content } }
                else -> Unparsed
            }
        }.getOrDefault(Unparsed)
    }

    /** The marker for "leave the field as it is". */
    object Unparsed
}

/** Context-bound access to [DraftFiles], serialised so a write never lands after its own delete. */
object DraftStore {
    private val mutex = Mutex()

    fun root(context: Context): File = File(context.filesDir, "drafts")

    /** Where a draft keeps copies of files it does not own (picked from another app). */
    fun mediaDir(context: Context): File = File(context.filesDir, "drafts-media").apply { mkdirs() }

    /** Where the camera writes captures: app storage that survives process death, unlike cacheDir. */
    fun capturesDir(context: Context): File = File(context.filesDir, "field-captures").apply { mkdirs() }

    suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    suspend fun write(context: Context, account: String, draft: FormDraftFile) =
        locked { DraftFiles.write(root(context), account, draft) }

    suspend fun read(context: Context, account: String, kind: String, recordKey: String): FormDraftFile? =
        locked { DraftFiles.read(root(context), account, kind, recordKey) }

    suspend fun latestNew(context: Context, account: String, kind: String): FormDraftFile? =
        locked { DraftFiles.latestNew(root(context), account, kind) }

    suspend fun all(context: Context, account: String): List<FormDraftFile> =
        locked { DraftFiles.all(root(context), account) }

    /** Delete a draft and the file copies it owned. */
    suspend fun delete(context: Context, account: String, kind: String, recordKey: String) = locked {
        DraftFiles.delete(root(context), account, kind, recordKey)?.let { removed ->
            removed.ownedFiles.forEach { runCatching { File(it).delete() } }
        }
    }

    /**
     * Delete capture files and draft copies older than [olderThanMillis] that no draft refers to.
     * Run at app start, before any form is open. Files the outbox needs are copies under
     * `outbox/media` and are never touched here.
     */
    suspend fun sweep(context: Context, olderThanMillis: Long = 3L * 24 * 60 * 60 * 1000) = locked {
        val referenced = DraftFiles.referencedStrings(root(context))
        val cutoff = System.currentTimeMillis() - olderThanMillis
        listOf(capturesDir(context), mediaDir(context)).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                val inUse = referenced.any { it == file.absolutePath || it.endsWith("/" + file.name) }
                if (!inUse && file.lastModified() < cutoff) runCatching { file.delete() }
            }
        }
    }
}
