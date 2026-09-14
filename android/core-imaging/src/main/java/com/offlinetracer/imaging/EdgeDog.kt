package com.offlinetracer.imaging

import kotlin.math.tanh

/**
 * Difference-of-Gaussians and Winnemöller's XDoG (ALGORITHMS §7.2).
 *
 * **Polarity, stated once and loudly:** [xdog] returns an **ink density where 1 is paper and low is
 * ink**. It is a picture, not a detector response — you display it directly, and you `invert` it (or
 * threshold with `invert = true`) to get an ink *coverage* map or a foreground [Mask]. Getting this
 * backwards produces a plausible-looking negative that nothing downstream complains about, which is
 * why it is spelled out on every function here.
 *
 * [dog] by contrast returns the raw **signed** DoG response, which is near zero in flat areas and
 * swings both ways across an edge. The two are not interchangeable.
 */
object EdgeDog {

    /**
     * Raw difference of Gaussians: `D = G(σ) − τ·G(kσ)`.
     *
     * Signed and unnormalised. For τ close to 1 the response of a flat region of intensity `I` is
     * `(1−τ)·I`, i.e. almost zero, and the interesting signal is the edge over/undershoot around it.
     *
     * @param k ratio between the two scales; 1.6 approximates the Laplacian of Gaussian.
     * @param tau how much of the coarse scale is subtracted; higher = more edge-only, less tone.
     * @return a new [GrayF] the size of [src]. Values are signed and are *not* clamped to 0..1.
     */
    fun dog(src: GrayF, sigma: Float, k: Float = 1.6f, tau: Float = 0.98f): GrayF {
        // τ is clamped to the same range as [xdog]'s. Both take it from the same slider, and a τ that
        // one of them clamps and the other does not means the preview and the export disagree about the
        // same setting; the range also has to be one rule so the two engines cannot pick different ones.
        val t = if (tau < 0f) 0f else if (tau > 0.999f) 0.999f else tau
        val s1 = if (sigma < 0f) 0f else sigma
        val s2 = if (k > 0f) s1 * k else s1
        val fine = Convolve.gaussianBlur(src, s1)
        val coarse = Convolve.gaussianBlur(src, s2)
        val a = fine.data
        val b = coarse.data
        val out = FloatArray(a.size)
        for (i in out.indices) out[i] = a[i] - t * b[i]
        return GrayF(src.width, src.height, out)
    }

