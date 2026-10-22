package com.fieldrepository.app.questionnaire.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.QArtisanOption
import com.fieldrepository.app.questionnaire.data.DecideAction
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.questionnaire.data.QArtisanRef
import com.fieldrepository.app.questionnaire.data.QDecideRequest
import com.fieldrepository.app.questionnaire.data.QFileMediaRequest
import com.fieldrepository.app.questionnaire.data.QInbox
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QRecordingPatch
import com.fieldrepository.app.questionnaire.data.QSession
import com.fieldrepository.app.questionnaire.data.QSessionDetail
import com.fieldrepository.app.questionnaire.data.QSessionPatch
import com.fieldrepository.app.questionnaire.data.QSubjectPatch
import com.fieldrepository.app.questionnaire.data.QTargetRef
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import com.fieldrepository.app.questionnaire.data.SubjectRole
import com.fieldrepository.app.ui.CalendarField
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.LocalMayDelete
import com.fieldrepository.app.ui.SearchableMultiSelectField
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate

/** A legacy take's candidate question: its lineage and the prompt the pill shows. */
internal data class LegacyCandidate(val lineageId: String, val prompt: String)

/**
 * One inbox row as shown: its section, its words, the session it opens, and what its in-place
 * actions need (audit H13, H14, 21 Oct 2026: every section's action works on Android, as on the web).
 */
internal data class InboxRow(
    val section: String,
    /** Stable across loads: "Not needed", "Keep" and "OK" remember it on this phone. */
    val key: String,
    val text: String,
    val sessionId: String?,
    val recordingId: String? = null,
    val mediaId: String? = null,
    /** "Keep one…": the take this one competes with, when the server names it. */
    val otherRecordingId: String? = null,
    /** Part 2: the take the server says this one continues. */
    val part2OfRecordingId: String? = null,
    val sessionIds: List<String> = emptyList(),
    val candidates: List<LegacyCandidate> = emptyList(),
    val dateFrom: String? = null,
    /** "File it": the session (and target) the server guessed, preselected. */
    val candidateSessionId: String? = null,
    val candidateTargetKey: String? = null
)

internal object InboxSection {
    const val TO_FILE = "toFile"
    const val NEEDS_DECISION = "needsDecision"
    const val LEGACY = "legacy"
    const val SET_DATES = "setDates"
    const val NO_PARTICIPANTS = "noParticipants"
    const val EMPTY = "emptySessions"
    const val PRIMARY_CHANGED = "primaryChanged"
}

/** The inbox sections of DESIGN §4.7, in order, with the rows the server sent and none dismissed here. */
internal fun inboxSections(inbox: QInbox, dismissed: Set<String> = emptySet()): List<Pair<String, List<InboxRow>>> = listOf(
    QuestionnaireCopy.s("inbox.toFile") to inbox.toFile.map(::toFileRow),
    QuestionnaireCopy.s("inbox.needsDecision") to inbox.needsDecision.map(::decisionRow),
    QuestionnaireCopy.s("inbox.legacy") to inbox.legacy.map(::legacyRow),
    QuestionnaireCopy.s("inbox.setDates") to inbox.setDates.map(::setDatesRow),
    QuestionnaireCopy.s("inbox.noParticipants") to inbox.noParticipants.map(::noParticipantsRow),
    QuestionnaireCopy.s("inbox.empty") to inbox.emptySessions.map(::emptySessionRow),
    QuestionnaireCopy.s("inbox.primaryChanged") to inbox.primaryChanged.map(::primaryChangedRow)
).map { (title, rows) -> title to rows.filter { it.key !in dismissed } }.filter { it.second.isNotEmpty() }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.ifBlank { null }

private fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.ifBlank { null } }
        ?: str(key)?.split(",")?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

/** "7 Jul" from a date or an instant; "7 Jul 18:24" when [withTime] and it carries a time. */
internal fun inboxDay(raw: String?, withTime: Boolean = false): String? {
    raw ?: return null
    runCatching { Instant.parse(raw) }.getOrNull()?.let { at ->
        val day = QuestionnaireCopy.day(at.atZone(Naming.IST).toLocalDate())
        return if (withTime) "$day ${QuestionnaireCopy.clock(at)}" else day
    }
    return runCatching { QuestionnaireCopy.day(LocalDate.parse(raw.take(10))) }.getOrNull()
}

