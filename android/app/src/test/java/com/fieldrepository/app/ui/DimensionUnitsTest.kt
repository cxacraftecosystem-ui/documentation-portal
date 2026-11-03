package com.fieldrepository.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DimensionUnitsTest {

    private val TABLE = listOf(
        "1" to ("2.54" to "0.39"),
        "1.0" to ("2.54" to "0.39"),
        ".5" to ("1.27" to "0.2"),
        "0.5" to ("1.27" to "0.2"),
        "2" to ("5.08" to "0.79"),
        "3" to ("7.62" to "1.18"),
        "10" to ("25.4" to "3.94"),
        "100" to ("254" to "39.37"),
        "2.54" to ("6.45" to "1"),
        "12.34" to ("31.34" to "4.86"),
        "0.1" to ("0.25" to "0.04"),
        "1.25" to ("3.18" to "0.49"),
        "0.125" to ("0.32" to "0.05"),
        "1.875" to ("4.76" to "0.74"),
        "6.25" to ("15.88" to "2.46"),
        "0.05" to ("0.13" to "0.02"),
        "1.005" to ("2.55" to "0.4"),
    )

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

    @Test
    fun `a value past the column ceiling writes nothing`() {
        assertNull(cmTextFromInches("50000000"))
        assertEquals("19685039.37", inchesTextFromCm("50000000"))
        assertEquals("99999998.12", cmTextFromInches("39370078"))
        assertNull(cmTextFromInches("39370079"))
        assertEquals("39370078.74", inchesTextFromCm("99999999.99"))
    }

    private class Partner {
        var value: String? = null
        val write: (String) -> Unit = { value = it }
    }

    @Test
    fun `clearing a box clears its partner, and it is the only non-number that writes`() {
        val partner = Partner()
        propagateDimension("", ::inchesTextFromCm, partner.write)
        assertEquals("", partner.value)

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

    @Test
    fun `a round trip genuinely loses the value, which is why propagation is one directional`() {
        assertEquals("0.39", inchesTextFromCm("1"))
        assertEquals("0.99", cmTextFromInches("0.39"))
    }
}
