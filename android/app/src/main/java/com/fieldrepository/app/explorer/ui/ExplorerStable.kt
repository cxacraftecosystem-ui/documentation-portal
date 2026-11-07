package com.fieldrepository.app.explorer.ui

import kotlinx.coroutines.CancellationException

class ExplorerIdentity(private val value: Any?) {
    override fun equals(other: Any?): Boolean = other is ExplorerIdentity && other.value === value

    override fun hashCode(): Int = System.identityHashCode(value)
}

suspend fun <T> attemptOrNull(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    null
}
