package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.data.QAnswer
import com.fieldrepository.app.questionnaire.data.QAnswerItem
import com.fieldrepository.app.questionnaire.data.QAnswerResult
import com.fieldrepository.app.questionnaire.data.QAnswersPut
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QSessionDetail
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.SessionStep
import com.fieldrepository.app.questionnaire.data.SessionUnit
import com.fieldrepository.app.questionnaire.data.StepKind
import com.fieldrepository.app.questionnaire.data.StepState
import com.fieldrepository.app.questionnaire.data.SubjectRole
import com.fieldrepository.app.questionnaire.data.outboxJson

/**
 * The pure halves of the workspace (audit C2, H19, H20, 21 Oct 2026), so JVM tests pin them: the
 * outbox fix tray's rows, the answers a question shows and what a save sends, and what removing a
 * participant costs.
 */

/** One row of the fix tray: a refused step, or one conflicted answer of a refused answers step. */
data class TrayRow(
    val stepId: String,
    val kind: String,
    val title: String,
    /** Why it was not sent: the server's sentence, or the answer the server holds now (P9). */
    val reason: String,
    /** P9: the conflicted answer, with the server's current one. Null for every other row. */
    val conflict: QAnswerResult? = null,
    /** P9: what this phone typed. */
    val mine: String? = null,
    /** "Send again" is offered: the refusal may have been a moment's (a switched-off v2, a stale id). */
    val sendAgain: Boolean = true,
    /** Files Discard deletes from the phone (a take's audio). */
    val files: Int = 0
)

object OutboxTrayModel {
    /**
     * The tray's rows for [unit]: every FAILED step, in replay order. A refused answers step lists
     * each conflicted answer on its own with Keep theirs · Keep mine (P9); an answer the server will
     * not take from this researcher at all (FORBIDDEN) offers Discard only.
     */
    fun rows(unit: SessionUnit, nameOf: (String) -> String, codeOf: (String) -> String): List<TrayRow> =
        unit.ordered().filter { it.state == StepState.FAILED }.flatMap { step -> rowsOf(unit, step, nameOf, codeOf) }

    private fun rowsOf(unit: SessionUnit, step: SessionStep, nameOf: (String) -> String, codeOf: (String) -> String): List<TrayRow> {
        val reason = step.failure?.takeIf { it.isNotBlank() && it != "CONFLICT" } ?: QuestionnaireCopy.s("upload.waiting")
        return when (step.kind) {
            StepKind.CLIP -> listOf(TrayRow(step.id, step.kind, step.clip?.label.orEmpty(), reason, files = 1))
            StepKind.ANSWERS -> {
                val mine = step.bodyJson?.let { runCatching { outboxJson.decodeFromString(QAnswersPut.serializer(), it) }.getOrNull() }?.items.orEmpty()
                if (step.conflicts.isEmpty()) return listOf(TrayRow(step.id, step.kind, QuestionnaireCopy.s("tray.answers"), reason))
                step.conflicts.map { c ->
                    val typed = mine.firstOrNull { unit.resolve(it.artisanId) == unit.resolve(c.artisanId) && it.questionLineageId == c.questionLineageId }
                    val forbidden = c.status == "FORBIDDEN"
                    TrayRow(
                        stepId = step.id, kind = step.kind,
                        title = listOf(codeOf(c.questionLineageId), QuestionnaireCopy.firstName(nameOf(unit.resolve(c.artisanId)))).filter { it.isNotBlank() }.joinToString(" · "),
                        // P15 when the answer is someone else's (U9); the plain refusal otherwise.
                        reason = when {
                            c.locked -> QuestionnaireCopy.locked(c.current?.authorName)
                            forbidden -> QuestionnaireCopy.s("tray.notAllowed")
                            else -> c.current?.text.orEmpty()
                        },
                        conflict = c.takeUnless { forbidden }, mine = typed?.text, sendAgain = false
                    )
                }
            }
            StepKind.SESSION -> listOf(TrayRow(step.id, step.kind, QuestionnaireCopy.s("tray.session"), reason))
            StepKind.ARTISAN -> listOf(TrayRow(step.id, step.kind, QuestionnaireCopy.s("tray.newArtisan"), reason))
            StepKind.PARTICIPANT -> listOf(
                TrayRow(step.id, step.kind, listOfNotNull(QuestionnaireCopy.s("tray.participant"), step.participant?.artisanId?.let { QuestionnaireCopy.firstName(nameOf(unit.resolve(it))) }?.ifBlank { null }).joinToString(" · "), reason)
            )
            else -> listOf(TrayRow(step.id, step.kind, QuestionnaireCopy.s("tray.finish"), reason))
        }
    }

