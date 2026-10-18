package com.fieldrepository.app.ui.questionnaire

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.QInbox
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.data.QuestionnaireV2
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The inbox sections of DESIGN §4.7, in order, each with its server rows. */
internal fun inboxSections(inbox: QInbox): List<Pair<String, List<JsonObject>>> = listOf(
    QuestionnaireCopy.s("inbox.toFile") to inbox.toFile,
    QuestionnaireCopy.s("inbox.needsDecision") to inbox.needsDecision,
    QuestionnaireCopy.s("inbox.legacy") to inbox.legacy,
    QuestionnaireCopy.s("inbox.setDates") to inbox.setDates,
    QuestionnaireCopy.s("inbox.noParticipants") to inbox.noParticipants,
    QuestionnaireCopy.s("inbox.empty") to inbox.emptySessions,
    QuestionnaireCopy.s("inbox.primaryChanged") to inbox.primaryChanged
).filter { it.second.isNotEmpty() }

/** The server words each row ("f-sec-1718.webm · 18 min · 23 Jun"); the client never invents it. */
internal fun inboxRowText(row: JsonObject): String =
    listOf("text", "label", "title", "summary").firstNotNullOfOrNull { (row[it] as? JsonPrimitive)?.content?.ifBlank { null } } ?: ""

@Composable
fun InboxScreen(v2: QuestionnaireV2, onError: (String) -> Unit) {
    var inbox by remember { mutableStateOf<QInbox?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loading = true
        runCatching { v2.api.inbox() }.onSuccess { inbox = it }.onFailure { onError("Couldn't load the inbox.") }
        loading = false
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(QuestionnaireCopy.s("inbox.title"), style = MaterialTheme.typography.titleLarge)
        if (loading) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
        val sections = inbox?.let(::inboxSections).orEmpty()
        if (!loading && sections.isEmpty()) Text(QuestionnaireCopy.s("inbox.nothing"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
        sections.forEach { (heading, rows) ->
            Text("$heading (${rows.size})", style = MaterialTheme.typography.titleMedium)
            rows.forEach { row ->
                Text(inboxRowText(row), style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(color = MaterialTheme.field.hairline)
            }
        }
        if (!loading && inbox == null) PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("record.tryAgain"), primary = true) { attempt++ }))
    }
}
