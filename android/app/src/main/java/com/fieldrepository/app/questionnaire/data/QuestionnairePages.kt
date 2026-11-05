package com.fieldrepository.app.questionnaire.data

object QuestionnairePages {
    const val PAGE = 100

    suspend fun <T> every(fetch: suspend (cursor: String?) -> Pair<List<T>, String?>): List<T> {
        val out = mutableListOf<T>()
        val seen = hashSetOf<String>()
        var cursor: String? = null
        do {
            val (items, next) = fetch(cursor)
            out += items
            cursor = next?.takeIf { it.isNotBlank() && seen.add(it) }
        } while (cursor != null)
        return out
    }
}
