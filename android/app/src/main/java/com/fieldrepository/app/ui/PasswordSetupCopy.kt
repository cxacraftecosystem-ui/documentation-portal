package com.fieldrepository.app.ui

import com.fieldrepository.app.data.SessionVerdict
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.accountHasPassword
import com.fieldrepository.app.data.apiErrorMessage
import com.fieldrepository.app.data.isPasswordChangeRequired
import com.fieldrepository.app.data.sessionVerdict
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/**
 * THE WORDS AND THE RULES BEHIND THE TWO PASSWORD SCREENS, with no Compose in sight.
 *
 * ── WHY THIS IS A FILE OF ITS OWN ────────────────────────────────────────────────────────────────
 *
 * The same argument `AccessRefusalCopy.kt` makes, and it applies harder here. Six of the sentences
 * below are one-per-refusal, each naming a DIFFERENT next action — "ask for another", "you already
 * used it, go and sign in", "ask the administrator what happened" — and the failure this feature can
 * actually ship is two of them being the same sentence, or one of them quietly becoming "invalid
 * link", which leaves a person with no next move at all. A `when` block inside a composable is not
 * reachable from a JVM test; this file is, and `PasswordSetupCopyTest` walks it.
 *
 * The token extraction is here for the same reason: it is the one piece of parsing on the redeem
 * path, it decides whether a designer who pastes a whole link gets a form or a refusal, and looking
 * at it is not evidence that it works.
 *
 * ── WHAT IS DELIBERATELY *NOT* HERE ──────────────────────────────────────────────────────────────
 *
 * The refusal sentence for a POST. `POST /auth/set-password` answers with the SERVER's own sentence
 * for every one of these reasons (`_SET_PASSWORD_REFUSALS` in backend/app/api/routes/auth.py), and
 * the redeem screen shows that verbatim rather than looking one up here. [passwordLinkRefusal] is
 * only for the link CHECK — `POST /auth/set-password/check`, or `GET /auth/set-password` on a server
 * older than that route — which answers with a reason WORD and no sentence, deliberately, because the
 * words a person reads are the client layer's job on that route and the server says so.
 */

/**
 * The shortest password this product will store.
 *
 * ONE FLOOR, FIVE SPELLINGS OF IT, AND THEY MUST AGREE. `credential_links.MIN_PASSWORD_LENGTH`,
 * `SetPasswordRequest.password`, `ChangePasswordRequest.newPassword`, `LoginRequest.password` and
 * `UserCreate.password` all carry 8 on the server, so that a password which can be SET can always be
 * used to sign in. The web mirrors it in `frontend/lib/signIn.ts`; this is the handset's copy.
 *
 * MIRRORED RATHER THAN FETCHED, the treatment `DW_DICTATION_MAX_BYTES` gets: no endpoint reports it,
 * it is a deployment constant, and the server refuses either way — so the only thing a round trip
 * would buy is a screen that cannot draw its own hint sentence with no signal.
 */
const val MIN_PASSWORD_LENGTH = 8

/**
 * The one line printed under a pair of password boxes.
 *
 * A FUNCTION AND NOT A CONSTANT, because the two screens have a genuinely different second clause —
 * a link works once, the first-login gate involves no link at all — and a shared string carrying the
 * link sentence would have the gate telling somebody about a link they are not holding. The FIRST
 * clause is what must not diverge, and it is the only part this owns. `passwordRuleLine` in
 * `frontend/lib/signIn.ts` is the same function, word for word.
 */
fun passwordRuleLine(suffix: String? = null): String =
    "At least $MIN_PASSWORD_LENGTH characters." + (suffix?.let { " $it" } ?: "")

