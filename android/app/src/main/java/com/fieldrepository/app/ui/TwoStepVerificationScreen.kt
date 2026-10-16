package com.fieldrepository.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.OtpauthLink
import com.fieldrepository.app.data.QrSquare
import com.fieldrepository.app.data.TWO_STEP_CHALLENGE_ENDED
import com.fieldrepository.app.data.TwoFactorSetupDto
import com.fieldrepository.app.data.TwoFactorStatusDto
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.apiFailure
import com.fieldrepository.app.data.recoveryCodesShareText
import kotlinx.coroutines.launch

/*
 * TWO-STEP VERIFICATION on the handset — the twin of the web's `TwoStepChallengeForm` (the code step
 * of a sign-in) and `TwoStepVerificationCard` (turning it on and off). Same words, same steps.
 *
 * The phone is usually the device with the authenticator on it, so setup here also offers "Open in
 * authenticator app" (the otpauth link handed to the system) beside "Copy key"; scanning a QR code on
 * the phone's own screen is the one thing the phone cannot do.
 */

/**
 * The second step of a sign-in, shown in place of the sign-in card after ANY first factor when the
 * account has two-step verification on. Nobody is signed in until a code is accepted, so "Back to
 * sign in" simply forgets the challenge.
 */
@Composable
fun TwoStepChallengeForm(
    repository: FieldRepository,
    challengeToken: String,
    onSignedIn: (UserDto) -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var useRecovery by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** The challenge is finished (five wrong codes, or five minutes); only signing in again leads anywhere. */
    var dead by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Canvas)) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Two-step verification", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (useRecovery) "Enter one of the recovery codes you saved. Each code works once."
                    else "Enter the 6-digit code from your authenticator app.",
                    color = Body
                )
                if (!error.isNullOrBlank()) Text(error!!, color = MaterialTheme.colorScheme.error)
                if (dead) {
                    Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Sign in again") }
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.take(20) },
                        label = { Text(if (useRecovery) "Recovery code" else "6-digit code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (useRecovery) KeyboardType.Ascii else KeyboardType.NumberPassword
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = !busy && code.isNotBlank(),
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                runCatching { repository.verifyTwoFactor(challengeToken, code) }
                                    .onSuccess(onSignedIn)
                                    .onFailure {
                                        code = ""
                                        val message = it.apiFailure("Couldn't connect. Check your connection and try again.").message
                                        if (message == TWO_STEP_CHALLENGE_ENDED) dead = true
                                        error = message
                                    }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Checking…" else "Verify") }
                    TextButton(onClick = {
                        useRecovery = !useRecovery
                        code = ""
                        error = null
                    }) {
                        Text(if (useRecovery) "Use your authenticator app instead" else "Use a recovery code instead")
                    }
                }
                TextButton(onClick = onCancel) { Text("Back to sign in", color = Muted) }
            }
        }
    }
}

private sealed interface TwoStepStep {
    data object Idle : TwoStepStep
    data class Setup(val setup: TwoFactorSetupDto) : TwoStepStep
    data class Codes(val codes: List<String>) : TwoStepStep
    data class Confirm(val disable: Boolean) : TwoStepStep
}

