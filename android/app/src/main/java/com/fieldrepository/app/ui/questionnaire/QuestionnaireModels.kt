package com.fieldrepository.app.ui.questionnaire

import com.fieldrepository.app.data.CoverageState
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.data.OverlayTake
import com.fieldrepository.app.data.QArtisanRecord
import com.fieldrepository.app.data.QCheckArtisan
import com.fieldrepository.app.data.QCoverageCheckResponse
import com.fieldrepository.app.data.QRecording
import com.fieldrepository.app.data.QSession
import com.fieldrepository.app.data.QSnapshotTake
import com.fieldrepository.app.data.QTarget
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.data.QuestionnaireSectionDto
import com.fieldrepository.app.data.RepeatReason
import com.fieldrepository.app.data.SessionPurpose
import com.fieldrepository.app.data.SubjectRole
import java.time.Instant
import java.time.LocalDate

/**
 * The questionnaire v2 screens' rules, kept out of the composables so the JVM tests can hold them:
 * the instrument's shape, the coverage grid, P2's copy, the transcript line and the artisan
 * timeline. Wording comes from [QuestionnaireCopy] only.
 */

// ── Instrument ──────────────────────────────────────────────────────────────────────────────

data class InstrumentQuestion(val lineageId: String, val displayCode: String, val prompt: String, val sectionId: String)
data class InstrumentSection(val id: String, val code: String, val title: String, val questions: List<InstrumentQuestion>)

data class Instrument(val id: String, val title: String, val sections: List<InstrumentSection>) {
    val sectionOfQuestion: Map<String, String> = sections.flatMap { s -> s.questions.map { it.lineageId to s.id } }.toMap()
    val questionsOfSection: Map<String, List<String>> = sections.associate { s -> s.id to s.questions.map { it.lineageId } }
    fun section(id: String) = sections.firstOrNull { it.id == id }
    fun question(lineageId: String) = sections.flatMap { it.questions }.firstOrNull { it.lineageId == lineageId }

    /** "Section D" for a section target, "D3" for a question. */
    fun label(targetKey: String): String = when {
        targetKey.startsWith("S:") -> section(targetKey.removePrefix("S:"))?.let { "Section ${it.code}" } ?: "Section"
        targetKey.startsWith("Q:") -> question(targetKey.removePrefix("Q:"))?.displayCode ?: "Question"
        else -> QuestionnaireCopy.s("record.wholeSession")
    }

    val labels: Map<String, String> get() =
        sections.flatMap { s -> listOf("S:${s.id}" to "Section ${s.code}") + s.questions.map { "Q:${it.lineageId}" to it.displayCode } }.toMap()

    companion object {
        /** From the builder's sections; falls back to row ids and positional codes on a pre-v2 server. */
        fun from(id: String, title: String, sections: List<QuestionnaireSectionDto>): Instrument = Instrument(
            id, title,
            sections.filter { it.isActive }.sortedBy { it.sortOrder }.map { s ->
                InstrumentSection(
                    s.id, s.code, s.title,
                    s.questions.filter { it.isActive }.sortedBy { it.sortOrder }.mapIndexed { index, q ->
                        InstrumentQuestion(q.lineageId ?: q.id, q.displayCode ?: "${s.code}${index + 1}", q.prompt, s.id)
                    }
                )
            }
        )
    }
}

// ── Coverage grid (§3.2, §4.3) ─────────────────────────────────────────────────────────────

enum class CellMark { NONE, RECORDED, PARTIAL, VIA_SECTION, UPLOADING, ON_DEVICE, NEEDS_DECISION }

data class GridCell(val artisanId: String, val mark: CellMark, val count: Int = 0, val partial: Pair<Int, Int>? = null) {
    /** ○ ● ●2 ◐ 4/10 ◌ ◎ ⚠, as the legend reads. */
    val glyph: String get() = when (mark) {
        CellMark.NONE -> "○"
        CellMark.RECORDED -> if (count >= 2) "●$count" else "●"
        CellMark.PARTIAL -> "◐ ${partial?.first}/${partial?.second}"
        CellMark.VIA_SECTION -> "◌"
        CellMark.UPLOADING, CellMark.ON_DEVICE -> "◎"
        CellMark.NEEDS_DECISION -> "⚠"
    }
    val covered: Boolean get() = mark == CellMark.RECORDED || mark == CellMark.NEEDS_DECISION || mark == CellMark.UPLOADING || mark == CellMark.ON_DEVICE
}

data class GridRow(val section: InstrumentSection, val cells: List<GridCell>) {
    /** The phone summary: "2/3", with ⚠ when any cell needs a decision. */
    val summary: String get() = "${cells.count { it.covered }}/${cells.size}" + if (cells.any { it.mark == CellMark.NEEDS_DECISION }) " ⚠" else ""
}

