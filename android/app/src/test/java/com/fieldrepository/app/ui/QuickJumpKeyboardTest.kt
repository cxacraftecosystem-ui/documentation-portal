package com.fieldrepository.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class QuickJumpKeyboardTest {

    private val pkg = "com/fieldrepository/app/ui"

    @Test
    fun `opening a quick-jump result drops focus and hides the keyboard before it opens`() {
        val source = source("DataBrowserScreen.kt")
        val row = handler(source.substringAfter("SearchResultRow(row = hit, onOpen = {"), "onOpen(hit.recordType")
        assertDismissesKeyboard("SearchResultRow onOpen", row)
        val dialog = handler(
            source.substringAfter("SearchAllDialog(").substringAfter("onOpen = { type, id, title ->"),
            "onOpen(type, id, title)",
        )
        assertDismissesKeyboard("SearchAllDialog onOpen", dialog)
    }

    @Test
    fun `the full search only takes focus when it opens with nothing typed`() {
        val source = source("DataUnifiedSearch.kt").substringAfter("internal fun DataUnifiedSearch(")
        val requests = source.lines().filter { it.contains("focus.requestFocus()") }
        assertTrue("DataUnifiedSearch no longer requests focus", requests.isNotEmpty())
        requests.forEach { line ->
            assertTrue("focus is requested even when a query was handed in: ${line.trim()}", line.contains("initial.isBlank()"))
        }
    }

    private fun handler(after: String, opens: String): String {
        assertTrue("handler not found", after.contains(opens))
        return after.substringBefore(opens)
    }

    private fun assertDismissesKeyboard(what: String, body: String) {
        assertTrue("$what leaves the field focused", body.contains("focusManager.clearFocus()"))
        assertTrue("$what leaves the keyboard up", body.contains("keyboard?.hide()"))
        assertFalse("$what opens before dismissing the keyboard", body.contains("onOpen("))
    }

    private fun source(name: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in listOf("src/main/java", "app/src/main/java", "android/app/src/main/java")) {
                val candidate = File(dir, "$path/$pkg/$name")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("$name not found from ${File(".").absolutePath}")
    }
}
