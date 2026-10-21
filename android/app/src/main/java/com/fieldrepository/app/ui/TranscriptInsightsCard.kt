package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.TranscriptInsightDto
import com.fieldrepository.app.data.TranscriptInsightStates
import com.fieldrepository.app.data.TranscriptInsightViewDto
import kotlinx.coroutines.delay

/**
 * How often a queued summary is looked at again, and for how long. The web's numbers. Every card's
 * poll goes through the repository's batch, so cards that fall due together share one request.
 */
private const val POLL_MILLIS = 15_000L
private const val POLL_LIMIT_MILLIS = 10 * 60_000L

/**
 * Summary, key quotes and English translation under a transcript. READ-ONLY on the handset: editing
 * and regenerating are on the web. Draws nothing while loading, when the feature is off (the server
 * answers 404), offline, or when there is nothing to show.
 *
 * [load] reads the item through the screen-wide batch (no translation text), and polls through it;
 * [loadFull] reads the single item, for the translation when it is opened. [formatDate] is the
 * screen's own date format.
 */
@Composable
fun TranscriptInsightsCard(
    mediaId: String,
    load: suspend (String) -> TranscriptInsightViewDto?,
    loadFull: suspend (String) -> TranscriptInsightViewDto?,
    formatDate: (String?) -> String?
) {
    var view by remember(mediaId) { mutableStateOf<TranscriptInsightViewDto?>(null) }
    LaunchedEffect(mediaId) {
        view = load(mediaId)
        val started = System.currentTimeMillis()
        while (TranscriptInsightStates.isWorking(view) && System.currentTimeMillis() - started < POLL_LIMIT_MILLIS) {
            delay(POLL_MILLIS)
            load(mediaId)?.let { view = it }
        }
    }
    val current = view
    if (!TranscriptInsightStates.isShown(current) || current == null) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(SurfaceCard, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val insight = current.insight
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Summary", color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            if (insight != null) {
                EditedOrMachine(insight, formatDate)
            }
        }
        when {
            TranscriptInsightStates.isWorking(current) -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Summarising…", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            current.state == TranscriptInsightStates.FAILED -> Text(current.error.orEmpty(), color = ErrorInk, style = MaterialTheme.typography.bodySmall)
        }
        if (current.stale && insight != null) {
            Text("The transcript changed after this summary.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (insight != null && !TranscriptInsightStates.isWorking(current)) {
            InsightBody(current.mediaId.ifBlank { mediaId }, insight, loadFull)
        }
    }
}

@Composable
private fun EditedOrMachine(insight: TranscriptInsightDto, formatDate: (String?) -> String?) {
    val line = TranscriptInsightStates.provenanceLine(insight, formatDate)
    if (insight.edited != null) {
        Text(
            line,
            color = FieldPalette.Purple700,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .background(FieldPalette.Purple50, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    } else {
        Text(line, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InsightBody(mediaId: String, insight: TranscriptInsightDto, loadFull: suspend (String) -> TranscriptInsightViewDto?) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        insight.summaryLines.forEach { line -> Text("•  $line", color = Body, style = MaterialTheme.typography.bodyMedium) }
    }
    if (insight.quotes.isNotEmpty()) {
        Text("Key quotes", color = Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        insight.quotes.forEach { quote ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = Hairline, shape = RoundedCornerShape(6.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val speaker = quote.speaker?.let { "$it: " }.orEmpty()
                Text("$speaker“${quote.original}”", color = Body, style = MaterialTheme.typography.bodyMedium)
                if (quote.english.isNotBlank() && quote.english != quote.original) {
                    Text(quote.english, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    // The text the full read answered with, once it has; see `TranslationDisclosure`.
    var loaded by remember(insight.id, insight.version) { mutableStateOf<String?>(null) }
    when {
        TranscriptInsightStates.offersTranslation(insight, loaded) ->
            TranslationDisclosure(mediaId, insight, loaded, { loaded = it }, loadFull)
        insight.translation.status == "FAILED" -> Text(insight.translation.note ?: "Translation failed.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TranslationDisclosure(
    mediaId: String,
    insight: TranscriptInsightDto,
    loaded: String?,
    onLoaded: (String) -> Unit,
    loadFull: suspend (String) -> TranscriptInsightViewDto?
) {
    var open by remember(insight.id) { mutableStateOf(false) }
    // The batch leaves the text out; it is read the first time the translation is opened. A failed
    // read leaves it null, so closing and reopening asks again; a blank answer hides the panel.
    val text = loaded ?: insight.translation.text
    LaunchedEffect(open, text) {
        if (open && text == null) TranscriptInsightStates.fullTranslationText(loadFull(mediaId))?.let(onLoaded)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = if (open) "Hide translation" else "Show translation") { open = !open }
    ) {
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = FieldPalette.Purple700,
            modifier = Modifier.size(18.dp)
        )
        Text("English translation", color = FieldPalette.Purple700, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
    if (open) {
        Text(text.orEmpty(), color = Body, style = MaterialTheme.typography.bodyMedium)
        insight.translation.note?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
}
