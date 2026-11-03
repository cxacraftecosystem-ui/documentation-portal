package com.offlinetracer.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportFormatDeliveryTest {

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

    @Test
    fun onlyTheContainersAndroidCanDecodeAreGalleryBound() {
        assertEquals(
            listOf(ExportFormat.PNG, ExportFormat.JPEG, ExportFormat.WEBP, ExportFormat.BMP),
            ExportFormat.entries.filter { it.belongsInPhotoGallery },
        )
    }

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

    @Test
    fun everyFormatStatesItsConsequenceAndNamesItself() {
        for (format in ExportFormat.entries) {
            assertTrue(format.choiceLabel.isNotBlank(), "${format.name} has no label to offer")
            val note = format.consequence
            assertTrue(note.isNotBlank(), "${format.name} says nothing about what opening it needs")
            assertTrue(note.trim().endsWith("."), "${format.name}: a consequence is a sentence — $note")
            assertTrue(note.length in 20..200, "${format.name}: $note")
        }
    }

    @Test
    fun svgIsLabelledForWhatItIs() {
        assertTrue(
            ExportFormat.SVG.choiceLabel.contains("vector", ignoreCase = true),
            ExportFormat.SVG.choiceLabel,
        )
        val note = ExportFormat.SVG.consequence
        assertTrue(note.contains("vector", ignoreCase = true), note)
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

    @Test
    fun platformEncodedIsExactlyJpegAndWebp() {
        assertEquals(
            listOf(ExportFormat.JPEG, ExportFormat.WEBP),
            ExportFormat.entries.filter { it.isPlatformEncoded },
        )
    }
}
