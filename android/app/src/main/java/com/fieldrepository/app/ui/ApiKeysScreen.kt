package com.fieldrepository.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.FieldRepository
import com.fieldrepository.app.data.ManagedSecretDto
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Two things about the transcription providers, gated at two different heights — the Android mirror
 * of the web's `/settings/api-keys` page, which stacks exactly these halves in exactly this order.
 *
 * THE RANKING ([ProviderOrderPanel], first) is `require_admin`. Deciding whether a Hindi interview
 * goes to ElevenLabs or to Deepgram first is an editorial judgement about transcription accuracy and
 * it belongs to the admins who run the workshops. Nothing about a key's VALUE is exposed by it —
 * only whether each engine has one and what the provider said the last time we asked.
 *
 * THE KEYS (`/secrets`, below) stay MASTER ADMIN ONLY, because handing out live provider credentials
 * (reveal returns plaintext) is a different class of power. The gate is discovered rather than
 * asserted: the API answers 403 for an ordinary admin and [ApiKeysRestrictedCard] takes the list's
 * place, while the ranking above it stays fully live. That is what lets one screen serve both roles.
 *
 * Two rules shape the key half, and both are the reason it is worth having at all:
 *
 * 1. A VALUE IS NEVER ON SCREEN UNTIL IT IS ASKED FOR. `GET /secrets` deliberately carries only a
 *    four-character hint, so the list renders with no credential in the process at all. Plaintext
 *    exists solely behind `GET /secrets/{key}/reveal`, which the server audit-logs against the
 *    account that called it. A revealed value therefore un-reveals itself after
 *    [REVEAL_TIMEOUT_MS] so a key cannot be left glowing on a phone put down on a table, is
 *    dropped the moment the screen leaves the composition, and is never written to a log, a
 *    crash report or an analytics event — there is not one logging call in this file.
 * 2. A SAVED KEY IS LIVE IMMEDIATELY. A write invalidates the server's cache, so the very next
 *    provider call (API *and* the transcription queue) uses it — no restart, no redeploy. The
 *    banner says so out loud rather than leaving the master admin wondering whether to SSH in.
 *
 * The caller gates this screen on master admin, but the API is the real authority: if it answers
 * 403 the screen renders [ApiKeysRestrictedCard] instead of an error, because "you are not allowed"
 * is a state, not a failure. That authority is also what makes the screen safe to open up to plain
 * admins for the ranking's sake — an account that may not read keys still cannot, whatever route
 * brought it here.
 */

/** How long a revealed value stays on screen before it hides itself again. */
private const val REVEAL_TIMEOUT_MS = 30_000L

private const val SOURCE_DATABASE = "database"
private const val SOURCE_ENVIRONMENT = "environment"

/** Which per-row action is in flight, so only that row's buttons go busy. */
enum class ApiKeyAction { REVEAL, TEST, SAVE, CLEAR }

/**
 * State + actions for [ApiKeysScreen]. Built by [rememberApiKeysState]; every action is fire-and-
 * forget on the composition's own scope, so leaving the screen cancels whatever was in flight.
 *
 * Revealed plaintext lives ONLY in [revealedValues], which is private, is never rendered except by
 * the row that asked for it, and is emptied by [hideAll] when the screen is disposed.
 */
