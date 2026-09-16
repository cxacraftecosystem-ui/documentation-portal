package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * THE CENTIMETRE ↔ INCH TABLE, DRIVEN ROW BY ROW — the only mechanism that can catch a Kotlin/TS
 * divergence in `ui/DimensionUnits.kt`.
 *
 * ── WHY A TABLE AND NOT A PROPERTY ───────────────────────────────────────────────────────────────
 *
 * The rule under test is not "convert inches to centimetres" — a round-trip property would pass on a
 * dozen wrong implementations. It is "produce THIS string for THIS input, on four clients, for ever",
 * and the interesting rows are the ones where a halfway value lands exactly on a hundredth: 1.25 in
 * is 317.5 hundredths of a centimetre EXACTLY in binary64, so the rounding rule is visible in the
 * answer. `Math.round` on the JVM, `kotlin.math.round`, banker's rounding and `floor(x + 0.5)` do not
 * all agree there, and the two that disagree do so in the last hundredth of a stored `Decimal(10,2)`.
 *
 * [TABLE] IS COPIED VERBATIM FROM THE CROSS-SURFACE SPECIFICATION and the browser's twin,
 * `frontend/e2e/dimension-units-unit.spec.ts`, drives the identical rows. Two tests, one table,
 * copied rather than shared — that IS the point, because the two languages are what is being
 * compared. Every row here was computed in V8 before it was written down.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ────────────────────────────────────────────────────────────────
 *
 * Nothing about the FORM. Which box writes which partner, whether a marker survives, and what the
 * labels say are all `MainActivity.kt`'s, and none of them can be reached from a JVM unit test — the
 * form is a composable inside a 14,000-line file. That is exactly why the arithmetic was lifted into
 * `ui/DimensionUnits.kt` in the first place, the same argument `ui/RecordPickers.kt`'s header makes
 * about `craftChangeClearsArtisan`.
 */
class DimensionUnitsTest {

    /** `input` to (`input` read as INCHES in centimetres, `input` read as CENTIMETRES in inches). */
    private val TABLE = listOf(
        "1" to ("2.54" to "0.39"),
        // Same as "1": a trailing zero is dropped because the answer is rendered from the scaled
        // integer and never from the input's own spelling.
        "1.0" to ("2.54" to "0.39"),
        // The leading-dot form is admitted by the grammar, and both languages read it as 0.5.
        ".5" to ("1.27" to "0.2"),
        "0.5" to ("1.27" to "0.2"),
        "2" to ("5.08" to "0.79"),
        "3" to ("7.62" to "1.18"),
        // One decimal, not two: 2540 hundredths renders "25.4" and not "25.40".
        "10" to ("25.4" to "3.94"),
        // Integral, so no point at all: "254" and not "254.0".
        "100" to ("254" to "39.37"),
        "2.54" to ("6.45" to "1"),
        "12.34" to ("31.34" to "4.86"),
        "0.1" to ("0.25" to "0.04"),
        // ── THE HALFWAY ROWS. Each left-hand answer is an EXACT .5 before rounding, so these are
        // the rows that would move under a different rounding rule or a re-associated expression.
        "1.25" to ("3.18" to "0.49"),      // (1.25 * 2.54) * 100 == 317.5 exactly
        "0.125" to ("0.32" to "0.05"),     // == 31.75 exactly
        "1.875" to ("4.76" to "0.74"),     // == 476.25 exactly
        "6.25" to ("15.88" to "2.46"),     // == 1587.5 exactly
        // NOT a halfway value, and that is the point of including it: 0.05 * 2.54 * 100 is
        // 12.699999999999999 in binary64, so it rounds UP to 13 through the arithmetic rather than
        // through anybody's intention.
        "0.05" to ("0.13" to "0.02"),
        "1.005" to ("2.55" to "0.4"),
    )

    /** Strings that are not a number at all. Every one of them must write NOTHING to the partner. */
    private val REFUSED = listOf(
        "", " ", "\t", ".", "-", "-1", "+1", "1.2.3", "abc", "1e3", "1E3", "0x1A", "1.5f",
        "Infinity", "NaN", "1,5", "1 000", "١٢", "5%",
    )

    @Test
    fun `every row of the shared table converts to exactly the shared answer`() {
        for ((input, expected) in TABLE) {
            val (cm, inches) = expected
            assertEquals("$input inches in centimetres", cm, cmTextFromInches(input))
            assertEquals("$input centimetres in inches", inches, inchesTextFromCm(input))
        }
    }

