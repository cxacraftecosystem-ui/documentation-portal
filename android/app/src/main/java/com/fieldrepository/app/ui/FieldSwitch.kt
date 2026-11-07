package com.fieldrepository.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object FieldSwitchDefaults {
    val Width: Dp = 48.dp
    val Height: Dp = 28.dp
    val Border: Dp = 1.dp
    val Thumb: Dp = 20.dp
    val ThumbInsetOff: Dp = 3.dp
    val ThumbInsetOn: Dp = 22.dp
    const val DISABLED_ALPHA = 0.6f

    fun thumbLeft(checked: Boolean): Dp = if (checked) ThumbInsetOn else ThumbInsetOff
}

data class FieldSwitchColors(val track: Color, val border: Color, val thumb: Color)

@Composable
fun fieldSwitchColors(checked: Boolean, enabled: Boolean = true): FieldSwitchColors {
    val scheme = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else FieldSwitchDefaults.DISABLED_ALPHA
    return if (checked) {
        FieldSwitchColors(track = scheme.primary.copy(alpha = alpha), border = scheme.primary.copy(alpha = alpha), thumb = scheme.onPrimary.copy(alpha = alpha))
    } else {
        FieldSwitchColors(track = MaterialTheme.field.surface200.copy(alpha = alpha), border = MaterialTheme.field.muted.copy(alpha = alpha), thumb = MaterialTheme.field.muted.copy(alpha = alpha))
    }
}

fun DrawScope.drawFieldSwitch(checked: Boolean, colors: FieldSwitchColors) {
    val radius = CornerRadius(size.height / 2)
    drawRoundRect(color = colors.track, cornerRadius = radius)
    val stroke = FieldSwitchDefaults.Border.toPx()
    drawRoundRect(
        color = colors.border,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius((size.height - stroke) / 2),
        style = Stroke(width = stroke),
    )
    val thumb = FieldSwitchDefaults.Thumb.toPx()
    val left = FieldSwitchDefaults.thumbLeft(checked).toPx()
    drawCircle(color = colors.thumb, radius = thumb / 2, center = Offset(left + thumb / 2, size.height / 2))
}

@Composable
fun FieldSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = fieldSwitchColors(checked, enabled)
    val toggle = if (onCheckedChange != null) {
        Modifier.minimumInteractiveComponentSize().toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = null,
            indication = null,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(modifier.then(toggle), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(FieldSwitchDefaults.Width, FieldSwitchDefaults.Height)) {
            drawFieldSwitch(checked, colors)
        }
    }
}
