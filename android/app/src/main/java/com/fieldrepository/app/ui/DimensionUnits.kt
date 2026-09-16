package com.fieldrepository.app.ui

import kotlin.math.floor

/**
 * CENTIMETRES ↔ INCHES FOR THE TOOL FORM'S PAIRED DIMENSION BOXES.
 *
 * The web twin of this file is `frontend/components/forms/dimensionUnits.ts`, and the two were
 * written together on purpose — the same reason `ui/RecordPickers.kt` names its own twin in its
 * header. Four clients across two repositories convert the same two boxes, and the failure mode of
 * one rule implemented four times is that they agree on the day they are written and disagree on a
 * halfway value six months later, in a column nobody re-reads.
 *
 * IN A `ui/` FILE RATHER THAN IN `MainActivity.kt` BECAUSE THAT IS WHERE A JVM UNIT TEST CAN REACH
 * IT — exactly the reason `ui/RecordPickers.kt`'s header gives for `craftChangeClearsArtisan`
 * living there. `DimensionUnitsTest` drives every row of the table the four clients share.
 *
 * ── WHAT IS PAIRED, AND WHAT DELIBERATELY IS NOT ────────────────────────────────────────────────
 *
 *      Height (cm)  `height`  ↔  Height (inches)  `heightInches`
 *      Width  (cm)  `width`   ↔  Breadth (inches) `breadthInches`
 *      Length (inches) `lengthInches` — STANDALONE. No centimetre partner, no new column.
 *
 * No column is added, renamed or retyped by this and no wire key changes: `height` and `width` keep
 * their names everywhere, and only what a person READS on the label changed. What DID change is the
 * old claim beside those two boxes — that `height` is "the unit-less legacy column" and must not be
 * filled alongside `heightInches`. It is the centimetre box now, the two are one measurement in two
 * units, and filling either fills the other.
 *
 * ── THE ARITHMETIC, AND WHY IT IS SPELLED THIS PEDANTICALLY ─────────────────────────────────────
 *
 * `2.54` is EXACT: the inch has been defined as 25.4 mm since the 1959 international yard-and-pound
 * agreement. Never "2.5 for readability".
 *
 * Parsing goes through ONE grammar rather than through each language's own number reader, because
 * `Double.parseDouble` and JavaScript's `Number()` disagree at the edges: `Number("0x1A")` is 26
 * while `"0x1A".toDouble()` throws, `"1.5f".toDouble()` is 1.5 while `Number("1.5f")` is NaN, and
 * both accept `"Infinity"` and `"NaN"`. A regex both languages implement identically is the only
 * shape in which the four clients cannot come apart.
 *
 * Conversion and rounding are ONE step, producing a SCALED INTEGER — hundredths, because both
 * columns are `Decimal(10,2)`. Rounding to two places and then formatting from a re-derived double
 * would round twice and can move the last hundredth. Rendering is built from that integer and never
 * from `Double.toString()`: Kotlin prints `3.0` where JavaScript prints `3`, and the two disagree
 * again on large magnitudes.
 *
 * `floor(x + 0.5)` and not `kotlin.math.round`, which is half-away-from-zero, nor `Math.round`,
 * which is `floor(x + 0.5)` only by its own contract rather than by this file's. The values here
 * are never negative — the grammar admits no sign — so all three rules agree today; writing the
 * expression out is what keeps them agreeing if a signed value ever reaches this file.
 *
 * ASSOCIATION ORDER IS LOAD-BEARING. `value * CM_PER_INCH * 100.0` must NOT be rewritten as
 * `value * 254.0`, and `value / CM_PER_INCH * 100.0` must NOT be rewritten as
 * `value * (100.0 / CM_PER_INCH)` or as a precomputed `0.3937007874015748`. Each rewrite changes the
 * last ulp and can flip a hundredth — 1.25 inches is 317.5 hundredths of a centimetre EXACTLY under
 * this spelling, and the halfway rule then has something true to round.
 *
 * ── NOT THE SAME FUNCTION AS THE SERVER'S ───────────────────────────────────────────────────────
 *
 * The sibling repository's `backend/app/services/design_workshops._inches_to_cm` is
 * `round(float(v) * 2.54, 2)` — Python's banker's rounding, a DIFFERENT halfway rule from
 * `floor(x + 0.5)`. It lives on the other side of the wire, it is applied to a different column (a
 * design-workshop stage carry, not a record box), and the two must not be "unified": doing so would
 * change every carried figure that lands on a halfway value. This repository has no design
 * workshops and therefore no copy of that function at all; the paragraph is here so that the next
 * reader who meets it in the sibling does not fold one into the other.
 */

/** Centimetres in one inch. Exact by definition since 1959; see the file header. */
const val CM_PER_INCH: Double = 2.54

/**
 * The one grammar both clients parse a dimension box with.
 *
 * Leading and trailing spaces or tabs, then either digits with an optional fractional part or a
 * bare fractional part. NO SIGN, no exponent, no hex, no thousands separator, no trailing type
 * letter. `"1."` and `".5"` ARE admitted, deliberately: they are what a person mid-decimal has
 * typed, and see [propagateDimension] for why that matters.
 */
