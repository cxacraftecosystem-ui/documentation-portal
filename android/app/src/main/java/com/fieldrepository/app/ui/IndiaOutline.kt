package com.fieldrepository.app.ui

import android.content.Context
import android.content.res.Resources
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import com.fieldrepository.app.R
import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

private const val OUTLINE_MAGIC = 0x494E4431

private const val QUANT_MAX = 65535.0

class IndiaGeometry internal constructor(
    internal val rings: List<FloatArray>,
    internal val ringBounds: List<Rect>,
    val aspect: Float,
    private val minLon: Double,
    private val maxLon: Double,
    private val minLat: Double,
    private val maxLat: Double
) {
    private val lonSpanRad = (maxLon - minLon) * PI / 180.0
    private val topMercator = mercatorY(maxLat)

    fun world(latitude: Double, longitude: Double): Offset {
        val x = (longitude - minLon) * PI / 180.0 / lonSpanRad
        val y = (topMercator - mercatorY(latitude)) / lonSpanRad
        return Offset(x.toFloat(), y.toFloat())
    }

    companion object {
        private fun mercatorY(latitude: Double): Double {
            val clamped = latitude.coerceIn(-85.05, 85.05)
            return ln(tan(PI / 4.0 + clamped * PI / 360.0))
        }
    }
}

fun haversineMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_008.8
    val p1 = lat1 * PI / 180.0
    val p2 = lat2 * PI / 180.0
    val dp = (lat2 - lat1) * PI / 180.0
    val dl = (lon2 - lon1) * PI / 180.0
    val a = kotlin.math.sin(dp / 2).pow(2) +
        kotlin.math.cos(p1) * kotlin.math.cos(p2) * kotlin.math.sin(dl / 2).pow(2)
    return 2 * r * kotlin.math.asin(min(1.0, kotlin.math.sqrt(a)))
}

fun loadIndiaGeometry(context: Context): IndiaGeometry =
    loadIndGeometry(context, R.raw.india_outline)

fun loadStateBorders(context: Context): IndiaGeometry =
    loadIndGeometry(context, R.raw.state_borders)

fun loadDistrictBorders(context: Context): IndiaGeometry =
    loadIndGeometry(context, R.raw.district_borders)

private fun loadIndGeometry(context: Context, resourceId: Int): IndiaGeometry {
    val name = runCatching { context.resources.getResourceEntryName(resourceId) }.getOrNull() ?: "IND1"
    DataInputStream(context.resources.openRawResource(resourceId).buffered()).use { input ->
        require(input.readInt() == OUTLINE_MAGIC) { "$name.bin: bad magic" }
        val minLon = input.readDouble()
        val maxLon = input.readDouble()
        val minLat = input.readDouble()
        val maxLat = input.readDouble()
        val ringCount = input.readInt()

        val lonSpanRad = (maxLon - minLon) * PI / 180.0
        val topMercator = ln(tan(PI / 4.0 + maxLat.coerceIn(-85.05, 85.05) * PI / 360.0))
        val lonStep = (maxLon - minLon) / QUANT_MAX
        val latStep = (maxLat - minLat) / QUANT_MAX

        val rings = ArrayList<FloatArray>(ringCount)
        val bounds = ArrayList<Rect>(ringCount)
        repeat(ringCount) {
            val n = input.readInt()
            val ring = FloatArray(n * 2)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until n) {
                val qx = input.readUnsignedShort()
                val qy = input.readUnsignedShort()
                val lon = minLon + qx * lonStep
                val lat = minLat + qy * latStep
                val x = ((lon - minLon) * PI / 180.0 / lonSpanRad).toFloat()
                val y = ((topMercator - ln(tan(PI / 4.0 + lat * PI / 360.0))) / lonSpanRad).toFloat()
                ring[i * 2] = x
                ring[i * 2 + 1] = y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            rings += ring
            bounds += Rect(minX, minY, maxX, maxY)
        }
        val aspect = bounds.fold(0f) { acc, r -> max(acc, r.bottom) }
        return IndiaGeometry(rings, bounds, aspect, minLon, maxLon, minLat, maxLat)
    }
}

