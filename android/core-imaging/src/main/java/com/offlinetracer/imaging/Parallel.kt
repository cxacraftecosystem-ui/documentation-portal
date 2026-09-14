package com.offlinetracer.imaging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/**
 * The engine's only parallelism primitive: split a row range (or any index range) across a shared
 * pool and block until every share is done.
 *
 * Design notes, because the obvious alternatives all have a specific failure this one avoids:
 *
 *  - **No coroutines.** `:core-imaging` has no dependency but the Kotlin stdlib, and a
 *    `Dispatchers.Default` launch per row band allocates a continuation per share for work that is
 *    purely CPU-bound and never suspends. `java.util.concurrent` is the right tool here.
 *  - **The caller runs the last share itself.** One fewer dispatch, and the calling thread stays
 *    busy instead of parked on a latch while a worker does its work.
 *  - **The pool is created lazily** and only holds `cpu - 1` workers, because the caller is the
 *    `cpu`-th runnable thread. A single-threaded run (a unit test, a 64×64 proxy) never starts a
 *    thread at all.
 *  - **Nested calls run inline.** A worker that blocks on the pool it is itself running in
 *    deadlocks the moment the pool is saturated, and splitting an inner loop that is already
 *    running on every core buys nothing.
 *  - **Exceptions are rethrown on the caller, unwrapped.** The pipeline signals cancellation with
 *    an exception, and wrapping it in an `ExecutionException` would make every call site unwrap by
 *    hand — one of them eventually would not, and a cancelled trace would surface as a crash.
 *
 * The inline path allocates nothing. The parallel path allocates a latch, a holder and one lambda
 * per share — O(threads) per call, not O(pixels).
 */
object Parallel {

    /**
     * Grain: the minimum work a single share must cover before a loop is split at all.
     *
     * These exist so the decision is made once, with the arithmetic written down, rather than as a
     * magic number at forty call sites. Note carefully what a grain does and does not control:
     * [chunks] caps the share count at [maxThreads], so a large image is cut into `maxThreads` roughly
     * equal shares **whatever the grain is**. The grain's only job is the yes/no question, *is this
     * worth dispatching at all* — it never makes the split finer or coarser on a real photograph.
     *
     * That is why raising a grain is nearly free and lowering one is not, and it is why both numbers
     * are pinned by the **small** end rather than the large one. The upper bound comes from cost:
     * dispatch is an `execute`, a latch count-down and a cache-cold destination on another core, so
     * single-digit microseconds a share, and a share sized at roughly a millisecond of work on one ARM
     * core buries it —
     *
     *  - [ROWS_NEIGHBOURHOOD]: 16 rows × 1200 px × 81 disc reads ≈ 1.6 M operations.
     *  - [ROWS_KERNEL]: 32 rows × 1200 px × 13 taps ≈ 500 k operations.
     *  - [PIXELS_MAP]: 65 536 pixels × one `exp`/`pow`/`tanh` ≈ 65 k transcendentals.
     *
     * The lower bound — the one that actually chose these values — is that **the cross-engine parity
     * fixtures must run inline**. They are 24×18 and 1×1 (ALGORITHMS §14), so `count / grain` must
     * come out as 0 or 1 for an 18-row, 24-column, 432-pixel image at every grain here: 18/16, 18/32,
     * 24/32 and 432/65536 all do. A grain of 8 rows would have split those 18 rows into two shares for
     * a few microseconds of work — pure overhead, and it would put a thread pool in the stack trace of
     * the one test whose whole value is being reproducible.
     *
     * Nothing real is lost at the other end. The smallest image any of these stages meets in the
     * pipeline is a 64×64 proxy, whose row loops still split (2 shares at [ROWS_KERNEL], 4 at
     * [ROWS_NEIGHBOURHOOD]); at the 2048 px working resolution every grain here — [PIXELS_MAP]
     * included — is using every core by row 256. `ParallelTest` asserts the fixture sizes stay inline
     * and `ParallelBitIdentityTest` asserts its own 384×352 image does *not*, so a grain raised far
     * enough to make that safety net vacuous fails loudly instead of passing quietly.
     */
    const val ROWS_NEIGHBOURHOOD = 16

    /**
     * Grain for a body that reads a 1-D kernel, a fixed handful of neighbours, or one line of the
     * image: separable convolution, morphology, the distance transform's envelope passes, resampling
     * and non-maximum suppression. See [ROWS_NEIGHBOURHOOD] for the calibration.
     */
    const val ROWS_KERNEL = 32

