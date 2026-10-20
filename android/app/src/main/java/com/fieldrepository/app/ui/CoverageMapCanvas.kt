package com.fieldrepository.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.CoverageFill
import com.fieldrepository.app.data.CoverageMarker
import com.fieldrepository.app.data.binOf
import com.fieldrepository.app.data.clusterMarkers
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * THE COVERAGE CHOROPLETH ON A PHONE — the web's `CoverageMap.tsx`, drawn by hand on a Compose Canvas
 * like the Places map. No map SDK, no tiles: the outline is the shipped official depiction
 * (`res/raw/india_outline.bin`), the districts are `res/raw/district_shapes.bin`, built from the same
 * source as the web's `district-shapes.json`.
 *
 * Shared with the Field Repository byte for byte apart from the package line.
 *
 * DRAWN IN THE WEB'S ORDER: the outline filled in the "no record" tone; district fills clipped to it
 * (a district with no figure is left unfilled, so the base shows through); hairline district edges;
 * a dashed edge where a district without its own polygon is drawn inside its parent; a 2dp edge on the
 * selected district; ONE hatch path for "No workshop"; the state borders and the outline stroke on top;
 * then the markers, folded where they crowd. Pinch, pan and double tap come from [mapZoomGestures];
 * a state focus fits the box to that state, as the web's viewBox does.
 */

/** The five bin fills, light to dark; dark mode reverses the ramp so "more" stays brighter. */
private val BIN_LIGHT = listOf(
    FieldPalette.Purple300,
    FieldPalette.Purple400,
    FieldPalette.Purple500,
    FieldPalette.Purple700,
    FieldPalette.Purple900
)
private val BIN_DARK = listOf(
    FieldPalette.Purple800,
    FieldPalette.Purple700,
    FieldPalette.Purple600,
    FieldPalette.Purple400,
    FieldPalette.Purple200
)

/** The bin colours for this theme, for the legend and the canvas alike. */
fun coverageBinColors(dark: Boolean): List<Color> = if (dark) BIN_DARK else BIN_LIGHT

/** Markers closer than this on screen fold into one counted mark (the web's `CLUSTER_CELL`). */
private val CLUSTER_CELL = 22.dp
private val MAP_PADDING = 8.dp
private val MARKER_TAP_SLOP = 10.dp

/**
 * Where the world sits in the box at rest: the whole country, or one state fitted with a margin. The
 * zoom transform is applied on top of this.
 */
internal class CoverageFrame(val size: Size, world: Rect, padPx: Float) {
    val scale: Float
    val offset: Offset

    init {
        val margin = max(world.width, world.height) * 0.08f
        val box = Rect(world.left - margin, world.top - margin, world.right + margin, world.bottom + margin)
        val usableW = max(1f, size.width - padPx * 2f)
        val usableH = max(1f, size.height - padPx * 2f)
        scale = min(usableW / max(box.width, 1e-6f), usableH / max(box.height, 1e-6f))
        offset = Offset(
            (size.width - box.width * scale) / 2f - box.left * scale,
            (size.height - box.height * scale) / 2f - box.top * scale
        )
    }

    fun toFrame(world: Offset): Offset = offset + world * scale
    fun toWorld(frame: Offset): Offset = (frame - offset) / scale
}

