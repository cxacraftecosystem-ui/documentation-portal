package com.fieldrepository.app.ui.trace

import androidx.compose.runtime.Immutable

/**
 * **WHICH REGION OF THE PHOTOGRAPH THE ENGINE IS ALLOWED TO SEE — the arithmetic, and the words.**
 *
 * ── THE PHOTOGRAPH IS NEVER ALTERED, AND THAT IS THE POINT RATHER THAN A COURTESY ─────────────
 *
 * This file changes exactly one thing, which is which pixels the TRACE is run on. The image the host
 * handed this panel is untouched — byte for byte, with its EXIF and its own checksum — from the moment
 * it was captured to whenever somebody opens it again. This panel never opens it for writing, never
 * re-encodes it and never hands a second Uri back for it.
 *
 * That is property 1 of the whole feature, and it is what lets the crop exist at all. A crop tool that
 * wrote a cropped photograph would be a second upload path — one this application would then have to
 * keep working offline, with its own retry and its own draft-store entry, in an application whose
 * entire premise is working offline. What leaves this panel is ONE derived file: the line art.
 *
 * ── AND WHY EVERY CROP IS TAKEN AFRESH FROM THE WHOLE DECODE ──────────────────────────────────
 *
 * [traceCropRgba] never writes into its source, and the panel never replaces the decoded frame with a
 * cropped one. Widening the frame back out has to be possible: a researcher who pulled the box in too
 * far and then could not get the edge of the sheet back would have to close the panel, re-choose the
 * photograph and start again — and on a handset, in a village, that is the point at which they attach
 * the photograph untraced instead.
 *
 * ── WHERE THE NUMBERS LIVE ────────────────────────────────────────────────────────────────────
 *
 * A crop rectangle only means something beside the frame it was aimed in, so [TraceFrameChoice] carries
 * both. The frame a researcher aims in is the one the engine will be handed — the decode's own working
 * size, [traceWorkingSize] of the photograph's stored dimensions — so the four numbers on screen are
 * the same four numbers that end up in the provenance sentence, and nobody has to reason about two
 * coordinate systems. When the decode nevertheless comes back at a different size (see
 * `traceDecodeForTrace`, whose exact resize is allowed to fail and return the subsampled frame
 * instead), [traceCropIn] maps the rectangle proportionally rather than clamping it into a frame it was
 * not drawn in — which would silently trace a different region from the one on screen.
 *
 * ── NO ANDROID IN THIS FILE ───────────────────────────────────────────────────────────────────
 *
 * `TracePlates.kt`'s header states the split and it holds here: everything that could be quietly wrong
 * is arithmetic and wording, so it lives where a JUnit test can reach it. There is no Robolectric in
 * this module. `TraceCropTest` pins the clamp order, the row copy and both sentences.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The rectangle
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A crop box in the frame's own pixels. [x] and [y] are the near edge; the far edge is exclusive.
 *
 * Mirrors the web's `CropRect` field for field, including the exclusive far edge, because the two
 * clients write the same sentence about the same rectangle into the same archive.
 */
@Immutable
data class TraceCropRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * A crop, and the frame it was aimed in.
 *
 * Both halves or neither. A rectangle carried without its frame is a rectangle nobody can re-clamp,
 * re-scale or describe, and the first thing that happens to a crop on this client is that it meets a
 * decode which is allowed to disagree about its own size.
 */
