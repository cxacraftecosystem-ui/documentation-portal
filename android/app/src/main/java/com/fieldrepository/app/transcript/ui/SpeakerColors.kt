package com.fieldrepository.app.transcript.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.transcript.data.TranscriptPreview
import com.fieldrepository.app.transcript.data.TranscriptPreviewDto
import com.fieldrepository.app.ui.field

object SpeakerPalette {
    val light: Map<String, Long> = linkedMapOf(
        "s0" to 0xFF762CB1, "s1" to 0xFF0F766E, "s2" to 0xFFB45309, "s3" to 0xFFBE185D,
        "s4" to 0xFF1D4ED8, "s5" to 0xFF4D7C0F, "s6" to 0xFFB91C1C, "s7" to 0xFF0E7490,
        TranscriptPreview.NEUTRAL to 0xFF52525B,
    )
    val dark: Map<String, Long> = linkedMapOf(
        "s0" to 0xFFC090F5, "s1" to 0xFF2DD4BF, "s2" to 0xFFFBBF24, "s3" to 0xFFF472B6,
        "s4" to 0xFF60A5FA, "s5" to 0xFFA3E635, "s6" to 0xFFF87171, "s7" to 0xFF22D3EE,
        TranscriptPreview.NEUTRAL to 0xFFA1A1AA,
    )

    fun argb(colorKey: String?, dark: Boolean): Long {
        val table = if (dark) this.dark else light
        return table[colorKey] ?: table.getValue(TranscriptPreview.NEUTRAL)
    }
}

@Composable
fun speakerColor(colorKey: String?): Color = Color(SpeakerPalette.argb(colorKey, MaterialTheme.field.isDark))

@Composable
fun TranscriptPreviewText(preview: TranscriptPreviewDto, modifier: Modifier = Modifier, maxLines: Int = 3) {
    if (preview.lines.isEmpty()) return
    val body = MaterialTheme.field.body
    val dark = MaterialTheme.field.isDark
    val text = buildAnnotatedString {
        preview.lines.forEachIndexed { i, line ->
            if (i > 0) append("\n")
            line.speaker?.let { name ->
                withStyle(SpanStyle(color = Color(SpeakerPalette.argb(line.colorKey, dark)), fontWeight = FontWeight.SemiBold)) { append(name) }
                append("  ")
            }
            withStyle(SpanStyle(color = body)) { append(line.text) }
        }
    }
    Column(modifier.semantics { contentDescription = preview.text }.testTag("transcript.preview"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TranscriptSpokenText(markdown: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val turns = androidx.compose.runtime.remember(markdown) { TranscriptPreview.turnsFromMarkdown(markdown) }
    if (turns.isEmpty()) return
    val body = MaterialTheme.field.body
    val dark = MaterialTheme.field.isDark
    val text = buildAnnotatedString {
        turns.forEachIndexed { i, turn ->
            if (i > 0) append("\n")
            turn.speaker?.let { name ->
                withStyle(SpanStyle(color = Color(SpeakerPalette.argb(TranscriptPreview.colorKey(null, name), dark)), fontWeight = FontWeight.SemiBold)) { append(name) }
                append("  ")
            }
            withStyle(SpanStyle(color = body)) { append(turn.text) }
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier.testTag("transcript.spoken"))
}
