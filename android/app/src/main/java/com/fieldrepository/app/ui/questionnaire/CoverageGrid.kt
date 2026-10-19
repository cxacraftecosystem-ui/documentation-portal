package com.fieldrepository.app.ui.questionnaire

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.ui.field
import com.fieldrepository.app.ui.isCompactWidth

/**
 * The session coverage grid (DESIGN §4.3). Wide windows show one column per artisan; a phone shows
 * one summary per section ("D  Process  2/3 ›"), and a tap opens the per-artisan take sheet.
 */
@Composable
fun CoverageGrid(
    rows: List<GridRow>,
    names: Map<String, String>,
    currentSectionId: String?,
    onOpenSection: (sectionId: String) -> Unit,
    onOpenCell: (sectionId: String, artisanId: String) -> Unit
) {
    val compact = isCompactWidth()
    Column(Modifier.fillMaxWidth()) {
        if (compact) {
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenSection(row.section.id) }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(row.section.code, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(28.dp))
                    Text(row.section.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(row.summary + " ›", style = MaterialTheme.typography.bodyMedium, color = if (row.section.id == currentSectionId) MaterialTheme.colorScheme.primary else MaterialTheme.field.body)
                }
                HorizontalDivider(color = MaterialTheme.field.hairline)
            }
        } else {
            val scroll = rememberScrollState()
            Column(Modifier.horizontalScroll(scroll)) {
                Row(Modifier.padding(vertical = 6.dp)) {
                    Text("Section", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.field.muted, modifier = Modifier.width(200.dp))
                    rows.firstOrNull()?.cells?.forEach { c ->
                        Text(names[c.artisanId].orEmpty().substringBefore(' '), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.field.muted, modifier = Modifier.width(96.dp))
                    }
                }
                rows.forEach { row ->
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            "${row.section.code}  ${row.section.title}", style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(200.dp).clickable { onOpenSection(row.section.id) }, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        row.cells.forEach { cell ->
                            Text(
                                cell.glyph,
                                style = MaterialTheme.typography.bodyMedium,
                                color = when (cell.mark) {
                                    CellMark.NEEDS_DECISION -> MaterialTheme.colorScheme.tertiary
                                    CellMark.NONE, CellMark.VIA_SECTION -> MaterialTheme.field.muted
                                    else -> MaterialTheme.field.body
                                },
                                modifier = Modifier.width(96.dp).clickable { onOpenCell(row.section.id, cell.artisanId) }
                            )
                        }
                        if (row.section.id == currentSectionId) Text("← this sitting", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted)
                    }
                }
            }
            Text("● recorded  ●2 twice  ◐ partial  ◎ uploading  ⚠ needs decision  ○ none", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted)
        }
    }
}
