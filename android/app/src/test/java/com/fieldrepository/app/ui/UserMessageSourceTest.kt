package com.fieldrepository.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The CI half of UX audit 2026-10-16, DC-A02: no screen may hand a throwable's own text to a reader.
 *
 * `it.message ?: "Unable to …"` put "HTTP 404" and "Unable to resolve host …" on about seventy
 * screens. The one mapper is `Throwable.userMessage` (data/UserMessage.kt); this test refuses the old
 * shape in MainActivity.kt and everything under ui/, so a new screen cannot go back to it.
 *
 * ALLOWED holds the lines that read `.message` from something that is not a throwable shown as-is:
 * a DTO's own server-written `message` field, or an exception type this app throws with a clause it
 * wrote for people. Each is listed by a fragment of its own line, so a move does not break it and a
 * new site is refused until somebody decides.
 */
class UserMessageSourceTest {

    private val root = File("src/main/java/com/fieldrepository/app")

    private val allowed = listOf(
        // A response DTO's `message`, written by the server for people.
        "resp.message ?:",
        // A queued save's own sentence ("Saved on this device…"), thrown by createOrKeep for people.
        "onError(error.message!!)",
    )

    private val refused = listOf(
        Regex("""\b(it|e|t|error|throwable|failure|cause)\.message \?: """"),
        Regex("""(onError|showMessage|showError|onMessage)\((it|e|t|error)\.message\)"""),
        Regex("""\$\{(it|e|t|error)\.message"""),
    )

    @Test
    fun `no screen shows a throwable's own message`() {
        val files = listOf(File(root, "MainActivity.kt")) +
            File(root, "ui").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the sources were not found from ${File(".").absolutePath}", files.first().isFile)
        val offences = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//")
                if (refused.none { it.containsMatchIn(code) } || allowed.any { it in code }) null
                else "${file.relativeTo(root)}:${index + 1}: ${line.trim()}"
            }
        }
        assertTrue(
            "Use Throwable.userMessage(fallback) from data/UserMessage.kt instead of the raw message:\n" +
                offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }
}
