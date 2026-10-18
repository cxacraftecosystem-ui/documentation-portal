package com.fieldrepository.app.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * ADTS → `.m4a` with [MediaMuxer]: the AAC frames are copied, never re-encoded (DESIGN §6.1.2).
 * Works on a stream cut short by a kill, because ADTS frames are self-delimiting.
 */
object Remuxer {
    data class Result(val file: File, val durationMs: Long, val sizeBytes: Long, val sha256: String)

    fun remux(adts: File, m4a: File): Result {
        val extractor = MediaExtractor()
        extractor.setDataSource(adts.absolutePath)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        m4a.parentFile?.mkdirs()
        val muxer = MediaMuxer(m4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var lastUs = 0L
        try {
            val out = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                lastUs = maxOf(lastUs, extractor.sampleTime)
                muxer.writeSampleData(out, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            extractor.release()
        }
        return Result(m4a, lastUs / 1000, m4a.length(), sha256(m4a))
    }

    /** Streaming sha256, hex; the whole file is never in memory. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
