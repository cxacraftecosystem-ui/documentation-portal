package com.offlinetracer.pipeline

import com.offlinetracer.imaging.RgbaImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The batch runner, whose entire reason for existing is that one bad file must not cost the other
 * hundred and ninety-nine.
 */
class BatchTest {

    private val white = RgbaImage.argb(255, 255, 255, 255)
    private val black = RgbaImage.argb(255, 0, 0, 0)

    private fun cross(size: Int, thickness: Int): RgbaImage {
        val img = RgbaImage(size, size).fill(white)
        val lo = (size - thickness) / 2
        val hi = lo + thickness
        for (y in 0 until size) for (x in lo until hi) img[x, y] = black
        for (x in 0 until size) for (y in lo until hi) img[x, y] = black
        return img
    }

    /** Small and cheap: these tests are about the loop, not about the trace. */
    private val fastParams = TraceParams(
        preprocess = PreprocessParams(claheEnabled = false, denoise = DenoiseMode.NONE),
        edge = EdgeParams(engine = EdgeEngine.ADAPTIVE, adaptiveRadius = 8),
    )

    private fun items(vararg ids: String): List<BatchItem> = ids.map { BatchItem(it, "$it.png") }

    @Test
    fun everyItemGetsExactlyOneOutcomeInInputOrder() {
        val list = items("a", "b", "c")
        val saved = ArrayList<String>()
        val outcomes = Batch.run(
            items = list,
            params = fastParams,
            load = { cross(48, 8) },
            save = { item, _ -> saved.add(item.id) },
            progress = { _, _, _ -> },
        )
        assertEquals(listOf("a", "b", "c"), outcomes.map { it.id })
        assertEquals(listOf("a", "b", "c"), saved)
        for (o in outcomes) {
            assertTrue(o.ok, "${o.id} failed: ${o.message}")
            assertTrue(o.message.isNotBlank())
            assertTrue(o.millis >= 0)
        }
        assertEquals(0, Batch.failureCount(outcomes))
    }

    @Test
    fun anItemThatCannotBeLoadedIsRecordedAndTheRunContinues() {
        val outcomes = Batch.run(
            items = items("good1", "unreadable", "good2"),
            params = fastParams,
            load = { if (it.id == "unreadable") null else cross(48, 8) },
            save = { _, _ -> },
            progress = { _, _, _ -> },
        )
        assertEquals(3, outcomes.size)
        assertTrue(outcomes[0].ok)
        assertTrue(!outcomes[1].ok)
        assertTrue(
            outcomes[1].message.contains("unreadable.png"),
            "the failure must name the file: ${outcomes[1].message}",
        )
        assertTrue(outcomes[2].ok, "the item after a failure must still run: ${outcomes[2].message}")
        assertEquals(1, Batch.failureCount(outcomes))
    }

    @Test
    fun anItemWhoseLoadThrowsIsRecordedAndTheRunContinues() {
        val attempted = ArrayList<String>()
        val outcomes = Batch.run(
            items = items("a", "corrupt", "c"),
            params = fastParams,
            load = {
                attempted.add(it.id)
                if (it.id == "corrupt") throw IllegalStateException("truncated JPEG stream")
                cross(48, 8)
            },
            save = { _, _ -> },
            progress = { _, _, _ -> },
        )
        assertEquals(listOf("a", "corrupt", "c"), attempted, "the loop stopped early")
        assertTrue(!outcomes[1].ok)
        assertTrue(
            outcomes[1].message.contains("truncated JPEG stream"),
            "the cause must survive into the row: ${outcomes[1].message}",
        )
        assertTrue(outcomes[0].ok && outcomes[2].ok)
    }

    @Test
    fun anItemWhoseSaveThrowsIsRecordedAndTheRunContinues() {
        // A disk that fills up halfway is recorded per item exactly like a corrupt input, because the
        // remaining files may be smaller and still fit.
        val outcomes = Batch.run(
            items = items("a", "b", "c"),
            params = fastParams,
            load = { cross(48, 8) },
            save = { item, _ ->
                if (item.id == "b") throw RuntimeException("No space left on device")
            },
            progress = { _, _, _ -> },
        )
        assertEquals(3, outcomes.size)
        assertTrue(outcomes[0].ok)
        assertTrue(!outcomes[1].ok)
        assertTrue(outcomes[1].message.contains("No space left on device"))
        assertTrue(outcomes[2].ok)
    }