/**
 * Must this account choose a new password before it is let into the product?
 *
 * ── THE DOOR STILL OPENS; EVERYTHING BEHIND IT IS REFUSED ────────────────────────────────────────
 *
 * `POST /auth/login` mints a token for an account carrying `mustChangePassword`, deliberately: the
 * only route that can change a password needs a bearer token, so a refusal at the door would be a
 * demand the account could never satisfy. Since 2026-10-09 the server also refuses every OTHER route
 * for that token with a gated 401 (see `data/PasswordChangeRequired.kt`), so this is no longer the
 * only thing holding the line — but it is still the only thing that can show the person what to do,
 * and the same answer is what holds this app's own background sends back while the gate is up. The
 * consent gate (`usageConsentBlocks`) has the door half of this shape and not the refusal half, and
 * the two are `when` arms side by side in `RepositoryApp`.
 *
 * ── A NULL IS "NO GATE", NEVER "GATE OPEN" AND NEVER "GATE SHUT" ─────────────────────────────────
 *
 * [UserDto.mustChangePassword] is nullable because a deployment older than the column sends nothing,
 * and a handset in the field may well be talking to one. `== true` is the only safe reading: it must
 * not invent a demand nobody made, and it must not claim the person has already chosen. This is the
 * same rule `usageConsentBlocks` states as `?.required == true`.
 */
fun mustChangePasswordBlocks(user: UserDto?): Boolean = user?.mustChangePassword == true

/**
 * The longest password this product will store, mirrored for [MIN_PASSWORD_LENGTH]'s reason.
 *
 * The server refuses anything longer on every password field it takes — create, update, change and
 * set-password — so refusing it here says so in a sentence before the round trip rather than as a
 * schema complaint after it. Counted in characters (code points), the way the server counts them.
 */
const val MAX_PASSWORD_LENGTH = 200

/**
 * The first-password gate's heading and its one sentence.
 *
 * ── NEUTRAL, BECAUSE THE GATE NO LONGER HAS ONE CAUSE ────────────────────────────────────────────
 *
 * It said "An administrator set your password", which was true while the only road onto the screen
 * was an account somebody else had created. An administrator can now also require a change at the
 * next sign-in WITHOUT touching the password — of somebody who chose their own — and telling that
 * person an administrator set it reads as "somebody has been into my account". The sentence is the
 * server's own `detail` for a session held at the gate, word for word, and the web gate says it too,
 * so a person who meets it in all three places reads one thing three times.
 */
const val PASSWORD_GATE_HEADING = "Set a new password"
const val PASSWORD_GATE_SENTENCE = "Choose a new password to continue."

/**
 * What changing the password does to the account's other sessions — the second clause of the rule
 * line under the gate's boxes.
 *
 * It said "Other devices stay signed in." until 2026-10-09, which was true of a server that compared
 * nothing. Since then a session token carries a fingerprint of the password it was opened with, so
 * the change ends every session of the account but the fresh one the answer carries, which
 * `FieldRepository.changeOwnPassword` adopts. That is also what retires a session somebody else
 * opened with a temporary password. `PASSWORD_CHANGE_SESSIONS` in `frontend/lib/passwordChange.ts`,
 * word for word.
 *
 * ── "SIGNED OUT" IS THE SERVER'S REFUSAL; WHEN A DEVICE NOTICES IS THE CLIENT'S ───────────────────
 *
 * The old token is refused from the moment of the change, everywhere. A web tab drops it at its next
 * request. This build notices at its next request too, whichever screen or queue makes it: a plain
 * 401 raises `SessionEndedSignal`, the root confirms it with `GET /me`, and the sign-in card says
 * [SESSION_ENDED_SENTENCE]. Builds before this one read a session verdict only at launch, so the
 * tablet in the next room keeps its dead token, its queues retry with it, and nothing sends anybody to
 * sign in until the app is reopened or signed out by hand. Nothing queued is lost on either.
 */
const val PASSWORD_CHANGE_SESSIONS = "You stay signed in here, and are signed out everywhere else."

/**
 * What the sign-in card says when the session this handset held has ended — `SessionVerdict.EXPIRED`,
 * at launch or, through `SessionEndedSignal`, after any request answered with a plain 401.
 *
 * It said "Your session expired. Please sign in again." Since 2026-10-09 the commonest reason is not
 * time: a password changed on the website, on another phone, by an administrator or through a
 * redeemed link retires every session opened with the old one — and the person this happens to has to
 * know WHICH password to type next. Both of the ways it happens to somebody are named, with the one
 * answer that serves both.
 */
const val SESSION_ENDED_SENTENCE = "Signed out. If your password changed, use the new one."

