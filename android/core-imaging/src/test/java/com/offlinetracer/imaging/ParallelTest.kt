package com.offlinetracer.imaging

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The contract of the engine's only parallelism primitive.
 *
 * Everything asserted here is depended on by a stage that must produce bit-identical output whether
 * it ran on one core or eight, so each of these is a load-bearing property rather than a nicety:
 *
 *  - **Shares tile the range exactly once.** A gap leaves a band of the destination buffer at its
 *    allocation value (black), an overlap has two threads writing one pixel. Both are silent.
 *  - **Small work runs inline.** The cross-engine parity fixtures are 24×18 and 1×1; if they
 *    dispatched, a failing parity test would have a stack trace through a thread pool for no gain.
 *  - **A worker's exception reaches the caller, unwrapped and by identity.** Cancellation is
 *    signalled by an exception, so an `ExecutionException` wrapper or a swallowed throw would turn a
 *    cancelled trace into either a crash or a hang.
 *  - **A nested call runs inline.** A worker blocking on the pool it is running in deadlocks the
 *    moment the pool saturates, and stages call other stages freely.
 */
class ParallelTest {

    private val original = Parallel.maxThreads

    @AfterTest
    fun restore() {
        // Leaving the cap where a test set it would silently change how every later test class runs.
        Parallel.maxThreads = original
    }

    @Test
    fun everyIndexIsVisitedExactlyOnce() {
        Parallel.maxThreads = 8
        for (count in intArrayOf(1, 2, 7, 33, 64, 65, 256, 1000, 4096, 4097)) {
            for (per in intArrayOf(1, 2, 8, 32, 100)) {
                val visits = IntArray(count)
                // No synchronisation on `visits` on purpose: if the shares really are disjoint then
                // no two threads touch the same element, and this test fails loudly if they are not.
                Parallel.chunks(count, per) { from, to ->
                    for (i in from until to) visits[i]++
                }
                for (i in 0 until count) {
                    assertEquals(1, visits[i], "count=$count per=$per index $i visited ${visits[i]} times")
                }
            }
        }
    }

    @Test
    fun sharesAreContiguousAndDifferByAtMostOneItem() {
        Parallel.maxThreads = 8
        val ranges = java.util.Collections.synchronizedList(ArrayList<IntArray>())
        Parallel.chunks(1000, 1) { from, to -> ranges.add(intArrayOf(from, to)) }
        val sorted = ranges.sortedBy { it[0] }
        assertTrue(sorted.size > 1, "1000 items over 8 threads must actually split, got ${sorted.size} shares")
        assertEquals(0, sorted.first()[0])
        assertEquals(1000, sorted.last()[1])
        for (i in 1 until sorted.size) {
            assertEquals(sorted[i - 1][1], sorted[i][0], "share $i must start where share ${i - 1} ended")
        }
        val lengths = sorted.map { it[1] - it[0] }
        assertTrue(
            lengths.max() - lengths.min() <= 1,
            "shares must differ by at most one item so no worker gets a sliver, got $lengths",
        )
    }

    @Test
    fun workBelowTheGrainRunsInlineOnTheCallingThread() {
        Parallel.maxThreads = 8
        val caller = Thread.currentThread()
        // 31 items at a grain of 32 is one share; so is 63. The parity fixtures are exactly here.
        for (count in intArrayOf(1, 2, 31, 32, 63)) {
            val calls = AtomicInteger(0)
            val range = AtomicReference<IntArray?>(null)
            val offThread = AtomicBoolean(false)
            Parallel.chunks(count, 32) { from, to ->
                calls.incrementAndGet()
                range.set(intArrayOf(from, to))
                if (Thread.currentThread() !== caller) offThread.set(true)
            }
            assertEquals(1, calls.get(), "count=$count at grain 32 must be a single share")
            assertEquals(0, range.get()!![0], "count=$count")
            assertEquals(count, range.get()!![1], "count=$count")
            assertTrue(!offThread.get(), "count=$count must not leave the calling thread")
        }
    }

