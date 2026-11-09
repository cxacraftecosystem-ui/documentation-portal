package com.fieldrepository.app.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.fieldrepository.app.explorer.ui.ExplorerMarkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StraightTickSourceTest {

    private val glyph = Regex("[\u2713\u2714\u2611\u2705]|\\\\u(2713|2714|2611|2705)|&#(10003|10004|9745|9989);")
    private val curvedIcon = Regex("""\bIcons\.[A-Za-z]+\.(Done|DoneAll|DoneOutline|TaskAlt|AddTask|CheckCircleOutline)\b|material\.icons\.[a-z]+\.(Done|DoneAll|DoneOutline|TaskAlt|AddTask|CheckCircleOutline)\b""")
    private val explorerCheck = Regex("""\bIcons\.[A-Za-z]+\.Check\b""")

    @Test
    fun noScreenDrawsAFontTickOrACurvedTickIcon() {
        val main = sourceRoot()
        val res = File(main.parentFile, "res")
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" } +
            res.walkTopDown().filter { it.isFile && it.extension == "xml" }
        val offenders = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (glyph.containsMatchIn(line) || curvedIcon.containsMatchIn(line)) "${file.relativeTo(main.parentFile)}:${index + 1}" else null
            }
        }.toList()
        assertEquals("Use ExplorerMarkIcon(ExplorerMarkKind.SAVED) or ExplorerTickText, the straight tick:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test
    fun theExplorerDrawsItsTickOnlyThroughTheMark() {
        val explorer = File(sourceRoot(), "${pkgPath()}/explorer")
        val offenders = explorer.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "ExplorerKit.kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line -> if (explorerCheck.containsMatchIn(line)) "${file.name}:${index + 1}" else null }
        }.toList()
        assertEquals("Explorer ticks go through ExplorerMarkIcon:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test
    fun theApprovedTickIsTheStraightCheck() {
        assertEquals(Icons.Filled.Check, ExplorerMarkKind.SAVED.icon)
        val kit = File(sourceRoot(), "${pkgPath()}/explorer/ui/ExplorerKit.kt").readText()
        assertTrue(kit.contains("fun ExplorerTickText("))
        assertTrue(kit.contains("ExplorerMarkIcon(ExplorerMarkKind.SAVED"))
    }

    private fun pkgPath(): String = ExplorerMarkKind::class.java.packageName.removeSuffix(".explorer.ui").replace('.', '/')

    private fun sourceRoot(): File {
        val kit = "${pkgPath()}/explorer/ui/ExplorerKit.kt"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in listOf("src/main/java", "app/src/main/java", "android/app/src/main/java")) {
                val candidate = File(dir, path)
                if (File(candidate, kit).isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("source root not found from ${File(".").absolutePath}")
    }
}
