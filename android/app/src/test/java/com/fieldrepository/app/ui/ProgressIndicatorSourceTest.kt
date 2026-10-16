package com.fieldrepository.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Progress indicators go through `FieldProgress.kt` and nowhere else, and the colours it hands out
 * meet the rule written there: a track visibly distinct from the card, a fill at WCAG 1.4.11's 3:1
 * against both the track and the card, in light and in dark.
 */
class ProgressIndicatorSourceTest {

    @Test
    fun noScreenCallsTheMaterialIndicatorsDirectly() {
        val root = sourceRoot()
        val direct = Regex("""androidx\.compose\.material3\.(Linear|Circular)ProgressIndicator\b|(?<![A-Za-z.`\[])(Linear|Circular)ProgressIndicator\(""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "FieldProgress.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val code = line.trimStart()
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) null
                    else if (direct.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}" else null
                }
            }
            .toList()
        assertEquals(
            "Use FieldLinearProgressIndicator / FieldCircularProgressIndicator (ui/FieldProgress.kt) so the " +
                "tinted track and the 4dp round stroke apply:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun lightTrackIsDistinctAndFillsClearThreeToOne() {
        val card = FieldPalette.CardLight
        val track = FieldProgressDefaults.TrackLight
        assertTrue("track vs card ${ratio(track, card)}", ratio(track, card) >= 1.5)
        for (fill in listOf(FieldPalette.Purple700, FieldPalette.Success600)) {
            assertTrue("fill vs track ${ratio(fill, track)}", ratio(fill, track) >= 3.0)
            assertTrue("fill vs card ${ratio(fill, card)}", ratio(fill, card) >= 3.0)
        }
    }

    @Test
    fun darkTrackIsDistinctAndFillsClearThreeToOne() {
        val card = FieldPalette.CardDark
        val track = FieldProgressDefaults.TrackDark
        assertTrue("track vs card ${ratio(track, card)}", ratio(track, card) >= 1.5)
        for (fill in listOf(FieldPalette.Purple400, FieldPalette.Success400)) {
            assertTrue("fill vs track ${ratio(fill, track)}", ratio(fill, track) >= 3.0)
            assertTrue("fill vs card ${ratio(fill, card)}", ratio(fill, card) >= 3.0)
        }
    }

    @Test
    fun brandTileTrackAndFillClearThreeToOne() {
        for (tile in listOf(FieldPalette.Purple800, FieldPalette.Purple900)) {
            val track = FieldPalette.Purple50.copy(alpha = 0.24f).compositeOver(tile)
            assertTrue("fill vs track on $tile", ratio(FieldPalette.Purple300, track) >= 3.0)
            assertTrue("fill vs tile on $tile", ratio(FieldPalette.Purple300, tile) >= 3.0)
        }
    }

    private fun ratio(a: Color, b: Color): Double {
        val x = a.luminance().toDouble()
        val y = b.luminance().toDouble()
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun sourceRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in listOf("src/main/java", "app/src/main/java", "android/app/src/main/java")) {
                val candidate = File(dir, path)
                if (File(candidate, "com/fieldrepository/app/ui/FieldProgress.kt").isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found from ${File(".").absolutePath}")
    }
}
