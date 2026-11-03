package com.fieldrepository.app.ui.trace

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
            0xFF000000.toInt()
        } else {
            (0xFF shl 24) or
                (traceClampedAverage(r, n) shl 16) or
                (traceClampedAverage(g, n) shl 8) or
                traceClampedAverage(b, n)
        }
    }
}

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

const val TRACE_DIFFERENCE_NOTE: String =
    "Bright where the trace and photo differ."

const val TRACE_DIFFERENCE_REFUSAL: String =
    "Not enough memory for the difference view. The drawing is unaffected."

const val TRACE_DIFFERENCE_PENDING: String = "Working out the difference picture…"

const val TRACE_DIFFERENCE_DESCRIPTION: String =
    "Dark where they agree, bright where they differ."

const val TRACE_DIFFERENCE_LABEL: String = "Difference"

const val TRACE_COMPARE_WHITE_NOTE: String =
    "Shown on white here; the file keeps your background."

fun traceComparisonReduction(
    plateWidth: Int,
    plateHeight: Int,
    tracedWidth: Int,
    tracedHeight: Int,
): String {
    if (plateWidth < 1 || plateHeight < 1 || tracedWidth < 1 || tracedHeight < 1) return ""
    if (plateWidth >= tracedWidth && plateHeight >= tracedHeight) return ""
    return "Shown at $plateWidth×$plateHeight (from $tracedWidth×$tracedHeight)."
}

fun traceComparisonStatus(
    hasPlates: Boolean,
    running: Boolean,
    failed: Boolean,
    plateRefusal: String,
    hasResult: Boolean,
): String = when {
    hasPlates && running ->
        "A newer trace is running; showing the last one."

    hasPlates -> ""

    plateRefusal.isNotBlank() -> plateRefusal

    running -> "Tracing…"

    failed -> "The trace didn't finish. The reason is above."

    !hasResult -> "Appears as soon as the first trace finishes."

    else -> "There is no comparison for this drawing."
}

const val TRACE_PLATE_MEMORY_REFUSAL: String =
    "The drawing is unaffected, but there's no memory to compare. Set resolution to Fast."
