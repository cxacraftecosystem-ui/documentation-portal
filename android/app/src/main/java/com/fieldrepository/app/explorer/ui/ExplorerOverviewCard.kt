package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
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
    const val artisans = "least complete first"
    const val complete = "complete"
    const val partial = "partly done"
    const val missing = "missing"
    const val showAll = "Show all"
    const val showFewer = "Show fewer"
    const val open = "Open"
    const val newSinceLabel = "New since you looked"
    const val artisansTitle = "Artisans · what's been captured"
    const val completeLabel = "Complete"
    const val partialLabel = "Partial"
    const val missingLabel = "Missing"
    const val note = "Tap a square to jump to that section."

    fun newSinceSub(files: Int, transcripts: Int) = listOfNotNull(
        files.takeIf { it > 0 }?.let { "$it files" },
        transcripts.takeIf { it > 0 }?.let { "$it transcripts" },
    ).joinToString(" · ").ifBlank { "files" }

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
    val (bg, fg) = explorerMark()
    Box(Modifier.background(bg, ExplorerShapes.pill).padding(horizontal = 10.dp, vertical = 4.dp).testTag("explorer.newsince")) {
        Text(OverviewText.newSince(n), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

private class TileData(val label: String, val value: String, val sub: String?, val accent: Boolean = false, val tag: String? = null)

@Composable
private fun Tile(label: String, value: String, sub: String?, modifier: Modifier, accent: Boolean = false, tag: String? = null, onSub: (() -> Unit)? = null) {
    val (markBg, markFg) = explorerMark()
    val line = if (accent) (if (MaterialTheme.field.isDark) com.fieldrepository.app.ui.FieldPalette.Purple800 else com.fieldrepository.app.ui.FieldPalette.Purple300) else MaterialTheme.field.hairline
    Column(
        modifier.explorerCard(if (accent) markBg else MaterialTheme.colorScheme.surface, line, ExplorerShapes.inner).padding(12.dp).let { if (tag != null) it.testTag(tag) else it },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (accent) markFg else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.bodySmall, color = if (accent) markFg else MaterialTheme.colorScheme.onSurfaceVariant, modifier = if (onSub != null) Modifier.clickable(role = Role.Button, onClick = onSub) else Modifier)
        }
    }
}

@Composable
private fun squareColors(state: String): Pair<Color, Color?> {
    val dark = MaterialTheme.field.isDark
    return when (state) {
        "complete" -> MaterialTheme.colorScheme.primary to null
        "partial" -> (if (dark) com.fieldrepository.app.ui.FieldPalette.Purple800 else com.fieldrepository.app.ui.FieldPalette.Purple200) to null
        else -> explorerTone(ExplorerStatus.FAILED)!!.let { it.container to it.border }
    }
}

