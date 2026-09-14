package com.fieldrepository.app.ui.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The parts of the trace feature that can be wrong WITHOUT FAILING — the marshalling, the geometry
 * mirror, the working size, the device ceilings and the failure sentences.
 *
 * Everything here is reachable on a desktop JVM because `TraceWire.kt` imports no `android.*`. That is
 * the whole reason it exists as a separate file; see its header.
 */
class TraceWireTest {

    /* ── Failure sentences ──────────────────────────────────────────────────────────────────── */

    /**
     * FIVE KINDS, AND EVERY ONE OF THEM IS CONSTRUCTED SOMEWHERE IN THIS PACKAGE.
     *
     * The design-workshop original carries eleven, six of which describe a JavaScript bridge that has
     * never existed in this repository — and its own header records that no designer can see any of
     * their sentences, because nothing constructs them. This asserts the port did not import them. A
     * sixth member appearing here means either a real new failure with a real new remedy, or somebody
     * copying a sentence from a product this one is not.
     */
    @Test
    fun everyFailureKindIsOneThisAppCanActuallyReach() {
        assertEquals(
            "TraceFailureKind gained or lost a member. Every one of them must be constructed by " +
                "something in this package — see TraceWire.kt's header for the six that were " +
                "deliberately not ported.",
            5,
            TraceFailureKind.entries.size,
        )
    }

    /** No two kinds may share a sentence: one kind per REMEDY, never one per exception class. */
    @Test
    fun everyFailureKindHasItsOwnSentence() {
        val sentences = TraceFailureKind.entries.map { traceSentence(it) }
        assertEquals(
            "Two failure kinds produce the same sentence, so one of them is telling somebody the " +
                "wrong remedy.",
            sentences.size,
            sentences.toSet().size,
        )
    }

    /**
     * A sentence a person can act on: long enough to be a sentence, and naming something to DO.
     *
     * "Something went wrong" is forbidden here because somebody four days from a connection cannot act
     * on it, and neither can whoever reads the screenshot afterwards.
     */
    @Test
    fun everyFailureSentenceNamesSomethingToDo() {
        val verbs = listOf(
            "Set ", "Choose", "Take", "Try", "Turn off", "Closing", "close", "trace it",
            "can still be attached", "can still be",
        )
        TraceFailureKind.entries.forEach { kind ->
            val sentence = traceSentence(kind)
            assertTrue("$kind is too short to be a sentence: $sentence", sentence.length > 60)
            assertTrue(
                "$kind names nothing the reader can do: $sentence",
                verbs.any { sentence.contains(it) },
            )
        }
    }

    /** The detail is appended when it is short and dropped when it is a stack trace in disguise. */
    @Test
    fun engineDetailIsCappedRatherThanPrinted() {
        val short = traceSentence(TraceFailureKind.ENGINE_ERROR, "the frame was empty")
        assertTrue(short.contains("the frame was empty"))

        val long = "x".repeat(TRACE_DETAIL_MAX + 1)
        val capped = traceSentence(TraceFailureKind.ENGINE_ERROR, long)
        assertTrue("A detail past the cap must be dropped, not truncated", !capped.contains(long))
    }

    /* ── The channel marshalling ────────────────────────────────────────────────────────────── */

    /**
     * **THE ONE PIECE OF THIS FEATURE THAT CAN BE WRONG AND STILL TRACE.**
     *
     * Swap two channels and the engine still produces line art — it traces a picture with red and blue
     * exchanged, which on a pencil sketch on cream paper looks very nearly right and comes out quietly
     * different from the portal's answer forever. So the pair is round-tripped rather than inspected:
     * `traceArgbRowToRgba` writes the engine's byte order and `traceImageOf` reads it back, and the
     * integers that come out must be the integers that went in.
     */
    @Test
    fun theRgbaMarshallingIsAnExactRoundTrip() {
        val pixels = intArrayOf(
            0xFF102030.toInt(),
            0x80FFFFFF.toInt(),
            0x00000000,
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
        )
        val bytes = ByteArray(pixels.size * 4)
        traceArgbRowToRgba(pixels, pixels.size, bytes, 0)

        val image = traceImageOf(bytes, pixels.size, 1)
        assertEquals(pixels.size, image.width)
        assertEquals(1, image.height)
        pixels.indices.forEach { i ->
            assertEquals(
                "Channel order drifted at pixel $i — see traceArgbRowToRgba's docblock.",
                pixels[i],
                image.pixels[i],
            )
        }
    }

