package com.offlinetracer.vector

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Mask
import com.offlinetracer.imaging.Px
import com.offlinetracer.imaging.RgbaImage
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Scanline rasterisation of vector geometry, for previews and for raster export.
 *
 * Coverage is computed on an **ordered 4×4 sub-pixel grid** (16 samples per pixel by default): the
 * scanline is evaluated at each of the 4 sub-rows and spans are accumulated into the 4 sub-columns.
 * An ordered grid rather than a stochastic one because the engine is required to be deterministic —
 * two runs on the same input must produce byte-identical output — and because on the long, nearly
 * straight edges that dominate traced line art a regular grid is visually cleaner than jittered
 * sampling at the same cost.
 *
 * Both fill rules are implemented. Strokes are converted to outlines by [StrokeStyle] first and then
 * filled **non-zero**, which is what SVG requires and what makes a self-overlapping stroke render
 * solid instead of showing a seam where the offset lobes cross.
 */
object Raster {

    /** Curve flattening tolerance used when rasterising. Finer than a pixel, so it never shows. */
    private const val FLATTEN_TOLERANCE = 0.25f

    /**
     * Rasterises the union of [paths] into a coverage image in 0..1 under [rule].
     *
     * Open paths are filled as if closed, exactly as SVG's `fill` does. [samples] is the sub-grid
     * size **per axis** (4 means 16 samples per pixel) and is clamped to 1..8. Non-positive
     * dimensions are clamped up to 1 rather than throwing, so a zero-sized preview request returns
     * an empty image instead of taking down the caller.
     */
    fun fill(paths: List<VecPath>, w: Int, h: Int, rule: FillRule, samples: Int = 4): GrayF {
        val width = if (w < 1) 1 else w
        val height = if (h < 1) 1 else h
        val ss = if (samples < 1) 1 else if (samples > 8) 8 else samples
        val out = GrayF(width, height)
        if (paths.isEmpty()) return out

        // -- edge table -----------------------------------------------------------------------
        val rings = ArrayList<List<VecPoint>>(paths.size)
        var cap = 0
        for (p in paths) {
            val pts = p.flatten(FLATTEN_TOLERANCE)
            if (pts.size >= 2) {
                rings.add(pts)
                cap += pts.size
            }
        }
        if (cap == 0) return out

        val eYMin = FloatArray(cap)
        val eYMax = FloatArray(cap)
        val eX = FloatArray(cap)
        val eSlope = FloatArray(cap)
        val eDir = IntArray(cap)
        var ne = 0
        for (pts in rings) {
            val m = pts.size
            for (i in 0 until m) {
                val a = pts[i]
                val b = pts[(i + 1) % m]
                if (!a.x.isFinite() || !a.y.isFinite() || !b.x.isFinite() || !b.y.isFinite()) continue
                if (a.y == b.y) continue
                if (a.y < b.y) {
                    eYMin[ne] = a.y; eYMax[ne] = b.y; eX[ne] = a.x
                    eSlope[ne] = (b.x - a.x) / (b.y - a.y); eDir[ne] = 1
                } else {
                    eYMin[ne] = b.y; eYMax[ne] = a.y; eX[ne] = b.x
                    eSlope[ne] = (a.x - b.x) / (a.y - b.y); eDir[ne] = -1
                }
                ne++
            }
        }
        if (ne == 0) return out

        // Sorted by the sub-row an edge becomes active on, packed into a LongArray so the sort is
        // primitive: sorting an IntArray by a Float key otherwise means boxing every index.
        //
        // The key must be `ceil(y * ss - 0.5)` and not `floor(y * ss)`. Sub-rows are sampled at
        // `yPos = py + (sy + 0.5) / ss`, i.e. at half-integer multiples of `1 / ss`, so the first
        // sub-row on which an edge is active satisfies `py * ss + sy + 0.5 >= yMin * ss` — which is
        // `ceil(yMin * ss - 0.5)`, half a sub-row above the floor.
        //
        // Getting this wrong drops whole scanlines rather than merely reordering them, because the
        // activation loop below BREAKS on the first edge with `eYMin > yPos` and `ptr` never rewinds.
        // A floor key puts edges from the upper and lower halves of one `1 / ss` bucket together,
        // ordered by edge index; a single upper-half edge sitting at a low index then blocks every
        // later edge in that bucket permanently, `na` stays 0, and the `na == 0` guard abandons the
        // sub-scanline — including the parts of the shape that had nothing to do with that edge.
        //
        // It survived every axis-aligned and 45° fixture in the suite because edges sharing a bucket
        // there share an identical `eYMin`, so the tie cannot mis-order. It takes a descending run of
        // vertices spaced under `1 / ss` apart to trigger — which is to say, every flattened Bezier,
        // every round cap and every traced contour in real artwork. Measured on a 12-chord round cap:
        // four of eight sub-rows emitted no crossings at all, and the stroke lost half its coverage.
        val order = LongArray(ne)
        for (i in 0 until ne) {
            var k = ceil(eYMin[i].toDouble() * ss - 0.5).toLong() + 0x4000_0000L
            if (k < 0L) k = 0L
            if (k > 0x7FFF_FFFFL) k = 0x7FFF_FFFFL
            order[i] = (k shl 32) or i.toLong()
        }
        java.util.Arrays.sort(order)

        var gMin = Float.POSITIVE_INFINITY
        var gMax = Float.NEGATIVE_INFINITY
        for (i in 0 until ne) {
            if (eYMin[i] < gMin) gMin = eYMin[i]
            if (eYMax[i] > gMax) gMax = eYMax[i]
        }
        var rowStart = floor(gMin.toDouble()).toInt()
        var rowEnd = ceil(gMax.toDouble()).toInt()
        if (rowStart < 0) rowStart = 0
        if (rowEnd > height) rowEnd = height
        if (rowStart >= rowEnd) return out

        // -- sweep ----------------------------------------------------------------------------
        val active = IntArray(ne)
        val xs = FloatArray(ne)
        val dirs = IntArray(ne)
        val cov = IntArray(width)
        val data = out.data
        val invTotal = 1f / (ss * ss).toFloat()
        var na = 0
        var ptr = 0

        for (py in rowStart until rowEnd) {
            java.util.Arrays.fill(cov, 0)
            for (sy in 0 until ss) {
                val yPos = py + (sy + 0.5f) / ss

                while (ptr < ne) {
                    val e = (order[ptr] and 0xFFFF_FFFFL).toInt()
                    if (eYMin[e] > yPos) break
                    active[na++] = e
                    ptr++
                }
                var keep = 0
                for (t in 0 until na) {
                    val e = active[t]
                    if (eYMax[e] > yPos) active[keep++] = e
                }
                na = keep
                if (na == 0) continue

                var nc = 0
                for (t in 0 until na) {
                    val e = active[t]
                    if (yPos < eYMin[e]) continue
                    xs[nc] = eX[e] + (yPos - eYMin[e]) * eSlope[e]
                    dirs[nc] = eDir[e]
                    nc++
                }
                if (nc < 2) continue

                // Insertion sort: crossing counts per scanline are small even for complex art, and
                // the list is nearly sorted from the previous sub-row.
                for (t in 1 until nc) {
                    val xv = xs[t]
                    val dv = dirs[t]
                    var u = t - 1
                    while (u >= 0 && xs[u] > xv) {
                        xs[u + 1] = xs[u]
                        dirs[u + 1] = dirs[u]
                        u--
                    }
                    xs[u + 1] = xv
                    dirs[u + 1] = dv
                }

                if (rule == FillRule.EVENODD) {
                    var t = 0
                    while (t + 1 < nc) {
                        addSpan(cov, width, ss, xs[t], xs[t + 1])
                        t += 2
                    }
                } else {
                    var wind = 0
                    var spanStart = 0f
                    for (t in 0 until nc) {
                        val before = wind
                        wind += dirs[t]
                        if (before == 0 && wind != 0) spanStart = xs[t]
                        else if (before != 0 && wind == 0) addSpan(cov, width, ss, spanStart, xs[t])
                    }
                }
            }
            val base = py * width
            for (x in 0 until width) {
                val c = cov[x]
                if (c != 0) data[base + x] = c * invTotal
            }
        }
        return out
    }

