package com.offlinetracer.export

import com.offlinetracer.vector.FillRule
import com.offlinetracer.vector.LineCap
import com.offlinetracer.vector.LineJoin
import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecPath
import com.offlinetracer.vector.VecSeg
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * A real single-page PDF 1.4 writer: header, catalog, page tree, content stream, resources,
 * a byte-accurate cross-reference table and a trailer.
 *
 * Three things decide whether the file opens at all:
 *
 *  - **The xref offsets must be exact byte positions of each `N 0 obj`.** Acrobat rebuilds a
 *    broken table and says nothing, which is precisely why a wrong table survives testing and
 *    then fails in whatever the user actually opens it with. Offsets here are read back off the
 *    output stream at the moment each object starts, so they cannot drift.
 *  - **PDF's origin is bottom-left with y growing upward**, the opposite of the engine's image
 *    coordinates. One `cm` at the top of the content stream flips y for everything that follows,
 *    which keeps every emitted coordinate in unmodified document space. Flipping per-path instead
 *    is the usual route to an upside-down export.
 *  - **The page is measured in points**, 1/72 inch. Document units are pixels at
 *    [ExportOptions.dpi], so the conversion is `72 / dpi` and a 3000px trace at 300 dpi prints as
 *    a 10 inch page rather than a 41 foot one.
 *
 * The content stream is left uncompressed. It costs size on a dense trace, but a PDF whose
 * content can be read in a text editor is a PDF whose geometry bugs can be diagnosed from a user's
 * emailed file, and that has been worth more than the bytes.
 */
object PdfWriter {

    private const val OBJ_CATALOG = 1
    private const val OBJ_PAGES = 2
    private const val OBJ_PAGE = 3
    private const val OBJ_RESOURCES = 4
    private const val OBJ_CONTENT = 5
    private const val OBJ_FIRST_GS = 6

    /** Content stream text plus the transparency states it referenced, in resource order. */
    private class Content(val text: String, val gs: List<FloatArray>)

