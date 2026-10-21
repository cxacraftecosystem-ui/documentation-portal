package com.fieldrepository.app.data

import kotlinx.serialization.Serializable
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Coverage offline (DESIGN §6.1.9): the server's snapshot per instrument and workshop, plus the
 * outbox overlay, answering `coverage/check` in the server's own response shape so P2 reads the same
 * with or without a signal. Coverage never blocks a replay; this only drives the prompt.
 *
 * The hierarchy rule (§3.1) is the server's: a question is already covered for an artisan when the
 * question, or its parent section, has a take for them. A section after question takes does not
 * prompt; it is Partial.
 */
@Serializable
data class CachedSnapshot(
    val questionnaireId: String,
    val workshopId: String? = null,
    val fetchedAt: String,
    val snapshot: QSnapshot
)

/** One take known only to this device (queued or uploading). */
data class OverlayTake(
    val clientClipKey: String,
    val sessionKey: String,
    val artisanId: String,
    val targetKey: String,
    val capturedAt: String,
    val durationMs: Long,
    val rosterCount: Int,
    val uploading: Boolean
)

class CoverageCache(private val dir: File?, private val clock: () -> Instant = Instant::now) {
    private val memory = HashMap<String, CachedSnapshot>()

    private fun key(questionnaireId: String, workshopId: String?) = "$questionnaireId@${workshopId ?: "none"}"
    private fun file(key: String) = dir?.let { File(it, key.filter { c -> c.isLetterOrDigit() || c == '@' || c == '-' || c == '_' } + ".json") }

    fun get(questionnaireId: String, workshopId: String?): CachedSnapshot? {
        val k = key(questionnaireId, workshopId)
        memory[k]?.let { return it }
        val loaded = file(k)?.takeIf { it.isFile }?.let {
            runCatching { outboxJson.decodeFromString(CachedSnapshot.serializer(), it.readText()) }.getOrNull()
        }
        if (loaded != null) memory[k] = loaded
        return loaded
    }

    /** Fold a full snapshot or a `sinceSeq` delta into the cache. */
    fun apply(questionnaireId: String, workshopId: String?, incoming: QSnapshot, delta: Boolean) {
        val k = key(questionnaireId, workshopId)
        val base = if (delta) get(questionnaireId, workshopId)?.snapshot else null
        val merged = if (base == null) incoming else {
            // A changed take's old rows go too: one that dropped to PRESENT-only is in neither list (M3).
            val replaced = incoming.takes.map { it.recordingId }.toSet() + incoming.changedRecordingIds
            val removed = incoming.removedRecordingIds.toSet()
            QSnapshot(
                asOfSeq = incoming.asOfSeq,
                takes = base.takes.filter { it.recordingId !in replaced && it.recordingId !in removed } + incoming.takes,
                removedRecordingIds = emptyList()
            )
        }
        val cached = CachedSnapshot(questionnaireId, workshopId, clock().toString(), merged)
        memory[k] = cached
        file(k)?.let { f ->
            f.parentFile?.mkdirs()
            writeFileAtomically(f, outboxJson.encodeToString(CachedSnapshot.serializer(), cached).toByteArray())
        }
    }

    /** "· Checked 2 h ago" when the answer came from the snapshot; null when it is fresh (< 1 min). */
    fun ageLabel(questionnaireId: String, workshopId: String?): String? {
        val fetched = get(questionnaireId, workshopId)?.fetchedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return QuestionnaireCopy.checkedAgo(Duration.between(fetched, clock()))
    }

    data class CheckContext(
        val sessionId: String? = null,
        val sessionKey: String? = null,
        val purpose: String = SessionPurpose.FIRST_SITTING.wire,
        val rosterCount: Int = 1,
        val myUserId: String? = null,
        /** questionLineageId → its section id, from the instrument in use. */
        val sectionOfQuestion: Map<String, String> = emptyMap(),
        /** sectionId → its active question lineage ids, for Partial. */
        val questionsOfSection: Map<String, List<String>> = emptyMap(),
        val labels: Map<String, String> = emptyMap()
    )

    /** The offline `coverage/check`, from the cached snapshot plus [overlay]. */
    fun check(request: QCoverageCheckRequest, workshopId: String?, overlay: List<OverlayTake>, context: CheckContext): QCoverageCheckResponse {
        val snapshot = get(request.questionnaireId, workshopId)?.snapshot ?: QSnapshot()
        return checkAgainst(snapshot, request, overlay, context, clock())
    }

