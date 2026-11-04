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

@Serializable
data class FormDraftFile(
    val formKind: String,
    val recordKey: String,
    val label: String = "",
    val savedAt: String,
    val fields: Map<String, JsonElement> = emptyMap(),
    val ownedFiles: List<String> = emptyList(),
    val parentId: String? = null,
    val parentLabel: String? = null
)

internal fun newDraftPrefix(parentId: String?): String =
    "new-" + (parentId?.let { "p_" + it.replace(Regex("[^A-Za-z0-9_-]"), "_") } ?: "none") + "-"

internal object DraftFiles {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun safe(part: String): String = part.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifBlank { "_" }

    fun accountDir(root: File, account: String): File = File(root, safe(account))

    fun file(root: File, account: String, kind: String, recordKey: String): File =
        File(accountDir(root, account), "${safe(kind)}__${safe(recordKey)}.json")

    fun write(root: File, account: String, draft: FormDraftFile) {
        val sealed = draft.copy(fields = AadhaarAtRest.sealDraft(draft.fields))
        writeFileAtomically(file(root, account, draft.formKind, draft.recordKey), json.encodeToString(FormDraftFile.serializer(), sealed).toByteArray())
    }

    fun read(file: File): FormDraftFile? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(FormDraftFile.serializer(), file.readText()) }
            .map { it.copy(fields = AadhaarAtRest.openDraft(it.fields)) }
            .getOrElse {
                runCatching { file.renameTo(File(file.parentFile, file.name + ".damaged-${System.currentTimeMillis()}")) }
                null
            }
    }

    fun read(root: File, account: String, kind: String, recordKey: String): FormDraftFile? =
        read(file(root, account, kind, recordKey))

    fun latestNew(root: File, account: String, kind: String, parentId: String? = null): FormDraftFile? =
        accountDir(root, account).listFiles { f ->
            f.name.startsWith("${safe(kind)}__${safe(newDraftPrefix(parentId))}") && f.name.endsWith(".json")
        }
            ?.mapNotNull { read(it) }
            ?.maxByOrNull { it.savedAt }

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

    object Unparsed
}

object DraftStore {
    private val mutex = Mutex()

    fun root(context: Context): File = File(context.filesDir, "drafts")

    fun mediaDir(context: Context): File = File(context.filesDir, "drafts-media").apply { mkdirs() }

    fun capturesDir(context: Context): File = File(context.filesDir, "field-captures").apply { mkdirs() }

    suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    suspend fun write(context: Context, account: String, draft: FormDraftFile) =
        locked { DraftFiles.write(root(context), account, draft) }

    suspend fun read(context: Context, account: String, kind: String, recordKey: String): FormDraftFile? =
        locked { DraftFiles.read(root(context), account, kind, recordKey) }

    suspend fun latestNew(context: Context, account: String, kind: String, parentId: String? = null): FormDraftFile? =
        locked { DraftFiles.latestNew(root(context), account, kind, parentId) }

    suspend fun all(context: Context, account: String): List<FormDraftFile> =
        locked { DraftFiles.all(root(context), account) }

    suspend fun delete(context: Context, account: String, kind: String, recordKey: String) = locked {
        DraftFiles.delete(root(context), account, kind, recordKey)?.let { removed ->
            removed.ownedFiles.forEach { runCatching { File(it).delete() } }
        }
    }

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
