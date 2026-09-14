package com.offlinetracer.export

import com.offlinetracer.vector.FillRule
import com.offlinetracer.vector.LineCap
import com.offlinetracer.vector.LineJoin
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecSeg
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Encapsulated PostScript, DSC 3.0 / EPSF 3.0.
 *
 * EPS is here because print shops, sign makers and older CAD/CAM front ends still ask for it, and
 * because it is the only vector interchange that a RIP will accept without translation.
 *
 * The details that decide whether a placing application shows the artwork or an empty box:
 *
 *  - **`%%BoundingBox` must be four integers and must enclose everything drawn.** It is what the
 *    placing application uses to size the frame, and geometry outside it is simply clipped away.
 *    The box here is the union of the page and the real geometry bounds, widened by half a stroke
 *    width, floored and ceiled outward — never rounded, because rounding inward crops the artwork
 *    by up to half a point on every side.
 *  - `%%HiResBoundingBox` carries the same box unrounded, which is what modern applications
 *    prefer and what keeps a placed EPS from jittering by a fraction of a point.
 *  - **PostScript's origin is bottom-left with y growing upward.** As in the PDF writer, a single
 *    `concat` at the top of the page flips y so every coordinate below is unmodified document
 *    space.
 *
 * PostScript Level 2 has no alpha channel. Rather than dropping opacity silently, partly
 * transparent colours are composited against the page colour before being written: on a white
 * page that is exactly what the screen shows, and it is far better than a 20%-opacity guide line
 * printing as solid black.
 */
object EpsWriter {

