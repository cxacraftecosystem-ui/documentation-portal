package com.fieldrepository.app.questionnaire

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireVocabulary
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The questionnaire's shared Android trees (UNIFY U10): the same Kotlin in both portals once the
 * package prefix is normalised, every sentence from `shared/strings/questionnaire.json` word for word
 * (B37), every vocabulary from `shared/enums.json` and `shared/questionnaire-enums.json`, and the
 * files exactly as `shared/android-questionnaire.sha256` lists them. `scripts/sync_questionnaire.py`
 * rewrites the manifest and copies the trees to the other portal; this test fails on a hand edit that
 * skipped it.
 */
class SharedQuestionnaireTreeTest {
    private fun repoRoot(): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }.firstOrNull { File(it, "shared/android-questionnaire.sha256").isFile }
            ?: error("shared/android-questionnaire.sha256 not found above ${File(".").absolutePath}")

    private val table: Map<String, String> by lazy {
        val root = Json.parseToJsonElement(File(repoRoot(), "shared/strings/questionnaire.json").readText()) as JsonObject
        (root["strings"] as JsonObject).mapValues { it.value.jsonPrimitive.content }
    }

    @QFeature("B37")
    @Test fun `every shared sentence the app holds is the table's, character for character`() {
        val differ = QuestionnaireCopy.shared.filter { (key, text) -> table[key] != text }
            .mapValues { (key, text) -> "app: $text | table: ${table[key]}" }
        assertEquals("sentences that differ from shared/strings/questionnaire.json", emptyMap<String, String>(), differ)
        val dangling = QuestionnaireCopy.sharedKeyOf.filterValues { it !in table || it !in QuestionnaireCopy.shared }
        assertEquals(emptyMap<String, String>(), dangling)
        assertEquals(QuestionnaireCopy.sharedKeyOf.size + QuestionnaireCopy.androidOnly.size, QuestionnaireCopy.strings.size)
        val duplicates = QuestionnaireCopy.androidOnly.filterValues { it in table.values }
        assertEquals("these have a shared key; map them instead", emptyMap<String, String>(), duplicates)
    }

    @Test fun `templates fill from the table`() {
        assertEquals("Their 2 takes here will no longer count for them.", QuestionnaireCopy.p10Body(2))
        assertEquals("Their 1 take here will no longer count for them.", QuestionnaireCopy.p10Body(1))
        assertEquals("Join code K7Q2MX", QuestionnaireCopy.joinCode("K7Q2MX"))
        assertEquals("Group answer (3)", QuestionnaireCopy.groupAnswer(3))
        assertEquals("14 of 24 sections · 1 session · 2 repeats", QuestionnaireCopy.recordSummary(14, 24, 1, 2))
        assertEquals("Earlier title: section F", QuestionnaireCopy.earlierTitle("section F"))
        assertEquals(
            QuestionnaireCopy.t("missingOutboxFile", "target" to "Section D", "date" to "7 Jul"),
            SessionOutbox.missingFileMessage("Section D (7 Jul)")
        )
    }

    @Test fun `every vocabulary is the shared files', value for value`() {
        val root = repoRoot()
        fun lists(path: String): Map<String, List<String>> =
            ((Json.parseToJsonElement(File(root, path).readText()) as JsonObject)["enums"] as JsonObject)
                .mapValues { (_, v) -> (v as JsonArray).map { it.jsonPrimitive.content } }
        val expected = lists("shared/enums.json").toMutableMap()
        lists("shared/questionnaire-enums.json").forEach { (k, v) -> expected.putIfAbsent(k, v) }
        assertEquals(expected, QuestionnaireVocabulary.lists)
    }

    @Test fun `the shared trees are exactly what the manifest lists`() {
        val root = repoRoot()
        val manifest = File(root, "shared/android-questionnaire.sha256").readText().replace("\r\n", "\n").lines().filter { it.isNotBlank() }
            .associate { line -> line.substringAfter("  ") to line.substringBefore("  ") }
        val pkg = listOf("com/designprototype/workshop", "com/fieldrepository/app").first { File(root, "android/app/src/main/java/$it").isDirectory }
        val trees = listOf("main" to "questionnaire/data", "main" to "questionnaire/ui", "main" to "recording", "test" to "questionnaire", "test" to "recording", "androidTest" to "questionnaire", "androidTest" to "recording")
        val onDisk = trees.flatMap { (set, tree) ->
            val base = File(root, "android/app/src/$set/java/$pkg")
            File(base, tree).walkTopDown().filter { it.isFile && it.extension == "kt" }
                .map { "$set/" + it.relativeTo(base).invariantSeparatorsPath }
                .filterNot { it.substringAfter('/').startsWith("questionnaire/portal/") }.toList()
        }.toSet()
        assertEquals("files missing from or extra to shared/android-questionnaire.sha256 (run python scripts/sync_questionnaire.py)", manifest.keys, onDisk)
        val drifted = manifest.filter { (rel, digest) ->
            val file = File(root, "android/app/src/${rel.substringBefore('/')}/java/$pkg/${rel.substringAfter('/')}")
            val text = file.readText().replace("\r\n", "\n").replace("com.fieldrepository.app", "<pkg>").replace("com.fieldrepository.app", "<pkg>")
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) } != digest
        }.keys
        assertTrue("edited without the sync script: $drifted (run python scripts/sync_questionnaire.py)", drifted.isEmpty())
    }
}
