package com.fieldrepository.app.legacyrecording

import android.media.MediaRecorder
import java.io.File

/**
 * The one legacy questionnaire take in progress (the old capture form's MPEG-4 recorder).
 *
 * It lives here, outside the composable, so every exit finalises the take instead of dropping it:
 * Stop, the section collapsing or the screen leaving (the recorder's dispose), and the system taking
 * [LegacyRecordingService] away. An MPEG-4 file is only playable once `stop()` has written its index,
 * so "finalise" means stop, release, and hand the file to whoever claims it next.
 *
 * A take finalised with nobody on screen to receive it (the service was destroyed) is held in
 * [orphan] until the recorder claims it with [finalise].
 */
object LegacyTake {

    /** The few recorder calls the form makes; [MediaRecorder] in the app, a fake in tests. */
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
        // A take still running is finalised first, never overwritten.
        finishCurrent()?.let { orphan = it }
        this.recorder = recorder
        this.file = file
    }

    @Synchronized
    fun pause() { recorder?.pause() }

    @Synchronized
    fun resume() { recorder?.resume() }

    /**
     * Stop and release the running take and return its file, or the take the service already
     * finalised. Null when there is nothing, or when the recorder held no audio (a stop straight
     * after start throws, and that file is unplayable, so it is deleted).
     */
    @Synchronized
    fun finalise(): File? {
        val current = finishCurrent()
        if (current != null) return current
        return orphan.also { orphan = null }
    }

    /** For the service: finalise so the file is playable, and keep it for the form to claim. */
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

    /** Tests only. */
    @Synchronized
    internal fun reset() {
        recorder = null
        file = null
        orphan = null
    }
}