    /**
     * Renders [doc] to an EPS 3.0 file.
     *
     * @param o `dpi` converts document units to points; `width`/`height`/`scale` resize the page;
     *   `background` paints a page colour and is also the colour partial alpha is composited
     *   against. No creation date is emitted — the export must be byte-reproducible.
     * @return the complete EPS file bytes, 7-bit ASCII throughout. A document with no shapes
     *   yields a valid empty page whose bounding box is the page box.
     */
    fun export(doc: VecDocument, o: ExportOptions): ByteArray {
        val docW = if (doc.width.isFinite() && doc.width > 0f) doc.width else 1f
        val docH = if (doc.height.isFinite() && doc.height > 0f) doc.height else 1f
        val s = ExportGeom.scaleXY(o, docW, docH)
        val unit = ExportGeom.POINTS_PER_INCH / o.effectiveDpi.toFloat()
        val kx = s[0] * unit
        val ky = s[1] * unit
        val pageW = docW * kx
        val pageH = docH * ky
        val p = if (o.precision < 1) 1 else if (o.precision > 6) 6 else o.precision

        val background = o.background ?: doc.background
        // Partial alpha is flattened against whatever the page actually is; an unset background
        // is a white sheet, which is what an EPS is printed on.
        val pageColour = if (background != null && ((background ushr 24) and 0xFF) == 0xFF) {
            background
        } else {
            0xFFFFFFFF.toInt()
        }

        val body = StringBuilder(4096)
        if (background != null && ((background ushr 24) and 0xFF) != 0) {
            appendColour(body, flatten(background, pageColour, 1f))
            body.append("n 0 0 m ").append(ExportGeom.num(docW, p)).append(" 0 l ")
                .append(ExportGeom.num(docW, p)).append(' ').append(ExportGeom.num(docH, p))
                .append(" l 0 ").append(ExportGeom.num(docH, p)).append(" l h F\n")
        }

        for (layer in doc.layers) {
            if (!layer.visible) continue
            val layerOpacity = clamp01(layer.opacity)
            if (layerOpacity <= 0f) continue
            for (shape in layer.shapes) {
                val path = shape.path
                if (path.isEmpty()) continue
                val style = shape.style
                val opacity = layerOpacity * clamp01(style.opacity)
                if (opacity <= 0f) continue

                val fillColour = style.fill
                val strokeColour = style.stroke
                val fillAlpha = if (fillColour == null) 0f else opacity * ExportGeom.alpha(fillColour)
                val strokeAlpha =
                    if (strokeColour == null || style.strokeWidth <= 0f) 0f
                    else opacity * ExportGeom.alpha(strokeColour)
                val doFill = fillColour != null && fillAlpha > 0.002f
                val doStroke = strokeColour != null && strokeAlpha > 0.002f
                if (!doFill && !doStroke) continue

                body.append("gs\n")
                appendPath(body, path, p)
                val fillOp = if (style.fillRule == FillRule.EVENODD) "E" else "F"
                if (doFill && doStroke) {
                    // gsave/grestore around the fill is what preserves the current path for the
                    // stroke that follows; fill consumes it otherwise and the stroke draws nothing.
                    body.append("gs ")
                    appendColour(body, flatten(fillColour!!, pageColour, fillAlpha))
                    body.append(fillOp).append(" gr\n")
                    appendStrokeState(body, style.strokeWidth, style.cap, style.join, style.miterLimit, p)
                    appendColour(body, flatten(strokeColour!!, pageColour, strokeAlpha))
                    body.append("S\n")
                } else if (doFill) {
                    appendColour(body, flatten(fillColour!!, pageColour, fillAlpha))
                    body.append(fillOp).append('\n')
                } else {
                    appendStrokeState(body, style.strokeWidth, style.cap, style.join, style.miterLimit, p)
                    appendColour(body, flatten(strokeColour!!, pageColour, strokeAlpha))
                    body.append("S\n")
                }
                body.append("gr\n")
            }
        }

        // Bounding box, in EPS (y-up) space.
        var x0 = 0f
        var y0 = 0f
        var x1 = pageW
        var y1 = pageH
        val gb = ExportGeom.geometryBounds(doc)
        if (gb != null) {
            val gx0 = gb[0] * kx
            val gx1 = gb[2] * kx
            val gy0 = pageH - gb[3] * ky
            val gy1 = pageH - gb[1] * ky
            if (gx0 < x0) x0 = gx0
            if (gy0 < y0) y0 = gy0
            if (gx1 > x1) x1 = gx1
            if (gy1 > y1) y1 = gy1
        }
        val bx0 = floor(x0.toDouble()).toInt()
        val by0 = floor(y0.toDouble()).toInt()
        val bx1 = ceil(x1.toDouble()).toInt()
        val by1 = ceil(y1.toDouble()).toInt()

        val out = StringBuilder(body.length + 1024)
        out.append("%!PS-Adobe-3.0 EPSF-3.0\n")
        out.append("%%Creator: Offline Tracer\n")
        if (o.includeMetadata) out.append("%%Title: Offline Tracer export\n")
        out.append("%%BoundingBox: ").append(bx0).append(' ').append(by0).append(' ')
            .append(bx1).append(' ').append(by1).append('\n')
        out.append("%%HiResBoundingBox: ").append(ExportGeom.num(x0, 4)).append(' ')
            .append(ExportGeom.num(y0, 4)).append(' ').append(ExportGeom.num(x1, 4)).append(' ')
            .append(ExportGeom.num(y1, 4)).append('\n')
        out.append("%%LanguageLevel: 2\n")
        out.append("%%DocumentData: Clean7Bit\n")
        out.append("%%Pages: 1\n")
        out.append("%%EndComments\n")

        out.append("%%BeginProlog\n")
        // Short operator aliases live in a private dictionary so that embedding this EPS inside
        // another PostScript job cannot collide with the host's definitions.
        out.append("/OTdict 32 dict def\n")
        out.append("OTdict begin\n")
        out.append("/n {newpath} bind def\n")
        out.append("/m {moveto} bind def\n")
        out.append("/l {lineto} bind def\n")
        out.append("/c {curveto} bind def\n")
        out.append("/h {closepath} bind def\n")
        out.append("/S {stroke} bind def\n")
        out.append("/F {fill} bind def\n")
        out.append("/E {eofill} bind def\n")
        out.append("/w {setlinewidth} bind def\n")
        out.append("/J {setlinecap} bind def\n")
        out.append("/j {setlinejoin} bind def\n")
        out.append("/M {setmiterlimit} bind def\n")
        out.append("/rgb {setrgbcolor} bind def\n")
        out.append("/gs {gsave} bind def\n")
        out.append("/gr {grestore} bind def\n")
        out.append("end\n")
        out.append("%%EndProlog\n")

        out.append("%%Page: 1 1\n")
        out.append("OTdict begin\n")
        out.append("gs\n")
        out.append('[').append(ExportGeom.num(kx, 6)).append(" 0 0 ")
            .append(ExportGeom.num(-ky, 6)).append(" 0 ").append(ExportGeom.num(pageH, 4))
            .append("] concat\n")
        out.append(body)
        out.append("gr\n")
        out.append("end\n")
        out.append("showpage\n")
        out.append("%%EOF\n")

        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun appendStrokeState(
        sb: StringBuilder,
        width: Float,
        cap: LineCap,
        join: LineJoin,
        miterLimit: Float,
        p: Int,
    ) {
        sb.append(ExportGeom.num(width, p)).append(" w ")
            .append(capCode(cap)).append(" J ")
            .append(joinCode(join)).append(" j ")
            .append(ExportGeom.num(if (miterLimit >= 1f) miterLimit else 1f, p)).append(" M\n")
    }

    private fun appendPath(sb: StringBuilder, path: VecPath, p: Int) {
        var cx = path.start.x
        var cy = path.start.y
        sb.append("n ").append(ExportGeom.num(cx, p)).append(' ')
            .append(ExportGeom.num(cy, p)).append(" m\n")
        for (seg in path.segments) {
            when (seg) {
                is VecSeg.Line -> {
                    sb.append(ExportGeom.num(seg.to.x, p)).append(' ')
                        .append(ExportGeom.num(seg.to.y, p)).append(" l\n")
                    cx = seg.to.x
                    cy = seg.to.y
                }

                is VecSeg.Cubic -> {
                    sb.append(ExportGeom.num(seg.c1.x, p)).append(' ')
                        .append(ExportGeom.num(seg.c1.y, p)).append(' ')
                        .append(ExportGeom.num(seg.c2.x, p)).append(' ')
                        .append(ExportGeom.num(seg.c2.y, p)).append(' ')
                        .append(ExportGeom.num(seg.to.x, p)).append(' ')
                        .append(ExportGeom.num(seg.to.y, p)).append(" c\n")
                    cx = seg.to.x
                    cy = seg.to.y
                }

                is VecSeg.Quad -> {
                    // PostScript has no quadratic operator either; same exact 2/3 lift as the PDF
                    // writer so both exports trace the identical curve.
                    val c1x = cx + 2f / 3f * (seg.c.x - cx)
                    val c1y = cy + 2f / 3f * (seg.c.y - cy)
                    val c2x = seg.to.x + 2f / 3f * (seg.c.x - seg.to.x)
                    val c2y = seg.to.y + 2f / 3f * (seg.c.y - seg.to.y)
                    sb.append(ExportGeom.num(c1x, p)).append(' ').append(ExportGeom.num(c1y, p))
                        .append(' ').append(ExportGeom.num(c2x, p)).append(' ')
                        .append(ExportGeom.num(c2y, p)).append(' ')
                        .append(ExportGeom.num(seg.to.x, p)).append(' ')
                        .append(ExportGeom.num(seg.to.y, p)).append(" c\n")
                    cx = seg.to.x
                    cy = seg.to.y
                }
            }
        }
        if (path.closed) sb.append("h\n")
    }

    private fun appendColour(sb: StringBuilder, rgb: Int) {
        sb.append(ExportGeom.num(ExportGeom.red(rgb), 4)).append(' ')
            .append(ExportGeom.num(ExportGeom.green(rgb), 4)).append(' ')
            .append(ExportGeom.num(ExportGeom.blue(rgb), 4)).append(" rgb ")
    }

    /** Source-over composite of [argb] at [alpha] onto the opaque [page] colour. */
    private fun flatten(argb: Int, page: Int, alpha: Float): Int {
        val a = clamp01(alpha)
        if (a >= 0.999f) return argb
        val r = (ExportGeom.red(argb) * a + ExportGeom.red(page) * (1f - a)) * 255f
        val g = (ExportGeom.green(argb) * a + ExportGeom.green(page) * (1f - a)) * 255f
        val b = (ExportGeom.blue(argb) * a + ExportGeom.blue(page) * (1f - a)) * 255f
        return (0xFF shl 24) or (round255(r) shl 16) or (round255(g) shl 8) or round255(b)
    }

    private fun round255(v: Float): Int {
        val i = (v + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    private fun capCode(cap: LineCap): Int = when (cap) {
        LineCap.BUTT -> 0
        LineCap.ROUND -> 1
        LineCap.SQUARE -> 2
    }

    private fun joinCode(join: LineJoin): Int = when (join) {
        LineJoin.MITER -> 0
        LineJoin.ROUND -> 1
        LineJoin.BEVEL -> 2
    }

    private fun clamp01(v: Float): Float =
        if (!v.isFinite()) 0f else if (v < 0f) 0f else if (v > 1f) 1f else v
}
