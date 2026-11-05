package com.fieldrepository.app.explorer.data

import kotlinx.coroutines.delay
import retrofit2.HttpException

class ExplorerBusyException(val retryAfterSeconds: Int, message: String) : IllegalStateException(message)

object ExplorerBusy {
    const val DEFAULT_SECONDS = 60
    const val MAX_SECONDS = 120
    const val REBUILT_ATTEMPTS = 5

    fun isBusy(code: Int): Boolean = code == 503 || code == 429

    fun retryAfter(header: String?): Int =
        header?.trim()?.toIntOrNull()?.coerceIn(1, MAX_SECONDS) ?: DEFAULT_SECONDS

    fun of(failure: Throwable): ExplorerBusyException? = when {
        failure is ExplorerBusyException -> failure
        failure is TransferFailed && isBusy(failure.code) -> ExplorerBusyException(DEFAULT_SECONDS, ExplorerText.busy(DEFAULT_SECONDS))
        failure is HttpException && isBusy(failure.code()) -> {
            val seconds = retryAfter(failure.response()?.headers()?.get("Retry-After"))
            ExplorerBusyException(seconds, ExplorerText.busy(seconds))
        }
        else -> null
    }

    suspend fun <T> retryOnce(
        onWait: (secondsLeft: Int?) -> Unit,
        tickMs: Long = 1_000,
        block: suspend () -> T,
    ): T = retry(2, onWait, tickMs, block)

    suspend fun <T> retry(
        attempts: Int,
        onWait: (secondsLeft: Int?) -> Unit,
        tickMs: Long = 1_000,
        block: suspend () -> T,
    ): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                val busy = of(failure) ?: throw failure
                if (attempt >= attempts) throw failure
                var left = busy.retryAfterSeconds
                while (left > 0) {
                    onWait(left)
                    delay(tickMs)
                    left--
                }
                onWait(null)
                attempt++
            }
        }
    }
}
