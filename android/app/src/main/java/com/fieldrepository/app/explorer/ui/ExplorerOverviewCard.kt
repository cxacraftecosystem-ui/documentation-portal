package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.explorer.data.ExplorerBytes
import com.fieldrepository.app.explorer.data.ExplorerOverview
import com.fieldrepository.app.explorer.data.ExplorerOverviewArtisan
import com.fieldrepository.app.explorer.data.ExplorerVisit
import com.fieldrepository.app.ui.field

object OverviewText {
    const val title = "Workshop overview"
    const val files = "Files"
    const val fields = "Recorded fields"
    const val transcripts = "Transcripts"
    const val review = "Review"
    const val artisans = "Artisans, least complete first"
    const val complete = "complete"
    const val partial = "partly done"
    const val missing = "missing"
    const val showAll = "Show all"
    const val showFewer = "Show fewer"

    fun newSince(n: Int) = "New since you looked +$n"

    fun percent(filled: Int, total: Int) = if (total > 0) "${filled * 100 / total}%" else "—"

    fun transcriptLine(ready: Int, translating: Int, failed: Int) =
        listOfNotNull("$ready ready", translating.takeIf { it > 0 }?.let { "$it translating" }, failed.takeIf { it > 0 }?.let { "$it failed" }).joinToString(" · ")

    fun reviewLine(approved: Int, pending: Int) = "$approved approved · $pending pending"

    fun fieldsOf(filled: Int, total: Int) = "$filled of $total fields"
}

internal fun newSinceCount(visit: ExplorerVisit?): Int = visit?.takeIf { it.previousVisitAt != null }?.let { it.newFiles + it.newTranscripts } ?: 0

internal fun isWorkshopFolder(path: String): Boolean {
    val parts = path.split('/').filter { it.isNotBlank() }
    return parts.size == 2 && parts[0] == "by-workshop"
}

@Composable
internal fun NewSinceChip(visit: ExplorerVisit?) {
    val n = newSinceCount(visit)
    if (n <= 0) return
    val tone = explorerTone(ExplorerStatus.SAVED)!!
    Box(Modifier.background(tone.container, MaterialTheme.shapes.extraLarge).padding(horizontal = 10.dp, vertical = 4.dp).testTag("explorer.newsince")) {
        Text(OverviewText.newSince(n), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tone.accent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverviewCard(overview: ExplorerOverview, onOpen: (path: String) -> Unit, onSection: ((artisanId: String, sectionKey: String) -> Unit)? = null) {
    var all by remember(overview.path) { mutableStateOf(false) }
    val t = overview.totals
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large)
            .padding(14.dp)
            .testTag("explorer.overview"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(OverviewText.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(OverviewText.files, ExplorerUiText.filesSize(t.files, t.bytes))
            Tile(OverviewText.fields, OverviewText.percent(t.fields.filled, t.fields.total))
            Tile(OverviewText.transcripts, OverviewText.transcriptLine(t.transcripts.ready, t.transcripts.translating, t.transcripts.failed))
            Tile(OverviewText.review, OverviewText.reviewLine(t.review.approved, t.review.pending))
        }
        if (overview.artisans.isNotEmpty()) {
            Text(OverviewText.artisans, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val sorted = overview.artisans.sortedBy { it.completeness }
            val shown = if (all) sorted else sorted.take(6)
            shown.forEach { ArtisanRow(it, overview, onOpen, onSection) }
            if (sorted.size > 6) {
                TextButton(onClick = { all = !all }, modifier = Modifier.heightIn(min = 44.dp)) { Text(if (all) OverviewText.showFewer else "${OverviewText.showAll} ${sorted.size}") }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String) {
    Column(Modifier.widthIn(min = 140.dp).background(MaterialTheme.field.surface50, MaterialTheme.shapes.medium).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtisanRow(a: ExplorerOverviewArtisan, overview: ExplorerOverview, onOpen: (String) -> Unit, onSection: ((String, String) -> Unit)?) {
    val saved = explorerTone(ExplorerStatus.SAVED)!!
    val warn = explorerWarnTone()
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.medium)
            .clickable(role = Role.Button) { onOpen(a.path) }
            .padding(12.dp)
            .testTag("explorer.overview.artisan.${a.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(a.craft, OverviewText.fieldsOf(a.fields.filled, a.fields.total), ExplorerUiText.filesSize(a.media.files, a.media.bytes)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(OverviewText.percent(a.fields.filled, a.fields.total), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        if (a.sections.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                a.sections.forEach { s ->
                    val label = overview.sections.firstOrNull { it.key == s.key }?.label ?: s.key
                    val (color, word) = when (s.state) {
                        "complete" -> saved.accent to OverviewText.complete
                        "partial" -> warn.border to OverviewText.partial
                        else -> MaterialTheme.field.surface200 to OverviewText.missing
                    }
                    Box(
                        Modifier.size(if (onSection != null) 32.dp else 18.dp)
                            .background(color, MaterialTheme.shapes.extraSmall)
                            .let { m -> if (onSection != null) m.clickable(role = Role.Button, onClickLabel = label) { onSection(a.id, s.key) } else m }
                            .semantics { contentDescription = "$label, $word" }
                            .testTag("explorer.overview.square.${a.id}.${s.key}"),
                    )
                }
            }
        }
    }
}
