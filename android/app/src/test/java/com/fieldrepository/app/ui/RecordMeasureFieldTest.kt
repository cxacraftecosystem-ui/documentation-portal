package com.fieldrepository.app.ui

import com.fieldrepository.app.data.MeasurePoint
import com.fieldrepository.app.data.MeasureResult
import com.fieldrepository.app.data.MeasureSegment
import com.fieldrepository.app.data.PhotoMeasure
import com.fieldrepository.app.data.ProductCreateRequest
import com.fieldrepository.app.data.ScaleReference
import com.fieldrepository.app.data.ToolCreateRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import org.junit.Assert.assertFalse

class RecordMeasureFieldTest {

    @Test
    fun `every product dimension names a column the product request carries`() {
        val declared = ProductCreateRequest::class.java.declaredFields.map { it.name }.toSet()
        PRODUCT_MEASURE_DIMENSIONS.forEach { dimension ->
            assertTrue(
                "ProductCreateRequest has no `${dimension.column}` — the proposal would be dropped at save",
                dimension.column in declared,
            )
        }
    }

    @Test
    fun `every tool dimension names a column the tool request carries`() {
        val declared = ToolCreateRequest::class.java.declaredFields.map { it.name }.toSet()
        TOOL_MEASURE_DIMENSIONS.forEach { dimension ->
            assertTrue(
                "ToolCreateRequest has no `${dimension.column}` — the proposal would be dropped at save",
                dimension.column in declared,
            )
        }
    }

    @Test
    fun `the tool's unitless height column is never a measurement destination`() {
        assertTrue(
            "the tool form's unitless `height` must never be a measurement destination",
            TOOL_MEASURE_DIMENSIONS.none { it.column == "height" },
        )
        assertTrue(
            "the tool's unit-bearing `heightInches` must be one",
            TOOL_MEASURE_DIMENSIONS.any { it.column == "heightInches" },
        )
    }

    @Test
    fun `the destinations are the three inch columns, in form order, each naming its own box`() {
        assertEquals(
            listOf("lengthInches", "breadthInches", "heightInches"),
            measurableDimensions(PRODUCT_MEASURE_DIMENSIONS).map { it.column },
        )
        assertEquals(
            listOf("lengthInches", "breadthInches", "heightInches"),
            measurableDimensions(TOOL_MEASURE_DIMENSIONS).map { it.column },
        )
        assertEquals(
            "the button has to name the box the researcher is looking at",
            listOf("Length (inches)", "Breadth (inches)", "Height (inches)"),
            PRODUCT_MEASURE_DIMENSIONS.map { it.label },
        )
    }

    @Test
    fun `every declared unit is one the geometry knows`() {
        (PRODUCT_MEASURE_DIMENSIONS + TOOL_MEASURE_DIMENSIONS).forEach { dimension ->
            assertTrue(
                "`${dimension.unit}` is not a unit PhotoMeasure can convert",
                dimension.unit in RECORD_UNITS,
            )
        }
        assertTrue("inches must stay convertible", "in" in PhotoMeasure.LENGTH_UNITS)
    }

    @Test
    fun `a unit the geometry cannot convert is refused as a destination`() {
        val refused = measurableDimensions(
            listOf(
                RecordDimension("weight", "Weight", unit = "g"),
                RecordDimension("replacementCost", "Replacement cost", unit = "INR"),
                RecordDimension("yearsInUse", "Years in use", unit = "years"),
            )
        )
        assertTrue(
            "no non-length column may be a measurement destination, and these got through: " +
                refused.joinToString { "${it.column} (${it.unit})" },
            refused.isEmpty(),
        )
    }

