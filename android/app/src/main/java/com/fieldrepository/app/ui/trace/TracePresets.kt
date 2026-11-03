package com.fieldrepository.app.ui.trace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.ActionRow
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field

fun traceStyleOptions(styles: List<TracePreset>): List<SelectOption> = styles.map { style ->
    SelectOption(
        value = style.id,
        label = if (style.group.isBlank()) style.name else "${style.group} · ${style.name}",
        hint = style.description,
    )
}

fun traceSubjectOptions(subjects: List<TracePreset>): List<SelectOption> = subjects.map { subject ->
    SelectOption(value = subject.id, label = subject.name, hint = subject.description)
}

fun tracePresetName(presets: List<TracePreset>, id: String): String =
    presets.firstOrNull { it.id == id }?.name ?: id

@Composable
fun TraceStylePicker(
    styles: List<TracePreset>,
    selectedId: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = "Style",
            options = traceStyleOptions(styles),
            selectedValue = selectedId,
            placeholder = "Choose a style",
            includeNone = false,
            enabled = enabled,
        ) { picked -> if (picked.isNotBlank()) onPick(picked) }
        Text(
            styles.firstOrNull { it.id == selectedId }?.description
                ?: "A style sets every control at once. Pick one, then adjust.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun TraceSubjectPicker(
    subjects: List<TracePreset>,
    selectedId: String,
    enabled: Boolean,
    seededFromHost: Boolean,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = "What this is a drawing of",
            options = traceSubjectOptions(subjects),
            selectedValue = selectedId,
            placeholder = "Choose a material",
            includeNone = false,
            enabled = enabled,
        ) { picked -> if (picked.isNotBlank()) onPick(picked) }
        Text(
            buildString {
                append(
                    subjects.firstOrNull { it.id == selectedId }?.description
                        ?: "Tunes for the material, keeping the style.",
                )
                if (seededFromHost) {
                    append(" Picked from this record.")
                }
            },
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TraceStyleSuggestion(
    styles: List<TracePreset>,
    suggestedStyleId: String,
    currentStyleId: String,
    enabled: Boolean,
    onApply: (String) -> Unit,
) {
    if (suggestedStyleId.isBlank() || suggestedStyleId == currentStyleId) return
    val suggested = styles.firstOrNull { it.id == suggestedStyleId } ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Suggested: “${suggested.name}”. " +
                suggested.description,
            color = MaterialTheme.field.body,
            style = MaterialTheme.typography.bodySmall,
        )
        ActionRow {
            OutlinedButton(
                onClick = { onApply(suggested.id) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Use the “${suggested.name}” style", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
