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

class EdgeModelTest {

    private class FakeModel(
        override val id: String,
        override val displayName: String = id,
        override val isAvailable: Boolean = true,
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

    @BeforeTest
    fun clearBefore() = EdgeModelRegistry.clear()

    @AfterTest
    fun clearAfter() = EdgeModelRegistry.clear()

    @Test
    fun theDefaultModelRefusesToInferRatherThanReturningZeros() {
        assertFalse(NoEdgeModel.isAvailable)
        assertEquals("", NoEdgeModel.id, "a blank id is what keeps the default out of the registry")
        assertTrue(NoEdgeModel.displayName.isNotBlank(), "the picker has to have something to show")

        val failure = assertFailsWith<UnsupportedOperationException> { NoEdgeModel.infer(GrayF(4, 4)) }
        assertTrue(
            failure.message!!.contains("isAvailable"),
            "the refusal should say what the caller forgot to check: ${failure.message}",
        )
    }

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
        EdgeModelRegistry.register(FakeModel(""))
        assertTrue(EdgeModelRegistry.all().isEmpty())
        assertNull(EdgeModelRegistry.byId(""))
    }

    @Test
    fun availableHidesAnUnreadyModelAndAllKeepsIt() {
        EdgeModelRegistry.register(FakeModel("ready", isAvailable = true))
        EdgeModelRegistry.register(FakeModel("loading", isAvailable = false))

        assertEquals(listOf("ready"), EdgeModelRegistry.available().map { it.id })
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
        val result = trace("pidinet-from-my-other-phone")
        val note = result.notes.firstOrNull { it.contains("pidinet-from-my-other-phone") }
        assertTrue(note != null, "the missing id was not named: ${result.notes}")
        assertTrue(note!!.contains("not installed"), note)
    }

    @Test
    fun aModelThatReturnsOutOfRangeValuesIsClampedRatherThanTrusted() {
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
        assertTrue(
            result.notes.none { it.contains("No ink survived") },
            "NaNs from the model emptied the mask: ${result.notes}",
        )
        assertTrue(result.document.shapeCount() > 0, "notes were ${result.notes}")
    }

    private fun trace(modelId: String) = Pipeline.run(
        cross(96, 10),
        TraceParams(edge = EdgeParams(engine = EdgeEngine.MODEL, modelId = modelId)),
        classify = false,
    )

    private fun cross(size: Int, thickness: Int): RgbaImage {
        val img = RgbaImage(size, size).fill(0xFFFFFFFF.toInt())
        val mid = size / 2
        val half = thickness / 2
        for (y in 0 until size) for (x in mid - half until mid - half + thickness) img[x, y] = 0xFF000000.toInt()
        for (x in 0 until size) for (y in mid - half until mid - half + thickness) img[x, y] = 0xFF000000.toInt()
        return img
    }
}
