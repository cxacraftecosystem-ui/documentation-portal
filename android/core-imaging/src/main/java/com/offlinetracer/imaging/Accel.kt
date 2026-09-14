package com.offlinetracer.imaging

import kotlin.math.abs

/**
 * The optional native accelerator.
 *
 * ## Why this shape
 *
 * The pure-Kotlin implementations in this module are the **reference**. They are what the unit
 * tests assert against, what the TypeScript engine is kept in parity with, and what runs on every
 * device by default. Native code is an *accelerator only*: it may make a kernel faster, it may
 * never be the only way to compute one, and the app is fully functional with no native library
 * present at all. An ABI mismatch or a missing `.so` therefore costs speed and nothing else.
 *
 * ## Why it is off until it proves itself
 *
 * A native kernel that is subtly wrong is worse than no native kernel: the output looks plausible,
 * differs from the reference by a few percent, and only shows up as "the phone traces this
 * differently from the tablet". So [enable] does not simply set a flag — it runs [selfCheck],
 * which executes both implementations over a small deterministic fixture and compares them. If
 * they disagree beyond [TOLERANCE] the accelerator is refused and the reason is recorded in
 * [lastRefusal] for the settings screen to display. The user is told, rather than silently served
 * a second-class result.
 *
 * This is also the only honest way to ship acceleration that could not be tested against real
 * hardware at build time.
 */
interface ImageKernels {

    /** Human-readable backend name, e.g. `"neon-arm64"`. Shown in settings. */
    val backendName: String

    /** Bilateral filter, semantics identical to [Denoise.bilateral]. */
    fun bilateral(src: FloatArray, dst: FloatArray, w: Int, h: Int, sigmaSpace: Float, sigmaRange: Float)

    /** Separable convolution, edge-clamped, identical to [Convolve.separable]. */
    fun separable(src: FloatArray, dst: FloatArray, w: Int, h: Int, kx: FloatArray, ky: FloatArray)

    /**
     * Exact squared Euclidean distance transform (Felzenszwalb–Huttenlocher).
     * `mask` is 1 for foreground, 0 for background; `dst` receives squared distances.
     */
    fun distanceSquared(mask: ByteArray, dst: FloatArray, w: Int, h: Int)
}

object Accel {

    /**
     * Absolute tolerance for the self-check. Chosen because the only legitimate source of
     * divergence is the last ULP or two of `expf`/`powf` differing between libm and the JVM's
     * intrinsics; anything larger is an algorithmic disagreement, which is exactly what we refuse.
     */
    const val TOLERANCE = 1e-4f

    @Volatile
    private var impl: ImageKernels? = null

    @Volatile
    var lastRefusal: String? = null
        private set

    /** The active backend, or null when everything is running on the pure-Kotlin reference. */
    val backend: ImageKernels? get() = impl

    val isEnabled: Boolean get() = impl != null

    val backendName: String get() = impl?.backendName ?: "kotlin-reference"

    /**
     * Offer a native backend. Returns true only if it reproduced the reference implementations on
     * the built-in fixture; on failure the accelerator stays off and [lastRefusal] explains why.
     */
    fun enable(candidate: ImageKernels): Boolean {
        val failure = selfCheck(candidate)
        return if (failure == null) {
            impl = candidate
            lastRefusal = null
            true
        } else {
            impl = null
            lastRefusal = failure
            false
        }
    }

    fun disable() {
        impl = null
    }

    /**
     * Runs [candidate] and the Kotlin reference over a small deterministic image and reports the
     * first disagreement as a sentence fit to show a user, or null when they agree.
     *
     * The fixture is generated, not stored: a 64×48 pattern with a hard edge, a smooth ramp and an
     * isolated speck, which between them exercise the range term, the spatial term and the
     * background/foreground split of the distance transform.
     */
    fun selfCheck(candidate: ImageKernels): String? {
        val w = 64
        val h = 48
        val src = FloatArray(w * h)
        val mask = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                // Left half: a smooth diagonal ramp. Right half: a flat plateau. The join is the
                // hard edge a bilateral filter must preserve and a blur must not.
                src[i] = if (x < w / 2) (x + y).toFloat() / (w + h) else 0.85f
                mask[i] = if ((x - 20) * (x - 20) + (y - 24) * (y - 24) < 25) 1 else 0
            }
        }
        src[10 * w + 50] = 0.05f // an isolated speck inside the plateau
        mask[4 * w + 60] = 1     // an isolated foreground pixel far from the disc

        try {
            val refB = Denoise.bilateral(GrayF(w, h, src.copyOf()), 3f, 0.15f)
            val gotB = FloatArray(w * h)
            candidate.bilateral(src.copyOf(), gotB, w, h, 3f, 0.15f)
            firstMismatch(refB.data, gotB)?.let {
                return "The ${candidate.backendName} accelerator computed a different bilateral " +
                    "filter than the built-in one ($it). It has been left switched off."
            }

            val kernel = Convolve.gaussianKernel(2f)
            val refS = Convolve.separable(GrayF(w, h, src.copyOf()), kernel, kernel)
            val gotS = FloatArray(w * h)
            candidate.separable(src.copyOf(), gotS, w, h, kernel, kernel)
            firstMismatch(refS.data, gotS)?.let {
                return "The ${candidate.backendName} accelerator computed a different blur than " +
                    "the built-in one ($it). It has been left switched off."
            }

            val refD = Distance.euclidean(Mask(w, h, BooleanArray(w * h) { mask[it].toInt() == 1 }))
            val gotSq = FloatArray(w * h)
            candidate.distanceSquared(mask.copyOf(), gotSq, w, h)
            // The reference returns true distance; the native kernel returns squared distance.
            for (i in gotSq.indices) {
                val got = kotlin.math.sqrt(gotSq[i].toDouble()).toFloat()
                if (abs(got - refD.data[i]) > TOLERANCE * 10f) {
                    return "The ${candidate.backendName} accelerator computed a different distance " +
                        "transform than the built-in one (pixel $i: ${refD.data[i]} vs $got). " +
                        "It has been left switched off."
                }
            }
        } catch (t: Throwable) {
            return "The ${candidate.backendName} accelerator failed to run " +
                "(${t.javaClass.simpleName}: ${t.message}). It has been left switched off."
        }
        return null
    }

    private fun firstMismatch(expected: FloatArray, actual: FloatArray): String? {
        if (expected.size != actual.size) return "size ${actual.size}, expected ${expected.size}"
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            if (a.isNaN() || abs(a - e) > TOLERANCE) return "pixel $i: $e vs $a"
        }
        return null
    }
}
