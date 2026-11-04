package com.fieldrepository.app.ui.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.ui.Text
import com.fieldrepository.app.ui.field

@Composable
internal fun RichTextToolbar(
    state: RichToolbarState,
    canUndo: Boolean,
    canRedo: Boolean,
    onMark: (String) -> Unit,
    onBlockKind: (String, Int) -> Unit,
    onIndent: (Int) -> Unit,
    onAlign: (String) -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
    onInsertImage: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { canFocus = false }
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RteToolButton("Bold", Icons.Filled.FormatBold, state.marks.contains("BOLD")) { onMark("BOLD") }
        RteToolButton("Italic", Icons.Filled.FormatItalic, state.marks.contains("ITALIC")) { onMark("ITALIC") }
        RteToolButton("Underline", Icons.Filled.FormatUnderlined, state.marks.contains("UNDERLINE")) {
            onMark("UNDERLINE")
        }
        RteToolButton(
            "Strikethrough",
            Icons.Filled.FormatStrikethrough,
            state.marks.contains("STRIKETHROUGH"),
        ) { onMark("STRIKETHROUGH") }
        RteToolButton("Inline code", Icons.Filled.Code, state.marks.contains("CODE")) { onMark("CODE") }

        RteSeparator()

        RteToolLabelButton("¶", "Paragraph", state.kind == "PARAGRAPH") { onBlockKind("PARAGRAPH", 0) }
        for (level in 1..4) {
            RteToolLabelButton(
                "H$level",
                "Heading $level",
                state.kind == "HEADING" && state.level == level,
            ) { onBlockKind("HEADING", level) }
        }
        RteToolButton("Quote", Icons.Filled.FormatQuote, state.kind == "QUOTE") { onBlockKind("QUOTE", 0) }

        RteSeparator()

        RteToolButton(
            "Bulleted list",
            Icons.AutoMirrored.Filled.FormatListBulleted,
            state.kind == "BULLET_ITEM",
        ) { onBlockKind("BULLET_ITEM", 0) }
        RteToolButton(
            "Numbered list",
            Icons.Filled.FormatListNumbered,
            state.kind == "ORDERED_ITEM",
        ) { onBlockKind("ORDERED_ITEM", 0) }
        RteToolButton(
            "Outdent",
            Icons.AutoMirrored.Filled.FormatIndentDecrease,
            active = null,
            enabled = state.canOutdent,
        ) { onIndent(-1) }
        RteToolButton(
            "Indent",
            Icons.AutoMirrored.Filled.FormatIndentIncrease,
            active = null,
            enabled = state.canIndent,
        ) { onIndent(1) }

        RteSeparator()

        RteToolButton("Align left", Icons.AutoMirrored.Filled.FormatAlignLeft, state.align == "LEFT") {
            onAlign("LEFT")
        }
        RteToolButton("Align centre", Icons.Filled.FormatAlignCenter, state.align == "CENTER") {
            onAlign("CENTER")
        }
        RteToolButton("Align right", Icons.AutoMirrored.Filled.FormatAlignRight, state.align == "RIGHT") {
            onAlign("RIGHT")
        }
        RteToolButton("Justify", Icons.Filled.FormatAlignJustify, state.align == "JUSTIFY") {
            onAlign("JUSTIFY")
        }

        if (onInsertImage != null) {
            RteSeparator()
            RteToolButton("Photograph", Icons.Filled.Image, active = null) { onInsertImage() }
        }

        RteSeparator()

        RteToolButton("Clear formatting", Icons.Filled.FormatClear, active = null) { onClear() }
        RteToolButton("Undo", Icons.AutoMirrored.Filled.Undo, active = null, enabled = canUndo) { onUndo() }
        RteToolButton("Redo", Icons.AutoMirrored.Filled.Redo, active = null, enabled = canRedo) { onRedo() }
    }
}

@Composable
private fun RteToolButton(
    label: String,
    icon: ImageVector,
    active: Boolean?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    RteToolShell(label, active, enabled, onClick) { tint ->
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RteToolLabelButton(
    glyph: String,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    RteToolShell(label, active, enabled = true, onClick = onClick) { tint ->
        Text(glyph, color = tint, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RteToolShell(
    label: String,
    active: Boolean?,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val isActive = active == true
    val tint = when {
        !enabled -> MaterialTheme.field.placeholder
        isActive -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.field.body
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .background(
                if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (active != null) stateDescription = if (active) "on" else "off"
            },
    ) {
        content(tint)
    }
}

@Composable
private fun RteSeparator() {
    Box(
        Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(18.dp)
            .background(MaterialTheme.field.hairline)
    )
}
