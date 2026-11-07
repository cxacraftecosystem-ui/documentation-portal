package com.fieldrepository.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object FieldCheckboxDefaults {
    val Box: Dp = 20.dp
    val Padding: Dp = 2.dp
    val Corner: Dp = 4.dp
    val Border: Dp = 2.dp
    val Tick: Dp = 2.dp
    val UncheckedLight = Color(0xFFA7A3BC)
    const val DISABLED_ALPHA = 0.38f

    val tickPoints: List<Offset> = listOf(Offset(0.24f, 0.52f), Offset(0.42f, 0.70f), Offset(0.77f, 0.32f))

    fun tickPath(size: Size): Path = Path().apply {
        tickPoints.forEachIndexed { index, point ->
            val x = point.x * size.width
            val y = point.y * size.height
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
}

data class FieldCheckboxColors(val fill: Color, val border: Color, val tick: Color)

@Composable
fun fieldCheckboxColors(checked: Boolean, enabled: Boolean = true): FieldCheckboxColors {
    val scheme = MaterialTheme.colorScheme
    val unchecked = if (MaterialTheme.field.isDark) MaterialTheme.field.placeholder else FieldCheckboxDefaults.UncheckedLight
    val alpha = if (enabled) 1f else FieldCheckboxDefaults.DISABLED_ALPHA
    return if (checked) {
        FieldCheckboxColors(fill = scheme.primary.copy(alpha = alpha), border = scheme.primary.copy(alpha = alpha), tick = scheme.onPrimary.copy(alpha = if (enabled) 1f else 0.8f))
    } else {
        FieldCheckboxColors(fill = Color.Transparent, border = unchecked.copy(alpha = alpha), tick = Color.Transparent)
    }
}

fun DrawScope.drawFieldCheckbox(checked: Boolean, colors: FieldCheckboxColors) {
    val corner = CornerRadius(FieldCheckboxDefaults.Corner.toPx())
    if (checked) {
        drawRoundRect(color = colors.fill, cornerRadius = corner)
        drawPath(
            path = FieldCheckboxDefaults.tickPath(size),
            color = colors.tick,
            style = Stroke(width = FieldCheckboxDefaults.Tick.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter),
        )
    } else {
        val stroke = FieldCheckboxDefaults.Border.toPx()
        drawRoundRect(
            color = colors.border,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius((FieldCheckboxDefaults.Corner.toPx() - stroke / 2).coerceAtLeast(0f)),
            style = Stroke(width = stroke),
        )
    }
}

@Composable
fun FieldCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = fieldCheckboxColors(checked, enabled)
    val toggle = if (onCheckedChange != null) {
        Modifier.minimumInteractiveComponentSize().toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            interactionSource = null,
            indication = null,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(modifier.then(toggle), contentAlignment = Alignment.Center) {
        Canvas(Modifier.padding(FieldCheckboxDefaults.Padding).size(FieldCheckboxDefaults.Box)) {
            drawFieldCheckbox(checked, colors)
        }
    }
}
