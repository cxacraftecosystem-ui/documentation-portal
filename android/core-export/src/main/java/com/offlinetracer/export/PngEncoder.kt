package com.offlinetracer.export

import com.offlinetracer.imaging.GrayF
import com.offlinetracer.imaging.Px
import com.offlinetracer.imaging.RgbaImage
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt

object PngEncoder {

    private const val COLOUR_TYPE_GRAY = 0
    private const val COLOUR_TYPE_RGBA = 6

    private const val FILTER_NONE = 0
    private const val FILTER_SUB = 1
    private const val FILTER_UP = 2
    private const val FILTER_AVERAGE = 3
    private const val FILTER_PAETH = 4

    private val EMPTY = ByteArray(0)

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
    )

    fun encode(src: RgbaImage, dpi: Int = 0): ByteArray {
        val idat = deflateRgba(src)
        val out = ByteArrayOutputStream(idat.size + 128)
        out.write(SIGNATURE)
        writeIhdr(out, src.width, src.height, COLOUR_TYPE_RGBA)
        if (dpi > 0) writePhys(out, dpi)
        writeChunk(out, "IDAT", idat)
        writeChunk(out, "IEND", EMPTY)
        return out.toByteArray()
    }

    fun encodeGray(src: GrayF, dpi: Int = 0): ByteArray {
        val idat = deflateGray(src)
        val out = ByteArrayOutputStream(idat.size + 128)
        out.write(SIGNATURE)
        writeIhdr(out, src.width, src.height, COLOUR_TYPE_GRAY)
        if (dpi > 0) writePhys(out, dpi)
        writeChunk(out, "IDAT", idat)
        writeChunk(out, "IEND", EMPTY)
        return out.toByteArray()
    }

    private fun deflateRgba(src: RgbaImage): ByteArray {
        val w = src.width
        val h = src.height
        val stride = w * 4
        val cur = ByteArray(stride)
        val filter = RowFilter(stride, 4)
        val sink = ByteArrayOutputStream(1 shl 16)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val dos = DeflaterOutputStream(sink, deflater, 1 shl 16)
        val px = src.pixels
        var y = 0
        while (y < h) {
            var i = y * w
            var o = 0
            var x = 0
            while (x < w) {
                val argb = px[i]
                cur[o] = ((argb ushr 16) and 0xFF).toByte()
                cur[o + 1] = ((argb ushr 8) and 0xFF).toByte()
                cur[o + 2] = (argb and 0xFF).toByte()
                cur[o + 3] = ((argb ushr 24) and 0xFF).toByte()
                o += 4
                i++
                x++
            }
            filter.emit(cur, dos)
            y++
        }
        dos.finish()
        dos.flush()
        deflater.end()
        return sink.toByteArray()
    }

    private fun deflateGray(src: GrayF): ByteArray {
        val w = src.width
        val h = src.height
        val cur = ByteArray(w)
        val filter = RowFilter(w, 1)
        val sink = ByteArrayOutputStream(1 shl 16)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val dos = DeflaterOutputStream(sink, deflater, 1 shl 16)
        val data = src.data
        var y = 0
        while (y < h) {
            var i = y * w
            var x = 0
            while (x < w) {
                cur[x] = Px.toByte255(data[i]).toByte()
                i++
                x++
            }
            filter.emit(cur, dos)
            y++
        }
        dos.finish()
        dos.flush()
        deflater.end()
        return sink.toByteArray()
    }

    private class RowFilter(private val stride: Int, private val bpp: Int) {
        private val prev = ByteArray(stride)
        private val sub = ByteArray(stride)
        private val up = ByteArray(stride)
        private val avg = ByteArray(stride)
        private val pae = ByteArray(stride)

        fun emit(cur: ByteArray, dos: DeflaterOutputStream) {
            val n = stride
            val b = bpp

            var i = 0
            while (i < b && i < n) {
                sub[i] = cur[i]
                i++
            }
            while (i < n) {
                sub[i] = (cur[i] - cur[i - b]).toByte()
                i++
            }

            i = 0
            while (i < n) {
                up[i] = (cur[i] - prev[i]).toByte()
                i++
            }

            i = 0
            while (i < n) {
                val left = if (i >= b) cur[i - b].toInt() and 0xFF else 0
                val above = prev[i].toInt() and 0xFF
                avg[i] = ((cur[i].toInt() and 0xFF) - ((left + above) shr 1)).toByte()
                i++
            }

            i = 0
            while (i < n) {
                val a = if (i >= b) cur[i - b].toInt() and 0xFF else 0
                val bb = prev[i].toInt() and 0xFF
                val c = if (i >= b) prev[i - b].toInt() and 0xFF else 0
                val p = a + bb - c
                var pa = p - a
                if (pa < 0) pa = -pa
                var pb = p - bb
                if (pb < 0) pb = -pb
                var pc = p - c
                if (pc < 0) pc = -pc
                val pred = if (pa <= pb && pa <= pc) a else if (pb <= pc) bb else c
                pae[i] = ((cur[i].toInt() and 0xFF) - pred).toByte()
                i++
            }

            var bestType = FILTER_NONE
            var bestScore = score(cur, n)
            var s = score(sub, n)
            if (s < bestScore) {
                bestScore = s
                bestType = FILTER_SUB
            }
            s = score(up, n)
            if (s < bestScore) {
                bestScore = s
                bestType = FILTER_UP
            }
            s = score(avg, n)
            if (s < bestScore) {
                bestScore = s
                bestType = FILTER_AVERAGE
            }
            s = score(pae, n)
            if (s < bestScore) {
                bestScore = s
                bestType = FILTER_PAETH
            }

            dos.write(bestType)
            when (bestType) {
                FILTER_NONE -> dos.write(cur, 0, n)
                FILTER_SUB -> dos.write(sub, 0, n)
                FILTER_UP -> dos.write(up, 0, n)
                FILTER_AVERAGE -> dos.write(avg, 0, n)
                else -> dos.write(pae, 0, n)
            }
            System.arraycopy(cur, 0, prev, 0, n)
        }

        private fun score(a: ByteArray, n: Int): Long {
            var sum = 0L
            var i = 0
            while (i < n) {
                val v = a[i].toInt()
                sum += if (v < 0) (-v).toLong() else v.toLong()
                i++
            }
            return sum
        }
    }

    private fun writeIhdr(out: ByteArrayOutputStream, w: Int, h: Int, colourType: Int) {
        val d = ByteArray(13)
        putBe32(d, 0, w)
        putBe32(d, 4, h)
        d[8] = 8
        d[9] = colourType.toByte()
        d[10] = 0
        d[11] = 0
        d[12] = 0
        writeChunk(out, "IHDR", d)
    }

    private fun writePhys(out: ByteArrayOutputStream, dpi: Int) {
        val ppm = (dpi / 0.0254).roundToInt()
        val d = ByteArray(9)
        putBe32(d, 0, ppm)
        putBe32(d, 4, ppm)
        d[8] = 1
        writeChunk(out, "pHYs", d)
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeBe32(out, data.size)
        val t = ByteArray(4)
        var i = 0
        while (i < 4) {
            t[i] = type[i].code.toByte()
            i++
        }
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        writeBe32(out, crc.value.toInt())
    }

    private fun writeBe32(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun putBe32(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }
}
