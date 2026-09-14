package com.fieldrepository.app.ui.trace

import com.offlinetracer.pipeline.EdgeEngine
import com.offlinetracer.pipeline.TraceParams
import com.offlinetracer.pipeline.VectorModeParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The translation between the panel's flat map and the engine's nested tree.
 *
 * A conversion nobody can test is exactly the kind that is quietly wrong for a year, which is why
 * `TraceEngineParams.kt` imports no `android.*` and why this file exists.
 */
class TraceEngineParamsTest {

    /* ── The leaves ─────────────────────────────────────────────────────────────────────────── */

    /**
     * `TraceParams` has 74 leaves and 73 of them have a dotted key. The odd one out is
     * `auto.handTuned`, which is a `Set<String>` — and [TraceValue] has four cases, none of which is a
     * list. See `TraceEngineParams.kt`'s header for why inventing a fifth would be worse than skipping
     * it.
     */
    @Test
    fun everyLeafButTheOneArrayHasADottedKey() {
        assertEquals(73, TRACE_LEAF_KEYS.size)
        assertFalse("auto.handTuned is an array and is deliberately not a leaf key", traceIsLeafKey("auto.handTuned"))
        assertEquals(
            listOf("auto.handTuned"),
            traceUnknownLeafKeys(listOf("auto.handTuned", "output.simplify")),
        )
    }

    @Test
    fun theKeysAreDottedPathsAndNotFlattenedNames() {
        assertTrue("edge.flow.sigmaM" in TRACE_LEAF_KEYS)
        assertTrue("preprocess.workingLongEdge" in TRACE_LEAF_KEYS)
        assertTrue(TRACE_STYLE_ID_KEY in TRACE_LEAF_KEYS)
    }

    /* ── The round trip ─────────────────────────────────────────────────────────────────────── */

    /** Flatten then nest is the identity for every dotted key. */
    @Test
    fun aFlattenedTreeNestsBackToItself() {
        val original = TraceParams().sanitized()
        val flat = traceFlattenParams(original)
        val back = traceNestLeaves(flat)
        assertEquals(traceFlattenParams(original), traceFlattenParams(back))
    }

    /** And the tree survives a trip through the wire form, which is what the panel actually holds. */
    @Test
    fun aTreeSurvivesTheWireForm() {
        val original = TraceParams(
            edge = TraceParams().edge.copy(engine = EdgeEngine.CANNY, sensitivity = 0.73f),
            output = TraceParams().output.copy(vectorMode = VectorModeParam.OUTLINE),
            styleId = "woodcut",
        ).sanitized()
        val back = traceParamsOf(traceValuesOfParams(original))
        assertEquals(original, back)
    }

    /**
     * **THE ONE LEAF A PATCH CANNOT NAME MUST SURVIVE A PATCH.** `auto.handTuned` has no dotted key, so
     * it is carried across from the base — and if it were not, every slider tick would silently discard
     * whatever the engine's knob protection had been told.
     */
    @Test
    fun theHandTunedSetSurvivesAPatchThatCannotNameIt() {
        val base = TraceParams(auto = TraceParams().auto.copy(handTuned = setOf("simplify"))).sanitized()
        val after = traceApplyLeaves(base, mapOf("output.simplify" to TraceValue.Num(3.0)))
        assertEquals(setOf("simplify"), after.auto.handTuned)
        assertEquals(3.0f, after.output.simplify, 1e-6f)
    }

    /** And through the wire, which is the form the panel's `withOverrides` actually goes through. */
    @Test
    fun theHandTunedSetSurvivesTheWire() {
        val base = TraceParams(auto = TraceParams().auto.copy(handTuned = setOf("simplify"))).sanitized()
        val values = traceValuesOfParams(base)
        assertNull("The flat map has no key for it", values["auto.handTuned"])
        assertEquals(
            "…and the wire carries it anyway, which is why the wire is the authority",
            setOf("simplify"),
            traceParamsOf(values).auto.handTuned,
        )
    }

    /* ── Colours ────────────────────────────────────────────────────────────────────────────── */

    /**
     * **COLOURS CROSS UNSIGNED.** JavaScript has no signed 32-bit integer, so every tree the portal has
     * written carries opaque black as 4278190080 and opaque white as 4294967295. A tree that carried
     * Kotlin's -16777216 would be a tree the portal reads as a nearly-transparent dark blue.
     */
    @Test
    fun aColourLeavesAsAnUnsignedNumber() {
        val values = traceValuesOfParams(TraceParams())
        assertEquals(4278190080.0, values.number("output.strokeColor")!!, 0.5)
    }

