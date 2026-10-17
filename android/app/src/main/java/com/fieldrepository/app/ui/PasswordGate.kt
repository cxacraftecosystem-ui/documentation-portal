package com.fieldrepository.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.ConnectivityObserver
import com.fieldrepository.app.data.IssuedPasswordLinkDto
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * THE PASSWORD SCREENS THIS HANDSET DID NOT HAVE.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT WAS MISSING, IN THREE PARTS
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * 1. **`mustChangePassword` WAS SET BY THE SERVER AND CONSUMED BY NO SCREEN — on either client.**
 *    `POST /api/users` creates an account with the flag, `serialize_user` carries it on all four
 *    doors, `POST /auth/change-password` is the route it names, and nothing anywhere called it. An
 *    account whose password an administrator typed — a secret two people know by construction —
 *    signed in, worked normally, and nobody was ever asked to replace it. [PasswordGateScreen] is
 *    this client's half; `FirstPasswordGate` in `frontend/app/login/page.tsx` is the web's.
 *
 * 2. **The redeem screen was web-only.** A designer sent a link opened it in a browser, which works.
 *    [SetPasswordLinkScreen] puts it in the app, because the link arrives in a chat app on the same
 *    phone this is installed on and the browser is a detour.
 *
 * 3. **An administrator could not issue one from the phone at all.** [IssuedPasswordLinkPanel] is
 *    the surface for that, mounted by the user-management card in `MainActivity`.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE GATE IS A `when` ARM AND NOT A DIALOG, FOR `UsageConsentGateScreen`'S REASON
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A dialog is dismissible and "set a password if you feel like it" is not the requirement. Read that
 * screen's header before changing the shape of this one: it argues at length why the enforcement
 * belongs between sign-in and the dashboard rather than behind a tap, and why the escape hatch
 * ("Sign out instead") is not a hedge — a person who genuinely cannot complete the step must not be
 * held on one screen whose controls all do nothing, and signing out returns them to a door they can
 * use with a different account without letting anybody INTO the product.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * SINCE 2026-10-09 THE SERVER REFUSES BEHIND THE GATE, AND THIS APP WAITS RATHER THAN FIGHTS IT
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * While the flag is set, every route outside a short allow-list answers 401 with
 * `X-Password-Change-Required` — `data/PasswordChangeRequired.kt` has the list, and why it is a 401.
 * On this side that means three things:
 *
 *  * **The session is kept.** A gated 401 is a live session owing a password, not a dead one, so
 *    nothing signs out on it; a gated answer to ANY request re-reads the profile and puts the gate
 *    up, which is how a flag this handset had not heard about still reaches the screen.
 *  * **The background sends wait.** The outbox loop, the design-workshop pass and the join-card
 *    flusher hold back while the flag is set and start again the moment it clears. Nothing queued is
 *    touched: a 401 is "try again later" to every queue in this app.
 *  * **Builds 0.0.2 to 0.0.5 have no gate screen,** so their users change the password on the web
 *    first. That cost was accepted when the server started refusing.
 *
 * And one thing the same day changed about the change itself: it retires the session it was sent
 * with. So a change whose ANSWER is lost may well have landed, and a retry of it meets "This session
 * is no longer valid". The gate therefore asks before it speaks — `GET /me` with the session it
 * holds — and never says "nothing has changed" on the strength of a missing answer alone; see
 * [passwordGateAfterFailure]. The change is also sent once and only once: `ApiClient` marks its body
 * one-shot, so OkHttp cannot quietly send a second copy after the first one landed.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 * THE LINK IS SHOWN ONCE. THIS FILE MUST NOT MAKE IT TWICE.
 * ══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `POST /auth/password-links` returns the LINK and deliberately no separate `token` field, on the
 * canonical route's stated grounds that a credential appearing twice in one answer is a credential in
 * two places to keep out of logs. There is no route that re-reads one. So [IssuedPasswordLinkPanel]
 * holds it in composable state and nowhere else — not in `TokenStore`, not in a preference, not in a
 * log line — and says on screen that closing the panel loses it. That sentence is not decoration: an
 * administrator who dismisses without copying has to issue another, and the throttle is per SUBJECT,
 * so doing that four times in an hour locks the person they are trying to help out of being helped.
 */

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 1. The gate between sign-in and the dashboard
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * BETWEEN SIGN-IN AND THE PRODUCT, while this account owes a new password.
 *
 * ── THE CURRENT PASSWORD IS CARRIED FORWARD, NOT ASKED FOR TWICE ─────────────────────────────────
 *
 * `POST /auth/change-password` requires it even for an account carrying the flag, and the server is
 * right to insist: the flag means "the password you hold must be replaced", not "anybody holding
 * this handset may replace it". On the ordinary path the person typed it into the sign-in card
 * seconds ago, so [doorPassword] hands it over and the box never appears. It IS asked for where this
 * app is not holding one — a Google sign-in, a session that was already open when the app was
 * launched or when the gate arrived — because the alternative is a gate whose only button cannot
 * succeed; and it appears after the server has refused the carried one, for the same reason (see
 * [passwordGateAsksForCurrentAfter]).
 *
 * ── AND IT NEVER READS THE PASSWORD BACK OUT OF ANYWHERE ─────────────────────────────────────────
 *
 * [doorPassword] is a parameter and dies with the composable. `TokenStore` holds a JWT and a profile
 * and has never held a password; nothing here starts. A handset in this fleet is shared, and a
 * credential that outlived the screen would be one the next person could reach.
 */