/** The account's own two-step verification, reached from Settings. Open to every role. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwoStepVerificationScreen(
    repository: FieldRepository,
    email: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var status by remember { mutableStateOf<TwoFactorStatusDto?>(null) }
    var step by remember { mutableStateOf<TwoStepStep>(TwoStepStep.Idle) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    suspend fun reload() {
        runCatching { repository.twoFactorStatus() }
            .onSuccess { status = it }
            .onFailure { error = it.apiFailure("Couldn't load this setting. Try again.").message }
    }

    LaunchedEffect(Unit) { reload() }

    fun go(next: TwoStepStep) {
        step = next
        code = ""
        error = null
        saved = false
    }

    fun run(work: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            notice = null
            runCatching { work() }.onFailure {
                code = ""
                error = it.apiFailure("Couldn't connect. Check your connection and try again.").message
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Two-step verification") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "After your password or Google, Microsoft or Yahoo sign-in, also ask for a 6-digit code from an " +
                    "authenticator app on your phone.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            if (!error.isNullOrBlank()) Text(error!!, color = MaterialTheme.colorScheme.error)
            if (!notice.isNullOrBlank()) Text(notice!!, color = MaterialTheme.field.success)

            val current = status
            if (current == null) {
                if (error == null) FieldCircularProgressIndicator()
            } else when (val now = step) {
                is TwoStepStep.Setup -> {
                    Text("1. Scan this code with an authenticator app, or open it in the app on this phone.", color = Body)
                    QrSquareImage(now.setup.qrSvg)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (OtpauthLink.isOpenable(now.setup.otpauthUri)) {
                            Button(onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(now.setup.otpauthUri)))
                                } catch (_: ActivityNotFoundException) {
                                    error = "No authenticator app on this phone. Install one, or copy the key into it."
                                }
                            }) { Text("Open in authenticator app") }
                        }
                    }
                    Text(now.setup.secret, fontFamily = FontFamily.Monospace)
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(now.setup.secret))
                        notice = "Key copied"
                    }) { Text("Copy key") }
                    Text("2. Enter the 6-digit code the app shows.", color = Body)
                    CodeBox(code, { code = it }, "6-digit code", acceptsRecovery = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && code.isNotBlank(), onClick = {
                            run {
                                val codes = repository.enableTwoFactor(code)
                                go(TwoStepStep.Codes(codes))
                                reload()
                            }
                        }) { Text(if (busy) "Checking…" else "Turn on") }
                        OutlinedButton(enabled = !busy, onClick = { go(TwoStepStep.Idle) }) { Text("Cancel") }
                    }
                }
                is TwoStepStep.Codes -> {
                    Text("Save these codes", fontWeight = FontWeight.SemiBold)
                    Text(
                        "If you lose your phone, each code signs you in once instead of the 6-digit code. Keep them " +
                            "somewhere safe — they won't be shown again.",
                        color = Body
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        now.codes.forEach { Text(it, fontFamily = FontFamily.Monospace) }
                    }
                    val text = recoveryCodesShareText(now.codes, email)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(text))
                            saved = true
                            notice = "Recovery codes copied"
                        }) { Text("Copy") }
                        OutlinedButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                            context.startActivity(Intent.createChooser(send, "Save recovery codes"))
                            saved = true
                        }) { Text("Share") }
                        Button(enabled = saved, onClick = { go(TwoStepStep.Idle) }) { Text("Done") }
                    }
                    if (!saved) Text("Copy or share the codes to finish.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                is TwoStepStep.Confirm -> {
                    CodeBox(
                        code,
                        { code = it },
                        if (now.disable) "Code" else "6-digit code",
                        acceptsRecovery = now.disable
                    )
                    Text(
                        if (now.disable) "Enter the 6-digit code from your authenticator app, or one of your recovery codes."
                        else "Your old recovery codes stop working as soon as the new ones are made.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && code.isNotBlank(), onClick = {
                            run {
                                if (now.disable) {
                                    repository.disableTwoFactor(code)
                                    go(TwoStepStep.Idle)
                                    notice = "Two-step verification is off"
                                } else {
                                    go(TwoStepStep.Codes(repository.makeNewRecoveryCodes(code)))
                                }
                                reload()
                            }
                        }) { Text(if (busy) "Checking…" else if (now.disable) "Turn off" else "Make new codes") }
                        OutlinedButton(enabled = !busy, onClick = { go(TwoStepStep.Idle) }) { Text("Cancel") }
                    }
                }
                TwoStepStep.Idle -> if (current.enabled) {
                    Text("On", color = MaterialTheme.field.success, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (current.recoveryCodesRemaining == 1) "1 recovery code left."
                        else "${current.recoveryCodesRemaining} recovery codes left.",
                        color = Body
                    )
                    OutlinedButton(onClick = { go(TwoStepStep.Confirm(disable = false)) }) { Text("Make new recovery codes") }
                    OutlinedButton(onClick = { go(TwoStepStep.Confirm(disable = true)) }) {
                        Text("Turn off", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text("Off.", color = Body)
                    Button(enabled = !busy, onClick = {
                        run { go(TwoStepStep.Setup(repository.beginTwoFactorSetup())) }
                    }) { Text(if (busy) "Starting…" else "Turn on") }
                }
            }
        }
    }
}

@Composable
private fun CodeBox(value: String, onChange: (String) -> Unit, label: String, acceptsRecovery: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(20)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (acceptsRecovery) KeyboardType.Ascii else KeyboardType.NumberPassword
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** The server's square, drawn black on white whatever the theme, so it scans. */
@Composable
private fun QrSquareImage(svg: String) {
    val drawing = remember(svg) { QrSquare.parse(svg) } ?: return
    val path = remember(drawing) { PathParser().parsePathString(drawing.pathData).toPath() }
    Canvas(
        modifier = Modifier
            .size(220.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .semantics { contentDescription = "QR code to scan with your authenticator app" }
    ) {
        val unit = size.minDimension / drawing.size
        scale(unit, unit, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, Color.Black)
        }
    }
}