/**
 * Why a new password may not be sent yet, as the sentence to show — or null when it may.
 *
 * Shared by both screens that set one. [current] is the password the first-password gate will send
 * as the current one, typed or carried from the door; the redeem screen has none and passes nothing.
 *
 * ── THE PAIR IS CHECKED HERE BECAUSE THE SERVER NEVER SEES THE SECOND BOX ────────────────────────
 *
 * It takes one new password, so a mismatch it cannot detect would be filed as the person's choice.
 *
 * ── AND THE SAME PASSWORD IS REFUSED HERE AS WELL AS THERE ───────────────────────────────────────
 *
 * The gate exists to replace a secret somebody else may know, and re-entering the temporary password
 * used to satisfy it. The server now refuses that and stays the authority; this only spares the round
 * trip where the screen already holds the current password. Raw strings, compared exactly: a password
 * is never trimmed.
 */
fun newPasswordRefusal(next: String, confirm: String, current: String = ""): String? {
    val length = next.codePointCount(0, next.length)
    return when {
        length < MIN_PASSWORD_LENGTH -> passwordRuleLine()
        length > MAX_PASSWORD_LENGTH -> "At most $MAX_PASSWORD_LENGTH characters."
        next != confirm -> "The two passwords do not match."
        // `newPasswordProblem` in `frontend/lib/passwordChange.ts` says the same, word for word.
        current.isNotEmpty() && next == current ->
            "Choose a different password from your current one."
        else -> null
    }
}

/**
 * After the server refused a change sent with the password carried from the door, must the gate now
 * ask for the current password itself?
 *
 * ── ONLY ON A 400 ────────────────────────────────────────────────────────────────────────────────
 *
 * `POST /auth/change-password` answers a wrong current password with 400 (a 401 until 2026-10-09,
 * which the web could not tell from a dead session). The carried password can be wrong for a real
 * reason — an administrator set another between the sign-in and this screen — and a box the screen
 * keeps hidden is a box nobody can correct, which left "Sign out instead" as the only control that
 * worked. A 400 is the family that refusal belongs to; showing the box after one of its siblings costs
 * a person one retype. A 401 is the SESSION and not the password, and a 429 is the guessing budget,
 * where a fresh box would only invite another guess.
 */
fun passwordGateAsksForCurrentAfter(status: Int?): Boolean = status == 400

/**
 * What the gate says when the change did not leave the phone, or did not land — the sentences it has
 * always shown when a failure carried no words of its own.
 *
 * TRUE ONLY WHEN SOMETHING HAS SAID SO. They are shown after a refusal the server answered, or after
 * `GET /me` has confirmed the account still owes a password; never on the strength of a missing
 * answer alone. See [passwordGateAfterFailure].
 */
const val PASSWORD_CHANGE_NOT_SENT = "Your new password could not be sent, so nothing has " +
    "changed. Try again."
const val PASSWORD_CHANGE_NOT_SENT_OFFLINE = "No connection — nothing changed. Try again with signal."

/**
 * The change may have landed and this session has since been refused: the gate signs out and the
 * sign-in card says this. Two passwords, in the order to try them, because only the server knows
 * which one it holds.
 */
const val PASSWORD_MAY_ALREADY_BE_IN_EFFECT = "Your new password may already work. Try it; if refused, use the old one."

/**
 * The change's answer was lost and the question asked afterwards went unanswered too. The gate stays
 * up and says so — a retry finds out either way — with the same two passwords for whoever signs out.
 */
const val PASSWORD_CHANGE_UNCONFIRMED = "Not sure your new password saved. Try again — or sign in with the new one, and the old one if that fails."

/** What the first-password gate does once `POST /auth/change-password` has failed. */
sealed interface PasswordGateAfterFailure {
    /** Stay on the gate and say [message]. */
    data class Stay(val message: String) : PasswordGateAfterFailure

    /** The session this handset held has been refused: sign out, and put [message] on the card. */
    data class SignOut(val message: String) : PasswordGateAfterFailure

    /** The server no longer asks this account for a new password: the gate is satisfied by [profile]. */
    data class Satisfied(val profile: UserDto) : PasswordGateAfterFailure
}

