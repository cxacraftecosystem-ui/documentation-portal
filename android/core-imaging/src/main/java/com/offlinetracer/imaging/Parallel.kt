package com.offlinetracer.imaging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

object Parallel {

    const val ROWS_NEIGHBOURHOOD = 16

    const val ROWS_KERNEL = 32

    const val PIXELS_MAP = 65536

    private class Worker(target: Runnable) : Thread(target, "offline-tracer-worker") {
        init {
            isDaemon = true
        }
    }

    private val cpuCount: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    @Volatile
    private var threadCap: Int = cpuCount

    var maxThreads: Int
        get() = threadCap
        set(value) {
            threadCap = if (value < 1) 1 else value
        }

    private val pool: ExecutorService by lazy {
        Executors.newFixedThreadPool(if (cpuCount > 1) cpuCount - 1 else 1) { r: Runnable -> Worker(r) }
    }

    fun rows(height: Int, minRowsPerTask: Int = 32, body: (from: Int, to: Int) -> Unit) {
        chunks(height, minRowsPerTask, body)
    }

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
            failure.compareAndSet(null, t)
        }
    }

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