@Stable
class ApiKeysState internal constructor(
    private val repository: FieldRepository,
    private val scope: CoroutineScope
) {
    /** Null until the first load finishes — the list is empty only if the server says it is. */
    var secrets by mutableStateOf<List<ManagedSecretDto>?>(null)
        private set

    /** True when the API answered 403: this account is not the master admin. */
    var restricted by mutableStateOf(false)
        private set

    /** The last failure, shown inline. Cleared when the next action starts. */
    var error by mutableStateOf<String?>(null)
        private set

    /** The last confirmation ("… saved", "… is working"), shown inline. */
    var notice by mutableStateOf<String?>(null)
        private set

    var busyKey by mutableStateOf<String?>(null)
        private set

    var busyAction by mutableStateOf<ApiKeyAction?>(null)
        private set

    /** The key whose Save panel is open, or null. */
    var editing by mutableStateOf<String?>(null)
        private set

    /** The value being typed into the Save panel. Held only while that panel is open. */
    var draft by mutableStateOf("")
        private set

    /** The key whose "Clear override" confirmation is open, or null. */
    var confirmingClear by mutableStateOf<String?>(null)
        private set

    /** The key whose value was just copied, for the two-second tick on the copy button. */
    var copied by mutableStateOf<String?>(null)
        private set

    private val revealedValues = mutableStateMapOf<String, String>()

    /** The plaintext currently on screen for [key], or null when the row is redacted. */
    fun revealed(key: String): String? = revealedValues[key]

    fun busyAction(key: String): ApiKeyAction? = if (busyKey == key) busyAction else null

    private fun begin(key: String, action: ApiKeyAction) {
        busyKey = key
        busyAction = action
        error = null
    }

    private fun finish() {
        busyKey = null
        busyAction = null
    }

    /** Swap one row in place, so a test/save/clear never costs a full reload of the list. */
    private fun replaceRow(row: ManagedSecretDto) {
        val current = secrets
        secrets = if (current == null) listOf(row) else current.map { if (it.key == row.key) row else it }
    }

    fun load() {
        scope.launch {
            error = null
            runCatching { repository.managedSecrets() }
                .onSuccess {
                    restricted = false
                    secrets = it
                }
                .onFailure { err ->
                    if (err is HttpException && err.code() == 403) {
                        restricted = true
                        secrets = emptyList()
                    } else {
                        error = err.apiErrorMessage("Unable to load the managed keys")
                    }
                }
        }
    }

    /** Tapping the eye a second time hides the value; otherwise fetch it (audit-logged server-side). */
    fun toggleReveal(secret: ManagedSecretDto) {
        if (revealedValues.containsKey(secret.key)) {
            hide(secret.key)
            return
        }
        begin(secret.key, ApiKeyAction.REVEAL)
        scope.launch {
            runCatching { repository.revealSecret(secret.key) }
                .onSuccess { result ->
                    val value = result.value
                    if (value.isNullOrEmpty()) {
                        notice = "Nothing to reveal — ${secret.label} has no value set anywhere."
                    } else {
                        revealedValues[secret.key] = value
                        notice = null
                    }
                }
                .onFailure { error = it.apiErrorMessage("Unable to reveal that key") }
            finish()
        }
    }

    /** Redact one row again. Called by the eye, by the auto-hide timer, and after a write. */
    fun hide(key: String) {
        revealedValues.remove(key)
        if (copied == key) copied = null
    }

    /** Drop every plaintext this screen is holding. Called on dispose. */
    fun hideAll() {
        revealedValues.clear()
        copied = null
        draft = ""
        editing = null
    }

    fun copyRevealed(context: Context, secret: ManagedSecretDto) {
        val value = revealedValues[secret.key] ?: return
        if (copySecretToClipboard(context, secret.label, value)) {
            copied = secret.key
            scope.launch {
                delay(2_000)
                if (copied == secret.key) copied = null
            }
        } else {
            error = "Could not copy the value to the clipboard."
        }
    }

    fun test(secret: ManagedSecretDto) {
        begin(secret.key, ApiKeyAction.TEST)
        scope.launch {
            runCatching { repository.testSecret(secret.key) }
                .onSuccess { row ->
                    replaceRow(row)
                    notice = if (row.lastStatus == "OK") {
                        "${row.label} is working — the provider accepted the key."
                    } else {
                        "${row.label} failed — ${row.lastError ?: "the provider rejected the key."}"
                    }
                }
                .onFailure { error = it.apiErrorMessage("Unable to test that key") }
            finish()
        }
    }

    fun startEdit(secret: ManagedSecretDto) {
        confirmingClear = null
        editing = secret.key
        draft = ""
    }

    fun onDraftChange(value: String) {
        draft = value
    }

    fun cancelEdit() {
        editing = null
        draft = ""
    }

    fun save(secret: ManagedSecretDto) {
        val value = draft.trim()
        if (value.isEmpty()) return
        begin(secret.key, ApiKeyAction.SAVE)
        scope.launch {
            runCatching { repository.setSecret(secret.key, value) }
                .onSuccess { row ->
                    replaceRow(row)
                    hide(row.key)
                    cancelEdit()
                    notice = "${row.label} saved — it is live for everyone right now, no restart needed."
                }
                .onFailure { error = it.apiErrorMessage("Unable to save that key") }
            finish()
        }
    }

    fun startClear(secret: ManagedSecretDto) {
        editing = null
        draft = ""
        confirmingClear = secret.key
    }

    fun cancelClear() {
        confirmingClear = null
    }

    fun clearOverride(secret: ManagedSecretDto) {
        begin(secret.key, ApiKeyAction.CLEAR)
        scope.launch {
            runCatching { repository.clearSecret(secret.key) }
                .onSuccess { row ->
                    replaceRow(row)
                    hide(row.key)
                    confirmingClear = null
                    notice = if (row.source == SOURCE_ENVIRONMENT) {
                        "${row.label} override cleared — the deployed environment value applies again."
                    } else {
                        "${row.label} override cleared — there is no value for this key anywhere now."
                    }
                }
                .onFailure { error = it.apiErrorMessage("Unable to clear that override") }
            finish()
        }
    }

    fun dismissNotice() {
        notice = null
    }

    fun dismissError() {
        error = null
    }
}

