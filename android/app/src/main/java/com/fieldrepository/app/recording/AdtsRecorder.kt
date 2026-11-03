package com.fieldrepository.app.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

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

    fun stop() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }
}
