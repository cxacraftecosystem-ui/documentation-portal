package com.fieldrepository.app.forms

import com.fieldrepository.app.data.Naming
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * THE PHONE-TO-PHONE FORM HANDOFF (UNIFY A10; `shared/questionnaire-bundle.json`, version 2).
 *
 * Gzipped UTF-8 JSON built and read with no connection. Field Repository writes `.frwq` and reads
 * both `.frwq` and designer-portal's `.dpwq` (v2, and DR Android's first format, v1). A received file
 * is untrusted: the format has no id, owner or author, so importing always CREATES (through
 * [FormAuthoringOutbox.queueBundle]) and whoever adds it is its author. Sessions and answers are
 * reserved for a later version and never read.
 */
@Serializable
data class FormBundleForm(val title: String = "", val description: String? = null, val kind: String? = null)

@Serializable
data class FormBundleSection(val code: String = "", val title: String = "", val sortOrder: Int = 0)

@Serializable
data class FormBundleQuestion(
    val sectionCode: String = "",
    val prompt: String = "",
    val helpText: String? = null,
    val isRequired: Boolean = false,
    val artisanField: String? = null,
    val questionCode: String? = null,
    val sortOrder: Int = 0
)

@Serializable
data class FormBundle(
    val v: Int = FormBundles.VERSION,
    val bundleId: String = "",
    val createdAt: String = "",
    val portal: String = FormBundles.PORTAL,
    val form: FormBundleForm = FormBundleForm(),
    val sections: List<FormBundleSection> = emptyList(),
    val questions: List<FormBundleQuestion> = emptyList()
)

sealed interface BundleRead {
    data class Ok(val bundle: FormBundle) : BundleRead
    data class Refused(val code: String, val message: String) : BundleRead
}

object FormBundles {
    const val VERSION = 2
    const val PORTAL = "documentation-portal"
    const val EXTENSION = "frwq"
    val READS = setOf("frwq", "dpwq")
    const val MIME = "application/vnd.fieldrepository.questionnaire+gzip"
    const val MAX_SECTIONS = 200
    const val MAX_QUESTIONS_PER_SECTION = 500
    const val MAX_TEXT = 4000
    const val MAX_BYTES = 5 * 1024 * 1024
    private const val V1_FORMAT = "designprototype.questionnaire"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun newBundleId(random: SecureRandom = SecureRandom()): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** A form's live sections and questions, as a bundle. No ids, no owner, no answers. */
    fun of(form: QForm, bundleId: String = newBundleId(), createdAt: String = OffsetDateTime.now(Naming.IST).toString()): FormBundle {
        val sections = form.sections.filter { it.isActive }.sortedBy { it.sortOrder }
        val codes = LinkedHashSet<String>()
        val outSections = sections.mapIndexed { i, s ->
            var code = s.code.trim().ifBlank { "S${i + 1}" }
            while (!codes.add(code)) code += "x"
            FormBundleSection(code, s.title.trim().take(MAX_TEXT), i + 1) to s
        }
        return FormBundle(
            bundleId = bundleId, createdAt = createdAt,
            form = FormBundleForm(form.title.trim().take(MAX_TEXT), form.description?.trim()?.ifBlank { null }?.take(MAX_TEXT), form.kind),
            sections = outSections.map { it.first },
            questions = outSections.flatMap { (bs, s) ->
                s.questions.filter { it.isActive && it.supersededById == null && it.retiredAt == null && it.prompt.isNotBlank() }
                    .sortedBy { it.sortOrder }
                    .mapIndexed { qi, q ->
                        FormBundleQuestion(bs.code, q.prompt.trim().take(MAX_TEXT), q.helpText?.trim()?.ifBlank { null }?.take(MAX_TEXT), q.isRequired, q.artisanField, q.questionCode, qi + 1)
                    }
            }
        )
    }