private fun line(vararg parts: String?) = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")

internal fun toFileRow(row: JsonObject): InboxRow {
    val candidate = row["candidate"] as? JsonObject
    return InboxRow(
        InboxSection.TO_FILE, "toFile:${row.str("mediaId")}", line(row.str("name"), inboxDay(row.str("date"))), row.str("sessionId"),
        mediaId = row.str("mediaId"),
        candidateSessionId = candidate?.str("sessionId") ?: row.str("sessionId"),
        candidateTargetKey = candidate?.str("targetKey") ?: candidate?.strings("targets")?.firstOrNull()
    )
}

internal fun decisionRow(row: JsonObject) = InboxRow(
    InboxSection.NEEDS_DECISION, "needsDecision:${row.str("recordingId")}",
    line(row.strings("targets").joinToString(", ").ifBlank { null }, row.strings("artisanNames").joinToString(", ").ifBlank { null }, inboxDay(row.str("capturedAt"), withTime = true)),
    row.str("sessionId"), row.str("recordingId"),
    otherRecordingId = row.str("otherRecordingId") ?: row.strings("otherRecordingIds").firstOrNull(),
    part2OfRecordingId = row.str("part2OfRecordingId")
)

internal fun legacyRow(row: JsonObject) = InboxRow(
    InboxSection.LEGACY, "legacy:${row.str("recordingId")}",
    line(QuestionnaireCopy.s("inbox.legacy"), inboxDay(row.str("capturedAt"), withTime = true)), row.str("sessionId"), row.str("recordingId"),
    candidates = (row["candidates"] as? JsonArray).orEmpty().mapNotNull { c ->
        val o = c as? JsonObject ?: return@mapNotNull null
        val lineage = o.str("questionLineageId") ?: return@mapNotNull null
        LegacyCandidate(lineage, o.str("prompt") ?: o.str("displayCode") ?: lineage)
    }
)

internal fun setDatesRow(row: JsonObject): InboxRow {
    val range = listOfNotNull(inboxDay(row.str("from")), inboxDay(row.str("to"))).distinct().joinToString("–")
    val count = row.str("count")?.toIntOrNull() ?: row.strings("sessionIds").size
    val text = line(row.str("workshop"), QuestionnaireCopy.t("legacySessionsRange", "count" to count, "range" to range).removeSuffix(", "))
    return InboxRow(
        InboxSection.SET_DATES, "setDates:${row.str("workshopId")}:${row.strings("sessionIds").joinToString(",")}", text,
        row.strings("sessionIds").firstOrNull(), sessionIds = row.strings("sessionIds"), dateFrom = row.str("from")
    )
}

internal fun noParticipantsRow(row: JsonObject) = InboxRow(
    InboxSection.NO_PARTICIPANTS, "noParticipants:${row.str("sessionId")}",
    row.str("title") ?: QuestionnaireCopy.t("sessionHasNoArtisans", "date" to (inboxDay(row.str("date")) ?: "")),
    row.str("sessionId")
)

internal fun emptySessionRow(row: JsonObject) = InboxRow(
    InboxSection.EMPTY, "emptySessions:${row.str("sessionId")}",
    line(row.str("title"), inboxDay(row.str("approvedAt"))?.let { QuestionnaireCopy.t("emptyApproved", "date" to it) }),
    row.str("sessionId")
)

internal fun primaryChangedRow(row: JsonObject) = InboxRow(
    InboxSection.PRIMARY_CHANGED, "primaryChanged:${row.str("eventId")}",
    line(QuestionnaireCopy.s("inbox.primaryChanged"), inboxDay(row.str("at"), withTime = true)), row.str("sessionId"), row.str("recordingId")
)

/**
 * "Keep one…" needs the other take (audit H13). The server names it on the row when it can; else it
 * is the live take in the same session that shares a target and an answering artisan with this one,
 * the nearest in time.
 */
