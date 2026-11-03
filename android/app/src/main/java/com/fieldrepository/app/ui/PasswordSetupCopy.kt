package com.fieldrepository.app.ui

import com.fieldrepository.app.data.SessionVerdict
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.accountHasPassword
import com.fieldrepository.app.data.apiErrorMessage
import com.fieldrepository.app.data.isPasswordChangeRequired
import com.fieldrepository.app.data.sessionVerdict
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

const val MIN_PASSWORD_LENGTH = 8

fun passwordRuleLine(suffix: String? = null): String =
    "At least $MIN_PASSWORD_LENGTH characters." + (suffix?.let { " $it" } ?: "")

fun mustChangePasswordBlocks(user: UserDto?): Boolean = user?.mustChangePassword == true

const val MAX_PASSWORD_LENGTH = 200

const val PASSWORD_GATE_HEADING = "Set a new password"
const val PASSWORD_GATE_SENTENCE = "Choose a new password to continue."

const val PASSWORD_CHANGE_SESSIONS = "You stay signed in here, and are signed out everywhere else."

const val SESSION_ENDED_SENTENCE = "Signed out. If your password changed, use the new one."

fun newPasswordRefusal(next: String, confirm: String, current: String = ""): String? {
    val length = next.codePointCount(0, next.length)
    return when {
        length < MIN_PASSWORD_LENGTH -> passwordRuleLine()
        length > MAX_PASSWORD_LENGTH -> "At most $MAX_PASSWORD_LENGTH characters."
        next != confirm -> "The two passwords do not match."
        current.isNotEmpty() && next == current ->
            "Choose a different password from your current one."
        else -> null
    }
}

fun passwordGateAsksForCurrentAfter(status: Int?): Boolean = status == 400

const val PASSWORD_CHANGE_NOT_SENT = "Your new password could not be sent, so nothing has " +
    "changed. Try again."
const val PASSWORD_CHANGE_NOT_SENT_OFFLINE = "No connection — nothing changed. Try again with signal."

const val PASSWORD_MAY_ALREADY_BE_IN_EFFECT = "Your new password may already work. Try it; if refused, use the old one."

const val PASSWORD_CHANGE_UNCONFIRMED = "Not sure your new password saved. Try again — or sign in with the new one, and the old one if that fails."

sealed interface PasswordGateAfterFailure {
    data class Stay(val message: String) : PasswordGateAfterFailure

    data class SignOut(val message: String) : PasswordGateAfterFailure

    data class Satisfied(val profile: UserDto) : PasswordGateAfterFailure
}

fun changePasswordOutcomeKnown(failure: Throwable): Boolean {
    val status = (failure as? HttpException)?.code() ?: return false
    return when {
        status >= 500 -> false
        status == 401 -> failure.isPasswordChangeRequired()
        else -> true
    }
}

suspend fun passwordGateAfterFailure(
    failure: Throwable,
    online: Boolean,
    probe: suspend () -> UserDto,
): PasswordGateAfterFailure {
    if (failure is CancellationException) throw failure
    fun usualWords() = PasswordGateAfterFailure.Stay(
        failure.apiErrorMessage(if (online) PASSWORD_CHANGE_NOT_SENT else PASSWORD_CHANGE_NOT_SENT_OFFLINE)
    )
    if (changePasswordOutcomeKnown(failure)) return usualWords()
    val profile = try {
        probe()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unanswered: Throwable) {
        return when (unanswered.sessionVerdict()) {
            SessionVerdict.EXPIRED -> PasswordGateAfterFailure.SignOut(PASSWORD_MAY_ALREADY_BE_IN_EFFECT)
            SessionVerdict.CHOOSE_NEW_PASSWORD -> usualWords()
            SessionVerdict.REFUSED, SessionVerdict.KEEP ->
                PasswordGateAfterFailure.Stay(PASSWORD_CHANGE_UNCONFIRMED)
        }
    }
    return if (mustChangePasswordBlocks(profile)) usualWords() else PasswordGateAfterFailure.Satisfied(profile)
}

fun passwordLinkOffered(target: UserDto): Boolean = accountHasPassword(target)

fun passwordLinkToken(pasted: String): String {
    val text = pasted.trim()
    if (text.isEmpty()) return ""
    for (run in text.split(PASTE_WHITESPACE)) {
        tokenInLink(run)?.let { return percentDecode(it) }
    }
    return text
}

private val PASTE_WHITESPACE = Regex("\\s+")

private fun parameterValue(part: String, openers: String): String? {
    var from = 0
    while (true) {
        val at = part.indexOf("token=", from)
        if (at < 0) return null
        if (at == 0 || part[at - 1] in openers) {
            return part.substring(at + "token=".length).takeWhile { it != '&' && it != '#' }
        }
        from = at + 1
    }
}

private fun tokenInLink(run: String): String? {
    val hash = run.indexOf('#')
    if (hash >= 0) parameterValue(run.substring(hash + 1), openers = "&")?.let { return it }
    return parameterValue(if (hash >= 0) run.substring(0, hash) else run, openers = "?&")
}

const val SET_PASSWORD_LINK_HOST = "field-repository.vercel.app"
const val SET_PASSWORD_LINK_PATH = "/set-password"

fun isSetPasswordLinkAddress(scheme: String?, host: String?, path: String?): Boolean =
    scheme.equals("https", ignoreCase = true) &&
        host.equals(SET_PASSWORD_LINK_HOST, ignoreCase = true) &&
        path?.trimEnd('/') == SET_PASSWORD_LINK_PATH

private fun percentDecode(value: String): String {
    if (!value.contains('%')) return value
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val hex = value.substring(index + 1, index + 3)
            val decoded = hex.toIntOrNull(16)
            if (decoded != null) {
                out.append(decoded.toChar())
                index += 3
                continue
            }
        }
        out.append(char)
        index += 1
    }
    return out.toString()
}

fun passwordLinkRefusal(reason: String?): String = when (reason?.trim()?.lowercase()) {
    "missing" -> "This link is incomplete. Paste all of it."
    "malformed" -> "Not a valid link. Ask for a new one."
    "expired" -> "This link has expired. Ask for a new one."
    "revoked" -> "This link was withdrawn. Ask for a new one."
    "spent" -> "Link already used. Sign in with your password."
    "unknown-account" -> "This link no longer points at an account."
    else -> "This password link is not valid."
}

fun passwordLinkPurposeLine(purpose: String?): String = when (purpose?.trim()?.uppercase()) {
    "INVITE" -> "Invitation — for an account that has never had a password."
    "RESET" -> "Reset — this account already has a password."
    else -> "One-time password link."
}