    companion object {
        fun checkAgainst(
            snapshot: QSnapshot,
            request: QCoverageCheckRequest,
            overlay: List<OverlayTake>,
            context: CheckContext,
            now: Instant
        ): QCoverageCheckResponse {
            val removed = snapshot.removedRecordingIds.toSet()
            val live = snapshot.takes.filter { it.role == SubjectRole.ANSWERED.wire && it.recordingId !in removed }
            val all: List<QSnapshotTake> = live + overlay.map {
                QSnapshotTake(
                    recordingId = "local:${it.clientClipKey}", sessionId = it.sessionKey, artisanId = it.artisanId,
                    targetKey = it.targetKey, capturedAt = it.capturedAt, durationMs = it.durationMs,
                    rosterCount = it.rosterCount, recordedBy = context.myUserId?.let { id -> QUserRef(id, "") }
                )
            }
            fun takesFor(artisanId: String, targetKey: String) =
                all.filter { it.artisanId == artisanId && it.targetKey == targetKey }.sortedBy { it.capturedAt ?: "" }

            var suggested: String? = null
            val targets = request.targets.map { target ->
                val targetKey = target.targetKey
                val perArtisan = request.artisanIds.map { artisanId ->
                    val own = takesFor(artisanId, targetKey)
                    val parentSection = target.questionLineageId?.let { context.sectionOfQuestion[it] }
                    val viaSection = if (own.isEmpty() && parentSection != null) takesFor(artisanId, "S:$parentSection") else emptyList()
                    val shown = own.ifEmpty { viaSection }
                    val state = when {
                        shown.any { it.filing == "NEEDS_DECISION" } -> CoverageState.NEEDS_DECISION.wire
                        own.isNotEmpty() -> CoverageState.RECORDED.wire
                        viaSection.isNotEmpty() -> CoverageState.VIA_SECTION.wire
                        target.sectionId != null && context.questionsOfSection[target.sectionId].orEmpty().any { q -> takesFor(artisanId, "Q:$q").isNotEmpty() } -> CoverageState.PARTIAL.wire
                        else -> CoverageState.NOT_RECORDED.wire
                    }
                    val takes = shown.mapIndexed { index, take -> toCheckTake(take, index + 1, context, now) }
                    if (suggested == null && takes.isNotEmpty()) suggested = suggestReason(takes.last(), context, now)
                    QCheckArtisan(artisanId, state, viaSection = own.isEmpty() && viaSection.isNotEmpty(), takes = takes)
                }
                QCheckTarget(targetKey, context.labels[targetKey], perArtisan)
            }
            return QCoverageCheckResponse(asOf = now.toString(), identical = null, targets = targets, suggestedReason = suggested)
        }

        private fun toCheckTake(take: QSnapshotTake, position: Int, context: CheckContext, now: Instant): QCheckTake {
            val sameSession = take.sessionId != null && (take.sessionId == context.sessionId || take.sessionId == context.sessionKey)
            return QCheckTake(
                recordingId = take.recordingId, position = take.position.takeIf { !take.recordingId.startsWith("local:") } ?: position,
                capturedAt = take.capturedAt, estimated = take.estimated, durationMs = take.durationMs,
                recordedBy = take.recordedBy, session = QCheckSession(take.sessionId ?: "", take.sessionDate ?: take.capturedAt?.take(10), take.rosterCount),
                sameSession = sameSession, mine = context.myUserId != null && take.recordedBy?.id == context.myUserId,
                repeatReason = take.repeatReason
            )
        }

        /**
         * Preselection (§3.3.2 and §4.4): the session purpose decides first; a same-session take by me
         * within 2 h → Redo; a different setting (solo vs group) → New setting; a different researcher
         * → Re-interview; otherwise Redo.
         */
        fun suggestReason(latest: QCheckTake, context: CheckContext, now: Instant): String {
            when (context.purpose) {
                SessionPurpose.FOLLOW_UP.wire -> return RepeatReason.FOLLOW_UP.wire
                SessionPurpose.REDO.wire -> return RepeatReason.REDO.wire
            }
            val at = latest.capturedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (latest.sameSession && latest.mine && at != null && Duration.between(at, now) <= Duration.ofHours(2)) return RepeatReason.REDO.wire
            val earlierSolo = (latest.session?.rosterCount ?: 1) == 1
            val nowSolo = context.rosterCount == 1
            if (earlierSolo != nowSolo) return RepeatReason.NEW_SETTING.wire
            return if (latest.mine) RepeatReason.REDO.wire else RepeatReason.RE_INTERVIEW.wire
        }

        /** Overlay takes from the outbox: every pending clip × its targets × its ANSWERED subjects. */
        fun overlayFrom(units: List<SessionUnit>, questionnaireId: String): List<OverlayTake> =
            units.filter { it.questionnaireId == questionnaireId }.flatMap { unit ->
                unit.pendingClips.flatMap { step ->
                    val clip = step.clip!!
                    val subjects = clip.recording.subjects.filter { it.role == SubjectRole.ANSWERED.wire }
                    clip.recording.targets.flatMap { target ->
                        subjects.map { s ->
                            OverlayTake(
                                clientClipKey = clip.clientClipKey, sessionKey = unit.sessionId ?: unit.clientKey,
                                artisanId = unit.resolve(s.artisanId), targetKey = target.targetKey,
                                capturedAt = clip.recording.deviceCapturedAt, durationMs = clip.recording.durationMs,
                                rosterCount = clip.recording.rosterCount, uploading = clip.uploaded || clip.partsDone.isNotEmpty()
                            )
                        }
                    }
                }
            }
    }
}
