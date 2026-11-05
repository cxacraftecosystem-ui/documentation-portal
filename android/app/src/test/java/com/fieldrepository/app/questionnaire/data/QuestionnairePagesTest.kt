package com.fieldrepository.app.questionnaire.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionnairePagesTest {
    private fun pages(total: Int, size: Int): suspend (String?) -> Pair<List<String>, String?> = { cursor ->
        val from = cursor?.toInt() ?: 0
        val until = minOf(from + size, total)
        ((from + 1)..until).map { "s$it" } to if (until < total) "$until" else null
    }

    @Test fun `every page is walked to the last session`() = runBlocking {
        val asked = mutableListOf<String?>()
        val walk = pages(231, QuestionnairePages.PAGE)
        val all = QuestionnairePages.every<String> { cursor -> asked += cursor; walk(cursor) }
        assertEquals(231, all.size)
        assertEquals("s231", all.last())
        assertEquals(listOf(null, "100", "200"), asked)
    }

    @Test fun `a repeated cursor stops the walk instead of looping`() = runBlocking {
        var calls = 0
        val all = QuestionnairePages.every<String> { _ -> calls++; listOf("s$calls") to "same" }
        assertEquals(2, calls)
        assertEquals(listOf("s1", "s2"), all)
    }

    @Test fun `one short page is one call`() = runBlocking {
        var calls = 0
        val all = QuestionnairePages.every<String> { cursor -> calls++; pages(7, 100)(cursor) }
        assertEquals(1, calls)
        assertEquals(7, all.size)
    }
}
