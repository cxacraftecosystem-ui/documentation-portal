package com.fieldrepository.app.questionnaire.data

/**
 * A cursor-paged list as it was left: its rows (every page loaded), the cursor for the next page,
 * how many pages it holds, and the filters it was read for.
 */
data class KeptPages<T>(
    val rows: List<T> = emptyList(),
    val cursor: String? = null,
    val pages: Int = 0,
    val filters: QuestionnaireFilters? = null,
)

/**
 * HOW A LIST COMES BACK (owner, 1 Nov 2026: Back restores the exact page, never page 1).
 *
 * A list re-reads its first page whenever it is shown. Read for the same filters it already holds,
 * that page REFRESHES the rows it covers and every later page stays, with its cursor, so the row the
 * reader opened is still there to scroll back to. Read for different filters it starts over.
 */
object ListPaging {
    fun <T> arrive(
        kept: KeptPages<T>,
        filters: QuestionnaireFilters,
        first: List<T>?,
        firstCursor: String?,
        idOf: (T) -> String,
    ): KeptPages<T> {
        val same = kept.filters == filters && kept.pages > 0
        // A failed read keeps what is there for the same filters, and shows nothing for new ones.
        if (first == null) return if (same) kept else KeptPages(filters = filters)
        if (!same || kept.pages <= 1) return KeptPages(first, firstCursor, 1, filters)
        val fresh = first.map(idOf).toHashSet()
        return KeptPages(first + kept.rows.filter { idOf(it) !in fresh }, kept.cursor, kept.pages, filters)
    }

    /** The next page joins; a row already listed is not repeated. */
    fun <T> more(kept: KeptPages<T>, page: List<T>, cursor: String?, idOf: (T) -> String): KeptPages<T> {
        val seen = kept.rows.map(idOf).toHashSet()
        return kept.copy(rows = kept.rows + page.filter { seen.add(idOf(it)) }, cursor = cursor, pages = kept.pages + 1)
    }
}