@Immutable
data class TraceFrameChoice(
    val rect: TraceCropRect,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * The smallest crop the panel allows, on either edge.
 *
 * The web's number and its reason, unchanged: not zero and not one, because a crop of a few pixels is
 * never a thing anybody meant, and every stage of the trace downstream reads global statistics off the
 * frame — an Otsu threshold over sixteen pixels is arithmetic on noise. 16 is small enough never to be
 * in anybody's way and large enough that the result is still an image.
 */
const val TRACE_CROP_MIN_EDGE_PX: Int = 16

/** @return the crop that is the whole frame — what "no crop" is, spelled as a rectangle. */
fun traceWholeFrame(width: Int, height: Int): TraceCropRect =
    TraceCropRect(0, 0, maxOf(1, width), maxOf(1, height))

/**
 * Force [rect] to be a usable crop of a [width] by [height] frame.
 *
 * INTEGER, INSIDE THE FRAME, AND AT LEAST [TRACE_CROP_MIN_EDGE_PX] ON EACH EDGE.
 *
 * **THE ORDER MATTERS AND IT IS THE WEB'S ORDER.** The size is clamped to the frame FIRST, then the
 * origin is clamped so the box still fits, then the size is trimmed again for the case where the frame
 * itself is smaller than the minimum. Clamping the origin first lets a large box push itself back off
 * the far edge — a rectangle that reads pixels which are not there, which on this client is a row copy
 * running past the end of a `ByteArray`.
 */
fun traceClampCrop(rect: TraceCropRect, width: Int, height: Int): TraceCropRect {
    val frameW = maxOf(1, width)
    val frameH = maxOf(1, height)
    val minW = minOf(TRACE_CROP_MIN_EDGE_PX, frameW)
    val minH = minOf(TRACE_CROP_MIN_EDGE_PX, frameH)

    val w = rect.width.coerceIn(minW, frameW)
    val h = rect.height.coerceIn(minH, frameH)
    val x = rect.x.coerceIn(0, frameW - w)
    val y = rect.y.coerceIn(0, frameH - h)
    return TraceCropRect(x, y, w, h)
}

/** True when [rect] is the entire frame, i.e. when there is nothing to say about a crop. */
fun traceIsWholeFrame(rect: TraceCropRect, width: Int, height: Int): Boolean {
    val whole = traceWholeFrame(width, height)
    return rect.x == 0 && rect.y == 0 && rect.width == whole.width && rect.height == whole.height
}

/**
 * [choice] as a rectangle of a [width] by [height] frame, scaled when the two frames differ.
 *
 * A NO-OP ON THE ORDINARY PATH, and the ordinary path is every trace where the decode came back at the
 * size the panel predicted. The scaling exists for the one case that is written down rather than
 * assumed: the decode's exact resize is allowed to fail, and when it does it returns the power-of-two
 * subsampled frame instead. A rectangle drawn on a 4096-wide preview and then clamped — not scaled —
 * into a 2048-wide decode would trace the top-left quarter of what the researcher framed, and nothing
 * on screen would say so.
 *
 * Proportional and then clamped, so the answer is always a legal crop of the frame it is for.
 */
fun traceCropIn(choice: TraceFrameChoice, width: Int, height: Int): TraceCropRect {
    if (choice.frameWidth < 1 || choice.frameHeight < 1 || width < 1 || height < 1) {
        return traceWholeFrame(maxOf(1, width), maxOf(1, height))
    }
    if (choice.frameWidth == width && choice.frameHeight == height) {
        return traceClampCrop(choice.rect, width, height)
    }
    val sx = width.toDouble() / choice.frameWidth.toDouble()
    val sy = height.toDouble() / choice.frameHeight.toDouble()
    val scaled = TraceCropRect(
        x = Math.round(choice.rect.x * sx).toInt(),
        y = Math.round(choice.rect.y * sy).toInt(),
        width = Math.round(choice.rect.width * sx).toInt(),
        height = Math.round(choice.rect.height * sy).toInt(),
    )
    return traceClampCrop(scaled, width, height)
}

/* ────────────────────────────────────────────────────────────────────────────
 * Moving it
 * ──────────────────────────────────────────────────────────────────────────── */

/** Which handle is being dragged. Named by where it sits, which is what its spoken label says. */
enum class TraceCropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * [rect] with one corner moved by [dx] and [dy] frame pixels.
 *
 * **THE MOVED EDGES ARE CLAMPED, NOT THE FINISHED RECTANGLE.** Building a rectangle first and handing
 * it to [traceClampCrop] is the obvious version and it is wrong in a way that only shows up under a
 * finger: dragging the left handle past the right one produces a negative width, which the clamp turns
 * into a minimum-width box at the ORIGINAL x — so the box jumps sideways away from the handle that is
 * being dragged. Clamping each moving edge against its opposite keeps the two corners the researcher is
 * not touching exactly where they are, which is the only behaviour a drag can have and still be aiming.
 */
fun traceMoveCorner(
    rect: TraceCropRect,
    corner: TraceCropCorner,
    dx: Int,
    dy: Int,
    width: Int,
    height: Int,
): TraceCropRect {
    val frameW = maxOf(1, width)
    val frameH = maxOf(1, height)
    val start = traceClampCrop(rect, frameW, frameH)
    val minW = minOf(TRACE_CROP_MIN_EDGE_PX, frameW)
    val minH = minOf(TRACE_CROP_MIN_EDGE_PX, frameH)

    var left = start.x
    var top = start.y
    var right = start.x + start.width
    var bottom = start.y + start.height

    when (corner) {
        TraceCropCorner.TOP_LEFT -> {
            left = (left + dx).coerceIn(0, right - minW)
            top = (top + dy).coerceIn(0, bottom - minH)
        }

        TraceCropCorner.TOP_RIGHT -> {
            right = (right + dx).coerceIn(left + minW, frameW)
            top = (top + dy).coerceIn(0, bottom - minH)
        }

        TraceCropCorner.BOTTOM_LEFT -> {
            left = (left + dx).coerceIn(0, right - minW)
            bottom = (bottom + dy).coerceIn(top + minH, frameH)
        }

        TraceCropCorner.BOTTOM_RIGHT -> {
            right = (right + dx).coerceIn(left + minW, frameW)
            bottom = (bottom + dy).coerceIn(top + minH, frameH)
        }
    }
    return TraceCropRect(left, top, right - left, bottom - top)
}

/** [rect] slid by [dx] and [dy] frame pixels, keeping its size and staying inside the frame. */
fun traceMoveCrop(
    rect: TraceCropRect,
    dx: Int,
    dy: Int,
    width: Int,
    height: Int,
): TraceCropRect {
    val start = traceClampCrop(rect, width, height)
    return traceClampCrop(start.copy(x = start.x + dx, y = start.y + dy), width, height)
}

/**
 * What to say when a typed number could not be used as typed, or an empty string when it could.
 *
 * A CLAMP THAT MOVED A TYPED NUMBER IS ANNOUNCED, not left as a box that ignored the typing. Two
 * branches, because the two are different mistakes: an origin that would hang the frame off the edge is
 * fixed by making the frame smaller FIRST, and a size outside the allowed range is fixed by typing a
 * different size. A single "that number was changed" would send half the people who read it to the
 * wrong control.
 *
 * @param field one of the four labels as the panel draws them: Left, Top, Width, Height.
 */
fun traceCropClampNote(
    field: String,
    typed: Int,
    applied: TraceCropRect,
    width: Int,
    height: Int,
): String = when (field) {
    "Left" ->
        "Left cannot be $typed: the frame is ${applied.width} wide on a ${width}px photograph, so " +
            "it would hang off the edge. It was set to ${applied.x}. Reduce Width first to move it " +
            "further right."

    "Top" ->
        "Top cannot be $typed: the frame is ${applied.height} tall on a ${height}px photograph, so " +
            "it would hang off the edge. It was set to ${applied.y}. Reduce Height first to move it " +
            "further down."

    "Width" ->
        "Width cannot be $typed: the frame is between ${minOf(TRACE_CROP_MIN_EDGE_PX, width)} " +
            "and $width pixels. It was set to ${applied.width}."

    else ->
        "Height cannot be $typed: the frame is between ${minOf(TRACE_CROP_MIN_EDGE_PX, height)} " +
            "and $height pixels. It was set to ${applied.height}."
}

/* ────────────────────────────────────────────────────────────────────────────
 * The copy
 * ──────────────────────────────────────────────────────────────────────────── */

/** The bytes inside a crop, and the box they were actually taken from after clamping. */
class TraceCroppedPixels(val rgba: ByteArray, val rect: TraceCropRect)

/**
 * Copy the RGBA inside [rect] out of [src], which is [width] by [height] pixels.
 *
 * ROW BY ROW WITH `System.arraycopy` rather than a loop per byte, which is the web's trade and holds
 * harder on a phone: four byte-writes per pixel over a 12 MP frame is fifty million bounds-checked
 * stores, and one array copy per row is three thousand of them.
 *
 * The clamp is applied HERE as well as at every call site, because this is the function that would read
 * out of bounds — and unlike the web's `Uint8ClampedArray`, which reads past its end as `undefined` and
 * writes a black band, this one throws inside a trace the researcher is waiting on.
 *
 * THE WHOLE FRAME COMES BACK AS THE SOURCE ITSELF, not as a copy. The web returns a slice there because
 * its caller may go on to sharpen in place; this port has no in-place stage, and a second copy of a
 * 48 MB buffer on a 2 GB handset is the allocation that kills a page. Nothing downstream writes into it
 * — [TraceCroppedPixels.rgba] is read once to build the engine's `IntArray` and once more to paint the
 * comparison plate.
 *
 * @return null when the source buffer is too small for the frame it claims, or when the crop could not
 *   be allocated. Never a throw: the caller is a trace a researcher is watching, and an allocation this
 *   size that did not happen is a sentence on screen rather than a crash in the middle of unsaved work.
 */
fun traceCropRgba(
    src: ByteArray,
    width: Int,
    height: Int,
    rect: TraceCropRect,
): TraceCroppedPixels? {
    if (width < 1 || height < 1) return null
    if (src.size.toLong() < width.toLong() * height.toLong() * 4L) return null

    val box = traceClampCrop(rect, width, height)
    if (traceIsWholeFrame(box, width, height)) return TraceCroppedPixels(src, box)

    val rowBytes = box.width * 4
    val out = runCatching { ByteArray(rowBytes * box.height) }.getOrNull() ?: return null
    for (row in 0 until box.height) {
        val from = ((box.y + row) * width + box.x) * 4
        System.arraycopy(src, from, out, row * rowBytes, rowBytes)
    }
    return TraceCroppedPixels(out, box)
}

/* ────────────────────────────────────────────────────────────────────────────
 * The words
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The crop clause for the provenance note written into the exported drawing, or an empty string.
 *
 * **CHARACTER FOR CHARACTER THE WEB'S SENTENCE, INCLUDING THE LOWER-CASE LETTER BETWEEN THE TWO
 * NUMBERS.** That is a deliberate exception to this client's own house style, which sets a pixel size
 * with a multiplication sign. The reason is the archive: a drawing traced on a handset and a drawing
 * traced in the portal land in the same submission, and a reviewer holding one of each should not have
 * to work out whether two phrasings of "cropped" mean two different operations. Everything a researcher
 * reads on SCREEN uses this app's own typography; this one string is a file's contents.
 *
 * A crop is destructive — everything outside it is absent from the drawing — so a reviewer holding the
 * drawing and the photograph needs to be able to tell why they do not match.
 *
 * @param sourceWidth the frame the crop was taken from, i.e. the pixels the engine was handed before
 *   the crop, which is what makes the two sizes in the sentence comparable.
 */
fun traceCropNote(rect: TraceCropRect, sourceWidth: Int, sourceHeight: Int): String {
    if (sourceWidth < 1 || sourceHeight < 1) return ""
    val box = traceClampCrop(rect, sourceWidth, sourceHeight)
    if (traceIsWholeFrame(box, sourceWidth, sourceHeight)) return ""
    return "Cropped on the device to ${box.width}x${box.height} at (${box.x}, ${box.y}) of " +
        "${sourceWidth}x$sourceHeight."
}

/**
 * What the panel says under the frame, in this app's own typography.
 *
 * The percentage is of AREA, which is the number that answers "how much of the sheet am I throwing
 * away" — a box at half the width and half the height keeps a quarter, and a reader told "50%" would be
 * told the wrong thing twice over.
 */
fun traceCropReadout(rect: TraceCropRect, width: Int, height: Int): String {
    if (width < 1 || height < 1) return ""
    val box = traceClampCrop(rect, width, height)
    if (traceIsWholeFrame(box, width, height)) return "The whole photograph, $width×$height."
    val part = box.width.toLong() * box.height.toLong()
    val whole = width.toLong() * height.toLong()
    val percent = Math.round(part * 100.0 / whole).toInt()
    return "${box.width}×${box.height} of $width×$height — $percent% of the frame. Everything " +
        "outside it is absent from the drawing."
}

/**
 * The sentence that always follows [traceCropReadout], whether or not a crop has been drawn.
 *
 * Printed unconditionally: the engine's own crop stage runs at its default on both clients, so a
 * researcher who framed the sheet exactly and still got a tighter drawing has not been failed by their
 * own aim. Saying it only when a crop exists would attach the explanation to the wrong cause.
 */
const val TRACE_CROP_ENGINE_NOTE: String =
    "The engine may still narrow the frame further on its own when it finds a subject it is " +
        "confident about; the notes under the traced result say when it did."

/**
 * What the panel says when the box on screen is no longer the box being traced.
 *
 * A control whose effect has silently gone stale is indistinguishable from a control that does nothing,
 * which is this repository's most-repeated bug class. The applied numbers are in it because "not the
 * one being traced" without them leaves a researcher unable to tell which of the two frames produced
 * what is on screen.
 */
fun traceCropStaleNote(applied: TraceCropRect): String =
    "The frame on screen is not the one being traced. The trace is still using " +
        "${applied.width}×${applied.height} from the last press — press “Use this frame for the " +
        "trace” to catch it up."
