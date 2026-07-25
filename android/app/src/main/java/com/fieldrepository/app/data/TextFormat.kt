package com.fieldrepository.app.data

/**
 * Kotlin mirror of the server's canonical name normalisation — `backend/app/services/text_format.py`.
 *
 * WHY a second copy exists
 * ------------------------
 * The API title-cases every name-like column on WRITE (`services.records.clean_data`), so "kutch",
 * "KUTCH " and "Kutch" all land as one value and the exact-match craft lookup, the exports and the
 * .xlsx workbook stop splitting one village across three spellings. The form should therefore show
 * the researcher WHAT WILL BE STORED before they save, instead of silently changing their text after
 * the fact — which means the rule has to exist here too.
 *
 * It is a faithful port, not an approximation. Anything that diverges would show a preview the server
 * then contradicts, which is worse than showing no preview at all:
 *
 * 1. Every word's first letter is capitalised…
 * 2. …EXCEPT the conventional small words ([SMALL_WORDS]), which stay lowercase unless they are the
 *    FIRST or LAST word ("salt of the earth" -> "Salt of the Earth").
 * 3. Intentional casing is never destroyed. A word is left EXACTLY as typed when it contains a digit
 *    (`PMV001`, `A4` — identifiers, not prose) or an uppercase letter anywhere after its first
 *    character (`ABC`, `McDonald`, `O'Brien`, `iPhone`).
 * 3a. UNLESS the whole value is shouting. Rule 3 on its own preserved "ZARI WORK" verbatim while
 *    "zari work" became "Zari Work", and because the craft lookup is an EXACT name match that made
 *    two crafts out of one real craft — the duplication this rule exists to prevent, observed on the
 *    live system. An acronym is one token inside an otherwise normally-cased value; caps-lock shouts
 *    the WHOLE value. So when a value has no lowercase letter at all AND is either multi-word or a
 *    single word longer than [MAX_ACRONYM_LENGTH], rule 3 is suspended and the value is cased
 *    normally: "ZARI WORK" -> "Zari Work", "BANDHANI" -> "Bandhani". "ABC" and "SEWA" are still
 *    acronyms (single word, short enough), and "SEWA weaving" keeps SEWA because that value is not
 *    entirely upper. See [isShouting].
 * 4. Hyphens and slashes separate words ("kutch-bhuj" -> "Kutch-Bhuj"); apostrophes are part of the
 *    word ("don'ts" -> "Don'ts", never "Don'Ts"). The one exception is a SINGLE-letter particle
 *    before an apostrophe, which is a name prefix: "o'brien" -> "O'Brien".
 * 5. Non-Latin scripts are returned untouched — `localName` is stored in Devanagari and Gujarati,
 *    where "capitalising" is meaningless. The guard tests for Latin letters rather than listing
 *    scripts, so cased non-Latin scripts (Greek, Cyrillic) are left alone too.
 * 6. Idempotent: `titleCase(titleCase(x)) == titleCase(x)` for every input.
 *
 * Surrounding whitespace is stripped as part of the rule: "Bhuj " is the same name, and the stray
 * space is exactly what breaks the exact-match lookup.
 */

/** The conventional English title-case exceptions, byte-for-byte the server's `SMALL_WORDS`. */
val TITLE_CASE_SMALL_WORDS: Set<String> = setOf(
    "a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "into", "nor", "of", "on", "or",
    "over", "per", "the", "to", "up", "via", "vs", "with"
)

/**
 * The columns the API title-cases on write (`services.records.TITLE_CASE_FIELDS`). Deliberately does
 * NOT include notes/description/remarks/address/dos/donts/localName or any identifier — casing those
 * would damage meaning rather than tidy it.
 */
val TITLE_CASE_FIELDS: Set<String> = setOf(
    "name", "craftName", "artisanName", "productName", "toolkitName", "englishName", "title",
    "place", "placeName", "village", "district", "state"
)

// Straight and typographic apostrophes both occur — an Android keyboard produces U+2019 while the web
// form produces U+0027, and the same artisan's name must normalise identically from both.
private const val APOSTROPHES = "'’"

// Latin letters incl. Latin-1 Supplement + Latin Extended-A/B, matching the server's "A-Za-zÀ-ɏ".
private const val LATIN_RANGE = "A-Za-zÀ-ɏ"

// A "word" is a run of letters/digits/apostrophes. Everything else (spaces, hyphens, slashes, commas,
// brackets, ampersands, Devanagari, Gujarati) is a separator copied through verbatim — which is what
// makes hyphens and slashes behave as word boundaries without special-casing them.
private val WORD_RE = Regex("[0-9$LATIN_RANGE$APOSTROPHES]+")
private val LATIN_RE = Regex("[$LATIN_RANGE]")

