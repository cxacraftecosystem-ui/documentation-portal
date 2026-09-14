package com.fieldrepository.app.ui.trace

import com.offlinetracer.pipeline.Subjects
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control table: that it adds up, that every key it names is a key the engine has, and that the
 * two halves of the disclosure are exhaustive and disjoint.
 *
 * **THE WAY A LATER TIDY-UP LOSES A CONTROL IS NOT BY DELETING IT.** It is by leaving a gap between two
 * lists somebody maintains by hand. Most of this file exists to make that gap a red build.
 */
class TraceParamsTest {

    /* ── The table adds up ──────────────────────────────────────────────────────────────────── */

    @Test
    fun theFourKindsAccountForEveryControl() {
        assertEquals(
            TRACE_SLIDERS.size + TRACE_TOGGLES.size + TRACE_CHOICES.size + TRACE_NUMBER_CHOICES.size,
            TRACE_CONTROLS.size,
        )
        assertEquals(TRACE_CONTROLS.size, TRACE_PARAM_COUNT)
    }

    /**
     * The shape of the table, stated once. This is the number the panel prints, so if it changes
     * somebody has either added a control or quietly lost one, and both want a person to look.
     */
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

    /**
     * **EVERY KEY IS A LEAF THE VENDORED ENGINE ACTUALLY HAS.**
     *
     * This is the check that makes the whole table a transcription rather than a guess: the keys are
     * derived from the engine's own default tree, so a control naming `preprocess.longEdge` instead of
     * `preprocess.workingLongEdge` fails here rather than drawing a row that silently does nothing.
     */
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

    /** Same for the cut list: a control cut on discipline must be a control that exists. */
    @Test
    fun everyCutControlNamesALeafTheEngineHas() {
        TRACE_CUT.keys.forEach { key ->
            assertTrue("$key is cut but is not an engine leaf", traceIsLeafKey(key))
            assertTrue("$key is in the cut list AND in the table", TRACE_CONTROLS.none { it.key == key })
        }
    }

    /* ── The disclosure's two halves ────────────────────────────────────────────────────────── */

    /**
     * **NOTHING BECOMES UNREACHABLE, BY CONSTRUCTION.** The rows above the disclosure and the rows
     * inside it are selected from ONE `tier` field by opposite tests, so a control added to the table
     * lands in one of them without anybody choosing.
     */
    @Test
    fun everyControlIsDrawnSomewhere() {
        val primary = TRACE_CONTROLS.count { it.tier == TraceTier.PRIMARY }
        val advanced = TRACE_CONTROLS.count { it.tier == TraceTier.ADVANCED }
        val export = TRACE_CONTROLS.count { it.tier == TraceTier.EXPORT }
        assertEquals(TRACE_PARAM_COUNT, primary + advanced + export)
        assertEquals(TRACE_ADVANCED_COUNT, advanced)
    }

    /** Six lead, and they are the ones that change the KIND of drawing that comes out. */
    @Test
    fun sixControlsLeadAndStrokeWidthIsNotOneOfThem() {
        assertEquals(6, TRACE_PRIMARY_KEYS.size)
        assertTrue("Stroke width is applied after every decision has been made", "output.strokeWidth" !in TRACE_PRIMARY_KEYS)
        assertTrue("preprocess.workingLongEdge" in TRACE_PRIMARY_KEYS)
        assertTrue("edge.sensitivity" in TRACE_PRIMARY_KEYS)
        assertTrue("cleanup.minBlobArea" in TRACE_PRIMARY_KEYS)
        assertTrue("output.vectorMode" in TRACE_PRIMARY_KEYS)
    }

    /** The background is relocated to the export step, not deleted: it must still be in the table. */
    @Test
    fun theBackgroundIsRelocatedRatherThanDropped() {
        val row = TRACE_CONTROLS.firstOrNull { it.key == "output.background" }
        assertNotNull("A relocated control must stay in the table, or nothing can report what a preset did to it", row)
        assertEquals(TraceTier.EXPORT, row!!.tier)
        assertEquals(TRACE_GROUP_EXPORT, row.group)
    }

    /* ── Travel and value ───────────────────────────────────────────────────────────────────── */

    /** Every geometric (LOG) slider needs a positive minimum, or its whole track is a NaN. */
    @Test
    fun everyLogSliderHasAPositiveMinimum() {
        TRACE_SLIDERS.filter { it.scale == TraceScale.LOG }.forEach {
            assertTrue("${it.key} is geometric and its minimum is not positive", it.min > 0.0)
        }
    }

