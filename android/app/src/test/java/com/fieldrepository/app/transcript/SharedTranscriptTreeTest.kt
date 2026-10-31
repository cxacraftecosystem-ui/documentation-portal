package com.fieldrepository.app.transcript

import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.json
import com.fieldrepository.app.transcript.data.TranscriptStrings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The transcript trees are the same Kotlin in both portals (`scripts/transcript_android.py`), and
 * every sentence is `shared/strings/transcript.json`'s, character for character.
 */
class SharedTranscriptTreeTest {
    private fun repoRoot(): File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "shared/android-transcript.sha256").isFile }
        ?: error("shared/android-transcript.sha256 not found above ${File(".").absolutePath}")

    @Test fun `every string is the shared table's`() {
        val table = (json.parseToJsonElement(File(repoRoot(), "shared/strings/transcript.json").readText()).jsonObject["strings"] as JsonObject)
            .mapValues { it.value.jsonPrimitive.content }
        assertEquals(table, TranscriptStrings.table)
        assertEquals("3 selected", TranscriptStrings.selectedCount(3))
        assertEquals("Speaker 2", TranscriptStrings.speakerDefault(2))
        assertEquals("$1.00 of $100.00 this month", TranscriptStrings.budgetSpent("$1.00", "$100.00"))
    }

    @Test fun `the shared trees are exactly what the manifest lists`() {
        val root = repoRoot()
        val manifest = File(root, "shared/android-transcript.sha256").readText().replace("\r\n", "\n").lines().filter { it.isNotBlank() }
            .associate { line -> line.substringAfter("  ") to line.substringBefore("  ") }
        val pkg = listOf("com/designprototype/workshop", "com/fieldrepository/app").first { File(root, "android/app/src/main/java/$it").isDirectory }
        val onDisk = listOf("main", "test", "androidTest").flatMap { set ->
            val base = File(root, "android/app/src/$set/java/$pkg")
            File(base, "transcript").walkTopDown().filter { it.isFile && it.extension == "kt" }
                .map { "$set/" + it.relativeTo(base).invariantSeparatorsPath }
                .filterNot { it.substringAfter('/').startsWith("transcript/portal/") }.toList()
        }.toSet()
        assertEquals("files missing from or extra to shared/android-transcript.sha256 (run python scripts/transcript_android.py)", manifest.keys, onDisk)
        val drifted = manifest.filter { (rel, digest) ->
            val file = File(root, "android/app/src/${rel.substringBefore('/')}/java/$pkg/${rel.substringAfter('/')}")
            val text = file.readText().replace("\r\n", "\n").replace("com.fieldrepository.app", "<pkg>").replace("com.fieldrepository.app", "<pkg>")
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) } != digest
        }.keys
        assertTrue("edited without the sync script: $drifted (run python scripts/transcript_android.py)", drifted.isEmpty())
    }

    /** CI clones the other portal beside this one (android-build.yml); both manifests must agree. */
    @Test fun `the other portal carries the same trees`() {
        val sibling = System.getenv("SIBLING_REPO")?.let(::File)?.takeIf { File(it, "shared/android-transcript.sha256").isFile } ?: return
        assertEquals(
            "the two portals' shared/android-transcript.sha256 differ (run python scripts/transcript_android.py --to ../documentation-portal)",
            File(repoRoot(), "shared/android-transcript.sha256").readText().replace("\r\n", "\n"),
            File(sibling, "shared/android-transcript.sha256").readText().replace("\r\n", "\n")
        )
    }
}
