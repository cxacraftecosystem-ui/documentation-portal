package com.fieldrepository.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// ActionRow — the one layout every group of buttons goes through.
//
//   1 button   → centred at its own width.
//   2 buttons  → one line, equal halves on a phone, equal content-fit widths
//                centred on a wider window. Never wraps; if a label cannot fit
//                its half (very large font scale) the pair stacks instead of
//                clipping.
//   3+ buttons → compact window (< 600dp): stacked, full width, one under
//                another. Wider: one centred horizontal row of pills (Material
//                buttons are already fully rounded), wrapping only when the
//                row is genuinely too wide.
//
// The count is the number of children actually emitted, so a button behind an
// `if` is handled at runtime. Children must not carry RowScope modifiers
// (`weight`) — the row decides widths. Put destructive actions last.
// ---------------------------------------------------------------------------

object ActionRowDefaults {
    val Spacing: Dp = 8.dp
    val CompactBreakpoint: Dp = 600.dp
}

/** True when the window is in the compact width class (< 600dp), as on a phone held upright. */
@Composable
fun isCompactWidth(): Boolean {
    val widthPx = LocalWindowInfo.current.containerSize.width
    if (widthPx <= 0) return true
    val widthDp = with(LocalDensity.current) { widthPx.toDp() }
    return widthDp < ActionRowDefaults.CompactBreakpoint
}

@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    spacing: Dp = ActionRowDefaults.Spacing,
    content: @Composable () -> Unit
) {
    val compact = isCompactWidth()
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        fun natural(m: Measurable) = m.maxIntrinsicWidth(Constraints.Infinity)
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth
        else measurables.sumOf { natural(it) } + gap * (measurables.size - 1).coerceAtLeast(0)

        fun stack(widthPx: Int): MeasureResultSpec {
            val ps = measurables.map { it.measure(Constraints.fixedWidth(widthPx).copy(maxHeight = loose.maxHeight)) }
            val h = ps.sumOf { it.height } + gap * (ps.size - 1).coerceAtLeast(0)
            var y = 0
            val pos = ps.map { p -> val at = (0 to y); y += p.height + gap; at }
            return MeasureResultSpec(widthPx, h, ps, pos)
        }

        val spec: MeasureResultSpec = when (measurables.size) {
            0 -> MeasureResultSpec(0, 0, emptyList(), emptyList())
            1 -> {
                val p = measurables[0].measure(loose.copy(maxWidth = maxW))
                MeasureResultSpec(maxW, p.height, listOf(p), listOf(((maxW - p.width) / 2) to 0))
            }
            2 -> {
                val half = (maxW - gap) / 2
                val wants = measurables.map { natural(it) }
                if (wants.any { it > half }) {
                    stack(maxW)
                } else {
                    val each = if (compact) half else wants.max()
                    val h = measurables.maxOf { it.minIntrinsicHeight(each) }
                    val ps = measurables.map { it.measure(Constraints.fixed(each, h)) }
                    val rowH = ps.maxOf { it.height }
                    val start = (maxW - (each * 2 + gap)) / 2
                    MeasureResultSpec(
                        maxW, rowH, ps,
                        listOf(start to (rowH - ps[0].height) / 2, (start + each + gap) to (rowH - ps[1].height) / 2)
                    )
                }
            }
            else -> if (compact) stack(maxW) else {
                val ps = measurables.map { it.measure(loose.copy(maxWidth = maxW)) }
                val lines = mutableListOf<MutableList<Int>>()
                var lineW = 0
                ps.forEachIndexed { i, p ->
                    val need = if (lines.isEmpty() || lines.last().isEmpty()) p.width else lineW + gap + p.width
                    if (lines.isEmpty() || need > maxW) { lines.add(mutableListOf(i)); lineW = p.width }
                    else { lines.last().add(i); lineW = need }
                }
                val pos = MutableList(ps.size) { 0 to 0 }
                var y = 0
                lines.forEach { line ->
                    val w = line.sumOf { ps[it].width } + gap * (line.size - 1)
                    val lh = line.maxOf { ps[it].height }
                    var x = (maxW - w) / 2
                    line.forEach { i -> pos[i] = x to (y + (lh - ps[i].height) / 2); x += ps[i].width + gap }
                    y += lh + gap
                }
                MeasureResultSpec(maxW, (y - gap).coerceAtLeast(0), ps, pos)
            }
        }
        layout(spec.width.coerceIn(constraints.minWidth, constraints.maxWidth), spec.height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            spec.placeables.forEachIndexed { i, p -> p.placeRelative(spec.positions[i].first, spec.positions[i].second) }
        }
    }
}

private class MeasureResultSpec(
    val width: Int,
    val height: Int,
    val placeables: List<Placeable>,
    val positions: List<Pair<Int, Int>>
)