    /**
     * XDoG: the DoG response passed through a soft threshold, which is what makes the output look
     * *drawn* rather than *detected*.
     *
     * **Returns ink density in 0..1 where 1 = paper and 0 = full ink.**
     *
     * The response is rescaled by `1/(1−τ)` before thresholding. This is not a fudge: Winnemöller's
     * operator is the sharpened image `S = (1+p)·G(σ) − p·G(kσ)`, and `S = D/(1−τ)` exactly when
     * `p = τ/(1−τ)`. Working in that domain is what makes [epsilon] an **intensity level** — a flat
     * region of intensity `I` maps to `u = I` — so the documented default of 0.5 means "ink below
     * mid-grey". Thresholding the raw `D` instead would put every sensible ε within 0.02 of zero and
     * the published defaults would all produce a solid black page.
     *
     * Because ε is a level, XDoG deliberately also fills genuinely dark regions with ink; that tone
     * response is the "drawn" quality. Drive ε toward 0 for edge-only, technical line work.
     *
     * @param epsilon ink level: `u ≥ ε` is paper.
     * @param phi transition sharpness (typ. 10–200); large = hard technical lines, small = pencil.
     * @return a new [GrayF] the size of [src], every value in 0..1.
     */
    fun xdog(
        src: GrayF,
        sigma: Float,
        k: Float = 1.6f,
        tau: Float = 0.98f,
        epsilon: Float = 0.5f,
        phi: Float = 20f,
    ): GrayF {
        // τ ≥ 1 inverts the DC term and 1/(1−τ) explodes; τ ≤ 0 is a plain blur. Both are clamped
        // rather than rejected because these arrive from a slider.
        val t = if (tau < 0f) 0f else if (tau > 0.999f) 0.999f else tau

        // **Everything from the source pixels to `u` is Double — kernel taps included** — and only the
        // thresholded result is Float. This is the same decision, for the same reason, as FDoG's in
        // [EdgeFlow.fdog]; ALGORITHMS §7.2 records the argument.
        //
        // `G(σ)` and `G(kσ)` are both ≈ I in any flat region, so `a − τb` is a catastrophic
        // cancellation that leaves `(1−τ)·I` — at the default τ = 0.98, one fiftieth of the operands.
        // Multiplying by `1/(1−τ) = 50` amplifies whatever rounding survived, and the soft threshold's
        // slope multiplies it by up to φ again: a *round-off* in the blur reaches the output magnified
        // by ~1000. One ulp of Float (6e-8) is therefore worth 6e-5 here, most of the §14 tolerance,
        // which is why every Float rounding on this path had to go rather than just the obvious one.
        //
        // Measured on `gradient-blob`, against the TypeScript engine computing the identical formula:
        // Float kernel taps cost 1.18e-4 (the two engines round `gaussianKernel` differently in the
        // last bit), a Float convolution accumulator 6.9e-5, and storing the two blur planes as Float
        // 2.5e-5. Widening only the subtraction — which is what this function used to do — left
        // 1.17e-4, over tolerance. Widening the whole path leaves 5.8e-6.
        //
        // `dog`'s own signature is deliberately left alone: it returns the raw signed response with no
        // rescale, so it is not amplified and a Float image is the honest result of what it computes.
        val s1 = if (sigma < 0f) 0f else sigma
        val s2 = if (k > 0f) s1 * k else s1
        val a = Convolve.gaussianBlurDouble(src, s1)
        val b = Convolve.gaussianBlurDouble(src, s2)
        val td = t.toDouble()
        val scale = 1.0 / (1.0 - td)
        val out = FloatArray(a.size)
        // Split: the `tanh` inside [softThreshold] is what makes this worth a share, and the body is a
        // pure function of `a[i]` and `b[i]` writing `out[i]`, so the split cannot move a single bit.
        Parallel.chunks(out.size, Parallel.PIXELS_MAP) { from, to ->
            for (i in from until to) {
                val u = (a[i] - td * b[i]) * scale
                out[i] = softThreshold(u.toFloat(), epsilon, phi)
            }
        }
        return GrayF(src.width, src.height, out)
    }

    /**
     * Winnemöller's soft threshold `T(u) = 1 if u ≥ ε, else 1 + tanh(φ·(u − ε))`.
     *
     * The sum is evaluated in **double** and narrowed once, for the reason §7.2 gives about everything
     * else on this path. `1 + tanh(x)` is a catastrophic cancellation as `tanh(x) → −1`: in Float the
     * whole deep-ink band collapses to exactly 0 where the true value is around 1e-8, and TypeScript —
     * where every intermediate is a double already — does not collapse it. Measured on `gradient-blob`:
     * widening §1's luma took [xdog] from 5.9e-6 to 6.2e-8, and widening this one expression took the
     * remaining 6.2e-8 to **zero — all 432 pixels bit-identical**.
     *
     * @return a value in 0..1 (1 = paper). NaN input maps to 1 (paper) so a NaN pixel shows as blank
     *   rather than as a black speck the user cannot explain.
     */
    fun softThreshold(u: Float, epsilon: Float, phi: Float): Float {
        if (u.isNaN()) return 1f
        if (u >= epsilon) return 1f
        val v = 1.0 + tanh(phi.toDouble() * (u.toDouble() - epsilon.toDouble()))
        return if (v < 0.0) 0f else if (v > 1.0) 1f else v.toFloat()
    }
}
