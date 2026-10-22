package com.fieldrepository.app.forms

import com.fieldrepository.app.data.writeFileAtomically
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

/**
 * The forms library and the forms opened from it, kept for reading offline (UNIFY A16, 4.3), and
 * STAMPED WITH THE ACCOUNT that fetched them (DR audit M15): a phone handed to another researcher
 * never shows them the first one's forms. A different account, or no account, reads nothing.
 */
@Serializable
data class FormLibrarySnapshot(
    val ownerUserId: String,
    val savedAt: String,
    val forms: List<QForm> = emptyList(),
    val details: Map<String, QForm> = emptyMap()
)

class FormLibraryCache(private val file: File, private val clock: () -> Instant = Instant::now) {
    private fun load(): FormLibrarySnapshot? =
        file.takeIf { it.isFile }?.let { runCatching { formsJson.decodeFromString(FormLibrarySnapshot.serializer(), it.readText()) }.getOrNull() }

    private fun save(snapshot: FormLibrarySnapshot) {
        file.parentFile?.mkdirs()
        writeFileAtomically(file, formsJson.encodeToString(FormLibrarySnapshot.serializer(), snapshot).toByteArray())
    }

    /** The cached snapshot, only for [ownerUserId]. */
    fun read(ownerUserId: String?): FormLibrarySnapshot? = load()?.takeIf { ownerUserId != null && it.ownerUserId == ownerUserId }

    fun putList(ownerUserId: String, forms: List<QForm>) {
        val prior = read(ownerUserId)
        save(FormLibrarySnapshot(ownerUserId, clock().toString(), forms, prior?.details.orEmpty().filterKeys { id -> forms.any { it.id == id } }))
    }

    fun putDetail(ownerUserId: String, form: QForm) {
        val prior = read(ownerUserId) ?: FormLibrarySnapshot(ownerUserId, clock().toString())
        save(prior.copy(savedAt = clock().toString(), details = prior.details + (form.id to form)))
    }

    fun detail(ownerUserId: String?, formId: String): QForm? = read(ownerUserId)?.details?.get(formId)

    /** Sign-out and an account switch forget it. */
    fun clear() { file.delete() }
}