/** Remembers an [ApiKeysState] bound to this composition's scope. */
@Composable
fun rememberApiKeysState(repository: FieldRepository): ApiKeysState {
    val scope = rememberCoroutineScope()
    return remember(repository, scope) { ApiKeysState(repository, scope) }
}

/**
 * The managed API keys screen. MASTER ADMIN ONLY — the caller gates it, and a 403 from the API
 * renders the restricted state rather than an error.
 *
 * HOSTING: this is an admin-hub tool, so it lays out as a plain [Column] and renders into whatever
 * scrolling parent hosts it — exactly like [TaskAdminScreen] and every other hub tool. It must NOT
 * scroll itself: the hub renders inside the app's shared `verticalScroll` Column, and a nested
 * `verticalScroll` is measured with an infinite height budget, which throws
 * "Vertical viewport was given infinite maximum height" and takes the whole screen down before it
 * can draw. That crash is why this screen would not open at all.
 *
 * [onBack] is optional and, like [TaskAdminScreen], is left null by the hub, whose own
 * "All admin tools" pill is the back control — a second arrow here would only duplicate it. When it
 * is null the Back key still closes an open edit/clear panel first, then falls through to the host.
 *
 * [onMessage] and [onError] are optional mirrors of what the screen already shows inline, for hosts
 * that also want a snackbar.
 */
