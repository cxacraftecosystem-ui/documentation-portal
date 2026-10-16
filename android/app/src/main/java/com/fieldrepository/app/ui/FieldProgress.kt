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

/*
 * THE ONE PLACE A PROGRESS INDICATOR GETS ITS LOOK — every bar, spinner and seek track in the app.
 *
 * Owner report, 2026-10-17: "the voice-note progress bar disappears against the white of its card".
 * It did, measurably. Material 3 draws a progress TRACK in `secondaryContainer` and a slider's
 * unplayed track in the same role, and this theme maps that role to surface-100 (#F3F1FA) — 1.12:1
 * against a white card, and 1.00:1 on the audio player, whose own ground IS surface-100. The fill
 * was never the problem (purple-700 on white is 7.59:1); the track simply was not there.
 *
 * `secondaryContainer` is not re-pointed to fix it: chips, tonal buttons and the navigation
 * indicator read that role too. Instead every indicator goes through the composables below, and
 * `ProgressIndicatorSourceTest` refuses a direct Material call anywhere else, so a new screen
 * cannot quietly bring the invisible track back.
 *
 * The rule, identical to the web's `.progress-track` / `.progress-fill` (globals.css):
 *   - TRACK is a tint of the action colour, never the card's own white: purple-700 at 28% over
 *     white in light (#D9C4E9, 1.61:1 on the card), purple-400 at 30% over the dark card in dark
 *     (#4C3B63, 1.77:1). Visibly a track, still quieter than the fill.
 *   - INDICATOR is `primary` and clears WCAG 1.4.11's 3:1 against BOTH the track and the card:
 *     light 4.71:1 / 7.59:1, dark 4.05:1 / 7.16:1. The success fill (success-600 light,
 *     success-400 dark) clears it too: 3.11:1 / 5.02:1 and 5.70:1 / 10.10:1.
 *   - THICKNESS is at least 4dp, with ROUND ends, bars and rings alike.
 *   - A caller that passes its own indicator colour (a spinner on a filled button, onPrimary) gets
 *     a track derived from THAT colour at 30%, because the theme's track was chosen for a card and
 *     means nothing on a purple button.
 */

/** Colours and sizes every progress indicator reads. */
object FieldProgressDefaults {
    /** Minimum stroke for any bar or ring. */
    val MinThickness: Dp = 4.dp

    /** Purple-700 at 28% over the white card. */
    internal val TrackLight = Color(0xFFD9C4E9)

    /** Purple-400 at 30% over the dark card (#1A1725). */
    internal val TrackDark = Color(0xFF4C3B63)

    /** The tinted track. Never the card's own colour. */
    val trackColor: Color
        @Composable @ReadOnlyComposable
        get() = if (MaterialTheme.field.isDark) TrackDark else TrackLight

    /** The fill: the action colour (purple-700 light / purple-400 dark). */
    val indicatorColor: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary

    /** The fill for a finished or passing measure (success-600 light / success-400 dark). */
    val successColor: Color
        @Composable @ReadOnlyComposable
        get() = if (MaterialTheme.field.isDark) FieldPalette.Success400 else FieldPalette.Success600

    /**
     * The track for [color]: the theme's tinted track for the theme's own fill, otherwise [color]
     * at 30% so a spinner on a filled button still has a ring to travel round.
     */
    @Composable
    @ReadOnlyComposable
    fun trackFor(color: Color): Color =
        if (color == indicatorColor || color == successColor) trackColor else color.copy(alpha = 0.3f)

    /** Fill on [FieldTokens.brandTile] — purple-300 (5.76:1 light, 7.17:1 dark on the tile). */
    val onBrandTileIndicator: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.field.accentOnBrandTile

    /** Track on [FieldTokens.brandTile] — onBrandTile at 24% (fill 3.20:1 / 3.70:1 against it). */
    val onBrandTileTrack: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.field.onBrandTile.copy(alpha = 0.24f)

    /** A seek bar (the audio player): same fill and track as a progress bar. */
    @Composable
    fun sliderColors(): SliderColors = SliderDefaults.colors(
        thumbColor = indicatorColor,
        activeTrackColor = indicatorColor,
        inactiveTrackColor = trackColor,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent
    )
}

/** Determinate bar. [progress] is 0..1. */
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

/** Indeterminate bar. */
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

/** Indeterminate ring. A [strokeWidth] under [FieldProgressDefaults.MinThickness] is raised to it. */
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

/** Determinate ring. [progress] is 0..1. */
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
