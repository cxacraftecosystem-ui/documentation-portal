package com.offlinetracer.imaging

object EdgeLog {

    fun logResponse(src: GrayF, sigma: Float): GrayF =
        Convolve.laplacian(Convolve.gaussianBlur(src, if (sigma < 0f) 0f else sigma))

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

    private fun straddlesZero(a: Float, b: Float): Boolean =
        (a < 0f && b > 0f) || (a > 0f && b < 0f)

    fun detect(src: GrayF, sigma: Float, slopeThreshold: Float): Mask =
        zeroCrossings(logResponse(src, sigma), slopeThreshold)
}
