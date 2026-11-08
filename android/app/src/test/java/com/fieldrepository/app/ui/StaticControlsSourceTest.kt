package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StaticControlsSourceTest {

    @Test
    fun noScreenUsesTheAnimatedMaterialRadioOrSwitch() {
        val root = sourceRoot()
        val direct = Regex("""androidx\.compose\.material3\.(RadioButton|Switch|SwitchDefaults|RadioButtonDefaults)\b|(?<![A-Za-z0-9_.`])(RadioButton|Switch)\(""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (direct.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}" else null
                }
            }
            .toList()
        assertEquals("Use FieldRadioButton and FieldSwitch (ui/), the static controls:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }

    @Test
    fun theStaticControlsNeverAnimate() {
        for (name in listOf("FieldRadioButton.kt", "FieldSwitch.kt")) {
            val source = File(sourceRoot(), "$PKG_PATH/ui/$name").readText()
            for (word in listOf("animate", "Animatable", "Transition", "tween", "spring(")) {
                assertFalse("$name must not use $word", source.contains(word))
            }
        }
    }

    @Test
    fun theSwitchThumbSitsWhereTheWebPutsIt() {
        assertEquals(3f, FieldSwitchDefaults.thumbLeft(false).value)
        assertEquals(22f, FieldSwitchDefaults.thumbLeft(true).value)
        assertEquals(48f, FieldSwitchDefaults.Width.value)
        assertEquals(28f, FieldSwitchDefaults.Height.value)
        assertTrue(FieldRadioDefaults.Dot < FieldRadioDefaults.Box)
    }

    private fun sourceRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in listOf("src/main/java", "app/src/main/java", "android/app/src/main/java")) {
                val candidate = File(dir, path)
                if (File(candidate, "$PKG_PATH/ui/FieldSwitch.kt").isFile) return candidate
            }
            dir = dir.parentFile
        }
        error("source root not found")
    }

    private companion object {
        const val PKG_PATH = "com/fieldrepository/app"
    }
}
