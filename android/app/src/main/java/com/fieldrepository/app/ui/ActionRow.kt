package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
//                centred on a wider window. A long label wraps inside its own
//                half; the pair stacks only when a single word cannot fit its
//                half (very large font scale).
//   3+ buttons → compact window (< 600dp): stacked, full width, one under
//                another. Wider: one centred horizontal row of pills (Material
//                buttons are already fully rounded), wrapping only when the
//                row is genuinely too wide.
//
// The count is the number of children actually emitted, so a button behind an
// `if` is handled at runtime. Children must not carry RowScope modifiers
// (`weight`) — the row decides widths. Pass Delete / Reject / Discard as
// `destructive`: it goes last and, in the wide pill row, behind a hairline rule,
// as the web's ButtonRow does.
//
// Dialogs: a dialog with three actions puts one ActionRow in `confirmButton`
// (no `dismissButton`), so it stacks on a phone like the web's FieldDialog.
// Dialogs with one or two actions keep Material's own button slots.
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

/**
 * Whether a pair shares one line at [rowWidth]. [minWidths] is each button's min-intrinsic width —
 * its longest word plus padding — so a long label wraps inside its half rather than forcing a stack.
 */
internal fun pairSharesLine(rowWidth: Int, gap: Int, minWidths: List<Int>): Boolean {
    val half = (rowWidth - gap) / 2
    return minWidths.all { it <= half }
}

/** Each button's width in a pair: equal halves on a phone; the wider natural width, capped at half, elsewhere. */
internal fun pairButtonWidth(rowWidth: Int, gap: Int, naturalWidths: List<Int>, compact: Boolean): Int {
    val half = (rowWidth - gap) / 2
    return if (compact) half else minOf(half, naturalWidths.max())
}

@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    spacing: Dp = ActionRowDefaults.Spacing,
    destructive: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val compact = isCompactWidth()
    val ruleColor = MaterialTheme.colorScheme.outlineVariant
    val contents: List<@Composable () -> Unit> = listOf(
        content,
        destructive ?: {},
        { if (destructive != null) Box(Modifier.width(1.dp).height(24.dp).background(ruleColor)) },
    )
    Layout(contents = contents, modifier = modifier) { groups, constraints ->
        val measurables = groups[0] + groups[1]
        val rule = groups[2].firstOrNull()
        val dangerFrom = if (groups[1].isEmpty()) -1 else groups[0].size
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
                if (!pairSharesLine(maxW, gap, measurables.map { it.minIntrinsicWidth(Constraints.Infinity) })) {
                    stack(maxW)
                } else {
                    val each = pairButtonWidth(maxW, gap, measurables.map { natural(it) }, compact)
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
                val rp = if (rule != null && dangerFrom > 0) rule.measure(loose) else null
                // The rule rides in front of the destructive action, as part of its slot.
                fun lead(i: Int) = if (rp != null && i == dangerFrom) rp.width + gap else 0
                val lines = mutableListOf<MutableList<Int>>()
                var lineW = 0
                ps.indices.forEach { i ->
                    val need = lineW + gap + lead(i) + ps[i].width
                    if (lines.isEmpty() || need > maxW) {
                        lines.add(mutableListOf(i)); lineW = ps[i].width
                    } else {
                        lines.last().add(i); lineW = need
                    }
                }
                val pos = MutableList(ps.size) { 0 to 0 }
                var ruleAt: Pair<Int, Int>? = null
                var y = 0
                lines.forEach { line ->
                    // A destructive action that starts a wrapped line is already set apart by the break.
                    fun leadHere(i: Int) = if (i == line.first()) 0 else lead(i)
                    val w = line.sumOf { leadHere(it) + ps[it].width } + gap * (line.size - 1)
                    val lh = line.maxOf { ps[it].height }
                    var x = (maxW - w) / 2
                    line.forEach { i ->
                        if (leadHere(i) > 0 && rp != null) {
                            ruleAt = x to (y + (lh - rp.height) / 2)
                            x += leadHere(i)
                        }
                        pos[i] = x to (y + (lh - ps[i].height) / 2); x += ps[i].width + gap
                    }
                    y += lh + gap
                }
                MeasureResultSpec(maxW, (y - gap).coerceAtLeast(0), ps, pos, rp?.takeIf { ruleAt != null }, ruleAt)
            }
        }
        layout(spec.width.coerceIn(constraints.minWidth, constraints.maxWidth), spec.height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            spec.placeables.forEachIndexed { i, p -> p.placeRelative(spec.positions[i].first, spec.positions[i].second) }
            val r = spec.rule
            val at = spec.ruleAt
            if (r != null && at != null) r.placeRelative(at.first, at.second)
        }
    }
}

private class MeasureResultSpec(
    val width: Int,
    val height: Int,
    val placeables: List<Placeable>,
    val positions: List<Pair<Int, Int>>,
    val rule: Placeable? = null,
    val ruleAt: Pair<Int, Int>? = null
)
