package com.fieldrepository.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.fieldrepository.app.data.DraftStore
import com.fieldrepository.app.data.DraftValues
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.FormDraftFile
import com.fieldrepository.app.data.newDraftPrefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * A record form's draft: which of its fields are saved, and the decision about an earlier draft.
 *
 * A form binds each piece of state it wants kept ([bind] for a plain `MutableState`, [bindCustom]
 * for anything else), shows [DraftOfferCard] and runs [FormDraftAutosave]. Everything else (the
 * file, the debounce, copying files picked from other apps) happens here. See `data/DraftStore.kt`.
 */
class FormDraft internal constructor(
    val kind: String,
    /** The record being edited, or null for a new one. */
    val recordId: String?,
    internal val account: String,
    /** The prefilled record a new record's form was opened from, part of its key (see [newDraftPrefix]). */
    val parentId: String? = null,
    val parentLabel: String? = null
) {
    private class Binding(val save: () -> JsonElement?, val restore: (JsonElement) -> Unit)

    private val bindings = LinkedHashMap<String, Binding>()

    /** `rec-<id>` for an edit, `new-<uuid>` for a new record (replaced by the key of a draft taken up). */
    internal var recordKey: String = recordId?.let { "rec-$it" } ?: newKey()

    internal fun newKey(): String = newDraftPrefix(parentId) + UUID.randomUUID()

    /** An earlier draft waiting for "Continue your draft" or "Discard". Autosave waits for the answer. */
    var offer by mutableStateOf<FormDraftFile?>(null)
        internal set

    /** True once there is nothing to decide: no earlier draft, or the researcher has chosen. */
    var decided by mutableStateOf(false)
        internal set

    /** Bumped by [clear], so a write already queued for the old state is dropped. */
    @Volatile
    internal var generation = 0

    /** Files copied into app storage for this draft, keyed by the original uri. */
    internal val copies = LinkedHashMap<String, String>()

    /** Keep [state] in the draft under [key]. Safe to call on every recomposition. */
    fun <T> bind(key: String, state: MutableState<T>): MutableState<T> {
        bindings[key] = Binding(
            save = { DraftValues.encode(state.value) },
            restore = { element ->
                val decoded = DraftValues.decode(element)
                if (decoded !== DraftValues.Unparsed) {
                    @Suppress("UNCHECKED_CAST")
                    (state as MutableState<Any?>).value = decoded
                }
            }
        )
        return state
    }

    /** Keep a value with its own encoding (a media batch, a list of steps). */
    fun bindCustom(key: String, save: () -> JsonElement?, restore: (JsonElement) -> Unit) {
        bindings[key] = Binding(save, restore)
    }

    internal fun snapshot(): Map<String, JsonElement> =
        bindings.mapNotNull { (key, binding) -> runCatching { binding.save() }.getOrNull()?.let { key to it } }.toMap()

    internal fun restore(fields: Map<String, JsonElement>) {
        fields.forEach { (key, value) -> bindings[key]?.let { runCatching { it.restore(value) } } }
    }

    /** Hook set by [rememberFormDraft]: deletes the file. */
    internal var onClear: (() -> Unit)? = null

    /** The record is saved or queued: delete the draft. A form that stays open starts a fresh one. */
    fun clear() {
        onClear?.invoke()
    }

    companion object {
        /** A list of uris as a draft value. */
        fun encodeUris(uris: List<Uri>): JsonElement =
            JsonObject(mapOf("t" to JsonPrimitive("uris"), "v" to JsonArray(uris.map { JsonPrimitive(it.toString()) })))

        fun decodeUris(element: JsonElement): List<Uri>? = runCatching {
            val obj = element.jsonObject
            if (obj["t"]?.jsonPrimitive?.contentOrNull != "uris") return null
            obj["v"]!!.jsonArray.map { Uri.parse(it.jsonPrimitive.content) }
        }.getOrNull()

        /** A map of string keys to uri lists (the questionnaire's recordings) as a draft value. */
        fun encodeUriMap(map: Map<String, List<Uri>>): JsonElement =
            JsonObject(
                mapOf(
                    "t" to JsonPrimitive("urimap"),
                    "v" to JsonObject(map.mapValues { (_, uris) -> JsonArray(uris.map { JsonPrimitive(it.toString()) }) })
                )
            )

        fun decodeUriMap(element: JsonElement): Map<String, List<Uri>>? = runCatching {
            val obj = element.jsonObject
            if (obj["t"]?.jsonPrimitive?.contentOrNull != "urimap") return null
            obj["v"]!!.jsonObject.mapValues { (_, list) -> list.jsonArray.map { Uri.parse(it.jsonPrimitive.content) } }
        }.getOrNull()
    }
}

/** The account a draft belongs to; drafts made while signed out (none should be) share one folder. */
private fun draftAccount(repository: FieldRepository): String = repository.cachedUser()?.id ?: "signed-out"

/**
 * The draft for one open form. [recordId] is the record being edited, or null for a new one.
 * Looks for an earlier draft once, and offers it through [DraftOfferCard].
 */
