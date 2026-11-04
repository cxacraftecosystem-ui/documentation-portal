package com.fieldrepository.app.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SharedExplorerTreeTest {
    private fun repoRoot(): File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "shared/android-explorer.sha256").isFile }
        ?: error("shared/android-explorer.sha256 not found above ${File(".").absolutePath}")

    @Test fun `the shared trees are exactly what the manifest lists`() {
        val root = repoRoot()
        val manifest = File(root, "shared/android-explorer.sha256").readText().replace("\r\n", "\n").lines().filter { it.isNotBlank() }
            .associate { line -> line.substringAfter("  ") to line.substringBefore("  ") }
        val pkg = listOf("com/designprototype/workshop", "com/fieldrepository/app").first { File(root, "android/app/src/main/java/$it").isDirectory }
        val onDisk = listOf("main", "test", "androidTest").flatMap { set ->
            val base = File(root, "android/app/src/$set/java/$pkg")
            File(base, "explorer").walkTopDown().filter { it.isFile && it.extension == "kt" }
                .map { "$set/" + it.relativeTo(base).invariantSeparatorsPath }
                .filterNot { it.substringAfter('/').startsWith("explorer/portal/") }.toList()
        }.toSet()
        assertEquals("files missing from or extra to shared/android-explorer.sha256 (run python scripts/explorer_android.py)", manifest.keys, onDisk)
        val drifted = manifest.filter { (rel, digest) ->
            val file = File(root, "android/app/src/${rel.substringBefore('/')}/java/$pkg/${rel.substringAfter('/')}")
            val text = file.readText().replace("\r\n", "\n").replace("com.fieldrepository.app", "<pkg>").replace("com.fieldrepository.app", "<pkg>")
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) } != digest
        }.keys
        assertTrue("edited without the sync script: $drifted (run python scripts/explorer_android.py)", drifted.isEmpty())
    }

    @Test fun `the other portal carries the same trees`() {
        val sibling = System.getenv("SIBLING_REPO")?.let(::File)?.takeIf { File(it, "shared/android-explorer.sha256").isFile } ?: return
        assertEquals(
            "the two portals' shared/android-explorer.sha256 differ (run python scripts/explorer_android.py --to ../documentation-portal)",
            File(repoRoot(), "shared/android-explorer.sha256").readText().replace("\r\n", "\n"),
            File(sibling, "shared/android-explorer.sha256").readText().replace("\r\n", "\n")
        )
    }
}
