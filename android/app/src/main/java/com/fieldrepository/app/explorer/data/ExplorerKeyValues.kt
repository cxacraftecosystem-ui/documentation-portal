package com.fieldrepository.app.explorer.data

data class ExplorerTextBody(
    val title: String? = null,
    val fields: List<ExplorerInfoField> = emptyList(),
    val paragraphs: List<String> = emptyList(),
) {
    val tabular: Boolean get() = fields.size >= ExplorerKeyValues.MIN_FIELDS
}

object ExplorerKeyValues {
    const val MIN_FIELDS = 2
    private const val MAX_KEY = 48
    private const val MAX_KEY_WORDS = 6
    private val PAIR = Regex("^\\s*([^:\\n]+?)\\s*:(?:\\s+(.*)|\\s*)$")

    fun pair(line: String): Pair<String, String>? {
        val match = PAIR.matchEntire(line) ?: return null
        val key = match.groupValues[1].trim()
        val value = match.groupValues[2].trim()
        if (key.isEmpty() || key.length > MAX_KEY) return null
        if (key.split(Regex("\\s+")).size > MAX_KEY_WORDS) return null
        if (key.all { it.isDigit() }) return null
        if (key.first() in "[-*#>•(\"'") return null
        if (key.contains("http", ignoreCase = true) || value.startsWith("//")) return null
        return key to value
    }

    fun parse(text: String): ExplorerTextBody {
        val lines = text.replace("\r\n", "\n").split("\n")
        var index = lines.indexOfFirst { it.isNotBlank() }
        if (index < 0) return ExplorerTextBody()
        var title: String? = null
        val first = lines[index]
        val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() }
        if (pair(first) == null && next != null && pair(next) != null) {
            title = first.trim()
            index = lines.indexOf(next).takeIf { it > index } ?: (index + 1)
        }
        val fields = ArrayList<ExplorerInfoField>()
        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) break
            val kv = pair(line)
            when {
                kv != null -> fields += ExplorerInfoField(kv.first, kv.second)
                fields.isNotEmpty() -> {
                    val last = fields.removeAt(fields.lastIndex)
                    fields += last.copy(value = listOf(last.value, line.trim()).filter { it.isNotEmpty() }.joinToString("\n"))
                }
                else -> break
            }
            index++
        }
        if (fields.size < MIN_FIELDS) return ExplorerTextBody(paragraphs = paragraphs(text))
        val rest = lines.drop(index).joinToString("\n")
        return ExplorerTextBody(title = title, fields = fields.toList(), paragraphs = paragraphs(rest))
    }

    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("\\n\\s*\\n")).map { it.trimEnd().trimStart('\n') }.filter { it.isNotBlank() }
}
