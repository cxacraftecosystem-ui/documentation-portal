package com.offlinetracer.vector

/**
 * SVG 1.1 serialisation of a [VecDocument].
 *
 * The output is deliberately plain: `width`/`height`/`viewBox` on the root, one `<g>` per layer
 * carrying the layer's id, name, opacity and visibility, and one `<path>` per shape. No transforms,
 * no `<defs>`, no CSS classes — every consumer of a traced file (Illustrator, Inkscape, a laser
 * cutter's job setup, a plotter, a browser) reads that subset identically, and each thing added
 * beyond it is another thing one of them reads differently.
 *
 * Two invariants matter more than the formatting:
 *
 *  - **Every attribute value is escaped.** Layer names come from the user, and an unescaped `&` in
 *    a layer name produces a file no XML parser will open.
 *  - **`NaN` and `Infinity` are never emitted.** A single non-finite coordinate makes a renderer
 *    drop the whole path, so [SvgPathData.num] clamps instead; see its KDoc.
 */
object SvgWriter {

    data class SvgOptions(
        val precision: Int = 2,
        val includeMetadata: Boolean = true,
        val groupByLayer: Boolean = true,
        val prettyPrint: Boolean = true,
        val widthUnit: String = "px",
        val title: String = "Offline Tracer export",
    )

    private const val MAX_DIMENSION = 1.0e7f

    /**
     * Serialises [doc] to a complete SVG 1.1 document, including the XML declaration.
     *
     * A non-positive or non-finite document size falls back to 1 unit so the file still opens; a
     * shape with no segments is skipped rather than emitted as an empty `<path>`. Paths carrying
     * `strokeWidths` are written as filled variable-width outlines, since SVG has no way to express
     * a stroke whose width varies along its length.
     */
    fun write(doc: VecDocument, options: SvgOptions = SvgOptions()): String {
        val p = if (options.precision < 0) 0 else if (options.precision > 6) 6 else options.precision
        val nl = if (options.prettyPrint) "\n" else ""
        val i1 = if (options.prettyPrint) "  " else ""
        val i2 = if (options.prettyPrint) "    " else ""

        val w = sanitizeDimension(doc.width)
        val h = sanitizeDimension(doc.height)
        val unit = escapeXml(options.widthUnit)

        val sb = StringBuilder(4096)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>").append(nl)
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\"")
        sb.append(" width=\"").append(SvgPathData.num(w, p)).append(unit).append('"')
        sb.append(" height=\"").append(SvgPathData.num(h, p)).append(unit).append('"')
        sb.append(" viewBox=\"0 0 ").append(SvgPathData.num(w, p)).append(' ')
            .append(SvgPathData.num(h, p)).append("\">").append(nl)

        if (options.includeMetadata) {
            sb.append(i1).append("<title>").append(escapeXml(options.title)).append("</title>").append(nl)
            sb.append(i1).append("<desc>")
                .append(escapeXml("${doc.shapeCount()} shapes, ${doc.nodeCount()} nodes"))
                .append("</desc>").append(nl)
        }

        val bg = doc.background
        if (bg != null && ((bg ushr 24) and 0xFF) != 0) {
            sb.append(i1).append("<rect x=\"0\" y=\"0\" width=\"").append(SvgPathData.num(w, p))
                .append("\" height=\"").append(SvgPathData.num(h, p))
                .append("\" fill=\"").append(colorHex(bg)).append('"')
            appendAlpha(sb, "fill-opacity", bg, p)
            sb.append("/>").append(nl)
        }

        for (layer in doc.layers) {
            if (options.groupByLayer) {
                sb.append(i1).append("<g id=\"").append(escapeXml(layer.id)).append('"')
                sb.append(" data-name=\"").append(escapeXml(layer.name)).append('"')
                val opacity = clamp01(layer.opacity)
                if (opacity < 1f) sb.append(" opacity=\"").append(SvgPathData.num(opacity, 3)).append('"')
                if (!layer.visible) sb.append(" style=\"display:none\"")
                if (options.includeMetadata && layer.locked) sb.append(" data-locked=\"true\"")
                sb.append('>').append(nl)
                for (shape in layer.shapes) appendShape(sb, shape, i2, nl, p, 1f)
                sb.append(i1).append("</g>").append(nl)
            } else {
                // Flattened output has nowhere to record visibility, so a hidden layer is simply
                // absent and its opacity is folded into every shape it owned.
                if (!layer.visible) continue
                val opacity = clamp01(layer.opacity)
                if (opacity <= 0f) continue
                for (shape in layer.shapes) appendShape(sb, shape, i1, nl, p, opacity)
            }
        }