internal fun otherTakeOf(recordingId: String, named: String?, recordings: List<QRecording>): QRecording? {
    named?.let { id -> recordings.firstOrNull { it.id == id }?.let { return it } }
    val self = recordings.firstOrNull { it.id == recordingId } ?: return null
    val keys = self.targets.map { it.targetKey }.toSet()
    val who = self.subjects.filter { it.role == SubjectRole.ANSWERED.wire }.map { it.artisanId }.toSet()
    val at = self.capturedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    return recordings.filter { r ->
        r.id != recordingId && r.removedAt == null && r.targets.any { it.targetKey in keys } &&
            (who.isEmpty() || r.subjects.any { it.role == SubjectRole.ANSWERED.wire && it.artisanId in who })
    }.minByOrNull { r ->
        val t = r.capturedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (at == null || t == null) Long.MAX_VALUE else kotlin.math.abs(t.toEpochMilli() - at.toEpochMilli())
    }
}

/**
 * Part 2 continues the earlier take: the later one is the continuation. Returns (continuation, parent).
 */
internal fun partTwoOrder(self: QRecording, other: QRecording): Pair<String, String> {
    val a = self.capturedAt.orEmpty(); val b = other.capturedAt.orEmpty()
    return if (a >= b) self.id to other.id else other.id to self.id
}

private const val PREFS = "questionnaire-v2"
private const val DISMISSED = "inboxDismissed"

/** "Not needed", "Keep" and "OK" only acknowledge, so they are remembered on this phone (as on the web). */
internal fun readDismissed(context: Context): Set<String> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(DISMISSED, emptySet()).orEmpty()

private fun writeDismissed(context: Context, keys: Set<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(DISMISSED, keys).apply()
}

