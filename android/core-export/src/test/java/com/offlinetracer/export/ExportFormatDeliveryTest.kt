package com.offlinetracer.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The facts that decide **delivery** rather than encoding.
 *
 * Every writer in this module was already correct when the bug was filed: the SVG had a valid `xmlns`,
 * no NaN, forty-two well-formed `<path d>` elements and a proper close — and the file still could not
 * be opened, because the host filed it in the user's camera roll on the strength of its MIME type
 * starting with `image/`. So these tests do not look at bytes at all. They pin the two properties a
 * host reads instead of guessing, and the first one is the reason the defect existed.
 */
class ExportFormatDeliveryTest {

    /**
     * The invariant. No format that stores geometry may ever be routed to the picture collection,
     * whatever its MIME type looks like — that is what put unopenable SVGs and DXFs in the gallery.
     */
    @Test
    fun noVectorFormatIsEverFiledWithThePhotographs() {
        for (format in ExportFormat.entries) {
            if (!format.isVector) continue
            assertFalse(
                format.belongsInPhotoGallery,
                "${format.name} stores geometry and no gallery can render it",
            )
        }
    }

    /**
     * The exact disagreement the property exists to settle. If this test ever passes trivially —
     * because no `image/` MIME type is left on a vector format — the old shortcut becomes safe again
     * by accident, so the disagreement itself is asserted rather than assumed.
     */
    @Test
    fun theMimePrefixIsNotTheTestAndThisIsWhere() {
        val misleading = ExportFormat.entries.filter {
            it.mimeType.startsWith("image/") && !it.belongsInPhotoGallery
        }
        assertTrue(
            misleading.containsAll(listOf(ExportFormat.SVG, ExportFormat.DXF)),
            "SVG (image/svg+xml) and DXF (image/vnd.dxf) are the formats a prefix test gets wrong",
        )
        assertTrue(ExportFormat.SVG.mimeType.startsWith("image/"))
        assertTrue(ExportFormat.DXF.mimeType.startsWith("image/"))
    }

    /**
     * The gallery set, enumerated. It is exactly the bitmap containers Android's own decoders read:
     * `BitmapFactory` handles PNG, JPEG, WEBP and BMP, and has never handled TIFF.
     */
    @Test
    fun onlyTheContainersAndroidCanDecodeAreGalleryBound() {
        assertEquals(
            listOf(ExportFormat.PNG, ExportFormat.JPEG, ExportFormat.WEBP, ExportFormat.BMP),
            ExportFormat.entries.filter { it.belongsInPhotoGallery },
        )
    }

    /**
     * TIFF is the edge case, and it is decided against the gallery.
     *
     * It holds pixels, so [ExportFormat.isRaster] is true and the editor flattens a raster for it —
     * but no Android decoder reads TIFF, so a TIFF in `MediaStore.Images` produces exactly the SVG
     * failure: an indexed file with a thumbnail that never loads and a gallery that refuses to open
     * it. It is written for print and scanning work on a computer, so it goes where a file manager
     * and a USB cable can reach it.
     */
    @Test
    fun tiffIsABitmapAndStillNotAGalleryFormat() {
        assertTrue(ExportFormat.TIFF.isRaster, "TIFF holds pixels")
        assertFalse(ExportFormat.TIFF.isVector)
        assertFalse(
            ExportFormat.TIFF.belongsInPhotoGallery,
            "Android cannot decode TIFF, so a gallery cannot display one",
        )
    }

    @Test
    fun aProjectFileIsNotAPicture() {
        assertFalse(ExportFormat.PROJECT.belongsInPhotoGallery)
        assertEquals("application/json", ExportFormat.PROJECT.mimeType)
    }

    /**
     * Every format says what opening it will need, because the user picks the format before they find
     * out. A blank sentence here is a silent format.
     */
    @Test
    fun everyFormatStatesItsConsequenceAndNamesItself() {
        for (format in ExportFormat.entries) {
            assertTrue(format.choiceLabel.isNotBlank(), "${format.name} has no label to offer")
            val note = format.consequence
            assertTrue(note.isNotBlank(), "${format.name} says nothing about what opening it needs")
            assertTrue(note.trim().endsWith("."), "${format.name}: a consequence is a sentence — $note")
            // Long enough to say something, short enough to sit beside a chip on a phone.
            assertTrue(note.length in 20..200, "${format.name}: $note")
        }
    }

    /** SVG is the product's point, so it has to be named as the vector format rather than as "SVG". */
    @Test
    fun svgIsLabelledForWhatItIs() {
        assertTrue(
            ExportFormat.SVG.choiceLabel.contains("vector", ignoreCase = true),
            ExportFormat.SVG.choiceLabel,
        )
        val note = ExportFormat.SVG.consequence
        assertTrue(note.contains("vector", ignoreCase = true), note)
        // The sentence has to name the thing the user must have. "Needs another app" is not actionable.
        assertTrue(note.contains("browser", ignoreCase = true), note)
    }

    @Test
    fun extensionsAreBareLowercaseAndUnique() {
        val extensions = ExportFormat.entries.map { it.extension }
        assertEquals(extensions.size, extensions.distinct().size, "two formats share an extension")
        for (format in ExportFormat.entries) {
            assertFalse(format.extension.startsWith("."), "${format.name} carries a leading dot")
            assertEquals(format.extension.lowercase(), format.extension, format.name)
            assertTrue(format.mimeType.contains('/'), "${format.name} has no media type")
        }
    }

    /** The mirror on [ExportOptions] has to agree, or a host holding options routes differently. */
    @Test
    fun theOptionsMirrorAgreesWithTheFormat() {
        for (format in ExportFormat.entries) {
            val options = ExportOptions(format = format)
            assertEquals(format.belongsInPhotoGallery, options.belongsInPhotoGallery, format.name)
            assertEquals(format.isVector, options.isVector, format.name)
            assertEquals(format.extension, options.extension, format.name)
            assertEquals(format.mimeType, options.mimeType, format.name)
        }
    }

    /** Unchanged behaviour, asserted here because the routing property sits beside it now. */
    @Test
    fun platformEncodedIsExactlyJpegAndWebp() {
        assertEquals(
            listOf(ExportFormat.JPEG, ExportFormat.WEBP),
            ExportFormat.entries.filter { it.isPlatformEncoded },
        )
    }
}
