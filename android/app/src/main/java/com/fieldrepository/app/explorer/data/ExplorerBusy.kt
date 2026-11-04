package com.fieldrepository.app.explorer.data

import kotlinx.coroutines.delay
import retrofit2.HttpException

class ExplorerBusyException(val retryAfterSeconds: Int, message: String) : IllegalStateException(message)

object ExplorerBusy {
    const val DEFAULT_SECONDS = 60
    const val MAX_SECONDS = 120

    fun isBusy(code: Int): Boolean = code == 503 || code == 429

    fun retryAfter(header: String?): Int =
        header?.trim()?.toIntOrNull()?.coerceIn(1, MAX_SECONDS) ?: DEFAULT_SECONDS

    fun of(failure: Throwable): ExplorerBusyException? = when {
        failure is ExplorerBusyException -> failure
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
    ): T {
        try {
            return block()
        } catch (failure: Throwable) {
            val busy = of(failure) ?: throw failure
            var left = busy.retryAfterSeconds
            while (left > 0) {
                onWait(left)
                delay(tickMs)
                left--
            }
            onWait(null)
            return block()
        }
    }
}
