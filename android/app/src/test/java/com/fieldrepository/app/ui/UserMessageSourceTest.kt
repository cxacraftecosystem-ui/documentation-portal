package com.fieldrepository.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UserMessageSourceTest {

    private val root = File("src/main/java/com/fieldrepository/app")

    private val allowed = listOf(
        "resp.message ?:",
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