object CoverageGridModel {
    /**
     * [known] are the server's takes (snapshot plus this session's recordings, de-duplicated by the
     * caller); [overlay] the takes still on this device.
     */
    fun rows(instrument: Instrument, artisanIds: List<String>, known: List<QSnapshotTake>, overlay: List<OverlayTake>): List<GridRow> {
        val answered = known.filter { it.role == SubjectRole.ANSWERED }
        return instrument.sections.map { section ->
            val cells = artisanIds.map { artisanId ->
                val sectionTakes = answered.filter { it.artisanId == artisanId && it.targetKey == "S:${section.id}" }
                val local = overlay.filter { it.artisanId == artisanId && (it.targetKey == "S:${section.id}" || section.questions.any { q -> it.targetKey == "Q:${q.lineageId}" }) }
                val questionTakes = section.questions.count { q -> answered.any { it.artisanId == artisanId && it.targetKey == "Q:${q.lineageId}" } }
                val decision = answered.any { it.artisanId == artisanId && it.filing == "NEEDS_DECISION" && (it.targetKey == "S:${section.id}" || section.questions.any { q -> it.targetKey == "Q:${q.lineageId}" }) }
                when {
                    decision -> GridCell(artisanId, CellMark.NEEDS_DECISION, sectionTakes.size)
                    local.isNotEmpty() && sectionTakes.isEmpty() -> GridCell(artisanId, if (local.any { it.uploading }) CellMark.UPLOADING else CellMark.ON_DEVICE, local.size)
                    sectionTakes.isNotEmpty() -> GridCell(artisanId, CellMark.RECORDED, sectionTakes.size + local.size)
                    questionTakes > 0 -> GridCell(artisanId, CellMark.PARTIAL, partial = questionTakes to section.questions.size)
                    else -> GridCell(artisanId, CellMark.NONE)
                }
            }
            GridRow(section, cells)
        }
    }

    /** A question's own mark for one artisan: its takes, else ◌ when its section has one. */
    fun questionMark(question: InstrumentQuestion, artisanId: String, known: List<QSnapshotTake>, overlay: List<OverlayTake>): GridCell {
        val answered = known.filter { it.role == SubjectRole.ANSWERED && it.artisanId == artisanId }
        val own = answered.filter { it.targetKey == "Q:${question.lineageId}" }
        val local = overlay.filter { it.artisanId == artisanId && it.targetKey == "Q:${question.lineageId}" }
        return when {
            own.any { it.filing == "NEEDS_DECISION" } -> GridCell(artisanId, CellMark.NEEDS_DECISION, own.size)
            own.isNotEmpty() -> GridCell(artisanId, CellMark.RECORDED, own.size + local.size)
            local.isNotEmpty() -> GridCell(artisanId, if (local.any { it.uploading }) CellMark.UPLOADING else CellMark.ON_DEVICE, local.size)
            answered.any { it.targetKey == "S:${question.sectionId}" } -> GridCell(artisanId, CellMark.VIA_SECTION)
            else -> GridCell(artisanId, CellMark.NONE)
        }
    }

    /** Recordings as snapshot takes, so the grid reads one shape whatever the source. */
    fun takesOf(recordings: List<QRecording>): List<QSnapshotTake> = recordings.filter { it.removedAt == null }.flatMap { r ->
        r.targets.flatMap { t ->
            r.subjects.map { s ->
                QSnapshotTake(
                    recordingId = r.id, sessionId = r.sessionId, artisanId = s.artisanId, targetKey = t.targetKey.ifBlank { keyOf(t) },
                    role = s.role, capturedAt = r.capturedAt, estimated = r.estimated, filing = r.filing, position = s.position ?: 1,
                    durationMs = r.durationMs, recordedBy = r.recordedBy, rosterCount = r.rosterCount, repeatReason = s.repeatReason
                )
            }
        }
    }

    fun merge(snapshot: List<QSnapshotTake>, session: List<QSnapshotTake>): List<QSnapshotTake> {
        val sessionIds = session.map { it.recordingId }.toSet()
        return snapshot.filter { it.recordingId !in sessionIds } + session
    }

    private fun keyOf(t: QTarget) = if (t.questionLineageId != null) "Q:${t.questionLineageId}" else if (t.sectionId != null) "S:${t.sectionId}" else ""
}

// ── P2 (§4.4) ──────────────────────────────────────────────────────────────────────────────

data class P2TakeLine(val artisanId: String, val text: String)

data class P2Model(
    val title: String,
    val body: String,
    val lines: List<P2TakeLine>,
    val firstTakes: List<String>,
    val reasons: List<String>,
    val preselected: String,
    val primaryButton: String,
    val offerPartTwo: Boolean,
    val offerReplace: Boolean,
    val ackedRecordingIds: List<String>,
    /** The latest earlier take, for "Replace the earlier take" and Part 2. */
    val latestRecordingId: String?
)

