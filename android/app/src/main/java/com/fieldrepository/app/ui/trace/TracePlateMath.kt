package com.fieldrepository.app.ui.trace

/**
 * **THE COMPARISON'S ARITHMETIC AND ITS SENTENCES, WITH NO ANDROID IN THEM.**
 *
 * `TracePlates.kt` says why it cannot be tested — there is no Robolectric in this module, so anything
 * touching `android.graphics` is by construction out of a unit test's reach — and then says what
 * follows from that: *"the channel arithmetic that could be quietly wrong lives next door … What is
 * left here is allocation, iteration and drawing — the parts that fail loudly or not at all."*
 *
 * This is next door. Three things live here and each is something that can be wrong while everything
 * still runs:
 *
 *  1. **The box filter** the photograph plate is reduced with. An off-by-one in the band arithmetic
 *     aliases a pencil sketch into a dotted line, and a researcher comparing a dotted photograph
 *     against a clean trace concludes the TRACE invented strokes.
 *  2. **The difference plate**, which has no reference implementation to be checked against — only a
 *     definition, stated below in one place so the two clients can agree on it in words.
 *  3. **The sentences the comparator has to be able to say**, including the ones about what it is NOT
 *     showing. An empty area indistinguishable from a place with no records is this repository's
 *     most-repeated bug class.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The box filter
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * An averaged channel, rounded the way a `Uint8ClampedArray` rounds.
 *
 * **ROUND HALF TO EVEN, AND NOT TRUNCATION, BECAUSE THE OTHER CLIENT DOES.** The web writes
 * `out[at] = r / n` into a `Uint8ClampedArray`, and that assignment is not a cast: ECMA-262's
 * `ToUint8Clamp` rounds, and rounds a tie to the even neighbour. An integer division here would floor
 * instead, so a box averaging to 127.5 would be 127 on a handset and 128 in a browser — one count,
 * invisible, on every pixel of every plate, forever, and the kind of difference that is discovered by
 * somebody diffing two screenshots a year later and concluding the two engines disagree.
 *
 * All-integer, so there is no floating-point rounding of its own to argue about: `2 * remainder`
 * against `n` is the same comparison as `remainder / n` against one half.
 */
fun traceClampedAverage(sum: Long, count: Int): Int {
    if (count <= 0) return 0
    if (sum <= 0L) return 0
    val floor = sum / count
    val twiceRemainder = 2L * (sum - floor * count)
    val rounded = when {
        twiceRemainder > count -> floor + 1L
        twiceRemainder < count -> floor
        (floor and 1L) == 1L -> floor + 1L
        else -> floor
    }
    return rounded.coerceIn(0L, 255L).toInt()
}

/**
 * One destination row of the box filter, as packed opaque ARGB written into [out].
 *
 * ── THE BOX BOUNDARIES ARE THE WEB'S, AND THE REASON IS THE SAME ──────────────────────────────
 *
 * The far edge is floored from the NEXT destination row rather than stepped from this one, so
 * consecutive boxes tile the source exactly: no source pixel is read twice and none is skipped, and
 * rounding cannot accumulate down the frame and leave the last row reading past the end of the
 * buffer. The `maxOf(y0 + 1, …)` covers a destination larger than the source in one axis only.
 *
 * ── BOX AVERAGE AND NOT NEAREST NEIGHBOUR ─────────────────────────────────────────────────────
 *
 * A photograph of a pencil sketch is exactly the content that aliases: one-pixel strokes on paper
 * grain, sampled at a third of the frequency, come back as a dotted line. The cost is one pass over
 * the source, which is already in memory.
 *
 * ── ALPHA IS NOT AVERAGED; THE ROW IS OPAQUE ──────────────────────────────────────────────────
 *
 * The web averages the fourth channel too, and for it that is free. Here the answer is a layer of a
 * comparator, and a translucent photograph layer shows the layer beneath it — which is the failure the
 * white-on-the-trace-plate rule describes for the OTHER plate and which is no better on this one. The
 * source's own alpha is deliberately not read: the engine traced whatever these pixels are.
 *
 * @param src RGBA, `srcWidth * srcHeight * 4` bytes of it.
 * @param out at least [plateWidth] ints; only that many are written.
 */
