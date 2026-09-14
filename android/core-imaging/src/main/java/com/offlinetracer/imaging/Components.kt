package com.offlinetracer.imaging

/**
 * Connected components and the blob filters built on them (ALGORITHMS §9).
 *
 * One two-pass union-find labelling backs every filter here. Running a fresh flood fill per filter
 * was the original design and it made `removeSmallBlobs` + `keepLargest` + `removeBorderTouching`
 * cost three full traversals of a 4 MP image for information that one pass already had.
 */
object Components {

    /**
     * Result of [label].
     *
     * [labels] holds `0` for background and `1..count` for components, in order of first appearance
     * in scan order (so the labelling is reproducible, not dependent on union order).
     * [area] is sized `count + 1` and [bounds] is sized `4 * (count + 1)`, laid out as
     * `[x0, y0, x1, y1]` per label with **inclusive** corners. Index 0 of both is the background
     * slot and is left as an empty box (`x1 < x0`); it is not the background's real extent.
     */
    class Labels(
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val labels: IntArray,
        @JvmField val count: Int,
        @JvmField val area: IntArray,
        @JvmField val bounds: IntArray,
    ) {
        /**
         * @return a [Mask] that is `true` exactly where [labels] equals [label]; an empty mask for
         *   any label outside `1..count`, which is what makes callers safe to loop past the end.
         */
        fun maskOf(label: Int): Mask {
            val out = BooleanArray(labels.size)
            if (label in 1..count) {
                for (i in labels.indices) out[i] = labels[i] == label
            }
            return Mask(width, height, out)
        }

        /** @return the pixel count of [label], or 0 for any label outside `1..count`. */
        fun areaOf(label: Int): Int = if (label in 1..count) area[label] else 0
    }

