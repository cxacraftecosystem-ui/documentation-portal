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

data class TrayRow(
    val stepId: String,
    val kind: String,
    val title: String,
    val reason: String,
    val conflict: QAnswerResult? = null,
    val mine: String? = null,
    val sendAgain: Boolean = true,
    val files: Int = 0
)

object OutboxTrayModel {
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

    fun conflictSteps(rows: List<TrayRow>): List<String> =
        rows.filter { it.kind == StepKind.ANSWERS && (it.conflict != null || !it.sendAgain) }.map { it.stepId }.distinct()

    fun uploadLine(percent: Int): String = if (percent <= 0) QuestionnaireCopy.s("upload.waiting") else QuestionnaireCopy.uploading(percent)
}

data class AnswerLine(
    val artisanId: String,
    val text: String,
    val badge: String?,
    val onPhone: Boolean,
    val note: String? = null,
    val lockedLine: String? = null,
    val answerId: String? = null
)

object AnswerModel {
    fun current(answers: List<QAnswer>, subjectId: String, lineageId: String): QAnswer? =
        answers.firstOrNull { it.subjectId == subjectId && it.questionLineageId == lineageId }

    fun locked(answer: QAnswer?, meId: String?, leadOrAdmin: Boolean): Boolean {
        if (answer == null || answer.text.isBlank() || answer.origin == "PREFILL" || leadOrAdmin) return false
        val author = answer.authorId ?: return false
        return meId == null || author != meId
    }

    fun typedPairs(answers: List<QAnswer>, unit: SessionUnit?): Set<Pair<String, String>> {
        val server = answers.filter { it.text.isNotBlank() && it.questionLineageId != null }.map { it.subjectId to it.questionLineageId!! }
        val queued = unit?.steps.orEmpty().filter { it.kind == StepKind.ANSWERS && it.state != StepState.DONE }.flatMap { step ->
            step.bodyJson?.let { runCatching { outboxJson.decodeFromString(QAnswersPut.serializer(), it) }.getOrNull() }?.items.orEmpty()
                .filter { it.text.isNotBlank() }.map { unit!!.resolve(it.artisanId) to it.questionLineageId }
        }
        return (server + queued).toSet()
    }

    fun queued(unit: SessionUnit?, artisanId: String, lineageId: String): String? {
        unit ?: return null
        return unit.steps.filter { it.kind == StepKind.ANSWERS && it.state != StepState.DONE }
            .sortedBy { it.deviceTime }
            .mapNotNull { step ->
                step.bodyJson?.let { runCatching { outboxJson.decodeFromString(QAnswersPut.serializer(), it) }.getOrNull() }
                    ?.items?.lastOrNull { unit.resolve(it.artisanId) == unit.resolve(artisanId) && it.questionLineageId == lineageId }?.text
            }.lastOrNull()
    }

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

    fun prefillItems(forIds: Collection<String>, lineageId: String, sessionId: String, answers: List<QAnswer>): List<QAnswerItem> =
        forIds.filterNot { com.fieldrepository.app.questionnaire.data.QSubjects.isGuest(it) }
            .filter { id -> current(answers, id, lineageId)?.let { it.text.isBlank() || it.origin == "PREFILL" } ?: true }
            .map { id -> QAnswerItem(artisanId = id, questionLineageId = lineageId, text = "", origin = "PREFILL", attribution = "INDIVIDUAL", sessionId = sessionId) }

    fun replacesRecordedAnswer(answers: List<QAnswer>, forIds: Collection<String>, lineageId: String): QAnswer? =
        forIds.firstNotNullOfOrNull { id -> current(answers, id, lineageId)?.takeIf { it.sourceRecordingId != null && it.text.isNotBlank() } }
}

fun replaceRemovesEarlier(isContentAdmin: Boolean): Boolean = isContentAdmin

object ParticipantModel {
    fun takesOf(detail: QSessionDetail?, artisanId: String): Int =
        detail?.recordings.orEmpty().count { r -> r.removedAt == null && r.allSubjects.any { it.artisanId == artisanId } }

    fun refusingSubject(recording: QRecording, detail: QSessionDetail?): String? {
        val refused = detail?.participants.orEmpty().filter { it.consent?.state == "REFUSED" }.map { it.artisanId }.toSet()
        return recording.subjects.firstOrNull { it.artisanId in refused }?.artisanId
            ?: recording.subjects.firstOrNull { it.role == SubjectRole.ANSWERED.wire }?.artisanId
            ?: recording.subjects.firstOrNull()?.artisanId
    }
}