/**
 * Did this failure of `POST /auth/change-password` settle what happened to the password?
 *
 * ── AN ANSWER FROM THE ROUTE DID ─────────────────────────────────────────────────────────────────
 *
 * A 400, 403, 422 or 429, or a gated 401, is the application refusing INSTEAD of writing, and its
 * sentence says why: the current password was wrong, the new one is the old one, the guessing budget
 * is spent.
 *
 * ── THREE FAILURES DID NOT ───────────────────────────────────────────────────────────────────────
 *
 * The server commits the new password before it answers, and from that moment the token the request
 * carried is retired. So a failure that may have happened AFTER the request was sent proves nothing
 * about the password:
 *
 *  * **No answer at all** — a read timeout, a connection dropped mid-answer, a body that would not
 *    decode.
 *  * **A 5xx** — the gateway in front of the origin, or the origin itself, may fail after the write.
 *  * **A plain 401** — the token is dead, and this very change is one of the things that kills it: a
 *    retry of a change that landed meets exactly this.
 */
fun changePasswordOutcomeKnown(failure: Throwable): Boolean {
    val status = (failure as? HttpException)?.code() ?: return false
    return when {
        status >= 500 -> false
        status == 401 -> failure.isPasswordChangeRequired()
        else -> true
    }
}

/**
 * What the gate does after a failed change, decided BEFORE a word is shown.
 *
 * ── WHY IT ASKS INSTEAD OF GUESSING ──────────────────────────────────────────────────────────────
 *
 * On a field connection the answer to a change that landed is lost often enough to matter. The gate
 * used to report every failure the same way, so somebody whose new password was already in force was
 * told nothing had changed — and the retry, sent with the session the change had retired, came back
 * "This session is no longer valid". They signed out, typed the temporary password they had been
 * told was still theirs, were refused, and concluded they were locked out.
 *
 * So where the failure settles nothing ([changePasswordOutcomeKnown]), [probe] — `GET /me` with the
 * session this handset still holds — is asked first, and its answer chooses the sentence:
 *
 *  * **A plain 401:** the held session is dead, most likely retired by this very change. Sign out with
 *    [PASSWORD_MAY_ALREADY_BE_IN_EFFECT].
 *  * **The account still owes a password** (the flag, or the gate's own 401): nothing landed, so the
 *    gate's usual words are now true, and they are what it says.
 *  * **It owes none:** the server no longer asks for a change — a server older than the fingerprint
 *    kept the session and cleared the flag in the same write — so the gate is satisfied.
 *  * **No answer, or any other:** nothing is known. Stay, and say [PASSWORD_CHANGE_UNCONFIRMED].
 *
 * The failure's body is read once, and only on the arms that show its words. A cancelled coroutine is
 * not a failure and is rethrown: a screen that has left must not decide anything.
 */