@Composable
private fun Legend(state: String, label: String) {
    val (fill, line) = squareColors(state)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).background(fill, CircleShape).let { if (line != null) it.border(1.dp, line, CircleShape) else it })
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OverviewCard(overview: ExplorerOverview, onOpen: (path: String) -> Unit, onSection: ((artisanId: String, sectionKey: String) -> Unit)? = null, visit: ExplorerVisit? = null) {
    var all by remember(overview.path) { mutableStateOf(false) }
    val t = overview.totals
    val large = LocalDensity.current.fontScale >= 1.5f
    Column(
        Modifier.fillMaxWidth().explorerCard().padding(14.dp).testTag("explorer.overview"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(OverviewText.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        val tiles = listOfNotNull(
            TileData(OverviewText.files, String.format(java.util.Locale.ROOT, "%,d", t.files), ExplorerBytes.format(t.bytes.takeIf { it > 0 })),
            TileData(OverviewText.fields, OverviewText.percent(t.fields.filled, t.fields.total), "${t.fields.filled} of ${t.fields.total}"),
            TileData(OverviewText.transcripts, String.format(java.util.Locale.ROOT, "%,d", t.transcripts.total.takeIf { it > 0 } ?: t.transcripts.ready), OverviewText.transcriptLine(t.transcripts.ready, t.transcripts.translating, t.transcripts.failed)),
            TileData(OverviewText.review, "${t.review.approved} / ${t.review.total.takeIf { it > 0 } ?: (t.review.approved + t.review.pending)}", OverviewText.reviewLine(t.review.approved, t.review.pending)),
            newSinceCount(visit).takeIf { it > 0 }?.let { n -> TileData(OverviewText.newSinceLabel, "+$n", OverviewText.newSinceSub(visit?.newFiles ?: 0, visit?.newTranscripts ?: 0), accent = true, tag = "explorer.overview.new") },
        )
        val perRow = if (large) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { d -> Tile(d.label, d.value, d.sub, Modifier.weight(1f).fillMaxHeight(), accent = d.accent, tag = d.tag) }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (overview.artisans.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(OverviewText.artisansTitle, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Legend("complete", OverviewText.completeLabel)
                    Legend("partial", OverviewText.partialLabel)
                    Legend("missing", OverviewText.missingLabel)
                    Text("· ${OverviewText.artisans}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val sorted = overview.artisans.sortedBy { it.completeness }
            val shown = if (all) sorted else sorted.take(6)
            Column(Modifier.fillMaxWidth().clip(ExplorerShapes.inner).border(1.dp, MaterialTheme.field.hairline, ExplorerShapes.inner)) {
                shown.forEachIndexed { index, a -> ArtisanRow(a, overview, onOpen, onSection, last = index == shown.lastIndex) }
            }
            if (sorted.size > 6) {
                ExplorerLinkButton(if (all) OverviewText.showFewer else "${OverviewText.showAll} ${sorted.size}", onClick = { all = !all })
            }
            if (onSection != null) Text(OverviewText.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun reviewTint(review: String?): Color? {
    val v = review?.lowercase() ?: return null
    return when {
        v.startsWith("approved") -> explorerTone(ExplorerStatus.SAVED)!!.accent
        v.startsWith("rejected") -> explorerTone(ExplorerStatus.FAILED)!!.accent
        else -> explorerWarnTone().muted
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtisanRow(a: ExplorerOverviewArtisan, overview: ExplorerOverview, onOpen: (String) -> Unit, onSection: ((String, String) -> Unit)?, last: Boolean) {
    val line = MaterialTheme.field.hairline
    Column(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button) { onOpen(a.path) }
            .drawBehind { if (!last) drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("explorer.overview.artisan.${a.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                a.craft?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            a.review?.let { r -> Text(r, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = reviewTint(r) ?: MaterialTheme.colorScheme.onSurface) }
            ExplorerLinkButton(OverviewText.open, onClick = { onOpen(a.path) }, modifier = Modifier.padding(start = 4.dp))
        }
        if (a.sections.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                a.sections.forEach { s ->
                    val label = overview.sections.firstOrNull { it.key == s.key }?.label ?: s.key
                    val (fill, edge) = squareColors(s.state)
                    val word = when (s.state) {
                        "complete" -> OverviewText.complete
                        "partial" -> OverviewText.partial
                        else -> OverviewText.missing
                    }
                    Box(
                        Modifier.size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(fill, RoundedCornerShape(6.dp))
                            .let { m -> if (edge != null) m.border(1.dp, edge, RoundedCornerShape(6.dp)) else m }
                            .let { m -> if (onSection != null) m.clickable(role = Role.Button, onClickLabel = label) { onSection(a.id, s.key) } else m }
                            .semantics { contentDescription = "$label, $word" }
                            .testTag("explorer.overview.square.${a.id}.${s.key}"),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val ratio = a.completeness
            val fill = when {
                ratio >= 1f -> explorerTone(ExplorerStatus.SAVED)!!.accent
                ratio < 0.5f -> explorerTone(ExplorerStatus.FAILED)!!.accent
                else -> MaterialTheme.colorScheme.primary
            }
            Box(Modifier.width(120.dp)) { ExplorerBar(ratio, height = 6.dp, fill = fill) }
            Text("${a.fields.filled} / ${a.fields.total}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            listOfNotNull(ExplorerUiText.filesSize(a.media.files, a.media.bytes), a.transcripts.takeIf { it > 0 }?.let { ExplorerBytes.count(it, "transcript") }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
