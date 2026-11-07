package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StaticCheckboxSourceTest {

    @Test
    fun noScreenUsesTheAnimatedMaterialCheckbox() {
        val root = sourceRoot()
        val direct = Regex("""androidx\.compose\.material3\.Checkbox\b|(?<![A-Za-z0-9_.`])Checkbox\(""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (direct.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}" else null
                }
            }
            .toList()
        assertEquals("Use FieldCheckbox (ui/FieldCheckbox.kt), the static check:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test
    fun theStaticCheckNeverAnimates() {
        val check = File(sourceRoot(), PKG_PATH + "/ui/FieldCheckbox.kt").readText()
        for (word in listOf("animate", "Animatable", "Transition", "tween", "spring(")) {
            assertFalse("FieldCheckbox must not use $word", check.contains(word))
        }
    }

    @Test
    fun theTickIsStraightSegmentsInsideTheBox() {
        val points = FieldCheckboxDefaults.tickPoints
        assertEquals(3, points.size)
        points.forEach { assertTrue(it.x in 0f..1f && it.y in 0f..1f) }
        assertTrue(points[1].y > points[0].y && points[1].y > points[2].y)
    }

    private fun sourceRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in listOf("src/main/java", "app/src/main/java", "android/app/src/main/java")) {
                val candidate = File(dir, path)
                if (File(candidate, "$PKG_PATH/ui/FieldCheckbox.kt").isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("source root not found")
    }

    private companion object {
        const val PKG_PATH = "com/fieldrepository/app"
    }
}
