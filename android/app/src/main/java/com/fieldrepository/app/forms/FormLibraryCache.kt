package com.fieldrepository.app.forms

import com.fieldrepository.app.data.writeFileAtomically
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant

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

    fun clear() { file.delete() }
}
