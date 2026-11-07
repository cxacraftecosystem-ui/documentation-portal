package com.fieldrepository.app

internal fun packScreenTokens(tokens: List<String?>): String = buildString {
    tokens.forEach { token ->
        if (token == null) append("n;") else append(token.length).append(':').append(token)
    }
}

internal fun unpackScreenTokens(raw: String): List<String?> {
    val out = mutableListOf<String?>()
    var i = 0
    while (i < raw.length) {
        if (raw[i] == 'n' && raw.getOrNull(i + 1) == ';') {
            out += null
            i += 2
            continue
        }
        val colon = raw.indexOf(':', i)
        require(colon > i) { "Malformed screen token" }
        val length = raw.substring(i, colon).toInt()
        val end = colon + 1 + length
        require(length >= 0 && end <= raw.length) { "Malformed screen token" }
        out += raw.substring(colon + 1, end)
        i = end
    }
    return out
}
