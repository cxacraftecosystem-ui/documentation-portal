package com.fieldrepository.app.ui.trace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **TURNING A `content://` Uri INTO PIXELS — twice, for two different jobs.**
 *
 * ── WHY THIS FILE EXISTS IN THIS REPOSITORY AND NOT IN THE ONE THIS WAS PORTED FROM ───────────
 *
 * The design-workshop original took an absolute FILE PATH, because its photographs had already been
 * copied into that app's own media directory by a capture pipeline the panel could see, and it decoded
 * with `BitmapFactory.decodeFile`. This app has no such directory and no such pipeline in front of this
 * panel: an image arrives as a `content://` Uri from the camera, the gallery picker, a share intent or
 * a record's media list, and on Android 10 and later resolving one to a path is not possible in general
 * — every app that tried is now carrying the scar.
 *
 * So both decodes below read through `ContentResolver`, and both open the stream TWICE — once for the
 * bounds and once for the pixels — because `BitmapFactory.Options.inJustDecodeBounds` consumes the
 * stream it is given and a `content://` stream is not rewindable. That is one extra open per decode, on
 * a file this device already holds, against the alternative of reading the whole thing into memory to
 * get a rewindable copy of something that can be 12 MP.
 *
 * ── TWO DECODES, AND THEY ARE DELIBERATELY NOT ONE FUNCTION ───────────────────────────────────
 *
 * [traceDecodeForTrace] produces the pixels the ENGINE traces. [traceDecodeForDisplay] produces the
 * picture the frame chooser draws a rectangle on. They want opposite things and folding them together
 * would mean one of the two settling for the other's answer:
 *
 *  | | for the trace | for the frame chooser |
 *  | --- | --- | --- |
 *  | config | **ARGB_8888** — the pixel values are about to be graded | **RGB_565** — nothing reads a value |
 *  | ceiling | [TRACE_DECODE_MAX_EDGE_PX], 4096, the web's number | [TRACE_CROP_PREVIEW_EDGE_PX], 1024 |
 *  | a rotation tag | **refuses** | tolerates, and reports the stored size |
 *
 * The config row is the one that matters and it is the opposite of what an image-loading helper usually
 * decides. RGB_565 halves the memory of a large allocation and is right wherever nothing reads a pixel
 * VALUE. **Every clause of that is false for a trace.** The photograph is about to be graded, pixel by
 * pixel, by Canny's sub-pixel ridge interpolation and a difference of Gaussians. RGB_565 quantises each
 * channel to five or six bits — roughly ±4 luma counts — and the vendored engine's own cross-engine
 * parity budget is 1e-4 of normalised intensity. Four counts out of 255 is 1.6%, which is over a
 * hundred times that budget. A handset tracing 565 pixels would produce visibly different line art from
 * the portal tracing the same sheet, which is the one failure this whole feature is disciplined against.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * For the engine
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The photograph as **ARGB_8888**, capped at the web's own decode ceiling, un-rotated.
 *
 * ── IT APPLIES NO ROTATION, AND REFUSES A FILE THAT WOULD NEED ONE ────────────────────────────
 *
 * A second decoder that applied EXIF orientation would be a second opinion about it, and the comparison
 * plate is built from THESE pixels — so if the two opinions ever differed, one layer of the comparator
 * would arrive rotated and the other upright, which reads on screen as "the trace came out sideways".
 *
 * This reads the orientation tag only to REFUSE, which is not an opinion about the transform. A refusal
 * is also the right answer on its own terms: the engine's own `preprocess.autoOrient` leaf exists and
 * nothing in the pipeline reads it, so a rotated frame would be traced as it lies, and the drawing
 * would come back on its side with the panel unable to say why. The sentence sends the researcher to a
 * remedy that works — re-take or straighten the photograph — rather than to a control that does not.
 *
 * `ORIENTATION_UNDEFINED` counts as upright: a great many files carry no EXIF at all, and treating "the
 * file did not say" as "rotate it somehow" would refuse most of them.
 *
 * ── THE SIZE MATCHES THE WEB'S; THE RESAMPLER DOES NOT, AND THAT IS AN OPEN GAP ───────────────
 *
 * [traceWorkingSize] is a line-for-line mirror of the web's own `workingSizeFor`, so both clients hand
 * the engine a frame of the same dimensions and every coordinate the engine reports is in the same
 * system. **What is NOT the same is the filter**: this uses `Bitmap.createScaledBitmap`, the web uses
 * `createImageBitmap`'s resize, and the two do not produce identical pixels. It binds only above
 * 4096 px, so on an ordinary photograph the cap does nothing at all — but it is a real difference on a
 * path somebody could take, and it is written down here rather than discovered later.
 *
 * @throws TraceHostFailure with a sentence for every way this can fail.
 */