@Composable
fun rememberFormDraft(
    repository: FieldRepository,
    kind: String,
    recordId: String?,
    parentId: String? = null,
    parentLabel: String? = null
): FormDraft {
    val context = LocalContext.current.applicationContext
    val account = remember(repository) { draftAccount(repository) }
    val scope = rememberCoroutineScope()
    val draft = remember(kind, recordId, account, parentId) { FormDraft(kind, recordId, account, parentId, parentLabel) }
    draft.onClear = {
        val key = draft.recordKey
        draft.generation++
        draft.offer = null
        draft.decided = true
        draft.copies.clear()
        if (draft.recordId == null) draft.recordKey = draft.newKey()
        // The app scope, not the form's: the form usually leaves the screen right after a save.
        com.fieldrepository.app.data.AppScope.io.launch {
            runCatching { DraftStore.delete(context, account, kind, key) }
        }
    }
    LaunchedEffect(draft) {
        val earlier = runCatching {
            if (recordId != null) DraftStore.read(context, account, kind, draft.recordKey)
            else DraftStore.latestNew(context, account, kind, parentId)
        }.getOrNull()
        draft.offer = earlier
        draft.decided = earlier == null
    }
    return draft
}

/**
 * Autosave: while the form has unsaved changes ([dirty], the same flag the back-navigation guard
 * reads) and any earlier draft has been answered, write the bound fields to disk at most once per
 * [debounceMillis]. A clean form writes nothing, so merely opening a form never leaves a draft.
 */
@Composable
fun FormDraftAutosave(draft: FormDraft, dirty: Boolean, label: String, debounceMillis: Long = 700) {
    val context = LocalContext.current.applicationContext
    val dirtyNow by rememberUpdatedState(dirty)
    val labelNow by rememberUpdatedState(label)
    LaunchedEffect(draft) {
        snapshotFlow { if (draft.decided && dirtyNow) draft.snapshot() else null }
            .collectLatest { snapshot ->
                if (snapshot == null) return@collectLatest
                delay(debounceMillis)
                val generation = draft.generation
                val key = draft.recordKey
                val fields = withContext(Dispatchers.IO) { keepFilesLocal(context, draft, snapshot) }
                val file = FormDraftFile(
                    formKind = draft.kind,
                    recordKey = key,
                    label = labelNow,
                    savedAt = Instant.now().toString(),
                    fields = fields,
                    ownedFiles = draft.copies.values.toList(),
                    parentId = draft.parentId,
                    parentLabel = draft.parentLabel
                )
                DraftStore.locked {
                    if (draft.generation == generation) {
                        com.fieldrepository.app.data.DraftFiles.write(DraftStore.root(context), draft.account, file)
                    }
                }
            }
    }
}

/**
 * A uri from another app (a picked file) may be unreadable after the process restarts, so the first
 * time a draft sees one it copies the bytes into app storage and saves the copy's uri instead.
 * Captures the app made itself are already in app storage and are kept as they are.
 */
private fun keepFilesLocal(context: Context, draft: FormDraft, fields: Map<String, JsonElement>): Map<String, JsonElement> {
    val authority = "${context.packageName}.fileprovider"
    fun local(uriString: String): String {
        val uri = Uri.parse(uriString)
        if (uri.authority == authority || uri.scheme == "file") return uriString
        draft.copies[uriString]?.let { path -> return FileProvider.getUriForFile(context, authority, File(path)).toString() }
        return runCatching {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
            val extension = name?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
            val target = File(DraftStore.mediaDir(context), UUID.randomUUID().toString() + (extension?.let { ".$it" } ?: ""))
            context.contentResolver.openInputStream(uri)!!.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
            }
            draft.copies[uriString] = target.absolutePath
            FileProvider.getUriForFile(context, authority, target).toString()
        }.getOrDefault(uriString)
    }
    fun walk(element: JsonElement): JsonElement {
        if (element is JsonArray) return JsonArray(element.map(::walk))
        val obj = element as? JsonObject ?: return element
        return when (obj["t"]?.jsonPrimitive?.contentOrNull) {
            "uris" -> JsonObject(obj + ("v" to JsonArray(obj["v"]!!.jsonArray.map { JsonPrimitive(local(it.jsonPrimitive.content)) })))
            "urimap" -> JsonObject(
                obj + ("v" to JsonObject(obj["v"]!!.jsonObject.mapValues { (_, list) ->
                    JsonArray(list.jsonArray.map { JsonPrimitive(local(it.jsonPrimitive.content)) })
                }))
            )
            null -> JsonObject(obj.mapValues { (_, value) -> walk(value) })
            else -> obj
        }
    }
    return fields.mapValues { (_, value) -> walk(value) }
}

private val OFFER_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/** "Continue your draft" or "Discard", shown at the top of a form that has an earlier draft. */
@Composable
fun DraftOfferCard(draft: FormDraft) {
    val offer = draft.offer ?: return
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val savedAt = runCatching { OFFER_TIME.format(Instant.parse(offer.savedAt).atZone(ZoneId.systemDefault())) }.getOrDefault("")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "You have an unsaved draft of this form" +
                (offer.parentLabel?.takeIf { it.isNotBlank() }?.let { " for $it" } ?: "") +
                (offer.label.takeIf { it.isNotBlank() }?.let { " (“$it”)" } ?: "") +
                (if (savedAt.isNotBlank()) ", last changed $savedAt." else "."),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontSize = 13.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                draft.recordKey = offer.recordKey
                draft.copies.clear()
                draft.restore(offer.fields)
                offer.ownedFiles.forEach { path -> draft.copies["file:$path"] = path }
                draft.offer = null
                draft.decided = true
            }) { Text("Continue your draft") }
            OutlinedButton(onClick = {
                draft.offer = null
                draft.decided = true
                scope.launch { runCatching { DraftStore.delete(context, draft.account, draft.kind, offer.recordKey) } }
            }) { Text("Discard") }
        }
    }
}
