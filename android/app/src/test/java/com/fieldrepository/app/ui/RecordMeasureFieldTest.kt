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

/**
 * WHERE A DETERMINISTIC MEASUREMENT MAY LAND ON A RECORD FORM, and — harder — where it may not.
 *
 * ── WHY THIS TEST AND NOT A SCREENSHOT ────────────────────────────────────────────────────────
 *
 * `PhotoMeasureTest` already proves the arithmetic value for value against the same constructions the
 * web spec uses. This file is the second question, and it is the one `RecordMeasureField` exists to
 * answer: a record form has no field registry, so every fact a registry would have declared — which
 * columns exist, what unit each is in, how many decimals it holds — is ASSERTED by hand in
 * `RecordMeasureField.kt`. An assertion is exactly the kind of thing that is quietly wrong: nothing
 * raises, nothing warns, the panel composes, and a figure in inches lands in a column stored in
 * something else. So the assertions are pinned.
 *
 * ── AND WHY THE REFUSALS ARE TESTED AS HARD AS THE OFFERS ─────────────────────────────────────
 *
 * A measurement proposed into a weight in grams is not a smaller version of the right answer: it is a
 * plausible number in a field nobody can re-check, printed on the record sheet. The whole eligibility
 * question goes through `PhotoMeasure.LENGTH_UNITS`, the same map the conversion itself goes through,
 * and `a unit the geometry cannot convert is refused` is what proves nobody quietly built the
 * destination list by hand instead.
 *
 * Nothing here needs a renderer, which is deliberate: this module's unit-test classpath carries JUnit
 * and nothing else, so a rule that lived inside a composable could not be checked at all.
 */
class RecordMeasureFieldTest {

    /* ── The columns exist, and are the ones the form actually sends ────────────────────────── */

    /**
     * Every column named in [PRODUCT_MEASURE_DIMENSIONS] is a real property of the body the product
     * form posts. A dimension naming a column the request has no key for would propose a number into a
     * box whose value is then dropped on the floor at save — a "measurement" the researcher watched
     * happen and that never reached the database.
     *
     * Java reflection rather than `kotlin-reflect`, which is not on this module's test classpath.
     */
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

    /**
     * THE ONE HEIGHT COLUMN THAT IS NEVER A DESTINATION.
     *
     * `ToolCreateRequest` carries two heights. `heightInches` states its unit in its own name and is a
     * destination; the bare `height` beside it declares no unit anywhere, and proposing a measured
     * number into it would throw away the single fact the measurement exists to establish. It is also
     * a column the server refuses a method marker for BY NAME — a 422 on the whole save, which the
     * outbox will not queue.
     */
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

    /* ── The unit is one the geometry can actually convert ──────────────────────────────────── */

    /**
     * ONE MAP DECIDES. `PhotoMeasure.LENGTH_UNITS` is what the measurement is converted through and
     * what [measurableDimensions] tests membership against; a unit spelled any other way here would
     * produce a destination the panel then cannot convert into, which it reports on screen as
     * "…which this cannot convert to" — a control that offers a box and then refuses it.
     */
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

    /**
     * The safety property this file exists to enforce rather than assume: a unit the geometry cannot
     * convert can never become a destination. Grams is the case that matters — a photograph cannot
     * weigh anything.
     */
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

    /* ── What the column can hold, and what is said when it cannot hold what was measured ────── */