internal suspend fun traceDecodeForTrace(context: Context, uri: Uri): Bitmap =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        if (traceRotationTagged(context, uri)) {
            throw TraceHostFailure(
                TraceFailureKind.IMAGE_UNREADABLE,
                "this photograph carries a rotation the tracer will not guess at — " +
                    "straighten it or take it again square on, and trace that",
            )
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (opened.isFailure) {
            throw TraceHostFailure(
                TraceFailureKind.IMAGE_UNREADABLE,
                "this device would not open that photograph",
            )
        }
        val storedWidth = bounds.outWidth
        val storedHeight = bounds.outHeight
        if (storedWidth < 1 || storedHeight < 1) {
            throw TraceHostFailure(TraceFailureKind.IMAGE_EMPTY, "${storedWidth}x$storedHeight")
        }

        val (targetWidth, targetHeight) = traceWorkingSize(storedWidth, storedHeight)
        // The smallest power-of-two subsample that stays AT OR ABOVE the target, so the exact resize
        // that follows is a downscale. Overshooting downwards here and scaling back up would be
        // upsampling a decode, which invents detail the engine would then trace.
        var sample = 1
        while (maxOf(storedWidth, storedHeight) / (sample * 2) >= maxOf(targetWidth, targetHeight)) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            // The SECOND open. See the file header: a content stream cannot be rewound after the
            // bounds pass has read it.
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
            ?: throw TraceHostFailure(TraceFailureKind.IMAGE_UNREADABLE, "the decoder refused it")

        if (decoded.width == targetWidth && decoded.height == targetHeight) return@withContext decoded
        val scaled = runCatching {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        }.getOrNull()
        if (scaled == null || scaled === decoded) {
            // A failed resize returns the subsampled frame rather than nothing. It is a different size
            // from the web's, which is a parity difference on a path an ordinary photograph never
            // takes, and it beats refusing a trace somebody is waiting for. `traceCropIn` is written
            // knowing this can happen — it re-scales an aimed rectangle into whatever frame arrives
            // rather than clamping it into one it was not drawn in.
            return@withContext decoded
        }
        decoded.recycle()
        scaled
    }

/**
 * Whether [uri] claims an orientation other than "upright".
 *
 * Reads ONE tag and compares it, which is deliberately not an eight-case transform mapping — see
 * [traceDecodeForTrace]. A file this cannot open at all answers false rather than throwing: the decode
 * two lines later will fail with a better sentence than "could not read your EXIF", and an
 * `ExifInterface` that refuses a perfectly good PNG (which carries no EXIF) must not refuse the trace.
 */
private fun traceRotationTagged(context: Context, uri: Uri): Boolean {
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: return false
    return orientation != ExifInterface.ORIENTATION_NORMAL &&
        orientation != ExifInterface.ORIENTATION_UNDEFINED
}

/* ────────────────────────────────────────────────────────────────────────────
 * For the frame chooser
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A decoded picture, and the size of the frame it came from.
 *
 * [sourceWidth] and [sourceHeight] are the STORED dimensions, not the decoded ones. The frame chooser
 * needs both: the bitmap to draw, and the original frame so that the four numbers a researcher types
 * are in the same coordinate system as the trace's own. They are carried together because a caller that
 * had to ask twice could get one of them from a decode and the other from somewhere else.
 */
internal class TraceDisplayImage(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/**
 * The photograph small enough to aim a rectangle on, or null when this device could not open it.
 *
 * **RGB_565 HERE AND ARGB_8888 FOR THE TRACE**, which is the split this file's header tabulates.
 * Nothing downstream of this decode reads a pixel value — it is drawn once, under a dimmed overlay and
 * four handles — so halving the memory of the one large allocation is worth the banding on a photograph
 * nobody is grading. A 4:3 frame at the 1024 px ceiling is 1.6 MB here against 3.1 MB at ARGB_8888, on
 * a handset that is also holding two 1024 px comparison plates at 4.2 MB each.
 *
 * NULL RATHER THAN A THROW, and the caller prints a sentence that refuses the FRAMING and says nothing
 * about the drawing: the trace uses a different decoder with a different ceiling and may well succeed
 * where this failed.
 */
internal suspend fun traceDecodeForDisplay(
    context: Context,
    uri: Uri,
    maxEdgePx: Int,
): TraceDisplayImage? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }
    val storedWidth = bounds.outWidth
    val storedHeight = bounds.outHeight
    if (storedWidth < 1 || storedHeight < 1) return@withContext null

    var sample = 1
    while (maxOf(storedWidth, storedHeight) / (sample * 2) >= maxEdgePx) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val decoded = runCatching {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull() ?: return@withContext null

    TraceDisplayImage(
        bitmap = decoded,
        sourceWidth = storedWidth,
        sourceHeight = storedHeight,
    )
}

/**
 * The photograph's own display name, for naming a derived file after it.
 *
 * ── IT ASKS THE RESOLVER AND FALLS BACK TO THE Uri, IN THAT ORDER ─────────────────────────────
 *
 * `OpenableColumns.DISPLAY_NAME` is the only thing that knows what a gallery calls a file, and a
 * `content://` Uri's last path segment is frequently a bare row id. But a provider is allowed to answer
 * nothing, and some answer nothing for a Uri granted through a share intent — so the last segment is
 * the fallback, and [traceExportFileName] falls back again to "sketch" if that is empty too.
 *
 * NEVER THROWS. This is used to build a FILE NAME, and a file name that came out as "sketch" is a
 * smaller failure than a trace that could not be saved.
 */
internal fun traceDisplayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: "sketch"
}
