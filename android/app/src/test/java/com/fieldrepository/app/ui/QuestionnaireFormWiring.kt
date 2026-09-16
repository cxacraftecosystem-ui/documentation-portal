package com.fieldrepository.app.ui

/**
 * SOURCE-SCANNING PRIMITIVES THE QUESTIONNAIRE SUITES SHARE — a Kotlin comment stripper and a
 * brace/paren balancer, kept beside [repoFile] because they are the same kind of thing: the small
 * amount of parsing a test has to do before it can assert about a file it reads off disk.
 *
 * ── WHY A THIRD COPY OF THE STRIPPER WAS NOT WRITTEN ────────────────────────────────────────────
 *
 * `RecordDictationParityTest` and `WalkthroughSurfaceTest` each carry a private `stripComments`, and
 * the two HAVE ALREADY DRIFTED: one preserves newlines and understands triple-quoted strings and
 * char literals, the other does neither. That is precisely the outcome `RepoSources.kt` predicted in
 * writing about its own subject — *"two copies of a walk-up is how one of them later stops checking
 * the candidate that mattered"* — and this file is the fourth suite arriving to find it true.
 *
 * SO WHY ARE THOSE TWO NOT POINTED HERE IN THIS COMMIT. Because they no longer agree about what the
 * function DOES, so moving them is not a lift but a reconciliation: one of the two behaviours has to
 * win, both suites have to be re-verified against the winner, and `RecordDictationParityTest` pins
 * an exact COUNT that a differently-behaving stripper could move. That is its own change with its
 * own evidence. Landing it inside a commit about which boxes the questionnaire form draws would put
 * two unrelated risks under one reason, which is the habit this repository's audits keep finding.
 * The condition for doing it is recorded here so the next person does not have to rediscover it.
 *
 * ── WHY STRIP AT ALL ────────────────────────────────────────────────────────────────────────────
 *
 * This repository records WHY in long comments, and those comments QUOTE the code they are about:
 * the questionnaire form's own comments name `questionnaireId`, `artisanIds` and
 * `ArtisanMultiSelectField` while arguing about them. A scanner that reads raw source finds every
 * one of those and reports a form as correctly wired because somebody wrote a paragraph saying it
 * should be — the worst possible failure for an assertion whose whole job is to notice a control
 * that renders without saving.
 */

/**
 * *source* with `//` and (nesting) `/* … */` comments removed, strings and char literals preserved.
 *
 * NEWLINES SURVIVE a stripped comment, so a line number computed from the output still points at the
 * same line of the file. That is not decoration: a failure that names a line the reader can open is
 * the difference between a report about the form and a report about this parser.
 *
 * It is NOT a Kotlin parser. A string template containing a nested string with a comment sequence in
 * it would be mis-split. Nothing in the questionnaire form does that, and the failure mode is a
 * false report rather than a silent pass — the assertions below all fail CLOSED, naming what they
 * could not find.
 */
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

/**
 * The balanced run starting at [start], which must be the [opener] itself — openers and closers
 * included.
 *
 * BALANCED AND NOT "UP TO THE NEXT `)`", because every argument list this file reads contains nested
 * calls, lambdas and `when` blocks. The naive slice ends at the first `)` of `place.blankToNull()`
 * and reports the rest of the payload as absent, which would fail a correctly-wired form.
 *
 * Fed comment-stripped source by every caller here, so a brace inside a comment cannot unbalance it;
 * string literals are left intact by [kotlinWithoutComments] and a brace inside one WOULD, which is
 * why this is only ever pointed at declaration and argument syntax.
 */
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
