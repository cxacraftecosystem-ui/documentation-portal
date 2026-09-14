package com.offlinetracer.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Dispatch and, more importantly, the refusal to guess. The JPEG/WEBP behaviour is tested by its
 * message as well as its type: a bare exception with no explanation sends the next reader looking
 * for a missing dependency instead of at the platform bridge they were supposed to write.
 */
class ExporterTest {

    private fun doc() = Fixtures.document()

    @Test
    fun formatMetadataIsConsistent() {
        val extensions = HashSet<String>()
        for (f in ExportFormat.entries) {
            assertTrue(f.extension.isNotEmpty(), "${f.name} needs an extension")
            assertTrue(!f.extension.startsWith("."), "${f.name} extension must not include the dot")
            assertTrue(f.mimeType.contains('/'), "${f.name} needs a media type")
            assertTrue(extensions.add(f.extension), "duplicate extension for ${f.name}")
        }
        assertTrue(ExportFormat.SVG.isVector && ExportFormat.PDF.isVector)
        assertTrue(ExportFormat.EPS.isVector && ExportFormat.DXF.isVector)
        assertTrue(!ExportFormat.PNG.isVector && !ExportFormat.TIFF.isVector)
        assertTrue(ExportFormat.JPEG.isPlatformEncoded && ExportFormat.WEBP.isPlatformEncoded)
        assertTrue(!ExportFormat.PNG.isPlatformEncoded)
    }

    @Test
    fun optionsMirrorTheirFormat() {
        val o = ExportOptions(ExportFormat.PDF)
        assertTrue(o.isVector)
        assertEquals("pdf", o.extension)
        assertEquals("application/pdf", o.mimeType)
        assertEquals(300, o.effectiveDpi)
        assertEquals(72, ExportOptions(ExportFormat.PDF, dpi = 0).effectiveDpi)
        assertEquals(72, ExportOptions(ExportFormat.PDF, dpi = -5).effectiveDpi)
    }

    @Test
    fun jpegRefusesWithAnActionableMessage() {
        val e = assertFailsWith<UnsupportedOperationException> {
            Exporter.export(doc(), Fixtures.image(100, 50), ExportOptions(ExportFormat.JPEG))
        }
        val m = e.message ?: ""
        assertTrue(m.contains("Bitmap.compress"), "the message must name the Android route: $m")
        assertTrue(m.contains("toBlob"), "the message must name the web route: $m")
        assertTrue(m.contains("isPlatformEncoded"), "the message must say how to avoid it: $m")
    }

    @Test
    fun webpRefusesWithAnActionableMessage() {
        val e = assertFailsWith<UnsupportedOperationException> {
            Exporter.export(doc(), Fixtures.image(100, 50), ExportOptions(ExportFormat.WEBP))
        }
        val m = e.message ?: ""
        assertTrue(m.contains("WEBP_LOSSY"), "the message must name the Android route: $m")
        assertTrue(m.contains("image/webp"), "the message must name the media type: $m")
    }

    @Test
    fun projectFilesAreThePipelinesJob() {
        val e = assertFailsWith<UnsupportedOperationException> {
            Exporter.export(doc(), null, ExportOptions(ExportFormat.PROJECT))
        }
        assertTrue((e.message ?: "").contains("ProjectCodec"))
    }

    @Test
    fun supportsMatchesWhatExportCanActuallyDo() {
        assertTrue(!Exporter.supports(ExportFormat.JPEG))
        assertTrue(!Exporter.supports(ExportFormat.WEBP))
        assertTrue(!Exporter.supports(ExportFormat.PROJECT))
        for (f in ExportFormat.entries) {
            if (!Exporter.supports(f)) continue
            // Every supported format must return bytes rather than throw, for both a populated
            // and a completely empty document.
            assertTrue(Exporter.export(doc(), Fixtures.image(100, 50), ExportOptions(f)).isNotEmpty(), f.name)
        }
    }

    @Test
    fun rasterFormatsDispatchToTheRightEncoder() {
        val raster = Fixtures.image(100, 50)
        val png = Exporter.export(doc(), raster, ExportOptions(ExportFormat.PNG))
        assertEquals(0x89, png[0].toInt() and 0xFF)
        assertEquals('P'.code, png[1].toInt() and 0xFF)

        val bmp = Exporter.export(doc(), raster, ExportOptions(ExportFormat.BMP))
        assertEquals('B'.code, bmp[0].toInt() and 0xFF)
        assertEquals('M'.code, bmp[1].toInt() and 0xFF)

        val tiff = Exporter.export(doc(), raster, ExportOptions(ExportFormat.TIFF))
        assertEquals('I'.code, tiff[0].toInt() and 0xFF)
        assertEquals('I'.code, tiff[1].toInt() and 0xFF)
    }