    @Test
    fun anErrorAndNotJustAnExceptionIsSurvivedToo() {
        // The realistic failure in a large batch is an OutOfMemoryError on one oversized file.
        val outcomes = Batch.run(
            items = items("a", "huge", "c"),
            params = fastParams,
            load = {
                if (it.id == "huge") throw OutOfMemoryError("Java heap space")
                cross(48, 8)
            },
            save = { _, _ -> },
            progress = { _, _, _ -> },
        )
        assertEquals(3, outcomes.size)
        assertTrue(!outcomes[1].ok)
        assertTrue(outcomes[2].ok, "the batch must not die with one oversized file")
    }

    @Test
    fun progressNamesTheItemAboutToRunNotTheOneJustFinished() {
        val reported = ArrayList<Triple<Int, Int, String>>()
        Batch.run(
            items = items("a", "b", "c"),
            params = fastParams,
            load = { cross(48, 8) },
            save = { _, _ -> },
            progress = { index, total, item -> reported.add(Triple(index, total, item.id)) },
        )
        assertEquals(
            listOf(Triple(0, 3, "a"), Triple(1, 3, "b"), Triple(2, 3, "c")),
            reported,
        )
    }

    @Test
    fun cancellingRecordsAnOutcomeForEveryItemThatNeverRan() {
        val token = CancellationToken()
        val outcomes = Batch.run(
            items = items("a", "b", "c", "d"),
            params = fastParams,
            load = { cross(48, 8) },
            save = { item, _ -> if (item.id == "b") token.cancel() },
            progress = { _, _, _ -> },
            cancel = token,
        )
        // One outcome per item even so: a caller that sees 4 items and 2 outcomes cannot tell
        // "cancelled" from "crashed", and those need different words in front of the user.
        assertEquals(4, outcomes.size)
        assertTrue(outcomes[0].ok)
        assertTrue(outcomes[1].ok)
        assertTrue(!outcomes[2].ok)
        assertTrue(outcomes[2].message.contains("cancelled"), outcomes[2].message)
        assertTrue(!outcomes[3].ok)
        assertEquals(2, Batch.failureCount(outcomes))
    }

    @Test
    fun aTokenCancelledBeforeTheRunStartsAttemptsNothing() {
        val token = CancellationToken()
        token.cancel()
        var loads = 0
        val outcomes = Batch.run(
            items = items("a", "b"),
            params = fastParams,
            load = { loads++; cross(48, 8) },
            save = { _, _ -> },
            progress = { _, _, _ -> },
            cancel = token,
        )
        assertEquals(0, loads)
        assertEquals(2, outcomes.size)
        assertEquals(2, Batch.failureCount(outcomes))
    }

    @Test
    fun anEmptyWorkListIsNotAnError() {
        var touched = false
        val outcomes = Batch.run(
            items = emptyList(),
            params = fastParams,
            load = { touched = true; null },
            save = { _, _ -> touched = true },
            progress = { _, _, _ -> touched = true },
        )
        assertTrue(outcomes.isEmpty())
        assertTrue(!touched)
    }

    @Test
    fun aSuccessfulItemsMessageCarriesTheTracesOwnNotesForward() {
        // The batch view is the one place a user never sees the per-image notes, so the count has to
        // survive into the row — the notes are where "the matte deleted your artwork" lives.
        val outcomes = Batch.run(
            items = items("only"),
            params = fastParams.copy(cleanup = CleanupParams(minBlobArea = 1_000_000)),
            load = { cross(48, 8) },
            save = { _, _ -> },
            progress = { _, _, _ -> },
        )
        assertTrue(outcomes[0].ok)
        assertTrue(outcomes[0].message.contains("note"), outcomes[0].message)
    }

    @Test
    fun everyItemSeesTheSameSanitisedParameters() {
        val seen = ArrayList<Int>()
        Batch.run(
            items = items("a", "b"),
            params = fastParams.copy(
                preprocess = fastParams.preprocess.copy(workingLongEdge = 9_999_999),
            ),
            load = { cross(48, 8) },
            save = { _, result -> seen.add(result.workingWidth) },
            progress = { _, _, _ -> },
        )
        // An illegal working edge was clamped once, up front, so both items ran identically.
        assertEquals(listOf(48, 48), seen)
    }
}
