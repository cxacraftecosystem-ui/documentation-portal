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

object ActionRowDefaults {
    val Spacing: Dp = 8.dp
    val CompactBreakpoint: Dp = 600.dp
}

@Composable
fun isCompactWidth(): Boolean {
    val widthPx = LocalWindowInfo.current.containerSize.width
    if (widthPx <= 0) return true
    val widthDp = with(LocalDensity.current) { widthPx.toDp() }
    return widthDp < ActionRowDefaults.CompactBreakpoint
}

internal fun pairSharesLine(rowWidth: Int, gap: Int, minWidths: List<Int>): Boolean {
    val half = (rowWidth - gap) / 2
    return minWidths.all { it <= half }
}

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
