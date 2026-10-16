package com.fieldrepository.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import retrofit2.HttpException

/**
 * THE SERVER'S HALF OF THE FIRST-PASSWORD GATE, AS THIS HANDSET HEARS IT.
 *
 * ── WHAT THE SERVER DOES ─────────────────────────────────────────────────────────────────────────
 *
 * While an account carries `mustChangePassword`, every authenticated route outside a short
 * allow-list answers **401** with [PASSWORD_CHANGE_REQUIRED_HEADER] and the detail "Choose a new
 * password to continue." (`backend/app/core/deps.py`). The allow-list is exactly what this app needs
 * to walk somebody through the gate: `GET /api/me`, `GET /api/auth/me`,
 * `POST /api/auth/change-password`, `POST /api/auth/logout`, `GET`/`POST /api/usage/consent` and
 * `GET /api/app/release/latest`. The break-glass master account is exempt.
 *
 * ── WHY A 401, AND WHY THIS CLIENT MUST NOT READ IT AS AN ORDINARY ONE ─────────────────────────────
 *
 * The server chose 401 because every queue on every installed build keeps its work on one and gives
 * it up on a 403: the record outbox (`FieldRepository.isTransient`), the design-workshop pass
 * (`isConnectionFailure`), the join cards (`dwRedeemJoinCard`). So the gate costs no queued fieldwork.
 *
 * But a 401 is also how this app learns a session is DEAD, and it signs the person out on one. A 401
 * carrying the header is the opposite: a LIVE session that owes a new password. Signing it out would
 * throw away the token and send the person round the sign-in card to arrive at the same gate, so the
 * header is the one thing that must be read before the status is acted on — see [sessionVerdict].
 * Every 401 WITHOUT it is still a dead session, and the queues still keep their work on it; what
 * changed when sessions were bound to their password is that the session root now hears of one from
 * ANY request, not only from `GET /me` at launch — see [SessionEndedSignal].
 */

/** The header the API marks a gated 401 with. Mirrors the server's spelling; spelled once here. */
const val PASSWORD_CHANGE_REQUIRED_HEADER = "X-Password-Change-Required"

/**
 * Does an answer with this status and this header value mean "this session must choose a new
 * password before anything else"?
 *
 * ONLY ON A 401, because that is the only status the server gates with. A 403 is a refusal of the
 * account or the act, and the queues are right to treat it as final; a header riding on one is not
 * the gate's shape and must not soften it.
 *
 * PRESENCE, NOT THE VALUE. The server sends `1`, and the header has no other value to carry: reading
 * the digit would only add a way for a proxy's rewrite to turn a held session into a dead one.
 */
fun isPasswordChangeRequired(status: Int, headerValue: String?): Boolean =
    status == 401 && !headerValue.isNullOrBlank()

/**
 * [isPasswordChangeRequired], asked of a failure. Reads only the status and the headers and never the
 * body, so it is safe to call before `apiErrorMessage`, which consumes the buffered body.
 */
fun Throwable.isPasswordChangeRequired(): Boolean {
    val http = this as? HttpException ?: return false
    return isPasswordChangeRequired(
        http.code(),
        http.response()?.headers()?.get(PASSWORD_CHANGE_REQUIRED_HEADER)
    )
}

/**
 * Does this answer say that the session this handset is HOLDING has ended?
 *
 * A plain 401 — no [PASSWORD_CHANGE_REQUIRED_HEADER] — to a request that carried [sentToken], while
 * the store still holds that same token as [heldToken]. Each clause rules out a 401 that is about
 * something else:
 *
 *  * **No token was sent:** a sign-in the server refused. Nobody was signed in to be signed out.
 *  * **The gate's header:** a LIVE session that owes a new password, which is [PasswordChangeSignal]'s.
 *  * **The store holds another token now:** the answer is about a session this handset has already
 *    left — `FieldRepository.changeOwnPassword` adopting the token its answer brought, or a
 *    sign-out followed by somebody else's sign-in — and a request still in flight with the old token
 *    must not end the new session.
 *
 * Pure, so a JVM test can walk it; `ApiClient` asks it of every answer from the API.
 */
