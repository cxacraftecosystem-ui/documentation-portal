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
 * The national outline the map screen draws, the interior borders drawn inside it, and the projection
 * every pin shares with both.
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
 * PROVENANCE OF res/raw/state_borders.bin AND res/raw/district_borders.bin. Built by
 * `scripts/build_boundaries.py` — read its module docstring before touching either file. In short: a
 * district source is decomposed by an undirected edge tally, so an edge shared by two districts of
 * DIFFERENT states is a state border, one shared by two districts of the SAME state is a district
 * border, and an edge appearing once is the coast or the international frontier and is DROPPED,
 * because the outline above already draws it. That is what makes every interior border ship exactly
 * once (as polygons, 86.4% of the source's edges would be shipped twice) and what makes the national
 * boundary identical at all three detail levels by construction. 81 polylines / 25,764 bytes for the
 * states, 972 polylines / 81,808 bytes for the districts.
 *
 * TWO THINGS THE BORDER FILES ARE NOT. They are not rings — see [buildBorderPath] for what closing
 * them would draw. And they are not authoritative about the national frontier: measured against
 * `india-osm.geojson`, the district source's outer extent differs by up to ~0.02 degrees (~2 km), so
 * MapScreen clips every border layer to the outline rather than trusting two datasets to agree.
 *
 * NOT to be confused with `MapPickerDialog` in FieldComponents.kt, which is a WebView over live OSM
 * raster tiles. That one is for CAPTURING a coordinate while online during data entry, where OSM's
 * own boundary is a rendering detail of a scratch pad. This one is the repository's own depiction of
 * the country, and is read-only and offline.
 */

/** File header: magic, four float64 bounds, int32 record count. Same for all three IND1 assets. */
private const val OUTLINE_MAGIC = 0x494E4431 // "IND1"

/** Records are stored on a uint16 grid spanning the bounds, so a coordinate is two bytes per axis. */
private const val QUANT_MAX = 65535.0

/**
 * One IND1 asset in a projected, unit-width space, plus the projection that put it there.
 *
 * World space is Web Mercator normalised so that x runs 0..1 across the country's longitude span and
 * y uses THE SAME scale (so it runs 0..[aspect], downward). Equal scaling on both axes is what keeps
 * the shape conformal: pins and coastline are placed by one function, so a pin cannot drift off the
 * land it belongs to at any zoom.
 *
 * ALL THREE ASSETS DECLARE EFFECTIVELY THE SAME BOUNDS, which is why MapScreen can draw all of them
 * under one translate and one scale. Not bit-identical, and it is worth knowing by how much: the
 * outline's header carries the source's raw extent (68.2056009..97.395561 E, 6.7559971..37.084107 N)
 * while the border files carry it rounded to the three decimal places their build quantises to
 * (68.206..97.394, 6.756..37.082). Each file's own bounds are used to un-quantise its own grid, so the
 * recovered longitude and latitude are right either way; the only residue is that a border's world
 * space is scaled and offset from the outline's by ~7e-5 of the country's width. Measured at the
 * extremes that is under 0.06 px on a 1000 px-wide canvas — a twentieth of the hairline the frontier
 * is stroked with — and at the coast, where it would be visible if it were anything, MapScreen's clip
 * to the outline absorbs it. Do not "fix" it by reprojecting borders through the outline's bounds
 * unless that number grows: it would make loading a border depend on the outline already being read.
 *
 * Two more things follow from sharing the class. [rings] holds RINGS for the outline and
 * OPEN POLYLINES for the two border files, so which builder walks it is not interchangeable
 * ([buildOutlinePath] vs [buildBorderPath]). And [aspect] is only the COUNTRY's proportion when this
 * is the outline: the border files contain no coastline, so their lowest point is not Indira Point and
 * their aspect is not the canvas's. MapScreen sizes its frame from the outline alone.
 */
class IndiaGeometry internal constructor(
    /**
     * Interleaved x,y world coordinates, one array per record, in file order — which is largest ring
     * first for the outline and unordered for the border files, since nothing that draws them cares.
     */
    internal val rings: List<FloatArray>,
    /** Per-record bounds in world space, so a whole record can be culled or dotted without walking it. */
    internal val ringBounds: List<Rect>,
    /** Height of this geometry in world units; the country's width is 1 by construction. */
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

/**
 * Parses res/raw/india_outline.bin — 308 rings, 11,649 points. Call off the main thread.
 *
 * The signature is unchanged on purpose: MapScreen calls this, and the outline is the one geometry
 * that must load whatever detail level is selected, because it is both the national boundary and the
 * clip every border layer is drawn inside.
 */
fun loadIndiaGeometry(context: Context): IndiaGeometry =
    loadIndGeometry(context, R.raw.india_outline)

/**
 * Parses res/raw/state_borders.bin — 81 open polylines, 6,350 points, 25,764 bytes. Call off the main
 * thread.
 *
 * Draw with [buildBorderPath], never [buildOutlinePath]. These are interior borders only: the coast
 * and the international frontier were dropped at build time because the outline already draws them.
 */
fun loadStateBorders(context: Context): IndiaGeometry =
    loadIndGeometry(context, R.raw.state_borders)

/**
 * Parses res/raw/district_borders.bin — 972 open polylines, 19,470 points, 81,808 bytes. Call off the
 * main thread, and only when the DISTRICT level is actually wanted: this is the largest of the three
 * assets and the other two levels never draw it.
 *
 * Draw with [buildBorderPath], never [buildOutlinePath].
 */
fun loadDistrictBorders(context: Context): IndiaGeometry =
    loadIndGeometry(context, R.raw.district_borders)

/**
 * The one IND1 reader. Shared by all three assets because the format is one format — magic, four
 * float64 bounds, an int32 record count, then per record an int32 length and that many uint16 x/y
 * pairs on a grid spanning the bounds — and a second copy of this loop is a second place for the
 * quantisation to be got subtly wrong.
 *
 * It does not know or care whether the records are closed rings or open polylines: that distinction
 * belongs to the DRAWING, and the two builders below are what carry it.
 */
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
 *
 * FOR RINGS ONLY. The two border assets are open polylines; [buildBorderPath] draws those, and its
 * doc says what each of the two behaviours above would do to one.
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

/**
 * The interior borders as one [Path] in pixels at [bucketScale] — state or district, whichever
 * geometry is handed in. Stroke it; there is nothing here to fill.
 *
 * SHARES THE DECIMATION, DELIBERATELY DIFFERS ON EVERYTHING ELSE. The same [MIN_STEP_PX] rule as
 * [buildOutlinePath], for the same reason and once per bucket rather than per frame — but the records
 * in the border files are OPEN POLYLINES, not rings, and the two things [buildOutlinePath] does to a
 * ring are both wrong for a polyline:
 *
 *   NO close(). A ring's last point is its first, so closing it costs nothing; a border's two ends are
 *   two different places on the map. Closing the Rajasthan/Gujarat border would draw a straight line
 *   from the Kutch coast back up to the Punjab tri-point, straight across the interior of India — a
 *   line no border follows, laid over three other states, and indistinguishable at a glance from a
 *   real border. Every one of the 81 state and 972 district polylines would grow one such line.
 *
 *   NO MIN_ISLAND_PX SQUARE. That substitution exists so Lakshadweep and the Nicobars cannot vanish:
 *   an island is a TERRITORY, and drawing it a pixel too big is honest where drawing nothing is not.
 *   A border segment is not a territory — it is a shared edge whose neighbours on both sides are
 *   already drawn — so a sub-pixel one carries no information that is lost by omitting it, while a
 *   1.6px box floating in the middle of Madhya Pradesh reads as a place the map is marking. A polyline
 *   that decimates to fewer than two emitted points is therefore SKIPPED: the `moveTo` is deferred
 *   until a second point survives, so a skipped polyline leaves no dangling subpath behind either.
 */
fun buildBorderPath(geometry: IndiaGeometry, bucketScale: Float): Path {
    val path = Path()
    val minStep = MIN_STEP_PX
    for (index in geometry.rings.indices) {
        val line = geometry.rings[index]
        if (line.size < 4) continue // A one-point "border" is nothing to draw a line between.
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
        // No close(): see above. A polyline where `started` stayed false emitted nothing at all, which
        // is the intended outcome for a border shorter than a pixel.
    }
    return path
}

/** Manhattan distance below which a point adds nothing the screen can show. */
private const val MIN_STEP_PX = 0.8f

/** Smallest a territory may render. Below this it is drawn as a dot so it cannot disappear. */
private const val MIN_ISLAND_PX = 1.6f
