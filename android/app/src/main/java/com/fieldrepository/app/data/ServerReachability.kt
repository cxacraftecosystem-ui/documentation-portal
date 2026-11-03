package com.fieldrepository.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

object ServerReachability {
    const val FAILURES_BEFORE_UNREACHABLE = 2

    const val SERVER_UNREACHABLE_SENTENCE = "Can't reach the server — your work is saved on this phone."

    private val consecutiveFailures = AtomicInteger(0)
    private val state = MutableStateFlow(false)

    val unreachable: StateFlow<Boolean> = state.asStateFlow()

    fun recordAnswer() {
        consecutiveFailures.set(0)
        if (state.value) state.value = false
    }

    fun recordFailure(error: IOException) {
        if (error.message.equals("Canceled", ignoreCase = true)) return
        if (consecutiveFailures.incrementAndGet() >= FAILURES_BEFORE_UNREACHABLE && !state.value) {
            state.value = true
        }
    }
}
