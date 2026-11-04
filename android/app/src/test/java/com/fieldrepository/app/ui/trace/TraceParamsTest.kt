package com.fieldrepository.app.ui.trace

import com.offlinetracer.pipeline.Subjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceParamsTest {

    @Test
    fun theFourKindsAccountForEveryControl() {
        assertEquals(
            TRACE_SLIDERS.size + TRACE_TOGGLES.size + TRACE_CHOICES.size + TRACE_NUMBER_CHOICES.size,
            TRACE_CONTROLS.size,
        )
        assertEquals(TRACE_CONTROLS.size, TRACE_PARAM_COUNT)
    }

    @Test
    fun theTableIsSeventeenSlidersNineTogglesFourChoicesAndOneNamedOption() {
        assertEquals(17, TRACE_SLIDERS.size)
        assertEquals(9, TRACE_TOGGLES.size)
        assertEquals(4, TRACE_CHOICES.size)
        assertEquals(1, TRACE_NUMBER_CHOICES.size)
        assertEquals(31, TRACE_PARAM_COUNT)
    }

    @Test
    fun noControlIsListedTwice() {
        val keys = TRACE_CONTROLS.map { it.key }
        assertEquals("A control appears under two rows", keys.size, keys.toSet().size)
    }

    @Test
    fun everyControlNamesALeafTheEngineHas() {
        TRACE_CONTROLS.forEach { control ->
            assertTrue(
                "${control.key} is not a leaf of the engine's parameter tree, so its row would draw " +
                    "nothing and `traceMissingKeys` would report it forever.",
                traceIsLeafKey(control.key),
            )
        }
    }

    @Test
    fun everyCutControlNamesALeafTheEngineHas() {
        TRACE_CUT.keys.forEach { key ->
            assertTrue("$key is cut but is not an engine leaf", traceIsLeafKey(key))
            assertTrue("$key is in the cut list AND in the table", TRACE_CONTROLS.none { it.key == key })
        }
    }

    @Test
    fun everyControlIsDrawnSomewhere() {
        val primary = TRACE_CONTROLS.count { it.tier == TraceTier.PRIMARY }
        val advanced = TRACE_CONTROLS.count { it.tier == TraceTier.ADVANCED }
        val export = TRACE_CONTROLS.count { it.tier == TraceTier.EXPORT }
        assertEquals(TRACE_PARAM_COUNT, primary + advanced + export)
        assertEquals(TRACE_ADVANCED_COUNT, advanced)
    }

    @Test
    fun sixControlsLeadAndStrokeWidthIsNotOneOfThem() {
        assertEquals(6, TRACE_PRIMARY_KEYS.size)
        assertTrue("Stroke width is applied after every decision has been made", "output.strokeWidth" !in TRACE_PRIMARY_KEYS)
        assertTrue("preprocess.workingLongEdge" in TRACE_PRIMARY_KEYS)
        assertTrue("edge.sensitivity" in TRACE_PRIMARY_KEYS)
        assertTrue("cleanup.minBlobArea" in TRACE_PRIMARY_KEYS)
        assertTrue("output.vectorMode" in TRACE_PRIMARY_KEYS)
    }

    @Test
    fun theBackgroundIsRelocatedRatherThanDropped() {
        val row = TRACE_CONTROLS.firstOrNull { it.key == "output.background" }
        assertNotNull("A relocated control must stay in the table, or nothing can report what a preset did to it", row)
        assertEquals(TraceTier.EXPORT, row!!.tier)
        assertEquals(TRACE_GROUP_EXPORT, row.group)
    }

    @Test
    fun everyLogSliderHasAPositiveMinimum() {
        TRACE_SLIDERS.filter { it.scale == TraceScale.LOG }.forEach {
            assertTrue("${it.key} is geometric and its minimum is not positive", it.min > 0.0)
        }
    }

    @Test
    fun travelAndValueAreInverses() {
        TRACE_SLIDERS.forEach { slider ->
            listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { t ->
                val value = slider.valueAt(t.toFloat())
                val back = slider.fractionOf(value)
                val again = slider.valueAt(back)
                assertEquals(
                    "${slider.key} does not round-trip at travel $t",
                    value,
                    again,
                    slider.step,
                )
            }
        }
    }

    @Test
    fun theSquareScalePutsTheSmallValuesInTheFirstQuarter() {
        val blob = TRACE_SLIDERS.first { it.key == "cleanup.minBlobArea" }
        assertEquals(TraceScale.SQUARE, blob.scale)
        assertTrue(
            "A quarter of the track must reach about 64, where 15 of the 26 preset values live",
            blob.valueAt(0.25f) <= 70.0,
        )
        assertEquals("The range itself is unchanged", 1000.0, blob.valueAt(1f), 0.5)
    }

    @Test
    fun theGeometricScaleMakesTheSoftEndReachable() {
        val phi = TRACE_SLIDERS.first { it.key == "edge.xdogPhi" }
        assertEquals(TraceScale.LOG, phi.scale)
        val at3 = phi.fractionOf(3.0)
        assertTrue("3 sits at ${at3} of the track; on a linear track it is under 0.01", at3 > 0.3f)
        assertTrue(at3 < 0.6f)
    }

    @Test
    fun theSnapRemovesBinaryFloatingPointDust() {
        val sharpen = TRACE_SLIDERS.first { it.key == "preprocess.unsharpAmount" }
        assertEquals(0.35, sharpen.snap(0.35000000000000003), 0.0)
    }

    @Test
    fun anIntegralSliderSendsAWholeNumber() {
        val blob = TRACE_SLIDERS.first { it.key == "cleanup.minBlobArea" }
        assertTrue(blob.integral)
        val patch = blob.patch(23.9999)
        assertEquals(TraceValue.Num(24.0), patch["cleanup.minBlobArea"])
    }

    @Test
    fun theReadoutUsesADotWhateverTheDeviceLanguageIs() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("0.35", traceFormatValue(0.35, 0.05))
            assertEquals("2.5", traceFormatValue(2.5, 0.1))
            assertEquals("24", traceFormatValue(23.6, 1.0))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun theDecimalCountFollowsTheStep() {
        assertEquals(0, traceDecimals(1.0))
        assertEquals(1, traceDecimals(0.1))
        assertEquals(2, traceDecimals(0.01))
    }

    @Test
    fun theNoiseSliderSaysSoWhenTheMedianFilterIsChosen() {
        val slider = TRACE_SLIDERS.first { it.key == "preprocess.denoiseStrength" }
        val median = traceValuesOf("""{"preprocess":{"denoise":"MEDIAN"}}""")
        val reason = traceInactiveReason(slider, median)
        assertNotNull("The median case is the whole reason this function exists", reason)
        assertTrue(reason!!.contains("fixed radius"))

        val bilateral = traceValuesOf("""{"preprocess":{"denoise":"BILATERAL"}}""")
        assertNull(traceInactiveReason(slider, bilateral))
    }

    @Test
    fun aMissingFlagDoesNotBecomeAConfidentSentence() {
        val prune = TRACE_SLIDERS.first { it.key == "cleanup.pruneSpurs" }
        assertNull(traceInactiveReason(prune, traceValuesOf("{}")))
        assertNotNull(
            traceInactiveReason(prune, traceValuesOf("""{"cleanup":{"skeletonize":false}}""")),
        )
    }

    @Test
    fun theFlowSettingsSaySoUnderEveryOtherEngine() {
        val sigma = TRACE_SLIDERS.first { it.key == "edge.flow.sigmaM" }
        assertNull(traceInactiveReason(sigma, traceValuesOf("""{"edge":{"engine":"FDOG"}}""")))
        assertNotNull(traceInactiveReason(sigma, traceValuesOf("""{"edge":{"engine":"CANNY"}}""")))
    }

    @Test
    fun aHealthyTreeIsMissingNothing() {
        assertEquals(emptyList<String>(), traceMissingKeys(traceValuesOfParams(defaults())))
    }

    @Test
    fun anEmptyTreeReportsEveryControlAsMissing() {
        assertEquals(TRACE_PARAM_COUNT, traceMissingKeys(traceValuesOf("{}")).size)
    }

    @Test
    fun theDisclosureCountsRowsItCanActuallyDraw() {
        val healthy = traceValuesOfParams(defaults())
        assertEquals(TRACE_ADVANCED_COUNT, traceAdvancedRevealed(healthy))
        assertEquals(0, traceAdvancedRevealed(traceValuesOf("{}")))
        assertEquals(
            "A group with nothing left in it must be dropped, not drawn as a heading over nothing",
            emptyList<Pair<String, List<TraceControl>>>(),
            traceAdvancedGroups(traceValuesOf("{}")),
        )
    }

    @Test
    fun theDisclosureLabelCarriesTheSharedPhrase() {
        val shut = traceDisclosureLabel(open = false, revealed = 24, changedHidden = 0)
        assertTrue(shut.startsWith(TRACE_DISCLOSURE_ACTION))
        assertTrue(shut.contains("24 settings"))
        assertTrue(traceDisclosureLabel(false, 24, 2).contains("2 changed"))
        assertTrue(traceDisclosureLabel(true, 24, 2).startsWith("Hide"))
        assertEquals("1 setting", traceDisclosureLabel(true, 1, 0).removePrefix("Hide the other "))
    }

    @Test
    fun theSpokenStateIsTheSameTwoWordsEveryDisclosureInThisAppUses() {
        assertEquals("Expanded", traceDisclosureState(true))
        assertEquals("Collapsed", traceDisclosureState(false))
    }

    @Test
    fun anUnchangedTreeReportsNothing() {
        val values = traceValuesOfParams(defaults())
        assertEquals(emptyList<String>(), traceChangedLabels(values, values))
        assertNull(traceOverwriteNotice("A style", values, values))
        assertNull(traceHiddenChangedSentence(emptyList()))
    }

    @Test
    fun aChangedLeafIsNamedByItsLabelAndNotByItsKey() {
        val before = traceValuesOfParams(defaults())
        val after = traceValuesOfParams(
            traceApplyLeaves(defaults(), mapOf("output.simplify" to TraceValue.Num(4.0)))
        )
        assertEquals(listOf("Simplify"), traceChangedLabels(before, after))
        val notice = traceOverwriteNotice("The “Woodcut” style", before, after)
        assertEquals("The “Woodcut” style changed one setting: Simplify.", notice)
    }

    @Test
    fun theToggleCountsOnlyWhatThePressReveals() {
        val before = traceValuesOfParams(defaults())
        val after = traceValuesOfParams(
            traceApplyLeaves(defaults(), mapOf("output.background" to TraceValue.Num(TRACE_OPAQUE_WHITE)))
        )
        assertEquals(
            "The background lives on the export step, so the disclosure must not claim it",
            emptyList<String>(),
            traceChangedBehindDisclosure(before, after),
        )
        assertEquals(
            listOf("White background"),
            traceChangedHiddenLabels(before, after, setOf(TraceTier.PRIMARY, TraceTier.ADVANCED)),
        )
    }

    @Test
    fun theHiddenSentenceNamesThemRatherThanCountingThem() {
        assertEquals(
            "A hidden setting changed: Simplify.",
            traceHiddenChangedSentence(listOf("Simplify")),
        )
        assertEquals(
            "2 hidden settings changed: Simplify, Keep corners.",
            traceHiddenChangedSentence(listOf("Simplify", "Keep corners")),
        )
    }

    @Test
    fun everySeededSubjectExistsInTheEngine() {
        val ids = Subjects.ALL.map { it.id }.toSet()
        TRACE_SUBJECT_FOR_CATEGORY.forEach { (category, subject) ->
            assertTrue("$category maps to \"$subject\", which this engine does not carry", subject in ids)
        }
        assertTrue(TRACE_DEFAULT_SUBJECT_ID in ids)
    }

    @Test
    fun anUnknownHintFallsBackToTheDefaultRatherThanToNothing() {
        assertEquals(TRACE_DEFAULT_SUBJECT_ID, traceSubjectFor(null))
        assertEquals(TRACE_DEFAULT_SUBJECT_ID, traceSubjectFor("  "))
        assertEquals(TRACE_DEFAULT_SUBJECT_ID, traceSubjectFor("SOMETHING_NOBODY_DECLARED"))
        assertEquals("textile", traceSubjectFor("SAREE"))
    }

    private fun defaults() = com.offlinetracer.pipeline.TraceParams()
}
