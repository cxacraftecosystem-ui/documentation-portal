package com.offlinetracer.imaging

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

object Thinning {

    fun zhangSuen(src: Mask, maxIterations: Int = 200): Mask {
        val w = src.width
        val h = src.height
        val d = src.data.copyOf()
        if (maxIterations <= 0) return Mask(w, h, d)
        var fg = 0
        for (b in d) if (b) fg++
        if (fg == 0) return Mask(w, h, d)

        val del = IntArray(fg)
        var iter = 0
        while (iter < maxIterations) {
            var changed = false
            var step = 0
            while (step < 2) {
                var n = 0
                for (y in 0 until h) {
                    val row = y * w
                    for (x in 0 until w) {
                        val i = row + x
                        if (!d[i]) continue
                        val p2 = at(d, w, h, x, y - 1)
                        val p3 = at(d, w, h, x + 1, y - 1)
                        val p4 = at(d, w, h, x + 1, y)
                        val p5 = at(d, w, h, x + 1, y + 1)
                        val p6 = at(d, w, h, x, y + 1)
                        val p7 = at(d, w, h, x - 1, y + 1)
                        val p8 = at(d, w, h, x - 1, y)
                        val p9 = at(d, w, h, x - 1, y - 1)

                        var b = 0
                        if (p2) b++
                        if (p3) b++
                        if (p4) b++
                        if (p5) b++
                        if (p6) b++
                        if (p7) b++
                        if (p8) b++
                        if (p9) b++
                        if (b < 2 || b > 6) continue

                        var a = 0
                        if (!p2 && p3) a++
                        if (!p3 && p4) a++
                        if (!p4 && p5) a++
                        if (!p5 && p6) a++
                        if (!p6 && p7) a++
                        if (!p7 && p8) a++
                        if (!p8 && p9) a++
                        if (!p9 && p2) a++
                        if (a != 1) continue

                        if (step == 0) {
                            if (p2 && p4 && p6) continue
                            if (p4 && p6 && p8) continue
                        } else {
                            if (p2 && p4 && p8) continue
                            if (p2 && p6 && p8) continue
                        }
                        del[n++] = i
                    }
                }
                if (n > 0) {
                    for (k in 0 until n) d[del[k]] = false
                    changed = true
                }
                step++
            }
            if (!changed) break
            iter++
        }
        return Mask(w, h, d)
    }

    fun guoHall(src: Mask, maxIterations: Int = 200): Mask {
        val w = src.width
        val h = src.height
        val d = src.data.copyOf()
        if (maxIterations <= 0) return Mask(w, h, d)
        var fg = 0
        for (b in d) if (b) fg++
        if (fg == 0) return Mask(w, h, d)

        val del = IntArray(fg)
        var iter = 0
        while (iter < maxIterations) {
            var changed = false
            var step = 0
            while (step < 2) {
                var n = 0
                for (y in 0 until h) {
                    val row = y * w
                    for (x in 0 until w) {
                        val i = row + x
                        if (!d[i]) continue
                        val p2 = at(d, w, h, x, y - 1)
                        val p3 = at(d, w, h, x + 1, y - 1)
                        val p4 = at(d, w, h, x + 1, y)
                        val p5 = at(d, w, h, x + 1, y + 1)
                        val p6 = at(d, w, h, x, y + 1)
                        val p7 = at(d, w, h, x - 1, y + 1)
                        val p8 = at(d, w, h, x - 1, y)
                        val p9 = at(d, w, h, x - 1, y - 1)

                        var c = 0
                        if (!p2 && (p3 || p4)) c++
                        if (!p4 && (p5 || p6)) c++
                        if (!p6 && (p7 || p8)) c++
                        if (!p8 && (p9 || p2)) c++
                        if (c != 1) continue

                        var n1 = 0
                        if (p9 || p2) n1++
                        if (p3 || p4) n1++
                        if (p5 || p6) n1++
                        if (p7 || p8) n1++
                        var n2 = 0
                        if (p2 || p3) n2++
                        if (p4 || p5) n2++
                        if (p6 || p7) n2++
                        if (p8 || p9) n2++
                        val nn = if (n1 < n2) n1 else n2
                        if (nn < 2 || nn > 3) continue

                        val m = if (step == 0) (p6 || p7 || !p9) && p8 else (p2 || p3 || !p5) && p4
                        if (m) continue

                        del[n++] = i
                    }
                }
                if (n > 0) {
                    for (k in 0 until n) d[del[k]] = false
                    changed = true
                }
                step++
            }
            if (!changed) break
            iter++
        }
        return Mask(w, h, d)
    }