    /** `fractionOf` and `valueAt` are inverses, for all three scales. */
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
                    // One step of tolerance: `valueAt` snaps, so the comparison is between two snapped
                    // values and not between two reals.
                    slider.step,
                )
            }
        }
    }

    /**
     * THE SQUARE SCALE IS WHAT PUTS THE PRESET VALUES WHERE A THUMB CAN REACH THEM. A quarter of the
     * travel must reach about a sixteenth of the range, or `minBlobArea`'s useful values are still
     * crammed into 19 dp.
     */
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

    /**
     * THE GEOMETRIC SCALE IS ABOUT THE HINT, NOT THE PRESETS. "3 is soft graphite" has to be somewhere
     * near the middle of the track, or the whole soft half of the control's own documented range is one
     * fingertip.
     */
    @Test
    fun theGeometricScaleMakesTheSoftEndReachable() {
        val phi = TRACE_SLIDERS.first { it.key == "edge.xdogPhi" }
        assertEquals(TraceScale.LOG, phi.scale)
        val at3 = phi.fractionOf(3.0)
        assertTrue("3 sits at ${at3} of the track; on a linear track it is under 0.01", at3 > 0.3f)
        assertTrue(at3 < 0.6f)
    }

    /**
     * THE SNAP IS NOT COSMETIC. `0.05 * 7` is 0.35000000000000003 in binary floating point, and that
     * value would reach the readout, the tree and the cross-runtime parity record.
     */
    @Test
    fun theSnapRemovesBinaryFloatingPointDust() {
        val sharpen = TRACE_SLIDERS.first { it.key == "preprocess.unsharpAmount" }
        assertEquals(0.35, sharpen.snap(0.35000000000000003), 0.0)
    }

    /** An integral leaf is rounded before it is sent, because the sanitiser truncates toward zero. */
    @Test
    fun anIntegralSliderSendsAWholeNumber() {
        val blob = TRACE_SLIDERS.first { it.key == "cleanup.minBlobArea" }
        assertTrue(blob.integral)
        val patch = blob.patch(23.9999)
        assertEquals(TraceValue.Num(24.0), patch["cleanup.minBlobArea"])
    }

    /* ── The readout ────────────────────────────────────────────────────────────────────────── */

    /**
     * **THE FORMATTER'S LOCALE IS PINNED, NOT THE JVM'S.** `app/build.gradle.kts` runs these tests as
     * en_US precisely so a locale bug can fail one, and its own comment forbids "fixing" such a failure
     * by editing the build file. This asserts the readout at the formatter rather than at the JVM, so
     * it is still true on a handset set to a language with a comma decimal separator.
     */
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

    /* ── What a control cannot do right now ─────────────────────────────────────────────────── */

    /**
     * **THE MEDIAN TRAP.** The median branch reads a fixed radius and never `denoiseStrength`, and
     * median is what the `sketch` subject selects — which is the subject this panel opens on. So on the
     * ordinary configuration the "Noise reduction" slider is inert, and nothing on the portal says so.
     */
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

    /**
     * A MISSING LEAF IS NOT A FALSE ONE. A tree with no `cleanup.skeletonize` is a version skew, and
     * reading it as "thinning is switched off" would put a confident sentence under a control on the
     * strength of a leaf that is not there.
     */
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

    /* ── Reporting a version skew ───────────────────────────────────────────────────────────── */

    @Test
    fun aHealthyTreeIsMissingNothing() {
        assertEquals(emptyList<String>(), traceMissingKeys(traceValuesOfParams(defaults())))
    }

    @Test
    fun anEmptyTreeReportsEveryControlAsMissing() {
        assertEquals(TRACE_PARAM_COUNT, traceMissingKeys(traceValuesOf("{}")).size)
    }

    /**
     * THE TOGGLE'S COUNT IS WHAT THE PRESS PRODUCES, not what the table holds. A button reading "show
     * the other 24 settings" that reveals 18 is the failure this pair of functions exists to prevent.
     */
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

    /* ── Saying what changed ────────────────────────────────────────────────────────────────── */

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

    /**
     * THE TOGGLE'S QUESTION IS NARROWER THAN THE SENTENCE'S. A count on the toggle that included the
     * export tier would be the toggle claiming to reveal a control that lives on another step.
     */
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
            "One setting that is not on screen has moved: Simplify.",
            traceHiddenChangedSentence(listOf("Simplify")),
        )
        assertEquals(
            "2 settings that are not on screen have moved: Simplify, Keep corners.",
            traceHiddenChangedSentence(listOf("Simplify", "Keep corners")),
        )
    }

    /* ── Seeding the subject ────────────────────────────────────────────────────────────────── */

    /**
     * The map is carried across from the design workshop's registry and this product has no such
     * vocabulary yet, so nothing here can check the KEYS. What it can check — and what matters — is
     * that every VALUE names a subject this engine actually carries.
     */
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