suspend fun passwordGateAfterFailure(
    failure: Throwable,
    online: Boolean,
    probe: suspend () -> UserDto,
): PasswordGateAfterFailure {
    if (failure is CancellationException) throw failure
    // The gate's usual words — the server's own sentence where the failure carries one. A function,
    // so the body is read only by an arm that shows it: `apiErrorMessage` consumes the buffer.
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

/**
 * May an administrator be offered a set-password link for this account?
 *
 * ── "HAS A PASSWORD", NOT "DOES NOT SIGN IN WITH GOOGLE" ─────────────────────────────────────────
 *
 * The button was hidden for every account whose `authProvider` read GOOGLE, on the premise that such
 * an account has no password to set. Until 2026-10-09 a Google sign-in rewrote the provider on ANY
 * account, keeping the password and its `passwordSetAt` — so the premise was false for every account
 * that had used both doors, which includes the ones administrators provision with a Gmail address.
 * `passwordSetAt` is the fact the button needs: every account holding a password was stamped when
 * the column arrived, so its absence means the account has never had one.
 *
 * AND AN ACCOUNT THAT HAS NEVER HAD ONE GETS NO LINK, whatever its provider. Giving it a password is
 * a decision, taken on the web's users page, not a side effect of handing somebody a link. The web
 * draws its "Password link" on the identical test (`hasPassword` in
 * `frontend/app/(protected)/users/accountAdmin.ts`).
 */
fun passwordLinkOffered(target: UserDto): Boolean = accountHasPassword(target)

/**
 * The token inside whatever a designer pasted.
 *
 * ── WHY THE SCREEN TAKES A PASTE AT ALL, RATHER THAN ONLY A DEEP LINK ────────────────────────────
 *
 * An administrator issues a link and hands it over by hand — a message, a note, read out over a
 * telephone. On a handset that arrives as a URL in a chat app, and tapping it opens a BROWSER, which
 * works and is not what somebody standing in a courtyard with this app open is trying to do. So the
 * screen accepts the whole link pasted, and also the bare token for the case where it reached them
 * without the address around it.
 *
 * ── THE TOKEN IS IN THE QUERY OR IN THE FRAGMENT, AND BOTH ARE READ ──────────────────────────────
 *
 * Links issued until now carry it in the query, `/set-password?token=…`. The server is moving them to
 * the FRAGMENT, `/set-password#token=…`, because a fragment is never sent to any server: the query
 * puts the token in the web host's request log and in the browser's history the moment the link is
 * opened (docs/OPEN_FINDINGS.md). The handset must read both — links of the old shape stay in chat
 * histories until they expire, and builds up to 0.0.15, which read only the query, are why the
 * server cannot switch until they have left the field. A tapped link reaches here whole, fragment
 * included: `MainActivity.takePasswordLink` hands over `Uri.toString()`, and the manifest's filter
 * matches on the path, which a fragment does not change.
 *
 * ── WHAT IT WILL AND WILL NOT DO ─────────────────────────────────────────────────────────────────
 *
 * It reads `token=` where it opens a parameter of the fragment or of the query — after `#` or `&` in
 * the fragment, after `?` or `&` in the query, or at the very start of a pasted `token=…` — and
 * otherwise returns the text as typed. THE FRAGMENT IS READ FIRST, as the web's
 * `takeLinkTokenFromAddress` reads it, so one link pasted on either client gives one token; a link
 * never carries two, and this only settles which wins if one ever does.
 *
 * WHITESPACE ENDS A LINK. A base64url token holds none, and a paste is often the whole message — "Your
 * link: https://…?token=… It expires at 14:12" — so the text is read one whitespace-separated run at
 * a time and the first run that carries a token wins. Read whole, the words after the link went to
 * the server as part of the token, came back "malformed", and hid the password boxes behind a refusal
 * for a link that was good.
 *
 * It does NOT validate the shape: the token is `base64url(payload).base64url(HMAC)` and the server
 * checks the signature, the shape, the expiry, the row AND the credential fingerprint. A client-side
 * shape test would only be able to produce a SEVENTH refusal — one the server does not have a word
 * for — for a string the server might well have accepted.
 *
 * PERCENT-DECODING IS DONE HERE because the value is URL-encoded in the link, and what the server
 * verifies is the token itself: sent in the check's JSON body, or — to a server older than that
 * route — in a query string Retrofit encodes again. Either way the encoded form would put `%3D` in
 * the token and the signature would not verify. It is deliberately NOT a general decoder — only
 * `%XX` pairs, and a malformed one is left standing rather than throwing, because a person who
 * pasted something odd is owed the server's refusal and not a crash.
 */
fun passwordLinkToken(pasted: String): String {
    val text = pasted.trim()
    if (text.isEmpty()) return ""
    for (run in text.split(PASTE_WHITESPACE)) {
        tokenInLink(run)?.let { return percentDecode(it) }
    }
    return text
}

private val PASTE_WHITESPACE = Regex("\\s+")

/** `token=` opening a parameter: at the start of the part, or after one of [openers]. */
private fun parameterValue(part: String, openers: String): String? {
    var from = 0
    while (true) {
        val at = part.indexOf("token=", from)
        if (at < 0) return null
        if (at == 0 || part[at - 1] in openers) {
            // Further parameters may follow it, and in the query a fragment may follow too.
            return part.substring(at + "token=".length).takeWhile { it != '&' && it != '#' }
        }
        from = at + 1
    }
}

/** The token one whitespace-free run carries, fragment first — see [passwordLinkToken]. */
private fun tokenInLink(run: String): String? {
    val hash = run.indexOf('#')
    if (hash >= 0) parameterValue(run.substring(hash + 1), openers = "&")?.let { return it }
    return parameterValue(if (hash >= 0) run.substring(0, hash) else run, openers = "?&")
}

/**
 * The host and path an administrator's link is issued for, as the manifest's VIEW filter names them.
 * See [isSetPasswordLinkAddress].
 */
const val SET_PASSWORD_LINK_HOST = "field-repository.vercel.app"
const val SET_PASSWORD_LINK_PATH = "/set-password"

/**
 * Is this the address of an administrator's set-password link — https, on the web app's host, at the
 * redeem path, a trailing slash tolerated?
 *
 * THE MANIFEST'S FILTER IS NOT THE CHECK. It decides which IMPLICIT intents the OS offers this app,
 * and the activity is exported for the launcher, so any app on the phone can address it EXPLICITLY
 * with whatever data it likes — `content://anything/set-password?token=…` included — and a filter
 * never sees that intent. The redeem screen then sets a password with whatever token arrived, so
 * what is read off an intent is held to the same three facts the filter states.
 */
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

/**
 * One sentence per refusal from the link check (`POST /auth/set-password/check`, or the older
 * `GET /auth/set-password`; both answer the same words), because each has a different next action.
 *
 * ── KEYED ON THE SERVER'S REASON WORD, NEVER ON ITS PROSE ────────────────────────────────────────
 *
 * The keys are the words `app/services/credential_links.py` defines as constants — `MISSING`,
 * `MALFORMED`, `EXPIRED`, `REVOKED`, `SPENT`, `UNKNOWN_ACCOUNT`. Matching on a sentence is what breaks
 * the first time somebody fixes a comma, and the route deliberately returns the WORD so that the
 * words a person reads are this layer's decision.
 *
 * ── AND WHY THERE ARE SIX AND NOT ONE ────────────────────────────────────────────────────────────
 *
 * "This link is not valid" is true of all six and useful for none of them. Expired means "ask for
 * another"; revoked means "ask the administrator what happened"; already used means "you have set it
 * — go and sign in", which is the opposite of asking anybody for anything. A single sentence leaves a
 * person with no next action that exists, which is the same failure the sign-in refusals were split up
 * to end. `LINK_REFUSALS` in `frontend/app/set-password/page.tsx` carries the same six, word for word,
 * because a designer refused on the phone opens the website next.
 *
 * The UNKNOWN branch is the seventh answer and is a real one: a reason word this build has never heard
 * of, from a server newer than the handset. It says only what a bare refusal proves.
 */
fun passwordLinkRefusal(reason: String?): String = when (reason?.trim()?.lowercase()) {
    "missing" -> "This link is incomplete. Paste all of it."
    "malformed" -> "Not a valid link. Ask for a new one."
    "expired" -> "This link has expired. Ask for a new one."
    "revoked" -> "This link was withdrawn. Ask for a new one."
    "spent" -> "Link already used. Sign in with your password."
    "unknown-account" -> "This link no longer points at an account."
    else -> "This password link is not valid."
}

/**
 * How long a link lasts, said in words beside the one an administrator has just minted.
 *
 * TWO PURPOSES AND TWO LIFETIMES, and the server picks which — an INVITE is generous (72 hours,
 * passed on by hand, read on a Monday and acted on after a conference) and a RESET is short (2 hours,
 * because it answers "I am locked out NOW" and a link outliving that conversation is a spare key left
 * under the mat). The handset does not compute either number: it prints the server's own `expiresAt`,
 * and this only names WHICH KIND it is, because "expires at 14:12" answers a different question from
 * "this is an invitation".
 */
fun passwordLinkPurposeLine(purpose: String?): String = when (purpose?.trim()?.uppercase()) {
    "INVITE" -> "Invitation — for an account that has never had a password."
    "RESET" -> "Reset — this account already has a password."
    else -> "One-time password link."
}
