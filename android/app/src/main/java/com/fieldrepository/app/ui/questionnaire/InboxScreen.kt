package com.fieldrepository.app.ui.questionnaire

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.DecideAction
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.data.QDecideRequest
import com.fieldrepository.app.data.QInbox
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.data.QuestionnaireV2
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate

/** One inbox row as shown: its words, the session it opens, and the decisions it answers in place. */
internal data class InboxRow(val text: String, val sessionId: String?, val recordingId: String?, val decisions: List<Pair<String, String>>)

/** The inbox sections of DESIGN §4.7, in order, with the rows the server sent. */
internal fun inboxSections(inbox: QInbox): List<Pair<String, List<InboxRow>>> = listOf(
    QuestionnaireCopy.s("inbox.toFile") to inbox.toFile.map(::toFileRow),
    QuestionnaireCopy.s("inbox.needsDecision") to inbox.needsDecision.map(::decisionRow),
    QuestionnaireCopy.s("inbox.legacy") to inbox.legacy.map(::legacyRow),
    QuestionnaireCopy.s("inbox.setDates") to inbox.setDates.map(::setDatesRow),
    QuestionnaireCopy.s("inbox.noParticipants") to inbox.noParticipants.map(::noParticipantsRow),
    QuestionnaireCopy.s("inbox.empty") to inbox.emptySessions.map(::emptySessionRow),
    QuestionnaireCopy.s("inbox.primaryChanged") to inbox.primaryChanged.map(::primaryChangedRow)
).filter { it.second.isNotEmpty() }

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

internal fun toFileRow(row: JsonObject) = InboxRow(line(row.str("name"), inboxDay(row.str("date"))), row.str("sessionId"), null, emptyList())

internal fun decisionRow(row: JsonObject) = InboxRow(
    line(row.strings("targets").joinToString(", ").ifBlank { null }, row.strings("artisanNames").joinToString(", ").ifBlank { null }, inboxDay(row.str("capturedAt"), withTime = true)),
    row.str("sessionId"), row.str("recordingId"),
    listOf(QuestionnaireCopy.s("inbox.keepBoth") to DecideAction.KEEP_BOTH.wire, QuestionnaireCopy.s("inbox.keepOne") to DecideAction.KEEP_THIS.wire)
)

internal fun legacyRow(row: JsonObject) = InboxRow(
    line(QuestionnaireCopy.s("inbox.legacy"), inboxDay(row.str("capturedAt"), withTime = true)), row.str("sessionId"), row.str("recordingId"), emptyList()
)

internal fun setDatesRow(row: JsonObject): InboxRow {
    val range = listOfNotNull(inboxDay(row.str("from")), inboxDay(row.str("to"))).distinct().joinToString("–")
    val count = row.str("count")?.toIntOrNull() ?: row.strings("sessionIds").size
    val text = line(row.str("workshop"), QuestionnaireCopy.t("legacySessionsRange", "count" to count, "range" to range).removeSuffix(", "))
    return InboxRow(text, row.strings("sessionIds").firstOrNull(), null, emptyList())
}

internal fun noParticipantsRow(row: JsonObject) = InboxRow(
    row.str("title") ?: QuestionnaireCopy.t("sessionHasNoArtisans", "date" to (inboxDay(row.str("date")) ?: "")),
    row.str("sessionId"), null, emptyList()
)

internal fun emptySessionRow(row: JsonObject) = InboxRow(
    line(row.str("title"), inboxDay(row.str("approvedAt"))?.let { QuestionnaireCopy.t("emptyApproved", "date" to it) }),
    row.str("sessionId"), null, emptyList()
)

internal fun primaryChangedRow(row: JsonObject) = InboxRow(
    line(QuestionnaireCopy.s("inbox.primaryChanged"), inboxDay(row.str("at"), withTime = true)), row.str("sessionId"), row.str("recordingId"), emptyList()
)

/**
 * The inbox (DESIGN §4.7). A row opens the session where its work is done; a "Needs a decision" row
 * also answers in place (Keep both, Keep one…) through `POST /recordings/{id}/decide`, like the web.
 */
@Composable
fun InboxScreen(v2: QuestionnaireV2, onError: (String) -> Unit, onOpenSession: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var inbox by remember { mutableStateOf<QInbox?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loading = true
        runCatching { v2.api.inbox() }.onSuccess { inbox = it }.onFailure { onError(QuestionnaireCopy.s("inbox.error")) }
        loading = false
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(QuestionnaireCopy.s("inbox.title"), style = MaterialTheme.typography.titleLarge)
        if (loading) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
        val sections = inbox?.let(::inboxSections).orEmpty()
        if (!loading && inbox != null && sections.isEmpty()) Text(QuestionnaireCopy.s("inbox.nothing"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
        sections.forEach { (heading, rows) ->
            Text("$heading (${rows.size})", style = MaterialTheme.typography.titleMedium)
            rows.forEach { row ->
                Column(
                    Modifier.fillMaxWidth().let { m -> row.sessionId?.let { id -> m.clickable { onOpenSession(id) } } ?: m }.padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(row.text, style = MaterialTheme.typography.bodyMedium, color = if (row.sessionId != null) MaterialTheme.field.body else MaterialTheme.colorScheme.onSurface)
                    if (row.recordingId != null && row.decisions.isNotEmpty()) PromptButtons(row.decisions.map { (label, action) ->
                        PromptButton(label) {
                            if (busy) return@PromptButton
                            busy = true
                            scope.launch {
                                runCatching { v2.api.decide(row.recordingId, QDecideRequest(action)) }.onFailure { onError(QuestionnaireCopy.s("upload.waiting")) }
                                busy = false
                                attempt++
                            }
                        }
                    })
                }
                HorizontalDivider(color = MaterialTheme.field.hairline)
            }
        }
        if (!loading && inbox == null) PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("record.tryAgain"), primary = true) { attempt++ }))
    }
}