object AlreadyRecorded {
    /** Null when nobody among [artisanIds] is covered: record straight away, no prompt. */
    fun model(
        response: QCoverageCheckResponse,
        targetLabel: String,
        targetCode: String,
        names: Map<String, String>,
        artisanIds: List<String>,
        purpose: String,
        filePick: Boolean,
        now: Instant
    ): P2Model? {
        val perArtisan: List<QCheckArtisan> = response.targets.flatMap { it.perArtisan }.filter { it.artisanId in artisanIds }
        val covered = perArtisan.filter { it.takes.isNotEmpty() && it.state != CoverageState.NOT_RECORDED && it.state != CoverageState.PARTIAL }
            .distinctBy { it.artisanId }
        if (covered.isEmpty()) return null
        val coveredIds = covered.map { it.artisanId }.toSet()
        val latest = covered.flatMap { it.takes }.maxByOrNull { it.capturedAt ?: "" }
        val latestDate = latest?.session?.date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            ?: latest?.capturedAt?.let { runCatching { Instant.parse(it).atZone(Naming.IST).toLocalDate() }.getOrNull() }
        val viaSection = covered.all { it.viaSection }
        val body = QuestionnaireCopy.p2Body(
            targetLabel, covered.map { names[it.artisanId].orEmpty().let(QuestionnaireCopy::firstName) },
            artisanIds.size, latestDate, viaSection
        )
        val lines = if (artisanIds.size == 1) emptyList() else covered.map { a ->
            val t = a.takes.last()
            P2TakeLine(
                a.artisanId,
                QuestionnaireCopy.p2TakeLine(
                    QuestionnaireCopy.firstName(names[a.artisanId].orEmpty()), t.durationMs, t.recordedBy?.name,
                    t.session?.date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }, t.session?.rosterCount ?: 1
                )
            )
        }
        val firsts = artisanIds.filter { it !in coveredIds }.map { QuestionnaireCopy.p2FirstTake(QuestionnaireCopy.firstName(names[it].orEmpty()), targetCode) }
        val preselected = when (purpose) {
            SessionPurpose.FOLLOW_UP -> RepeatReason.FOLLOW_UP
            SessionPurpose.REDO -> RepeatReason.REDO
            else -> response.suggestedReason ?: RepeatReason.REDO
        }
        // Part 2 only when the latest take is in this session with the same ANSWERED subjects (§3.3.5).
        val partTwo = latest != null && latest.sameSession && coveredIds == artisanIds.toSet()
        return P2Model(
            title = QuestionnaireCopy.s("p2.title"),
            body = body,
            lines = lines,
            firstTakes = firsts,
            reasons = RepeatReason.ALL,
            preselected = preselected,
            primaryButton = QuestionnaireCopy.s(if (filePick) "p2.uploadAgain" else "p2.recordAgain"),
            offerPartTwo = partTwo,
            offerReplace = latest != null && latest.sameSession && latest.mine,
            ackedRecordingIds = covered.flatMap { it.takes }.map { it.recordingId }.filterNot { it.startsWith("local:") }.distinct(),
            latestRecordingId = latest?.recordingId?.takeUnless { it.startsWith("local:") }
        )
    }
}

// ── Transcript status (§3.14) ──────────────────────────────────────────────────────────────

enum class TranscriptTone { NEUTRAL, ERROR }

data class TranscriptLine(val text: String, val tone: TranscriptTone, val retryEnabled: Boolean, val retryReason: String?)

object TranscriptStatus {
    /**
     * Paused is neutral, never an error, and no control can start a transcription while it holds.
     * A recording without consent reads "Waiting for consent to transcribe". The 18 Oct recordings
     * carry the owner's attestation (verdict ALLOW) and show no consent line at all.
     */
    fun line(recording: QRecording, paused: Boolean): TranscriptLine? {
        val t = recording.transcript
        val verdict = recording.consent?.verdict
        if (verdict == "REFUSED" || t?.status == "REFUSED_CONSENT") return TranscriptLine(QuestionnaireCopy.s("transcript.refused"), TranscriptTone.NEUTRAL, false, null)
        if (verdict == "AWAITING" || t?.status == "AWAITING_CONSENT") return TranscriptLine(QuestionnaireCopy.s("consent.waiting"), TranscriptTone.NEUTRAL, false, null)
        if (paused || t?.paused == true) return TranscriptLine(QuestionnaireCopy.s("transcript.paused"), TranscriptTone.NEUTRAL, false, QuestionnaireCopy.s("transcript.pausedReason"))
        return when (t?.status) {
            null -> null
            "COMPLETED" -> TranscriptLine("Transcribed", TranscriptTone.NEUTRAL, false, null)
            "FAILED" -> TranscriptLine("Transcription failed", TranscriptTone.ERROR, true, null)
            "PROCESSING" -> TranscriptLine("Transcribing", TranscriptTone.NEUTRAL, false, null)
            else -> TranscriptLine("Queued to transcribe", TranscriptTone.NEUTRAL, false, null)
        }
    }
}