    /** The byte order itself, spelled out, so a reader can check it against the engine's own. */
    @Test
    fun theByteOrderIsRedGreenBlueAlpha() {
        val bytes = ByteArray(4)
        traceArgbRowToRgba(intArrayOf(0x11223344), 1, bytes, 0)
        assertEquals(0x22.toByte(), bytes[0])
        assertEquals(0x33.toByte(), bytes[1])
        assertEquals(0x44.toByte(), bytes[2])
        assertEquals(0x11.toByte(), bytes[3])
    }

    /** A buffer too short for the frame it claims is refused rather than read past its end. */
    @Test
    fun aShortBufferIsRefused() {
        try {
            traceImageOf(ByteArray(3), 4, 4)
            fail("A buffer three bytes long cannot hold a 4x4 image and must be refused")
        } catch (expected: TraceHostFailure) {
            assertEquals(TraceFailureKind.ENGINE_ERROR, expected.kind)
        }
    }

    /* ── The working size ───────────────────────────────────────────────────────────────────── */

    @Test
    fun aSourceInsideTheCapIsNotResampledForNothing() {
        assertEquals(2480 to 3508, traceWorkingSize(2480, 3508))
    }

    /**
     * `Math.round` and not a floor. A one-pixel difference in the working frame moves every coordinate
     * the engine reports, so the two clients must round the same way.
     */
    @Test
    fun theCapRoundsRatherThanFloors() {
        // 5000x3000 capped at 4096: 3000 * 4096/5000 = 2457.6, which rounds to 2458 and floors to 2457.
        assertEquals(4096 to 2458, traceWorkingSize(5000, 3000))
    }

    @Test
    fun anEdgeNeverFallsBelowOnePixel() {
        val (w, h) = traceWorkingSize(40000, 3)
        assertEquals(TRACE_DECODE_MAX_EDGE_PX, w)
        assertTrue("A dimension of zero would throw inside a bitmap allocation", h >= 1)
    }

    /* ── The device ceilings ────────────────────────────────────────────────────────────────── */

    /**
     * A FAILED MEMORY READ TAKES THE CAUTIOUS HALF. A handset that would have said it was small must
     * not be promoted by a lookup that failed.
     */
    @Test
    fun anUnknownMemorySizeIsTreatedAsASmallPhone() {
        assertEquals(traceCeilings(null), traceCeilings(2L * 1024L * 1024L * 1024L))
        assertEquals(
            TRACE_DEFAULT_MAX_WORKING_EDGE / 2 to TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE / 2,
            traceCeilings(null),
        )
    }

    @Test
    fun aLargePhoneGetsTheFullCeilings() {
        assertEquals(
            TRACE_DEFAULT_MAX_WORKING_EDGE to TRACE_DEFAULT_FDOG_MAX_WORKING_EDGE,
            traceCeilings(6L * 1024L * 1024L * 1024L),
        )
    }

    /** The flow engine's ceiling is BELOW the general one. See TraceEngine.kt for why it is a ceiling. */
    @Test
    fun theFlowEngineIsCappedLowerThanEverythingElse() {
        val (general, flow) = traceCeilings(6L * 1024L * 1024L * 1024L)
        assertTrue("Flow is 5.7x the cost of the alternatives and must not share their ceiling", flow < general)
    }

    /* ── The parameter codec ────────────────────────────────────────────────────────────────── */

    /**
     * **`JsonNull` IS A `JsonPrimitive`**, so it has to be tested before anything general or
     * `output.background: null` decodes as the string "null" and the "White background" toggle reads
     * as ON forever.
     */
    @Test
    fun aNullLeafIsAbsentAndNotTheStringNull() {
        val values = traceValuesOf("""{"output":{"background":null,"simplify":1.5}}""")
        assertEquals(TraceValue.Absent, values["output.background"])
        assertTrue("`present` is what the White background toggle reads", !values.present("output.background"))
        assertEquals(1.5, values.number("output.simplify")!!, 1e-9)
    }

    /** The four leaf kinds survive a flatten, which is what the whole "what changed" report rests on. */
    @Test
    fun everyLeafKindSurvivesTheFlatten() {
        val values = traceValuesOf(
            """{"a":{"n":2.5,"f":true,"c":"CENTERLINE","z":null},"styleId":"clean-line"}"""
        )
        assertEquals(TraceValue.Num(2.5), values["a.n"])
        assertEquals(TraceValue.Flag(true), values["a.f"])
        assertEquals(TraceValue.Choice("CENTERLINE"), values["a.c"])
        assertEquals(TraceValue.Absent, values["a.z"])
        assertEquals("clean-line", values.styleId)
    }

    /**
     * ARRAYS ARE NOT LEAVES AND ARE SKIPPED — there is exactly one today, `auto.handTuned`, and no
     * control reads it. Inventing a key for it would make the flat map and the tree disagree about what
     * a leaf is.
     */
    @Test
    fun anArrayIsNotALeaf() {
        val values = traceValuesOf("""{"auto":{"handTuned":["simplify"],"mode":"SUGGEST"}}""")
        assertNull(values["auto.handTuned"])
        assertEquals("SUGGEST", values.choice("auto.mode"))
    }