    /** The answers steps with at least one answer the server refused: "Send without the conflicting answers". */
    fun conflictSteps(rows: List<TrayRow>): List<String> =
        rows.filter { it.kind == StepKind.ANSWERS && (it.conflict != null || !it.sendAgain) }.map { it.stepId }.distinct()

    /** "Uploading 40%" from the clip's parts on the server; "Waiting for signal" before any. */
    fun uploadLine(percent: Int): String = if (percent <= 0) QuestionnaireCopy.s("upload.waiting") else QuestionnaireCopy.uploading(percent)
}

/**
 * What a question shows for one subject: the answer, where it came from, whether it is only on this
 * phone, its note (A20), and, when someone else wrote it, P15 (U9): it reads locked to this researcher.
 */
data class AnswerLine(
    val artisanId: String,
    val text: String,
    val badge: String?,
    val onPhone: Boolean,
    val note: String? = null,
    /** P15's line when the answer is locked to this researcher; null when they may change it. */
    val lockedLine: String? = null,
    /** The server's answer id, for History (B21). */
    val answerId: String? = null
)

object AnswerModel {
    /** [subjectId] is an artisan id or `R:<respondentId>`; an answer is matched on the same key. */
    fun current(answers: List<QAnswer>, subjectId: String, lineageId: String): QAnswer? =
        answers.firstOrNull { it.subjectId == subjectId && it.questionLineageId == lineageId }

    /**
     * The one answer-lock rule (U9; owner decision 2): an answer with text is changed only by its author,
     * a session lead or an admin. A PREFILL is nobody's account and never locks. The server decides in
     * the end (`ANSWER_LOCKED` per item); this only shows it before the researcher types.
     */
    fun locked(answer: QAnswer?, meId: String?, leadOrAdmin: Boolean): Boolean {
        if (answer == null || answer.text.isBlank() || answer.origin == "PREFILL" || leadOrAdmin) return false
        val author = answer.authorId ?: return false
        return meId == null || author != meId
    }

    /** (subject, lineage) pairs with a typed answer that has text: on the server, or queued here (D4). */
    fun typedPairs(answers: List<QAnswer>, unit: SessionUnit?): Set<Pair<String, String>> {
        val server = answers.filter { it.text.isNotBlank() && it.questionLineageId != null }.map { it.subjectId to it.questionLineageId!! }
        val queued = unit?.steps.orEmpty().filter { it.kind == StepKind.ANSWERS && it.state != StepState.DONE }.flatMap { step ->
            step.bodyJson?.let { runCatching { outboxJson.decodeFromString(QAnswersPut.serializer(), it) }.getOrNull() }?.items.orEmpty()
                .filter { it.text.isNotBlank() }.map { unit!!.resolve(it.artisanId) to it.questionLineageId }
        }
        return (server + queued).toSet()
    }

    /** The newest text this phone queued (sent or not yet sent) for the pair, when it has not landed. */
    fun queued(unit: SessionUnit?, artisanId: String, lineageId: String): String? {
        unit ?: return null
        return unit.steps.filter { it.kind == StepKind.ANSWERS && it.state != StepState.DONE }
            .sortedBy { it.deviceTime }
            .mapNotNull { step ->
                step.bodyJson?.let { runCatching { outboxJson.decodeFromString(QAnswersPut.serializer(), it) }.getOrNull() }
                    ?.items?.lastOrNull { unit.resolve(it.artisanId) == unit.resolve(artisanId) && it.questionLineageId == lineageId }?.text
            }.lastOrNull()
    }

