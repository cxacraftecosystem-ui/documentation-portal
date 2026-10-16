package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.SignInAgainRequired
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.accountHasPassword
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.launch

/*
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * GETTING BACK IN, ON THE HANDSET
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Three surfaces, the web's three, with the words in ui/AccountRecoveryCopy.kt:
 *
 *   1. [ForgotPasswordScreen] — drawn by `MainActivity` in place of the sign-in card. One box, one
 *      button, and the server's one answer printed as it is: the same for an address with an account
 *      and one without, so nothing on this screen can branch on which it was.
 *   2. [AccountPasswordScreen] — Settings → Password. Changes the password, or sets a first one for
 *      an account that signs in with Google, Microsoft or Yahoo.
 *   3. [HelpThemBackInActions] — on Manage users, beside the existing "Issue a set-password link" and
 *      "Turn off two-step verification": e-mail them a link, set a temporary password, sign them out
 *      of every device.
 *
 * NONE OF THESE MINTS A SESSION. A link or the forgotten-password e-mail sets a password; the person
 * then signs in and meets the two-step code exactly as any sign-in does.
 */

@Composable
fun ForgotPasswordScreen(
    repository: FieldRepository,
    onBack: () -> Unit,
    /** The address already typed on the sign-in card, if any — saves typing it twice. */
    initialEmail: String = "",
) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(initialEmail.trim()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            FORGOT_PASSWORD_HEADING,
            display = true,
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onBackground
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
                Text(FORGOT_PASSWORD_INTRO, color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 19.sp)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    label = { Text("Email address") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                sent?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        val problem = forgotPasswordEmailProblem(email)
                        if (problem != null) {
                            error = problem
                            return@Button
                        }
                        scope.launch {
                            busy = true
                            error = null
                            sent = null
                            runCatching { repository.forgotPassword(email) }
                                .onSuccess { sent = forgotPasswordShown(it) }
                                .onFailure {
                                    error = it.apiErrorMessage("That request did not work. Check the address and try again.")
                                }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Sending…" else "Email me a link")
                }
                Text(FORGOT_PASSWORD_TWO_STEP_NOTE, color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)
                Text(FORGOT_PASSWORD_NO_EMAIL_NOTE, color = MaterialTheme.field.muted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), shape = RectangleShape) {
            Text("Back to sign in", fontSize = 13.sp)
        }
    }
}

/** Settings → Password. Its own TopAppBar, like the two-step screen beside it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPasswordScreen(
    repository: FieldRepository,
    onBack: () -> Unit,
    /** Sign this device out — the way forward when the server asks for a fresh sign-in. */
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Password") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { AccountPasswordCard(repository, onSignOut) }
        }
    }
}

