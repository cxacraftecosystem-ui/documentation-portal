package com.offlinetracer.vector

import com.offlinetracer.imaging.Mask

object ContourTrace {

    data class Contour(val points: List<VecPoint>, val isHole: Boolean, val label: Int)

    fun trace(mask: Mask): List<Contour> = traceInternal(mask, includeHoles = true)

    fun traceOuterOnly(mask: Mask): List<Contour> = traceInternal(mask, includeHoles = false)

    private val DX8 = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DY8 = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    private val DIR_OF = intArrayOf(5, 6, 7, 4, -1, 0, 3, 2, 1)

    private const val DIR_S = 2
    private const val DIR_W = 4

    private fun traceInternal(mask: Mask, includeHoles: Boolean): List<Contour> {
        val w = mask.width
        val h = mask.height
        val n = w * h
        val fg = mask.data
        val out = ArrayList<Contour>()

        val labels = IntArray(n)
        val stack = IntStack(1024)
        var count = 0
        for (i in 0 until n) {
            if (fg[i] && labels[i] == 0) {
                count++
                floodForeground(mask, labels, count, i, stack)
            }
        }
        if (count == 0) return out

        val seeded = BooleanArray(count + 1)
        for (i in 0 until n) {
            val label = labels[i]
            if (label != 0 && !seeded[label]) {
                seeded[label] = true
                out.add(Contour(moore(mask, i % w, i / w, DIR_W, n), false, label))
            }
        }
        if (!includeHoles) return out

        val bgSeen = BooleanArray(n)
        floodOutsideBackground(mask, bgSeen, stack)

        for (i in 0 until n) {
            if (fg[i] || bgSeen[i]) continue
            val hx = i % w
            val hy = i / w
            floodHoleBackground(mask, bgSeen, i, stack)
            out.add(Contour(moore(mask, hx, hy - 1, DIR_S, n), true, labels[i - w]))
        }
        return out
    }

    private fun moore(mask: Mask, sx: Int, sy: Int, startBacktrack: Int, cells: Int): List<VecPoint> {
        val pts = ArrayList<VecPoint>(64)
        pts.add(VecPoint(sx.toFloat(), sy.toFloat()))

        val entryBacktrack = cycleEntryBacktrack(mask, sx, sy, startBacktrack, cells)

        var cx = sx
        var cy = sy
        var backtrack = entryBacktrack

        val maxSteps = 8L * cells + 8L
        var steps = 0L

        while (steps < maxSteps) {
            steps++
            var probed = backtrack
            var found = -1
            for (j in 1..8) {
                val k = (backtrack + j) and 7
                if (mask.safe(cx + DX8[k], cy + DY8[k])) {
                    found = k
                    break
                }
                probed = k
            }
            if (found < 0) break

            val nx = cx + DX8[found]
            val ny = cy + DY8[found]
            val bx = cx + DX8[probed] - nx
            val by = cy + DY8[probed] - ny
            val newBacktrack = DIR_OF[(by + 1) * 3 + (bx + 1)]

            if (nx == sx && ny == sy && newBacktrack == entryBacktrack) break

            pts.add(VecPoint(nx.toFloat(), ny.toFloat()))
            cx = nx
            cy = ny
            backtrack = newBacktrack
        }
        return pts
    }

    private fun cycleEntryBacktrack(
        mask: Mask,
        sx: Int,
        sy: Int,
        startBacktrack: Int,
        cells: Int,
    ): Int {
        var cx = sx
        var cy = sy
        var backtrack = startBacktrack
        val maxSteps = 8L * cells + 8L
        var steps = 0L

        while (steps < maxSteps) {
            steps++
            var probed = backtrack
            var found = -1
            for (j in 1..8) {
                val k = (backtrack + j) and 7
                if (mask.safe(cx + DX8[k], cy + DY8[k])) {
                    found = k
                    break
                }
                probed = k
            }
            if (found < 0) return startBacktrack

            val nx = cx + DX8[found]
            val ny = cy + DY8[found]
            val bx = cx + DX8[probed] - nx
            val by = cy + DY8[probed] - ny
            val newBacktrack = DIR_OF[(by + 1) * 3 + (bx + 1)]

            if (nx == sx && ny == sy) return newBacktrack

            cx = nx
            cy = ny
            backtrack = newBacktrack
        }
        return startBacktrack
    }

    private fun floodForeground(mask: Mask, labels: IntArray, label: Int, seed: Int, stack: IntStack) {
        val w = mask.width
        val h = mask.height
        val fg = mask.data
        stack.clear()
        labels[seed] = label
        stack.push(seed)
        while (stack.size > 0) {
            val p = stack.pop()
            val x = p % w
            val y = p / w
            for (k in 0 until 8) {
                val nx = x + DX8[k]
                val ny = y + DY8[k]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val q = ny * w + nx
                if (fg[q] && labels[q] == 0) {
                    labels[q] = label
                    stack.push(q)
                }
            }
        }
    }

    private fun floodOutsideBackground(mask: Mask, seen: BooleanArray, stack: IntStack) {
        val w = mask.width
        val h = mask.height
        val fg = mask.data
        stack.clear()
        for (x in 0 until w) {
            val top = x
            if (!fg[top] && !seen[top]) { seen[top] = true; stack.push(top) }
            val bottom = (h - 1) * w + x
            if (!fg[bottom] && !seen[bottom]) { seen[bottom] = true; stack.push(bottom) }
        }
        for (y in 0 until h) {
            val left = y * w
            if (!fg[left] && !seen[left]) { seen[left] = true; stack.push(left) }
            val right = y * w + (w - 1)
            if (!fg[right] && !seen[right]) { seen[right] = true; stack.push(right) }
        }
        drainBackground(mask, seen, stack)
    }

    private fun floodHoleBackground(mask: Mask, seen: BooleanArray, seed: Int, stack: IntStack) {
        stack.clear()
        seen[seed] = true
        stack.push(seed)
        drainBackground(mask, seen, stack)
    }

    private fun drainBackground(mask: Mask, seen: BooleanArray, stack: IntStack) {
        val w = mask.width
        val h = mask.height
        val fg = mask.data
        while (stack.size > 0) {
            val p = stack.pop()
            val x = p % w
            val y = p / w
            if (x > 0) { val q = p - 1; if (!fg[q] && !seen[q]) { seen[q] = true; stack.push(q) } }
            if (x < w - 1) { val q = p + 1; if (!fg[q] && !seen[q]) { seen[q] = true; stack.push(q) } }
            if (y > 0) { val q = p - w; if (!fg[q] && !seen[q]) { seen[q] = true; stack.push(q) } }
            if (y < h - 1) { val q = p + w; if (!fg[q] && !seen[q]) { seen[q] = true; stack.push(q) } }
        }
    }

    private class IntStack(initialCapacity: Int) {
        private var data = IntArray(if (initialCapacity < 16) 16 else initialCapacity)

        var size: Int = 0
            private set

        fun push(v: Int) {
            if (size == data.size) data = data.copyOf(data.size shl 1)
            data[size++] = v
        }

        fun pop(): Int = data[--size]

        fun clear() {
            size = 0
        }
    }
}
