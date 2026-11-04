package com.fieldrepository.app.questionnaire.data

data class KeptPages<T>(
    val rows: List<T> = emptyList(),
    val cursor: String? = null,
    val pages: Int = 0,
    val filters: QuestionnaireFilters? = null,
)

object ListPaging {
    fun <T> arrive(
        kept: KeptPages<T>,
        filters: QuestionnaireFilters,
        first: List<T>?,
        firstCursor: String?,
        idOf: (T) -> String,
    ): KeptPages<T> {
        val same = kept.filters == filters && kept.pages > 0
        if (first == null) return if (same) kept else KeptPages(filters = filters)
        if (!same || kept.pages <= 1) return KeptPages(first, firstCursor, 1, filters)
        val fresh = first.map(idOf).toHashSet()
        return KeptPages(first + kept.rows.filter { idOf(it) !in fresh }, kept.cursor, kept.pages, filters)
    }

    fun <T> more(kept: KeptPages<T>, page: List<T>, cursor: String?, idOf: (T) -> String): KeptPages<T> {
        val seen = kept.rows.map(idOf).toHashSet()
        return kept.copy(rows = kept.rows + page.filter { seen.add(idOf(it)) }, cursor = cursor, pages = kept.pages + 1)
    }
}
