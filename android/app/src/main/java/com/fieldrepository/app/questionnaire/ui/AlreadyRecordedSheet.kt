package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.ui.field

sealed interface P2Choice {
    data class Again(val reason: String) : P2Choice
    data object PartTwo : P2Choice
    data object Replace : P2Choice
    data object DifferentQuestion : P2Choice
}

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
                    val picked = reason == r
                    FilterChip(
                        selected = picked, onClick = { reason = r },
                        label = { Text(QuestionnaireCopy.reasonLabel(r), fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            labelColor = MaterialTheme.field.body
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = picked,
                            borderColor = MaterialTheme.field.surface300,
                            selectedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
            PromptButtons(
                listOf(
                    PromptButton(QuestionnaireCopy.s("p2.cancel"), onClick = onDismiss),
                    PromptButton(model.primaryButton, primary = true) { onChoose(P2Choice.Again(reason)) }
                )
            )
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { more = !more },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        QuestionnaireCopy.s("p2.more"), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.field.body
                    )
                    Icon(if (more) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.field.muted)
                }
                if (more) {
                    if (model.offerPartTwo) MoreOption(QuestionnaireCopy.s("p2.partTwo"), MaterialTheme.colorScheme.onSurface) { onChoose(P2Choice.PartTwo) }
                    MoreOption(QuestionnaireCopy.s("p2.different"), MaterialTheme.colorScheme.onSurface) { onChoose(P2Choice.DifferentQuestion) }
                    if (model.offerReplace) MoreOption(QuestionnaireCopy.s("p2.replace"), MaterialTheme.colorScheme.error) { onChoose(P2Choice.Replace) }
                }
            }
        }
    }
}

@Composable
private fun MoreOption(label: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}
