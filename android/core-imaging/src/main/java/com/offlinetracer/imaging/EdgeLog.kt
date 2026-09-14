package com.offlinetracer.imaging

/**
 * Laplacian-of-Gaussian zero crossings (ALGORITHMS §7.5).
 *
 * Kept alongside Canny and FDoG because it gives the thinnest, most delicate lines of any engine
 * here, which is what filigree, jewellery and fine engraving need. It is also the noisiest, which is
 * why the slope threshold is not optional.
 */
object EdgeLog {

    /**
     * `LoG = ∇²(G(σ) * I)`, computed as a Gaussian blur followed by the 8-neighbour Laplacian rather
     * than by convolving with a sampled LoG kernel. Two separable passes plus a 3×3 beats one dense
     * (2·ceil(3σ)+1)² kernel at every σ the pipeline uses, and the results agree to well inside the
     * parity tolerance.
     *
     * @return a new [GrayF] the size of [src], **signed**: the sign change across an edge is the
     *   whole point, so this is never clamped.
     */
    fun logResponse(src: GrayF, sigma: Float): GrayF =
        Convolve.laplacian(Convolve.gaussianBlur(src, if (sigma < 0f) 0f else sigma))

    /**
     * Marks pixels where the LoG changes sign between 4-neighbours with a slope steeper than
     * [slopeThreshold].
     *
     * Of the two pixels straddling a crossing only the one with the smaller magnitude is marked —
     * that is the one nearer the true zero — which keeps the result one pixel wide. Marking both
     * doubles every line and the doubling survives thinning as a ladder of spurs.
     *
     * @param slopeThreshold minimum `|a − b|` across the pair; 0 marks every crossing including
     *   sensor noise in flat areas.
     * @return a [Mask] the size of [log] where `true` is an edge pixel.
     *
     * **Left single-threaded, unlike the rest of the edge engines.** A pixel here does not write its
     * own cell: it examines the pair `(i, i+w)` and marks whichever of the two is nearer the zero, so
     * the last row of a share writes into the first row of the next one. Only `true` is ever written,
     * so the answer would in fact come out the same — but "two threads writing the same array element
     * and it happens not to matter" is a claim that has to be re-verified every time the rule about
     * which of the pair wins is touched, and the whole cost this file has is in [logResponse]'s blur
     * and Laplacian, both of which are split. One pass of four float compares per pixel is not where
     * the two and a half minutes went.
     */
    fun zeroCrossings(log: GrayF, slopeThreshold: Float): Mask {
        val w = log.width
        val h = log.height
        val d = log.data
        val out = BooleanArray(d.size)
        val slope = if (slopeThreshold < 0f) 0f else slopeThreshold
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val a = d[i]
                val aAbs = if (a < 0f) -a else a
                if (x + 1 < w) {
                    val b = d[i + 1]
                    if (straddlesZero(a, b)) {
                        val diff = if (a > b) a - b else b - a
                        if (diff > slope) {
                            val bAbs = if (b < 0f) -b else b
                            if (aAbs <= bAbs) out[i] = true else out[i + 1] = true
                        }
                    }
                }
                if (y + 1 < h) {
                    val j = i + w
                    val b = d[j]
                    if (straddlesZero(a, b)) {
                        val diff = if (a > b) a - b else b - a
                        if (diff > slope) {
                            val bAbs = if (b < 0f) -b else b
                            if (aAbs <= bAbs) out[i] = true else out[j] = true
                        }
                    }
                }
            }
        }
        return Mask(w, h, out)
    }

    /**
     * True only when [a] and [b] lie strictly on opposite sides of zero.
     *
     * §7.5 defines an edge as a **sign change**, and zero has no sign. A response of exactly `0f` is
     * not a rare accident: the finite Gaussian kernel returns the input unchanged everywhere it sees
     * one value, so the Laplacian is bit-zero across every flat region, which is most of a drawing.
     * Testing `(a < 0) != (b < 0)` silently treats those zeros as positive, so a negative tail meeting
     * the zero plateau beyond it reads as a crossing while the positive tail does not — a one-pixel
     * false edge at the outer end of every blur tail, on one side only.
     *
     * `a * b < 0` is the other tempting spelling and is also wrong here: two genuinely opposite
     * subnormal responses multiply to `-0f` in Float and the crossing disappears.
     */
    private fun straddlesZero(a: Float, b: Float): Boolean =
        (a < 0f && b > 0f) || (a > 0f && b < 0f)

    /**
     * Convenience: [logResponse] followed by [zeroCrossings].
     *
     * @return a [Mask] the size of [src].
     */
    fun detect(src: GrayF, sigma: Float, slopeThreshold: Float): Mask =
        zeroCrossings(logResponse(src, sigma), slopeThreshold)
}
