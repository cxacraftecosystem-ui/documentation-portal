package com.fieldrepository.app.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `res/raw/district_shapes.bin` loads, holds exactly the districts the boundary build joined (the web
 * manifest's `coverage.joined`), and nearly every label point falls inside its own polygon.
 */
class DistrictShapesTest {
    private fun find(vararg relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.exists()) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${File("").absolutePath}")
    }

    private fun shapes(): List<DistrictShape> =
        find("src/main/res/raw/district_shapes.bin", "app/src/main/res/raw/district_shapes.bin")
            .inputStream()
            .use { parseDistrictShapes(it) }

    @Test
    fun theBlobHoldsEveryJoinedDistrict() {
        val shapes = shapes()
        val manifest = find("frontend/public/boundaries/manifest.json").readText()
        val joined = Regex("\"joined\"\\s*:\\s*(\\d+)").find(manifest)!!.groupValues[1].toInt()
        assertEquals(752, joined)
        assertEquals(joined, shapes.size)
        assertEquals(shapes.size, shapes.map { it.lgd }.toSet().size)
        assertTrue(shapes.all { it.rings.isNotEmpty() })
        val outside = shapes.filterNot { it.contains(it.centroid) }.map { it.lgd }
        assertTrue("label points outside their polygon: $outside", outside.size <= shapes.size / 50)
    }

    @Test
    fun aStateBoxCoversItsDistricts() {
        val shapes = shapes()
        val state = shapes.first().stateLgd
        val box = stateBounds(shapes, state)!!
        for (shape in shapes.filter { it.stateLgd == state }) {
            assertTrue(box.contains(Offset(shape.bounds.left, shape.bounds.top)))
        }
    }
}