    @Test
    fun `a whole-number reading keeps the digit its error bar earned`() {
        assertEquals("12.0", recordProposalText("12.0"))
        assertNull(recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), "12.0"))
    }

    @Test
    fun `a reading finer than the column is rounded to what the column holds`() {
        assertEquals("4.21", recordProposalText("4.213"))
        assertEquals(
            "“Length (inches)” rounded 4.213 in to 4.21.",
            recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), "4.213"),
        )
    }

    @Test
    fun `a reading the column can hold is passed through and says nothing`() {
        assertEquals("4.2", recordProposalText("4.2"))
        assertNull(recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), "4.2"))
    }

    @Test
    fun `a reading that rounds to zero is refused out loud rather than stored as nothing`() {
        assertEquals("", recordProposalText("0.002"))
        val note = recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), "0.002")
        assertNotNull(note)
        assertTrue("it has to name the box:\n$note", note!!.contains("Length (inches)"))
        assertTrue("and say that nothing was written:\n$note", note.contains("left empty"))
    }

    @Test
    fun `the clamp rounds the decimal text and not its binary approximation`() {
        assertEquals("1.01", recordProposalText("1.005"))
        assertEquals(100.49999999999999, 1.005 * 100, 0.0)
    }

    @Test
    fun `a non-numeric figure is neither clamped nor annotated`() {
        assertEquals("", recordProposalText(""))
        assertNull(recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), ""))
    }

    @Test
    fun `a measurement in millimetres reaches an inches box as the column can hold it`() {
        val measurement = PhotoMeasure.measureBySameScale(
            reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(1000.0, 0.0), 100.0, "mm"),
            target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(2000.0, 50.0)),
            markSigmaPx = PhotoMeasure.markSigmaForDisplayScale(4.0),
        ) as MeasureResult.Measurement

        assertEquals(200.0, measurement.value, 0.0)
        val converted = PhotoMeasure.convertLength(measurement.value, measurement.unit, "in")!!
        val doubt = PhotoMeasure.convertLength(measurement.uncertainty, measurement.unit, "in")!!
        val offered = formatRounded(PhotoMeasure.roundToUncertainty(converted, doubt))

        assertEquals("7.874", offered)
        assertEquals("7.87", recordProposalText(offered))
        assertNotNull(
            "and the researcher is told the third digit went",
            recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), offered),
        )
        assertEquals(PhotoMeasure.METHOD_SCALE, measurement.method)
    }

    @Test
    fun `the same marks placed without zooming propose a coarser figure and no note`() {
        val measurement = PhotoMeasure.measureBySameScale(
            reference = ScaleReference(MeasurePoint(0.0, 0.0), MeasurePoint(200.0, 0.0), 100.0, "mm"),
            target = MeasureSegment(MeasurePoint(0.0, 50.0), MeasurePoint(400.0, 50.0)),
        ) as MeasureResult.Measurement

        val converted = PhotoMeasure.convertLength(measurement.value, measurement.unit, "in")!!
        val doubt = PhotoMeasure.convertLength(measurement.uncertainty, measurement.unit, "in")!!
        val offered = formatRounded(PhotoMeasure.roundToUncertainty(converted, doubt))

        assertEquals("7.9", offered)
        assertEquals("7.9", recordProposalText(offered))
        assertNull(recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), offered))
    }

    @Test
    fun `the proposal text is not spelled by the handset's locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("7.874", formatRounded(PhotoMeasure.roundToUncertainty(7.874015, 0.005)))
            assertEquals(
                "and it still parses back as a number, which is what the form does with it",
                7.874,
                formatRounded(PhotoMeasure.roundToUncertainty(7.874015, 0.005)).toDouble(),
                0.0,
            )
            assertEquals("7.87", recordProposalText(formatRounded(PhotoMeasure.roundToUncertainty(7.874015, 0.005))))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `the replace warning names what is about to be lost, and says nothing when there is nothing`() {
        assertEquals("“14” is in this field now. This replaces it.", panelReplaceWarning("14"))
        assertEquals("whitespace is trimmed out of the quotation", "“14” is in this field now. This replaces it.", panelReplaceWarning(" 14 "))
        assertNull(panelReplaceWarning(""))
        assertNull(panelReplaceWarning("   "))
        assertNull(panelReplaceWarning(null))
    }

    @Test
    fun `the collapsed summary describes a configuration and stays quiet about an empty one`() {
        assertNull(
            measureSummary(0, 4, false, "", "in", "", "", "mm"),
        )
        assertEquals(
            "2 of 4 marks placed · 5 in reference · same-plane method",
            measureSummary(2, 4, false, "5", "in", "", "", "mm"),
        )
        assertEquals(
            "no marks placed yet · 210 × 297 mm rectangle · four-corner method",
            measureSummary(0, 6, true, "", "in", "210", "297", "mm"),
        )
        assertEquals(
            "a half-typed rectangle is not a rectangle",
            "4 of 6 marks placed · no rectangle size yet · four-corner method",
            measureSummary(4, 6, true, "", "in", "210", "", "mm"),
        )
    }

    @Test
    fun `the chooser counts the photographs, and does not count a lone one against itself`() {
        assertEquals("Photo 1 of 3", recordPhotoLabel(0, 3))
        assertEquals("Photo 3 of 3", recordPhotoLabel(2, 3))
        assertEquals("Photo 1", recordPhotoLabel(0, 1))
    }
}

class RecordMeasureFieldVisibilityTest {

    private fun source(): String {
        val path = java.io.File("src/main/java/com/fieldrepository/app/ui/RecordMeasureField.kt")
        assertTrue("cannot find RecordMeasureField.kt from ${java.io.File(".").absolutePath}", path.exists())
        return path.readText()
    }

    private fun body(): String {
        val s = source()
        val start = s.indexOf("internal fun RecordMeasureField(")
        assertTrue("RecordMeasureField declaration not found", start >= 0)
        val end = s.indexOf("\n@Composable", start + 1).let { if (it < 0) s.length else it }
        return s.substring(start, end)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines()
            .joinToString(" ") { line -> line.substringBefore("//") }
    }

    @Test
    fun `the card does not hide itself when the record carries no photograph`() {
        assertFalse(
            "RecordMeasureField returns early on an empty photo batch again. That does not just hide " +
                "the card — it leaves GridMeasurementSection, which has its own camera button and " +
                "uploads to a vision model, as the only measuring route the researcher can see. The " +
                "product then defaults to the model by concealing the offline alternative.",
            Regex("""if\s*\(\s*photos\.isEmpty\(\)\s*\)\s*return""").containsMatchIn(body()),
        )
    }

    @Test
    fun `the card can obtain a photograph without one being attached to the record first`() {
        val text = body()
        assertTrue(
            "RecordMeasureField no longer has a picker of its own. Without one it is only usable on a " +
                "record that already carries a photograph, which is the state that made the model the " +
                "default. The web equivalent (RecordPhotoMeasure) carries its own capture input for " +
                "the same reason.",
            text.contains("rememberLauncherForActivityResult") && text.contains("GetContent()"),
        )
    }

    @Test
    fun `a record type with no measurable column is still allowed to draw nothing`() {
        assertTrue(
            "the targets guard is gone; the card would now draw on record types with no dimension to fill",
            Regex("""if\s*\(\s*targets\.isEmpty\(\)\s*\)\s*return""").containsMatchIn(body()),
        )
    }
}
