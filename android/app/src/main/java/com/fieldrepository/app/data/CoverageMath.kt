package com.fieldrepository.app.data

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/*
 * THE COVERAGE MAP'S PURE HALF on the phone: quantile breaks, bins, which polygon carries which value,
 * and how crowded markers fold. The web's `coverageMath.ts` line for line; `CoverageMathTest` reads the
 * same `coverage-parity.json` the web's parity spec reads, so the two cannot drift.
 *
 * Shared with the Field Repository byte for byte apart from the package line.
 */

const val COVERAGE_BIN_COUNT = 5

/**
 * The number a unit is coloured by. Workshops are those that HAPPENED (held design workshops plus
 * field workshops; planned ones never); "all" sums every record bucket, never crafts. A peer row is
 * never added in.
 */
fun coverageMetricValue(counts: CoverageCounts, metric: String): Int {
    val held = counts.designWorkshops + counts.workshops
    return when (metric) {
        CoverageMetric.WORKSHOPS -> held
        CoverageMetric.ARTISANS -> counts.artisans
        else -> held + counts.artisans + counts.products + counts.tools + counts.media
    }
}

/**
 * Upper bounds of up to five quantile bins over the NON-ZERO values. Zero is "no record", never a bin;
 * duplicate bounds collapse.
 */
fun quantileBreaks(values: List<Int>, bins: Int = COVERAGE_BIN_COUNT): List<Int> {
    val sorted = values.filter { it > 0 }.sorted()
    if (sorted.isEmpty()) return emptyList()
    val breaks = ArrayList<Int>()
    for (k in 1..bins) {
        val at = min(sorted.size - 1, ceil(k.toDouble() / bins * sorted.size).toInt() - 1)
        val bound = sorted[at]
        if (breaks.isEmpty() || bound > breaks.last()) breaks += bound
    }
    return breaks
}

/** -1 for zero, else the first bin whose bound holds the value. */
fun binOf(value: Int, breaks: List<Int>): Int {
    if (value <= 0 || breaks.isEmpty()) return -1
    val index = breaks.indexOfFirst { value <= it }
    return if (index == -1) breaks.size - 1 else index
}

/** Legend text for each bin: "1", "2–4", "5–12". */
fun binLabels(breaks: List<Int>): List<String> = breaks.mapIndexed { index, bound ->
    val low = if (index == 0) 1 else breaks[index - 1] + 1
    if (low >= bound) "$bound" else "$low–$bound"
}

/** A polygon to fill: its LGD code and its state's. */
data class CoverageShapeKey(val lgd: Int, val stateLgd: Int)

/** The value a polygon carries, and whether a district without its own polygon is drawn inside it. */
data class CoverageFill(val value: Int, val hostsChild: Boolean)

/**
 * Which value each district polygon carries, by LGD — the web's `polygonFills`. At DISTRICT level a
 * PARENT unit adds to its parent's polygon and marks it `hostsChild`; a STATE unit has no polygon. At
 * STATE level every district takes its state's value, and a zero state is left out.
 */
fun fillsFor(
    units: List<CoverageUnit>,
    level: String,
    metric: String,
    shapes: List<CoverageShapeKey>
): Map<Int, CoverageFill> {
    val out = LinkedHashMap<Int, CoverageFill>()
    if (level == CoverageLevel.STATE) {
        val byState = HashMap<Int, Int>()
        for (unit in units) byState[unit.stateLgd] = coverageMetricValue(unit.counts, metric)
        for (shape in shapes) {
            val value = byState[shape.stateLgd] ?: 0
            if (value != 0) out[shape.lgd] = CoverageFill(value, false)
        }
        return out
    }
    for (unit in units) {
        val district = unit.districtLgd ?: continue
        val target = when (unit.polygon) {
            CoveragePolygon.PARENT -> unit.parentLgd
            CoveragePolygon.OWN -> district
            else -> null
        } ?: continue
        val current = out[target] ?: CoverageFill(0, false)
        out[target] = CoverageFill(
            current.value + coverageMetricValue(unit.counts, metric),
            current.hostsChild || unit.polygon == CoveragePolygon.PARENT
        )
    }
    return out
}

/** Polygons whose districts have no workshop: hatched, from the gaps lists as on the web. */
fun noWorkshopFor(response: CoverageResponse, level: String, shapes: List<CoverageShapeKey>): Set<Int> {
    val out = HashSet<Int>()
    if (level == CoverageLevel.STATE) {
        val states = response.gaps.statesWithoutWorkshop.mapNotNull { it.lgd }.toSet()
        for (shape in shapes) if (shape.stateLgd in states) out += shape.lgd
        return out
    }
    for (row in response.gaps.districtsWithoutWorkshop.byState) {
        for (district in row.without) district.lgd?.let { out += it }
    }
    return out
}

/** Markers folded into one mark, drawn at the members' averaged position. */
data class CoverageCluster(val x: Float, val y: Float, val markers: List<CoverageMarker>)

/**
 * Markers closer than [cell] (in the units [position] answers, on screen pixels on the phone) fold into
 * one counted mark at their averaged position — the web's `clusterMarkers` with `CLUSTER_CELL = 22`.
 */
fun clusterMarkers(
    markers: List<CoverageMarker>,
    cell: Float,
    position: (CoverageMarker) -> Pair<Float, Float>
): List<CoverageCluster> {
    data class Acc(var x: Float, var y: Float, val members: MutableList<CoverageMarker>)
    val cells = LinkedHashMap<Pair<Int, Int>, Acc>()
    for (marker in markers) {
        val (x, y) = position(marker)
        val key = floor(x / cell).toInt() to floor(y / cell).toInt()
        val found = cells[key]
        if (found == null) {
            cells[key] = Acc(x, y, mutableListOf(marker))
        } else {
            found.members += marker
            val n = found.members.size
            found.x = (found.x * (n - 1) + x) / n
            found.y = (found.y * (n - 1) + y) / n
        }
    }
    return cells.values.map { CoverageCluster(it.x, it.y, it.members) }
}

/** The web's `DEPICTION_CAPTION`, word for word. */
const val COVERAGE_DEPICTION_CAPTION =
    "External boundaries of India as depicted by Survey of India. District boundaries are indicative."
