package com.fieldrepository.app.transcript.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.transcript.data.TranscriptKaraoke
import com.fieldrepository.app.transcript.data.TranscriptMarkdown
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.ui.field

/**
 * A turn's original words: the word being said lit (karaoke), the words Scribe doubted faintly
 * underlined with a dashed rule, and a tap on any word seeking the audio to it (DESIGN §4.3, §10.1).
 * The lit word is also the node's state description, so a screen reader and the Compose tests read it.
 */
@Composable
fun KaraokeText(
    turn: TranscriptTurn,
    activeWord: Int,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val ranges = remember(turn.id, turn.text, turn.words) { TranscriptKaraoke.wordRanges(turn) }
    val lit = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    val doubt = MaterialTheme.field.muted.copy(alpha = 0.7f)
    val active = ranges.getOrNull(activeWord)
    val text = remember(turn.text, active, lit) {
        buildAnnotatedString {
            append(turn.text)
            if (active != null) addStyle(SpanStyle(background = lit, fontWeight = FontWeight.SemiBold), active.first, active.last + 1)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val doubts = remember(turn.text, turn.lowConfidence) { TranscriptKaraoke.doubts(turn) }
    Text(
        text = text,
        style = style,
        color = MaterialTheme.field.body,
        onTextLayout = { layout = it },
        modifier = modifier
            .testTag("turn.text.${turn.id}")
            .semantics { if (active != null) stateDescription = turn.text.substring(active.first, active.last + 1) }
            .drawBehind {
                val l = layout ?: return@drawBehind
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                for (span in doubts) underline(l, span.first, span.last + 1) { a, b -> drawLine(doubt, a, b, strokeWidth = 1.dp.toPx(), pathEffect = dash) }
            }
            .pointerInput(turn.id, turn.text) {
                detectTapGestures { position ->
                    val l = layout ?: return@detectTapGestures
                    onSeek(TranscriptKaraoke.seekFor(turn, l.getOffsetForPosition(position)))
                }
            }
    )
}

/** Calls [draw] with each line's stretch of [start, end) just under the text. */
private inline fun underline(layout: TextLayoutResult, start: Int, end: Int, draw: (Offset, Offset) -> Unit) {
    val first = layout.getLineForOffset(start)
    val last = layout.getLineForOffset((end - 1).coerceAtLeast(start))
    for (line in first..last) {
        val left = if (line == first) layout.getHorizontalPosition(start, true) else layout.getLineLeft(line)
        val right = if (line == last) layout.getHorizontalPosition(end, true) else layout.getLineRight(line)
        val y = layout.getLineBottom(line) - 1f
        draw(Offset(left, y), Offset(right, y))
    }
}

/** [TranscriptMarkdown.Inline] as styled text. */
fun TranscriptMarkdown.Inline.annotated(code: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (s in spans) {
        val style = when (s.style) {
            TranscriptMarkdown.Style.BOLD -> SpanStyle(fontWeight = FontWeight.SemiBold)
            TranscriptMarkdown.Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            TranscriptMarkdown.Style.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = code)
        }
        addStyle(style, s.start, s.end)
    }
}

/**
 * Markdown drawn with Compose text, block by block — never a WebView, never HTML. Headings, the
 * header's one-row table, quote blocks and paragraphs (see [TranscriptMarkdown]).
 */
@Composable
fun TranscriptMarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { TranscriptMarkdown.parse(markdown) }
    val code = MaterialTheme.field.surface200
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) when (block) {
            is TranscriptMarkdown.Block.Heading -> Text(
                block.inline.annotated(code),
                style = if (block.level == 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            is TranscriptMarkdown.Block.Table -> Row(
                Modifier.fillMaxWidth().background(MaterialTheme.field.surface100, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                block.cells.forEachIndexed { i, cell ->
                    Text(
                        cell.annotated(code),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (i % 2 == 0) MaterialTheme.field.muted else MaterialTheme.field.body
                    )
                }
            }
            is TranscriptMarkdown.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(2.dp)))
                Text(
                    block.inline.annotated(code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.field.muted,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            is TranscriptMarkdown.Block.Paragraph -> Text(block.inline.annotated(code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
        }
    }
}