    /**
     * NON-FINITE IS A REFUSAL, NOT A SILENT SUBSTITUTION. Dropping the key is the worst of the three
     * options: the slider moves, the trace runs, and the parameter that was changed is the one that did
     * not change.
     */
    @Test
    fun aNonFinitePatchValueIsRefusedRatherThanDropped() {
        try {
            tracePatchJson(mapOf("output.simplify" to TraceValue.Num(Double.NaN)))
            fail("NaN has no JSON spelling and must be refused rather than silently dropped")
        } catch (expected: TraceHostFailure) {
            assertTrue(expected.message!!.contains("not a number the engine can be sent"))
        }
    }

    @Test
    fun aPatchSpellsAbsentAsNull() {
        val json = tracePatchJson(mapOf("output.background" to TraceValue.Absent))
        assertEquals("""{"output.background":null}""", json)
    }

    /** The escaping is hand-written, so it is checked rather than assumed. */
    @Test
    fun theStringEscaperHandlesTheAwkwardCharacters() {
        assertEquals("\"a\\\"b\"", traceJsonString("a\"b"))
        assertEquals("\"a\\\\b\"", traceJsonString("a\\b"))
        assertEquals("\"a\\nb\"", traceJsonString("a\nb"))
        // U+2028 is legal raw JSON and is escaped anyway, because anything re-parsing the output as a
        // script would choke on it. Compared by code point, never by a literal nobody can see.
        assertEquals("\"a\\u2028b\"", traceJsonString("a" + 0x2028.toChar() + "b"))
    }

    /* ── The geometry mirror ────────────────────────────────────────────────────────────────── */

    private fun oneLineGeometry(): TraceGeometry = TraceGeometry(
        coords = floatArrayOf(0f, 0f, 10f, 10f),
        verbs = byteArrayOf(TRACE_VERB_LINE),
        verbStarts = intArrayOf(0, 1),
        coordStarts = intArrayOf(0, 4),
        closed = byteArrayOf(0),
        styleTable = listOf(
            TraceStyle(
                stroke = 0xFF000000.toInt(),
                strokeWidth = 1.5f,
                fill = null,
                fillRule = "EVENODD",
                cap = "ROUND",
                join = "ROUND",
                miterLimit = 4f,
                opacity = 1f,
            )
        ),
        styleIndex = intArrayOf(0),
    )

    @Test
    fun aWellFormedGeometryValidates() {
        oneLineGeometry().validate()
    }

    /**
     * THE COORDINATE-COUNT CHECK IS THE ONE THAT EARNS ITS KEEP. A mismatch there does not crash: it
     * reads a neighbouring shape's numbers as this shape's curve and draws something plausible and
     * wrong, which is the worse failure.
     */
    @Test
    fun aShapeWhoseVerbsWantMoreCoordinatesIsRefused() {
        val good = oneLineGeometry()
        val bad = TraceGeometry(
            coords = good.coords,
            // A cubic needs six coordinates after the start point; this run has two.
            verbs = byteArrayOf(TRACE_VERB_CUBIC),
            verbStarts = good.verbStarts,
            coordStarts = good.coordStarts,
            closed = good.closed,
            styleTable = good.styleTable,
            styleIndex = good.styleIndex,
        )
        try {
            bad.validate()
            fail("A shape whose verbs need more coordinates than it has must be refused")
        } catch (expected: TraceHostFailure) {
            assertTrue(expected.message!!.contains("coordinates"))
        }
    }

    @Test
    fun anUnknownVerbIsRefusedRatherThanSwallowed() {
        val good = oneLineGeometry()
        val bad = TraceGeometry(
            coords = good.coords,
            verbs = byteArrayOf(9),
            verbStarts = good.verbStarts,
            coordStarts = good.coordStarts,
            closed = good.closed,
            styleTable = good.styleTable,
            styleIndex = good.styleIndex,
        )
        try {
            bad.validate()
            fail("A fourth verb code must be refused, because the export's `else` branch reads cubic")
        } catch (expected: TraceHostFailure) {
            assertNotNull(expected.message)
        }
    }

    /** The three codes are a wire contract with the other client's serialiser. */
    @Test
    fun theVerbCodesAreTheOnesTheOtherClientWrites() {
        assertEquals(0.toByte(), TRACE_VERB_LINE)
        assertEquals(1.toByte(), TRACE_VERB_QUAD)
        assertEquals(2.toByte(), TRACE_VERB_CUBIC)
    }
}