@Composable
fun CoverageMapCanvas(
    outline: IndiaGeometry,
    stateBorders: IndiaGeometry?,
    shapes: List<DistrictShape>,
    fills: Map<Int, CoverageFill>,
    breaks: List<Int>,
    noWorkshop: Set<Int>,
    markers: List<CoverageMarker>,
    selectedLgd: Int?,
    focusStateLgd: Int?,
    onSelectDistrict: (Int) -> Unit,
    modifier: Modifier = Modifier,
    zoom: MapZoomState = rememberMapZoomState()
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val base = if (dark) FieldPalette.Surface100Dark else FieldPalette.Surface100Light
    val bins = coverageBinColors(dark)
    val hairline = scheme.outlineVariant
    val dashed = scheme.onSurfaceVariant
    val selectedInk = scheme.onSurface
    val hatchInk = scheme.onSurfaceVariant.copy(alpha = 0.55f)
    val stateInk = scheme.onSurfaceVariant.copy(alpha = 0.75f)
    val coast = scheme.onSurface.copy(alpha = 0.7f)
    val discDesign = if (dark) FieldPalette.Purple300 else FieldPalette.Purple700
    val discOther = if (dark) FieldPalette.Purple950 else Color.White
    val rimDesign = if (dark) FieldPalette.Purple950 else Color.White
    val rimOther = if (dark) FieldPalette.Purple300 else FieldPalette.Purple700
    val inkOnDesign = if (dark) FieldPalette.Purple950 else Color.White
    val inkOnOther = if (dark) FieldPalette.Purple200 else FieldPalette.Purple800
    val reducedMotion = LocalAppPreferences.current.reducedMotion

    // A new state focus re-fits the box, so any zoom from before is meaningless: back to 1.
    LaunchedEffect(focusStateLgd) { zoom.reset() }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        if (!widthPx.isFinite() || widthPx <= 0f) return@BoxWithConstraints
        val aspect = max(0.1f, outline.aspect)
        val size = Size(widthPx, widthPx * aspect)
        val padPx = with(density) { MAP_PADDING.toPx() }
        val focusBox = remember(shapes, focusStateLgd) { focusStateLgd?.let { stateBounds(shapes, it) } }
        val frame = remember(size, focusBox, aspect, padPx) {
            CoverageFrame(size, focusBox ?: Rect(0f, 0f, 1f, aspect), padPx)
        }
        val visible = remember(shapes, focusStateLgd) {
            if (focusStateLgd == null) shapes else shapes.filter { it.stateLgd == focusStateLgd }
        }
        val clusterCell = with(density) { CLUSTER_CELL.toPx() }
        val tapSlop = with(density) { MARKER_TAP_SLOP.toPx() }
        val hairPx = with(density) { 0.5.dp.toPx() }
        val selectedPx = with(density) { 2.dp.toPx() }
        val markerSmall = with(density) { 5.dp.toPx() }
        val markerLarge = with(density) { 10.dp.toPx() }
        val hatchGap = with(density) { 6.dp.toPx() }

        // The gesture detector outlives a recomposition, so it calls through this holder and always
        // hits the markers, districts and callback of the latest frame, not the first one.
        val onTap = rememberUpdatedState { content: Offset ->
            // Markers first, at their on-screen size; then the district under the finger.
            val hitMarker = markers.firstOrNull { marker ->
                val at = frame.toFrame(outline.world(marker.lat, marker.lon))
                hypot(at.x - content.x, at.y - content.y) * zoom.scale <= markerLarge + tapSlop
            }
            val district = hitMarker?.districtLgd
                ?: visible.firstOrNull { it.contains(frame.toWorld(content)) }?.lgd
            if (district != null) onSelectDistrict(district)
        }

        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / aspect)
                    .testTag("coverage-map")
                    .semantics {
                        contentDescription = "Coverage map of India. Every place is listed below."
                    }
                    .mapZoomGestures(zoom, size, reducedMotion) { content -> onTap.value(content) }
            ) {
                val pxPerWorld = frame.scale * zoom.scale
                val bucket = outlineBucketScale(pxPerWorld)
                val residual = pxPerWorld / bucket
                val origin = zoom.offset + frame.offset * zoom.scale
                val outlinePath = outlinePathFor(outline, bucket)

                translate(left = origin.x, top = origin.y) {
                    scale(scaleX = residual, scaleY = residual, pivot = Offset.Zero) {
                        // 1. The base: all of India in the "no record" tone.
                        drawPath(outlinePath, color = base, style = Fill)
                        clipPath(outlinePath) {
                            // 2-4. Fills, clipped to the outline; a district with no figure is left unfilled.
                            for (shape in visible) {
                                val fill = fills[shape.lgd] ?: continue
                                val bin = binOf(fill.value, breaks)
                                if (bin >= 0) drawPath(shape.path(bucket), color = bins[bin.coerceAtMost(bins.size - 1)])
                            }
                            // 5. Hairline district edges.
                            for (shape in visible) {
                                drawPath(shape.path(bucket), color = hairline, style = Stroke(width = hairPx / residual))
                            }
                            // 6. Dashed: a district drawn inside this parent.
                            for (shape in visible) {
                                if (fills[shape.lgd]?.hostsChild != true || shape.lgd == selectedLgd) continue
                                drawPath(
                                    shape.path(bucket),
                                    color = dashed,
                                    style = Stroke(
                                        width = hairPx * 2f / residual,
                                        pathEffect = PathEffect.dashPathEffect(
                                            floatArrayOf(3f * hairPx * 2f / residual, 2f * hairPx * 2f / residual)
                                        )
                                    )
                                )
                            }
                            // 7. The selected district.
                            visible.firstOrNull { it.lgd == selectedLgd }?.let {
                                drawPath(it.path(bucket), color = selectedInk, style = Stroke(width = selectedPx / residual))
                            }
                            // 8. ONE hatch path over every district with no workshop.
                            drawHatch(visible.filter { it.lgd in noWorkshop }, bucket, hatchGap / residual, hatchInk, hairPx * 2f / residual)
                        }
                        // 9. State borders and the outline on top.
                        stateBorders?.let {
                            drawPath(statePathFor(it, bucket), color = stateInk, style = Stroke(width = hairPx * 2.2f / residual))
                        }
                        drawPath(outlinePath, color = coast, style = Stroke(width = hairPx * 2.4f / residual))
                    }
                }

                // 10. Markers, at a constant on-screen size, folded where they crowd.
                val clusters = clusterMarkers(markers, clusterCell) { marker ->
                    val at = zoom.toScreen(frame.toFrame(outline.world(marker.lat, marker.lon)))
                    at.x to at.y
                }
                for (cluster in clusters) {
                    val many = cluster.markers.size > 1
                    val center = Offset(cluster.x, cluster.y)
                    val radius = if (many) markerLarge else markerSmall
                    val kinds = cluster.markers.map { it.kind }
                    val planned = kinds.all { it == "PLANNED" }
                    val grouped = kinds.all { it == "CLUSTER" }
                    val design = !planned && !grouped && kinds.any { it == "DESIGN_WORKSHOP" }
                    val rimPx = selectedPx
                    drawCircle(color = if (design) discDesign else discOther, radius = radius, center = center)
                    drawCircle(
                        color = if (design) rimDesign else rimOther,
                        radius = radius,
                        center = center,
                        style = Stroke(
                            width = rimPx,
                            pathEffect = if (planned) PathEffect.dashPathEffect(floatArrayOf(rimPx * 1.6f, rimPx * 1.2f)) else null
                        )
                    )
                    if (grouped) {
                        drawCircle(color = rimOther, radius = radius + rimPx * 1.5f, center = center, style = Stroke(width = rimPx * 0.6f))
                    }
                    if (many) {
                        val text = measurer.measure(
                            cluster.markers.size.toString(),
                            style = TextStyle(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (design) inkOnDesign else inkOnOther
                            )
                        )
                        drawText(text, topLeft = center - Offset(text.size.width / 2f, text.size.height / 2f))
                    }
                }
            }
            MapZoomResetButton(zoom, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
    }
}

