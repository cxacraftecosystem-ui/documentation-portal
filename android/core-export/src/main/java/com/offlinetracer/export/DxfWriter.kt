package com.offlinetracer.export

import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecPoint

/**
 * DXF R12 ASCII, in millimetres, one DXF layer per [VecLayer].
 *
 * The layer mapping is the entire reason this format exists in the app. CNC routers, laser
 * cutters and vinyl plotters assign a tool, a power or a pass count **per layer**, so a trace
 * whose cut lines, score lines and engrave regions arrive as one flat pile of geometry has to be
 * re-separated by hand before it can be run. Preserving the layer structure is what turns an
 * export into a job.
 *
 * Format details that bite:
 *  - Every value is **two lines**: a group code, then the value. Nothing is on the same line.
 *  - **DXF y grows upward**, the opposite of image coordinates, so y is flipped exactly once,
 *    here, using the page height.
 *  - **Layer names are uppercased and sanitised.** R12 permits only letters, digits and `$-_`,
 *    and a name with a space or a lowercase letter is either rejected or silently renamed by the
 *    receiving application — which then no longer matches the tool table the operator set up.
 *  - Output is in millimetres (`px * 25.4 / dpi`). A DXF whose units are pixels cuts a 3000px
 *    trace as a three-metre part.
 *
 * `LWPOLYLINE` is used for the flattened geometry even though it is strictly an R14 entity: it is
 * a fraction of the size of R12's `POLYLINE`/`VERTEX`/`SEQEND` triple and every consumer that
 * matters — LibreCAD, Inkscape, Fusion, LightBurn, LaserGRBL — reads it regardless of the
 * `$ACADVER` in the header. Curves are flattened rather than emitted as splines because a spline
 * is re-flattened by the CAM tool anyway, at a chord tolerance we would not control.
 */
object DxfWriter {

    private const val EOL = "\r\n"

    /** ACI 1..7 as RGB, used to pick a sensible per-layer colour for the operator's tool table. */
    private val ACI_RGB = intArrayOf(
        0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFFFFFF,
    )

    /** Fallback colour rotation for layers whose geometry carries no colour of its own. */
    private val ACI_CYCLE = intArrayOf(7, 1, 3, 5, 2, 4, 6)

