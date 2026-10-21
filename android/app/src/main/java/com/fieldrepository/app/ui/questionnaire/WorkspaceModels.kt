package com.fieldrepository.app.ui.questionnaire

import com.fieldrepository.app.data.QAnswer
import com.fieldrepository.app.data.QAnswerItem
import com.fieldrepository.app.data.QAnswerResult
import com.fieldrepository.app.data.QAnswersPut
import com.fieldrepository.app.data.QRecording
import com.fieldrepository.app.data.QSessionDetail
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.data.SessionStep
import com.fieldrepository.app.data.SessionUnit
import com.fieldrepository.app.data.StepKind
import com.fieldrepository.app.data.StepState
import com.fieldrepository.app.data.SubjectRole
import com.fieldrepository.app.data.outboxJson

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
                        reason = if (forbidden) QuestionnaireCopy.s("tray.notAllowed") else c.current?.text.orEmpty(),
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

    /** "Uploading 40%" from the clip's parts on the server; "Waiting for signal" before any. */
    fun uploadLine(percent: Int): String = if (percent <= 0) QuestionnaireCopy.s("upload.waiting") else QuestionnaireCopy.uploading(percent)
}

/** What a question shows for one artisan: the answer, where it came from, and whether it is only on this phone. */
data class AnswerLine(val artisanId: String, val text: String, val badge: String?, val onPhone: Boolean)

object AnswerModel {
    fun current(answers: List<QAnswer>, artisanId: String, lineageId: String): QAnswer? =
        answers.firstOrNull { it.artisanId == artisanId && it.questionLineageId == lineageId }

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
    fun lines(roster: List<String>, lineageId: String, answers: List<QAnswer>, unit: SessionUnit?, rosterOf: (String?) -> Int?): List<AnswerLine> =
        roster.mapNotNull { id ->
            queued(unit, id, lineageId)?.let { return@mapNotNull AnswerLine(id, it, QuestionnaireCopy.s("upload.onDevice"), onPhone = true) }
            val a = current(answers, id, lineageId)?.takeIf { it.text.isNotBlank() } ?: return@mapNotNull null
            val badge = when {
                a.attribution == "GROUP_SHARED" -> QuestionnaireCopy.groupAnswer(rosterOf(a.sourceRecordingId))
                a.sourceRecordingId != null -> QuestionnaireCopy.s("record.fromRecording")
                else -> null
            }
            AnswerLine(id, a.text, badge, onPhone = false)
        }

    /**
     * The items one Save sends (audit H19): each carries the version of the answer it replaces, so the
     * server can tell an edit from a clash. "All N" is one group answer; one artisan is their own.
     */
    fun items(
        text: String, forIds: Collection<String>, lineageId: String, sessionId: String, answers: List<QAnswer>,
        origin: String = "TYPED", sourceRecordingId: String? = null
    ): List<QAnswerItem> {
        val shared = forIds.size > 1
        return forIds.map { id ->
            QAnswerItem(
                artisanId = id, questionLineageId = lineageId, text = text, origin = origin,
                attribution = if (shared) "GROUP_SHARED" else "INDIVIDUAL", sessionId = sessionId,
                sourceRecordingId = sourceRecordingId, baseVersion = current(answers, id, lineageId)?.version
            )
        }
    }

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
        detail?.recordings.orEmpty().count { r -> r.removedAt == null && r.subjects.any { it.artisanId == artisanId } }

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
