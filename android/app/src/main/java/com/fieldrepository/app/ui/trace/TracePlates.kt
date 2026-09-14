package com.fieldrepository.app.ui.trace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * The only file in the trace feature that knows what a `Bitmap` is.
 *
 * The split is the one `TraceWire.kt` states: there is no Robolectric in this module
 * (`app/build.gradle.kts` declares JUnit 4 and nothing else), so anything touching `android.graphics`
 * is by construction code no unit test can reach. Everything worth pinning therefore lives next
 * door — [traceArgbRowToRgba] in `TraceWire.kt`, pinned against the engine's own buffer arithmetic, and
 * the box filter and the difference in `TracePlateMath.kt`. What is left here is allocation, iteration
 * and drawing — the parts that fail loudly or not at all.
 *
 * ── NEITHER PLATE MAY EVER REACH THE RECORD ───────────────────────────────────────────────────
 *
 * A canvas re-encode must not arrive anywhere near an upload: the original file IS the artefact, and
 * re-encoding destroys full resolution and strips the EXIF this app deliberately preserves. What gets
 * attached is [TraceResult.svg], which is the engine's own writer output and has been through no canvas.
 *
 * ── ONE BITMAP FROM THIS FILE DOES LEAVE THE PHONE, AND IT IS NOT A PLATE ─────────────────────
 *
 * [renderTrace] also paints the PNG a researcher SAVES (`TraceExportRaster.kt`), which lands in this
 * device's own Downloads folder and in the share sheet. That is a copy for the person holding the phone
 * and it touches no record field, no upload queue and no offline store. The refusal above is unchanged:
 * no bitmap this file produces may be ATTACHED, and none is.
 */
internal object TracePlates {