    /**
     * Two-pass connected-component labelling with union-find (path compression **and** union by
     * rank, so the near-degenerate case of one long snake through the image stays near-linear).
     *
     * @param connectivity 8 (default) or 4; any other value is treated as 8.
     * @return a [Labels] with `count == 0` when [src] is all background.
     */
    fun label(src: Mask, connectivity: Int = 8): Labels {
        val w = src.width
        val h = src.height
        val d = src.data
        val labels = IntArray(d.size)
        val eight = connectivity != 4

        // A provisional label is only created at a pixel with no already-scanned foreground
        // neighbour, which caps the count at half the pixels; the grow path exists because that
        // bound is easy to reason about wrongly and an ArrayIndexOutOfBounds here is a crash.
        var parent = IntArray(d.size / 2 + 2)
        var rank = IntArray(parent.size)
        var next = 1

        for (y in 0 until h) {
            val row = y * w
            val up = row - w
            for (x in 0 until w) {
                val i = row + x
                if (!d[i]) continue
                val nW = if (x > 0) labels[i - 1] else 0
                val nN = if (y > 0) labels[up + x] else 0
                val nNW = if (eight && y > 0 && x > 0) labels[up + x - 1] else 0
                val nNE = if (eight && y > 0 && x < w - 1) labels[up + x + 1] else 0

                var l = 0
                if (nW != 0) l = nW
                if (nN != 0) {
                    if (l == 0) l = nN else if (nN != l) union(parent, rank, l, nN)
                }
                if (nNW != 0) {
                    if (l == 0) l = nNW else if (nNW != l) union(parent, rank, l, nNW)
                }
                if (nNE != 0) {
                    if (l == 0) l = nNE else if (nNE != l) union(parent, rank, l, nNE)
                }
                if (l == 0) {
                    if (next >= parent.size) {
                        parent = parent.copyOf(parent.size * 2)
                        rank = rank.copyOf(parent.size)
                    }
                    l = next++
                    parent[l] = l
                }
                labels[i] = l
            }
        }

        val mapping = IntArray(next)
        var count = 0
        for (i in labels.indices) {
            val l = labels[i]
            if (l == 0) continue
            val root = find(parent, l)
            var m = mapping[root]
            if (m == 0) {
                count++
                m = count
                mapping[root] = m
            }
            labels[i] = m
        }

        val area = IntArray(count + 1)
        val bounds = IntArray((count + 1) * 4)
        for (l in 0..count) {
            val b = l * 4
            bounds[b] = Int.MAX_VALUE
            bounds[b + 1] = Int.MAX_VALUE
            bounds[b + 2] = -1
            bounds[b + 3] = -1
        }
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = labels[row + x]
                if (l == 0) continue
                area[l]++
                val b = l * 4
                if (x < bounds[b]) bounds[b] = x
                if (y < bounds[b + 1]) bounds[b + 1] = y
                if (x > bounds[b + 2]) bounds[b + 2] = x
                if (y > bounds[b + 3]) bounds[b + 3] = y
            }
        }
        // The background slot never receives a pixel; leave it as a normalised empty box rather than
        // MAX_VALUE, which reads as a nonsense rectangle in a debugger and in any UI overlay.
        bounds[0] = 0
        bounds[1] = 0
        bounds[2] = -1
        bounds[3] = -1

        return Labels(w, h, labels, count, area, bounds)
    }

    /**
     * Drops components smaller than [minArea] pixels — dust, sensor noise and JPEG mosquito ringing.
     *
     * @param minArea `≤ 1` keeps everything (a 1-pixel component already has area 1).
     * @return a new [Mask] the size of [src].
     */
    fun removeSmallBlobs(src: Mask, minArea: Int): Mask {
        if (minArea <= 1) return src.copy()
        val lab = label(src, 8)
        if (lab.count == 0) return Mask.like(src)
        val keep = BooleanArray(lab.count + 1)
        for (l in 1..lab.count) keep[l] = lab.area[l] >= minArea
        return select(lab, keep)
    }

    /**
     * Keeps the [n] largest components, ties broken by the lower label (i.e. by first appearance in
     * scan order) so the result never depends on sort stability.
     *
     * @param n `≤ 0` returns an **empty** mask — keeping zero components is the literal meaning.
     *   Callers that use 0 as "disabled" must not call this at all.
     * @return a new [Mask] the size of [src].
     */
    fun keepLargest(src: Mask, n: Int): Mask {
        if (n <= 0) return Mask.like(src)
        val lab = label(src, 8)
        if (lab.count == 0) return Mask.like(src)
        if (n >= lab.count) return src.copy()

        // (area, tie-break) packed into one long so the sort is a primitive sort with no boxing and
        // no comparator. `count - l` in the low 32 bits makes the smaller label win a tie. Both
        // fields are non-negative and area is bounded by the pixel count, so the shift cannot reach
        // the sign bit for any image that can be allocated.
        val keys = LongArray(lab.count)
        for (l in 1..lab.count) {
            keys[l - 1] = (lab.area[l].toLong() shl 32) or (lab.count - l).toLong()
        }
        java.util.Arrays.sort(keys)
        val keep = BooleanArray(lab.count + 1)
        var i = keys.size - 1
        var taken = 0
        while (i >= 0 && taken < n) {
            val l = lab.count - (keys[i] and 0xFFFFFFFFL).toInt()
            keep[l] = true
            taken++
            i--
        }
        return select(lab, keep)
    }

    /**
     * Drops every component with a pixel on the image border — the scan frame, the page edge, and
     * the shadow band down the side of a photographed book.
     *
     * @return a new [Mask] the size of [src].
     */
    fun removeBorderTouching(src: Mask): Mask {
        val lab = label(src, 8)
        if (lab.count == 0) return Mask.like(src)
        val w = lab.width
        val h = lab.height
        val l = lab.labels
        val keep = BooleanArray(lab.count + 1)
        java.util.Arrays.fill(keep, true)
        keep[0] = false
        val last = (h - 1) * w
        for (x in 0 until w) {
            keep[l[x]] = false
            keep[l[last + x]] = false
        }
        for (y in 0 until h) {
            val row = y * w
            keep[l[row]] = false
            keep[l[row + w - 1]] = false
        }
        return select(lab, keep)
    }

    /**
     * Clears any set pixel with fewer than [minNeighbours] set 8-neighbours, iterated to a fixed
     * point. Each pass is evaluated against the previous state and applied afterwards, so the result
     * does not depend on scan order.
     *
     * @param minNeighbours `≤ 0` returns a copy (every pixel trivially qualifies).
     * @param maxPasses hard bound; erosion of a hairline can otherwise chase its own tail.
     * @return a new [Mask] the size of [src].
     */
    fun removeIsolated(src: Mask, minNeighbours: Int = 1, maxPasses: Int = 8): Mask {
        if (minNeighbours <= 0 || maxPasses <= 0) return src.copy()
        val w = src.width
        val h = src.height
        var cur = src.data.copyOf()
        var pass = 0
        while (pass < maxPasses) {
            val nextData = cur.copyOf()
            var changed = false
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    if (!cur[i]) continue
                    var n = 0
                    if (x > 0 && cur[i - 1]) n++
                    if (x + 1 < w && cur[i + 1]) n++
                    if (y > 0) {
                        val u = i - w
                        if (cur[u]) n++
                        if (x > 0 && cur[u - 1]) n++
                        if (x + 1 < w && cur[u + 1]) n++
                    }
                    if (y + 1 < h) {
                        val v = i + w
                        if (cur[v]) n++
                        if (x > 0 && cur[v - 1]) n++
                        if (x + 1 < w && cur[v + 1]) n++
                    }
                    if (n < minNeighbours) {
                        nextData[i] = false
                        changed = true
                    }
                }
            }
            cur = nextData
            if (!changed) break
            pass++
        }
        return Mask(w, h, cur)
    }

    /**
     * Fills background regions of at most [maxHoleArea] pixels that are fully enclosed by foreground.
     *
     * The background is labelled with **4-connectivity** while the foreground is 8-connected; that
     * duality is what makes a diagonal 1px stroke count as sealing a hole. Using 8 for both leaks
     * every hole out through the diagonal gaps of its own wall.
     *
     * @param maxHoleArea `≤ 0` returns a copy — "fill holes up to 0 pixels" fills nothing.
     * @return a new [Mask] the size of [src].
     */
    fun fillHoles(src: Mask, maxHoleArea: Int): Mask {
        if (maxHoleArea <= 0) return src.copy()
        val w = src.width
        val h = src.height
        val bg = label(src.invert(), 4)
        if (bg.count == 0) return src.copy()

        val open = BooleanArray(bg.count + 1)
        open[0] = true
        val l = bg.labels
        val last = (h - 1) * w
        for (x in 0 until w) {
            open[l[x]] = true
            open[l[last + x]] = true
        }
        for (y in 0 until h) {
            val row = y * w
            open[l[row]] = true
            open[l[row + w - 1]] = true
        }

        val out = src.data.copyOf()
        for (i in l.indices) {
            val lb = l[i]
            if (lb == 0 || open[lb]) continue
            if (bg.area[lb] <= maxHoleArea) out[i] = true
        }
        return Mask(w, h, out)
    }

    /** Materialise the labels flagged in [keep] (indexed by label, index 0 unused) as a [Mask]. */
    private fun select(lab: Labels, keep: BooleanArray): Mask {
        val l = lab.labels
        val out = BooleanArray(l.size)
        for (i in l.indices) {
            val v = l[i]
            if (v != 0 && keep[v]) out[i] = true
        }
        return Mask(lab.width, lab.height, out)
    }

    /** Union-find root with full path compression (two short walks, no recursion). */
    private fun find(parent: IntArray, x: Int): Int {
        var root = x
        while (parent[root] != root) root = parent[root]
        var cur = x
        while (parent[cur] != root) {
            val nxt = parent[cur]
            parent[cur] = root
            cur = nxt
        }
        return root
    }

    /** Union by rank; keeps the tree shallow so [find] stays effectively constant time. */
    private fun union(parent: IntArray, rank: IntArray, a: Int, b: Int) {
        val ra = find(parent, a)
        val rb = find(parent, b)
        if (ra == rb) return
        if (rank[ra] < rank[rb]) {
            parent[ra] = rb
        } else if (rank[ra] > rank[rb]) {
            parent[rb] = ra
        } else {
            parent[rb] = ra
            rank[ra]++
        }
    }
}