    /**
     * Binary version of [fill]: a pixel is set when its supersampled coverage reaches 50%.
     *
     * Thresholding antialiased coverage rather than point-sampling pixel centres is what keeps a
     * thin diagonal stroke connected in the mask instead of breaking it into dots.
     */
    fun toMask(paths: List<VecPath>, w: Int, h: Int, rule: FillRule): Mask {
        val cov = fill(paths, w, h, rule, 4)
        val out = Mask(cov.width, cov.height)
        val src = cov.data
        val dst = out.data
        for (i in src.indices) dst[i] = src[i] >= 0.5f
        return out
    }

    /**
     * Renders [doc] into a new [RgbaImage] of the requested size over [background] (an ARGB int;
     * pass 0 for transparent).
     *
     * The document is scaled to fit `w × h` exactly — aspect ratio is the caller's business, since
     * export dialogs already enforce it. Hidden layers are skipped and layer opacity is folded into
     * each shape's opacity.
     */
    fun render(doc: VecDocument, w: Int, h: Int, background: Int, samples: Int = 4): RgbaImage {
        val width = if (w < 1) 1 else w
        val height = if (h < 1) 1 else h
        val target = RgbaImage(width, height).fill(background)
        val sx = if (doc.width > 0f && doc.width.isFinite()) width / doc.width else 1f
        val sy = if (doc.height > 0f && doc.height.isFinite()) height / doc.height else 1f
        val m = Mat2D.scale(sx, sy)
        for (layer in doc.layers) {
            if (!layer.visible) continue
            val layerOpacity = Px.clamp01(layer.opacity)
            if (layerOpacity <= 0f) continue
            for (shape in layer.shapes) {
                val style = shape.style
                val scaled = VecShape(
                    shape.path.transform(m),
                    style.copy(
                        strokeWidth = style.strokeWidth * ((sx + sy) * 0.5f),
                        opacity = Px.clamp01(style.opacity) * layerOpacity,
                    ),
                )
                renderShape(target, scaled, samples)
            }
        }
        return target
    }