    /**
     * The photograph, as the RGBA the engine was actually handed, at [plateWidth] x [plateHeight].
     *
     * **FROM THE DECODED PIXELS AND NOT FROM THE Uri A SECOND TIME.** Two reasons, and both hold harder
     * on a handset: a second decode is a second opinion about EXIF orientation, so one layer of the
     * comparator can arrive rotated and the other upright — which reads on screen as "the trace came
     * out sideways" — and these are the pixels the engine actually traced, which is the only thing the
     * comparison is about.
     *
     * BOX AVERAGE AND NOT NEAREST NEIGHBOUR. A photograph of a pencil sketch is exactly the content
     * that aliases: 1 px strokes on paper grain, sampled at a third of the frequency, come back as a
     * dotted line, and a researcher comparing a dotted photograph against a clean trace would conclude
     * the trace had invented strokes. The cost is one pass over the source, which is already in memory.
     *
     * @param rgba the bytes handed to the engine, `width * height * 4` of them
     * @return null when the bitmap could not be allocated — see [renderTrace] for why that is a null
     *   and not a throw
     */
    fun photographPlate(
        rgba: ByteArray,
        width: Int,
        height: Int,
        plateWidth: Int,
        plateHeight: Int,
    ): Bitmap? {
        if (width < 1 || height < 1 || plateWidth < 1 || plateHeight < 1) return null
        if (rgba.size.toLong() < width.toLong() * height.toLong() * 4L) return null

        val bitmap = runCatching {
            Bitmap.createBitmap(plateWidth, plateHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        val row = IntArray(plateWidth)
        for (py in 0 until plateHeight) {
            // THE AVERAGING ITSELF LIVES NEXT DOOR, in a file no `android.graphics` import can reach
            // into and a unit test can. What is left in this loop is allocation and iteration, which is
            // this file's whole remit — see the header.
            traceResampleRow(rgba, width, height, plateWidth, plateHeight, py, row)
            bitmap.setPixels(row, 0, plateWidth, 0, py, plateWidth, 1)
        }
        return bitmap
    }

    /**
     * The two plates subtracted from each other, at their own size, as a third plate.
     *
     * ── BUILT WHEN IT IS FIRST ASKED FOR, AND NOT WITH THE OTHER TWO ──────────────────────────
     *
     * A third 1024 px ARGB_8888 bitmap is another 4.2 MB held for as long as the comparator is on
     * screen, on a phone that is also running the camera, and most researchers never press the fourth
     * chip. Two of these are affordable and the full-resolution pair is not; a third built eagerly
     * would be paying that argument's price for a view nobody opened. The comparator builds it on the
     * first press and keeps it.
     *
     * ── THE SIZE CHECK IS NOT A COURTESY ──────────────────────────────────────────────────────
     *
     * Two plates of different sizes cannot be subtracted at all, so this refuses rather than
     * letterboxing one — the same decision the comparator makes for the wipe, for the same reason: a
     * difference between two framings of one drawing is a picture of the framing.
     *
     * @return null when the sizes disagree or the bitmap could not be allocated. The caller prints
     *   [TRACE_DIFFERENCE_REFUSAL] and leaves the other three modes alone.
     */
    fun differencePlate(photograph: Bitmap, trace: Bitmap): Bitmap? {
        val width = photograph.width
        val height = photograph.height
        if (width < 1 || height < 1) return null
        if (trace.width != width || trace.height != height) return null

        val bitmap = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        // Row by row, for [readRgba]'s reason: three full-frame IntArrays over a 1024x1024 pair is
        // 12 MB of temporary on top of three bitmaps, and one row each is a few kilobytes reused.
        val photoRow = IntArray(width)
        val traceRow = IntArray(width)
        val out = IntArray(width)
        for (y in 0 until height) {
            photograph.getPixels(photoRow, 0, width, 0, y, width, 1)
            trace.getPixels(traceRow, 0, width, 0, y, width, 1)
            traceDifferenceRow(photoRow, traceRow, out, width)
            bitmap.setPixels(out, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    /**
     * The traced geometry, drawn at [plateWidth] x [plateHeight], on [background].
     *
     * ── THE WHITE IS THE DEFAULT BECAUSE IT IS THE COMPARATOR'S, AND IT IS NOT THE EXPORT'S ───
     *
     * `output.background` defaults to `null`, which is transparent, and that is right for a file. It is
     * wrong for a comparator: a transparent AFTER layer stacked over the photograph shows the
     * photograph through both layers, so the divider moves and nothing changes, which is
     * indistinguishable from a broken slider. So the white belongs to the comparison and the panel says
     * so next to it.
     *
     * **[background] IS A PARAMETER WITH THE COMPARATOR'S ANSWER AS ITS DEFAULT, RATHER THAN A
     * CONSTANT.** The saved PNG is painted by this same function — one painter, because a second one
     * would be a second opinion about what a cubic segment means — and a saved file must carry the
     * DOCUMENT's ground, never the comparison's. `TraceExportRaster.kt` is that caller and it passes
     * `traceExportBackground(documentBackground)`; every comparator call site passes nothing and is
     * unchanged. Null paints no ground at all, which is what a freshly created bitmap already is.
     *
     * ── AND WHY THIS DRAWS FROM THE GEOMETRY RATHER THAN FROM THE SVG ─────────────────────────
     *
     * Because the SVG is the artefact and must not be re-parsed to be looked at. Rendering it would
     * need an SVG reader — a second opinion about what those bytes mean, on the one string the
     * cross-runtime parity harness compares exactly. The flat arrays are the same drawing before it was
     * spelled, and walking them is the same trade the web's own painter makes.
     *
     * @return null on an allocation failure. Never a throw: a 1024x1024 ARGB bitmap is 4.2 MB and this
     *   runs on handsets that are sometimes at the edge, and a plate which could not be built is a
     *   sentence on screen rather than a crash in the middle of unsaved work.
     */
    fun renderTrace(
        geometry: TraceGeometry,
        documentWidth: Int,
        documentHeight: Int,
        plateWidth: Int,
        plateHeight: Int,
        background: Int? = Color.WHITE,
    ): Bitmap? {
        if (documentWidth < 1 || documentHeight < 1 || plateWidth < 1 || plateHeight < 1) return null

        val bitmap = runCatching {
            Bitmap.createBitmap(plateWidth, plateHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

        val canvas = Canvas(bitmap)
        // A NEW BITMAP IS ALREADY TRANSPARENT, so null is "leave it" and not a second code path. The
        // web's painter guards the identical fill for the same reason, and the alpha byte of a
        // translucent ground reaches the canvas either way: `drawColor` composites SRC_OVER onto
        // nothing, which is what a filled rectangle under a global alpha does in a browser.
        if (background != null) canvas.drawColor(background)

        val scaleX = plateWidth.toDouble() / documentWidth.toDouble()
        val scaleY = plateHeight.toDouble() / documentHeight.toDouble()
        // The factor a stroke width scales by: the isotropic scale, `sqrt(|det|)`, which is what a
        // width must use rather than either axis — "a width that did not scale with the geometry comes
        // out several times too thin on a downscaled 12 MP source". The two axes are equal here by
        // construction (the plate keeps the document's aspect), so this is the same number either way;
        // it is written out so it stays right the day somebody lets the plate be letterboxed.
        val widthScale = Math.sqrt(scaleX * scaleY).toFloat()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()

        for (shape in 0 until geometry.shapeCount) {
            val style = geometry.styleOf(shape)
            buildPath(geometry, shape, scaleX, scaleY, style.fillRule, path)

            val alpha = style.opacity.coerceIn(0f, 1f)
            style.fill?.let { fill ->
                paint.reset()
                paint.isAntiAlias = true
                paint.style = Paint.Style.FILL
                paint.color = fill
                paint.alpha = (Color.alpha(fill) * alpha).toInt().coerceIn(0, 255)
                canvas.drawPath(path, paint)
            }
            style.stroke?.let { stroke ->
                paint.reset()
                paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.color = stroke
                paint.alpha = (Color.alpha(stroke) * alpha).toInt().coerceIn(0, 255)
                // Hairline rather than zero: Android draws a 0-width stroke as one device pixel, which
                // at a 1024 px plate of a 4096 px document is four times too wide. The engine's own
                // minimum stroke width is 0.01, so anything at all is a real width.
                paint.strokeWidth = maxOf(style.strokeWidth * widthScale, 0.1f)
                paint.strokeCap = capOf(style.cap)
                paint.strokeJoin = joinOf(style.join)
                paint.strokeMiter = maxOf(style.miterLimit, 1f)
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }

    /**
     * Walks one shape's run of [TraceGeometry] into [into].
     *
     * `reset()` and not a fresh `Path` per shape: a 20,000-shape trace would otherwise allocate 20,000
     * native path objects and hand the collector every one, which on a phone is a visible stall in the
     * middle of showing a result. The geometry has already been through [TraceGeometry.validate], so
     * the index arithmetic here cannot run off the end.
     */
    private fun buildPath(
        geometry: TraceGeometry,
        shape: Int,
        scaleX: Double,
        scaleY: Double,
        fillRule: String,
        into: Path,
    ) {
        into.reset()
        into.fillType = if (fillRule == "NONZERO") Path.FillType.WINDING else Path.FillType.EVEN_ODD

        val coords = geometry.coords
        var c = geometry.coordStarts[shape]
        into.moveTo((coords[c] * scaleX).toFloat(), (coords[c + 1] * scaleY).toFloat())
        c += 2

        for (v in geometry.verbStarts[shape] until geometry.verbStarts[shape + 1]) {
            when (geometry.verbs[v]) {
                TRACE_VERB_LINE -> {
                    into.lineTo((coords[c] * scaleX).toFloat(), (coords[c + 1] * scaleY).toFloat())
                    c += 2
                }
                TRACE_VERB_QUAD -> {
                    into.quadTo(
                        (coords[c] * scaleX).toFloat(), (coords[c + 1] * scaleY).toFloat(),
                        (coords[c + 2] * scaleX).toFloat(), (coords[c + 3] * scaleY).toFloat(),
                    )
                    c += 4
                }
                else -> {
                    into.cubicTo(
                        (coords[c] * scaleX).toFloat(), (coords[c + 1] * scaleY).toFloat(),
                        (coords[c + 2] * scaleX).toFloat(), (coords[c + 3] * scaleY).toFloat(),
                        (coords[c + 4] * scaleX).toFloat(), (coords[c + 5] * scaleY).toFloat(),
                    )
                    c += 6
                }
            }
        }
        if (geometry.isClosed(shape)) into.close()
    }

    /**
     * The engine's cap name as a `Paint.Cap`, falling back to the engine's own default.
     *
     * A FALLBACK AND NOT A THROW. `VecStyle` defaults `cap` to `ROUND`, and the day a newer vendored
     * copy adds a fourth cap this must draw something reasonable rather than crash a phone in a
     * village — the value is a display detail on a plate nobody attaches, and the SVG that IS attached
     * carries whatever the engine wrote regardless.
     */
    private fun capOf(name: String): Paint.Cap = when (name) {
        "BUTT" -> Paint.Cap.BUTT
        "SQUARE" -> Paint.Cap.SQUARE
        else -> Paint.Cap.ROUND
    }

    /** @see capOf */
    private fun joinOf(name: String): Paint.Join = when (name) {
        "MITER" -> Paint.Join.MITER
        "BEVEL" -> Paint.Join.BEVEL
        else -> Paint.Join.ROUND
    }

    /**
     * One row of a bitmap at a time, as the RGBA bytes the engine reads.
     *
     * ROW BY ROW AND NOT ONE `getPixels` OVER THE WHOLE FRAME: a 2400x1800 working copy is 4.3M pixels,
     * so a full-frame `IntArray` is 17 MB allocated on top of the bitmap itself, on a handset that is
     * also running a camera. One row is a few kilobytes and is reused, so the peak is the bitmap plus
     * the output buffer and nothing else.
     */
    fun readRgba(bitmap: Bitmap): ByteArray? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 1 || height < 1) return null
        val out = runCatching { ByteArray(width * height * 4) }.getOrNull() ?: return null
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            traceArgbRowToRgba(row, width, out, y * width * 4)
        }
        return out
    }
}
