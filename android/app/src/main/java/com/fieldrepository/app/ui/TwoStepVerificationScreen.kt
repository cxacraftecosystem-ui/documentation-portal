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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.OtpauthLink
import com.fieldrepository.app.data.QrSquare
import com.fieldrepository.app.data.TWO_STEP_CHALLENGE_ENDED
import com.fieldrepository.app.data.TwoFactorSetupDto
import com.fieldrepository.app.data.TwoFactorStatusDto
import com.fieldrepository.app.data.TwoStepSwitch
import com.fieldrepository.app.data.UserDto
import com.fieldrepository.app.data.apiFailure
import com.fieldrepository.app.data.recoveryCodesShareText
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import retrofit2.HttpException

/*
 * TWO-STEP VERIFICATION on the handset — the twin of the web's `TwoStepChallengeForm` (the code step
 * of a sign-in) and `TwoStepVerificationCard` (the switch). Same words, same steps.
 *
 * Owner's ruling (2026-10-17), the same on both clients:
 *   - It is ONE switch ([AppToggle]) labelled "Two-step verification", never a bare "Off" line.
 *   - Off → switch on → setup opens (QR, key, the six code boxes). The switch reads "Setting up…" and
 *     stays OFF until the server has VERIFIED a code. Only then is it On.
 *   - Leaving setup without a verified code DISCARDS it (`POST /auth/two-factor/setup/cancel`):
 *     Cancel, Back, leaving the screen, or coming back after more than [TwoStepSwitch.AWAY_LIMIT_MILLIS]
 *     in the background. A SHORT trip to the authenticator app on this phone — which is how the code
 *     is read — does not cancel it. The server also drops a setup older than 15 minutes, and a setup
 *     found left over from an earlier visit reads Off and is cleared, never On.
 *   - On → switch off → a current code (six boxes) or, behind "Use a recovery code instead", a
 *     recovery code; then it is off.
 *
 * The phone is usually the device with the authenticator on it, so setup here also offers "Open in
 * authenticator app" (the otpauth link handed to the system) beside "Copy key".
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
    var errorNonce by remember { mutableIntStateOf(0) }
    /** The challenge is finished (five wrong codes, or five minutes); only signing in again leads anywhere. */
    var dead by remember { mutableStateOf(false) }

    fun send(typed: String) {
        val value = typed.trim()
        if (busy || dead) return
        if (if (useRecovery) value.isBlank() else !OtpCode.isComplete(value)) return
        scope.launch {
            busy = true
            error = null
            runCatching { repository.verifyTwoFactor(challengeToken, value) }
                .onSuccess(onSignedIn)
                .onFailure {
                    code = ""
                    errorNonce += 1
                    val message = it.apiFailure("Couldn't connect. Check your connection and try again.").message
                    if (message == TWO_STEP_CHALLENGE_ENDED) dead = true
                    error = message
                }
            busy = false
        }
    }

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
                if (!error.isNullOrBlank()) {
                    Text(
                        error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                }
                if (dead) {
                    Button(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Sign in again") }
                } else {
                    if (useRecovery) {
                        RecoveryCodeBox(code) { code = it }
                    } else {
                        OtpCodeField(
                            value = code,
                            onValueChange = { code = it },
                            onComplete = ::send,
                            error = error != null,
                            errorNonce = errorNonce,
                            enabled = !busy,
                            autoFocus = true
                        )
                    }
                    Button(
                        enabled = !busy && (if (useRecovery) code.isNotBlank() else OtpCode.isComplete(code)),
                        onClick = { send(code) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(if (busy) "Checking…" else "Verify") }
                    AppToggle(
                        label = "Use a recovery code instead",
                        description = "For when your phone is lost or the authenticator app is gone.",
                        checked = useRecovery,
                        onCheckedChange = {
                            useRecovery = it
                            code = ""
                            error = null
                        }
                    )
                }
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to sign in", color = Muted) }
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

/** "17 Oct 2026" from the server's ISO instant, in this phone's zone. */
private fun twoStepDate(iso: String): String {
    val instant = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(iso) }.getOrNull()
        ?: return iso.take(10)
    return DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(instant.atZone(ZoneId.systemDefault()))
}

/** Fire-and-forget: the screen that asked may already be gone, so this must outlive its scope. */
private fun discardSetup(repository: FieldRepository) {
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { repository.cancelTwoFactorSetup() } }
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
    var useRecovery by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorNonce by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var backgroundedAt by remember { mutableLongStateOf(0L) }

    val settingUp = step is TwoStepStep.Setup
    val view = TwoStepSwitch.view(status, settingUp)
    val settingUpNow by rememberUpdatedState(settingUp)

    suspend fun reload() {
        runCatching { repository.twoFactorStatus() }
            .onSuccess { status = it }
            .onFailure { error = it.apiFailure("Couldn't load this setting. Try again.").message }
    }

    fun go(next: TwoStepStep) {
        step = next
        code = ""
        useRecovery = false
        error = null
        saved = false
    }

    LaunchedEffect(Unit) {
        runCatching { repository.twoFactorStatus() }
            .onSuccess { answer ->
                // A setup somebody started on an earlier visit and never finished: it is Off, so
                // forget it rather than leave a half-made secret on the account.
                status = if (!answer.enabled && answer.pending) {
                    runCatching { repository.cancelTwoFactorSetup() }
                    answer.copy(pending = false)
                } else {
                    answer
                }
            }
            .onFailure { error = it.apiFailure("Couldn't load this setting. Try again.").message }
    }

    // Away too long with a setup open → discard it. Leaving the screen with one open → discard it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundedAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    val away = if (backgroundedAt == 0L) 0L else System.currentTimeMillis() - backgroundedAt
                    backgroundedAt = 0L
                    if (TwoStepSwitch.abandonedAfterAway(settingUpNow, away)) {
                        discardSetup(repository)
                        go(TwoStepStep.Idle)
                        error = "Setup was stopped because you were away. Turn it on again to get a new code to scan."
                        scope.launch { reload() }
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (settingUpNow) discardSetup(repository)
        }
    }

    fun run(work: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            notice = null
            runCatching { work() }.onFailure {
                code = ""
                errorNonce += 1
                error = it.apiFailure("Couldn't connect. Check your connection and try again.").message
                // The server already dropped this setup (older than 15 minutes): back to Off.
                if (it is HttpException && it.code() == 409 && step is TwoStepStep.Setup) {
                    step = TwoStepStep.Idle
                    reload()
                }
            }
            busy = false
        }
    }

    fun cancelSetup() {
        go(TwoStepStep.Idle)
        scope.launch {
            runCatching { repository.cancelTwoFactorSetup() }
            reload()
        }
    }

    fun submit(typed: String) {
        if (busy) return
        when (val now = step) {
            is TwoStepStep.Setup -> {
                if (!OtpCode.isComplete(typed)) return
                run {
                    val codes = repository.enableTwoFactor(typed)
                    go(TwoStepStep.Codes(codes))
                    reload()
                }
            }
            is TwoStepStep.Confirm -> {
                if (now.disable && useRecovery) {
                    if (typed.isBlank()) return
                } else if (!OtpCode.isComplete(typed)) return
                run {
                    if (now.disable) {
                        repository.disableTwoFactor(typed)
                        go(TwoStepStep.Idle)
                        notice = "Two-step verification is off"
                    } else {
                        go(TwoStepStep.Codes(repository.makeNewRecoveryCodes(typed)))
                    }
                    reload()
                }
            }
            else -> Unit
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
            if (!error.isNullOrBlank()) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
            if (!notice.isNullOrBlank()) Text(notice!!, color = MaterialTheme.field.success)

            val current = status
            if (current == null) {
                if (error == null) CircularProgressIndicator()
                return@Column
            }

            AppToggle(
                label = "Two-step verification",
                description = TwoStepSwitch.statusLine(current, view, ::twoStepDate),
                checked = view == TwoStepSwitch.View.ON,
                busy = view == TwoStepSwitch.View.SETTING_UP || busy,
                enabled = step !is TwoStepStep.Codes && step !is TwoStepStep.Confirm,
                onCheckedChange = { next ->
                    if (next && view == TwoStepSwitch.View.OFF) {
                        run { go(TwoStepStep.Setup(repository.beginTwoFactorSetup())) }
                    } else if (!next && view == TwoStepSwitch.View.ON) {
                        go(TwoStepStep.Confirm(disable = true))
                    }
                }
            )
            HorizontalDivider(color = MaterialTheme.field.hairline)

            when (val now = step) {
                is TwoStepStep.Setup -> {
                    Text("1. Scan this code with an authenticator app, or open it in the app on this phone.", color = Body)
                    QrSquareImage(now.setup.qrSvg)
                    if (OtpauthLink.isOpenable(now.setup.otpauthUri)) {
                        Button(
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(now.setup.otpauthUri)))
                                } catch (_: ActivityNotFoundException) {
                                    error = "No authenticator app on this phone. Install one, or copy the key into it."
                                }
                            }
                        ) { Text("Open in authenticator app") }
                    }
                    Text(now.setup.secret, fontFamily = FontFamily.Monospace)
                    OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                        clipboard.setText(AnnotatedString(now.setup.secret))
                        notice = "Key copied"
                    }) { Text("Copy key") }
                    Text(
                        "2. Enter the 6-digit code the app shows. Two-step verification turns on once the code is checked.",
                        color = Body
                    )
                    OtpCodeField(
                        value = code,
                        onValueChange = { code = it },
                        onComplete = ::submit,
                        error = error != null,
                        errorNonce = errorNonce,
                        enabled = !busy
                    )
                    Text(
                        "Codes change every 30 seconds. This setup is dropped if it isn't finished within 15 minutes.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.heightIn(min = 48.dp),
                            enabled = !busy && OtpCode.isComplete(code),
                            onClick = { submit(code) }
                        ) { Text(if (busy) "Checking…" else "Turn on") }
                        OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy, onClick = ::cancelSetup) {
                            Text("Cancel")
                        }
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
                        OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                            clipboard.setText(AnnotatedString(text))
                            saved = true
                            notice = "Recovery codes copied"
                        }) { Text("Copy") }
                        OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                            context.startActivity(Intent.createChooser(send, "Save recovery codes"))
                            saved = true
                        }) { Text("Share") }
                        Button(modifier = Modifier.heightIn(min = 48.dp), enabled = saved, onClick = { go(TwoStepStep.Idle) }) {
                            Text("Done")
                        }
                    }
                    if (!saved) Text("Copy or share the codes to finish.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                is TwoStepStep.Confirm -> {
                    Text(
                        if (now.disable) "Turn off two-step verification" else "Make new recovery codes",
                        fontWeight = FontWeight.SemiBold
                    )
                    if (now.disable && useRecovery) {
                        RecoveryCodeBox(code) { code = it }
                    } else {
                        OtpCodeField(
                            value = code,
                            onValueChange = { code = it },
                            onComplete = ::submit,
                            error = error != null,
                            errorNonce = errorNonce,
                            enabled = !busy,
                            autoFocus = true
                        )
                    }
                    Text(
                        when {
                            now.disable && useRecovery -> "Each recovery code works once."
                            now.disable -> "Enter the 6-digit code from your authenticator app."
                            else -> "Enter the 6-digit code from your authenticator app. Your old recovery codes stop " +
                                "working as soon as the new ones are made."
                        },
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (now.disable) {
                        AppToggle(
                            label = "Use a recovery code instead",
                            description = "For when your phone is lost or the authenticator app is gone.",
                            checked = useRecovery,
                            onCheckedChange = {
                                useRecovery = it
                                code = ""
                                error = null
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.heightIn(min = 48.dp),
                            enabled = !busy && (if (now.disable && useRecovery) code.isNotBlank() else OtpCode.isComplete(code)),
                            onClick = { submit(code) }
                        ) { Text(if (busy) "Checking…" else if (now.disable) "Turn off" else "Make new codes") }
                        OutlinedButton(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy, onClick = { go(TwoStepStep.Idle) }) {
                            Text("Cancel")
                        }
                    }
                }
                TwoStepStep.Idle -> if (view == TwoStepSwitch.View.ON) {
                    OutlinedButton(
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { go(TwoStepStep.Confirm(disable = false)) }
                    ) { Text("Make new recovery codes") }
                }
            }
        }
    }
}

/** A recovery code (`xxxx-xxxx`): its own text field, never the six digit boxes. */
@Composable
internal fun RecoveryCodeBox(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(20)) },
        label = { Text("Recovery code") },
        placeholder = { Text("xxxx-xxxx", color = MaterialTheme.field.placeholder) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
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
