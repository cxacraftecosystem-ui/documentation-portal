package com.fieldrepository.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * WHETHER THE SERVER HAS STOPPED ANSWERING, as opposed to the phone having no network.
 *
 * The offline line reads the phone's connectivity, so a phone on good Wi-Fi talking to a server
 * that is down (a deploy, a crashed box, a captive portal that swallows the request) showed nothing
 * at all while every load failed. [ApiClient] reports every request's outcome here: a response of
 * any status means the server answered, a transport failure means it did not. After
 * [FAILURES_BEFORE_UNREACHABLE] failures in a row [unreachable] turns true, and the next answer of
 * any kind turns it false again — the outbox sync loop keeps asking, so recovery is noticed without
 * the researcher doing anything.
 */
object ServerReachability {
    /** One failure is a dropped packet; two in a row is a server that is not there. */
    const val FAILURES_BEFORE_UNREACHABLE = 2

    /** The line the app shows while [unreachable] is true and the phone itself is online. */
    const val SERVER_UNREACHABLE_SENTENCE = "Can't reach the server — your work is kept on this phone."

    private val consecutiveFailures = AtomicInteger(0)
    private val state = MutableStateFlow(false)

    val unreachable: StateFlow<Boolean> = state.asStateFlow()

    /** The server answered — with anything, an error status included. */
    fun recordAnswer() {
        consecutiveFailures.set(0)
        if (state.value) state.value = false
    }

    /** A request never got an answer. A cancelled call says nothing about the server and is ignored. */
    fun recordFailure(error: IOException) {
        if (error.message.equals("Canceled", ignoreCase = true)) return
        if (consecutiveFailures.incrementAndGet() >= FAILURES_BEFORE_UNREACHABLE && !state.value) {
            state.value = true
        }
    }
}
