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

@Composable
fun PasswordGateScreen(
    repository: FieldRepository,
    user: UserDto,
    doorPassword: String,
    onSatisfied: (UserDto) -> Unit,
    onSignOut: () -> Unit,
    onSessionEnded: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val online = ConnectivityObserver.isOnline(context)
    var current by remember { mutableStateOf(doorPassword) }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var carriedRefused by remember { mutableStateOf(false) }
    val askCurrent = doorPassword.isBlank() || carriedRefused

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 8.dp + (LocalBannerClearance.current?.value ?: 0.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            PASSWORD_GATE_HEADING,
            display = true,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            PASSWORD_GATE_SENTENCE,
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodyMedium,
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
                    passwordRuleLine(PASSWORD_CHANGE_SESSIONS),
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                error?.let {
                    Text(
                        it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 18.sp
                    )
                }
                Button(
                    enabled = !saving && next.length >= MIN_PASSWORD_LENGTH && confirm.isNotBlank() &&
                        (!askCurrent || current.isNotBlank()),
                    onClick = {
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
                                    val refreshed = repository.cachedUser()
                                    onSatisfied(
                                        if (refreshed != null && refreshed.mustChangePassword != true) refreshed
                                        else (refreshed ?: user).copy(mustChangePassword = false)
                                    )
                                }
                                .onFailure { failure ->
                                    val status = (failure as? HttpException)?.code()
                                    if (!askCurrent && passwordGateAsksForCurrentAfter(status)) {
                                        carriedRefused = true
                                        current = ""
                                    }
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
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out instead")
        }
    }
}

@Composable
fun SetPasswordLinkScreen(
    repository: FieldRepository,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    initialLink: String = "",
) {
    val scope = rememberCoroutineScope()
    var pasted by remember(initialLink) { mutableStateOf(initialLink) }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }
    var purpose by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    val token = passwordLinkToken(pasted)

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
            style = MaterialTheme.typography.headlineSmall,
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
                    Text(
                        "Password set. Other devices were signed out.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodyMedium,
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
                    label = { Text("Paste your password link") },
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
                        Text("Checking this link…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                refusal?.let {
                    Text(
                        it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 18.sp
                    )
                }
                if (token.isNotBlank() && refusal == null && !checking) {
                    Text(
                        passwordLinkPurposeLine(purpose),
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

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
                        style = MaterialTheme.typography.bodySmall
                    )
                    error?.let {
                        Text(
                            it,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 18.sp
                        )
                    }
                    Button(
                        enabled = !saving && next.length >= MIN_PASSWORD_LENGTH && confirm.isNotBlank(),
                        onClick = {
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

@Composable
fun IssuedPasswordLinkPanel(
    link: IssuedPasswordLinkDto,
    subject: String,
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
                style = MaterialTheme.typography.bodyMedium
            )
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
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp
            )
            Text(
                "Shown once — copy it and send it yourself.",
                color = MaterialTheme.field.onWarningContainer,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp
            )
            ActionRow {
                Button(
                    onClick = {
                        copied = runCatching {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Password link", link.link))
                        }.isSuccess
                    },
                ) {
                    Text(if (copied) "Copied" else "Copy", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = onWithdraw,
                ) {
                    Text("Withdraw", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), shape = RectangleShape) {
                Text("Done", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