@Composable
fun PasswordGateScreen(
    repository: FieldRepository,
    user: UserDto,
    /** The password typed at the door this session, or "" where this session never saw one. */
    doorPassword: String,
    onSatisfied: (UserDto) -> Unit,
    onSignOut: () -> Unit,
    /**
     * The held session has been refused after a change whose outcome was not known: sign out, and put
     * the sentence handed over — that the new password may already be the one to use — on the sign-in
     * card. Separate from [onSignOut], which is the person's own choice and says nothing.
     */
    onSessionEnded: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read here rather than passed down, for [UsageConsentGateScreen]'s reason: this screen's own
    // retry does not recompose the host, so a value handed in would go on telling somebody who has
    // just walked outside that their phone has no connection.
    val online = ConnectivityObserver.isOnline(context)
    var current by remember { mutableStateOf(doorPassword) }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    // Per-composition only and deliberately not persisted anywhere — see ui/PasswordReveal.kt for
    // why a reveal that survived the screen being reopened is a hazard on a shared handset.
    var reveal by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Set once the server has refused a change sent with the carried password: from then on that
    // password is suspect and the person must be able to see and correct it. See
    // `passwordGateAsksForCurrentAfter`. Never unset — a box that came and went would be worse.
    var carriedRefused by remember { mutableStateOf(false) }
    // Computed from the ARGUMENT and the refusal, and not from `current`, which the person is about
    // to type into: reading the state would make the box vanish under the caret on the first keystroke.
    val askCurrent = doorPassword.isBlank() || carriedRefused

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // The offline / outbox banners float over the foot of the gate too (BannerClearance.kt).
            .padding(top = 8.dp, bottom = 8.dp + (LocalBannerClearance.current?.value ?: 0.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            PASSWORD_GATE_HEADING,
            display = true,
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        // TERSE, and neutral about WHY: see `PASSWORD_GATE_SENTENCE` for the reasons this no longer
        // says an administrator chose the password. The owner's instruction of 2026-08-30 is that
        // reasoning belongs in comments, not on screen.
        Text(
            PASSWORD_GATE_SENTENCE,
            color = MaterialTheme.field.muted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )

        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (askCurrent) {
                    OutlinedTextField(
                        value = current,
                        onValueChange = { current = it },
                        label = { Text("Current password") },
                        singleLine = true,
                        visualTransformation = passwordTransformation(reveal),
                        trailingIcon = {
                            PasswordRevealIcon(revealed = reveal, onToggle = { reveal = !reveal })
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = next,
                    onValueChange = { next = it },
                    label = { Text("New password") },
                    singleLine = true,
                    visualTransformation = passwordTransformation(reveal),
                    // ONE TOGGLE FOR EVERY BOX ON THIS FORM, and it is on the box a person types
                    // FIRST. The pair below is typed in sequence by one person checking one password
                    // against itself; two independent eyes would let them be revealed separately,
                    // which is the one arrangement in which "they do not match" is still a mystery.
                    // `/set-password` on the web made the identical ruling in its own words.
                    trailingIcon = {
                        PasswordRevealIcon(revealed = reveal, onToggle = { reveal = !reveal })
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("Repeat password") },
                    singleLine = true,
                    visualTransformation = passwordTransformation(reveal),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    // The link clause is deliberately absent: nobody on this screen is holding one.
                    // The second clause is the change's own consequence, said before it happens — see
                    // `PASSWORD_CHANGE_SESSIONS`. Until 2026-10-09 it promised the opposite, which was
                    // true of the server then.
                    passwordRuleLine(PASSWORD_CHANGE_SESSIONS),
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp
                )
                error?.let {
                    Text(
                        it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
                Button(
                    enabled = !saving && next.length >= MIN_PASSWORD_LENGTH && confirm.isNotBlank() &&
                        (!askCurrent || current.isNotBlank()),
                    onClick = {
                        // CHECKED HERE AND NOT ONLY BY THE SERVER: the pair, which the server never
                        // sees, and a new password equal to the one being replaced, which this
                        // screen can see before spending a request on it. See `newPasswordRefusal`.
                        val localRefusal =
                            newPasswordRefusal(next = next, confirm = confirm, current = current)
                        if (localRefusal != null) {
                            error = localRefusal
                            return@Button
                        }
                        scope.launch {
                            saving = true
                            error = null
                            runCatching { repository.changeOwnPassword(current, next) }
                                .onSuccess {
                                    /*
                                      THE GATE CLOSES ON THE SERVER'S ANSWER WHERE THERE IS ONE, AND
                                      ON A LOCAL CLEAR WHERE THERE IS NOT — and the second half is
                                      what stops this being a lockout.

                                      `changeOwnPassword` refreshes the cached profile as part of
                                      the call, best-effort. If that `/me` failed (a dropped
                                      connection between two requests, which on this fleet is an
                                      ordinary event) the profile is the PRE-CHANGE row, with the flag
                                      still set — so handing it straight to `onSatisfied` would put
                                      the person back on this screen and ask them for a password they
                                      had just set, using a "current password" that no longer works.
                                      The write has landed by this point; the flag is the only thing
                                      that has not caught up, and clearing it is the honest reading of
                                      "the server accepted this". The repository now clears it in the
                                      STORE as well, so a cold start agrees; this copy is what still
                                      answers if the store was emptied in between. (The session is
                                      not this screen's to carry over: the repository has already
                                      adopted the fresh token the answer brought.)
                                    */
                                    val refreshed = repository.cachedUser()
                                    onSatisfied(
                                        if (refreshed != null && refreshed.mustChangePassword != true) refreshed
                                        else (refreshed ?: user).copy(mustChangePassword = false)
                                    )
                                }
                                .onFailure { failure ->
                                    // The STATUS first: reading the sentence below consumes the
                                    // buffered body, and the status decides whether the carried
                                    // password is now suspect enough to put its box on screen.
                                    val status = (failure as? HttpException)?.code()
                                    if (!askCurrent && passwordGateAsksForCurrentAfter(status)) {
                                        carriedRefused = true
                                        // Emptied, not shown: if it is the wrong password, a box
                                        // holding it as dots is one more thing to clear by hand.
                                        current = ""
                                    }
                                    // THE SERVER'S OWN SENTENCE WHERE THERE IS ONE. It is the only
                                    // text that knows which of four things happened — the current
                                    // password was wrong, the new one is the same as it, this account
                                    // has no password to change at all (told to ask for a link
                                    // instead), or the new one was refused — and those are four
                                    // different next moves behind one family of status codes.
                                    //
                                    // AND WHERE THE FAILURE SETTLES NOTHING — no answer, a 5xx, a
                                    // plain 401 — `GET /me` is asked first, with the session this
                                    // screen still holds, because the change may have landed and
                                    // retired it. `saving` stays up while it is asked, so the button
                                    // cannot send a second change into the same question. The whole
                                    // decision is `passwordGateAfterFailure`, pinned by
                                    // `ChangePasswordSessionTest`; this only applies it.
                                    val outcome = passwordGateAfterFailure(failure, online) {
                                        repository.refreshUser()
                                    }
                                    when (outcome) {
                                        is PasswordGateAfterFailure.Stay -> error = outcome.message
                                        is PasswordGateAfterFailure.SignOut -> onSessionEnded(outcome.message)
                                        is PasswordGateAfterFailure.Satisfied -> onSatisfied(outcome.profile)
                                    }
                                }
                            saving = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (saving) "Saving…" else "Set password and continue")
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        // The escape. See this file's header, and `UsageConsentGateScreen`, for why it is here and
        // why it is not a way past the gate.
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out instead")
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 2. Redeeming an administrator's link, on the handset
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * THE SCREEN AT THE END OF AN ADMINISTRATOR'S PASSWORD LINK, for somebody who cannot sign in.
 *
 * ── IT IS REACHED FROM THE SIGN-IN CARD, WHICH IS THE ONLY PLACE IT CAN BE ───────────────────────
 *
 * The whole point of holding a link is that you cannot sign in, so this cannot live behind the
 * navigation drawer or anywhere else inside the app. `MainActivity` renders it INSTEAD OF
 * `LoginScreen` while it is open, and the way back is a button on it.
 *
 * ── THE LINK IS PASTED, OR NOW ALSO TAPPED, AND THE BOX IS THE SAME EITHER WAY ───────────────────
 *
 * See [passwordLinkToken]. A link tapped in a chat app used to open a browser only — which works,
 * and is a detour for somebody standing in a courtyard with this app already open. `AndroidManifest`
 * now offers this app for those links as well, and a tap arrives here through [initialLink] with the
 * whole URL already in the box, so the check fires without anybody copying anything.
 *
 * THE PASTE BOX IS NOT SUPERSEDED BY THAT AND MUST NOT BE REMOVED. The filter is a plain one and
 * not a verified App Link (its comment in the manifest says what a verified one would owe), so it
 * offers a CHOOSER rather than taking the link; it names one host, so a deployment serving the web
 * app from anywhere else never matches it at all; and a link read out over a telephone or copied off
 * a note was never a tap in the first place. Every one of those still ends at this box.
 *
 * ── A FAILED CHECK IS NOT A DEAD LINK ────────────────────────────────────────────────────────────
 *
 * The link check is asked first — `POST /auth/set-password/check`, with the token in its body, or
 * the older `GET /auth/set-password` on a server without that route (see
 * `FieldRepository.checkPasswordLink`) — because its six reason words are the only way to say
 * WHICH refusal this is — expired, withdrawn, already used — and "invalid link" leaves a person with
 * no next action. But a check that could not be MADE says nothing about the link: the phone may
 * simply have no signal, and telling somebody their link is dead when it has not been examined sends
 * them back to an administrator for nothing. So a thrown check is treated as unknown, the form is
 * offered, and the POST is the authority either way. The web screen takes the identical position.
 *
 * ── AND THE SERVER DELIBERATELY DOES NOT SAY WHOSE ACCOUNT IT IS ─────────────────────────────────
 *
 * No email, no name, no role, on either route. This is reachable by anybody with a guess, and a body
 * that named the account would turn a forged-token probe into an account lookup. Nothing on this
 * screen may print an identity, because there is none to print.
 */
@Composable
fun SetPasswordLinkScreen(
    repository: FieldRepository,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    /**
     * The link this screen was opened WITH, or blank when it was opened from the sign-in card's own
     * button. Seeded once into the editable box rather than held as the value, because the box stays
     * a box: somebody who arrived by tapping a stale link must be able to paste the newer one over it
     * without leaving the screen.
     */
    initialLink: String = "",
) {
    val scope = rememberCoroutineScope()
    // Keyed on the link it was opened with, so a SECOND link tapped while this screen is up replaces
    // the first in the box — `MainActivity` hands it over as a new [initialLink] — instead of being
    // dropped behind a box that still holds the stale one. A paste does not change [initialLink], so
    // what somebody types or pastes here is never overwritten.
    var pasted by remember(initialLink) { mutableStateOf(initialLink) }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    /** The server's verdict, or null for "not asked yet / could not ask". */
    var refusal by remember { mutableStateOf<String?>(null) }
    var purpose by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    val token = passwordLinkToken(pasted)

    // ASKED WHEN THE PASTE SETTLES, not on every keystroke: the box is filled by one paste in
    // practice, and a request per character would spend a village connection on a route that answers
    // the same thing six times. `LaunchedEffect` keyed on the TOKEN and not on the raw text, so
    // trimming a trailing space does not re-ask.
    LaunchedEffect(token) {
        refusal = null
        purpose = null
        if (token.isBlank()) return@LaunchedEffect
        checking = true
        runCatching { repository.checkPasswordLink(token) }
            .onSuccess { verdict ->
                purpose = verdict.purpose
                refusal = if (verdict.valid) null else passwordLinkRefusal(verdict.reason)
            }
            // Deliberately silent: see this screen's header. An unexamined link is not a dead one.
            .onFailure { refusal = null }
        checking = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Set your password",
            display = true,
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (done) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // BOTH HALVES OF WHAT THE REDEMPTION DID, and the second is not a nicety: it
                    // writes `User.sessionsValidFrom`, so any other device signed in to this account
                    // is signed out from this instant. Somebody who discovers that later, with no
                    // explanation, reasonably concludes something is broken.
                    Text(
                        "Your password is set. Any other device signed in to this account has been " +
                            "signed out.",
                        color = MaterialTheme.field.muted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Go to sign in")
                    }
                }
            }
            return@Column
        }

        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = { Text("Paste the link an administrator sent you") },
                    // `KeyboardType.Uri` rather than Text: it puts "/" and "." on the primary layout
                    // and, more importantly, is the one type for which system keyboards do not offer
                    // autocorrect — a token is base64url and a corrected character breaks its HMAC.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                if (checking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Checking this link…", color = MaterialTheme.field.muted, fontSize = 13.sp)
                    }
                }
                refusal?.let {
                    Text(
                        it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
                if (token.isNotBlank() && refusal == null && !checking) {
                    Text(
                        passwordLinkPurposeLine(purpose),
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                }

                // THE BOXES ARE DRAWN ONLY ONCE A LINK IS ON SCREEN AND HAS NOT BEEN REFUSED.
                // Offering somebody a password box before there is anything to redeem it against is
                // asking them to choose a secret that has nowhere to go.
                if (token.isNotBlank() && refusal == null) {
                    HorizontalDivider()
                    OutlinedTextField(
                        value = next,
                        onValueChange = { next = it },
                        label = { Text("New password") },
                        singleLine = true,
                        visualTransformation = passwordTransformation(reveal),
                        trailingIcon = {
                            PasswordRevealIcon(revealed = reveal, onToggle = { reveal = !reveal })
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Repeat password") },
                        singleLine = true,
                        visualTransformation = passwordTransformation(reveal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        passwordRuleLine("This link works once."),
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp
                    )
                    error?.let {
                        Text(
                            it,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    Button(
                        enabled = !saving && next.length >= MIN_PASSWORD_LENGTH && confirm.isNotBlank(),
                        onClick = {
                            // The gate's own check, with no current password to compare against.
                            val localRefusal = newPasswordRefusal(next = next, confirm = confirm)
                            if (localRefusal != null) {
                                error = localRefusal
                                return@Button
                            }
                            scope.launch {
                                saving = true
                                error = null
                                runCatching { repository.setPasswordWithLink(token, next) }
                                    .onSuccess { done = true }
                                    .onFailure { failure ->
                                        // THE SERVER'S OWN SENTENCE, and on this route it has one for
                                        // every refusal (`_SET_PASSWORD_REFUSALS`). It is also the
                                        // only party that can say a link went stale between the check
                                        // above and this POST — somebody else redeemed it, an admin
                                        // withdrew it — so composing our own here would contradict it.
                                        error = failure.apiErrorMessage(
                                            "Your password could not be set. Check your connection " +
                                                "and try again."
                                        )
                                    }
                                saving = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (saving) "Saving…" else "Set password")
                    }
                }
            }
        }

        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Back to sign in")
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════════════════════════
 * 3. The administrator's side: minting one, and handing it over
 * ══════════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * A link an administrator has just minted, held on screen until they dismiss it.
 *
 * ── EVERY DECISION HERE IS ABOUT THE FACT THAT IT IS SHOWN ONCE ──────────────────────────────────
 *
 * There is no route that returns an issued link a second time, and there is deliberately no `token`
 * field beside it. So this panel is the only place it will ever exist outside the person's hands:
 *
 *  * **It is held in composable state and written nowhere else.** Not `TokenStore`, not a
 *    preference, not a log line. `logcat` on a shared handset is not a private place.
 *  * **Copy puts it on the clipboard and SAYS SO, and a copy that failed says nothing.** A "Copied"
 *    that did not copy is the one outcome that loses the credential — the administrator closes the
 *    panel believing they have it. The web panel makes the same call in its own comment.
 *  * **The link is also on screen and selectable**, so a clipboard the platform refuses is not the
 *    end of the road.
 *  * **Withdraw is offered beside Copy**, because "I pasted that into the wrong window" is the case
 *    the `PasswordResetToken` table exists for: the credential fingerprint cannot answer it, since
 *    the account's password has not changed and the token still verifies.
 *
 * ── AND THE ONE SENTENCE THAT IS NOT DECORATION ──────────────────────────────────────────────────
 *
 * "Copy it now" — because dismissing without copying means issuing another, and the throttle is per
 * SUBJECT and not per admin (four an hour). An administrator who burns the budget by tidying up has
 * locked the person they were helping out of being helped for an hour.
 */
@Composable
fun IssuedPasswordLinkPanel(
    link: IssuedPasswordLinkDto,
    subject: String,
    /**
     * When it stops working, already in words a person reads.
     *
     * FORMATTED BY THE CALLER, because `formatIsoDate` lives in `MainActivity.kt` and is private
     * there — and because the alternative is worse than the indirection: printing
     * `link.expiresAt` raw puts "2026-09-02T14:12:00.000Z" on the one panel an administrator has to
     * act on quickly, and a stamp nobody can read at a glance is a stamp nobody checks.
     */
    expires: String,
    onWithdraw: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.warningContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Password link for $subject",
                display = true,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 14.sp
            )
            // READ-ONLY AND SELECTABLE rather than a `Text`, so the value can be picked up by hand
            // where the clipboard is refused. `readOnly` and not `enabled = false`: a disabled field
            // is not selectable either, which would take away the fallback this box IS.
            OutlinedTextField(
                value = link.link,
                onValueChange = {},
                readOnly = true,
                label = { Text("Link") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                passwordLinkPurposeLine(link.purpose) + " Expires " + expires + ".",
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Text(
                // TERSE, but this sentence earns its place: nothing can show this link again.
                "Copy it now and send it yourself — it is shown once and works once.",
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        // SAY NOTHING RATHER THAN CLAIM SUCCESS. Clipboard access can fail outright
                        // on a managed device, and a "Copied" that did not copy is the one outcome
                        // that loses the credential.
                        copied = runCatching {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Password link", link.link))
                        }.isSuccess
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (copied) "Copied" else "Copy", maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
                OutlinedButton(
                    // NO LOCAL BUSY FLAG. The withdrawal is a network call the CALLER owns and
                    // launches; a `busy` set and cleared around a plain callback here would be
                    // theatre — flipped back in the same frame, telling nobody anything. The caller
                    // disables the row's issue button while its own request is in flight.
                    onClick = onWithdraw,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Withdraw", maxLines = 1, softWrap = false, fontSize = 13.sp)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = RectangleShape) {
                Text("Done", fontSize = 12.sp)
            }
        }
    }
}