    fun encode(bundle: FormBundle): ByteArray {
        val raw = json.encodeToString(FormBundle.serializer(), bundle).toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(raw.size / 3 + 64)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    /** `FieldRepository_Questionnaire_2026-10-22_2nd-Craft-Toolkit_Handoff.frwq` (the naming helper). */
    fun fileName(title: String, day: String? = null): String =
        Naming.exportFilename(entity = "Questionnaire", what = "Handoff", ext = EXTENSION, subject = title, day = day, portal = "FieldRepository")

    fun readable(name: String?): Boolean = name?.substringAfterLast('.', "")?.lowercase() in READS

    /** Reads a received file: v2 from either portal, or DR Android's v1. Refuses, never half-reads. */
    fun read(bytes: ByteArray?): BundleRead {
        if (bytes == null || bytes.isEmpty()) return BundleRead.Refused("EMPTY", "That file is empty. Ask for it again.")
        val text = try {
            GZIPInputStream(bytes.inputStream()).use { input ->
                val buffer = ByteArray(64 * 1024)
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    if (out.size() + n > MAX_BYTES) return BundleRead.Refused("TOO_LARGE", "That file is too big to be a form.")
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
        } catch (e: Exception) {
            return BundleRead.Refused("DAMAGED", "That file is damaged or incomplete. Ask for it again.")
        }
        val root = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: return BundleRead.Refused("NOT_A_FORM", "Not a form file. Ask for the .frwq or .dpwq file.")
        val bundle = when {
            root["v"] != null -> {
                val v = root["v"]?.jsonPrimitive?.intOrNull ?: return BundleRead.Refused("NOT_A_FORM", "Not a form file. Ask for the .frwq or .dpwq file.")
                if (v > VERSION) return BundleRead.Refused("BUNDLE_VERSION", "Update the app to open this form.")
                runCatching { json.decodeFromJsonElement(FormBundle.serializer(), root) }.getOrNull()
                    ?: return BundleRead.Refused("NOT_A_FORM", "Not a form file. Ask for the .frwq or .dpwq file.")
            }
            root["format"]?.jsonPrimitive?.content == V1_FORMAT -> {
                if ((root["schemaVersion"]?.jsonPrimitive?.int ?: 1) > 1) return BundleRead.Refused("BUNDLE_VERSION", "Update the app to open this form.")
                fromV1(root) ?: return BundleRead.Refused("NOT_A_FORM", "Not a form file. Ask for the .frwq or .dpwq file.")
            }
            else -> return BundleRead.Refused("NOT_A_FORM", "Not a form file. Ask for the .frwq or .dpwq file.")
        }
        return check(bundle)
    }

    private fun check(bundle: FormBundle): BundleRead {
        if (bundle.form.title.isBlank()) return BundleRead.Refused("NOT_A_FORM", "This form has no title.")
        if (bundle.sections.size > MAX_SECTIONS) return BundleRead.Refused("TOO_LARGE", "That file is too big to be a form.")
        val codes = bundle.sections.map { it.code }
        if (codes.toSet().size != codes.size) return BundleRead.Refused("NOT_A_FORM", "Two sections share a code in this file.")
        if (bundle.questions.any { it.sectionCode !in codes }) return BundleRead.Refused("NOT_A_FORM", "A question in this file has no section.")
        if (bundle.questions.groupBy { it.sectionCode }.values.any { it.size > MAX_QUESTIONS_PER_SECTION }) return BundleRead.Refused("TOO_LARGE", "That file is too big to be a form.")
        if (bundle.questions.none { it.prompt.isNotBlank() }) return BundleRead.Refused("NOTHING_IN_IT", "This form has no questions. Check it's the right file.")
        fun clip(t: String) = t.take(MAX_TEXT)
        return BundleRead.Ok(
            bundle.copy(
                form = bundle.form.copy(title = clip(bundle.form.title.trim()), description = bundle.form.description?.let(::clip), kind = bundle.form.kind?.takeIf { it in FormKinds.LABELS }),
                sections = bundle.sections.map { it.copy(title = clip(it.title)) },
                questions = bundle.questions.filter { it.prompt.isNotBlank() }.map { it.copy(prompt = clip(it.prompt), helpText = it.helpText?.let(::clip)) }
            )
        )
    }

    /** DR Android's first format: `{format, schemaVersion, title, description, sections:[{code,title,sortOrder,questions:[…]}]}`. */
    private fun fromV1(root: JsonObject): FormBundle? = runCatching {
        val v1 = json.decodeFromJsonElement(V1.serializer(), root)
        val sections = v1.sections.sortedBy { it.sortOrder }.mapIndexed { i, s -> FormBundleSection(s.code.ifBlank { "S${i + 1}" }, s.title, i + 1) }
        FormBundle(
            v = 1, bundleId = "v1-" + Naming.field(v1.title) + "-" + root.toString().hashCode().toUInt().toString(16), portal = "designer-portal",
            form = FormBundleForm(v1.title, v1.description, null),
            sections = sections,
            questions = v1.sections.sortedBy { it.sortOrder }.zip(sections).flatMap { (s, bs) ->
                s.questions.sortedBy { it.sortOrder }.mapIndexed { qi, q -> FormBundleQuestion(bs.code, q.prompt, q.helpText, q.isRequired, sortOrder = qi + 1) }
            }
        )
    }.getOrNull()

    @Serializable
    private data class V1Question(val prompt: String = "", val helpText: String? = null, val isRequired: Boolean = false, val sortOrder: Int = 0)

    @Serializable
    private data class V1Section(val code: String = "", val title: String = "", val sortOrder: Int = 0, val questions: List<V1Question> = emptyList())

    @Serializable
    private data class V1(val format: String = "", val schemaVersion: Int = 1, val title: String = "", val description: String? = null, val sections: List<V1Section> = emptyList())
}