fun traceResampleRow(
    src: ByteArray,
    srcWidth: Int,
    srcHeight: Int,
    plateWidth: Int,
    plateHeight: Int,
    py: Int,
    out: IntArray,
) {
    if (srcWidth < 1 || srcHeight < 1 || plateWidth < 1 || plateHeight < 1) return

    val sy0 = (py.toLong() * srcHeight / plateHeight).toInt()
    val sy1 = maxOf(sy0 + 1, ((py + 1).toLong() * srcHeight / plateHeight).toInt())
    for (px in 0 until plateWidth) {
        val sx0 = (px.toLong() * srcWidth / plateWidth).toInt()
        val sx1 = maxOf(sx0 + 1, ((px + 1).toLong() * srcWidth / plateWidth).toInt())
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (sy in sy0 until minOf(sy1, srcHeight)) {
            var index = (sy * srcWidth + sx0) * 4
            for (sx in sx0 until minOf(sx1, srcWidth)) {
                r += (src[index].toInt() and 0xFF).toLong()
                g += (src[index + 1].toInt() and 0xFF).toLong()
                b += (src[index + 2].toInt() and 0xFF).toLong()
                index += 4
                n += 1
            }
        }
        out[px] = if (n == 0) {
            // Unreachable while the two guards above hold, and black rather than a guess anyway: a
            // transparent or white pixel here would be a hole in a photograph that read as paper.
            0xFF000000.toInt()
        } else {
            (0xFF shl 24) or
                (traceClampedAverage(r, n) shl 16) or
                (traceClampedAverage(g, n) shl 8) or
                traceClampedAverage(b, n)
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The difference plate
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One row of the difference plate: the two layers subtracted from each other.
 *
 * ── THE DEFINITION, WRITTEN DOWN BECAUSE TWO CLIENTS HAVE TO SHARE IT ─────────────────────────
 *
 * **ABSOLUTE DIFFERENCE PER CHANNEL.** Red, green and blue are each subtracted independently and the
 * sign is dropped; the result is opaque. It is NOT a luminance difference, and that is a decision
 * rather than a convenience: a luminance difference needs a set of weights, there are at least two
 * standard sets in common use, and the day the two clients picked different ones the difference plate
 * would disagree between a laptop and a handset with nothing on either screen to say which was right.
 * An absolute per-channel difference has exactly one definition, needs no colour-space opinion, and is
 * what every image editor's "difference" blend already means — so a researcher who has met one has met
 * this.
 *
 * ── WHAT IT SHOWS, WHICH IS WHY IT IS WORTH A FOURTH CHIP ─────────────────────────────────────
 *
 * The wipe answers "is the line there" one strip at a time; this answers it everywhere at once. A
 * stroke the trace reproduced is dark in the photograph and black on the plate, so it comes out near
 * black — agreement reads as nothing. A stroke the trace MISSED is dark in the photograph and white on
 * the plate, and a stroke the trace INVENTED is the reverse; both come out bright. The paper's own tone
 * becomes an even dim grey, because paper is not quite white and the plate is.
 *
 * @param width how many entries of each row to read and write. All three arrays must hold that many.
 */
fun traceDifferenceRow(photograph: IntArray, trace: IntArray, out: IntArray, width: Int) {
    for (x in 0 until width) {
        val p = photograph[x]
        val t = trace[x]
        val r = Math.abs(((p ushr 16) and 0xFF) - ((t ushr 16) and 0xFF))
        val g = Math.abs(((p ushr 8) and 0xFF) - ((t ushr 8) and 0xFF))
        val b = Math.abs((p and 0xFF) - (t and 0xFF))
        out[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The sentences
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What the comparator says about the difference mode, once, under the frame.
 *
 * It says what the picture MEANS rather than what was done to it, because "absolute difference per
 * channel" is a sentence for the two people maintaining the arithmetic and this one is for the
 * researcher holding the sheet.
 */
const val TRACE_DIFFERENCE_NOTE: String =
    "Difference subtracts the two pictures from each other: black where they agree, bright where " +
        "they do not. A line the trace missed and a line it invented both come out bright, and the " +
        "paper's own tone shows as an even dim grey."

/**
 * What the comparator says when the difference plate could not be allocated.
 *
 * ITS OWN SENTENCE RATHER THAN [TraceFailureKind.OUT_OF_MEMORY]'s, because the remedies are different
 * in kind. That one sends a researcher to change the trace and run it again; this one costs them
 * nothing they had — the drawing, the wipe and the two whole pictures are all still there, and the only
 * honest thing to say is which single view is missing.
 */
const val TRACE_DIFFERENCE_REFUSAL: String =
    "This phone could not make room for the difference picture. The wipe and the two whole " +
        "pictures still work, and the drawing is unaffected."

/**
 * What the comparator says while the difference plate is being worked out.
 *
 * HOISTED OUT OF THE COMPARATOR INTO THIS FILE, WHICH IS THE POINT. As a literal inside a composable
 * nothing could reach it — not a unit test, and not a parity test that compares the two clients' copy.
 */
const val TRACE_DIFFERENCE_PENDING: String = "Working out the difference picture…"

/**
 * How the difference picture is described to TalkBack.
 *
 * IT REPLACES THE SEAM PROPORTION RATHER THAN JOINING IT. In every other mode the frame reads out what
 * is on screen and in what proportion; here there is one picture rather than two laid over each other,
 * so a proportion would be a number about nothing. What it says instead is what the picture MEANS —
 * dark is agreement, bright is disagreement — because that is the whole content of the view and a
 * sighted researcher takes it off the plate in a second.
 *
 * NAMING THE OPERATION IS NOT ENOUGH: "the difference between the drawing and the photograph" is true,
 * and leaves a reader who cannot see the plate with no way to read it.
 */
const val TRACE_DIFFERENCE_DESCRIPTION: String =
    "The traced drawing and the photograph subtracted from each other. Dark where they agree, " +
        "bright where they differ."

/**
 * The word written on the picture while the difference is showing, and the name of the chip that gets
 * there.
 *
 * ONE CONSTANT FOR BOTH, because they are the same word on purpose: the picture and the pressed control
 * name each other, and a researcher who presses "Difference" and sees a badge saying anything else has
 * been shown two names for one thing.
 *
 * WHY THE PICTURE IS BADGED AT ALL, WHERE THE TWO WHOLE VIEWS ARE NOT. A difference plate of a GOOD
 * trace is very nearly black, because near-black is what agreement looks like. A nearly black frame
 * carrying no word is indistinguishable from a plate that failed to draw, and the honest reading of it
 * is that the trace is broken — the exact opposite of what it is telling them.
 */
const val TRACE_DIFFERENCE_LABEL: String = "Difference"

/**
 * The clause about the white, said next to the comparator rather than only on the export step.
 *
 * The web says it under its comparator, and the handset said it only as the note on the "White
 * background" toggle — which lives in the EXPORT group, behind the disclosure, on a different part of
 * the panel. A researcher who never opens that step sees a white drawing over their photograph with no
 * explanation for it, and the obvious conclusion is that the trace has flooded their sheet.
 */
const val TRACE_COMPARE_WHITE_NOTE: String =
    "The comparison paints the drawing on white so it is visible over the photograph; the file " +
        "that is attached or downloaded keeps whatever background you chose."

/**
 * That the two pictures in the comparator are smaller than the drawing, when they are.
 *
 * The web's plate builder carries a `reduced` flag whose entire documentation is *"Say so on screen"*.
 * Without it a researcher judging lost line weight at 1024 against a 4096 trace cannot tell whether the
 * loss is the trace's or the plate's — which is the one question this comparator exists to answer,
 * asked about the comparator itself.
 *
 * @return an empty string when the plates are the traced size, so a caller can print it unconditionally.
 */
fun traceComparisonReduction(
    plateWidth: Int,
    plateHeight: Int,
    tracedWidth: Int,
    tracedHeight: Int,
): String {
    if (plateWidth < 1 || plateHeight < 1 || tracedWidth < 1 || tracedHeight < 1) return ""
    if (plateWidth >= tracedWidth && plateHeight >= tracedHeight) return ""
    return "Both pictures here are $plateWidth×$plateHeight, reduced from $tracedWidth×" +
        "$tracedHeight for the comparison only."
}

/**
 * Why there is no comparison on screen, in its own words for each way that happens.
 *
 * ── AN ABSENCE IS A SENTENCE, NOT AN EMPTY SPACE ──────────────────────────────────────────────
 *
 * A panel that composes the comparator only when a result exists and puts NOTHING in its place — no
 * card, no placeholder, no sentence — leaves an empty area, and an empty area is indistinguishable
 * from a place with no records. That is the failure this repository repeats most often.
 *
 * ── THE BRANCH ORDER IS LOAD-BEARING ──────────────────────────────────────────────────────────
 *
 * Plates on screen win over everything, because whatever else is true the researcher can see something.
 * A plate refusal comes before "tracing", because a refusal that was replaced by a spinner would never
 * be read. And the failed branch POINTS AT the red message rather than repeating it: two copies of one
 * fault in one panel is how a researcher ends up believing there are two.
 *
 * @param hasPlates whether the comparator itself is on screen.
 * @param plateRefusal the sentence from a trace that succeeded and whose plates did not, or empty.
 * @return an empty string when the comparator is on screen and current — the one state that needs no
 *   caption at all.
 */
fun traceComparisonStatus(
    hasPlates: Boolean,
    running: Boolean,
    failed: Boolean,
    plateRefusal: String,
    hasResult: Boolean,
): String = when {
    // Gestures really are off for the duration (the panel disables them), so this says why. A control
    // that stops answering with nothing on screen to explain it is, for those few seconds,
    // indistinguishable from a frozen one.
    hasPlates && running ->
        "A newer trace is running. This is the last finished one, and its controls come back when " +
            "the new drawing lands."

    hasPlates -> ""

    plateRefusal.isNotBlank() -> plateRefusal

    running -> "Tracing… the comparison appears here when it finishes."

    failed -> "The trace did not finish, so there is nothing to compare. The reason is above."

    !hasResult -> "The comparison appears as soon as the first trace finishes."

    // A result with no plates and no refusal is a state nothing constructs. Said rather than left
    // blank, because a blank here is the very thing this function exists to remove.
    else -> "There is no comparison for this drawing."
}

/**
 * Why the two comparison pictures could not be drawn, for a trace that itself succeeded.
 *
 * ── A DISPLAY ARTEFACT MUST NOT DESTROY THE ARCHIVE ARTEFACT ──────────────────────────────────
 *
 * This sentence exists because building the plates inside the run means a frame mismatch or a failed
 * allocation throws, becomes a whole-run refusal, and discards a finished SVG. That is a picture nobody
 * attaches killing the one thing that reaches a record, on the device least able to allocate two
 * 1024 px bitmaps. So the plates are built AFTER the run, both refusals say the drawing survived, and
 * they say it before naming the remedy.
 */
const val TRACE_PLATE_MEMORY_REFUSAL: String =
    "This phone could not make room for the two comparison pictures, so there is nothing to " +
        "compare the drawing against. The drawing itself is unaffected and can still be attached — " +
        "set the trace resolution to Fast if you want the comparison as well."
