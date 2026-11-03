package com.fieldrepository.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import retrofit2.HttpException

const val PASSWORD_CHANGE_REQUIRED_HEADER = "X-Password-Change-Required"

fun isPasswordChangeRequired(status: Int, headerValue: String?): Boolean =
    status == 401 && !headerValue.isNullOrBlank()

fun Throwable.isPasswordChangeRequired(): Boolean {
    val http = this as? HttpException ?: return false
    return isPasswordChangeRequired(
        http.code(),
        http.response()?.headers()?.get(PASSWORD_CHANGE_REQUIRED_HEADER)
    )
}

fun isSessionEnded(status: Int, headerValue: String?, sentToken: String?, heldToken: String?): Boolean =
    status == 401 && headerValue.isNullOrBlank() && !sentToken.isNullOrBlank() && sentToken == heldToken

enum class SessionVerdict {
    CHOOSE_NEW_PASSWORD,

    REFUSED,

    EXPIRED,

    KEEP,
}

fun Throwable.sessionVerdict(): SessionVerdict {
    val status = (this as? HttpException)?.code() ?: return SessionVerdict.KEEP
    return when {
        isPasswordChangeRequired() -> SessionVerdict.CHOOSE_NEW_PASSWORD
        status == 403 -> SessionVerdict.REFUSED
        status == 401 -> SessionVerdict.EXPIRED
        else -> SessionVerdict.KEEP
    }
}

object PasswordChangeSignal {
    private val raised = MutableStateFlow(0L)

    val raises: StateFlow<Long> = raised.asStateFlow()

    fun raise() {
        raised.update { it + 1 }
    }
}

object SessionEndedSignal {
    private val raised = MutableStateFlow(0L)

    val raises: StateFlow<Long> = raised.asStateFlow()

    fun raise() {
        raised.update { it + 1 }
    }
}
