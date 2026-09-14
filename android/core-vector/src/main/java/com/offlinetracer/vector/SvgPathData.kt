package com.offlinetracer.vector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * SVG path data — writing it ([toD]) and reading all of it ([parse]).
 *
 * The parser implements the whole grammar, not the subset this app happens to emit: `M m L l H h
 * V v C c S s Q q T t A a Z z`, implicit repeated coordinate sets, numbers run together without
 * separators (`10-5`), exponent notation, and elliptical arcs. Anything less and importing an SVG
 * that some other tool wrote fails in a way the user reads as "this app is broken", which is fair.
 *
 * Arcs are converted to cubics on the way in ([arcToCubics]) because [VecSeg] has no arc case, and
 * every consumer — rasteriser, boolean ops, exporters — would otherwise need one.
 */
object SvgPathData {

    /**
     * Coordinates are clamped to this before formatting. `NaN`/`Infinity` in a `d` attribute make
     * the whole path invisible in every renderer, so a finite value is always emitted instead: a
     * wrong pixel is debuggable, a silently blank export is not.
     */
    private const val MAX_COORD = 1.0e7

    private val POW10 = longArrayOf(1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L)

    // ---------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------

    /**
     * Serialises [path] to an SVG `d` string with [precision] decimal places (0..6), in the **compact
     * canonical form** ALGORITHMS.md §10 fixes for both engines:
     *
     *  - no whitespace after a command letter — the letter is already a delimiter;
     *  - the command letter is omitted for a run of segments of the same type;
     *  - exactly one space between two numbers, and nothing before `Z`.
     *
     * The form is not cosmetic. A `d` attribute is the product's main export and a traced path carries
     * thousands of segments, so the two elisions remove roughly one character in eight of the largest
     * thing this app writes; it is also what every SVG optimiser emits, so a diff against one is a diff
     * about geometry rather than about whitespace. Both engines must spell it identically because §14
     * compares this string **exactly**.
     *
     * The one space between numbers is deliberately kept rather than dropped before a leading `-`
     * (`11-0.62` is legal SVG and this file's parser reads it): a single unconditional separator is one
     * rule instead of two, and it cannot become the one place where two independent implementations
     * disagree about when a delimiter is required.
     *
     * The result round-trips: `toD(parse(toD(p)).single())` is byte-identical to `toD(p)` for any path
     * built from lines, cubics and quads.
     */
    fun toD(path: VecPath, precision: Int = 2): String {
        val p = if (precision < 0) 0 else if (precision > 6) 6 else precision
        val sb = StringBuilder(24 + path.segments.size * 20)
        sb.append('M').append(num(path.start.x, p)).append(' ').append(num(path.start.y, p))
        // ' ' rather than 'M': a repeated coordinate pair after a moveto is an implicit *lineto*, so the
        // first L has to be written even though the letter before it was also a "move".
        var last = ' '
        for (seg in path.segments) {
            when (seg) {
                is VecSeg.Line -> {
                    sb.append(if (last == 'L') ' ' else 'L')
                    last = 'L'
                    sb.append(num(seg.to.x, p)).append(' ').append(num(seg.to.y, p))
                }
                is VecSeg.Cubic -> {
                    sb.append(if (last == 'C') ' ' else 'C')
                    last = 'C'
                    sb.append(num(seg.c1.x, p)).append(' ').append(num(seg.c1.y, p))
                    sb.append(' ').append(num(seg.c2.x, p)).append(' ').append(num(seg.c2.y, p))
                    sb.append(' ').append(num(seg.to.x, p)).append(' ').append(num(seg.to.y, p))
                }
                is VecSeg.Quad -> {
                    sb.append(if (last == 'Q') ' ' else 'Q')
                    last = 'Q'
                    sb.append(num(seg.c.x, p)).append(' ').append(num(seg.c.y, p))
                    sb.append(' ').append(num(seg.to.x, p)).append(' ').append(num(seg.to.y, p))
                }
            }
        }
        if (path.closed) sb.append('Z')
        return sb.toString()
    }