        sb.append("</svg>").append(nl)
        return sb.toString()
    }

    /**
     * Escapes the five XML entities and drops the control characters XML 1.0 cannot represent.
     * Safe for both attribute values and element text. Returns [s] unchanged when nothing needs it.
     */
    fun escapeXml(s: String): String {
        var needs = false
        for (c in s) {
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'' || c.code < 0x20) {
                needs = true
                break
            }
        }
        if (!needs) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (c.code >= 0x20 || c == '\t' || c == '\n' || c == '\r') sb.append(c)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private fun appendShape(
        sb: StringBuilder,
        shape: VecShape,
        indent: String,
        nl: String,
        precision: Int,
        opacityMultiplier: Float,
    ) {
        val path = shape.path
        if (path.segments.isEmpty()) return
        val style = shape.style
        val opacity = clamp01(style.opacity) * opacityMultiplier

        val widths = path.strokeWidths
        val stroke = style.stroke
        if (widths != null && widths.isNotEmpty() && stroke != null && style.strokeWidth > 0f) {
            val outline = StrokeStyle.variableWidthOutline(path, widths, style.cap)
            if (outline.segments.isEmpty()) return
            sb.append(indent).append("<path d=\"").append(SvgPathData.toD(outline, precision)).append('"')
            sb.append(" fill=\"").append(colorHex(stroke)).append('"')
            sb.append(" fill-rule=\"nonzero\"")
            appendAlpha(sb, "fill-opacity", stroke, precision)
            if (opacity < 1f) sb.append(" opacity=\"").append(SvgPathData.num(opacity, 3)).append('"')
            if (path.id.isNotEmpty()) sb.append(" id=\"").append(escapeXml(path.id)).append('"')
            sb.append("/>").append(nl)
            return
        }

        sb.append(indent).append("<path d=\"").append(SvgPathData.toD(path, precision)).append('"')
        val fill = style.fill
        if (fill == null) {
            sb.append(" fill=\"none\"")
        } else {
            sb.append(" fill=\"").append(colorHex(fill)).append('"')
            sb.append(" fill-rule=\"")
                .append(if (style.fillRule == FillRule.EVENODD) "evenodd" else "nonzero").append('"')
            appendAlpha(sb, "fill-opacity", fill, precision)
        }
        if (stroke != null && style.strokeWidth > 0f && style.strokeWidth.isFinite()) {
            sb.append(" stroke=\"").append(colorHex(stroke)).append('"')
            sb.append(" stroke-width=\"").append(SvgPathData.num(style.strokeWidth, precision)).append('"')
            sb.append(" stroke-linecap=\"").append(capName(style.cap)).append('"')
            sb.append(" stroke-linejoin=\"").append(joinName(style.join)).append('"')
            if (style.join == LineJoin.MITER) {
                val limit = if (style.miterLimit.isFinite() && style.miterLimit >= 1f) style.miterLimit else 4f
                sb.append(" stroke-miterlimit=\"").append(SvgPathData.num(limit, precision)).append('"')
            }
            appendAlpha(sb, "stroke-opacity", stroke, precision)
        }
        if (opacity < 1f) sb.append(" opacity=\"").append(SvgPathData.num(opacity, 3)).append('"')
        if (path.id.isNotEmpty()) sb.append(" id=\"").append(escapeXml(path.id)).append('"')
        sb.append("/>").append(nl)
    }

    private fun appendAlpha(sb: StringBuilder, attribute: String, argb: Int, precision: Int) {
        val a = (argb ushr 24) and 0xFF
        if (a >= 255) return
        val p = if (precision < 3) 3 else precision
        sb.append(' ').append(attribute).append("=\"").append(SvgPathData.num(a / 255f, p)).append('"')
    }

    private fun colorHex(argb: Int): String {
        val sb = StringBuilder(7)
        sb.append('#')
        appendHexByte(sb, (argb ushr 16) and 0xFF)
        appendHexByte(sb, (argb ushr 8) and 0xFF)
        appendHexByte(sb, argb and 0xFF)
        return sb.toString()
    }

    private fun appendHexByte(sb: StringBuilder, v: Int) {
        val digits = "0123456789ABCDEF"
        sb.append(digits[(v ushr 4) and 0xF])
        sb.append(digits[v and 0xF])
    }

    private fun capName(cap: LineCap): String = when (cap) {
        LineCap.BUTT -> "butt"
        LineCap.ROUND -> "round"
        LineCap.SQUARE -> "square"
    }

    private fun joinName(join: LineJoin): String = when (join) {
        LineJoin.MITER -> "miter"
        LineJoin.ROUND -> "round"
        LineJoin.BEVEL -> "bevel"
    }

    private fun sanitizeDimension(v: Float): Float {
        if (!v.isFinite() || v <= 0f) return 1f
        return if (v > MAX_DIMENSION) MAX_DIMENSION else v
    }

    private fun clamp01(v: Float): Float = if (!v.isFinite() || v < 0f) 0f else if (v > 1f) 1f else v
}

// Nested to match the API contract's grouping under `object SvgWriter`; aliased at file scope so
// `com.offlinetracer.vector.SvgOptions` resolves too. :core-export refers to it unqualified, and a
// type that only resolves one way is a compile error in somebody else's module.
typealias SvgOptions = SvgWriter.SvgOptions
