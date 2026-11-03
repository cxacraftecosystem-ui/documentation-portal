package com.fieldrepository.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRecordRoutingTest {

    private val source = repoSource(
        "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
        "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
    )

    private val mapper: String by lazy {
        val start = source.indexOf("fun searchRecordEntryMode(")
        assertTrue("searchRecordEntryMode must exist", start >= 0)
        source.substring(start, source.indexOf("\n}\n", start))
    }

    @Test
    fun `an unknown record type opens nothing rather than the artisan editor`() {
        assertFalse("the ARTISAN fallback must not come back", Regex("""else\s*->\s*EntryMode\.ARTISAN""").containsMatchIn(mapper))
        assertTrue(Regex("""else\s*->\s*null""").containsMatchIn(mapper))
    }

    @Test
    fun `every record type the search panel can report has its own arm`() {
        listOf(
            "ARTISAN", "WORKSHOP", "PRODUCT", "TOOL", "MEDIA", "CRAFT", "PROCESS", "QUESTIONNAIRE"
        ).forEach { type ->
            assertTrue("SearchRecordTypes.$type needs an arm", mapper.contains("SearchRecordTypes.$type ->"))
        }
    }

    @Test
    fun `the wire strings match the designer portal's record types`() {
        assertTrue(SearchRecordTypes.CRAFT == "craft")
        assertTrue(SearchRecordTypes.PROCESS == "process")
        assertTrue(SearchRecordTypes.QUESTIONNAIRE == "questionnaire")
        assertFalse("crafts/processes/interviews are not buckets of GET /search", SearchRecordTypes.ALL.contains("craft"))
    }

    @Test
    fun `every call site routes through the one opener`() {
        assertFalse(
            "no call site may build Screen.Edit from the mapper directly",
            source.contains("Screen.Edit(searchRecordEntryMode(")
        )
        assertTrue(source.contains("fun openSearchRecord(recordType: String, recordId: String)"))
    }
}