    /**
     * THE TRAP THIS ADAPTER EXISTS TO AVOID, PINNED.
     *
     * `MainActivity.numToText(12.0)` is "12". The panel rounds its answer to the precision its own
     * error bar reaches and says on screen that "the number of digits is the only thing left saying
     * how well it was measured", so 12.0 in ± 0.4 in and 12 in are two different claims — one about a
     * tenth of an inch and one about an inch. The proposal must reach the form's box with its digit
     * intact.
     */
    @Test
    fun `a whole-number reading keeps the digit its error bar earned`() {
        assertEquals("12.0", recordProposalText("12.0"))
        assertNull(recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), "12.0"))
    }

    /**
     * THE OTHER HALF OF THE SAME RULE, and the one the column imposes rather than the error bar.
     *
     * `roundToUncertainty` caps at four decimals and a zoomed mark routinely earns three, but every
     * dimension column holds two. An unclamped 4.213 is accepted by the API and stored as 4.21 with
     * nobody told, while the web port of this adapter proposes 4.21 — the same photograph and the same
     * marks giving two clients two numbers.
     */
    @Test
    fun `a reading finer than the column is rounded to what the column holds`() {
        assertEquals("4.21", recordProposalText("4.213"))
        assertEquals(
            "“Length (inches)” holds 2 decimal places, so the measured 4.213 in went in as 4.21.",
            recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), "4.213"),
        )
    }

    /** Nothing was given back, so nothing is said. A note under every proposal trains a reader past it. */
    @Test
    fun `a reading the column can hold is passed through and says nothing`() {
        assertEquals("4.2", recordProposalText("4.2"))
        assertNull(recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), "4.2"))
    }

    /**
     * A stored `0.00` in a dimension column does not read as "under five thousandths of an inch"; it
     * reads as a measurement of nothing, and it is printed that way on the record sheet. A tool's
     * needle thickness is the real case. Refused — and SAID, because a button that silently did
     * nothing is the worse of the two failures.
     */
    @Test
    fun `a reading that rounds to zero is refused out loud rather than stored as nothing`() {
        assertEquals("", recordProposalText("0.002"))
        val note = recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), "0.002")
        assertNotNull(note)
        assertTrue("it has to name the box:\n$note", note!!.contains("Length (inches)"))
        assertTrue("and say that nothing was written:\n$note", note.contains("nothing was put in it"))
    }

    /**
     * DECIMAL TEXT, NOT BINARY FLOATING POINT. The figure on the button is the promise, so the clamp
     * has to round the number that was PRINTED rather than its binary approximation.
     */
    @Test
    fun `the clamp rounds the decimal text and not its binary approximation`() {
        assertEquals("1.01", recordProposalText("1.005"))
        // The scale-by-100 route, shown rather than argued about: this is the answer the clamp must NOT
        // give, and it is why `BigDecimal` is not a stylistic choice here.
        assertEquals(100.49999999999999, 1.005 * 100, 0.0)
    }

    /** Anything that is not a number is passed through untouched — the honest answer for a value nobody clamped. */
    @Test
    fun `a non-numeric figure is neither clamped nor annotated`() {
        assertEquals("", recordProposalText(""))
        assertNull(recordProposalNote(PRODUCT_MEASURE_DIMENSIONS.first(), ""))
    }

    /* ── The whole chain, end to end, with no composition in it ─────────────────────────────── */

    /**
     * THE ONE PATH THAT ACTUALLY REACHES A FORM BOX: marks → geometry → unit conversion → the error
     * bar's own rounding → the column's clamp.
     *
     * A 100 mm reference marked 1000 px long against a 2000 px target is exactly 200 mm, which is
     * 7.874015748031496 inches. The marks are placed at a `markSigmaPx` of 0.375 — one screen pixel and
     * a half at four screen pixels to the image pixel, which is what zooming in buys — so the error bar
     * is ±0.0047 in and earns THREE decimals, and the column holds two. Every step is the one the panel
     * takes, in the order it takes them.
     *
     * THE SIGMA IS WHAT MAKES THIS CASE EXIST AT ALL. At the 1:1 default the same marks earn only one
     * decimal (±0.12 in, proposed as "7.9") and the column never has to clamp anything — so a test
     * written at the default would assert nothing about the clamp while looking as if it did.
     */
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
        // The technique reaches the marker untouched — the two words the server's own set holds.
        assertEquals(PhotoMeasure.METHOD_SCALE, measurement.method)
    }

    /**
     * The same marks at the 1:1 default, stated rather than left implied by the case above: the error
     * bar earns one decimal, the column can hold it, and NOTHING IS SAID. A note under a proposal that
     * lost no digit is the noise that trains a reader past the one proposal where it matters.
     */
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

    /**
     * THE LOCALE TRAP THIS REPOSITORY HAS ALREADY BEEN BITTEN BY ONCE, in the other direction.
     *
     * `formatRounded` pins `Locale.ROOT`. Were it to read the handset's locale, a researcher whose
     * phone is set to a comma-decimal locale would get "7,87" written into a box the form parses with
     * `toDoubleOrNull()` — which answers null, so the measurement would vanish at save with no message
     * at all. The unit-test JVM is pinned to en_US, so this cannot be caught by running the suite; the
     * default locale has to be moved under the formatter's feet, which is what this does. It is put
     * back in a `finally`, because a leaked default locale would silently change every other test in
     * this JVM.
     */
    @Test
    fun `the proposal text is not spelled by the handset's locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // decimal comma
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

    /* ── What the researcher is told before the button is pressed ───────────────────────────── */

    /**
     * The "…is in this field now. This replaces it." warning is the only thing standing between a
     * proposal and the silent overwrite of a number somebody measured with callipers ten minutes ago.
     * Nothing there is the ABSENCE of a warning rather than a quieter one, so it answers null and the
     * caller cannot render an empty box.
     */
    @Test
    fun `the replace warning names what is about to be lost, and says nothing when there is nothing`() {
        assertEquals("“14” is in this field now. This replaces it.", panelReplaceWarning("14"))
        assertEquals("whitespace is trimmed out of the quotation", "“14” is in this field now. This replaces it.", panelReplaceWarning(" 14 "))
        assertNull(panelReplaceWarning(""))
        assertNull(panelReplaceWarning("   "))
        assertNull(panelReplaceWarning(null))
    }

    /**
     * A card nobody has touched must read as the invitation it was, not as a set-up somebody made.
     */
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

    /* ── Telling the photographs apart ──────────────────────────────────────────────────────── */

    @Test
    fun `the chooser counts the photographs, and does not count a lone one against itself`() {
        assertEquals("Photo 1 of 3", recordPhotoLabel(0, 3))
        assertEquals("Photo 3 of 3", recordPhotoLabel(2, 3))
        // One photograph gets no "of 1": the chooser is not even drawn, and the count would be noise.
        assertEquals("Photo 1", recordPhotoLabel(0, 1))
    }
}