    @Test
    fun opaqueWhiteRoundTripsThroughTheSignedInt() {
        val white = traceApplyLeaves(
            TraceParams(),
            mapOf("output.background" to TraceValue.Num(TRACE_OPAQUE_WHITE)),
        )
        assertEquals(
            "4294967295 must wrap to -1, not saturate to Int.MAX_VALUE",
            0xFFFFFFFF.toInt(),
            white.output.background,
        )
        assertEquals(TRACE_OPAQUE_WHITE, traceValuesOfParams(white).number("output.background")!!, 0.5)
    }

    /**
     * `null` IS A VALUE HERE AND NOT AN ABSENCE: it is the only spelling of a transparent export, which
     * is why the "White background" toggle asks whether the leaf is PRESENT.
     */
    @Test
    fun aTransparentBackgroundStaysNullThroughTheRoundTrip() {
        val transparent = traceApplyLeaves(
            TraceParams(output = TraceParams().output.copy(background = 0xFFFFFFFF.toInt())),
            mapOf("output.background" to TraceValue.Absent),
        )
        assertNull(transparent.output.background)
        assertFalse(traceValuesOfParams(transparent).present("output.background"))
    }

    /** A tree written by the portal, with unsigned colours, opens here. */
    @Test
    fun aPortalWrittenTreeOpens() {
        val portal = """{"output":{"strokeColor":4278190080,"background":4294967295}}"""
        val params = traceParamsOfWire(portal)
        assertEquals(0xFF000000.toInt(), params.output.strokeColor)
        assertEquals(0xFFFFFFFF.toInt(), params.output.background)
    }

    /* ── Skew in both directions ────────────────────────────────────────────────────────────── */

    /** A key this build does not know is ignored, exactly as the web's own merge ignores it. */
    @Test
    fun anUnknownKeyInAPatchIsIgnoredRatherThanThrown() {
        val after = traceApplyLeaves(TraceParams(), mapOf("preprocess.somethingNewer" to TraceValue.Num(1.0)))
        assertEquals(TraceParams().sanitized(), after)
    }

    /** An enum member this build has never heard of falls back rather than crashing an old app. */
    @Test
    fun anUnknownEnumMemberFallsBackToTheFactoryDefault() {
        val after = traceApplyLeaves(TraceParams(), mapOf("edge.engine" to TraceValue.Choice("QUANTUM")))
        assertEquals(EdgeEngine.FDOG, after.edge.engine)
    }

    /**
     * A value of the WRONG SHAPE for a key this file does know is refused rather than reset. It can only
     * be reached by a caller bug — every patch in this app is built by `TRACE_CONTROLS` with the leaf's
     * own kind — and a slider that moved while the parameter did not is the worse outcome.
     */
    @Test
    fun aValueOfTheWrongShapeIsRefused() {
        try {
            traceApplyLeaves(TraceParams(), mapOf("output.simplify" to TraceValue.Choice("a lot")))
            fail("A name where a number belongs must be refused")
        } catch (expected: TraceHostFailure) {
            assertTrue(expected.message!!.contains("a number"))
        }
    }

    @Test
    fun aNonFiniteValueIsRefused() {
        try {
            traceApplyLeaves(TraceParams(), mapOf("output.simplify" to TraceValue.Num(Double.NEGATIVE_INFINITY)))
            fail("An infinity must be refused rather than clamped into something plausible")
        } catch (expected: TraceHostFailure) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun aTreeThatIsNotJsonIsRefusedRatherThanRepaired() {
        try {
            traceParamsOfWire("not a tree")
            fail("A malformed tree must be refused")
        } catch (expected: TraceHostFailure) {
            assertEquals(TraceFailureKind.ENGINE_ERROR, expected.kind)
        }
    }

    /* ── Integer truncation ─────────────────────────────────────────────────────────────────── */

    /**
     * TRUNCATION TOWARD ZERO, matching the web's own integer clamp. The slider rounds before it sends
     * precisely because of this: a leaf sent as 2047.9999 comes back 2047 and the readout would settle
     * one below the thumb.
     */
    @Test
    fun anIntegerLeafTruncatesTowardZero() {
        val after = traceApplyLeaves(
            TraceParams(),
            mapOf("preprocess.workingLongEdge" to TraceValue.Num(2047.9999)),
        )
        assertEquals(2047, after.preprocess.workingLongEdge)
    }

    /* ── The sanitiser is the engine's ──────────────────────────────────────────────────────── */

    /**
     * **NO CLAMP TABLE ON THIS SIDE.** An out-of-range value is not rejected here and is not clamped
     * here — it goes to the engine's own `sanitized()`, which is the sole authority on what is legal.
     */
    @Test
    fun anOutOfRangeValueIsClampedByTheEngineAndNotByThisSide() {
        val after = traceApplyLeaves(TraceParams(), mapOf("edge.sensitivity" to TraceValue.Num(99.0)))
        assertTrue("The engine clamps it; nothing here decides where", after.edge.sensitivity <= 1f)
    }
}
