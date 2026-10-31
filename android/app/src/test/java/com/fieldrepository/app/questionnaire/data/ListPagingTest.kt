package com.fieldrepository.app.questionnaire.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Back from a record restores the exact page (owner, 1 Nov 2026): the first page read again for the
 * same filters refreshes the rows it covers and keeps every later page and its cursor; new filters
 * start over; a failed read keeps what was there.
 */
class ListPagingTest {
    private data class Row(val id: String, val label: String = id)

    private val all = QuestionnaireFilters()
    private val narrowed = QuestionnaireFilters().withQ("loom")

    private fun page(from: Int, to: Int, label: String = "") = (from until to).map { Row("r$it", "r$it$label") }

    @Test fun `the first read keeps one page and its cursor`() {
        val kept = ListPaging.arrive(KeptPages(), all, page(0, 30), "c1") { it.id }
        assertEquals(30, kept.rows.size)
        assertEquals("c1", kept.cursor)
        assertEquals(1, kept.pages)
        assertEquals(all, kept.filters)
    }

    @Test fun `load more joins the next page without repeating a row`() {
        val one = ListPaging.arrive(KeptPages(), all, page(0, 30), "c1") { it.id }
        val two = ListPaging.more(one, page(29, 60), "c2") { it.id }
        assertEquals(60, two.rows.size)
        assertEquals("c2", two.cursor)
        assertEquals(2, two.pages)
    }

    @Test fun `back to the same filters refreshes page one and keeps page two with its cursor`() {
        val two = ListPaging.more(ListPaging.arrive(KeptPages(), all, page(0, 30), "c1") { it.id }, page(30, 60), "c2") { it.id }
        val back = ListPaging.arrive(two, all, page(0, 30, label = "*"), "c1") { it.id }
        assertEquals(60, back.rows.size)
        assertEquals("r0*", back.rows.first().label)
        assertEquals("r45", back.rows[45].id)
        assertEquals("c2", back.cursor)
        assertEquals(2, back.pages)
    }

    @Test fun `new filters start over at page one`() {
        val two = ListPaging.more(ListPaging.arrive(KeptPages(), all, page(0, 30), "c1") { it.id }, page(30, 60), "c2") { it.id }
        val fresh = ListPaging.arrive(two, narrowed, page(0, 5), null) { it.id }
        assertEquals(5, fresh.rows.size)
        assertNull(fresh.cursor)
        assertEquals(1, fresh.pages)
    }

    @Test fun `a failed read keeps the rows for the same filters and shows none for new ones`() {
        val two = ListPaging.more(ListPaging.arrive(KeptPages(), all, page(0, 30), "c1") { it.id }, page(30, 60), "c2") { it.id }
        assertEquals(two, ListPaging.arrive(two, all, null, null) { it.id })
        assertEquals(0, ListPaging.arrive(two, narrowed, null, null) { it.id }.rows.size)
    }
}
