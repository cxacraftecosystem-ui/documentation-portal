package com.offlinetracer.pipeline

import com.offlinetracer.imaging.RgbaImage

data class BatchItem(val id: String, val label: String)

data class BatchOutcome(val id: String, val ok: Boolean, val message: String, val millis: Long)

object Batch {

    fun run(
        items: List<BatchItem>,
        params: TraceParams,
        load: (BatchItem) -> RgbaImage?,
        save: (BatchItem, TraceResult) -> Unit,
        progress: (Int, Int, BatchItem) -> Unit,
        cancel: CancellationToken = CancellationToken(),
    ): List<BatchOutcome> {
        val total = items.size
        val outcomes = ArrayList<BatchOutcome>(total)
        if (total == 0) return outcomes

        val sanitized = params.sanitized()

        var index = 0
        var cancelled = false
        while (index < total) {
            val item = items[index]
            index++

            if (cancelled || cancel.isCancelled) {
                cancelled = true
                outcomes.add(
                    BatchOutcome(item.id, false, "Not started: the batch was cancelled.", 0L)
                )
                continue
            }

            progress(index - 1, total, item)
            val started = System.nanoTime()
            try {
                val image = load(item)
                if (image == null) {
                    outcomes.add(
                        BatchOutcome(
                            item.id,
                            false,
                            "Could not read \"${item.label}\" as an image, so it was skipped.",
                            millisSince(started),
                        )
                    )
                    continue
                }
                val result = Pipeline.run(image, sanitized, null, cancel, classify = false)
                save(item, result)
                outcomes.add(
                    BatchOutcome(
                        item.id,
                        true,
                        summarise(result),
                        millisSince(started),
                    )
                )
            } catch (e: CancelledException) {
                cancelled = true
                outcomes.add(
                    BatchOutcome(item.id, false, "Cancelled part-way through.", millisSince(started))
                )
            } catch (t: Throwable) {
                outcomes.add(
                    BatchOutcome(item.id, false, describe(item, t), millisSince(started))
                )
            }
        }
        return outcomes
    }

    fun failureCount(outcomes: List<BatchOutcome>): Int {
        var n = 0
        for (o in outcomes) if (!o.ok) n++
        return n
    }

    private fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    private fun summarise(result: TraceResult): String {
        val shapes = result.document.shapeCount()
        val noteCount = result.notes.size
        val base = "Traced $shapes ${if (shapes == 1) "path" else "paths"} in ${result.totalMillis} ms"
        return if (noteCount == 0) {
            "$base."
        } else {
            "$base, with $noteCount ${if (noteCount == 1) "note" else "notes"}: ${result.notes[0]}"
        }
    }

    private fun describe(item: BatchItem, t: Throwable): String {
        val name = t::class.simpleName ?: "error"
        val message = t.message
        val detail = if (message.isNullOrBlank()) name else "$name: ${message.trim()}"
        return "Failed on \"${item.label}\": $detail"
    }
}
