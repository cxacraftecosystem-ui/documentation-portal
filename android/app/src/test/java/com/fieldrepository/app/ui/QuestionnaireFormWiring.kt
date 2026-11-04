package com.fieldrepository.app.ui

internal fun kotlinWithoutComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    var depth = 0
    while (i < source.length) {
        val c = source[i]
        val next = if (i + 1 < source.length) source[i + 1] else ' '
        when {
            depth > 0 -> when {
                c == '/' && next == '*' -> { depth++; i += 2 }
                c == '*' && next == '/' -> { depth--; i += 2 }
                else -> { if (c == '\n') out.append('\n'); i++ }
            }
            c == '/' && next == '*' -> { depth = 1; i += 2 }
            c == '/' && next == '/' -> while (i < source.length && source[i] != '\n') i++
            c == '"' && source.startsWith("\"\"\"", i) -> {
                val end = source.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) source.length else end + 3
                out.append(source, i, stop)
                i = stop
            }
            c == '"' || c == '\'' -> {
                val quote = c
                out.append(c)
                i++
                while (i < source.length) {
                    val ch = source[i]
                    out.append(ch)
                    i++
                    if (ch == '\\' && i < source.length) {
                        out.append(source[i])
                        i++
                    } else if (ch == quote || ch == '\n') {
                        break
                    }
                }
            }
            else -> { out.append(c); i++ }
        }
    }
    return out.toString()
}

internal fun balancedFrom(source: String, start: Int, opener: Char, closer: Char): String {
    require(source[start] == opener) { "balancedFrom must start on its opener" }
    var depth = 0
    var i = start
    while (i < source.length) {
        val c = source[i]
        if (c == opener) depth++
        if (c == closer) {
            depth--
            if (depth == 0) return source.substring(start, i + 1)
        }
        i++
    }
    throw AssertionError("unbalanced $opener from offset $start")
}