/** The outline path, cached per geometry and bucket (a pinch crosses a bucket at most every 41%). */
private val outlineCache = HashMap<Pair<IndiaGeometry, Float>, Path>()
private val stateCache = HashMap<Pair<IndiaGeometry, Float>, Path>()

private fun outlinePathFor(geometry: IndiaGeometry, bucket: Float): Path =
    outlineCache.getOrPut(geometry to bucket) {
        if (outlineCache.size > 8) outlineCache.clear()
        buildOutlinePath(geometry, bucket).apply { fillType = PathFillType.EvenOdd }
    }

private fun statePathFor(geometry: IndiaGeometry, bucket: Float): Path =
    stateCache.getOrPut(geometry to bucket) {
        if (stateCache.size > 8) stateCache.clear()
        buildBorderPath(geometry, bucket)
    }

/** Diagonal lines, as ONE path, clipped to the union of [shapes]: the web's single hatch `<path>`. */
private fun DrawScope.drawHatch(shapes: List<DistrictShape>, bucket: Float, gap: Float, color: Color, width: Float) {
    if (shapes.isEmpty()) return
    val union = Path().apply { fillType = PathFillType.EvenOdd }
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (shape in shapes) {
        union.addPath(shape.path(bucket))
        left = min(left, shape.bounds.left * bucket)
        top = min(top, shape.bounds.top * bucket)
        right = max(right, shape.bounds.right * bucket)
        bottom = max(bottom, shape.bounds.bottom * bucket)
    }
    val lines = Path()
    val span = (right - left) + (bottom - top)
    var d = 0f
    while (d <= span) {
        lines.moveTo(left + d, top)
        lines.lineTo(left + d - (bottom - top), bottom)
        d += gap
    }
    clipPath(union) { drawPath(lines, color = color, style = Stroke(width = width)) }
}
