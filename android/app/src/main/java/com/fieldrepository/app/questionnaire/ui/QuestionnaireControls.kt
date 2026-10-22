package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.QWorkshopOption
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field
import java.time.Instant

/**
 * The few controls the questionnaire screens draw that the two portals' own `ui/` trees do not both
 * have under one name: the segmented choice, the compact-width test, and the default workshop.
 * Everything else (the searchable selects, the calendar, the toggle, the action row) is the
 * portal's own primitive, which both portals carry with the same signature.
 */

/**
 * A short choice between named modes, every option visible: two to four options in equal cells on one
 * line, 48dp tall (the web's `SegmentedChoice`). Anything longer, or built from records, stays a
 * searchable select.
 */
@Composable
fun SegmentedChoice(
    label: String,
    options: List<SelectOption>,
    selectedValue: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotBlank()) Text(label, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .selectableGroup()
        ) {
            options.forEach { option ->
                val on = option.value == selectedValue
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .background(if (on) primary else Color.Transparent)
                        .selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = { if (!on) onSelect(option.value) })
                        .padding(horizontal = 6.dp)
                ) {
                    Text(
                        option.label,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

/** True when the window is in the compact width class (< 600dp), as on a phone held upright. */
@Composable
fun isCompactWidth(): Boolean {
    val widthPx = LocalWindowInfo.current.containerSize.width
    if (widthPx <= 0) return true
    val widthDp = with(LocalDensity.current) { widthPx.toDp() }
    return widthDp < 600.dp
}

/**
 * The start sheet's default workshop (B30): the one the server names for this account when it named
 * one in the list, else the most recently changed. Reads only the list it is given, so it holds
 * offline from the cached copy.
 */
fun mostRecentRecordableWorkshop(list: List<QWorkshopOption>, preferredId: String? = null): QWorkshopOption? =
    list.firstOrNull { it.id == preferredId }
        ?: list.maxByOrNull { w -> w.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH }

/** What Discard says before it deletes a queued step from this phone. */
fun outboxDiscardSentence(label: String, files: Int, clash: Boolean): String {
    val filesPart = when (files) {
        0 -> ""
        1 -> " and its recording"
        else -> " and its $files recordings"
    }
    val clashPart = if (clash) " The copy already on the server is not touched." else ""
    return "“$label”$filesPart will be deleted from this phone. This can't be undone.$clashPart"
}
