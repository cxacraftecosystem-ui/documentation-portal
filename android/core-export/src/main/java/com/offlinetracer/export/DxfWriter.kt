package com.offlinetracer.export

import com.offlinetracer.vector.VecDocument
import com.offlinetracer.vector.VecLayer
import com.offlinetracer.vector.VecPoint

object DxfWriter {

    private const val EOL = "\r\n"

    private val ACI_RGB = intArrayOf(
        0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFFFFFF,
    )

    private val ACI_CYCLE = intArrayOf(7, 1, 3, 5, 2, 4, 6)

    fun export(doc: VecDocument, o: ExportOptions): ByteArray {
        val docW = if (doc.width.isFinite() && doc.width > 0f) doc.width else 1f
        val docH = if (doc.height.isFinite() && doc.height > 0f) doc.height else 1f
        val s = ExportGeom.scaleXY(o, docW, docH)
        val mmPerUnit = ExportGeom.MM_PER_INCH / o.effectiveDpi.toFloat()
        val mx = s[0] * mmPerUnit
        val my = s[1] * mmPerUnit
        val heightMm = docH * my

        val flattenTolerance = clamp(0.05f / maxOf(mx, my, 1e-6f), 0.02f, 2f)

        val layerNames = ArrayList<String>(doc.layers.size)
        val layerColours = IntArray(doc.layers.size)
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
        group(sb, 9, "\$INSUNITS")
        group(sb, 70, "4")
        group(sb, 0, "ENDSEC")

        group(sb, 0, "SECTION")
        group(sb, 2, "TABLES")

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
            group(sb, 70, if (layer.locked) "4" else "0")
            group(sb, 62, (if (layer.visible) layerColours[i] else -layerColours[i]).toString())
            group(sb, 6, "CONTINUOUS")
        }
        group(sb, 0, "ENDTAB")
        group(sb, 0, "ENDSEC")

        group(sb, 0, "SECTION")
        group(sb, 2, "ENTITIES")
        sb.append(entities)
        group(sb, 0, "ENDSEC")

        group(sb, 0, "EOF")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun group(sb: StringBuilder, code: Int, value: String) {
        sb.append(code).append(EOL).append(value).append(EOL)
    }

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
            if (n > 10000) {
                used.add(candidate)
                break
            }
        }
        return candidate
    }

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

    private fun nearestAci(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
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

    private fun dxfNum(v: Float): String {
        val s = ExportGeom.num(v, 4)
        return if (s.indexOf('.') >= 0) s else "$s.0"
    }

    private fun samePoint(a: VecPoint, b: VecPoint): Boolean =
        kotlin.math.abs(a.x - b.x) < 1e-4f && kotlin.math.abs(a.y - b.y) < 1e-4f

    private fun clamp(v: Float, lo: Float, hi: Float): Float =
        if (!v.isFinite()) lo else if (v < lo) lo else if (v > hi) hi else v
}
