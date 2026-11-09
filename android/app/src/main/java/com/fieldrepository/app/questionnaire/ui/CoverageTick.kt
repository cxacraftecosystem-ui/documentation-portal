package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.fieldrepository.app.explorer.ui.ExplorerMarkIcon
import com.fieldrepository.app.explorer.ui.ExplorerMarkKind
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy

object CoverageTick {
    const val TOKEN = "\uE000"
    private const val INLINE_ID = "coverage.tick"

    fun spoken(text: String): String = text.replace(TOKEN, QuestionnaireCopy.overrideLabel("COMPLETED") ?: "Complete")

    @Composable
    fun Line(
        text: String,
        style: TextStyle,
        modifier: Modifier = Modifier,
        color: Color = Color.Unspecified,
        maxLines: Int = Int.MAX_VALUE,
        overflow: TextOverflow = TextOverflow.Clip,
    ) {
        if (!text.contains(TOKEN)) {
            Text(text, style = style, color = color, modifier = modifier, maxLines = maxLines, overflow = overflow)
            return
        }
        val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
        val annotated = buildAnnotatedString {
            text.split(TOKEN).forEachIndexed { index, part ->
                if (index > 0) appendInlineContent(INLINE_ID, spoken(TOKEN))
                append(part)
            }
        }
        val inline = mapOf(
            INLINE_ID to InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
                ExplorerMarkIcon(ExplorerMarkKind.SAVED, ink, 14.dp, Modifier.fillMaxSize())
            }
        )
        Text(annotated, style = style, color = color, modifier = modifier, maxLines = maxLines, overflow = overflow, inlineContent = inline)
    }
}