private const val BUCKETS_PER_OCTAVE = 2.0

fun outlineBucketScale(scale: Float): Float {
    val safe = scale.coerceAtLeast(1f)
    return 2.0.pow(ceil(log2(safe.toDouble()) * BUCKETS_PER_OCTAVE) / BUCKETS_PER_OCTAVE).toFloat()
}

fun buildOutlinePath(geometry: IndiaGeometry, bucketScale: Float): Path {
    val path = Path()
    val minStep = MIN_STEP_PX
    for (index in geometry.rings.indices) {
        val bounds = geometry.ringBounds[index]
        val widthPx = bounds.width * bucketScale
        val heightPx = bounds.height * bucketScale
        if (max(widthPx, heightPx) < MIN_ISLAND_PX) {
            val cx = bounds.center.x * bucketScale
            val cy = bounds.center.y * bucketScale
            val half = MIN_ISLAND_PX / 2f
            path.moveTo(cx - half, cy - half)
            path.lineTo(cx + half, cy - half)
            path.lineTo(cx + half, cy + half)
            path.lineTo(cx - half, cy + half)
            path.close()
            continue
        }
        val ring = geometry.rings[index]
        var lastX = 0f
        var lastY = 0f
        var started = false
        var emitted = 0
        var i = 0
        while (i < ring.size) {
            val x = ring[i] * bucketScale
            val y = ring[i + 1] * bucketScale
            if (!started) {
                path.moveTo(x, y)
                lastX = x
                lastY = y
                started = true
                emitted = 1
            } else if (abs(x - lastX) + abs(y - lastY) >= minStep) {
                path.lineTo(x, y)
                lastX = x
                lastY = y
                emitted++
            }
            i += 2
        }
        if (started) {
            if (emitted < 3) {
                val cx = bounds.center.x * bucketScale
                val cy = bounds.center.y * bucketScale
                val half = MIN_ISLAND_PX / 2f
                path.moveTo(cx - half, cy - half)
                path.lineTo(cx + half, cy - half)
                path.lineTo(cx + half, cy + half)
                path.lineTo(cx - half, cy + half)
            }
            path.close()
        }
    }
    return path
}

fun buildBorderPath(geometry: IndiaGeometry, bucketScale: Float): Path {
    val path = Path()
    val minStep = MIN_STEP_PX
    for (index in geometry.rings.indices) {
        val line = geometry.rings[index]
        if (line.size < 4) continue
        var startX = 0f
        var startY = 0f
        var lastX = 0f
        var lastY = 0f
        var haveStart = false
        var started = false
        var i = 0
        while (i < line.size) {
            val x = line[i] * bucketScale
            val y = line[i + 1] * bucketScale
            if (!haveStart) {
                startX = x
                startY = y
                lastX = x
                lastY = y
                haveStart = true
            } else if (abs(x - lastX) + abs(y - lastY) >= minStep) {
                if (!started) {
                    path.moveTo(startX, startY)
                    started = true
                }
                path.lineTo(x, y)
                lastX = x
                lastY = y
            }
            i += 2
        }
    }
    return path
}

private const val MIN_STEP_PX = 0.8f

private const val MIN_ISLAND_PX = 1.6f

private const val SHAPES_MAGIC = 0x44534831