    /**
     * Composites one shape onto [target] with source-over alpha, in the target's own pixel
     * coordinates (no transform is applied — [render] scales beforehand).
     *
     * Fill is drawn first, then stroke, which is the SVG painting order. A path carrying
     * `strokeWidths` is stroked as a variable-width outline; that is the only place a modulated
     * width becomes visible pixels.
     */
    fun renderShape(target: RgbaImage, shape: VecShape, samples: Int = 4) {
        val path = shape.path
        if (path.segments.isEmpty()) return
        val style = shape.style
        val opacity = Px.clamp01(style.opacity)
        if (opacity <= 0f) return

        val fillColor = style.fill
        if (fillColor != null) {
            val cov = fill(listOf(path), target.width, target.height, style.fillRule, samples)
            composite(target, cov, fillColor, opacity)
        }

        val strokeColor = style.stroke
        if (strokeColor != null && style.strokeWidth > 0f && style.strokeWidth.isFinite()) {
            val widths = path.strokeWidths
            val outline = if (widths != null && widths.isNotEmpty()) {
                StrokeStyle.variableWidthOutline(path, widths, style.cap)
            } else {
                StrokeStyle.outlineStroke(path, style.strokeWidth, style.cap, style.join, style.miterLimit)
            }
            if (outline.segments.isNotEmpty()) {
                val cov = fill(listOf(outline), target.width, target.height, FillRule.NONZERO, samples)
                composite(target, cov, strokeColor, opacity)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    /**
     * Accumulates a span `[xa, xb)` of one sub-row into the per-pixel sub-sample counters.
     *
     * Work is done in sub-column index space so the interior pixels of a wide span cost one add
     * each instead of one add per sub-sample.
     */
    private fun addSpan(cov: IntArray, w: Int, ss: Int, xa: Float, xb: Float) {
        if (!(xb > xa)) return
        var j0 = ceil(xa.toDouble() * ss - 0.5).toInt()
        var j1 = ceil(xb.toDouble() * ss - 0.5).toInt() - 1
        if (j0 < 0) j0 = 0
        val maxJ = w * ss - 1
        if (j1 > maxJ) j1 = maxJ
        if (j1 < j0) return
        val p0 = j0 / ss
        val p1 = j1 / ss
        if (p0 == p1) {
            cov[p0] += j1 - j0 + 1
            return
        }
        cov[p0] += (p0 + 1) * ss - j0
        for (p in p0 + 1 until p1) cov[p] += ss
        cov[p1] += j1 - p1 * ss + 1
    }

    private fun composite(target: RgbaImage, coverage: GrayF, argb: Int, opacity: Float) {
        val srcA = ((argb ushr 24) and 0xFF) / 255f * opacity
        if (srcA <= 0f) return
        val srcR = ((argb ushr 16) and 0xFF).toFloat()
        val srcG = ((argb ushr 8) and 0xFF).toFloat()
        val srcB = (argb and 0xFF).toFloat()
        val px = target.pixels
        val cd = coverage.data
        val n = if (px.size < cd.size) px.size else cd.size
        for (i in 0 until n) {
            val c = cd[i]
            if (c <= 0f) continue
            val a = srcA * (if (c > 1f) 1f else c)
            if (a <= 0f) continue
            val d = px[i]
            val dstA = ((d ushr 24) and 0xFF) / 255f
            val keep = dstA * (1f - a)
            val outA = a + keep
            if (outA <= 0f) {
                px[i] = 0
                continue
            }
            val r = (srcR * a + ((d ushr 16) and 0xFF) * keep) / outA
            val g = (srcG * a + ((d ushr 8) and 0xFF) * keep) / outA
            val b = (srcB * a + (d and 0xFF) * keep) / outA
            px[i] = RgbaImage.argb(Px.toByte255(outA), round255(r), round255(g), round255(b))
        }
    }

    private fun round255(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }
}