/**
 * The inbox (DESIGN §4.7). Every row carries its own action, in place, like the web: To file (Not
 * needed · File it), Needs a decision (Keep both · Keep one… with Part 2), Legacy question (pills ·
 * Confirm), Set dates (calendar · Set date), Who was there (artisans · Save), Empty session (Keep ·
 * Delete), Primary changed (OK). A row with a session also opens it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InboxScreen(
    v2: QuestionnaireV2,
    artisans: List<QArtisanOption>,
    loadInstrument: suspend (questionnaireId: String) -> Instrument?,
    onError: (String) -> Unit,
    onOpenSession: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mayDelete = LocalMayDelete.current
    var inbox by remember { mutableStateOf<QInbox?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var dismissed by remember { mutableStateOf(readDismissed(context)) }
    val rowError = remember { mutableStateMapOf<String, String>() }
    var keepOne by remember { mutableStateOf<InboxRow?>(null) }
    var filing by remember { mutableStateOf<InboxRow?>(null) }
    var deleting by remember { mutableStateOf<InboxRow?>(null) }
    LaunchedEffect(attempt) {
        loading = true
        runCatching { v2.api.inbox() }.onSuccess { inbox = it }.onFailure { onError(QuestionnaireCopy.s("inbox.error")) }
        loading = false
    }

    fun dismiss(row: InboxRow) {
        dismissed = dismissed + row.key
        writeDismissed(context, dismissed)
    }

    fun run(row: InboxRow, work: suspend () -> Unit) {
        if (busy != null) return
        busy = row.key
        rowError.remove(row.key)
        scope.launch {
            runCatching { work() }.onFailure { rowError[row.key] = it.userSentence() }
            busy = null
            attempt++
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(QuestionnaireCopy.s("inbox.title"), style = MaterialTheme.typography.titleLarge)
        if (loading) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
        val sections = inbox?.let { inboxSections(it, dismissed) }.orEmpty()
        if (!loading && inbox != null && sections.isEmpty()) Text(QuestionnaireCopy.s("inbox.nothing"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
        sections.forEach { (heading, rows) ->
            Text("$heading (${rows.size})", style = MaterialTheme.typography.titleMedium)
            rows.forEach { row ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        row.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.sessionId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = row.sessionId?.let { id -> Modifier.clickable { onOpenSession(id) } } ?: Modifier
                    )
                    when (row.section) {
                        InboxSection.TO_FILE -> PromptButtons(listOfNotNull(
                            PromptButton(QuestionnaireCopy.s("inbox.notNeeded")) {
                                // Kept on the server so every device agrees; hidden here at once either way.
                                dismiss(row)
                                row.mediaId?.let { id -> scope.launch { runCatching { v2.api.mediaNotNeeded(id) } } }
                            },
                            row.mediaId?.let { PromptButton(QuestionnaireCopy.s("inbox.fileIt"), primary = true) { filing = row } }
                        ))
                        InboxSection.NEEDS_DECISION -> if (row.recordingId != null) PromptButtons(listOf(
                            PromptButton(QuestionnaireCopy.s("inbox.keepBoth")) {
                                run(row) {
                                    val other = row.sessionId?.let { sid -> otherTakeOf(row.recordingId, row.otherRecordingId, v2.api.session(sid).recordings) }
                                    v2.api.decide(row.recordingId, QDecideRequest(DecideAction.KEEP_BOTH.wire, other?.id))
                                }
                            },
                            PromptButton(QuestionnaireCopy.s("inbox.keepOne"), primary = true) { keepOne = row }
                        ))
                        InboxSection.LEGACY -> LegacyPick(row, busy == row.key) { lineage ->
                            run(row) {
                                val sid = row.sessionId ?: error(QuestionnaireCopy.s("record.error"))
                                val version = v2.api.session(sid).recordings.firstOrNull { it.id == row.recordingId }?.version ?: error(QuestionnaireCopy.s("record.error"))
                                v2.api.patchRecording(row.recordingId!!, QRecordingPatch(version, targets = listOf(QTargetRef(questionLineageId = lineage))))
                                // Filing the take settles the decision: KEEP_BOTH with no other take files just this one.
                                v2.api.decide(row.recordingId, QDecideRequest(DecideAction.KEEP_BOTH.wire))
                            }
                        }
                        InboxSection.SET_DATES -> SetDates(row, busy == row.key) { date ->
                            run(row) {
                                for (id in row.sessionIds) {
                                    val detail = v2.api.session(id)
                                    v2.api.patchSession(id, QSessionPatch(version = detail.version, sessionDate = date.toString()))
                                }
                            }
                        }
                        InboxSection.NO_PARTICIPANTS -> WhoWasThere(artisans, busy == row.key) { ids ->
                            run(row) { ids.forEach { v2.api.addParticipant(SessionOutbox.newKey(), row.sessionId!!, QArtisanRef(it)) } }
                        }
                        InboxSection.EMPTY -> PromptButtons(listOfNotNull(
                            PromptButton(QuestionnaireCopy.s("inbox.keep")) { dismiss(row) },
                            // Deleting a session is a content admin's act (owner, 2026-10-21).
                            if (mayDelete && row.sessionId != null) PromptButton(QuestionnaireCopy.s("inbox.delete"), destructive = true) { deleting = row } else null
                        ))
                        InboxSection.PRIMARY_CHANGED -> PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("inbox.ok"), primary = true) { dismiss(row) }))
                    }
                    rowError[row.key]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                HorizontalDivider(color = MaterialTheme.field.hairline)
            }
        }
        if (!loading && inbox == null) PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("record.tryAgain"), primary = true) { attempt++ }))
    }

    keepOne?.let { row ->
        KeepOnePrompt(
            body = row.text,
            mayRemove = mayDelete,
            onDismiss = { keepOne = null },
            onChoose = { choice ->
                keepOne = null
                run(row) {
                    val sid = row.sessionId ?: error(QuestionnaireCopy.s("inbox.noOtherTake"))
                    val takes = v2.api.session(sid).recordings
                    val self = takes.firstOrNull { it.id == row.recordingId } ?: error(QuestionnaireCopy.s("inbox.noOtherTake"))
                    val other = otherTakeOf(self.id, row.otherRecordingId, takes) ?: error(QuestionnaireCopy.s("inbox.noOtherTake"))
                    when (choice) {
                        KeepOneChoice.THIS -> v2.api.decide(self.id, QDecideRequest(DecideAction.KEEP_THIS.wire, other.id))
                        KeepOneChoice.OTHER -> v2.api.decide(other.id, QDecideRequest(DecideAction.KEEP_THIS.wire, self.id))
                        KeepOneChoice.PART_TWO -> {
                            // The server's word first: this take continues the one it names.
                            val (continuation, parent) = row.part2OfRecordingId?.let { self.id to it } ?: partTwoOrder(self, other)
                            v2.api.decide(continuation, QDecideRequest(DecideAction.CONTINUATION.wire, parent))
                        }
                    }
                }
            }
        )
    }
    filing?.let { row ->
        FileItSheet(
            v2 = v2, row = row, artisans = artisans, loadInstrument = loadInstrument,
            onDismiss = { filing = null },
            onFile = { body ->
                filing = null
                run(row) { v2.api.fileMedia(row.mediaId!!, body) }
            }
        )
    }
    deleting?.let { row ->
        QuestionnairePrompt(
            title = QuestionnaireCopy.s("session.deleteTitle"),
            body = QuestionnaireCopy.s("session.deleteBody"),
            buttons = listOf(
                PromptButton(QuestionnaireCopy.s("session.cancel")) { deleting = null },
                PromptButton(QuestionnaireCopy.s("session.delete"), primary = true, destructive = true) {
                    deleting = null
                    run(row) {
                        val response = v2.api.deleteSession(row.sessionId!!)
                        if (!response.isSuccessful) throw retrofit2.HttpException(response)
                    }
                }
            ),
            onDismiss = { deleting = null }
        )
    }
}

internal enum class KeepOneChoice { THIS, OTHER, PART_TWO }

/**
 * "Keep one…" (DESIGN §4.7): keep this take or the other, or file this one as Part 2 of the earlier
 * one ("More"). Keeping one removes the other, which is a content admin's act; Part 2 is anyone's.
 */
