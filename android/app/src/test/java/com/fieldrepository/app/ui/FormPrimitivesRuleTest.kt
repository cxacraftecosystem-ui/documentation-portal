package com.fieldrepository.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE OWNER'S RULE (2026-10-17), held mechanically across the WHOLE app, as
 * `e2e/form-primitives-rule-unit.spec.ts` holds it on the web: every on/off setting is an [AppToggle],
 * every date a [CalendarField] — never a bare Material `Switch`, never the word "ON"/"OFF" as a
 * setting's state, never a raw [FieldDateField] outside the file that defines it and the primitive
 * that wraps it. `PrimitivesTest` checks the converted screens one by one; this one checks every file.
 *
 * A checkbox choosing ROWS (select these three records) is legitimate and cannot be told from a
 * setting by a scan, so that half is held by review and the field-repo-frontend skill (§11.11).
 * The designer repo carries the same test under the same name.
 */
class FormPrimitivesRuleTest {

    // RepoSources' walk finds FILES, so it is pointed at a file in the root and climbs one.
    private val root: File = repoFile(
        "src/main/java/com/fieldrepository/app/MainActivity.kt",
        "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt"
    ).parentFile

    private fun offenders(pattern: Regex, allowed: Set<String>): List<String> =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    // Prose is not code: KDoc and comment lines quote the old controls by name.
                    val trimmed = line.trimStart()
                    val prose = trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")
                    val code = line.substringBefore("//")
                    if (!prose && pattern.containsMatchIn(code)) "${file.name}:${index + 1}" else null
                }
            }
            .toList()

    @Test
    fun `the scan finds the sources it guards`() {
        assertTrue(File(root, "MainActivity.kt").isFile)
        assertTrue(File(root, "ui/AppToggle.kt").isFile)
    }

    @Test
    fun `a setting is never a bare Material Switch`() {
        assertEquals(emptyList<String>(), offenders(Regex("""(^|[^A-Za-z.])Switch\("""), setOf("AppToggle.kt")))
    }

    @Test
    fun `a setting never reads as an ON or OFF word`() {
        assertEquals(
            emptyList<String>(),
            offenders(Regex(""""On" else "Off"|"ON" else "OFF"|: ON"|: OFF""""), emptySet())
        )
    }

    @Test
    fun `every date field is the CalendarField`() {
        // DateTimeFields.kt defines FieldDateField, CalendarField over it and the range field built from two.
        assertEquals(
            emptyList<String>(),
            offenders(Regex("""(^|[^A-Za-z])FieldDateField\("""), setOf("DateTimeFields.kt"))
        )
    }
}
