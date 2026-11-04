package com.offlinetracer.imaging

import kotlin.math.abs

interface ImageKernels {

    val backendName: String

    fun bilateral(src: FloatArray, dst: FloatArray, w: Int, h: Int, sigmaSpace: Float, sigmaRange: Float)

    fun separable(src: FloatArray, dst: FloatArray, w: Int, h: Int, kx: FloatArray, ky: FloatArray)

    fun distanceSquared(mask: ByteArray, dst: FloatArray, w: Int, h: Int)
}

object Accel {

    const val TOLERANCE = 1e-4f

    @Volatile
    private var impl: ImageKernels? = null

    @Volatile
    var lastRefusal: String? = null
        private set

    val backend: ImageKernels? get() = impl

    val isEnabled: Boolean get() = impl != null

    val backendName: String get() = impl?.backendName ?: "kotlin-reference"

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

    fun selfCheck(candidate: ImageKernels): String? {
        val w = 64
        val h = 48
        val src = FloatArray(w * h)
        val mask = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                src[i] = if (x < w / 2) (x + y).toFloat() / (w + h) else 0.85f
                mask[i] = if ((x - 20) * (x - 20) + (y - 24) * (y - 24) < 25) 1 else 0
            }
        }
        src[10 * w + 50] = 0.05f
        mask[4 * w + 60] = 1

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