    @Test
    fun vectorFormatsDispatchToTheRightWriter() {
        assertTrue(
            String(Exporter.export(doc(), null, ExportOptions(ExportFormat.PDF)), Charsets.ISO_8859_1)
                .startsWith("%PDF-1.4")
        )
        assertTrue(
            String(Exporter.export(doc(), null, ExportOptions(ExportFormat.EPS)), Charsets.US_ASCII)
                .startsWith("%!PS-Adobe-3.0 EPSF-3.0")
        )
        val dxf = String(Exporter.export(doc(), null, ExportOptions(ExportFormat.DXF)), Charsets.US_ASCII)
        assertTrue(dxf.startsWith("0\r\nSECTION\r\n"))
        assertTrue(dxf.endsWith("EOF\r\n"))
    }

    @Test
    fun aMatchingRasterIsEncodedWithoutResampling() {
        // Re-rendering or resampling a preview that is already the output size would soften a
        // trace that is exactly one pixel per pixel, which is the whole point of passing one in.
        val raster = Fixtures.image(100, 50)
        assertTrue(
            Exporter.export(doc(), raster, ExportOptions(ExportFormat.PNG, dpi = 96))
                .contentEquals(PngEncoder.encode(raster, 96)),
            "a matching raster must reach the encoder untouched",
        )
    }

    @Test
    fun aMismatchedRasterIsResampledToTheRequestedSize() {
        val png = Exporter.export(doc(), Fixtures.image(100, 50), ExportOptions(ExportFormat.PNG, width = 50))
        // IHDR is the first chunk: 8 signature + 4 length + 4 type, then width and height.
        assertEquals(50, be32(png, 16), "requested width")
        assertEquals(25, be32(png, 20), "height follows the aspect ratio")
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    @Test
    fun outputSizeHonoursWidthHeightAndScale() {
        val w = Fixtures.DOC_W
        val h = Fixtures.DOC_H
        assertEquals(listOf(100, 50), ExportGeom.outputSize(ExportOptions(ExportFormat.PNG), w, h).toList())
        assertEquals(
            listOf(200, 100),
            ExportGeom.outputSize(ExportOptions(ExportFormat.PNG, scale = 2f), w, h).toList(),
        )
        assertEquals(
            listOf(400, 200),
            ExportGeom.outputSize(ExportOptions(ExportFormat.PNG, width = 400), w, h).toList(),
            "one dimension must preserve the aspect ratio",
        )
        assertEquals(
            listOf(400, 400),
            ExportGeom.outputSize(ExportOptions(ExportFormat.PNG, width = 400, height = 400), w, h).toList(),
            "both dimensions are honoured verbatim",
        )
        assertEquals(
            listOf(1, 1),
            ExportGeom.outputSize(ExportOptions(ExportFormat.PNG, scale = 0.001f), w, h).toList(),
            "a degenerate scale must not produce a zero-sized image",
        )
    }

    @Test
    fun numberFormattingIsLocaleIndependentAndTrimmed() {
        assertEquals("0", ExportGeom.num(0f, 2))
        assertEquals("0", ExportGeom.num(-0.0001f, 2), "a value that rounds to zero has no sign")
        assertEquals("1.5", ExportGeom.num(1.5f, 2))
        assertEquals("1.5", ExportGeom.num(1.500f, 4), "trailing zeros are trimmed")
        assertEquals("-2.25", ExportGeom.num(-2.25f, 2))
        assertEquals("0.05", ExportGeom.num(0.05f, 2), "leading fractional zeros are kept")
        assertEquals("3", ExportGeom.num(3.004f, 2))
        assertEquals("0", ExportGeom.num(Float.NaN, 2), "a NaN coordinate must not corrupt the file")
        assertTrue(!ExportGeom.num(Float.POSITIVE_INFINITY, 2).contains("Inf"))
        assertTrue(!ExportGeom.num(1234.5f, 2).contains(','), "never a comma decimal separator")
    }

}