    fun neighbourCount(m: Mask, x: Int, y: Int): Int {
        var n = 0
        if (m.safe(x, y - 1)) n++
        if (m.safe(x + 1, y - 1)) n++
        if (m.safe(x + 1, y)) n++
        if (m.safe(x + 1, y + 1)) n++
        if (m.safe(x, y + 1)) n++
        if (m.safe(x - 1, y + 1)) n++
        if (m.safe(x - 1, y)) n++
        if (m.safe(x - 1, y - 1)) n++
        return n
    }

    fun transitions(m: Mask, x: Int, y: Int): Int {
        val p2 = m.safe(x, y - 1)
        val p3 = m.safe(x + 1, y - 1)
        val p4 = m.safe(x + 1, y)
        val p5 = m.safe(x + 1, y + 1)
        val p6 = m.safe(x, y + 1)
        val p7 = m.safe(x - 1, y + 1)
        val p8 = m.safe(x - 1, y)
        val p9 = m.safe(x - 1, y - 1)
        var a = 0
        if (!p2 && p3) a++
        if (!p3 && p4) a++
        if (!p4 && p5) a++
        if (!p5 && p6) a++
        if (!p6 && p7) a++
        if (!p7 && p8) a++
        if (!p8 && p9) a++
        if (!p9 && p2) a++
        return a
    }

    fun pruneSpurs(skeleton: Mask, minBranchLength: Int, maxRounds: Int = 10): Mask {
        val w = skeleton.width
        val h = skeleton.height
        val d = skeleton.data.copyOf()
        if (minBranchLength <= 0 || maxRounds <= 0) return Mask(w, h, d)

        val limit = if (minBranchLength > 65536) 65536 else minBranchLength
        val path = IntArray(limit)
        val toDelete = BooleanArray(d.size)

        var round = 0
        while (round < maxRounds) {
            java.util.Arrays.fill(toDelete, false)
            var removedAny = false
            for (start in d.indices) {
                if (!d[start]) continue
                if (degree(d, w, h, start) != 1) continue

                var prev = -1
                var cur = start
                var n = 0
                var hitJunction = false
                while (n < limit) {
                    if (n > 0 && crossing(d, w, h, cur) >= 3) {
                        hitJunction = true
                        break
                    }
                    path[n++] = cur
                    val nxt = pickNext(d, w, h, cur, prev)
                    if (nxt < 0) break
                    prev = cur
                    cur = nxt
                }
                if (hitJunction && n < limit) {
                    for (k in 0 until n) toDelete[path[k]] = true
                    removedAny = true
                }
            }
            if (!removedAny) break
            for (i in d.indices) if (toDelete[i]) d[i] = false
            round++
        }
        return Mask(w, h, d)
    }