@Composable
internal fun KeepOnePrompt(body: String, mayRemove: Boolean, onDismiss: () -> Unit, onChoose: (KeepOneChoice) -> Unit) {
    QuestionnairePrompt(
        title = QuestionnaireCopy.s("inbox.keepOne").removeSuffix("…"),
        body = body,
        buttons = buildList {
            if (mayRemove) {
                add(PromptButton(QuestionnaireCopy.s("inbox.keepThis"), primary = true) { onChoose(KeepOneChoice.THIS) })
                add(PromptButton(QuestionnaireCopy.s("inbox.keepOther")) { onChoose(KeepOneChoice.OTHER) })
            }
            add(PromptButton(QuestionnaireCopy.s("inbox.partTwo"), primary = !mayRemove) { onChoose(KeepOneChoice.PART_TWO) })
        },
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegacyPick(row: InboxRow, busy: Boolean, onConfirm: (String) -> Unit) {
    if (row.candidates.isEmpty()) return
    var pick by remember(row.key) { mutableStateOf(row.candidates.singleOrNull()?.lineageId.orEmpty()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.candidates.forEach { c -> FilterChip(selected = pick == c.lineageId, onClick = { pick = c.lineageId }, label = { Text(c.prompt) }) }
    }
    PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("inbox.confirm"), primary = true) { if (pick.isNotBlank() && !busy) onConfirm(pick) }))
}

@Composable
private fun SetDates(row: InboxRow, busy: Boolean, onSet: (LocalDate) -> Unit) {
    var date by remember(row.key) { mutableStateOf(row.dateFrom?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }) }
    CalendarField(label = QuestionnaireCopy.s("start.date"), value = date, onValueChange = { date = it })
    PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("inbox.setDate"), primary = true) { date?.takeIf { !busy }?.let(onSet) }))
}

@Composable
private fun WhoWasThere(artisans: List<QArtisanOption>, busy: Boolean, onSave: (List<String>) -> Unit) {
    var ids by remember { mutableStateOf(emptySet<String>()) }
    SearchableMultiSelectField(
        label = QuestionnaireCopy.s("start.artisans"),
        options = artisans.map { SelectOption(it.id, it.name, it.place.ifBlank { null }) },
        selected = ids
    ) { ids = it }
    PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("inbox.save"), primary = true) { if (ids.isNotEmpty() && !busy) onSave(ids.toList()) }))
}

/**
 * "File it" (audit H14, DESIGN §4.7): a searchable select for the session, the target and who
 * answered, with the server's candidate preselected. The clip becomes a take of that session.
 */
