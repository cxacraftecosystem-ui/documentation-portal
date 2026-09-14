package com.offlinetracer.pipeline

import com.offlinetracer.imaging.EdgeModel
import com.offlinetracer.imaging.EdgeModelRegistry
import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.NoEdgeModel
import com.offlinetracer.imaging.RgbaImage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Learned edge models (ALGORITHMS §7.6) — the registry, and every way the pipeline can fail to use one.
 *
 * **No weights ship with this app and none are downloaded**, and that is the intended shipping state,
 * not a gap: the Android manifest holds no `INTERNET` permission, so there is nowhere to download from
 * and a downloader would have to weaken the one guarantee the whole project rests on. So there is
 * nothing here that infers anything for real. What there *is* is the substitution machinery, and the
 * only thing that makes it worth having is that **every** path through it is reported: a model that is
 * missing, one that is present but not ready, one that throws, and one that answers with the wrong
 * shape all have to produce a drawing plus a sentence naming what ran instead.
 *
 * That is the whole point. A model that silently falls back to FDoG is indistinguishable, on screen,
 * from a model that ran and disagreed with the user, and those two need opposite responses — reinstall
 * the model, or change the parameters.
 *
 * The doubles below are test doubles, not stubs standing in for a shipped implementation. [FakeModel]
 * exists so the *available* branch is executed by something at least once; without it that branch is
 * dead code that has never run, which is how a fallback path ends up being the only one that works.
 */
class EdgeModelTest {

    /**
     * A model whose availability and behaviour the test dictates.
     *
     * `calls` is the load-bearing part: several assertions below turn on whether inference was even
     * attempted, and "the note is absent" is not evidence that the model ran.
     */
    private class FakeModel(
        override val id: String,
        override val displayName: String = id,
        override val isAvailable: Boolean = true,
        // The default is a plausible answer: dark is ink, so `1 − v` is an edge probability with
        // 1 = edge, which is the polarity infer() promises and the opposite of every DoG engine's.
        private val behaviour: (GrayF) -> GrayF = { src ->
            val out = FloatArray(src.size)
            for (i in out.indices) out[i] = 1f - src.data[i]
            GrayF(src.width, src.height, out)
        },
    ) : EdgeModel {
        var calls = 0
            private set

        override fun infer(src: GrayF): GrayF {
            calls++
            return behaviour(src)
        }
    }

    // The registry is a process-wide singleton, because the platform layer registers into it while a
    // trace may already be running on a worker. That makes leaked registrations a cross-test hazard:
    // PipelineTest asserts that "pidinet-tiny" is *not* installed, and a stray registration here would
    // break it somewhere else entirely.
    @BeforeTest
    fun clearBefore() = EdgeModelRegistry.clear()

    @AfterTest
    fun clearAfter() = EdgeModelRegistry.clear()

    // -------------------------------------------------------------------------------------------
    // NoEdgeModel
    // -------------------------------------------------------------------------------------------

    @Test
    fun theDefaultModelRefusesToInferRatherThanReturningZeros() {
        assertFalse(NoEdgeModel.isAvailable)
        assertEquals("", NoEdgeModel.id, "a blank id is what keeps the default out of the registry")
        assertTrue(NoEdgeModel.displayName.isNotBlank(), "the picker has to have something to show")

        // Returning an all-zero map would read as "the model found no edges in your artwork", which is
        // a statement about the image rather than about the install. Throwing puts the failure at the
        // one place the mistake is legible.
        val failure = assertFailsWith<UnsupportedOperationException> { NoEdgeModel.infer(GrayF(4, 4)) }
        assertTrue(
            failure.message!!.contains("isAvailable"),
            "the refusal should say what the caller forgot to check: ${failure.message}",
        )
    }

    // -------------------------------------------------------------------------------------------
    // The registry
    // -------------------------------------------------------------------------------------------

    @Test
    fun anEmptyRegistryIsTheNormalStateAndMissesCleanly() {
        assertTrue(EdgeModelRegistry.all().isEmpty())
        assertTrue(EdgeModelRegistry.available().isEmpty())
        assertNull(EdgeModelRegistry.byId("anything"))
        assertNull(EdgeModelRegistry.byId(""), "the default's blank id must not resolve to something")
    }

    @Test
    fun registrationOrderIsPreservedSoThePickerDoesNotReshuffle() {
        EdgeModelRegistry.register(FakeModel("pidinet"))
        EdgeModelRegistry.register(FakeModel("hed"))
        EdgeModelRegistry.register(FakeModel("dexined"))
        assertEquals(listOf("pidinet", "hed", "dexined"), EdgeModelRegistry.all().map { it.id })
    }

