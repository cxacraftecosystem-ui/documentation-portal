package com.offlinetracer.pipeline

import com.offlinetracer.imaging.RgbaImage

/** One thing to trace. [label] is what a failure message names, so it should be human-recognisable. */
data class BatchItem(val id: String, val label: String)

/**
 * What happened to one [BatchItem].
 *
 * @param ok whether the item was traced **and** saved.
 * @param message a sentence: what went wrong, or a short summary when it went right. Never blank, so
 *   the caller never has to invent text for a row.
 * @param millis wall-clock time spent on this item, including loading and saving.
 */
data class BatchOutcome(val id: String, val ok: Boolean, val message: String, val millis: Long)

/**
 * Runs a trace over many images.
 *
 * The contract that matters here is small and absolute: **one bad item never stops the run.** A folder
 * of two hundred scans reliably contains at least one truncated JPEG, one file the user renamed from
 * `.heic`, and one 20 000 × 20 000 px monster that exhausts the heap. Losing the other hundred and
 * ninety-seven of them to any of those is not acceptable, so every item is attempted inside its own
 * guard, the failure is recorded against that item, and the loop continues.
 *
 * Two consequences the caller has to honour:
 *
 *  - The returned list always has **exactly one outcome per input item**, in input order, including for
 *    items that were never attempted because the run was cancelled. That is what lets a UI render the
 *    result as a table without reconciling two lists of different lengths.
 *  - The caller is required to show the failed count rather than reporting "done". A batch that saved
 *    197 of 200 files and said "done" is a batch that quietly lost three files, and the user finds out
 *    weeks later.
 */
object Batch {

    /**
     * @param items the work list; an empty list returns an empty result and calls nothing.
     * @param load opens an item's pixels, or returns `null` when it cannot. Returning `null` and
     *   throwing are both handled; `null` is for the expected "not an image" case and throwing is for
     *   the unexpected.
     * @param save persists a finished trace. Allowed to throw — a disk that fills up halfway through a
     *   batch is recorded per item exactly like a corrupt input, because the remaining items may well
     *   be smaller and still succeed.
     * @param progress called **before** each item is attempted, as `(index, total, item)` with a
     *   zero-based index, so a UI can name the file it is working on rather than the one it just
     *   finished.
     * @param cancel checked before each item and threaded into each trace.
     * @return one [BatchOutcome] per item, in input order.
     */
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

        // Sanitised once for the whole batch rather than once per item: two hundred identical clamps
        // produce two hundred identical results, and doing it here means every item provably ran with
        // the same parameters — which is the only reason to batch at all.
        val sanitized = params.sanitized()

        var index = 0
        var cancelled = false
        while (index < total) {
            val item = items[index]
            index++

            if (cancelled || cancel.isCancelled) {
                cancelled = true
                // Recorded, not omitted. A caller that sees 200 items and 47 outcomes cannot tell
                // "cancelled" from "crashed", and those need different words in front of the user.
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
                // The user's own decision, not a failure of this file, and it applies to the whole run.
                cancelled = true
                outcomes.add(
                    BatchOutcome(item.id, false, "Cancelled part-way through.", millisSince(started))
                )
            } catch (t: Throwable) {
                // Throwable and not Exception, on purpose. The realistic failure in a large batch is an
                // OutOfMemoryError on one oversized file, and the whole reason this loop exists is that
                // the next file is probably 4 MP and will trace perfectly. Continuing after an Error is
                // normally indefensible; here abandoning 199 successful items to honour that principle
                // is the greater harm, and each item's work is independent so there is no shared state
                // left inconsistent.
                outcomes.add(
                    BatchOutcome(item.id, false, describe(item, t), millisSince(started))
                )
            }
        }
        return outcomes
    }

    /** Convenience for the caller's summary line: how many items failed. */
    fun failureCount(outcomes: List<BatchOutcome>): Int {
        var n = 0
        for (o in outcomes) if (!o.ok) n++
        return n
    }

    private fun millisSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    /**
     * A one-line summary of a successful trace, which carries the trace's own notes forward.
     *
     * A batch is the one place a user never sees the per-image notes, so the count of caps applied has
     * to survive into the row: "traced 318 paths (2 notes)" is enough to make somebody click, whereas
     * "ok" is not, and the notes are where "the matte deleted your artwork" lives.
     */
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
