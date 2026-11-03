package com.fieldrepository.app.legacyrecording

import android.media.MediaRecorder
import java.io.File

object LegacyTake {

    interface Recorder {
        fun pause()
        fun resume()
        fun stop()
        fun release()
    }

    class Media(private val recorder: MediaRecorder) : Recorder {
        override fun pause() = recorder.pause()
        override fun resume() = recorder.resume()
        override fun stop() = recorder.stop()
        override fun release() = recorder.release()
    }

    private var recorder: Recorder? = null
    private var file: File? = null
    private var orphan: File? = null

    val active: Boolean @Synchronized get() = recorder != null

    @Synchronized
    fun begin(recorder: Recorder, file: File) {
        finishCurrent()?.let { orphan = it }
        this.recorder = recorder
        this.file = file
    }

    @Synchronized
    fun pause() { recorder?.pause() }

    @Synchronized
    fun resume() { recorder?.resume() }

    @Synchronized
    fun finalise(): File? {
        val current = finishCurrent()
        if (current != null) return current
        return orphan.also { orphan = null }
    }

    @Synchronized
    fun finaliseForLater() {
        finishCurrent()?.let { orphan = it }
    }

    private fun finishCurrent(): File? {
        val rec = recorder ?: return null
        val out = file
        recorder = null
        file = null
        val stopped = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        if (!stopped) {
            out?.delete()
            return null
        }
        return out?.takeIf { it.exists() && it.length() > 0 }
    }

    @Synchronized
    internal fun reset() {
        recorder = null
        file = null
        orphan = null
    }
}
