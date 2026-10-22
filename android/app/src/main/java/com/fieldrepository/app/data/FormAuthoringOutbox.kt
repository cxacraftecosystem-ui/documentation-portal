package com.fieldrepository.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * OFFLINE FORM AUTHORING WITH TEMPORARY IDS (UNIFY A15, 4.3; DR audit M12).
 *
 * A form, its sections and its questions written with no signal get LOCAL ids (`local-form-…`,
 * `local-section-…`, `local-question-…`). Each create is one step with its body frozen and its
 * Idempotency-Key minted when it is queued; the steps run in the outbox's first three places
 * (`form` → `section` → `question`), before any session, and each landing writes local → server into
 * [FormQueue.ids]. A child names its parent by whichever id it had then; [FormQueue.resolve] turns a
 * landed local id into the server's, and a child whose parent has not landed waits for it.
 *
 * The session outbox asks [resolveFormId] before sending a session create on a form written here, so
 * a session started on a phone-only form lands on the right form once the form does.
 *
 * Reuse (A5) and a received bundle (A10, `.frwq`/`.dpwq`) are queued through the same steps, so both
 * work with no connection. A bundle is added once: its `bundleId` is remembered.
 */
object FormStepKind {
    const val FORM = "FORM"
    const val SECTION = "SECTION"
    const val QUESTION = "QUESTION"

    fun rank(kind: String): Int = when (kind) { FORM -> 0; SECTION -> 1; else -> 2 }
}

@Serializable
data class FormStep(
    val id: String,
    val kind: String,
    val localId: String,
    /** The form a section joins, or the section a question joins: local or server. */
    val parentId: String? = null,
    val idempotencyKey: String,
    val bodyJson: String,
    val createdAt: String,
    val state: String = StepState.PENDING,
    val failure: String? = null,
    val attempts: Int = 0
)

@Serializable
data class FormQueue(
    val ownerUserId: String? = null,
    val steps: List<FormStep> = emptyList(),
    /** Local id → server id, written as each create lands. */
    val ids: Map<String, String> = emptyMap(),
    /** Bundles already added on this phone (A10: import is idempotent on the bundle id). */
    val bundleIds: Set<String> = emptySet()
) {
    /** A server id stays itself; a landed local id becomes the server's; an unlanded one is null. */
    fun resolve(id: String): String? = ids[id] ?: id.takeUnless { isLocal(it) }

    fun ordered(): List<FormStep> = steps.sortedWith(compareBy<FormStep> { FormStepKind.rank(it.kind) }.thenBy { it.createdAt }.thenBy { it.id })

    val pending: List<FormStep> get() = steps.filter { it.state == StepState.PENDING }
    val failed: List<FormStep> get() = steps.filter { it.state == StepState.FAILED }

    companion object {
        fun isLocal(id: String): Boolean = id.startsWith("local-")
    }
}

/** The three creates the queue drives; [QuestionnaireFormsApi] implements them for real. */
interface FormTransport {
    suspend fun createForm(idempotencyKey: String, body: QFormCreate): String
    suspend fun createSection(idempotencyKey: String, body: QSectionCreate): String
    suspend fun createQuestion(idempotencyKey: String, body: QQuestionCreate): String
}

interface FormQueueStore {
    suspend fun read(): FormQueue
    suspend fun write(queue: FormQueue)
}

class FileFormQueueStore(private val file: File) : FormQueueStore {
    override suspend fun read(): FormQueue =
        file.takeIf { it.isFile }?.let { runCatching { outboxJson.decodeFromString(FormQueue.serializer(), it.readText()) }.getOrNull() } ?: FormQueue()

    override suspend fun write(queue: FormQueue) {
        file.parentFile?.mkdirs()
        writeFileAtomically(file, outboxJson.encodeToString(FormQueue.serializer(), queue).toByteArray())
    }
}

class InMemoryFormQueueStore(private var queue: FormQueue = FormQueue()) : FormQueueStore {
    override suspend fun read(): FormQueue = queue
    override suspend fun write(queue: FormQueue) { this.queue = queue }
}

class FormAuthoringOutbox(private val store: FormQueueStore, private val clock: () -> Instant = Instant::now) {
    private val lock = Mutex()

    private fun newKey() = UUID.randomUUID().toString()

    private suspend fun change(block: (FormQueue) -> FormQueue): FormQueue = lock.withLock { block(store.read()).also { store.write(it) } }

    suspend fun queue(): FormQueue = lock.withLock { store.read() }

    suspend fun resolveFormId(id: String): String? = queue().resolve(id)

    /** The owner whose phone queued these; another account's sync never sends them. */
    suspend fun claim(ownerUserId: String?) = change { q -> if (q.ownerUserId == null && q.steps.isEmpty()) q.copy(ownerUserId = ownerUserId) else q }