    @Test
    fun `surrounding spaces and tabs are part of the grammar and change no answer`() {
        assertEquals("2.54", cmTextFromInches(" 1 "))
        assertEquals("2.54", cmTextFromInches("\t1\t"))
        assertEquals("0.39", inchesTextFromCm("  1"))
    }

    @Test
    fun `zero is a value and not an absence`() {
        assertEquals("0", cmTextFromInches("0"))
        assertEquals("0", inchesTextFromCm("0"))
        assertEquals("0", cmTextFromInches("0.000"))
    }

    /**
     * A PARTIALLY TYPED DECIMAL IS A NUMBER, NOT A MISTAKE — the behaviour chosen for rule 4, pinned.
     *
     * Typing 1 → 1. → 1.5 must give a partner of 2.54 → 2.54 → 3.81 and must never blank in between:
     * a blank partner means "no value", and somebody halfway through a decimal has not said that.
     */
    @Test
    fun `a half typed decimal converts rather than blanking`() {
        assertEquals("2.54", cmTextFromInches("1"))
        assertEquals("2.54", cmTextFromInches("1."))
        assertEquals("3.81", cmTextFromInches("1.5"))
        assertEquals("1.27", cmTextFromInches(".5"))
    }

    @Test
    fun `nothing that is not a number produces an answer`() {
        for (text in REFUSED) {
            assertNull("refused: '$text' as inches", cmTextFromInches(text))
            assertNull("refused: '$text' as centimetres", inchesTextFromCm(text))
            assertNull("refused: '$text' parses to nothing", parseDimension(text))
        }
    }

    /**
     * A value the `Decimal(10,2)` column could not hold writes nothing rather than a truncated lie.
     *
     * The ceiling is 99,999,999.99, and it is the CONVERTED value that has to fit: 50,000,000 inches
     * is 127,000,000 cm and does not, while the same figure read as centimetres is 19,685,039.37
     * inches and does. The boundary is walked one unit at a time, because a `>=` written where a `>`
     * belongs would pass every other row in this file.
     */
    @Test
    fun `a value past the column ceiling writes nothing`() {
        assertNull(cmTextFromInches("50000000"))
        assertEquals("19685039.37", inchesTextFromCm("50000000"))
        assertEquals("99999998.12", cmTextFromInches("39370078"))
        assertNull(cmTextFromInches("39370079"))
        assertEquals("39370078.74", inchesTextFromCm("99999999.99"))
    }

    // -----------------------------------------------------------------------
    // propagateDimension — the three rules that decide when a partner is written
    // -----------------------------------------------------------------------

    private class Partner {
        var value: String? = null
        val write: (String) -> Unit = { value = it }
    }

    @Test
    fun `clearing a box clears its partner, and it is the only non-number that writes`() {
        val partner = Partner()
        propagateDimension("", ::inchesTextFromCm, partner.write)
        assertEquals("", partner.value)

        // Whitespace-only is an empty box with a stray space in it, not a value.
        val spaces = Partner()
        propagateDimension("   ", ::inchesTextFromCm, spaces.write)
        assertEquals("", spaces.value)
    }

    @Test
    fun `a string that cannot be a number leaves the partner exactly as it was`() {
        for (text in REFUSED.filter { it.isNotBlank() }) {
            val partner = Partner()
            propagateDimension(text, ::cmTextFromInches, partner.write)
            assertNull("'$text' must not have written a partner", partner.value)
        }
    }

    @Test
    fun `a number writes the converted partner`() {
        val partner = Partner()
        propagateDimension("1.25", ::cmTextFromInches, partner.write)
        assertEquals("3.18", partner.value)
    }

    /**
     * THE ROUND-TRIP THIS WHOLE DESIGN EXISTS TO MAKE IMPOSSIBLE, asserted as arithmetic.
     *
     * 1 cm is 0.39 in, and 0.39 in is 0.99 cm. The numbers really do drift, which is why the form
     * writes in ONE direction per keystroke and the partner never re-derives its source. This test
     * cannot see the form; what it pins is that the drift is real, so that anybody who "simplifies"
     * the propagation into a watcher effect has this row to read.
     */
    @Test
    fun `a round trip genuinely loses the value, which is why propagation is one directional`() {
        assertEquals("0.39", inchesTextFromCm("1"))
        assertEquals("0.99", cmTextFromInches("0.39"))
    }
}