// The longest single all-caps token still read as an acronym rather than caps-lock, byte-for-byte the
// server's `_MAX_ACRONYM_LENGTH`. Real acronyms in this domain are short (GI, NGO, SEWA, KVIC); a
// longer all-caps single word — "BANDHANI" — is somebody's caps-lock. Only consulted for a ONE-word
// value; a multi-word all-caps value is always treated as shouting. See [isShouting].
private const val MAX_ACRONYM_LENGTH = 4

/**
 * Upper-case the first letter, leaving the (already lower-cased) tail alone.
 *
 * `replaceFirstChar { it.uppercase() }` alone is not enough: a ONE-letter particle before an
 * apostrophe is a name prefix (O', D', L'), never a contraction — "don'ts" has its apostrophe at
 * index 3 and is untouched by this.
 */
private fun capitaliseWord(word: String): String {
    if (word.length > 2 && word[1] in APOSTROPHES) {
        return word[0].uppercase() + word[1] + word[2].uppercase() + word.substring(3)
    }
    return word[0].uppercase() + word.substring(1)
}

private fun caseWord(word: String, isFirst: Boolean, isLast: Boolean, shouting: Boolean): String {
    // Devanagari / Gujarati / digits-only: there is no meaningful capitalisation to apply.
    if (!LATIN_RE.containsMatchIn(word)) return word
    // An identifier, not prose: PMV001, A4, GI2019. Reshaping it would corrupt a real id.
    if (word.any { it.isDigit() }) return word
    // Deliberate interior capital: ABC, PMV, McDonald, O'Brien, iPhone. Left verbatim — which is also
    // what makes the whole function idempotent for everything it has already cased.
    //
    // Skipped when the WHOLE value is shouting (see [isShouting]). There, an all-caps word is
    // caps-lock rather than an acronym, and preserving it is what let "ZARI WORK" and "Zari Work"
    // become two different crafts.
    if (!shouting && word.drop(1).any { it.isUpperCase() }) return word
    val lowered = word.lowercase()
    if (lowered in TITLE_CASE_SMALL_WORDS && !isFirst && !isLast) return lowered
    return capitaliseWord(lowered)
}

/**
 * Is this whole value caps-lock rather than a deliberate acronym?
 *
 * The preserve-interior-capitals rule protects "ABC", "SEWA" and "McDonald" — but it also meant a
 * researcher with caps-lock on produced a value nothing would ever match: "ZARI WORK" was kept
 * verbatim while "zari work" became "Zari Work", so the exact-name craft lookup created a SECOND
 * craft for the same real craft.
 *
 * The signal that separates the two cases is scope: an acronym is one token inside an otherwise
 * normally-cased value, whereas caps-lock shouts the ENTIRE value. So a value is "shouting" when it
 * has at least one cased letter, no lowercase letter anywhere, and either contains a separator (two
 * or more words — multi-word acronyms are vanishingly rare in a craft or place name) or is a single
 * word longer than the longest plausible acronym.
 *
 * "SEWA weaving" keeps SEWA (the value is not entirely upper). "PMV001" never reaches here — the
 * digit rule catches it first.
 */
private fun isShouting(text: String): Boolean {
    if (text.any { it.isLowerCase() }) return false
    if (text.none { it.isUpperCase() }) return false
    val words = WORD_RE.findAll(text).toList()
    if (words.size > 1) return true
    return words.isNotEmpty() && words[0].value.length > MAX_ACRONYM_LENGTH
}

/** Title-case a name-like value exactly as the API will when it stores it. */
fun titleCase(value: String): String {
    val text = value.trim()
    // Nothing to do, or the whole value is in a script without case (an Indic `localName`).
    if (text.isEmpty() || !LATIN_RE.containsMatchIn(text)) return text

    val matches = WORD_RE.findAll(text).toList()
    if (matches.isEmpty()) return text

    // Decided once for the whole value: an all-caps word means something different in "ZARI WORK"
    // than it does in "SEWA weaving".
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

/**
 * The normalised value to SHOW the user, or null when there is nothing worth showing — the field is
 * empty, or what they typed is already exactly what will be stored. Callers render a "will be saved
 * as …" hint only for a non-null result, so a correctly-typed name adds no noise to the form.
 */
fun titleCasePreview(value: String): String? {
    val normalised = titleCase(value)
    return normalised.takeIf { it.isNotEmpty() && it != value }
}
