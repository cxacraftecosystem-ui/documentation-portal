package com.fieldrepository.app.questionnaire

import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QuestionnaireStringKeysTest {
    private fun sources(): List<File> {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }.firstOrNull { File(it, "shared/android-questionnaire.sha256").isFile }
            ?: error("shared/android-questionnaire.sha256 not found above ${File(".").absolutePath}")
        val pkg = QuestionnaireCopy::class.java.`package`!!.name.removeSuffix(".questionnaire.data").replace('.', '/')
        val base = File(root, "android/app/src/main/java/$pkg")
        return listOf("questionnaire", "recording").map { File(base, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    private fun keys(pattern: Regex): Map<String, List<String>> = sources()
        .flatMap { file -> pattern.findAll(file.readText()).map { it.groupValues[1] to file.name }.toList() }
        .groupBy({ it.first }, { it.second })

    @Test fun `every literal s key a screen asks for resolves`() {
        val asked = keys(Regex("""QuestionnaireCopy\.s\(\s*"([^"]+)"\s*\)"""))
        assertTrue("no s() calls found; the scan is looking in the wrong place", asked.size > 50)
        val missing = asked.filterKeys { it !in QuestionnaireCopy.strings && it !in QuestionnaireCopy.shared }
        assertEquals("QuestionnaireCopy.s keys with no string; each would throw in composition", emptyMap<String, List<String>>(), missing)
    }

    @Test fun `every literal t key a screen asks for is a shared sentence`() {
        val asked = keys(Regex("""QuestionnaireCopy\.t\(\s*"([^"]+)""""))
        assertTrue("no t() calls found; the scan is looking in the wrong place", asked.isNotEmpty())
        val missing = asked.filterKeys { it !in QuestionnaireCopy.shared }
        assertEquals("QuestionnaireCopy.t keys with no shared sentence; each would throw in composition", emptyMap<String, List<String>>(), missing)
    }

    @Test fun `the battery prompt on the sessions home names its buttons`() {
        assertEquals("Not now", QuestionnaireCopy.s("battery.notNow"))
        assertEquals("Not now", QuestionnaireCopy.t("notNow"))
        assertEquals("Keep recording in the background", QuestionnaireCopy.s("battery.title"))
    }

    @Test fun `a release build shows words for a missing key instead of crashing`() {
        val before = QuestionnaireCopy.strict
        try {
            QuestionnaireCopy.strict = false
            assertEquals("Not There", QuestionnaireCopy.t("notThere"))
            assertEquals("Not There", QuestionnaireCopy.s("battery.notThere"))
            QuestionnaireCopy.strict = true
            assertTrue(runCatching { QuestionnaireCopy.t("notThere") }.isFailure)
        } finally {
            QuestionnaireCopy.strict = before
        }
    }
}
