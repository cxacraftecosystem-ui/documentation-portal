package com.fieldrepository.app.legacyrecording

import android.media.MediaRecorder
import com.fieldrepository.app.recording.MicBusyException
import com.fieldrepository.app.recording.MicLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

object LegacyTake {

    interface Recorder {
        fun pause()
        fun resume()
        fun stop()
        fun release()
    }

    class Media(val recorder: MediaRecorder) : Recorder {
        override fun pause() = recorder.pause()
        override fun resume() = recorder.resume()
        override fun stop() = recorder.stop()
        override fun release() = recorder.release()
    }

    private object Unowned

    private var recorder: Recorder? = null
    private var file: File? = null
    private var owner: Any = Unowned
    private val orphans = mutableListOf<Pair<Any, File>>()
    private val _holder = MutableStateFlow<Any?>(null)

    val holder: StateFlow<Any?> = _holder

    val active: Boolean @Synchronized get() = recorder != null

    @Synchronized
    fun begin(recorder: Recorder, file: File) {
        finishCurrent()
        if (!MicLock.acquire(this)) throw MicBusyException()
        take(Unowned, recorder, file)
    }

    @Synchronized
    fun <R : Recorder> start(owner: Any, file: File, create: () -> R): R {
        finishCurrent()
        if (!MicLock.acquire(this)) throw MicBusyException()
        val created = try {
            create()
        } catch (failed: Throwable) {
            MicLock.release(this)
            throw failed
        }
        take(owner, created, file)
        return created
    }

    fun owns(owner: Any): Boolean = synchronized(this) { recorder != null && this.owner === owner }

    @Synchronized
    fun pause() { recorder?.pause() }

    @Synchronized
    fun resume() { recorder?.resume() }

    @Synchronized
    fun finalise(): File? {
        val current = finishCurrent()
        if (current != null) {
            orphans.removeAll { it.second == current }
            return current
        }
        return if (orphans.isEmpty()) null else orphans.removeAt(orphans.size - 1).second
    }

    @Synchronized
    fun finalise(owner: Any): File? {
        if (recorder != null && this.owner === owner) {
            val current = finishCurrent()
            if (current != null) orphans.removeAll { it.second == current }
            return current
        }
        val index = orphans.indexOfFirst { it.first === owner }
        return if (index < 0) null else orphans.removeAt(index).second
    }

    @Synchronized
    fun finaliseForLater() {
        finishCurrent()
    }

    private fun take(owner: Any, recorder: Recorder, file: File) {
        this.recorder = recorder
        this.file = file
        this.owner = owner
        _holder.value = owner
    }

    private fun finishCurrent(): File? {
        val rec = recorder ?: return null
        val out = file
        val was = owner
        recorder = null
        file = null
        owner = Unowned
        _holder.value = null
        val stopped = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        MicLock.release(this)
        if (!stopped) {
            out?.delete()
            return null
        }
        val kept = out?.takeIf { it.exists() && it.length() > 0 } ?: return null
        orphans.add(was to kept)
        return kept
    }

    @Synchronized
    internal fun reset() {
        recorder = null
        file = null
        owner = Unowned
        orphans.clear()
        _holder.value = null
        MicLock.release(this)
    }
}