@Composable
fun AccountPasswordCard(repository: FieldRepository, onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(repository.cachedUser()) }
    // Re-read once, so an account that gained a password on the web is not offered "Set a password".
    LaunchedEffect(Unit) {
        runCatching { repository.refreshUser() }.onSuccess { user = it }
    }
    val hasPassword = accountHasPassword(user)
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var signInAgain by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(passwordCardTitle(hasPassword), display = true, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(passwordCardIntro(hasPassword, user), color = MaterialTheme.field.muted, fontSize = 13.sp, lineHeight = 19.sp)
            if (hasPassword) {
                OutlinedTextField(
                    value = current,
                    onValueChange = { current = it; error = null; saved = null },
                    label = { Text("Current password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = passwordTransformation(reveal),
                    trailingIcon = { PasswordRevealIcon(reveal, { reveal = !reveal }) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = next,
                onValueChange = { next = it; error = null; saved = null },
                label = { Text("New password") },
                supportingText = { Text(passwordRuleLine()) },
                singleLine = true,
                enabled = !busy,
                visualTransformation = passwordTransformation(reveal),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it; error = null; saved = null },
                label = { Text("Type it again") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = passwordTransformation(reveal),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            saved?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
            if (signInAgain) {
                OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                    Text(SIGN_IN_AGAIN_ACTION)
                }
            }
            Button(
                enabled = !busy && (!hasPassword || current.isNotEmpty()),
                onClick = {
                    val problem = newPasswordRefusal(next, confirm, if (hasPassword) current else "")
                    if (problem != null) {
                        error = problem
                        return@Button
                    }
                    scope.launch {
                        busy = true
                        error = null
                        signInAgain = false
                        val hadPassword = hasPassword
                        runCatching { repository.saveOwnPassword(if (hadPassword) current else null, next) }
                            .onSuccess {
                                saved = passwordSavedLine(hadPassword)
                                current = ""
                                next = ""
                                confirm = ""
                                user = repository.cachedUser()
                            }
                            .onFailure { failure ->
                                if (failure is SignInAgainRequired) {
                                    error = failure.message
                                    signInAgain = true
                                } else {
                                    error = failure.apiErrorMessage(PASSWORD_CHANGE_NOT_SENT)
                                }
                            }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Saving…" else if (hasPassword) "Change password" else "Set password")
            }
        }
    }
}

/**
 * The three "help them back in" actions this handset lacked, for one row of Manage users. Drawn only
 * when the caller has already decided the administrator may manage [target] — the same test the
 * link and two-step buttons beside it use, which mirrors the server's `assert_may_reset_credentials`.
 *
 * [onChanged] hands back the row as the server now has it (null when nothing came back), so the list
 * can refresh; [onError] carries the server's own sentence.
 */
@Composable
fun HelpThemBackInActions(
    repository: FieldRepository,
    target: UserDto,
    onChanged: (UserDto?) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mailAvailable by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { mailAvailable = repository.mailAvailable() }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var temporaryOpen by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    HorizontalDivider()
    Text(HELP_THEM_BACK_IN_HEADING, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    if (emailLinkOffered(target, mailAvailable)) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    notice = null
                    runCatching { repository.emailPasswordLink(target.id) }
                        .onSuccess { notice = emailedLinkLine(target.name, target.email) }
                        .onFailure { onError(it.apiErrorMessage("Unable to email a password link")) }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Email them a password link") }
    }
    OutlinedButton(
        enabled = !busy,
        onClick = { temporaryOpen = true; notice = null },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Set a temporary password") }
    OutlinedButton(
        enabled = !busy,
        onClick = { confirmSignOut = true; notice = null },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Sign out of every device") }
    notice?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    }

    if (temporaryOpen) {
        TemporaryPasswordDialog(
            name = target.name,
            busy = busy,
            onDismiss = { temporaryOpen = false },
            onSubmit = { password ->
                scope.launch {
                    busy = true
                    runCatching { repository.setTemporaryPassword(target.id, password) }
                        .onSuccess {
                            temporaryOpen = false
                            notice = temporaryPasswordSetLine(target.name)
                            onChanged(it)
                        }
                        .onFailure { onError(it.apiErrorMessage("Unable to set a temporary password")) }
                    busy = false
                }
            }
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmSignOut = false },
            title = { Text(signOutEverywhereTitle(target.name)) },
            text = { Text(signOutEverywhereBody(target.name)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirmSignOut = false
                        scope.launch {
                            busy = true
                            runCatching { repository.signOutEverywhere(target.id) }
                                .onSuccess {
                                    notice = signedOutEverywhereLine(target.name)
                                    onChanged(it)
                                }
                                .onFailure { onError(it.apiErrorMessage("Unable to sign them out")) }
                            busy = false
                        }
                    }
                ) { Text("Sign out everywhere") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmSignOut = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TemporaryPasswordDialog(
    name: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    // Never trimmed, and never echoed anywhere once the dialog closes: a password typed for somebody
    // else is a shared secret, and this box is the one place on the handset it exists.
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Set a temporary password for $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(temporaryPasswordIntro(name), color = MaterialTheme.field.muted, fontSize = 12.sp)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; problem = null },
                    label = { Text("Temporary password") },
                    supportingText = { Text(problem ?: passwordRuleLine()) },
                    isError = problem != null,
                    singleLine = true,
                    visualTransformation = passwordTransformation(reveal),
                    trailingIcon = { PasswordRevealIcon(reveal, { reveal = !reveal }) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val refusal = temporaryPasswordProblem(password)
                    if (refusal != null) problem = refusal else onSubmit(password)
                }
            ) { Text(if (busy) "Saving…" else "Set password") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}