private val NUMERIC_RE = Regex("""^[ \t]*([0-9]+\.?[0-9]*|\.[0-9]+)[ \t]*$""")

/** 99,999,999.99 in hundredths — the ceiling of the `Decimal(10,2)` columns on both sides. */
private const val DECIMAL_10_2_MAX_SCALED: Long = 9_999_999_999L

/**
 * [text] as a finite, non-negative number, or null when it is not one at all.
 *
 * A TOTAL FUNCTION: every string has an answer and none of them is an exception. The `isFinite`
 * guard is unreachable under the grammar above and is kept anyway, because "unreachable" is a
 * property of the regex and the regex is one edit away from somebody allowing an exponent.
 */
fun parseDimension(text: String): Double? {
    if (!NUMERIC_RE.matches(text)) return null
    val value = text.trim().toDoubleOrNull() ?: return null
    if (!value.isFinite()) return null
    return value
}

/** [text] read as inches, in hundredths of a centimetre — or null when it is not a number. */
private fun cmScaledFromInches(text: String): Long? {
    val value = parseDimension(text) ?: return null
    val n = floor(value * CM_PER_INCH * 100.0 + 0.5)
    if (n > DECIMAL_10_2_MAX_SCALED) return null
    return n.toLong()
}

/** [text] read as centimetres, in hundredths of an inch — or null when it is not a number. */
private fun inchesScaledFromCm(text: String): Long? {
    val value = parseDimension(text) ?: return null
    val n = floor(value / CM_PER_INCH * 100.0 + 0.5)
    if (n > DECIMAL_10_2_MAX_SCALED) return null
    return n.toLong()
}

/**
 * A scaled hundredth count as the shortest decimal string that means it.
 *
 * 254 gives "254", 2540 gives "25.4", 318 gives "3.18", 4 gives "0.04". Built from the integer and
 * never from a double — see the file header.
 */
private fun renderScaled(n: Long): String {
    val whole = n / 100
    val frac = n % 100
    return when {
        frac == 0L -> whole.toString()
        frac % 10L == 0L -> "$whole.${frac / 10}"
        else -> "$whole." + frac.toString().padStart(2, '0')
    }
}

/** An inches box's text as the centimetre partner's text, or null to write nothing. */
fun cmTextFromInches(text: String): String? = cmScaledFromInches(text)?.let(::renderScaled)

/** A centimetre box's text as the inches partner's text, or null to write nothing. */
fun inchesTextFromCm(text: String): String? = inchesScaledFromCm(text)?.let(::renderScaled)

/**
 * Write the partner of the box a person is TYPING IN — the whole of the propagation rule, in one
 * place, so the four boxes on the tool form cannot each implement a slightly different one.
 *
 * ONE-DIRECTIONAL, ALWAYS. Only the box being edited writes its partner; the partner never writes
 * back. That is achieved by calling this from inside the source box's own `onValueChange` and by
 * NOTHING ELSE — a `LaunchedEffect(height, heightInches)` watcher fires for BOTH values and is how
 * 1 cm becomes 0.39 in becomes 0.99 cm. Compose state assignment does not re-invoke the target
 * control's change handler, so the natural shape is already the correct one.
 *
 * AND IT COVERS DICTATION FOR FREE, WHICH IS NOT OBVIOUS. `ui/RecordProseField.kt` writes a box
 * from two places — the keyboard's `onValueChange` on the `OutlinedTextField`, and the recogniser's
 * `onCommit` — but BOTH of them call the CALLER's own `onValueChange` lambda, so a rule written
 * there runs for a spoken value exactly as for a typed one. A rule written inside the
 * `OutlinedTextField` instead would miss every dictated value, silently. It is the same reason
 * `markers.forget(...)` already sits in those lambdas rather than in the control.
 *
 * NEVER ON LOAD. Nothing here is reachable from form construction: each box is seeded from its own
 * column and from nothing else. Historic rows genuinely hold unrelated numbers in `height` and
 * `heightInches`, and a load-time conversion would destroy one of them on the next save.
 *
 * CLEARING PROPAGATES, and it is the ONE case where a non-numeric value writes the partner: an
 * empty box means "no value", and leaving a stale converted number beside it is a lie. It is spelled
 * as its own branch BEFORE the parse for exactly that reason.
 *
 * A PARTIALLY TYPED DECIMAL IS A NUMBER, NOT A MISTAKE: "1." parses and converts, so the partner
 * tracks the keystrokes instead of blanking and refilling. Only a string that cannot be a number at
 * all leaves the partner alone — and only an EMPTY box clears it, because empty is the one input
 * that means "no value". So 1 → 1. → 1.5 gives 2.54 → 2.54 → 3.81, and 1.2 → 1.2. holds 3.05 for
 * one keystroke and corrects on the next, which is strictly better than a partner that blanks in
 * the middle of a number the researcher has not finished typing.
 */
fun propagateDimension(text: String, convert: (String) -> String?, setPartner: (String) -> Unit) {
    if (text.trim().isEmpty()) {
        setPartner("")
        return
    }
    val next = convert(text) ?: return
    setPartner(next)
}