    suspend fun queueForm(body: QFormCreate, ownerUserId: String?): String {
        val localId = "local-form-" + newKey()
        change { q ->
            q.copy(
                ownerUserId = q.ownerUserId ?: ownerUserId,
                steps = q.steps + FormStep(newKey(), FormStepKind.FORM, localId, null, newKey(), outboxJson.encodeToString(QFormCreate.serializer(), body), clock().toString())
            )
        }
        return localId
    }

    /** [formId] is the form's local id or its server id. */
    suspend fun queueSection(formId: String, code: String, title: String, sortOrder: Int?): String {
        val localId = "local-section-" + newKey()
        val body = QSectionCreate(questionnaireId = formId, code = code.trim(), title = title.trim(), sortOrder = sortOrder)
        change { q -> q.copy(steps = q.steps + FormStep(newKey(), FormStepKind.SECTION, localId, formId, newKey(), outboxJson.encodeToString(QSectionCreate.serializer(), body), clock().toString())) }
        return localId
    }

    suspend fun queueQuestion(sectionId: String, prompt: String, helpText: String?, isRequired: Boolean, sortOrder: Int?, artisanField: String? = null): String {
        val localId = "local-question-" + newKey()
        val body = QQuestionCreate(sectionId = sectionId, prompt = prompt.trim(), helpText = helpText?.trim()?.ifBlank { null }, isRequired = isRequired, sortOrder = sortOrder, artisanField = artisanField)
        change { q -> q.copy(steps = q.steps + FormStep(newKey(), FormStepKind.QUESTION, localId, sectionId, newKey(), outboxJson.encodeToString(QQuestionCreate.serializer(), body), clock().toString())) }
        return localId
    }

    /**
     * A whole form from a bundle or a reuse: the form, then each section, then its questions, all
     * local. Returns the form's local id, or null when [bundleId] was added before.
     */
    suspend fun queueBundle(bundle: FormBundle, ownerUserId: String?, bundleId: String? = bundle.bundleId): String? {
        if (bundleId != null && bundleId in queue().bundleIds) return null
        val formId = queueForm(QFormCreate(title = bundle.form.title, description = bundle.form.description, kind = bundle.form.kind), ownerUserId)
        val sorted = bundle.sections.sortedBy { it.sortOrder }
        sorted.forEachIndexed { index, section ->
            val sectionId = queueSection(formId, section.code, section.title, index + 1)
            bundle.questions.filter { it.sectionCode == section.code }.sortedBy { it.sortOrder }.forEachIndexed { qi, question ->
                queueQuestion(sectionId, question.prompt, question.helpText, question.isRequired, qi + 1, question.artisanField)
            }
        }
        if (bundleId != null) change { q -> q.copy(bundleIds = q.bundleIds + bundleId) }
        return formId
    }

    /** "Send again": the failure clears, the key and body stay. */
    suspend fun retry(stepId: String) = change { q -> q.copy(steps = q.steps.map { if (it.id == stepId) it.copy(state = StepState.PENDING, failure = null, attempts = 0) else it }) }

    /** "Discard": the step, and everything that hangs from its local id. */
    suspend fun discard(stepId: String) = change { q ->
        val gone = mutableSetOf(q.steps.firstOrNull { it.id == stepId }?.localId ?: return@change q)
        var grew = true
        while (grew) {
            val more = q.steps.filter { it.parentId in gone && it.localId !in gone }.map { it.localId }
            grew = more.isNotEmpty(); gone += more
        }
        q.copy(steps = q.steps.filterNot { it.localId in gone })
    }

    /**
     * One pass, in order. A refusal fails that step (and its children wait in the tray with it); a
     * missing signal stops the pass so nothing is sent out of order.
     */
    suspend fun sync(transport: FormTransport, ownerUserId: String?): SessionOutbox.PassResult {
        var sent = 0
        val start = queue()
        if (start.ownerUserId != null && ownerUserId != null && start.ownerUserId != ownerUserId) return SessionOutbox.PassResult(0, 0, start.pending.size)
        for (step in start.ordered()) {
            val q = queue()
            val current = q.steps.firstOrNull { it.id == step.id } ?: continue
            if (current.state != StepState.PENDING) continue
            val parent = current.parentId?.let { q.resolve(it) }
            if (current.parentId != null && parent == null) continue // its parent has not landed
            val serverId = try {
                when (current.kind) {
                    FormStepKind.FORM -> transport.createForm(current.idempotencyKey, outboxJson.decodeFromString(QFormCreate.serializer(), current.bodyJson))
                    FormStepKind.SECTION -> transport.createSection(
                        current.idempotencyKey, outboxJson.decodeFromString(QSectionCreate.serializer(), current.bodyJson).copy(questionnaireId = parent!!)
                    )
                    else -> transport.createQuestion(
                        current.idempotencyKey, outboxJson.decodeFromString(QQuestionCreate.serializer(), current.bodyJson).copy(sectionId = parent!!)
                    )
                }
            } catch (refused: PermanentStepFailure) {
                change { x -> x.copy(steps = x.steps.map { if (it.id == current.id) it.copy(state = StepState.FAILED, failure = refused.message, attempts = it.attempts + 1) else it }) }
                continue
            } catch (transient: IOException) {
                change { x -> x.copy(steps = x.steps.map { if (it.id == current.id) it.copy(attempts = it.attempts + 1) else it }) }
                break
            }
            change { x -> x.copy(ids = x.ids + (current.localId to serverId), steps = x.steps.map { if (it.id == current.id) it.copy(state = StepState.DONE) else it }) }
            sent++
        }
        val end = queue()
        // Landed steps go; the id table stays, so a session queued on a local form still finds its form.
        change { x -> x.copy(steps = x.steps.filterNot { it.state == StepState.DONE }) }
        return SessionOutbox.PassResult(sent, end.failed.size, end.pending.size)
    }

