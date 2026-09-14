package com.fieldrepository.app.ui.trace

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * **THE PICTURE OF THE DRAWING — the one export this device writes itself.**
 *
 * ── WHY THE PNG DOES NOT GO THROUGH THE ENGINE, ON EITHER CLIENT ──────────────────────────────
 *
 * Four of the five formats offered are vector, and every one of them is a file format the vendored
 * engine owns a writer for. The fifth is a picture, and a picture is the PLATFORM's job:
 * `Bitmap.compress` is Android's PNG encoder, it ships with the operating system, and it is better
 * tested than any bundled encoder could be. The web reached the same conclusion from the other side and
 * wrote it into its own table — its PNG is "written by `canvas.toBlob` rather than by the engine's own
 * PNG encoder", on the rule that "the platform layer owns the pixel formats the browser already has an
 * encoder for".
 *
 * So this is not a handset shortcut and it is not a divergence to be tidied away later. It is the same
 * decision made twice.
 *
 * ── ONE PAINTER, NOT TWO ──────────────────────────────────────────────────────────────────────
 *
 * The geometry is walked by [TracePlates.renderTrace], which is the SAME function that already paints
 * the comparator's trace plate. A second painter on this side would be a second opinion about what a
 * cubic segment, a fill rule or a mitre limit means, and the two would diverge the first time one of
 * them was fixed. The web states the identical rule for itself: "A preview drawn by different code from
 * the file that gets attached is a preview that can lie, and it lies in the one direction nobody
 * checks."
 *
 * WHAT THAT SHARING COSTS, STATED RATHER THAN DISCOVERED. `renderTrace` clamps a stroke to a hairline
 * (0.1 device pixels) where the web's painter falls back to 1 DOCUMENT unit for a non-positive width.
 * Neither branch is reachable from a traced document: the engine clamps `output.strokeWidth` to
 * 0.01..64, so a width of zero can only arrive from a document this engine did not write. It is written
 * down here because the two clients' rasters are meant to be the same picture, and an unstated
 * difference is one somebody re-derives from scratch in a year.
 *
 * ── AND WHY THE BACKGROUND IS THE DOCUMENT'S, NEVER WHITE ─────────────────────────────────────
 *
 * `renderTrace`'s white belongs to the COMPARATOR — a transparent AFTER layer stacked over the
 * photograph shows the photograph through both layers, so the divider moves and nothing changes. A saved
 * PNG is not a comparison. It gets `traceExportBackground(documentBackground)`, which is the
 * pass-through `TraceExport.kt` argues for at length: the same value the vector writers are handed, so a
 * PNG and a PDF of one drawing cannot disagree about their ground.
 *
 * ── THE ALLOCATION IS THE BIGGEST THIS FEATURE MAKES, AND IT IS BRIEF ─────────────────────────
 *
 * A PNG at [TRACE_PNG_MAX_EDGE_PX] is 2048 x 2048 x 4 = 16,777,216 bytes of ARGB_8888 — twice either
 * display plate, on a phone that may be holding three of those and a camera. Three things keep it
 * survivable and all three are deliberate: the cap itself; the bitmap is recycled the moment the bytes
 * exist, because unlike a display plate nothing else ever points at it; and a failure to allocate is a
 * null and a sentence rather than an exception.
 *
 * NO ROBOLECTRIC IN THIS MODULE, so nothing in this file is reachable from a unit test — the same split
 * `TracePlates.kt` states for itself. Everything that could be quietly wrong is next door and pinned:
 * [tracePngSize] holds the size rule and [tracePngReductionNote] holds the sentence.
 */

/**
 * Paint the traced geometry and encode it as a PNG, or answer null if this phone could not.
 *
 * @param background the DOCUMENT's own background — packed ARGB, or null for transparent. Pass it
 *   through [traceExportBackground] and never choose one here; see that function's own section for what
 *   a substituted null would do differently to the vector writers and to the rasteriser.
 * @return the file's bytes, or null when the bitmap or the encode failed. The caller prints
 *   [TRACE_PNG_MEMORY_REFUSAL]; there is no second reason anybody could act on differently.
 */
suspend fun traceRenderPngBytes(
    geometry: TraceGeometry,
    documentWidth: Int,
    documentHeight: Int,
    background: Int?,
): ByteArray? = withContext(Dispatchers.Default) {
    /*
      OFF THE MAIN THREAD, BY THIS FUNCTION AND NOT BY ITS CALLER, which is the discipline
      `TraceEngineRuntime` sets for this whole feature: "an implementation puts its own `withContext`
      inside, because every caller here is a composable's scope and that is the main thread". A 2048px
      rasterisation of twenty thousand paths plus a PNG deflate is not a frame's worth of work.
    */
    val size = tracePngSize(documentWidth, documentHeight)
    val bitmap = TracePlates.renderTrace(
        geometry = geometry,
        documentWidth = documentWidth,
        documentHeight = documentHeight,
        plateWidth = size.width,
        plateHeight = size.height,
        background = background,
    ) ?: return@withContext null

    try {
        /*
          QUALITY 100 IS NOT A QUALITY. PNG is lossless and the argument is ignored for it; it is passed
          as 100 only because the signature demands a number, and a 0 here would read to the next person
          as "a low-quality PNG", which is not a thing.

          SIZED FROM THE PIXELS RATHER THAN LEFT AT THE DEFAULT 32 BYTES. Line art is very nearly
          bilevel and compresses hard, so a quarter of the raw ARGB is a generous starting guess that
          still saves the stream several doublings — each of which is a full copy of everything written
          so far, on the phone that has just allocated 16 MB.
        */
        val out = ByteArrayOutputStream(size.width * size.height)
        val encoded = runCatching {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }.getOrDefault(false)
        if (encoded) out.toByteArray() else null
    } finally {
        // SAFE HERE AND NOT SAFE FOR A DISPLAY PLATE. Compose holds a display plate through an
        // `ImageBitmap` for as long as the frame is on screen, and recycling one that is still being
        // drawn throws. Nothing ever points at this one: it was created inside this function, it was
        // never handed to a composition, and the bytes it became are independent of it. Holding 16 MB
        // until the collector notices would be the whole cost of the feature paid for nothing.
        bitmap.recycle()
    }
}
