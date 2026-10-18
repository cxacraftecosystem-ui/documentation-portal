package com.fieldrepository.app.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * AAC in an ADTS stream: mono, 64 kbps, 44.1 kHz (DESIGN §6.1.2). ADTS has no index to write at the
 * end, so a file cut short by a process kill or a dead battery still plays up to its last frame;
 * [Remuxer] turns it into the `.m4a` that is uploaded.
 *
 * [onEnded] fires once, for an error or info listener firing (e.g. the disk filling), so the service
 * finalises the take on every exit path.
 */
class AdtsRecorder(private val context: Context, private val output: File, private val onEnded: (String) -> Unit) {
    private var recorder: MediaRecorder? = null
    var paused: Boolean = false
        private set

    fun start() {
        output.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioEncodingBitRate(64_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(output.absolutePath)
        r.setOnErrorListener { _, what, _ -> onEnded("error:$what") }
        r.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onEnded("info:$what")
        }
        r.prepare()
        r.start()
        recorder = r
    }

    fun pause() {
        recorder?.let { runCatching { it.pause() }.onSuccess { paused = true } }
    }

    fun resume() {
        recorder?.let { runCatching { it.resume() }.onSuccess { paused = false } }
    }

    /** Stops and releases. Safe to call twice; a stop on a recorder that captured nothing is swallowed. */
    fun stop() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }
}
