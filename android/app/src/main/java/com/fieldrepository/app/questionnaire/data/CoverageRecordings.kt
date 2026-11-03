package com.fieldrepository.app.questionnaire.data

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object CoverageRecordings {
    private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

    data class Take(val recordingId: String, val mediaFileId: String, val capturedAt: String?, val researcher: String?, val durationMs: Long?)

    sealed interface Opens {
        data object Nothing : Opens
        data class One(val take: Take) : Opens
        data class Choose(val takes: List<Take>) : Opens
    }

    fun opens(takes: List<Take>): Opens = when (takes.size) {
        0 -> Opens.Nothing
        1 -> Opens.One(takes.single())
        else -> Opens.Choose(takes)
    }

    fun ofCell(cell: QGridCell): List<Take> = cell.recordings.mapNotNull { r ->
        r.mediaFileId?.let { Take(r.recordingId, it, r.capturedAt, r.recordedBy?.name?.ifBlank { null }, r.durationMs) }
    }

    fun hasTakes(cell: QGridCell?): Boolean = cell != null && (cell.recordings.isNotEmpty() || cell.takes > 0 || cell.recordedCount > 0)

    fun filters(questionnaireId: String, sectionId: String, subjectId: String, workshopId: String?, designWorkshop: Boolean): QuestionnaireFilters {
        var f = QuestionnaireFilters()
            .with("questionnaireId", listOf(questionnaireId))
            .with("sectionId", listOf(sectionId))
        QSubjects.artisanOf(subjectId)?.let { f = f.with("artisanId", listOf(it)) }
        workshopId?.ifBlank { null }?.let { f = f.with(if (designWorkshop) "designWorkshopId" else "workshopId", listOf(it)) }
        return f
    }

    fun fromList(rows: List<QRecording>, sectionId: String, subjectId: String): List<Take> =
        rows.filter { r ->
            r.removedAt == null && r.mediaFileId != null &&
                r.allSubjects.any { it.artisanId == subjectId } &&
                r.targets.any { it.sectionId == sectionId || it.targetKey == "S:$sectionId" }
        }.sortedBy { it.capturedAt.orEmpty() }.map { r ->
            Take(r.id, r.mediaFileId!!, r.capturedAt, r.recordedBy?.name?.ifBlank { null }, r.durationMs.takeIf { it > 0 })
        }

    fun label(take: Take, zone: ZoneId = ZoneId.systemDefault()): String = listOfNotNull(
        take.capturedAt?.let { at -> runCatching { OffsetDateTime.parse(at).atZoneSameInstant(zone).format(WHEN) }.getOrNull() },
        take.researcher?.let(QuestionnaireCopy::firstName),
        take.durationMs?.takeIf { it > 0 }?.let(QuestionnaireCopy::minutes),
    ).joinToString(" · ")
}
