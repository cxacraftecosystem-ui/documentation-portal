package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ONE TYPE SCALE (DC-A20). Text takes its size from a `MaterialTheme.typography` role — the ladder
 * is written down in ui/Theme.kt, slot for slot the designer portal's — and never from a literal.
 * Before this guard the app carried 712 literal `fontSize = N.sp` values in fifteen sizes, with help
 * and body text at 10–11sp; a literal is how that comes back, one "just this once" at a time.
 *
 * The allow-list is files, and it is short on purpose. Past Theme.kt, each entry is a dense label
 * drawn ON TOP of the thing it labels, where a scale step would cover it:
 *  - ui/Theme.kt defines the scale, so its sizes ARE the literals.
 *  - ui/MapScreen.kt — the pin label and the scale-bar "km" it paints onto the map canvas.
 *  - ui/CoverageMapCanvas.kt — the count it paints inside a folded marker on the coverage map.
 *  - ui/RecordMeasureField.kt — the letter inside a 20dp measuring mark on the photo.
 *  - ui/trace/TraceCompare.kt — the picture's name over the corner of the compare slider.
 */
class TypeScaleGuardTest {

    private val allowList = setOf(
        "com/fieldrepository/app/ui/Theme.kt",
        "com/fieldrepository/app/ui/MapScreen.kt",
        "com/fieldrepository/app/ui/CoverageMapCanvas.kt",
        "com/fieldrepository/app/ui/RecordMeasureField.kt",
        "com/fieldrepository/app/ui/trace/TraceCompare.kt",
    )

    /** `fontSize = 12.sp`, `fontSize = if (x) 12.sp else 11.sp`, and a `fontSize: TextUnit = 14.sp` default. */
    private val literalSize =
        Regex("""\bfontSize\s*=\s*[^,\n]*?\b\d+(\.\d+)?\.sp\b|\bfontSize\s*:\s*TextUnit\s*=\s*\d""")

    /** The roles that would be a fourth heading size, or a second display size. */
    private val offScaleRole = Regex("""\btypography\.(titleSmall|headlineLarge|headlineMedium|displayLarge|displayMedium)\b""")

    @Test
    fun noLiteralFontSizeOutsideTheAllowList() {
        val offenders = offenders(literalSize) { it !in allowList }
        assertEquals(
            "Set text with a MaterialTheme.typography role (see Theme.kt's type scale), not a literal " +
                "fontSize:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun threeHeadingStepsAndOneDisplayStep() {
        val offScale = offenders(offScaleRole) { !it.endsWith("ui/Theme.kt") }
        assertEquals(
            "Headings are headlineSmall / titleLarge / titleMedium only:\n" + offScale.joinToString("\n"),
            emptyList<String>(),
            offScale
        )
        val display = offenders(Regex("""\btypography\.displaySmall\b""")) { true }
        assertTrue("displaySmall is the sign-in wordmark only: $display", display.size <= 1)
    }

    @Test
    fun theAllowListStillEarnsItsPlace() {
        // A file that no longer needs its exemption leaves the list, so the list cannot grow stale
        // and quietly cover a new literal.
        val root = sourceRoot()
        for (path in allowList) {
            val file = File(root, path)
            assertTrue("$path is missing", file.isFile)
            assertTrue("$path has no literal sizes left; drop it from the allow-list", codeLines(file).any { literalSize.containsMatchIn(it.second) })
        }
    }

    private fun offenders(pattern: Regex, include: (String) -> Boolean): List<String> {
        val root = sourceRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { include(it.relativeTo(root).invariantSeparatorsPath) }
            .flatMap { file ->
                codeLines(file).filter { pattern.containsMatchIn(it.second) }
                    .map { "${file.relativeTo(root).invariantSeparatorsPath}:${it.first}" }
            }
            .toList()
    }

    /** (line number, text) for every line that is not a comment line. */
    private fun codeLines(file: File): List<Pair<Int, String>> =
        file.readLines().mapIndexedNotNull { index, line ->
            val code = line.trimStart()
            if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) null
            else index + 1 to line.substringBefore(" // ")
        }

    private fun sourceRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in listOf("src/main/java", "app/src/main/java", "android/app/src/main/java")) {
                val candidate = File(dir, path)
                if (File(candidate, "com/fieldrepository/app/ui/Theme.kt").isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("could not find the app's source root from ${File(".").absolutePath}")
    }
}