// ── Artisan record timeline (§4.6) ─────────────────────────────────────────────────────────

sealed interface TimelineRow {
    data class SessionHead(val sessionId: String, val text: String) : TimelineRow
    data class Take(val recordingId: String, val time: String?, val text: String, val badges: List<String>) : TimelineRow
    data object Uncertain : TimelineRow
}

object ArtisanTimeline {
    fun rows(record: QArtisanRecord, questionnaireId: String, artisanId: String, names: Map<String, String> = emptyMap()): List<TimelineRow> {
        val instrument = record.instruments.firstOrNull { it.questionnaireId == questionnaireId } ?: record.instruments.firstOrNull() ?: return emptyList()
        val out = mutableListOf<TimelineRow>()
        instrument.sessions.sortedByDescending { it.session.sessionDate ?: "" }.forEach { entry ->
            out += TimelineRow.SessionHead(entry.session.id, sessionHead(entry.session, artisanId, names))
            val uncertainIds = entry.uncertain.map { it.id }.toSet()
            entry.recordings.filter { it.id !in uncertainIds && it.removedAt == null }.sortedBy { it.capturedAt ?: "" }
                .forEach { out += take(it, artisanId, showTime = true) }
            if (entry.uncertain.isNotEmpty()) {
                out += TimelineRow.Uncertain
                entry.uncertain.filter { it.removedAt == null }.forEach { out += take(it, artisanId, showTime = false) }
            }
        }
        return out
    }

    /** "24 Jun · Group of 5 with Meena, Ravi +2 · Ankur · Kolkata". */
    fun sessionHead(session: QSession, artisanId: String, names: Map<String, String>): String {
        val date = session.sessionDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        val others = session.participants.filter { it.artisanId != artisanId }.map { QuestionnaireCopy.firstName(it.name.ifBlank { names[it.artisanId].orEmpty() }) }
        val count = session.participantCount.coerceAtLeast(session.participants.size)
        val mode = when {
            count <= 1 -> "Solo"
            count == 2 -> "Pair with ${others.firstOrNull().orEmpty()}"
            else -> "Group of $count with " + others.take(2).joinToString(", ") + if (others.size > 2) " +${others.size - 2}" else ""
        }
        return listOfNotNull(
            date?.let(QuestionnaireCopy::day),
            mode,
            session.researchers.joinToString(", ") { QuestionnaireCopy.firstName(it.name) }.ifBlank { null },
            session.place?.ifBlank { null }?.substringBefore(',')
        ).joinToString(" · ")
    }

    private fun take(r: QRecording, artisanId: String, showTime: Boolean): TimelineRow.Take {
        val subject = r.subjects.firstOrNull { it.artisanId == artisanId }
        val label = targetsLabel(r.targets)
        val parts = mutableListOf(label, QuestionnaireCopy.elapsed(r.durationMs))
        if (subject?.role == SubjectRole.PRESENT) parts += QuestionnaireCopy.s("record.present")
        else if (r.targets.isNotEmpty()) {
            subject?.position?.let { parts += QuestionnaireCopy.take(it) }
            subject?.repeatReason?.let { parts += QuestionnaireCopy.reasonLabel(it) }
        }
        val badges = buildList {
            if (r.filing == "NEEDS_DECISION") add(QuestionnaireCopy.s("record.needsDecision"))
            if (r.estimated) add(QuestionnaireCopy.s("record.estimated"))
            if (r.provenance == "LEGACY") add(QuestionnaireCopy.s("record.legacy"))
        }
        val time = if (showTime && !r.estimated) r.capturedAt?.let { runCatching { QuestionnaireCopy.clock(Instant.parse(it)) }.getOrNull() } else null
        return TimelineRow.Take(r.id, time, parts.joinToString(" · "), badges)
    }

    /** "Section A", "F7", "F1–F6", or "Whole session". */
    fun targetsLabel(targets: List<QTarget>): String {
        if (targets.isEmpty()) return QuestionnaireCopy.s("record.wholeSession")
        if (targets.size == 1) {
            val t = targets.single()
            return t.displayCode ?: t.label ?: "Section"
        }
        val codes = targets.mapNotNull { it.displayCode }
        return if (codes.size == targets.size && codes.size >= 2) "${codes.first()}–${codes.last()}" else targets.joinToString(", ") { it.displayCode ?: it.label.orEmpty() }
    }
}
