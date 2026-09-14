package com.offlinetracer.export

import com.offlinetracer.vector.SvgWriter
import com.offlinetracer.vector.VecDocument

/**
 * SVG export: the [ExportOptions] adaptor around `:core-vector`'s [SvgWriter].
 *
 * The serialiser itself lives in `:core-vector` because the editor previews and the clipboard
 * both need it without pulling in the export module; this object exists only to turn an
 * [ExportOptions] into the writer's own option type, apply the requested output size as a real
 * geometric scale, and hand back bytes.
 *
 * Scaling is applied to the geometry rather than emitted as a `viewBox` mismatch or a wrapping
 * `transform`. A downstream consumer — a cutter, a plotter driver, another editor — is entitled
 * to read the coordinates directly, and a document whose numbers only mean the right thing after
 * an outer transform is applied is a document that cuts at the wrong size.
 */
object SvgExport {

    /**
     * Serialises [doc] to UTF-8 SVG bytes.
     *
     * @param o `width`/`height`/`scale` scale the geometry; `background` overrides the document
     *   background when non-null; `precision` is decimal places in path data; `flattenLayers`
     *   suppresses the per-layer `<g>` grouping.
     * @return UTF-8 encoded SVG. Never empty: a document with no shapes still produces a valid
     *   `<svg>` element of the requested size.
     */
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
