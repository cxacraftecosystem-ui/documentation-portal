package com.fieldrepository.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Splits a long audio/video file into time-ordered MP4 segments, each no larger than a byte budget,
 * by re-muxing samples (no re-encoding, so it is fast and lossless). Video is cut only at sync frames
 * so every segment is independently playable; audio is cut at any sample boundary. This keeps each
 * part within the limits downstream processing can handle — most importantly under the speech-to-text
 * service's per-file size cap — so a very long recording never fails as one oversized blob.
 *
 * The splitter is deliberately fail-safe: if the container/codec can't be re-muxed (e.g. MP3 audio,
 * which MediaMuxer's MP4 output doesn't support) it returns an empty list and the caller uploads the
 * original file whole.
 */
object MediaSplitter {

    fun split(context: Context, uri: Uri, maxBytes: Long, outDir: File): List<File> {
        if (!outDir.exists()) outDir.mkdirs()
        val extractor = MediaExtractor()
        val segments = ArrayList<File>()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackCount = extractor.trackCount
            if (trackCount == 0) return emptyList()

            val formats = ArrayList<MediaFormat>(trackCount)
            var videoTrack = -1
            var bufferCap = 1 shl 20 // 1 MB floor
            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                formats.add(format)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) videoTrack = i
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferCap = maxOf(bufferCap, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                extractor.selectTrack(i)
            }

            val buffer = ByteBuffer.allocate(bufferCap)
            val info = MediaCodec.BufferInfo()
            var trackMap = IntArray(trackCount) { -1 }
            var segmentBytes = 0L
            var segmentStartPts = -1L
            var index = 0

            fun openSegment() {
                val out = File(outDir, "seg-${System.currentTimeMillis()}-${index + 1}.mp4")
                val created = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                trackMap = IntArray(trackCount) { t -> created.addTrack(formats[t]) }
                created.start()
                muxer = created
                segments.add(out)
                segmentBytes = 0L
                segmentStartPts = -1L
            }

            openSegment()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val track = extractor.sampleTrackIndex
                val time = extractor.sampleTime
                val isSync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                // Cut at a clean boundary once the running segment exceeds the budget: a sync frame on
                // the video track, or (for audio-only) any sample.
                val atCutBoundary = if (videoTrack >= 0) (track == videoTrack && isSync) else true
                if (segmentBytes >= maxBytes && segmentBytes > 0L && atCutBoundary) {
                    muxer?.let { it.stop(); it.release() }
                    index += 1
                    openSegment()
                }
                if (segmentStartPts < 0L) segmentStartPts = time
                info.offset = 0
                info.size = size
                info.presentationTimeUs = (time - segmentStartPts).coerceAtLeast(0L)
                info.flags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer?.writeSampleData(trackMap[track], buffer, info)
                segmentBytes += size
                extractor.advance()
            }

            muxer?.let { runCatching { it.stop() }; runCatching { it.release() } }
            muxer = null

            if (segments.size < 2) {
                segments.forEach { runCatching { it.delete() } }
                return emptyList()
            }
            return segments
        } catch (t: Throwable) {
            runCatching { muxer?.release() }
            segments.forEach { runCatching { it.delete() } }
            return emptyList()
        } finally {
            runCatching { extractor.release() }
        }
    }
}