class DistrictShape internal constructor(
    val lgd: Int,
    val stateLgd: Int,
    val centroid: Offset,
    internal val rings: List<FloatArray>,
    val bounds: Rect
) {
    private var cachedBucket = Float.NaN
    private var cachedPath: Path? = null

    fun path(bucketScale: Float): Path {
        val cached = cachedPath
        if (cached != null && cachedBucket == bucketScale) return cached
        val path = Path().apply { fillType = PathFillType.EvenOdd }
        for (ring in rings) {
            if (ring.size < 6) continue
            path.moveTo(ring[0] * bucketScale, ring[1] * bucketScale)
            var i = 2
            while (i < ring.size) {
                path.lineTo(ring[i] * bucketScale, ring[i + 1] * bucketScale)
                i += 2
            }
            path.close()
        }
        cachedBucket = bucketScale
        cachedPath = path
        return path
    }

    fun contains(world: Offset): Boolean {
        if (!bounds.contains(world)) return false
        var inside = false
        for (ring in rings) {
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = ring[i * 2]
                val yi = ring[i * 2 + 1]
                val xj = ring[j * 2]
                val yj = ring[j * 2 + 1]
                if ((yi > world.y) != (yj > world.y) &&
                    world.x < (xj - xi) * (world.y - yi) / (yj - yi) + xi
                ) {
                    inside = !inside
                }
                j = i
            }
        }
        return inside
    }
}

fun loadDistrictShapes(res: Resources): List<DistrictShape> =
    res.openRawResource(R.raw.district_shapes).use { parseDistrictShapes(it) }

fun parseDistrictShapes(stream: InputStream): List<DistrictShape> {
    DataInputStream(stream.buffered()).use { input ->
        require(input.readInt() == SHAPES_MAGIC) { "district_shapes.bin: bad magic" }
        val minLon = input.readDouble()
        val maxLon = input.readDouble()
        val minLat = input.readDouble()
        val maxLat = input.readDouble()
        val count = input.readInt()

        val lonSpanRad = (maxLon - minLon) * PI / 180.0
        val topMercator = ln(tan(PI / 4.0 + maxLat.coerceIn(-85.05, 85.05) * PI / 360.0))
        val lonStep = (maxLon - minLon) / QUANT_MAX
        val latStep = (maxLat - minLat) / QUANT_MAX
        fun worldX(qx: Int): Float = (((minLon + qx * lonStep) - minLon) * PI / 180.0 / lonSpanRad).toFloat()
        fun worldY(qy: Int): Float {
            val lat = (minLat + qy * latStep).coerceIn(-85.05, 85.05)
            return ((topMercator - ln(tan(PI / 4.0 + lat * PI / 360.0))) / lonSpanRad).toFloat()
        }

        val shapes = ArrayList<DistrictShape>(count)
        repeat(count) {
            val lgd = (input.readInt().toLong() and 0xFFFFFFFFL).toInt()
            val stateLgd = input.readUnsignedShort()
            val centroid = Offset(worldX(input.readUnsignedShort()), worldY(input.readUnsignedShort()))
            val ringCount = input.readUnsignedShort()
            val rings = ArrayList<FloatArray>(ringCount)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            repeat(ringCount) {
                val n = input.readInt()
                val ring = FloatArray(n * 2)
                for (i in 0 until n) {
                    val x = worldX(input.readUnsignedShort())
                    val y = worldY(input.readUnsignedShort())
                    ring[i * 2] = x
                    ring[i * 2 + 1] = y
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                rings += ring
            }
            val bounds = if (rings.isEmpty()) Rect(centroid, centroid) else Rect(minX, minY, maxX, maxY)
            shapes += DistrictShape(lgd, stateLgd, centroid, rings, bounds)
        }
        return shapes
    }
}

fun stateBounds(shapes: List<DistrictShape>, stateLgd: Int): Rect? {
    var box: Rect? = null
    for (shape in shapes) {
        if (shape.stateLgd != stateLgd) continue
        box = box?.let {
            Rect(
                min(it.left, shape.bounds.left),
                min(it.top, shape.bounds.top),
                max(it.right, shape.bounds.right),
                max(it.bottom, shape.bounds.bottom)
            )
        } ?: shape.bounds
    }
    return box
}

