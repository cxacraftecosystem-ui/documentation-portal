package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.data.QAnswer
import com.fieldrepository.app.questionnaire.data.QAnswerItem
import com.fieldrepository.app.questionnaire.data.QAnswerResult
import com.fieldrepository.app.questionnaire.data.QAnswersPut
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QSessionDetail
import com.fieldrepository.app.questionnaire.data.QSessionReview
import com.fieldrepository.app.questionnaire.data.QTargetRef
import com.fieldrepository.app.questionnaire.data.QueuedSessionPatch
import com.fieldrepository.app.questionnaire.data.RecordBy
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.SessionOutbox
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
    val files: Int = 0,
    val choice: Boolean = false
)

internal fun SessionStep.isConflict(): Boolean =
    state == StepState.FAILED && (failureCode == SessionOutbox.RECORD_BY_CONFLICT || (kind == StepKind.ANSWERS && failureCode == "CONFLICT" && conflicts.isNotEmpty()))

internal fun recordByLabel(wire: String?): String =
    QuestionnaireCopy.t(if (wire == RecordBy.QUESTION.wire) "recordByQuestion" else "recordBySection")

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
            StepKind.SESSION_PATCH -> {
                val queued = step.bodyJson?.let { runCatching { outboxJson.decodeFromString(QueuedSessionPatch.serializer(), it) }.getOrNull() }
                if (step.failureCode == SessionOutbox.RECORD_BY_CONFLICT && queued != null) listOf(
                    TrayRow(step.id, step.kind, QuestionnaireCopy.t("fieldRecordBy"), recordByLabel(queued.before), mine = recordByLabel(queued.recordBy), sendAgain = false, choice = true)
                ) else listOf(TrayRow(step.id, step.kind, QuestionnaireCopy.t("fieldRecordBy"), reason))
            }
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

enum class SaveTone { SAVED, ON_PHONE, UPLOADING, WAITING, FAILED, CONFLICT }

data class SavePillState(val tone: SaveTone, val text: String, val percent: Int = 0, val failedSteps: List<String> = emptyList())

object SavePillModel {
    fun of(unit: SessionUnit?): SavePillState {
        if (unit == null) return SavePillState(SaveTone.SAVED, QuestionnaireCopy.t("saved"))
        val failed = unit.failedSteps.filterNot { it.isConflict() }
        if (failed.isNotEmpty()) return SavePillState(SaveTone.FAILED, QuestionnaireCopy.s("upload.failed"), failedSteps = failed.map { it.id })
        if (unit.failedSteps.isNotEmpty()) return SavePillState(SaveTone.CONFLICT, QuestionnaireCopy.s("p9.title"))
        val uploading = unit.uploadingClips
        if (uploading.isNotEmpty()) {
            val percent = uploading.mapNotNull { it.clip?.uploadedPercent }.let { if (it.isEmpty()) 0 else it.sum() / it.size }
            val line = OutboxTrayModel.uploadLine(percent)
            return if (percent <= 0) SavePillState(SaveTone.WAITING, line)
            else SavePillState(SaveTone.UPLOADING, QuestionnaireCopy.takesUploadingPercent(uploading.size, percent), percent)
        }
        if (unit.steps.any { it.state == StepState.PENDING }) return SavePillState(SaveTone.ON_PHONE, QuestionnaireCopy.t("savedOnThisPhone"))
        return SavePillState(SaveTone.SAVED, QuestionnaireCopy.t("saved"))
    }
}

data class UploadBatch(val keys: List<String>, val target: String?) {
    val notice: String get() = QuestionnaireCopy.uploadedTo(keys.size, target)
}

object UploadBatches {
    fun scopeOf(target: QTargetRef?): String = target?.targetKey ?: InPlaceTakes.WHOLE

    fun split(batches: List<UploadBatch>, unit: SessionUnit?, recordings: List<QRecording>): Pair<List<UploadBatch>, List<UploadBatch>> {
        val clips = unit?.steps.orEmpty().filter { it.kind == StepKind.CLIP }.associateBy { it.clip?.clientClipKey }
        val onServer = recordings.mapNotNull { it.clientClipKey }.toSet()
        val finished = mutableListOf<UploadBatch>()
        val waiting = mutableListOf<UploadBatch>()
        batches.forEach { batch ->
            val states = batch.keys.map { key -> if (key in onServer) StepState.DONE else clips[key]?.state }
            when {
                states.any { it == StepState.FAILED } -> Unit
                states.all { it == StepState.DONE } -> finished += batch
                else -> waiting += batch
            }
        }
        return finished to waiting
    }