@Composable
fun ApiKeysScreen(
    repository: FieldRepository,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onMessage: (String) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val state = rememberApiKeysState(repository)
    val context = LocalContext.current

    LaunchedEffect(state) { state.load() }

    // Security: nothing revealed survives leaving this screen.
    DisposableEffect(state) { onDispose { state.hideAll() } }

    LaunchedEffect(state.notice) { state.notice?.let(onMessage) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

    // Back closes an open panel first, so a half-typed key is never one tap from leaving the screen.
    // With nothing open and no [onBack], stay disabled so the host's own handler (the admin hub's
    // "return to the tool list") gets the press instead of it being silently swallowed here.
    val hasPanelOpen = state.editing != null || state.confirmingClear != null
    BackHandler(enabled = hasPanelOpen || onBack != null) {
        when {
            state.editing != null -> state.cancelEdit()
            state.confirmingClear != null -> state.cancelClear()
            else -> onBack?.invoke()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                "Providers & API keys",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // The ranking first: it is the control most admins come here for, and the only one they can
        // all use. The key list below it is the master admin's. Its own failures are its own — they
        // must not be mistaken for the key list being broken, so they never reach this screen's
        // shared error line.
        ProviderOrderPanel(onMessage = onMessage)

        if (state.restricted) {
            ApiKeysRestrictedCard()
            return@Column
        }

        // Named now that the ranking sits above it — two sections on one screen, and only one of
        // them is about credentials.
        Text(
            "Managed API keys",
            display = true,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        ApiKeysLiveBanner()

        state.error?.let { message ->
            ApiKeysNoticeCard(
                text = message,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                onDismiss = state::dismissError
            )
        }
        state.notice?.let { message ->
            ApiKeysNoticeCard(
                text = message,
                container = MaterialTheme.field.surface100,
                content = MaterialTheme.field.body,
                onDismiss = state::dismissNotice
            )
        }

        Text(
            "Every key the repository can be configured with. Test one to check it against the " +
                "provider before a field team depends on it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val rows = state.secrets
        when {
            rows == null -> Text(
                "Loading…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            rows.isEmpty() -> Text(
                "No managed keys are registered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> rows.forEach { secret ->
                ApiKeyCard(
                    secret = secret,
                    state = state,
                    onCopy = { state.copyRevealed(context, secret) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

/** The one thing a master admin needs to know before touching anything here. */
@Composable
private fun ApiKeysLiveBanner() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Saved keys take effect immediately, for everyone.",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            "A key stored here overrides the deployed environment on the very next provider call — " +
                "the API and the transcription queue both pick it up without a restart or a redeploy. " +
                "Values are encrypted at rest, are never written to logs, and revealing one is " +
                "recorded against your account.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** Shown when the API answers 403 — a state, not an error. */
@Composable
private fun ApiKeysRestrictedCard() {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    "Master admin only",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                "The key VALUES are limited to the master admin account. Ordinary admins manage " +
                    "people and records; handing out live provider credentials stays with the " +
                    "single master admin. The provider ranking above is yours — you can reorder the " +
                    "engines and ask each provider whether its key works, which is a verdict rather " +
                    "than a credential.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ApiKeysNoticeCard(
    text: String,
    container: Color,
    content: Color,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.small)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}

@Composable
private fun ApiKeyCard(
    secret: ManagedSecretDto,
    state: ApiKeysState,
    onCopy: () -> Unit
) {
    val plaintext = state.revealed(secret.key)
    val rowBusy = state.busyAction(secret.key)
    val tokens = MaterialTheme.field

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // --- Identity -------------------------------------------------------------------
            Text(
                secret.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                secret.key,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            secret.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.body)
            }

            HorizontalDivider(color = tokens.hairline)

            // --- Value ----------------------------------------------------------------------
            ApiKeyFieldLabel("Value")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = when {
                        plaintext != null -> plaintext
                        secret.configured -> "••••••••" + (secret.hint ?: "")
                        else -> "Not set"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (plaintext != null) MaterialTheme.colorScheme.onSurface else tokens.muted,
                    modifier = Modifier
                        .weight(1f)
                        .background(tokens.surface50, MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
                IconButton(
                    onClick = { state.toggleReveal(secret) },
                    enabled = secret.configured && rowBusy != ApiKeyAction.REVEAL
                ) {
                    if (rowBusy == ApiKeyAction.REVEAL) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(
                            if (plaintext != null) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (plaintext != null) {
                                "Hide ${secret.label}"
                            } else {
                                "Reveal ${secret.label} (recorded against your account)"
                            },
                            tint = if (secret.configured) tokens.body else tokens.placeholder,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (plaintext != null) {
                    IconButton(onClick = onCopy) {
                        Icon(
                            if (state.copied == secret.key) Icons.Filled.Check else Icons.Filled.ContentCopy,
                            contentDescription = "Copy ${secret.label}",
                            tint = if (state.copied == secret.key) tokens.success else tokens.body,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (plaintext != null) {
                // The auto-hide countdown lives here, keyed on the value, so it restarts on a fresh
                // reveal and is cancelled by the composition the instant the row is redacted or the
                // screen is left — no timer can outlive what it was guarding.
                var remaining by remember(secret.key, plaintext) {
                    mutableStateOf((REVEAL_TIMEOUT_MS / 1000L).toInt())
                }
                LaunchedEffect(secret.key, plaintext) {
                    var left = (REVEAL_TIMEOUT_MS / 1000L).toInt()
                    remaining = left
                    while (left > 0) {
                        delay(1_000)
                        left -= 1
                        remaining = left
                    }
                    state.hide(secret.key)
                }
                Text(
                    "Hides itself again in $remaining seconds.",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.muted
                )
            }

            HorizontalDivider(color = tokens.hairline)

            // --- Source + status -------------------------------------------------------------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ApiKeySourceBadge(secret.source)
                ApiKeyStatusBadge(secret.lastStatus)
            }
            Text(
                apiKeySourceHelp(secret.source),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.muted
            )
            Text(
                formatApiKeyStamp(secret.lastCheckedAt)?.let { "Checked $it" } ?: "Never tested",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.muted
            )
            secret.lastError?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            ApiKeyFieldLabel("Last updated")
            val updatedAt = formatApiKeyStamp(secret.updatedAt)
            if (updatedAt == null) {
                Text(
                    "Never edited here",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.muted
                )
            } else {
                Text(updatedAt, style = MaterialTheme.typography.bodySmall, color = tokens.body)
                Text(
                    "by ${secret.updatedBy ?: "unknown"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.muted
                )
            }

            HorizontalDivider(color = tokens.hairline)

            // --- Actions ---------------------------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { state.test(secret) },
                    enabled = rowBusy != ApiKeyAction.TEST,
                    modifier = Modifier.weight(1f)
                ) {
                    if (rowBusy == ApiKeyAction.TEST) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (rowBusy == ApiKeyAction.TEST) "Testing…" else "Test")
                }
                Button(
                    onClick = { state.startEdit(secret) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (secret.source == SOURCE_DATABASE || secret.source == SOURCE_ENVIRONMENT) {
                            Icons.Filled.VpnKey
                        } else {
                            Icons.Filled.Add
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (secret.source == "unset") "Add key" else "Update")
                }
            }
            if (secret.source == SOURCE_DATABASE) {
                TextButton(
                    onClick = { state.startClear(secret) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear override")
                }
            }

            if (state.editing == secret.key) {
                ApiKeyEditPanel(secret = secret, state = state, busy = rowBusy == ApiKeyAction.SAVE)
            }
            if (state.confirmingClear == secret.key) {
                ApiKeyClearPanel(secret = secret, state = state, busy = rowBusy == ApiKeyAction.CLEAR)
            }
        }
    }
}

/** The Save/Update panel. Password-style: the value being typed is never rendered either. */
@Composable
private fun ApiKeyEditPanel(secret: ManagedSecretDto, state: ApiKeysState, busy: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (secret.source == "unset") {
                "New ${secret.label} key"
            } else {
                "Replace the ${secret.label} key"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        OutlinedTextField(
            value = state.draft,
            onValueChange = state::onDraftChange,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { state.save(secret) }),
            placeholder = {
                Text(
                    "Paste the key — whitespace is trimmed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.field.placeholder
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Saving replaces the value for the whole repository at once, effective immediately.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = state::cancelEdit,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text("Cancel") }
            Button(
                onClick = { state.save(secret) },
                enabled = state.draft.isNotBlank() && !busy,
                modifier = Modifier.weight(1f)
            ) {
                if (busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "Saving…" else "Save key")
            }
        }
    }
}

/** The Clear-override confirmation. Spells out what comes back into force. */
@Composable
private fun ApiKeyClearPanel(secret: ManagedSecretDto, state: ApiKeysState, busy: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Clear the stored ${secret.label} key?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Text(
            "The saved override is deleted and the deployed environment value applies again from " +
                "the next call. If the environment has no value for this key, the features that " +
                "need it stop working.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = state::cancelClear,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            ) { Text("Keep it") }
            Button(
                onClick = { state.clearOverride(secret) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.weight(1f)
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "Clearing…" else "Clear override")
            }
        }
    }
}

@Composable
private fun ApiKeyFieldLabel(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ApiKeyBadge(text: String, container: Color, content: Color, border: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = Modifier
            .background(container, MaterialTheme.shapes.extraSmall)
            .border(1.dp, border, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun ApiKeySourceBadge(source: String) {
    val tokens = MaterialTheme.field
    when (source) {
        SOURCE_DATABASE -> ApiKeyBadge(
            text = "Database",
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            border = MaterialTheme.colorScheme.primary
        )
        SOURCE_ENVIRONMENT -> ApiKeyBadge(
            text = "Environment",
            container = tokens.surface50,
            content = tokens.body,
            border = tokens.hairline
        )
        else -> ApiKeyBadge(
            text = "Not set",
            container = tokens.warningContainer,
            content = tokens.onWarningContainer,
            border = tokens.warning
        )
    }
}

@Composable
private fun ApiKeyStatusBadge(status: String) {
    val tokens = MaterialTheme.field
    when (status) {
        "OK" -> ApiKeyBadge(
            text = "OK",
            container = tokens.successContainer,
            content = tokens.onSuccessContainer,
            border = tokens.success
        )
        "FAILED" -> ApiKeyBadge(
            text = "Failed",
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            border = MaterialTheme.colorScheme.error
        )
        else -> ApiKeyBadge(
            text = "Unknown",
            container = tokens.surface50,
            content = tokens.muted,
            border = tokens.hairline
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Plain helpers
// ---------------------------------------------------------------------------------------------

private fun apiKeySourceHelp(source: String): String = when (source) {
    SOURCE_DATABASE -> "Saved here — this value overrides the deployed environment."
    SOURCE_ENVIRONMENT -> "Coming from the deployed environment. Saving here overrides it."
    else -> "No value anywhere — the features that need it are switched off."
}

/**
 * Put the value on the clipboard, flagged SENSITIVE on API 33+ so the system's paste preview does
 * not draw the credential in a floating toast. Returns false rather than throwing when the platform
 * refuses; the value is never logged either way.
 */
private fun copySecretToClipboard(context: Context, label: String, value: String): Boolean =
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        manager.setPrimaryClip(clip)
        true
    }.getOrDefault(false)

private val apiKeyStampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.getDefault())

/** The web's `formatDateTime`, best-effort: ISO in, "25 Jul 2026, 06:01 pm" out, null if unparseable. */
private fun formatApiKeyStamp(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val instant = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: return null
    return runCatching { apiKeyStampFormatter.format(instant.atZone(ZoneId.systemDefault())) }.getOrNull()
}