    @Test
    fun reRegisteringAModelReplacesItInPlaceWithoutMovingIt() {
        // The real sequence this exists for: a model registers as unavailable while its weights load,
        // then registers again as available. If that moved it to the end, the picker would reorder
        // itself under the user's finger.
        EdgeModelRegistry.register(FakeModel("pidinet", isAvailable = false))
        EdgeModelRegistry.register(FakeModel("hed"))
        val loaded = FakeModel("pidinet", displayName = "PiDiNet (loaded)", isAvailable = true)
        EdgeModelRegistry.register(loaded)

        assertEquals(listOf("pidinet", "hed"), EdgeModelRegistry.all().map { it.id })
        assertSame(loaded, EdgeModelRegistry.byId("pidinet"))
        assertEquals("PiDiNet (loaded)", EdgeModelRegistry.byId("pidinet")!!.displayName)
    }

    @Test
    fun aModelWithABlankIdIsIgnoredRatherThanCrashingTheApp() {
        // A blank id can only come from a malformed side-loaded bundle. It cannot be persisted in a
        // project file or looked up, so listing it would give the user a row that does nothing —
        // but taking the app down over one is worse than not listing it.
        EdgeModelRegistry.register(FakeModel(""))
        assertTrue(EdgeModelRegistry.all().isEmpty())
        assertNull(EdgeModelRegistry.byId(""))
    }

    @Test
    fun availableHidesAnUnreadyModelAndAllKeepsIt() {
        EdgeModelRegistry.register(FakeModel("ready", isAvailable = true))
        EdgeModelRegistry.register(FakeModel("loading", isAvailable = false))

        assertEquals(listOf("ready"), EdgeModelRegistry.available().map { it.id })
        // The distinction the picker needs. Built on available() it would drop the second model
        // entirely, and "my model disappeared" becomes a question with no answer on screen; built on
        // all() the row is there, greyed out, which is a fact somebody can act on.
        assertEquals(listOf("ready", "loading"), EdgeModelRegistry.all().map { it.id })
        assertFalse(EdgeModelRegistry.byId("loading")!!.isAvailable)
    }

    @Test
    fun theReturnedListsAreSnapshotsAndNotViewsOntoTheRegistry() {
        EdgeModelRegistry.register(FakeModel("one"))
        val snapshot = EdgeModelRegistry.all()
        EdgeModelRegistry.register(FakeModel("two"))
        assertEquals(1, snapshot.size, "a caller iterating the list while the platform registers would " +
            "otherwise hit a ConcurrentModificationException on a worker thread")
        assertEquals(2, EdgeModelRegistry.all().size)
    }

    @Test
    fun clearForgetsEverything() {
        EdgeModelRegistry.register(FakeModel("one"))
        EdgeModelRegistry.register(FakeModel("two"))
        EdgeModelRegistry.clear()
        assertTrue(EdgeModelRegistry.all().isEmpty())
        assertNull(EdgeModelRegistry.byId("one"))
    }

    // -------------------------------------------------------------------------------------------
    // What the pipeline does with one
    // -------------------------------------------------------------------------------------------

    @Test
    fun anAvailableModelActuallyRunsAndNothingFallsBack() {
        val model = FakeModel("pidinet", displayName = "PiDiNet")
        EdgeModelRegistry.register(model)

        val result = trace("pidinet")

        assertEquals(1, model.calls, "the model was registered and available but was never asked")
        assertTrue(
            result.notes.none { it.contains("FDoG engine ran instead") },
            "nothing should have fallen back: ${result.notes}",
        )
        assertTrue(result.document.shapeCount() > 0, "the model's map produced no geometry: ${result.notes}")
    }

    @Test
    fun aRegisteredButUnavailableModelIsNamedAndTheClassicalEngineRuns() {
        val model = FakeModel("pidinet", displayName = "PiDiNet (tiny)", isAvailable = false)
        EdgeModelRegistry.register(model)

        val result = trace("pidinet")

        assertEquals(0, model.calls, "an unavailable model must not be called")
        val note = result.notes.firstOrNull { it.contains("PiDiNet (tiny)") }
        assertTrue(note != null, "the model was not named: ${result.notes}")
        assertTrue(note!!.contains("not ready to run"), note)
        assertTrue(note.contains("FDoG"), "the note must say what ran instead: $note")
        assertTrue(result.document.shapeCount() > 0, "an unready model must degrade, not fail")
    }