    /**
     * Renders [doc] to a one-page PDF 1.4 file.
     *
     * @param o `dpi` converts document units to points; `width`/`height`/`scale` resize the page;
     *   `background` paints an opaque page colour when non-null; `includeMetadata` adds an `/Info`
     *   dictionary. No timestamp is written — the export must be byte-reproducible.
     * @return the complete PDF file bytes. A document with no shapes yields a valid empty page.
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

        val content = buildContent(doc, o, kx, ky, pageW, pageH, docW, docH)
        val bodyBytes = content.text.toByteArray(Charsets.ISO_8859_1)
        val gsCount = content.gs.size
        val infoNum = if (o.includeMetadata) OBJ_FIRST_GS + gsCount else 0
        val maxObj = OBJ_CONTENT + gsCount + (if (o.includeMetadata) 1 else 0)

        val out = ByteArrayOutputStream(bodyBytes.size + 2048)
        val offsets = IntArray(maxObj + 1)

        fun w(text: String) {
            out.write(text.toByteArray(Charsets.ISO_8859_1))
        }

        fun obj(n: Int, body: String) {
            offsets[n] = out.size()
            w("$n 0 obj\n")
            w(body)
            w("\nendobj\n")
        }

        w("%PDF-1.4\n")
        // A comment of bytes >= 128 on line 2 is what tells transfer agents and repair tools that
        // this file is binary. Omitting it makes some FTP/mail paths mangle the stream.
        out.write(
            byteArrayOf(
                '%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(),
                '\n'.code.toByte(),
            )
        )

        obj(OBJ_CATALOG, "<< /Type /Catalog /Pages $OBJ_PAGES 0 R >>")
        obj(OBJ_PAGES, "<< /Type /Pages /Kids [$OBJ_PAGE 0 R] /Count 1 >>")
        obj(
            OBJ_PAGE,
            "<< /Type /Page /Parent $OBJ_PAGES 0 R" +
                " /MediaBox [0 0 ${ExportGeom.num(pageW, 3)} ${ExportGeom.num(pageH, 3)}]" +
                " /Resources $OBJ_RESOURCES 0 R /Contents $OBJ_CONTENT 0 R >>",
        )

        val res = StringBuilder(96)
        res.append("<< /ProcSet [/PDF]")
        if (gsCount > 0) {
            res.append(" /ExtGState <<")
            var i = 0
            while (i < gsCount) {
                res.append(" /GS").append(i).append(' ').append(OBJ_FIRST_GS + i).append(" 0 R")
                i++
            }
            res.append(" >>")
        }
        res.append(" >>")
        obj(OBJ_RESOURCES, res.toString())

        offsets[OBJ_CONTENT] = out.size()
        w("$OBJ_CONTENT 0 obj\n<< /Length ${bodyBytes.size} >>\nstream\n")
        out.write(bodyBytes)
        w("\nendstream\nendobj\n")

        var i = 0
        while (i < gsCount) {
            val v = content.gs[i]
            obj(
                OBJ_FIRST_GS + i,
                "<< /Type /ExtGState /ca ${ExportGeom.num(v[0], 4)} /CA ${ExportGeom.num(v[1], 4)} >>",
            )
            i++
        }

        if (infoNum > 0) {
            obj(
                infoNum,
                "<< /Producer (Offline Tracer) /Creator (Offline Tracer)" +
                    " /Title (${pdfString("Offline Tracer export")}) >>",
            )
        }

        val xrefOffset = out.size()
        w("xref\n0 ${maxObj + 1}\n")
        // Every entry is exactly 20 bytes including the two-byte EOL, and readers seek by
        // multiplying the object number by 20. A single missing pad character shifts every
        // lookup after it.
        w("0000000000 65535 f\r\n")
        var n = 1
        while (n <= maxObj) {
            w(pad10(offsets[n]))
            w(" 00000 n\r\n")
            n++
        }
        w("trailer\n<< /Size ${maxObj + 1} /Root $OBJ_CATALOG 0 R")
        if (infoNum > 0) w(" /Info $infoNum 0 R")
        w(" >>\n")
        w("startxref\n$xrefOffset\n%%EOF\n")

        return out.toByteArray()
    }

    // ---------------------------------------------------------------- content stream

    private fun buildContent(
        doc: VecDocument,
        o: ExportOptions,
        kx: Float,
        ky: Float,
        pageW: Float,
        pageH: Float,
        docW: Float,
        docH: Float,
    ): Content {
        val p = if (o.precision < 1) 1 else if (o.precision > 6) 6 else o.precision
        val sb = StringBuilder(4096)
        val gsList = ArrayList<FloatArray>()
        val gsIndex = HashMap<Long, Int>()

        fun gsName(fillAlpha: Float, strokeAlpha: Float): String? {
            if (fillAlpha >= 0.999f && strokeAlpha >= 0.999f) return null
            val key = ((fillAlpha * 1000f).roundToInt().toLong() shl 32) or
                ((strokeAlpha * 1000f).roundToInt().toLong() and 0xFFFFFFFFL)
            var idx = gsIndex[key]
            if (idx == null) {
                idx = gsList.size
                gsList.add(floatArrayOf(fillAlpha, strokeAlpha))
                gsIndex[key] = idx
            }
            return "GS$idx"
        }

        sb.append("q\n")
        // Clip to the page before the flip. Control points from a bad fit can land far outside
        // the artboard, and a viewer that honours the clip shows the page rather than a document
        // whose zoom-to-fit is a thousand times too wide.
        sb.append("0 0 ").append(ExportGeom.num(pageW, 3)).append(' ')
            .append(ExportGeom.num(pageH, 3)).append(" re W n\n")
        // The y-flip. Everything after this line is written in unmodified document coordinates.
        sb.append(ExportGeom.num(kx, 6)).append(" 0 0 ").append(ExportGeom.num(-ky, 6))
            .append(" 0 ").append(ExportGeom.num(pageH, 3)).append(" cm\n")

        val bg = o.background ?: doc.background
        if (bg != null && ((bg ushr 24) and 0xFF) != 0) {
            val bgAlpha = ExportGeom.alpha(bg)
            sb.append("q\n")
            val name = gsName(bgAlpha, 1f)
            if (name != null) sb.append('/').append(name).append(" gs\n")
            appendColour(sb, bg, false)
            sb.append("0 0 ").append(ExportGeom.num(docW, p)).append(' ')
                .append(ExportGeom.num(docH, p)).append(" re f\n")
            sb.append("Q\n")
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

                sb.append("q\n")
                val name = gsName(if (doFill) fillAlpha else 1f, if (doStroke) strokeAlpha else 1f)
                if (name != null) sb.append('/').append(name).append(" gs\n")
                if (doFill) appendColour(sb, fillColour!!, false)
                if (doStroke) {
                    appendColour(sb, strokeColour!!, true)
                    sb.append(ExportGeom.num(style.strokeWidth, p)).append(" w\n")
                    sb.append(capCode(style.cap)).append(" J\n")
                    sb.append(joinCode(style.join)).append(" j\n")
                    val miter = if (style.miterLimit >= 1f) style.miterLimit else 1f
                    sb.append(ExportGeom.num(miter, p)).append(" M\n")
                }
                appendPath(sb, path, p)
                val evenOdd = style.fillRule == FillRule.EVENODD
                val op = if (doFill && doStroke) {
                    if (evenOdd) "B*" else "B"
                } else if (doFill) {
                    if (evenOdd) "f*" else "f"
                } else {
                    "S"
                }
                sb.append(op).append('\n')
                sb.append("Q\n")
            }
        }

        sb.append("Q\n")
        return Content(sb.toString(), gsList)
    }

    private fun appendPath(sb: StringBuilder, path: VecPath, p: Int) {
        var cx = path.start.x
        var cy = path.start.y
        sb.append(ExportGeom.num(cx, p)).append(' ').append(ExportGeom.num(cy, p)).append(" m\n")
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
                    // PDF has no quadratic operator. The exact cubic equivalent lifts the control
                    // point two thirds of the way from each endpoint; approximating it instead
                    // visibly flattens tight corners.
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

    private fun appendColour(sb: StringBuilder, argb: Int, stroke: Boolean) {
        sb.append(ExportGeom.num(ExportGeom.red(argb), 4)).append(' ')
            .append(ExportGeom.num(ExportGeom.green(argb), 4)).append(' ')
            .append(ExportGeom.num(ExportGeom.blue(argb), 4))
            .append(if (stroke) " RG\n" else " rg\n")
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

    /** Escapes the three characters that terminate or nest a PDF literal string. */
    private fun pdfString(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when (ch) {
                '(', ')', '\\' -> {
                    sb.append('\\')
                    sb.append(ch)
                }

                '\n', '\r' -> sb.append(' ')
                else -> if (ch.code in 32..126) sb.append(ch) else sb.append('?')
            }
        }
        return sb.toString()
    }

    private fun pad10(v: Int): String {
        val s = v.toString()
        if (s.length >= 10) return s
        val sb = StringBuilder(10)
        var i = s.length
        while (i < 10) {
            sb.append('0')
            i++
        }
        sb.append(s)
        return sb.toString()
    }
}