    /** Forms written on this phone that have not landed, drawn in the library as "On this phone". */
    suspend fun localForms(): List<QForm> {
        val q = queue()
        return q.steps.filter { it.kind == FormStepKind.FORM && q.ids[it.localId] == null }.map { step ->
            val body = outboxJson.decodeFromString(QFormCreate.serializer(), step.bodyJson)
            val sections = localSections(q, step.localId)
            QForm(
                id = step.localId, title = body.title, description = body.description, kind = body.kind,
                ownerId = q.ownerUserId, sectionCount = sections.size, questionCount = sections.sumOf { it.questions.size },
                sections = sections, createdAt = step.createdAt
            )
        }
    }

    /** Sections and questions queued under [formId] (local or server), to show beside the server's. */
    suspend fun pendingSectionsOf(formId: String): List<QFormSection> = localSections(queue(), formId)

    private fun localSections(q: FormQueue, formId: String): List<QFormSection> =
        q.steps.filter { it.kind == FormStepKind.SECTION && (it.parentId == formId || q.resolve(it.parentId.orEmpty()) == formId) && q.ids[it.localId] == null }
            .map { s ->
                val body = outboxJson.decodeFromString(QSectionCreate.serializer(), s.bodyJson)
                QFormSection(
                    id = s.localId, code = body.code, title = body.title, sortOrder = body.sortOrder ?: 0,
                    questions = q.steps.filter { it.kind == FormStepKind.QUESTION && it.parentId == s.localId }.map { qs ->
                        val qb = outboxJson.decodeFromString(QQuestionCreate.serializer(), qs.bodyJson)
                        QFormQuestion(id = qs.localId, sectionId = s.localId, sectionCode = body.code, prompt = qb.prompt, helpText = qb.helpText, isRequired = qb.isRequired, sortOrder = qb.sortOrder ?: 0)
                    }
                )
            }
}

/** The real transport over [QuestionnaireFormsApi], with the session outbox's refusal rule. */
class ApiFormTransport(private val api: QuestionnaireFormsApi) : FormTransport {
    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (http: retrofit2.HttpException) {
        val code = http.code()
        if (code in 400..499 && code != 408 && code != 429) throw PermanentStepFailure(apiDetail(http) ?: "The server refused this ($code).")
        throw IOException("HTTP $code", http)
    }

    override suspend fun createForm(idempotencyKey: String, body: QFormCreate): String = try {
        call { api.createForm(idempotencyKey, body).id }
    } catch (refused: PermanentStepFailure) {
        // A server that does not know form kinds yet refuses the field (422): the form still lands.
        if (body.kind == null) throw refused
        call { api.createForm("$idempotencyKey-k", body.copy(kind = null)).id }
    }
    override suspend fun createSection(idempotencyKey: String, body: QSectionCreate): String = call { api.createSection(idempotencyKey, body).id }
    override suspend fun createQuestion(idempotencyKey: String, body: QQuestionCreate): String = call { api.createQuestion(idempotencyKey, body).id }
}

/** The server's sentence for a 4xx: `{detail: "…"}` or `{detail: {message}}`. */
fun apiDetail(http: retrofit2.HttpException): String? = runCatching {
    val body = http.response()?.errorBody()?.string() ?: return null
    val detail = ApiClient.json.parseToJsonElement(body).let { (it as? kotlinx.serialization.json.JsonObject)?.get("detail") }
    when (detail) {
        is kotlinx.serialization.json.JsonPrimitive -> detail.content
        is kotlinx.serialization.json.JsonObject -> (detail["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        else -> null
    }
}.getOrNull()?.ifBlank { null }
