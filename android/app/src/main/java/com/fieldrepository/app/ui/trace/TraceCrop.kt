package com.fieldrepository.app.ui.trace

import androidx.compose.runtime.Immutable

@Immutable
data class TraceCropRect(val x: Int, val y: Int, val width: Int, val height: Int)

@Immutable
data class TraceFrameChoice(
    val rect: TraceCropRect,
    val frameWidth: Int,
    val frameHeight: Int,
)

const val TRACE_CROP_MIN_EDGE_PX: Int = 16

fun traceWholeFrame(width: Int, height: Int): TraceCropRect =
    TraceCropRect(0, 0, maxOf(1, width), maxOf(1, height))

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

fun traceIsWholeFrame(rect: TraceCropRect, width: Int, height: Int): Boolean {
    val whole = traceWholeFrame(width, height)
    return rect.x == 0 && rect.y == 0 && rect.width == whole.width && rect.height == whole.height
}

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

enum class TraceCropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

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

fun traceCropClampNote(
    field: String,
    typed: Int,
    applied: TraceCropRect,
    width: Int,
    height: Int,
): String = when (field) {
    "Left" ->
        "Left set to ${applied.x} — $typed would go off the edge. Reduce Width first."

    "Top" ->
        "Top set to ${applied.y} — $typed would go off the edge. Reduce Height first."

    "Width" ->
        "Width must be between ${minOf(TRACE_CROP_MIN_EDGE_PX, width)} and $width px. Set to ${applied.width}."

    else ->
        "Height must be between ${minOf(TRACE_CROP_MIN_EDGE_PX, height)} and $height px. Set to ${applied.height}."
}

class TraceCroppedPixels(val rgba: ByteArray, val rect: TraceCropRect)

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

fun traceCropNote(rect: TraceCropRect, sourceWidth: Int, sourceHeight: Int): String {
    if (sourceWidth < 1 || sourceHeight < 1) return ""
    val box = traceClampCrop(rect, sourceWidth, sourceHeight)
    if (traceIsWholeFrame(box, sourceWidth, sourceHeight)) return ""
    return "Cropped on the device to ${box.width}x${box.height} at (${box.x}, ${box.y}) of " +
        "${sourceWidth}x$sourceHeight."
}

fun traceCropReadout(rect: TraceCropRect, width: Int, height: Int): String {
    if (width < 1 || height < 1) return ""
    val box = traceClampCrop(rect, width, height)
    if (traceIsWholeFrame(box, width, height)) return "The whole photograph, $width×$height."
    val part = box.width.toLong() * box.height.toLong()
    val whole = width.toLong() * height.toLong()
    val percent = Math.round(part * 100.0 / whole).toInt()
    return "${box.width}×${box.height} of $width×$height ($percent%)."
}

const val TRACE_CROP_ENGINE_NOTE: String =
    "The tracer may tighten this further; the result notes say when."

fun traceCropStaleNote(applied: TraceCropRect): String =
    "Not applied yet — still tracing ${applied.width}×${applied.height}. Tap “Use this frame”."
