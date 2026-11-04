package com.offlinetracer.export

import com.offlinetracer.vector.SvgWriter
import com.offlinetracer.vector.VecDocument

object SvgExport {

    fun export(doc: VecDocument, o: ExportOptions): ByteArray {
        val s = ExportGeom.scaleXY(o, doc.width, doc.height)
        val scaled = ExportGeom.scaleDocument(doc, s[0], s[1])
        val target = if (o.background != null && o.background != scaled.background) {
            VecDocument(scaled.width, scaled.height, scaled.layers, o.background)
        } else {
            scaled
        }
        val svg = SvgWriter.write(
            target,
            SvgWriter.SvgOptions(
                precision = if (o.precision < 0) 0 else if (o.precision > 8) 8 else o.precision,
                includeMetadata = o.includeMetadata,
                groupByLayer = !o.flattenLayers,
                prettyPrint = true,
            ),
        )
        return svg.toByteArray(Charsets.UTF_8)
    }
}
