package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TypeScaleGuardTest {

    private val allowList = setOf(
        "com/fieldrepository/app/ui/Theme.kt",
        "com/fieldrepository/app/ui/MapScreen.kt",
        "com/fieldrepository/app/ui/RecordMeasureField.kt",
        "com/fieldrepository/app/ui/trace/TraceCompare.kt",
    )

    private val literalSize =
        Regex("""\bfontSize\s*=\s*[^,\n]*?\b\d+(\.\d+)?\.sp\b|\bfontSize\s*:\s*TextUnit\s*=\s*\d""")

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