    fun landed(before: SessionUnit?, after: SessionUnit?): Set<String> {
        val was = before?.steps.orEmpty().filter { it.kind == StepKind.CLIP && it.state != StepState.DONE }.mapNotNull { it.clip?.clientClipKey }.toSet()
        return after?.steps.orEmpty().filter { it.kind == StepKind.CLIP && it.state == StepState.DONE && it.clip?.clientClipKey in was }
            .mapNotNull { it.clip?.clientClipKey }.toSet()
    }
}

data class LeaveCounts(val uploading: Int, val failed: Int) {
    val asks: Boolean get() = uploading > 0 || failed > 0

    companion object {
        fun of(unit: SessionUnit?, recording: Boolean): LeaveCounts {
            val clips = unit?.steps.orEmpty().filter { it.kind == StepKind.CLIP }
            return LeaveCounts(clips.count { it.state == StepState.PENDING } + if (recording) 1 else 0, clips.count { it.state == StepState.FAILED })
        }
    }
}

data class NeedsYouRow(val key: String, val title: String, val detail: String, val action: String, val stepId: String? = null, val code: String? = null, val conflict: Boolean = false)

data class StillToAskRow(val section: InstrumentSection, val status: SectionStatus) {
    val trailing: String get() =
        if (status.answered == 0) QuestionnaireCopy.t("notAskedCount", "count" to status.total) else QuestionnaireCopy.t("leftCount", "count" to status.left)
}

data class ReviewModel(
    val tally: SessionTally,
    val summary: String,
    val needsYou: List<NeedsYouRow>,
    val stillToAsk: List<StillToAskRow>
) {
    companion object {
        const val FIRST_ROWS = 3

        fun of(
            instrument: Instrument,
            evidence: SessionEvidence,
            names: List<String>,
            pending: Int,
            tray: List<TrayRow>,
            served: QSessionReview? = null,
            required: Int = 0
        ): ReviewModel {
            val local = evidence.tally(instrument)
            val tally = served?.let { s ->
                if (s.answered >= local.answered) local.copy(answered = s.answered, total = s.total.takeIf { it > 0 } ?: local.total, sectionsDone = s.sectionsDone) else local
            } ?: local
            val codes = (evidence.decisions(instrument) + served?.needsDecision.orEmpty().flatMap { it.codes }).distinct()
            val decisions = codes.map { code ->
                NeedsYouRow("decide:$code", QuestionnaireCopy.t("hasTwoTakes", "code" to code), QuestionnaireCopy.t("chooseWhichToKeep"), QuestionnaireCopy.t("choose"), code = code)
            }
            val failed = tray.distinctBy { it.stepId }.map { row ->
                if (row.kind == StepKind.CLIP) {
                    val code = row.title.substringBefore(" (").removePrefix("Section ")
                    NeedsYouRow("step:${row.stepId}", QuestionnaireCopy.t("takeDidNotUpload", "code" to code), QuestionnaireCopy.t("uploadFailed"), QuestionnaireCopy.t("retry"), stepId = row.stepId)
                } else {
                    val conflict = row.choice || row.conflict != null
                    val action = when {
                        conflict -> QuestionnaireCopy.t("choose")
                        row.sendAgain -> QuestionnaireCopy.t("retry")
                        else -> ""
                    }
                    NeedsYouRow("step:${row.stepId}", row.title, if (row.choice) QuestionnaireCopy.s("p9.title") else row.reason, action, stepId = row.stepId, conflict = conflict)
                }
            }
            val still = if (served != null) {
                served.stillToAsk.mapNotNull { row ->
                    val section = instrument.section(row.sectionId) ?: return@mapNotNull null
                    if (evidence.section(section).done) return@mapNotNull null
                    StillToAskRow(section, SectionStatus(section.id, row.answered, row.total, inSectionTake = false, typedOnly = false))
                }
            } else {
                instrument.sections.map { it to evidence.section(it) }.filter { !it.second.done }.map { StillToAskRow(it.first, it.second) }
            }
            val uploading = maxOf(pending, served?.pendingUploads ?: 0)
            val full = QuestionnaireCopy.t(
                "reviewSummary", "sections" to tally.sectionsDone, "names" to names.joinToString(", ").ifBlank { "—" },
                "pending" to uploading
            )
            val trimmed = if (uploading == 0) full.substringBeforeLast(". ") + "." else full
            val summary = listOfNotNull(trimmed, QuestionnaireCopy.requiredUnanswered(required)).joinToString(" ")
            return ReviewModel(tally, summary, decisions + failed, still)
        }
    }
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