    fun bridgeEndpoints(skeleton: Mask, maxGap: Int, maxAngleDegrees: Float = 60f): Mask {
        val w = skeleton.width
        val h = skeleton.height
        val d = skeleton.data.copyOf()
        if (maxGap <= 0) return Mask(w, h, d)
        val gap = if (maxGap > 1024) 1024 else maxGap

        val eps = endpointIndices(d, w, h)
        val m = eps.size
        if (m < 2 || m > 0xFFFFF) return Mask(w, h, d)

        val dirX = FloatArray(m)
        val dirY = FloatArray(m)
        val hasDir = BooleanArray(m)
        for (i in 0 until m) {
            val e = eps[i]
            val back = walkBack(d, w, h, e, 5)
            if (back == e) continue
            val vx = (e % w - back % w).toFloat()
            val vy = (e / w - back / w).toFloat()
            val len = sqrt(vx * vx + vy * vy)
            if (len > 1e-6f) {
                dirX[i] = vx / len
                dirY[i] = vy / len
                hasDir[i] = true
            }
        }

        val gw = (w + gap - 1) / gap
        val gh = (h + gap - 1) / gap
        val cellOf = IntArray(m)
        val cellStart = IntArray(gw * gh + 1)
        for (i in 0 until m) {
            val c = (eps[i] / w / gap) * gw + (eps[i] % w / gap)
            cellOf[i] = c
            cellStart[c + 1]++
        }
        for (c in 1..gw * gh) cellStart[c] += cellStart[c - 1]
        val cursor = cellStart.copyOf()
        val order = IntArray(m)
        for (i in 0 until m) {
            order[cursor[cellOf[i]]] = i
            cursor[cellOf[i]]++
        }

        val g2 = gap * gap
        var keys = LongArray(256)
        var nk = 0
        for (i in 0 until m) {
            if (!hasDir[i]) continue
            val xi = eps[i] % w
            val yi = eps[i] / w
            val cx = xi / gap
            val cy = yi / gap
            var ny = if (cy > 0) cy - 1 else 0
            val nyEnd = if (cy + 1 < gh) cy + 1 else gh - 1
            while (ny <= nyEnd) {
                var nx = if (cx > 0) cx - 1 else 0
                val nxEnd = if (cx + 1 < gw) cx + 1 else gw - 1
                while (nx <= nxEnd) {
                    val c = ny * gw + nx
                    var p = cellStart[c]
                    val end = cellStart[c + 1]
                    while (p < end) {
                        val j = order[p]
                        p++
                        if (j <= i || !hasDir[j]) continue
                        val dx = eps[j] % w - xi
                        val dy = eps[j] / w - yi
                        val dist2 = dx * dx + dy * dy
                        if (dist2 == 0 || dist2 > g2) continue
                        if (nk == keys.size) {
                            if (keys.size >= (1 shl 22)) break
                            keys = keys.copyOf(keys.size * 2)
                        }
                        keys[nk++] = (dist2.toLong() shl 40) or (i.toLong() shl 20) or j.toLong()
                    }
                    nx++
                }
                ny++
            }
        }
        if (nk == 0) return Mask(w, h, d)

        java.util.Arrays.sort(keys, 0, nk)
        val angle = if (maxAngleDegrees < 0f) 0f else if (maxAngleDegrees > 180f) 180f else maxAngleDegrees
        val cosLimit = cos(angle.toDouble() * PI / 180.0).toFloat()
        val used = BooleanArray(m)
        for (k in 0 until nk) {
            val key = keys[k]
            val i = ((key ushr 20) and 0xFFFFFL).toInt()
            val j = (key and 0xFFFFFL).toInt()
            if (used[i] || used[j]) continue
            val facing = dirX[i] * -dirX[j] + dirY[i] * -dirY[j]
            if (facing <= cosLimit) continue
            if (crossesInk(d, w, h, eps[i], eps[j])) continue
            drawLine(d, w, h, eps[i], eps[j])
            used[i] = true
            used[j] = true
        }
        return Mask(w, h, d)
    }

    fun endpoints(skeleton: Mask): IntArray =
        collect(skeleton.data, skeleton.width, skeleton.height, 1, 1)

    fun junctions(skeleton: Mask): IntArray =
        collect(skeleton.data, skeleton.width, skeleton.height, 3, 8)

    private fun collect(d: BooleanArray, w: Int, h: Int, lo: Int, hi: Int): IntArray {
        var n = 0
        for (i in d.indices) {
            if (!d[i]) continue
            val deg = degree(d, w, h, i)
            if (deg in lo..hi) n++
        }
        val out = IntArray(n)
        var k = 0
        for (i in d.indices) {
            if (!d[i]) continue
            val deg = degree(d, w, h, i)
            if (deg in lo..hi) out[k++] = i
        }
        return out
    }

    private fun endpointIndices(d: BooleanArray, w: Int, h: Int): IntArray = collect(d, w, h, 1, 1)

    private fun at(d: BooleanArray, w: Int, h: Int, x: Int, y: Int): Boolean =
        if (x < 0 || y < 0 || x >= w || y >= h) false else d[y * w + x]

