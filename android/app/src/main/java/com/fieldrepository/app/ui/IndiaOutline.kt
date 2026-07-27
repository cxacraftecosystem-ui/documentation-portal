package com.fieldrepository.app.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.fieldrepository.app.R
import java.io.DataInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/*
 * The national outline the map screen draws, and the projection every pin shares with it.
 *
 * WHY A BAKED OUTLINE AND NOT A MAP SDK. Google Maps, Mapbox and every OSM tile SDK draw their own
 * boundary for Jammu & Kashmir, which is the one thing this screen may not get wrong — depicting
 * India by the official Government of India depiction is a legal requirement here. They also need a
 * key, a licence and a network, and this app is used in villages with no signal. So the boundary
 * ships inside the APK as geometry we control, and Compose draws it.
 *
 * PROVENANCE OF res/raw/india_outline.bin. Simplified from datameet/maps `Country/india-osm.geojson`
 * (6,651,148 bytes, one MultiPolygon, 308 rings, 245,160 coordinates), which carries the official
 * depiction. Douglas-Peucker at 5e-3 degrees with each ring's four extreme points pinned, then
 * quantised to a uint16 grid: 308 rings, 11,649 points, 47,868 bytes. Pinning the extremes is not a
 * nicety — a plain tolerance sweep deletes Indira Point (6.7560 N, a 342-point ring) and the western
 * tip at 68.2056 E (a 77-point ring), silently shortening the country. The shipped bytes reproduce
 * the source bounding box exactly and were re-tested point-in-polygon after simplification:
 * Aksai Chin, Gilgit-Baltistan, Muzaffarabad, Arunachal Pradesh and Srinagar all INSIDE; Lhasa and
 * Karachi OUTSIDE. Re-run those seven if this file is ever regenerated.
 *
 * NOT to be confused with `MapPickerDialog` in FieldComponents.kt, which is a WebView over live OSM
 * raster tiles. That one is for CAPTURING a coordinate while online during data entry, where OSM's
 * own boundary is a rendering detail of a scratch pad. This one is the repository's own depiction of
 * the country, and is read-only and offline.
 */

/** File header: magic, four float64 bounds, int32 ring count. */
private const val OUTLINE_MAGIC = 0x494E4431 // "IND1"

/** Rings are stored on a uint16 grid spanning the bounds, so a coordinate is two bytes per axis. */
private const val QUANT_MAX = 65535.0

/**
 * The outline in a projected, unit-width space, plus the projection that put it there.
 *
 * World space is Web Mercator normalised so that x runs 0..1 across the country's longitude span and
 * y uses THE SAME scale (so it runs 0..[aspect], downward). Equal scaling on both axes is what keeps
 * the shape conformal: pins and coastline are placed by one function, so a pin cannot drift off the
 * land it belongs to at any zoom.
 */
class IndiaGeometry internal constructor(
    /** Interleaved x,y world coordinates, one array per ring, largest ring first. */
    internal val rings: List<FloatArray>,
    /** Per-ring bounds in world space, so a whole ring can be culled or dotted without walking it. */
    internal val ringBounds: List<Rect>,
    /** Height of the country in world units; width is 1 by construction. */
    val aspect: Float,
    private val minLon: Double,
    private val maxLon: Double,
    private val minLat: Double,
    private val maxLat: Double
) {
    private val lonSpanRad = (maxLon - minLon) * PI / 180.0
    private val topMercator = mercatorY(maxLat)

    /** Where a coordinate falls in world space. Shared by the outline, every pin and every hit test. */
    fun world(latitude: Double, longitude: Double): Offset {
        val x = (longitude - minLon) * PI / 180.0 / lonSpanRad
        val y = (topMercator - mercatorY(latitude)) / lonSpanRad
        return Offset(x.toFloat(), y.toFloat())
    }

    companion object {
        private fun mercatorY(latitude: Double): Double {
            // Clamped short of the poles, where the projection diverges. India comes nowhere near
            // them; the clamp exists so a corrupt coordinate cannot produce an infinite path point.
            val clamped = latitude.coerceIn(-85.05, 85.05)
            return ln(tan(PI / 4.0 + clamped * PI / 360.0))
        }
    }
}

/**
 * Great-circle distance in metres. Used only to describe how tightly the captured points cluster,
 * which is the one honest thing this screen can say about a dataset recorded at a single venue.
 */
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

/** Parses res/raw/india_outline.bin. Call off the main thread; it allocates ~12k points. */
fun loadIndiaGeometry(context: Context): IndiaGeometry {
    DataInputStream(context.resources.openRawResource(R.raw.india_outline).buffered()).use { input ->
        require(input.readInt() == OUTLINE_MAGIC) { "india_outline.bin: bad magic" }
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
                // readUnsignedShort, not readShort: the grid is unsigned and the top half of the
                // country would otherwise arrive as negative longitudes.
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

// ---------------------------------------------------------------------------------------------
// Zoom buckets and the cached path
// ---------------------------------------------------------------------------------------------

/**
 * How many discrete path builds there are per doubling of scale.
 *
 * The whole point of bucketing: a [Path] with 11,649 points is expensive to BUILD and cheap for Skia
 * to transform, so it is built once per bucket and only translated and residually scaled per frame.
 * Two per octave means a rebuild at most every 41% of zoom and a residual scale never below 0.71 —
 * far too small a difference to see, and it turns a per-frame allocation into roughly one per pinch.
 */
private const val BUCKETS_PER_OCTAVE = 2.0

/** The scale a given pixels-per-world-unit rounds up to. Path geometry is only ever built at these. */
fun outlineBucketScale(scale: Float): Float {
    val safe = scale.coerceAtLeast(1f)
    return 2.0.pow(ceil(log2(safe.toDouble()) * BUCKETS_PER_OCTAVE) / BUCKETS_PER_OCTAVE).toFloat()
}

/**
 * The outline as one [Path] in pixels at [bucketScale], with two things baked in that can only be
 * decided once the scale is known.
 *
 * Decimation: at national zoom the country is a few hundred pixels wide and the source has ~33
 * points per pixel, so points closer than [MIN_STEP_PX] to the last kept one are dropped. This is
 * done here, once per bucket, rather than being paid on every frame.
 *
 * Island rescue: a ring smaller than [MIN_ISLAND_PX] is emitted as a small square instead of its
 * true shape. Lakshadweep and the Nicobars are sub-pixel at national zoom and a degenerate subpath
 * renders as nothing at all — which would quietly drop island territory from the map of the country.
 */
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
            // A ring that decimated down to a sliver still has real extent (its bounds cleared the
            // island test), so give it the square rather than closing a two-point subpath to nothing.
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

/** Manhattan distance below which a point adds nothing the screen can show. */
private const val MIN_STEP_PX = 0.8f

/** Smallest a territory may render. Below this it is drawn as a dot so it cannot disappear. */
private const val MIN_ISLAND_PX = 1.6f