    /** Each roster artisan's answer to [lineageId]: what is on this phone first, else the server's. */
    fun lines(
        roster: List<String>, lineageId: String, answers: List<QAnswer>, unit: SessionUnit?,
        meId: String? = null, leadOrAdmin: Boolean = false, rosterOf: (String?) -> Int?
    ): List<AnswerLine> =
        roster.mapNotNull { id ->
            queued(unit, id, lineageId)?.let { return@mapNotNull AnswerLine(id, it, QuestionnaireCopy.s("upload.onDevice"), onPhone = true) }
            val a = current(answers, id, lineageId)?.takeIf { it.text.isNotBlank() } ?: return@mapNotNull null
            val badge = when {
                a.attribution == "GROUP_SHARED" -> QuestionnaireCopy.groupAnswer(rosterOf(a.sourceRecordingId))
                a.sourceRecordingId != null -> QuestionnaireCopy.s("record.fromRecording")
                else -> null
            }
            AnswerLine(
                id, a.text, badge, onPhone = false, note = a.note?.ifBlank { null },
                lockedLine = if (locked(a, meId, leadOrAdmin)) QuestionnaireCopy.locked(a.authorName) else null,
                answerId = a.id.ifBlank { null }
            )
        }

    /**
     * The items one Save sends (audit H19): each carries the version of the answer it replaces, so the
     * server can tell an edit from a clash. "All N" is one group answer; one artisan is their own.
     */
    fun items(
        text: String, forIds: Collection<String>, lineageId: String, sessionId: String, answers: List<QAnswer>,
        origin: String = "TYPED", sourceRecordingId: String? = null, note: String? = null, richText: String? = null
    ): List<QAnswerItem> {
        val shared = forIds.size > 1
        return forIds.map { id ->
            QAnswerItem(
                artisanId = id, questionLineageId = lineageId, text = text, origin = origin,
                attribution = if (shared) "GROUP_SHARED" else "INDIVIDUAL", sessionId = sessionId,
                sourceRecordingId = sourceRecordingId, baseVersion = current(answers, id, lineageId)?.version,
                note = note?.trim()?.ifBlank { null },
                richText = richText?.ifBlank { null }
            )
        }
    }

    /**
     * B24: a question mapped to an artisan field is filled from each artisan's profile by the server
     * (origin PREFILL, one per artisan, never a group answer). A prefill only ever replaces an earlier
     * prefill, so a typed answer is left alone; a guest has no profile and is skipped.
     */
    fun prefillItems(forIds: Collection<String>, lineageId: String, sessionId: String, answers: List<QAnswer>): List<QAnswerItem> =
        forIds.filterNot { com.fieldrepository.app.questionnaire.data.QSubjects.isGuest(it) }
            .filter { id -> current(answers, id, lineageId)?.let { it.text.isBlank() || it.origin == "PREFILL" } ?: true }
            .map { id -> QAnswerItem(artisanId = id, questionLineageId = lineageId, text = "", origin = "PREFILL", attribution = "INDIVIDUAL", sessionId = sessionId) }

    /** P8: the answer being replaced came from a recording, so the researcher confirms first. */
    fun replacesRecordedAnswer(answers: List<QAnswer>, forIds: Collection<String>, lineageId: String): QAnswer? =
        forIds.firstNotNullOfOrNull { id -> current(answers, id, lineageId)?.takeIf { it.sourceRecordingId != null && it.text.isNotBlank() } }
}

/**
 * Whether "Replace the earlier take" removes it, so P7 asks first. Removing a take is for content
 * admins only on every path (owner, 20 Oct 2026): a researcher's replace keeps both takes and the
 * server marks the new one as the redo.
 */
fun replaceRemovesEarlier(isContentAdmin: Boolean): Boolean = isContentAdmin

object ParticipantModel {
    /** P10's count: the takes in this session on which [artisanId] is a subject. */
    fun takesOf(detail: QSessionDetail?, artisanId: String): Int =
        detail?.recordings.orEmpty().count { r -> r.removedAt == null && r.allSubjects.any { it.artisanId == artisanId } }

    /**
     * Who refused, for a take's "Change consent" (the web's M22 bug was asking the first subject):
     * a subject whose consent is refused, else the first who answered.
     */
    fun refusingSubject(recording: QRecording, detail: QSessionDetail?): String? {
        val refused = detail?.participants.orEmpty().filter { it.consent?.state == "REFUSED" }.map { it.artisanId }.toSet()
        return recording.subjects.firstOrNull { it.artisanId in refused }?.artisanId
            ?: recording.subjects.firstOrNull { it.role == SubjectRole.ANSWERED.wire }?.artisanId
            ?: recording.subjects.firstOrNull()?.artisanId
    }
}