    private fun degree(d: BooleanArray, w: Int, h: Int, idx: Int): Int {
        val x = idx % w
        val y = idx / w
        var n = 0
        if (at(d, w, h, x, y - 1)) n++
        if (at(d, w, h, x + 1, y - 1)) n++
        if (at(d, w, h, x + 1, y)) n++
        if (at(d, w, h, x + 1, y + 1)) n++
        if (at(d, w, h, x, y + 1)) n++
        if (at(d, w, h, x - 1, y + 1)) n++
        if (at(d, w, h, x - 1, y)) n++
        if (at(d, w, h, x - 1, y - 1)) n++
        return n
    }

    private fun crossing(d: BooleanArray, w: Int, h: Int, idx: Int): Int {
        val x = idx % w
        val y = idx / w
        val p2 = at(d, w, h, x, y - 1)
        val p3 = at(d, w, h, x + 1, y - 1)
        val p4 = at(d, w, h, x + 1, y)
        val p5 = at(d, w, h, x + 1, y + 1)
        val p6 = at(d, w, h, x, y + 1)
        val p7 = at(d, w, h, x - 1, y + 1)
        val p8 = at(d, w, h, x - 1, y)
        val p9 = at(d, w, h, x - 1, y - 1)
        var a = 0
        if (!p2 && p3) a++
        if (!p3 && p4) a++
        if (!p4 && p5) a++
        if (!p5 && p6) a++
        if (!p6 && p7) a++
        if (!p7 && p8) a++
        if (!p8 && p9) a++
        if (!p9 && p2) a++
        return a
    }

    private fun pickNext(d: BooleanArray, w: Int, h: Int, idx: Int, exclude: Int): Int {
        val x = idx % w
        val y = idx / w
        var best = -1
        var bestCrossing = -1
        var k = 0
        while (k < 8) {
            val nx = x + RING_X[k]
            val ny = y + RING_Y[k]
            k++
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            val j = ny * w + nx
            if (!d[j] || j == exclude) continue
            val c = crossing(d, w, h, j)
            if (c > bestCrossing) {
                bestCrossing = c
                best = j
            }
        }
        return best
    }

    private val RING_X = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    private val RING_Y = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)

    private fun soleNeighbour(d: BooleanArray, w: Int, h: Int, idx: Int, exclude: Int): Int {
        val x = idx % w
        val y = idx / w
        var found = -1
        var n = 0
        var dy = -1
        while (dy <= 1) {
            val ny = y + dy
            if (ny in 0 until h) {
                var dx = -1
                while (dx <= 1) {
                    val nx = x + dx
                    if ((dx != 0 || dy != 0) && nx >= 0 && nx < w) {
                        val j = ny * w + nx
                        if (d[j] && j != exclude) {
                            n++
                            if (n > 1) return -1
                            found = j
                        }
                    }
                    dx++
                }
            }
            dy++
        }
        return if (n == 1) found else -1
    }

    private fun walkBack(d: BooleanArray, w: Int, h: Int, start: Int, steps: Int): Int {
        var prev = -1
        var cur = start
        var i = 0
        while (i < steps) {
            val nxt = soleNeighbour(d, w, h, cur, prev)
            if (nxt < 0) break
            prev = cur
            cur = nxt
            i++
        }
        return cur
    }

    private fun crossesInk(d: BooleanArray, w: Int, h: Int, a: Int, b: Int): Boolean {
        var x = a % w
        var y = a / w
        val x1 = b % w
        val y1 = b / w
        val dx = if (x1 > x) x1 - x else x - x1
        val dy = -(if (y1 > y) y1 - y else y - y1)
        val sx = if (x < x1) 1 else -1
        val sy = if (y < y1) 1 else -1
        var err = dx + dy
        while (true) {
            if (x == x1 && y == y1) return false
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
            if (x == x1 && y == y1) return false
            if (d[y * w + x]) return true
        }
    }

    private fun drawLine(d: BooleanArray, w: Int, h: Int, a: Int, b: Int) {
        var x = a % w
        var y = a / w
        val x1 = b % w
        val y1 = b / w
        val dx = if (x1 > x) x1 - x else x - x1
        val dy = -(if (y1 > y) y1 - y else y - y1)
        val sx = if (x < x1) 1 else -1
        val sy = if (y < y1) 1 else -1
        var err = dx + dy
        while (true) {
            d[y * w + x] = true
            if (x == x1 && y == y1) return
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
        }
    }
}