fun isSessionEnded(status: Int, headerValue: String?, sentToken: String?, heldToken: String?): Boolean =
    status == 401 && headerValue.isNullOrBlank() && !sentToken.isNullOrBlank() && sentToken == heldToken

/**
 * What a failed `GET /me` means for the session this handset is holding.
 *
 * Pure, and the whole decision, so a JVM test can pin it: `RepositoryApp` only applies it.
 */
enum class SessionVerdict {
    /** A gated 401. Keep the token and every queue, mark the profile, and show the password gate. */
    CHOOSE_NEW_PASSWORD,

    /** 403: the server refused the ACCOUNT (suspended, rejected, de-empanelled). Sign out and say why. */
    REFUSED,

    /** Any other 401: the token itself is dead. Sign out. */
    EXPIRED,

    /** No answer at all, or one about the server rather than the account: keep the cached session. */
    KEEP,
}

fun Throwable.sessionVerdict(): SessionVerdict {
    val status = (this as? HttpException)?.code() ?: return SessionVerdict.KEEP
    return when {
        // FIRST, because it is a 401 too and the arm below would sign it out.
        isPasswordChangeRequired() -> SessionVerdict.CHOOSE_NEW_PASSWORD
        status == 403 -> SessionVerdict.REFUSED
        status == 401 -> SessionVerdict.EXPIRED
        else -> SessionVerdict.KEEP
    }
}

/**
 * Raised by the HTTP stack whenever a request comes back gated; watched by `RepositoryApp`.
 *
 * ── WHY A PROCESS-WIDE SIGNAL ────────────────────────────────────────────────────────────────────
 *
 * A gated answer can reach any caller — a screen, the outbox loop, the join-card flusher that runs
 * before any activity exists — and only the session root can act on it, by re-reading the profile
 * and putting the gate on screen. Threading a callback through every one of those callers would be a
 * change to all of them for the sake of one header; [ApiClient] already sees every request.
 *
 * A COUNTER IN A [StateFlow], not an event stream: a raise made before the root was composed (the
 * flusher at process start) is still there when the root starts collecting, and a burst of raises
 * from requests that were in flight together conflates into one re-read instead of one each.
 */
object PasswordChangeSignal {
    private val raised = MutableStateFlow(0L)

    /** How many gated answers this process has seen. Only changes matter; 0 means none yet. */
    val raises: StateFlow<Long> = raised.asStateFlow()

    fun raise() {
        raised.update { it + 1 }
    }
}

/**
 * Raised by the HTTP stack whenever [isSessionEnded] says so; watched by `RepositoryApp`, which
 * re-reads the profile and signs out only if `GET /me` agrees (`SessionVerdict.EXPIRED`).
 *
 * ── WHY IT EXISTS: A RETIRED TOKEN WAS NOTICED ONLY AT A COLD START ──────────────────────────────
 *
 * Since 2026-10-09 a session token carries a fingerprint of the password it was opened with, so a
 * change of that password anywhere — on the website, on another phone, by a provisioner, through a
 * redeemed link — retires this handset's token. This app read a session verdict in one place,
 * `GET /me` at launch, so every request after the change came back 401, every queue kept its work and
 * retried with the dead token, and nothing sent the person to sign in until the app was killed and
 * reopened. Days of fieldwork could sit on the phone under a banner promising an upload.
 *
 * ── THE QUEUES ARE NOT TOLD, AND MUST NOT BE ─────────────────────────────────────────────────────
 *
 * Each caller still gets its own 401 and still keeps its work on it (`isTransient`,
 * `isConnectionFailure`). This only tells the root, and the root asks `GET /me` — sent with the
 * CURRENT token — before it signs anybody out: a 401 that was about something else costs one re-read,
 * and a dead session costs nothing queued, because a sign-out clears the token and never the outbox.
 *
 * Shaped exactly like [PasswordChangeSignal], for its reasons: a raise made before the root was
 * composed is still there when it starts collecting, and a burst of refusals from requests that were
 * in flight together conflates into one re-read.
 */
object SessionEndedSignal {
    private val raised = MutableStateFlow(0L)

    /** How many ended-session answers this process has seen. Only changes matter; 0 means none yet. */
    val raises: StateFlow<Long> = raised.asStateFlow()

    fun raise() {
        raised.update { it + 1 }
    }
}