    /**
     * The engine's real thresholds, checked against the smallest images the engine actually sees.
     * This is the guarantee that the cross-engine fixtures never touch the pool.
     */
    @Test
    fun theParityFixtureSizesRunInlineAtEveryEngineGrain() {
        Parallel.maxThreads = 8
        val caller = Thread.currentThread()
        val grains = intArrayOf(Parallel.ROWS_NEIGHBOURHOOD, Parallel.ROWS_KERNEL)
        for (height in intArrayOf(1, 18)) {
            for (grain in grains) {
                val calls = AtomicInteger(0)
                val offThread = AtomicBoolean(false)
                Parallel.rows(height, grain) { _, _ ->
                    calls.incrementAndGet()
                    if (Thread.currentThread() !== caller) offThread.set(true)
                }
                assertEquals(1, calls.get(), "a ${height}-row image at grain $grain must be one share")
                assertTrue(!offThread.get(), "a ${height}-row image at grain $grain must stay inline")
            }
        }
        // 24×18 = 432 pixels and 1×1 = 1 pixel are both far below the per-pixel grain.
        for (pixels in intArrayOf(1, 432)) {
            val calls = AtomicInteger(0)
            Parallel.chunks(pixels, Parallel.PIXELS_MAP) { _, _ -> calls.incrementAndGet() }
            assertEquals(1, calls.get(), "$pixels pixels must be one share at the per-pixel grain")
        }
    }

    @Test
    fun aThreadCapOfOneForcesEverythingInline() {
        Parallel.maxThreads = 1
        val caller = Thread.currentThread()
        val calls = AtomicInteger(0)
        val offThread = AtomicBoolean(false)
        Parallel.chunks(100_000, 1) { _, _ ->
            calls.incrementAndGet()
            if (Thread.currentThread() !== caller) offThread.set(true)
        }
        assertEquals(1, calls.get(), "maxThreads = 1 must never split")
        assertTrue(!offThread.get(), "maxThreads = 1 must never leave the calling thread")
    }

    @Test
    fun aCapBelowOneClampsToOneRatherThanDisablingTheLoop() {
        Parallel.maxThreads = 0
        assertEquals(1, Parallel.maxThreads)
        val calls = AtomicInteger(0)
        Parallel.chunks(5000, 1) { from, to -> if (from == 0 && to == 5000) calls.incrementAndGet() }
        assertEquals(1, calls.get(), "a clamped cap must still run the whole range once")
    }

    @Test
    fun anEmptyRangeRunsNothing() {
        Parallel.maxThreads = 8
        for (count in intArrayOf(0, -1, Int.MIN_VALUE)) {
            val calls = AtomicInteger(0)
            Parallel.chunks(count, 32) { _, _ -> calls.incrementAndGet() }
            assertEquals(0, calls.get(), "count=$count must not invoke the body at all")
        }
        val calls = AtomicInteger(0)
        Parallel.rows(0) { _, _ -> calls.incrementAndGet() }
        assertEquals(0, calls.get(), "a zero-height image must not invoke the body")
    }

    /** A grain below 1 would divide by zero when working out the share count. */
    @Test
    fun aNonPositiveGrainIsTreatedAsOne() {
        Parallel.maxThreads = 4
        for (per in intArrayOf(0, -5)) {
            val visits = IntArray(40)
            Parallel.chunks(40, per) { from, to -> for (i in from until to) visits[i]++ }
            for (i in visits.indices) assertEquals(1, visits[i], "per=$per index $i")
        }
    }

    private class ProbeFailure(val share: Int) : RuntimeException("share $share")

    /**
     * The exception must arrive as the *same object* that was thrown, from a share that ran on a pool
     * thread. Share 0 is never the inline one — [Parallel.chunks] keeps the last share for the caller
     * — so throwing there proves the value crossed a thread boundary rather than merely propagating up
     * a stack.
     */
    @Test
    fun anExceptionFromAPoolThreadReachesTheCallerUnwrapped() {
        Parallel.maxThreads = 8
        val caller = Thread.currentThread()
        val thrown = AtomicReference<ProbeFailure?>(null)
        val threwOffThread = AtomicBoolean(false)
        val caught = assertFailsWith<ProbeFailure> {
            Parallel.chunks(8 * 64, 64) { from, _ ->
                if (from == 0) {
                    if (Thread.currentThread() !== caller) threwOffThread.set(true)
                    val e = ProbeFailure(0)
                    thrown.set(e)
                    throw e
                }
            }
        }
        assertTrue(threwOffThread.get(), "share 0 was expected to run on a pool thread")
        assertSame(
            thrown.get(), caught,
            "the caller must see the thrown object itself, not an ExecutionException wrapping it",
        )
    }