@Composable
private fun FileItSheet(
    v2: QuestionnaireV2,
    row: InboxRow,
    artisans: List<QArtisanOption>,
    loadInstrument: suspend (String) -> Instrument?,
    onDismiss: () -> Unit,
    onFile: (QFileMediaRequest) -> Unit
) {
    var sessions by remember { mutableStateOf<List<QSession>>(emptyList()) }
    var sessionId by remember { mutableStateOf(row.candidateSessionId.orEmpty()) }
    var detail by remember { mutableStateOf<QSessionDetail?>(null) }
    var instrument by remember { mutableStateOf<Instrument?>(null) }
    var target by remember { mutableStateOf(row.candidateTargetKey.orEmpty()) }
    var who by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) { sessions = runCatching { v2.api.sessions(limit = 100).items }.getOrDefault(emptyList()) }
    LaunchedEffect(sessionId) {
        if (sessionId.isBlank()) return@LaunchedEffect
        detail = runCatching { v2.api.session(sessionId) }.getOrNull()
        instrument = detail?.questionnaire?.id?.let { runCatching { loadInstrument(it) }.getOrNull() }
        who = detail?.participants?.map { it.artisanId }?.toSet().orEmpty()
    }
    val names = artisans.associate { it.id to it.name }
    QuestionnairePromptContent(
        title = QuestionnaireCopy.s("inbox.fileTitle"),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(row.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                val options = (sessions.map { SelectOption(it.id, it.title.ifBlank { it.id }, it.sessionDate?.take(10)) } +
                    listOfNotNull(detail?.takeIf { d -> sessions.none { it.id == d.id } }?.let { SelectOption(it.id, it.title.ifBlank { it.id }) }))
                SearchableSelectField(
                    label = QuestionnaireCopy.s("inbox.fileSession"), options = options, selectedValue = sessionId, includeNone = false, searchable = true
                ) { sessionId = it; target = "" }
                instrument?.let { ins ->
                    val targets = ins.sections.flatMap { s -> listOf(SelectOption("S:${s.id}", "Section ${s.code} · ${s.title}")) + s.questions.map { SelectOption("Q:${it.lineageId}", "${it.displayCode} · ${it.prompt}") } }
                    SearchableSelectField(
                        label = QuestionnaireCopy.s("inbox.fileTarget"), options = targets, selectedValue = target, includeNone = false, searchable = true
                    ) { target = it }
                }
                detail?.participants?.takeIf { it.isNotEmpty() }?.let { people ->
                    SearchableMultiSelectField(
                        label = QuestionnaireCopy.s("inbox.fileWho"),
                        options = people.map { SelectOption(it.artisanId, it.name.ifBlank { names[it.artisanId].orEmpty() }) },
                        selected = who
                    ) { who = it }
                }
            }
        },
        buttons = listOf(
            PromptButton(QuestionnaireCopy.s("session.cancel"), onClick = onDismiss),
            PromptButton(QuestionnaireCopy.s("inbox.fileIt"), primary = true) {
                if (sessionId.isBlank()) return@PromptButton
                val ref = when {
                    target.startsWith("Q:") -> QTargetRef(questionLineageId = target.removePrefix("Q:"))
                    target.startsWith("S:") -> QTargetRef(sectionId = target.removePrefix("S:"))
                    else -> null
                }
                val everyone = detail?.participants?.map { it.artisanId }?.toSet().orEmpty()
                onFile(QFileMediaRequest(
                    sessionId = sessionId, targets = listOfNotNull(ref),
                    // Everyone there answered unless a subset was picked; the server's default then.
                    subjects = who.takeIf { it.isNotEmpty() && it != everyone }?.map { QSubjectPatch(it, SubjectRole.ANSWERED.wire) }
                ))
            }
        )
    )
}

/** The server's sentence for a refusal, else a plain one; never a stack trace. */
internal fun Throwable.userSentence(): String {
    (this as? retrofit2.HttpException)?.response()?.errorBody()?.string()?.let { body ->
        runCatching { com.fieldrepository.app.questionnaire.portal.QuestionnairePortal.json.decodeFromString(com.fieldrepository.app.questionnaire.data.QErrorBody.serializer(), body).detail?.message }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    if (this is java.io.IOException) return QuestionnaireCopy.s("error.needsSignal")
    return message?.takeIf { it.isNotBlank() && this is IllegalStateException } ?: QuestionnaireCopy.s("upload.waiting")
}