    /**
     * Renders [doc] to a DXF R12 ASCII file in millimetres.
     *
     * @param o `dpi` sets the pixels-per-inch used for the millimetre conversion;
     *   `width`/`height`/`scale` resize the part. `background` and `precision` are ignored: DXF
     *   has no page colour, and coordinates are always written to four decimal places (0.1 µm),
     *   which is finer than any machine this targets can position.
     * @return the complete DXF file bytes, ASCII with CRLF line endings. A document with no
     *   shapes yields a valid file with an empty ENTITIES section.
     */
    fun export(doc: VecDocument, o: ExportOptions): ByteArray {
        val docW = if (doc.width.isFinite() && doc.width > 0f) doc.width else 1f
        val docH = if (doc.height.isFinite() && doc.height > 0f) doc.height else 1f
        val s = ExportGeom.scaleXY(o, docW, docH)
        val mmPerUnit = ExportGeom.MM_PER_INCH / o.effectiveDpi.toFloat()
        val mx = s[0] * mmPerUnit
        val my = s[1] * mmPerUnit
        val heightMm = docH * my

        // Flatten to a chord error of 0.05 mm in the output, which is well under the kerf of any
        // laser and the runout of any router, then clamped so a pathological scale cannot produce
        // either a million-segment polyline or a visible polygon.
        val flattenTolerance = clamp(0.05f / maxOf(mx, my, 1e-6f), 0.02f, 2f)

        val layerNames = ArrayList<String>(doc.layers.size)
        val layerColours = IntArray(doc.layers.size)
        // "0" is DXF's mandatory default layer; seeding it here means a document layer literally
        // named "0" is renamed rather than colliding with it.
        val used = HashSet<String>()
        used.add("0")
        for (i in doc.layers.indices) {
            val layer = doc.layers[i]
            layerNames.add(sanitizeLayerName(layer.name.ifEmpty { layer.id }, used))
            val colour = representativeColour(layer)
            layerColours[i] = if (colour != null) nearestAci(colour) else ACI_CYCLE[i % ACI_CYCLE.size]
        }

        val entities = StringBuilder(4096)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var any = false

        for (i in doc.layers.indices) {
            val layer = doc.layers[i]
            val name = layerNames[i]
            for (shape in layer.shapes) {
                val path = shape.path
                if (path.isEmpty()) continue
                val pts = path.flatten(flattenTolerance)
                var count = pts.size
                if (count < 2) continue
                // A closed path that repeats its start as a final vertex would draw a zero-length
                // segment on top of the closing one; CAM software reports that as a duplicate
                // geometry warning on every single contour.
                if (path.closed && count > 2 && samePoint(pts[0], pts[count - 1])) count--
                if (count < 2) continue

                group(entities, 0, "LWPOLYLINE")
                group(entities, 8, name)
                group(entities, 90, count.toString())
                group(entities, 70, if (path.closed) "1" else "0")
                var k = 0
                while (k < count) {
                    val px = pts[k].x * mx
                    val py = heightMm - pts[k].y * my
                    group(entities, 10, dxfNum(px))
                    group(entities, 20, dxfNum(py))
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                    any = true
                    k++
                }
            }
        }

        if (!any) {
            minX = 0f
            minY = 0f
            maxX = docW * mx
            maxY = heightMm
        }

        val sb = StringBuilder(entities.length + 2048)

        // ---- HEADER
        group(sb, 0, "SECTION")
        group(sb, 2, "HEADER")
        group(sb, 9, "\$ACADVER")
        group(sb, 1, "AC1009")
        group(sb, 9, "\$INSBASE")
        group(sb, 10, "0.0")
        group(sb, 20, "0.0")
        group(sb, 30, "0.0")
        group(sb, 9, "\$EXTMIN")
        group(sb, 10, dxfNum(minX))
        group(sb, 20, dxfNum(minY))
        group(sb, 30, "0.0")
        group(sb, 9, "\$EXTMAX")
        group(sb, 10, dxfNum(maxX))
        group(sb, 20, dxfNum(maxY))
        group(sb, 30, "0.0")
        group(sb, 9, "\$LIMMIN")
        group(sb, 10, dxfNum(minX))
        group(sb, 20, dxfNum(minY))
        group(sb, 9, "\$LIMMAX")
        group(sb, 10, dxfNum(maxX))
        group(sb, 20, dxfNum(maxY))
        // $INSUNITS post-dates R12, but every reader either honours it or skips the variable, and
        // without it a DXF in millimetres is imported as inches by roughly half of them.
        group(sb, 9, "\$INSUNITS")
        group(sb, 70, "4")
        group(sb, 0, "ENDSEC")

        // ---- TABLES
        group(sb, 0, "SECTION")
        group(sb, 2, "TABLES")

        // The LTYPE table has to exist because every LAYER below names CONTINUOUS; a layer
        // referencing an undefined linetype is a load error in strict readers.
        group(sb, 0, "TABLE")
        group(sb, 2, "LTYPE")
        group(sb, 70, "1")
        group(sb, 0, "LTYPE")
        group(sb, 2, "CONTINUOUS")
        group(sb, 70, "0")
        group(sb, 3, "Solid line")
        group(sb, 72, "65")
        group(sb, 73, "0")
        group(sb, 40, "0.0")
        group(sb, 0, "ENDTAB")

        group(sb, 0, "TABLE")
        group(sb, 2, "LAYER")
        group(sb, 70, (doc.layers.size + 1).toString())
        group(sb, 0, "LAYER")
        group(sb, 2, "0")
        group(sb, 70, "0")
        group(sb, 62, "7")
        group(sb, 6, "CONTINUOUS")
        for (i in doc.layers.indices) {
            val layer = doc.layers[i]
            group(sb, 0, "LAYER")
            group(sb, 2, layerNames[i])
            // Group 70 bit 4 locks the layer; a negative colour is how R12 says "layer off",
            // which is the only way to round-trip a hidden layer without deleting its geometry.
            group(sb, 70, if (layer.locked) "4" else "0")
            group(sb, 62, (if (layer.visible) layerColours[i] else -layerColours[i]).toString())
            group(sb, 6, "CONTINUOUS")
        }
        group(sb, 0, "ENDTAB")
        group(sb, 0, "ENDSEC")

        // ---- ENTITIES
        group(sb, 0, "SECTION")
        group(sb, 2, "ENTITIES")
        sb.append(entities)
        group(sb, 0, "ENDSEC")

        group(sb, 0, "EOF")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** One DXF item: the group code on its own line, then the value on its own line. */
    private fun group(sb: StringBuilder, code: Int, value: String) {
        sb.append(code).append(EOL).append(value).append(EOL)
    }

    /**
     * Uppercases and strips [raw] down to the characters R12 allows, truncates to 31 characters
     * and disambiguates against [used], which it also updates. Never returns an empty name.
     */
    private fun sanitizeLayerName(raw: String, used: MutableSet<String>): String {
        val sb = StringBuilder(32)
        for (ch in raw) {
            val c = ch.uppercaseChar()
            if ((c in 'A'..'Z') || (c in '0'..'9') || c == '$' || c == '-' || c == '_') {
                sb.append(c)
            } else {
                sb.append('_')
            }
        }
        var base = sb.toString()
        if (base.length > 31) base = base.substring(0, 31)
        if (base.isEmpty()) base = "LAYER"
        var candidate = base
        var n = 1
        while (!used.add(candidate)) {
            val suffix = "_$n"
            val head = if (base.length + suffix.length > 31) {
                base.substring(0, 31 - suffix.length)
            } else {
                base
            }
            candidate = head + suffix
            n++
            // Bounded so a pathological document cannot spin here; collisions past this point
            // simply share a name, which is far better than never returning.
            if (n > 10000) {
                used.add(candidate)
                break
            }
        }
        return candidate
    }

    /** First stroke colour in the layer, else its first fill colour, else null. */
    private fun representativeColour(layer: VecLayer): Int? {
        for (shape in layer.shapes) {
            val stroke = shape.style.stroke
            if (stroke != null && ((stroke ushr 24) and 0xFF) != 0) return stroke
        }
        for (shape in layer.shapes) {
            val fill = shape.style.fill
            if (fill != null && ((fill ushr 24) and 0xFF) != 0) return fill
        }
        return null
    }

    /** Nearest AutoCAD Colour Index in 1..7 for an ARGB colour. */
    private fun nearestAci(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        // Black ink is the overwhelmingly common case. ACI 7 is drawn as the sheet's contrast
        // colour, so it reads as black on a white background and white on a dark one instead of
        // disappearing into whichever the operator happens to use.
        if (hi - lo < 40) return 7
        var best = 7
        var bestDist = Int.MAX_VALUE
        var i = 0
        while (i < ACI_RGB.size) {
            val cr = (ACI_RGB[i] ushr 16) and 0xFF
            val cg = (ACI_RGB[i] ushr 8) and 0xFF
            val cb = ACI_RGB[i] and 0xFF
            val dr = r - cr
            val dg = g - cg
            val db = b - cb
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                best = i + 1
            }
            i++
        }
        return best
    }

    /** DXF reals are safer with an explicit decimal point; some readers reject a bare integer. */
    private fun dxfNum(v: Float): String {
        val s = ExportGeom.num(v, 4)
        return if (s.indexOf('.') >= 0) s else "$s.0"
    }

    private fun samePoint(a: VecPoint, b: VecPoint): Boolean =
        kotlin.math.abs(a.x - b.x) < 1e-4f && kotlin.math.abs(a.y - b.y) < 1e-4f

    private fun clamp(v: Float, lo: Float, hi: Float): Float =
        if (!v.isFinite()) lo else if (v < lo) lo else if (v > hi) hi else v
}
