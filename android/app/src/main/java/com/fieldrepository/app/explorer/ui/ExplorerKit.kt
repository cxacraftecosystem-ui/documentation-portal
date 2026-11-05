package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.ui.field

object ExplorerShapes {
    val card = RoundedCornerShape(16.dp)
    val inner = RoundedCornerShape(14.dp)
    val control = RoundedCornerShape(12.dp)
    val tile = RoundedCornerShape(10.dp)
    val pill = RoundedCornerShape(999.dp)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
}

object ExplorerInk {
    val lightInk = Color(0xFF1E1B2E)
    val darkInk = Color(0xFFF2F0F9)
    val darkControlLine = Color(0xFF3A3651)
    val track = Color(0xFFD9C4E9)
    val trackDark = Color(0xFF4C3B63)
    val subLight = Color(0xFF3A3651)
    val focus = Color(0xFF9148D2)
}

@Composable
@ReadOnlyComposable
fun explorerControlLine(): Color = if (MaterialTheme.field.isDark) ExplorerInk.darkControlLine else MaterialTheme.field.hairline

@Composable
@ReadOnlyComposable
fun explorerTrack(): Color = if (MaterialTheme.field.isDark) ExplorerInk.trackDark else ExplorerInk.track

@Composable
@ReadOnlyComposable
fun explorerSelectedRow(): Color = if (MaterialTheme.field.isDark) Color(0xFF2A2140) else Color(0xFFFAF5FF)

@Composable
fun Modifier.explorerCard(container: Color = MaterialTheme.colorScheme.surface, line: Color = MaterialTheme.field.hairline, shape: androidx.compose.ui.graphics.Shape = ExplorerShapes.card): Modifier =
    this.background(container, shape).border(1.dp, line, shape)

@Composable
fun ExplorerPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ink: Boolean = false,
    minHeight: Dp = 36.dp,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    horizontal: Dp = 12.dp,
    role: Role = Role.Checkbox,
    enabled: Boolean = true,
) {
    val dark = MaterialTheme.field.isDark
    val container = when {
        !selected -> MaterialTheme.colorScheme.surface
        ink -> if (dark) ExplorerInk.darkInk else ExplorerInk.lightInk
        else -> MaterialTheme.colorScheme.primary
    }
    val content = when {
        !selected -> MaterialTheme.colorScheme.onSurface
        ink -> if (dark) MaterialTheme.colorScheme.background else Color.White
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Box(
        modifier
            .heightIn(min = minHeight)
            .clip(ExplorerShapes.pill)
            .background(container, ExplorerShapes.pill)
            .let { if (selected) it else it.border(1.dp, explorerControlLine(), ExplorerShapes.pill) }
            .clickable(enabled = enabled, role = role, onClick = onClick)
            .semantics { this.role = role; this.selected = selected }
            .padding(horizontal = horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = content, textAlign = TextAlign.Center)
    }
}

@Composable
fun ExplorerPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
    minHeight: Dp = 48.dp,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    shape: androidx.compose.ui.graphics.Shape = ExplorerShapes.control,
    bold: Boolean = MaterialTheme.field.isDark,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = minHeight),
    ) {
        Text(text, style = style, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
fun ExplorerSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    line: Color = explorerControlLine(),
    content: Color = MaterialTheme.colorScheme.onSurface,
    container: Color = MaterialTheme.colorScheme.surface,
    minHeight: Dp = 48.dp,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    shape: androidx.compose.ui.graphics.Shape = ExplorerShapes.control,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        border = BorderStroke(1.dp, line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = modifier.heightIn(min = minHeight),
    ) {
        Text(text, style = style, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
    }
}

@Composable
fun ExplorerLinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, style: TextStyle = MaterialTheme.typography.bodyMedium, enabled: Boolean = true) {
    Box(
        modifier.heightIn(min = 36.dp).clip(ExplorerShapes.tile).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = style, fontWeight = if (MaterialTheme.field.isDark) FontWeight.Bold else FontWeight.SemiBold, color = color)
    }
}

@Composable
fun ExplorerBar(fraction: Float?, modifier: Modifier = Modifier, height: Dp = 6.dp, fill: Color = MaterialTheme.colorScheme.primary, track: Color = explorerTrack()) {
    Box(modifier.fillMaxWidth().height(height).clip(ExplorerShapes.pill).background(track)) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(ExplorerShapes.pill).background(fill))
        } else {
            com.fieldrepository.app.ui.FieldLinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(height))
        }
    }
}

@Composable
fun ExplorerToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val track = if (checked) MaterialTheme.colorScheme.primary else if (MaterialTheme.field.isDark) ExplorerInk.darkControlLine else Color(0xFFD9D6E5)
    Box(
        modifier.size(width = 44.dp, height = 26.dp).clip(CircleShape).background(track)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(horizontal = 3.dp).size(20.dp).background(if (checked && MaterialTheme.field.isDark) MaterialTheme.colorScheme.onPrimary else Color.White, CircleShape))
    }
}

