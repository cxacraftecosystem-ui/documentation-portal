package com.offlinetracer.export

import com.offlinetracer.imaging.Resample
import com.offlinetracer.imaging.RgbaImage
import com.offlinetracer.vector.Raster
import com.offlinetracer.vector.VecDocument

object Exporter {

    fun export(doc: VecDocument, raster: RgbaImage?, o: ExportOptions): ByteArray =
        when (o.format) {
            ExportFormat.SVG -> SvgExport.export(doc, o)
            ExportFormat.PDF -> PdfWriter.export(doc, o)
            ExportFormat.EPS -> EpsWriter.export(doc, o)
            ExportFormat.DXF -> DxfWriter.export(doc, o)
            ExportFormat.PNG -> PngEncoder.encode(resolveRaster(doc, raster, o), o.dpi)
            ExportFormat.BMP -> BmpEncoder.encode(resolveRaster(doc, raster, o), o.dpi)
            ExportFormat.TIFF -> TiffEncoder.encode(resolveRaster(doc, raster, o), o.effectiveDpi)
            ExportFormat.JPEG -> platformOnly(ExportFormat.JPEG)
            ExportFormat.WEBP -> platformOnly(ExportFormat.WEBP)
            ExportFormat.PROJECT -> throw UnsupportedOperationException(
                "ExportFormat.PROJECT is written by :core-pipeline's ProjectCodec.encode, not by " +
                    "Exporter. A project file stores the TraceParams alongside the geometry so the " +
                    "trace can be reopened and re-tuned, and Exporter.export is only given a " +
                    "VecDocument — the parameters are already gone by the time it is called."
            )
        }

    fun supports(format: ExportFormat): Boolean =
        !format.isPlatformEncoded && format != ExportFormat.PROJECT

    private fun resolveRaster(doc: VecDocument, raster: RgbaImage?, o: ExportOptions): RgbaImage {
        val size = ExportGeom.outputSize(o, doc.width, doc.height)
        val w = size[0]
        val h = size[1]
        val background = o.background ?: doc.background ?: 0
        if (raster == null) return Raster.render(doc, w, h, background, 4)
        val sized = if (raster.width == w && raster.height == h) raster else Resample.resize(raster, w, h)
        val requested = o.background ?: return sized
        return compositeOver(sized, requested)
    }

    private fun compositeOver(src: RgbaImage, background: Int): RgbaImage {
        val ba = (background ushr 24) and 0xFF
        if (ba == 0) return src
        val br = (background ushr 16) and 0xFF
        val bg = (background ushr 8) and 0xFF
        val bb = background and 0xFF
        val n = src.size
        val out = IntArray(n)
        val px = src.pixels
        var i = 0
        while (i < n) {
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            if (a == 255) {
                out[i] = p
                i++
                continue
            }
            val inv = 255 - a
            val bw = ba * inv / 255
            val oa = a + bw
            if (oa == 0) {
                out[i] = 0
                i++
                continue
            }
            val r = (((p ushr 16) and 0xFF) * a + br * bw + oa / 2) / oa
            val g = (((p ushr 8) and 0xFF) * a + bg * bw + oa / 2) / oa
            val b = ((p and 0xFF) * a + bb * bw + oa / 2) / oa
            out[i] = (oa shl 24) or
                ((if (r > 255) 255 else r) shl 16) or
                ((if (g > 255) 255 else g) shl 8) or
                (if (b > 255) 255 else b)
            i++
        }
        return RgbaImage(src.width, src.height, out)
    }

    private fun platformOnly(format: ExportFormat): Nothing {
        val compressFormat = if (format == ExportFormat.JPEG) "JPEG" else "WEBP_LOSSY"
        throw UnsupportedOperationException(
            "${format.name} is encoded by the host platform, not by :core-export. Android must " +
                "route it through Bitmap.compress(Bitmap.CompressFormat.$compressFormat, " +
                "options.quality, out) and the web build through " +
                "canvas.toBlob(\"${format.mimeType}\", options.quality / 100). A hand-written " +
                "encoder would be measurably worse than either, and returning PNG bytes under a " +
                "\".${format.extension}\" name would be a silent corruption, so this call fails " +
                "loudly instead. Check ExportFormat.isPlatformEncoded before calling Exporter."
        )
    }
}
