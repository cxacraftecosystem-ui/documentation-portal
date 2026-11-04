package com.fieldrepository.app.data

val TITLE_CASE_SMALL_WORDS: Set<String> = setOf(
    "a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "into", "nor", "of", "on", "or",
    "over", "per", "the", "to", "up", "via", "vs", "with"
)

val TITLE_CASE_FIELDS: Set<String> = setOf(
    "name", "craftName", "artisanName", "productName", "toolkitName", "englishName", "title",
    "place", "placeName", "village", "district", "state"
)

private const val APOSTROPHES = "'’"

private const val LATIN_RANGE = "A-Za-zÀ-ɏ"

private val WORD_RE = Regex("[0-9$LATIN_RANGE$APOSTROPHES]+")
private val LATIN_RE = Regex("[$LATIN_RANGE]")

private const val MAX_ACRONYM_LENGTH = 4

private fun capitaliseWord(word: String): String {
    if (word.length > 2 && word[1] in APOSTROPHES) {
        return word[0].uppercase() + word[1] + word[2].uppercase() + word.substring(3)
    }
    return word[0].uppercase() + word.substring(1)
}

private fun caseWord(word: String, isFirst: Boolean, isLast: Boolean, shouting: Boolean): String {
    if (!LATIN_RE.containsMatchIn(word)) return word
    if (word.any { it.isDigit() }) return word
    if (!shouting && word.drop(1).any { it.isUpperCase() }) return word
    val lowered = word.lowercase()
    if (lowered in TITLE_CASE_SMALL_WORDS && !isFirst && !isLast) return lowered
    return capitaliseWord(lowered)
}

private fun isShouting(text: String): Boolean {
    if (text.any { it.isLowerCase() }) return false
    if (text.none { it.isUpperCase() }) return false
    val words = WORD_RE.findAll(text).toList()
    if (words.size > 1) return true
    return words.isNotEmpty() && words[0].value.length > MAX_ACRONYM_LENGTH
}

fun titleCase(value: String): String {
    val text = value.trim()
    if (text.isEmpty() || !LATIN_RE.containsMatchIn(text)) return text

    val matches = WORD_RE.findAll(text).toList()
    if (matches.isEmpty()) return text

    val shouting = isShouting(text)

    val out = StringBuilder(text.length)
    var cursor = 0
    val lastIndex = matches.size - 1
    matches.forEachIndexed { index, match ->
        out.append(text, cursor, match.range.first)
        out.append(caseWord(match.value, isFirst = index == 0, isLast = index == lastIndex, shouting = shouting))
        cursor = match.range.last + 1
    }
    out.append(text, cursor, text.length)
    return out.toString()
}

fun titleCasePreview(value: String): String? {
    val normalised = titleCase(value)
    return normalised.takeIf { it.isNotEmpty() && it != value }
}

fun countNoun(count: Int, one: String, many: String = "${one}s"): String =
    "$count ${if (count == 1) one else many}"