    /**
     * A failing share must not abandon the others: the caller owns the destination buffer, and
     * returning while a worker is still writing into it hands the next stage a half-written image.
     */
    @Test
    fun everyShareFinishesBeforeTheFailureIsRethrown() {
        Parallel.maxThreads = 8
        val started = AtomicInteger(0)
        val finished = AtomicInteger(0)
        assertFailsWith<ProbeFailure> {
            Parallel.chunks(8 * 64, 64) { from, _ ->
                started.incrementAndGet()
                if (from == 0) throw ProbeFailure(0)
                // Long enough that a caller returning early would be observable.
                var acc = 0L
                for (i in 0 until 2_000_000) acc += i
                if (acc == -1L) throw ProbeFailure(-1)
                finished.incrementAndGet()
            }
        }
        assertTrue(started.get() > 1, "the range was expected to split, got ${started.get()} shares")
        assertEquals(
            started.get() - 1, finished.get(),
            "every share but the throwing one must have completed before the rethrow",
        )
    }

    /** Several shares failing must still surface exactly one of them, and it must be a real one. */
    @Test
    fun oneOfSeveralFailuresIsReportedAndItIsOneThatHappened() {
        Parallel.maxThreads = 8
        val created = java.util.Collections.synchronizedSet(java.util.HashSet<ProbeFailure>())
        val caught = assertFailsWith<ProbeFailure> {
            Parallel.chunks(8 * 64, 64) { from, _ ->
                val e = ProbeFailure(from)
                created.add(e)
                throw e
            }
        }
        assertTrue(created.size > 1, "every share was expected to throw, got ${created.size}")
        assertTrue(created.any { it === caught }, "the reported failure must be one of the ones thrown")
    }

    /**
     * The deadlock guard. A worker that queued nested work onto the pool it is running in would wait
     * for a thread that is itself waiting, as soon as every worker does the same — so a nested call
     * must be exactly one inline share on the thread that made it.
     */
    @Test
    fun aNestedCallFromAWorkerRunsInlineOnThatWorker() {
        Parallel.maxThreads = 8
        val caller = Thread.currentThread()
        val outerOnWorkers = AtomicInteger(0)
        val innerShares = AtomicInteger(0)
        val innerRanges = AtomicInteger(0)
        val movedThread = AtomicBoolean(false)
        Parallel.chunks(8 * 64, 64) { _, _ ->
            val self = Thread.currentThread()
            if (self !== caller) {
                outerOnWorkers.incrementAndGet()
                Parallel.chunks(1 shl 20, 1) { from, to ->
                    innerShares.incrementAndGet()
                    if (from == 0 && to == (1 shl 20)) innerRanges.incrementAndGet()
                    if (Thread.currentThread() !== self) movedThread.set(true)
                }
            }
        }
        assertTrue(outerOnWorkers.get() > 0, "no share ran on a pool thread; the test proved nothing")
        assertEquals(
            outerOnWorkers.get(), innerShares.get(),
            "a nested call must be exactly one share per caller, not a fresh dispatch",
        )
        assertEquals(
            outerOnWorkers.get(), innerRanges.get(),
            "the single nested share must cover the whole range",
        )
        assertTrue(!movedThread.get(), "nested work must not leave the worker that asked for it")
    }

    @Test
    fun rowsAndChunksAgreeAboutTheRangeTheyCover() {
        Parallel.maxThreads = 8
        val byRows = IntArray(500)
        val byChunks = IntArray(500)
        Parallel.rows(500, 32) { from, to -> for (y in from until to) byRows[y]++ }
        Parallel.chunks(500, 32) { from, to -> for (y in from until to) byChunks[y]++ }
        for (i in byRows.indices) {
            assertEquals(1, byRows[i], "rows() missed or repeated row $i")
            assertEquals(1, byChunks[i], "chunks() missed or repeated index $i")
        }
    }

    /**
     * Repeated calls must not accumulate state. This is the shape every stage uses — one `Parallel`
     * call per pass, several passes per image — and a latch or a failure holder that survived a call
     * would make the second pass hang or throw the first pass's exception.
     */
    @Test
    fun aFailedCallDoesNotPoisonTheNextOne() {
        Parallel.maxThreads = 8
        assertFailsWith<ProbeFailure> {
            Parallel.chunks(8 * 64, 64) { from, _ -> if (from == 0) throw ProbeFailure(0) }
        }
        val visits = IntArray(8 * 64)
        Parallel.chunks(8 * 64, 64) { from, to -> for (i in from until to) visits[i]++ }
        for (i in visits.indices) assertEquals(1, visits[i], "index $i after a failed call")
    }
}