    /**
     * Grain for an index-range loop whose body is a per-pixel arithmetic map with real cost in it —
     * a transcendental, a divide-and-round chain, a colour-space matrix. Plain one-flop maps
     * (`out[i] = 1 - in[i]`) are deliberately **not** split at all: they run at memory speed on one
     * core and adding cores adds only dispatch. See [ROWS_NEIGHBOURHOOD] for the calibration.
     */
    const val PIXELS_MAP = 65536

    /** Marker type so [chunks] can recognise its own workers without a `ThreadLocal` lookup. */
    private class Worker(target: Runnable) : Thread(target, "offline-tracer-worker") {
        init {
            isDaemon = true
        }
    }

    private val cpuCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    @Volatile
    private var threadCap: Int = cpuCount

    /**
     * Upper bound on the number of shares a single [chunks] call is split into. Defaults to
     * `availableProcessors()`. Setting it to 1 forces every call inline, which is what tests that
     * need a deterministic single-threaded stack trace should do. Values below 1 clamp to 1.
     */
    var maxThreads: Int
        get() = threadCap
        set(value) {
            threadCap = if (value < 1) 1 else value
        }

    private val pool: ExecutorService by lazy {
        Executors.newFixedThreadPool(if (cpuCount > 1) cpuCount - 1 else 1) { r: Runnable -> Worker(r) }
    }

    /**
     * Splits `0 until height` into row bands of at least [minRowsPerTask] rows and runs [body] on
     * each, returning once all of them have finished.
     *
     * `body(from, to)` receives a half-open row range. Returns immediately for a non-positive
     * [height].
     */
    fun rows(height: Int, minRowsPerTask: Int = 32, body: (from: Int, to: Int) -> Unit) {
        chunks(height, minRowsPerTask, body)
    }

    /**
     * Splits `0 until count` into at most [maxThreads] contiguous shares of at least [minPerTask]
     * items and runs [body] on each, returning once all of them have finished.
     *
     * Shares differ in length by at most one item, so no worker gets a sliver. [body] is invoked on
     * several threads at once and must therefore be safe to call concurrently on disjoint ranges.
     * The first exception thrown by any share is rethrown to the caller (unwrapped) after every
     * share has finished. Returns immediately for a non-positive [count].
     */
    fun chunks(count: Int, minPerTask: Int, body: (from: Int, to: Int) -> Unit) {
        if (count <= 0) return
        val per = if (minPerTask < 1) 1 else minPerTask
        var tasks = count / per
        val cap = threadCap
        if (tasks > cap) tasks = cap
        if (tasks <= 1 || Thread.currentThread() is Worker) {
            body(0, count)
            return
        }

        val base = count / tasks
        val extra = count % tasks
        val latch = CountDownLatch(tasks - 1)
        val failure = AtomicReference<Throwable?>()
        var from = 0
        for (t in 0 until tasks) {
            val to = from + base + if (t < extra) 1 else 0
            if (t == tasks - 1) {
                // Last share inline; it runs after every other share has been queued, so the pool
                // is never idle while this thread works.
                runShare(body, from, to, failure)
            } else {
                val shareFrom = from
                val shareTo = to
                try {
                    pool.execute {
                        try {
                            runShare(body, shareFrom, shareTo, failure)
                        } finally {
                            latch.countDown()
                        }
                    }
                } catch (rejected: RejectedExecutionException) {
                    // The pool refused the work; running it here is slower but still correct, and
                    // an unbalanced count-down would hang the caller forever.
                    runShare(body, shareFrom, shareTo, failure)
                    latch.countDown()
                }
            }
            from = to
        }

        awaitQuietly(latch)
        val err = failure.get()
        if (err != null) throw err
    }

    private fun runShare(
        body: (Int, Int) -> Unit,
        from: Int,
        to: Int,
        failure: AtomicReference<Throwable?>,
    ) {
        try {
            body(from, to)
        } catch (t: Throwable) {
            // First failure wins; later ones are dropped rather than overwriting the cause the
            // caller will actually see.
            failure.compareAndSet(null, t)
        }
    }

    /**
     * Waits for every dispatched share even if this thread is interrupted: returning early would
     * hand the caller a half-written destination buffer. The interrupt is re-asserted so the
     * caller's own cancellation check still sees it.
     */
    private fun awaitQuietly(latch: CountDownLatch) {
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
