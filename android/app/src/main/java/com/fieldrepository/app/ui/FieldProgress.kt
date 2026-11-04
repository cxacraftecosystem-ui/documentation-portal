package com.fieldrepository.app.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object FieldProgressDefaults {
    val MinThickness: Dp = 4.dp

    internal val TrackLight = Color(0xFFD9C4E9)

    internal val TrackDark = Color(0xFF4C3B63)

    val trackColor: Color
        @Composable @ReadOnlyComposable
        get() = if (MaterialTheme.field.isDark) TrackDark else TrackLight

    val indicatorColor: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary

    val successColor: Color
        @Composable @ReadOnlyComposable
        get() = if (MaterialTheme.field.isDark) FieldPalette.Success400 else FieldPalette.Success600

    @Composable
    @ReadOnlyComposable
    fun trackFor(color: Color): Color =
        if (color == indicatorColor || color == successColor) trackColor else color.copy(alpha = 0.3f)

    val onBrandTileIndicator: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.field.accentOnBrandTile

    val onBrandTileTrack: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.field.onBrandTile.copy(alpha = 0.24f)

    @Composable
    fun sliderColors(): SliderColors = SliderDefaults.colors(
        thumbColor = indicatorColor,
        activeTrackColor = indicatorColor,
        inactiveTrackColor = trackColor,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent
    )
}

@Composable
fun FieldLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = FieldProgressDefaults.indicatorColor,
    trackColor: Color = FieldProgressDefaults.trackFor(color)
) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round
    )
}

@Composable
fun FieldLinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = FieldProgressDefaults.indicatorColor,
    trackColor: Color = FieldProgressDefaults.trackFor(color)
) {
    LinearProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round
    )
}

@Composable
fun FieldCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = FieldProgressDefaults.indicatorColor,
    strokeWidth: Dp = FieldProgressDefaults.MinThickness,
    trackColor: Color = FieldProgressDefaults.trackFor(color)
) {
    CircularProgressIndicator(
        modifier = modifier,
        color = color,
        strokeWidth = strokeWidth.coerceAtLeast(FieldProgressDefaults.MinThickness),
        trackColor = trackColor,
        strokeCap = StrokeCap.Round
    )
}

@Composable
fun FieldCircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = FieldProgressDefaults.indicatorColor,
    strokeWidth: Dp = FieldProgressDefaults.MinThickness,
    trackColor: Color = FieldProgressDefaults.trackFor(color)
) {
    CircularProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        strokeWidth = strokeWidth.coerceAtLeast(FieldProgressDefaults.MinThickness),
        trackColor = trackColor,
        strokeCap = StrokeCap.Round
    )
}
