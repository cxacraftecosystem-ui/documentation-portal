package com.fieldrepository.app.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException

object MicLock {
    const val SESSION = "session"

    private val _holder = MutableStateFlow<Any?>(null)
    val holder: StateFlow<Any?> = _holder

    val held: Boolean @Synchronized get() = _holder.value != null

    @Synchronized
    fun acquire(owner: Any): Boolean {
        val current = _holder.value
        if (current != null && current != owner) return false
        _holder.value = owner
        return true
    }

    @Synchronized
    fun release(owner: Any) {
        if (_holder.value == owner) _holder.value = null
    }

    @Synchronized
    fun reset() {
        _holder.value = null
    }
}

class MicBusyException : IllegalStateException("The microphone is held by another recording")

enum class MicFailure(val sentence: String) {
    BUSY("Another recording is using the microphone. Stop it, then record again."),
    NO_PERMISSION("Microphone access is off. Allow it in Settings, then record again."),
    STORAGE_FULL("This phone is out of space. Free some storage, then record again."),
    OTHER("Couldn't start the recorder. Try again.");

    companion object {
        const val MIN_FREE_BYTES = 2L * 1024 * 1024

        fun of(error: Throwable?, permitted: Boolean, freeBytes: Long): MicFailure = when {
            !permitted || error.chain().any { it is SecurityException } -> NO_PERMISSION
            freeBytes in 0 until MIN_FREE_BYTES || error.chain().any { it.noSpace() } -> STORAGE_FULL
            error.chain().any { it is MicBusyException } -> BUSY
            error is IOException -> OTHER
            error is IllegalStateException || error is RuntimeException -> BUSY
            else -> OTHER
        }

        fun freeBytes(file: File): Long =
            runCatching { (file.parentFile ?: file).usableSpace }.getOrDefault(-1L)

        private fun Throwable?.chain(): Sequence<Throwable> = generateSequence(this) { it.cause }

        private fun Throwable.noSpace(): Boolean {
            val text = message.orEmpty()
            return text.contains("ENOSPC") || text.contains("No space left", ignoreCase = true)
        }
    }
}