    @Test
    fun aModelThatThrowsIsNamedWithItsErrorAndDoesNotTakeTheTraceDown() {
        // A side-loaded model is third-party native code on an unknown ABI. The one outcome that is not
        // acceptable is the trace dying with it, and the second-worst is dying silently.
        EdgeModelRegistry.register(
            FakeModel("crashy", displayName = "Crashy") {
                throw IllegalStateException("tensor rank 3 expected")
            }
        )

        val result = trace("crashy")

        val note = result.notes.firstOrNull { it.contains("Crashy") }
        assertTrue(note != null, "the failing model was not named: ${result.notes}")
        assertTrue(note!!.contains("failed while running"), note)
        assertTrue(
            note.contains("tensor rank 3 expected"),
            "the model's own message is the only clue the user has: $note",
        )
        assertTrue(result.document.shapeCount() > 0, "the trace must survive a model that throws")
    }

    @Test
    fun aModelThatAnswersWithTheWrongShapeIsDiscardedWithANote() {
        // Not resampled. A model that returns the wrong dimensions has misunderstood its input, and
        // scaling its output to fit would hide that behind a plausible-looking drawing.
        EdgeModelRegistry.register(
            FakeModel("mismatched", displayName = "Mismatched") { GrayF(it.width / 2, it.height) }
        )

        val result = trace("mismatched")

        val note = result.notes.firstOrNull { it.contains("Mismatched") }
        assertTrue(note != null, "notes were ${result.notes}")
        assertTrue(note!!.contains("returned a"), note)
        assertTrue(note.contains("discarded"), note)
        assertTrue(note.contains("FDoG"), note)
    }

    @Test
    fun anIdThatWasNeverRegisteredIsNamedRatherThanSilentlyIgnored() {
        // The common real case: a project file references a model the user has since removed, or opened
        // on a second device. The id has to appear in the note, because it is the only thing that tells
        // them *which* model to reinstall.
        val result = trace("pidinet-from-my-other-phone")
        val note = result.notes.firstOrNull { it.contains("pidinet-from-my-other-phone") }
        assertTrue(note != null, "the missing id was not named: ${result.notes}")
        assertTrue(note!!.contains("not installed"), note)
    }

    @Test
    fun aModelThatReturnsOutOfRangeValuesIsClampedRatherThanTrusted() {
        // The contract says 0..1; a real ONNX graph with the wrong final activation returns logits. The
        // values are clamped instead of rejected, because a shifted range is still a usable ranking of
        // edge strength and Otsu adapts to whatever scale it is handed — but NaN is not, and one NaN
        // propagates through the threshold into an empty drawing with nothing to attribute it to.
        var maxSeen = Float.NEGATIVE_INFINITY
        EdgeModelRegistry.register(
            FakeModel("logits", displayName = "Logits") { src ->
                val out = FloatArray(src.size)
                for (i in out.indices) {
                    out[i] = if (i % 97 == 0) Float.NaN else (1f - src.data[i]) * 8f - 3f
                    if (out[i] > maxSeen) maxSeen = out[i]
                }
                GrayF(src.width, src.height, out)
            }
        )

        val result = trace("logits")
        assertTrue(maxSeen > 1f, "the fixture did not actually produce out-of-range values")
        assertTrue(
            result.notes.none { it.contains("FDoG engine ran instead") },
            "an out-of-range map is usable and must not trigger a fallback: ${result.notes}",
        )
        // The clamp is what makes this pass. Left alone, the NaNs make every comparison in the
        // threshold false, the mask comes back empty, and the failure surfaces two stages later as
        // "no ink survived the cleanup stages" — a sentence that blames the cleanup for the model.
        assertTrue(
            result.notes.none { it.contains("No ink survived") },
            "NaNs from the model emptied the mask: ${result.notes}",
        )
        assertTrue(result.document.shapeCount() > 0, "notes were ${result.notes}")
    }

    // -------------------------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------------------------

    private fun trace(modelId: String) = Pipeline.run(
        cross(96, 10),
        TraceParams(edge = EdgeParams(engine = EdgeEngine.MODEL, modelId = modelId)),
        classify = false,
    )

    /** White paper with a black cross — enough ink for every engine here to find something. */
    private fun cross(size: Int, thickness: Int): RgbaImage {
        val img = RgbaImage(size, size).fill(0xFFFFFFFF.toInt())
        val mid = size / 2
        val half = thickness / 2
        for (y in 0 until size) for (x in mid - half until mid - half + thickness) img[x, y] = 0xFF000000.toInt()
        for (x in 0 until size) for (y in mid - half until mid - half + thickness) img[x, y] = 0xFF000000.toInt()
        return img
    }
}
