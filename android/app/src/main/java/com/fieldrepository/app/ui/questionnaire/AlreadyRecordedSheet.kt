package com.fieldrepository.app.ui.questionnaire

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.ui.field

/** What the researcher chose on P2. */
sealed interface P2Choice {
    data class Again(val reason: String) : P2Choice
    data object PartTwo : P2Choice
    data object Replace : P2Choice
    data object DifferentQuestion : P2Choice
}

/**
 * P2, "Already recorded" (DESIGN §4.4). Fires on Record press or file pick, from the server's
 * `coverage/check` or, offline, the same shape from [com.fieldrepository.app.data.CoverageCache].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AlreadyRecordedSheet(model: P2Model, onDismiss: () -> Unit, onChoose: (P2Choice) -> Unit) {
    var reason by remember(model) { mutableStateOf(model.preselected) }
    var more by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(model.title, style = MaterialTheme.typography.titleLarge)
            Text(model.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
            model.lines.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
            model.firstTakes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
            Text(QuestionnaireCopy.s("p2.why"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                model.reasons.forEach { r ->
                    FilterChip(selected = reason == r, onClick = { reason = r }, label = { Text(QuestionnaireCopy.reasonLabel(r)) })
                }
            }
            PromptButtons(
                listOf(
                    PromptButton(QuestionnaireCopy.s("p2.cancel"), onClick = onDismiss),
                    PromptButton(model.primaryButton, primary = true) { onChoose(P2Choice.Again(reason)) }
                )
            )
            TextButton(onClick = { more = !more }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(QuestionnaireCopy.s("p2.more"))
            }
            if (more) {
                // Three or more choices: stacked on a phone.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (model.offerPartTwo) TextButton(onClick = { onChoose(P2Choice.PartTwo) }, modifier = Modifier.fillMaxWidth()) { Text(QuestionnaireCopy.s("p2.partTwo")) }
                    if (model.offerReplace) TextButton(onClick = { onChoose(P2Choice.Replace) }, modifier = Modifier.fillMaxWidth()) { Text(QuestionnaireCopy.s("p2.replace")) }
                    TextButton(onClick = { onChoose(P2Choice.DifferentQuestion) }, modifier = Modifier.fillMaxWidth()) { Text(QuestionnaireCopy.s("p2.different")) }
                }
            }
        }
    }
}
