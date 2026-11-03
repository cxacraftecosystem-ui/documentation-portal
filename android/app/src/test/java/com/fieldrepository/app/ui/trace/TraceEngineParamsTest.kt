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

class TraceEngineParamsTest {

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

    @Test
    fun aFlattenedTreeNestsBackToItself() {
        val original = TraceParams().sanitized()
        val flat = traceFlattenParams(original)
        val back = traceNestLeaves(flat)
        assertEquals(traceFlattenParams(original), traceFlattenParams(back))
    }

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

    @Test
    fun theHandTunedSetSurvivesAPatchThatCannotNameIt() {
        val base = TraceParams(auto = TraceParams().auto.copy(handTuned = setOf("simplify"))).sanitized()
        val after = traceApplyLeaves(base, mapOf("output.simplify" to TraceValue.Num(3.0)))
        assertEquals(setOf("simplify"), after.auto.handTuned)
        assertEquals(3.0f, after.output.simplify, 1e-6f)
    }

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

    @Test
    fun aTransparentBackgroundStaysNullThroughTheRoundTrip() {
        val transparent = traceApplyLeaves(
            TraceParams(output = TraceParams().output.copy(background = 0xFFFFFFFF.toInt())),
            mapOf("output.background" to TraceValue.Absent),
        )
        assertNull(transparent.output.background)
        assertFalse(traceValuesOfParams(transparent).present("output.background"))
    }

    @Test
    fun aPortalWrittenTreeOpens() {
        val portal = """{"output":{"strokeColor":4278190080,"background":4294967295}}"""
        val params = traceParamsOfWire(portal)
        assertEquals(0xFF000000.toInt(), params.output.strokeColor)
        assertEquals(0xFFFFFFFF.toInt(), params.output.background)
    }

    @Test
    fun anUnknownKeyInAPatchIsIgnoredRatherThanThrown() {
        val after = traceApplyLeaves(TraceParams(), mapOf("preprocess.somethingNewer" to TraceValue.Num(1.0)))
        assertEquals(TraceParams().sanitized(), after)
    }

    @Test
    fun anUnknownEnumMemberFallsBackToTheFactoryDefault() {
        val after = traceApplyLeaves(TraceParams(), mapOf("edge.engine" to TraceValue.Choice("QUANTUM")))
        assertEquals(EdgeEngine.FDOG, after.edge.engine)
    }

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

    @Test
    fun anIntegerLeafTruncatesTowardZero() {
        val after = traceApplyLeaves(
            TraceParams(),
            mapOf("preprocess.workingLongEdge" to TraceValue.Num(2047.9999)),
        )
        assertEquals(2047, after.preprocess.workingLongEdge)
    }

    @Test
    fun anOutOfRangeValueIsClampedByTheEngineAndNotByThisSide() {
        val after = traceApplyLeaves(TraceParams(), mapOf("edge.sensitivity" to TraceValue.Num(99.0)))
        assertTrue("The engine clamps it; nothing here decides where", after.edge.sensitivity <= 1f)
    }
}
