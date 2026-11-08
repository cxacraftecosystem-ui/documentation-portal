package com.fieldrepository.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object FieldRadioDefaults {
    val Box: Dp = 20.dp
    val Padding: Dp = 2.dp
    val Ring: Dp = 2.dp
    val Dot: Dp = 10.dp
    const val DISABLED_ALPHA = 0.38f
}

data class FieldRadioColors(val ring: Color, val dot: Color)

@Composable
fun fieldRadioColors(selected: Boolean, enabled: Boolean = true): FieldRadioColors {
    val scheme = MaterialTheme.colorScheme
    val unselected = if (MaterialTheme.field.isDark) MaterialTheme.field.placeholder else FieldCheckboxDefaults.UncheckedLight
    val alpha = if (enabled) 1f else FieldRadioDefaults.DISABLED_ALPHA
    return if (selected) {
        FieldRadioColors(ring = scheme.primary.copy(alpha = alpha), dot = scheme.primary.copy(alpha = alpha))
    } else {
        FieldRadioColors(ring = unselected.copy(alpha = alpha), dot = Color.Transparent)
    }
}

fun DrawScope.drawFieldRadio(selected: Boolean, colors: FieldRadioColors) {
    val stroke = FieldRadioDefaults.Ring.toPx()
    val center = Offset(size.width / 2, size.height / 2)
    drawCircle(color = colors.ring, radius = size.minDimension / 2 - stroke / 2, center = center, style = Stroke(width = stroke))
    if (selected) drawCircle(color = colors.dot, radius = FieldRadioDefaults.Dot.toPx() / 2, center = center)
}

@Composable
fun FieldRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = fieldRadioColors(selected, enabled)
    val select = if (onClick != null) {
        Modifier.minimumInteractiveComponentSize().selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            interactionSource = null,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    Box(modifier.then(select), contentAlignment = Alignment.Center) {
        Canvas(Modifier.padding(FieldRadioDefaults.Padding).size(FieldRadioDefaults.Box)) {
            drawFieldRadio(selected, colors)
        }
    }
}