    /**
     * Fixed-point number formatting, locale-independent by construction.
     *
     * `String.format` is not used: it consults the default `Locale`, and on a device set to a
     * comma-decimal locale it would emit `1,5` — a `d` attribute that parses as two coordinates and
     * silently corrupts every exported file on that device.
     */
    internal fun num(v: Float, precision: Int): String {
        var d = v.toDouble()
        if (d.isNaN()) d = 0.0
        if (d > MAX_COORD) d = MAX_COORD
        if (d < -MAX_COORD) d = -MAX_COORD
        val p = if (precision < 0) 0 else if (precision > 6) 6 else precision
        val scale = POW10[p]
        var scaled = Math.round(d * scale)
        if (scaled == 0L) return "0"
        val sb = StringBuilder(16)
        if (scaled < 0) { sb.append('-'); scaled = -scaled }
        sb.append(scaled / scale)
        if (p > 0) {
            val frac = (scaled % scale).toString().padStart(p, '0').trimEnd('0')
            if (frac.isNotEmpty()) sb.append('.').append(frac)
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------

    /**
     * Parses a `d` attribute into one [VecPath] per subpath.
     *
     * Malformed input never throws: unparseable characters are skipped and a truncated coordinate
     * set reads as zero, so a partially-corrupt file imports as much geometry as it actually
     * contains. Returns an empty list for empty or entirely unparseable input.
     */
    fun parse(d: String): List<VecPath> {
        if (d.isEmpty()) return emptyList()
        return SvgPathParser(d).run()
    }

    /**
     * Converts one SVG elliptical arc, given in endpoint parameterisation, into up to four cubics.
     *
     * Follows the implementation notes in SVG 1.1 F.6.5, including the out-of-range radius
     * correction. Degenerate cases return what the specification requires: an empty list when the
     * endpoints coincide (the arc is omitted entirely), and a straight line as a single cubic when
     * either radius is zero.
     */
    fun arcToCubics(
        x0: Float, y0: Float, rx: Float, ry: Float, xRotDeg: Float,
        largeArc: Boolean, sweep: Boolean, x: Float, y: Float,
    ): List<VecSeg.Cubic> {
        if (!x0.isFinite() || !y0.isFinite() || !x.isFinite() || !y.isFinite()) return emptyList()
        if (abs(x - x0) < 1e-7f && abs(y - y0) < 1e-7f) return emptyList()

        var rxa = abs(rx.toDouble())
        var rya = abs(ry.toDouble())
        if (!rxa.isFinite() || !rya.isFinite() || rxa < 1e-9 || rya < 1e-9) {
            return listOf(lineAsCubic(x0.toDouble(), y0.toDouble(), x.toDouble(), y.toDouble()))
        }

        val phi = (if (xRotDeg.isFinite()) xRotDeg.toDouble() else 0.0) * PI / 180.0
        val cosP = cos(phi)
        val sinP = sin(phi)

        val dx2 = (x0 - x) / 2.0
        val dy2 = (y0 - y) / 2.0
        val x1p = cosP * dx2 + sinP * dy2
        val y1p = -sinP * dx2 + cosP * dy2

        // F.6.6: scale the radii up when they are too small to span the endpoints.
        val lambda = (x1p * x1p) / (rxa * rxa) + (y1p * y1p) / (rya * rya)
        if (lambda > 1.0) {
            val s = sqrt(lambda)
            rxa *= s
            rya *= s
        }

        val rxs = rxa * rxa
        val rys = rya * rya
        val den = rxs * y1p * y1p + rys * x1p * x1p
        if (den <= 0.0) return listOf(lineAsCubic(x0.toDouble(), y0.toDouble(), x.toDouble(), y.toDouble()))
        var numer = rxs * rys - den
        if (numer < 0.0) numer = 0.0
        var coef = sqrt(numer / den)
        if (largeArc == sweep) coef = -coef

        val cxp = coef * (rxa * y1p / rya)
        val cyp = coef * (-rya * x1p / rxa)
        val cx = cosP * cxp - sinP * cyp + (x0 + x) / 2.0
        val cy = sinP * cxp + cosP * cyp + (y0 + y) / 2.0

        val ux = (x1p - cxp) / rxa
        val uy = (y1p - cyp) / rya
        val vx = (-x1p - cxp) / rxa
        val vy = (-y1p - cyp) / rya

        val theta1 = atan2(uy, ux)
        var dTheta = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        if (!sweep && dTheta > 0.0) dTheta -= 2.0 * PI
        else if (sweep && dTheta < 0.0) dTheta += 2.0 * PI

        var count = ceil(abs(dTheta) / (PI / 2.0)).toInt()
        if (count < 1) count = 1
        if (count > 4) count = 4
        val delta = dTheta / count
        val k = 4.0 / 3.0 * tan(delta / 4.0)

        val out = ArrayList<VecSeg.Cubic>(count)
        var th = theta1
        var px = x0.toDouble()
        var py = y0.toDouble()
        for (seg in 0 until count) {
            val th2 = th + delta
            val cos1 = cos(th); val sin1 = sin(th)
            val cos2 = cos(th2); val sin2 = sin(th2)
            val ex = cx + rxa * cosP * cos2 - rya * sinP * sin2
            val ey = cy + rxa * sinP * cos2 + rya * cosP * sin2
            val d1x = -rxa * cosP * sin1 - rya * sinP * cos1
            val d1y = -rxa * sinP * sin1 + rya * cosP * cos1
            val d2x = -rxa * cosP * sin2 - rya * sinP * cos2
            val d2y = -rxa * sinP * sin2 + rya * cosP * cos2
            out.add(
                VecSeg.Cubic(
                    VecPoint((px + k * d1x).toFloat(), (py + k * d1y).toFloat()),
                    VecPoint((ex - k * d2x).toFloat(), (ey - k * d2y).toFloat()),
                    VecPoint(ex.toFloat(), ey.toFloat()),
                )
            )
            px = ex
            py = ey
            th = th2
        }
        return out
    }

    private fun lineAsCubic(x0: Double, y0: Double, x1: Double, y1: Double): VecSeg.Cubic =
        VecSeg.Cubic(
            VecPoint((x0 + (x1 - x0) / 3.0).toFloat(), (y0 + (y1 - y0) / 3.0).toFloat()),
            VecPoint((x0 + (x1 - x0) * 2.0 / 3.0).toFloat(), (y0 + (y1 - y0) * 2.0 / 3.0).toFloat()),
            VecPoint(x1.toFloat(), y1.toFloat()),
        )
}

/**
 * Single-pass recursive-descent scanner over a `d` attribute.
 *
 * Every read advances the cursor even when it fails, so no input can make the loop spin: a parser
 * that hangs on a malformed file is worse than one that produces a wrong path.
 */
private class SvgPathParser(private val s: String) {

    private var i = 0
    private val out = ArrayList<VecPath>()
    private var segs = ArrayList<VecSeg>()
    private var startPt: VecPoint? = null
    private var explicitMove = false

    private var cx = 0f
    private var cy = 0f
    private var subX = 0f
    private var subY = 0f
    private var ctrlX = 0f
    private var ctrlY = 0f
    private var quadX = 0f
    private var quadY = 0f
    private var prev = ' '

    fun run(): List<VecPath> {
        var cmd = ' '
        while (true) {
            skipSeparators()
            if (i >= s.length) break
            val c = s[i]
            if (isCommand(c)) {
                cmd = c
                i++
            } else if (cmd == ' ') {
                i++
                continue
            } else if (cmd == 'Z' || cmd == 'z') {
                // Numbers after a closepath are not legal; drop them rather than mis-binding them.
                i++
                continue
            } else if (cmd == 'M') {
                cmd = 'L'
            } else if (cmd == 'm') {
                cmd = 'l'
            }
            exec(cmd)
            prev = cmd
        }
        flush(false)
        return out
    }

    private fun exec(cmd: Char) {
        val rel = cmd.isLowerCase()
        when (cmd.uppercaseChar()) {
            'M' -> {
                flush(false)
                val x = readNumber()
                val y = readNumber()
                cx = if (rel) cx + x else x
                cy = if (rel) cy + y else y
                subX = cx
                subY = cy
                startPt = VecPoint(cx, cy)
                segs = ArrayList()
                explicitMove = true
            }
            'L' -> {
                ensureStart()
                val x = readNumber()
                val y = readNumber()
                cx = if (rel) cx + x else x
                cy = if (rel) cy + y else y
                segs.add(VecSeg.Line(VecPoint(cx, cy)))
            }
            'H' -> {
                ensureStart()
                val x = readNumber()
                cx = if (rel) cx + x else x
                segs.add(VecSeg.Line(VecPoint(cx, cy)))
            }
            'V' -> {
                ensureStart()
                val y = readNumber()
                cy = if (rel) cy + y else y
                segs.add(VecSeg.Line(VecPoint(cx, cy)))
            }
            'C' -> {
                ensureStart()
                val x1 = readNumber(); val y1 = readNumber()
                val x2 = readNumber(); val y2 = readNumber()
                val x = readNumber(); val y = readNumber()
                val c1x = if (rel) cx + x1 else x1
                val c1y = if (rel) cy + y1 else y1
                val c2x = if (rel) cx + x2 else x2
                val c2y = if (rel) cy + y2 else y2
                cx = if (rel) cx + x else x
                cy = if (rel) cy + y else y
                segs.add(VecSeg.Cubic(VecPoint(c1x, c1y), VecPoint(c2x, c2y), VecPoint(cx, cy)))
                ctrlX = c2x
                ctrlY = c2y
            }
            'S' -> {
                ensureStart()
                val x2 = readNumber(); val y2 = readNumber()
                val x = readNumber(); val y = readNumber()
                // The reflection is only defined when the previous command was itself a cubic;
                // after anything else the first control point coincides with the current point.
                val smooth = prev == 'C' || prev == 'c' || prev == 'S' || prev == 's'
                val c1x = if (smooth) 2f * cx - ctrlX else cx
                val c1y = if (smooth) 2f * cy - ctrlY else cy
                val c2x = if (rel) cx + x2 else x2
                val c2y = if (rel) cy + y2 else y2
                cx = if (rel) cx + x else x
                cy = if (rel) cy + y else y
                segs.add(VecSeg.Cubic(VecPoint(c1x, c1y), VecPoint(c2x, c2y), VecPoint(cx, cy)))
                ctrlX = c2x
                ctrlY = c2y
            }
            'Q' -> {
                ensureStart()
                val x1 = readNumber(); val y1 = readNumber()
                val x = readNumber(); val y = readNumber()
                val qx = if (rel) cx + x1 else x1
                val qy = if (rel) cy + y1 else y1
                cx = if (rel) cx + x else x
                cy = if (rel) cy + y else y
                segs.add(VecSeg.Quad(VecPoint(qx, qy), VecPoint(cx, cy)))
                quadX = qx
                quadY = qy
            }
            'T' -> {
                ensureStart()
                val x = readNumber(); val y = readNumber()
                val smooth = prev == 'Q' || prev == 'q' || prev == 'T' || prev == 't'
                val qx = if (smooth) 2f * cx - quadX else cx
                val qy = if (smooth) 2f * cy - quadY else cy
                cx = if (rel) cx + x else x
                cy = if (rel) cy + y else y
                segs.add(VecSeg.Quad(VecPoint(qx, qy), VecPoint(cx, cy)))
                quadX = qx
                quadY = qy
            }
            'A' -> {
                ensureStart()
                val rx = readNumber()
                val ry = readNumber()
                val rot = readNumber()
                val large = readFlag()
                val sweep = readFlag()
                val x = readNumber()
                val y = readNumber()
                val ex = if (rel) cx + x else x
                val ey = if (rel) cy + y else y
                val cubics = SvgPathData.arcToCubics(cx, cy, rx, ry, rot, large, sweep, ex, ey)
                for (c in cubics) segs.add(c)
                cx = ex
                cy = ey
            }
            'Z' -> {
                if (startPt != null) flush(true)
                cx = subX
                cy = subY
                startPt = VecPoint(subX, subY)
                segs = ArrayList()
                explicitMove = false
            }
        }
    }

    /** Path data that does not begin with a moveto is illegal; treat the origin as the start. */
    private fun ensureStart() {
        if (startPt == null) startPt = VecPoint(cx, cy)
    }

    private fun flush(closed: Boolean) {
        val sp = startPt
        if (sp != null && (segs.isNotEmpty() || explicitMove || closed)) {
            out.add(VecPath(sp, segs, closed))
        }
        segs = ArrayList()
    }

    private fun isCommand(c: Char): Boolean = when (c) {
        'M', 'm', 'L', 'l', 'H', 'h', 'V', 'v', 'C', 'c', 'S', 's',
        'Q', 'q', 'T', 't', 'A', 'a', 'Z', 'z' -> true
        else -> false
    }

    private fun skipSeparators() {
        while (i < s.length) {
            val c = s[i]
            // 12 is the form feed the SVG grammar counts as whitespace; it is written by code
            // point so this source file never carries a raw control character of its own.
            val ws = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c.code == 12
            if (!ws) return
            i++
        }
    }

    private fun readNumber(): Float {
        skipSeparators()
        val start = i
        val n = s.length
        if (i < n && (s[i] == '+' || s[i] == '-')) i++
        while (i < n && s[i] in '0'..'9') i++
        if (i < n && s[i] == '.') {
            i++
            while (i < n && s[i] in '0'..'9') i++
        }
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            val save = i
            i++
            if (i < n && (s[i] == '+' || s[i] == '-')) i++
            if (i < n && s[i] in '0'..'9') {
                while (i < n && s[i] in '0'..'9') i++
            } else {
                i = save
            }
        }
        if (i == start) {
            // Not a number at all. Consume one character so the caller cannot loop forever.
            if (i < n) i++
            return 0f
        }
        val v = s.substring(start, i).toFloatOrNull() ?: 0f
        return if (v.isFinite()) v else 0f
    }

    /** Arc flags are a single character in the grammar; `a1 1 0 011 1` is legal and common. */
    private fun readFlag(): Boolean {
        skipSeparators()
        if (i < s.length && (s[i] == '0' || s[i] == '1')) {
            val f = s[i] == '1'
            i++
            return f
        }
        return readNumber() != 0f
    }
}
