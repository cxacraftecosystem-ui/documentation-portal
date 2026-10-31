package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.ChipFilter
import com.fieldrepository.app.questionnaire.data.ChipRules
import com.fieldrepository.app.questionnaire.data.DisplayChip
import com.fieldrepository.app.questionnaire.data.QChips
import com.fieldrepository.app.questionnaire.data.QuestionnaireFilters
import com.fieldrepository.app.ui.field

/**
 * A recording's chips (owner, 31 Oct 2026): section or question, form, who answered (+N, present
 * marked), dates (estimated), workshop, language and duration, in the order and wording of
 * `shared/questionnaire-chips.json`. Compact: one FlowRow of small chips that wraps.
 *
 * REUSABLE ANYWHERE A TAKE IS SHOWN, the transcript viewer's header included: it needs only the
 * server's `chips` object, and draws nothing for null.
 *
 * @param chips the take's (or session's) `chips` from the server; null draws nothing.
 * @param modifier applied to the row.
 * @param filters the filters in force, so a chip already applied reads as selected; null when the
 *   screen has none.
 * @param onChip called with a chip's filter (and its label) when it is tapped. Duration, "+N", a
 *   guest and "Whole session" are never tappable; "+N" opens to show the names it hides. Null makes
 *   every chip a plain label. [QuestionnaireV2Nav.openRecordings] is the usual target from a screen
 *   that has no list of its own: it opens the Recordings list filtered by the chip.
 * @param session draws a session's chips (no "Whole session" target).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordingChips(
    chips: QChips?,
    modifier: Modifier = Modifier,
    filters: QuestionnaireFilters? = null,
    onChip: ((ChipFilter, String) -> Unit)? = null,
    session: Boolean = false
) {
    val list = if (session) ChipRules.displaySession(chips) else ChipRules.display(chips)
    if (list.isEmpty()) return
    var opened by remember(chips) { mutableStateOf<String?>(null) }
    Column(modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.testTag("recording-chips")
        ) {
            list.forEach { chip ->
                val f = chip.filter
                if (f != null && onChip != null) {
                    FilterChip(
                        selected = filters?.isActive(f) == true,
                        onClick = { onChip(f, chip.label) },
                        label = { ChipText(chip) },
                        shape = RoundedCornerShape(8.dp),
                        colors = FilterChipDefaults.filterChipColors(),
                        modifier = Modifier.heightIn(min = 32.dp).testTag("chip-${chip.kind}")
                    )
                } else {
                    PlainChip(chip, onClick = chip.title?.let { title -> { opened = if (opened == title) null else title } })
                }
            }
        }
        opened?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun ChipText(chip: DisplayChip) {
    Text(chip.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun PlainChip(chip: DisplayChip, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(8.dp)
    val mod = Modifier.heightIn(min = 32.dp).testTag("chip-${chip.kind}").semantics { chip.title?.let { contentDescription = "${chip.label}: $it" } }
    val body: @Composable () -> Unit = {
        Text(
            chip.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.body, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = MaterialTheme.field.surface100, modifier = mod) { body() }
    else Surface(shape = shape, color = MaterialTheme.field.surface100, modifier = mod) { body() }
}
