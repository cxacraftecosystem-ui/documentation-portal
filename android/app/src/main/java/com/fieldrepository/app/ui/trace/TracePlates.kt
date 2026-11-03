package com.fieldrepository.app.ui.trace

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

internal object TracePlates {

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
            traceResampleRow(rgba, width, height, plateWidth, plateHeight, py, row)
            bitmap.setPixels(row, 0, plateWidth, 0, py, plateWidth, 1)
        }
        return bitmap
    }

    fun differencePlate(photograph: Bitmap, trace: Bitmap): Bitmap? {
        val width = photograph.width
        val height = photograph.height
        if (width < 1 || height < 1) return null
        if (trace.width != width || trace.height != height) return null

        val bitmap = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null

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
        if (background != null) canvas.drawColor(background)

        val scaleX = plateWidth.toDouble() / documentWidth.toDouble()
        val scaleY = plateHeight.toDouble() / documentHeight.toDouble()
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
                paint.strokeWidth = maxOf(style.strokeWidth * widthScale, 0.1f)
                paint.strokeCap = capOf(style.cap)
                paint.strokeJoin = joinOf(style.join)
                paint.strokeMiter = maxOf(style.miterLimit, 1f)
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }

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

    private fun capOf(name: String): Paint.Cap = when (name) {
        "BUTT" -> Paint.Cap.BUTT
        "SQUARE" -> Paint.Cap.SQUARE
        else -> Paint.Cap.ROUND
    }

    private fun joinOf(name: String): Paint.Join = when (name) {
        "MITER" -> Paint.Join.MITER
        "BEVEL" -> Paint.Join.BEVEL
        else -> Paint.Join.ROUND
    }

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