internal enum class TileKind(val label: String) { VIDEO("VID"), AUDIO("AUD"), DOC("DOC"), TEXT("TXT"), FILE("FILE") }

internal fun tileKind(entry: ExplorerEntry): TileKind = when (entry.mediaType) {
    "VIDEO" -> TileKind.VIDEO
    "AUDIO" -> TileKind.AUDIO
    "PDF", "DOCUMENT" -> TileKind.DOC
    null -> if (entry.content != null || entry.transcriptPath != null) TileKind.TEXT else TileKind.FILE
    else -> TileKind.FILE
}

@Composable
@ReadOnlyComposable
internal fun tileColors(kind: TileKind): Pair<Color, Color> {
    val dark = MaterialTheme.field.isDark
    return when (kind) {
        TileKind.VIDEO -> if (dark) Color(0xFF3B1F5C) to Color(0xFFE7D5FE) else Color(0xFFE7D5FE) to Color(0xFF5F2491)
        TileKind.AUDIO -> if (dark) Color(0xFF3B2A06) to Color(0xFFFCD34D) else Color(0xFFFEF3C7) to Color(0xFF92400E)
        TileKind.DOC -> if (dark) Color(0xFF0C2A3E) to Color(0xFF7DD3FC) else Color(0xFFE0F2FE) to Color(0xFF075985)
        TileKind.TEXT, TileKind.FILE -> MaterialTheme.field.surface100 to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
internal fun ExplorerTypeTile(entry: ExplorerEntry, size: Dp = 48.dp) {
    val kind = tileKind(entry)
    val (bg, fg) = tileColors(kind)
    Box(Modifier.size(size).background(bg, ExplorerShapes.tile).testTag("explorer.tile.${kind.label.lowercase()}"), contentAlignment = Alignment.Center) {
        Text(kind.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = fg)
    }
}

internal fun DrawScope.cellLines(first: Boolean, last: Boolean, line: Color, divider: Color, radius: Float, stroke: Float) {
    val w = size.width
    val h = size.height
    val top = if (first) radius else 0f
    val bottom = if (last) h - radius else h
    drawLine(line, androidx.compose.ui.geometry.Offset(stroke / 2, top), androidx.compose.ui.geometry.Offset(stroke / 2, bottom), stroke)
    drawLine(line, androidx.compose.ui.geometry.Offset(w - stroke / 2, top), androidx.compose.ui.geometry.Offset(w - stroke / 2, bottom), stroke)
    if (first) {
        drawArc(line, 180f, 90f, false, androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2), androidx.compose.ui.geometry.Size(radius * 2 - stroke, radius * 2 - stroke), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawArc(line, 270f, 90f, false, androidx.compose.ui.geometry.Offset(w - radius * 2 + stroke / 2, stroke / 2), androidx.compose.ui.geometry.Size(radius * 2 - stroke, radius * 2 - stroke), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawLine(line, androidx.compose.ui.geometry.Offset(radius, stroke / 2), androidx.compose.ui.geometry.Offset(w - radius, stroke / 2), stroke)
    }
    if (last) {
        drawArc(line, 90f, 90f, false, androidx.compose.ui.geometry.Offset(stroke / 2, h - radius * 2 + stroke / 2), androidx.compose.ui.geometry.Size(radius * 2 - stroke, radius * 2 - stroke), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawArc(line, 0f, 90f, false, androidx.compose.ui.geometry.Offset(w - radius * 2 + stroke / 2, h - radius * 2 + stroke / 2), androidx.compose.ui.geometry.Size(radius * 2 - stroke, radius * 2 - stroke), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawLine(line, androidx.compose.ui.geometry.Offset(radius, h - stroke / 2), androidx.compose.ui.geometry.Offset(w - radius, h - stroke / 2), stroke)
    } else {
        drawLine(divider, androidx.compose.ui.geometry.Offset(stroke, h - stroke / 2), androidx.compose.ui.geometry.Offset(w - stroke, h - stroke / 2), stroke)
    }
}

@Composable
fun Modifier.explorerCell(first: Boolean, last: Boolean, container: Color = MaterialTheme.colorScheme.surface, divider: Color = MaterialTheme.field.hairline): Modifier {
    val line = MaterialTheme.field.hairline
    val shape = RoundedCornerShape(topStart = if (first) 16.dp else 0.dp, topEnd = if (first) 16.dp else 0.dp, bottomStart = if (last) 16.dp else 0.dp, bottomEnd = if (last) 16.dp else 0.dp)
    return this.clip(shape).background(container, shape).drawWithLines(first, last, line, divider)
}

@Composable
private fun Modifier.drawWithLines(first: Boolean, last: Boolean, line: Color, divider: Color): Modifier {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val radius = with(density) { 16.dp.toPx() }
    val stroke = with(density) { 1.dp.toPx() }
    return this.drawWithContent { drawContent(); cellLines(first, last, line, divider, radius, stroke) }
}

@Composable
fun ExplorerSheetHandle(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Box(Modifier.width(40.dp).height(4.dp).background(explorerControlLine(), RoundedCornerShape(2.dp)))
    }
}
