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

private const val REVEAL_TIMEOUT_MS = 30_000L

private const val SOURCE_DATABASE = "database"
private const val SOURCE_ENVIRONMENT = "environment"

enum class ApiKeyAction { REVEAL, TEST, SAVE, CLEAR }

@Stable
class ApiKeysState internal constructor(
    private val repository: FieldRepository,
    private val scope: CoroutineScope
) {
    var secrets by mutableStateOf<List<ManagedSecretDto>?>(null)
        private set

    var restricted by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    var busyKey by mutableStateOf<String?>(null)
        private set

    var busyAction by mutableStateOf<ApiKeyAction?>(null)
        private set

    var editing by mutableStateOf<String?>(null)
        private set

    var draft by mutableStateOf("")
        private set

    var confirmingClear by mutableStateOf<String?>(null)
        private set

    var copied by mutableStateOf<String?>(null)
        private set

    private val revealedValues = mutableStateMapOf<String, String>()

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
                        notice = "${secret.label} has no value."
                    } else {
                        revealedValues[secret.key] = value
                        notice = null
                    }
                }
                .onFailure { error = it.apiErrorMessage("Unable to reveal that key") }
            finish()
        }
    }

    fun hide(key: String) {
        revealedValues.remove(key)
        if (copied == key) copied = null
    }

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
                        "${row.label} works."
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
                    notice = "${row.label} saved. Live for everyone."
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
                        "${row.label} reset to default."
                    } else {
                        "${row.label} cleared. No value set."
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

@Composable
fun rememberApiKeysState(repository: FieldRepository): ApiKeysState {
    val scope = rememberCoroutineScope()
    return remember(repository, scope) { ApiKeysState(repository, scope) }
}

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

    DisposableEffect(state) { onDispose { state.hideAll() } }

    LaunchedEffect(state.notice) { state.notice?.let(onMessage) }
    LaunchedEffect(state.error) { state.error?.let(onError) }

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

        ProviderOrderPanel(onMessage = onMessage)

        com.fieldrepository.app.transcript.ui.TranscriptBudgetCard()

        TranscriptionPanel(canChange = state.secrets != null && !state.restricted)

        com.fieldrepository.app.transcript.ui.AudioIsolationCard(canChange = state.secrets != null && !state.restricted)
        com.fieldrepository.app.transcript.ui.TranscriptAutoSwitchesCard(canChange = state.secrets != null && !state.restricted)

        if (state.restricted) {
            ApiKeysRestrictedCard()
            return@Column
        }

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
            "Service keys the app uses. Test one before the team relies on it.",
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
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            "Applies to everyone at once. Stored encrypted, never logged; reveals are recorded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

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
                "Only the master admin can see or change key values. You can reorder providers and test each key.",
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
                        FieldCircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(
                            if (plaintext != null) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (plaintext != null) {
                                "Hide ${secret.label}"
                            } else {
                                "Reveal ${secret.label} (logged)"
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
                    "Hides in ${remaining}s.",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.muted
                )
            }

            HorizontalDivider(color = tokens.hairline)

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

            ActionRow {
                OutlinedButton(
                    onClick = { state.test(secret) },
                    enabled = rowBusy != ApiKeyAction.TEST,
                ) {
                    if (rowBusy == ApiKeyAction.TEST) {
                        FieldCircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (rowBusy == ApiKeyAction.TEST) "Testing…" else "Test")
                }
                Button(
                    onClick = { state.startEdit(secret) },
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
                ActionRow {
                    TextButton(
                        onClick = { state.startClear(secret) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear override")
                    }
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
                "Replace ${secret.label}"
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
                    "Paste the key",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.field.placeholder
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Applies to everyone immediately.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        ActionRow {
            OutlinedButton(
                onClick = state::cancelEdit,
                enabled = !busy,
            ) { Text("Cancel") }
            Button(
                onClick = { state.save(secret) },
                enabled = state.draft.isNotBlank() && !busy,
            ) {
                if (busy) {
                    FieldCircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "Saving…" else "Save key")
            }
        }
    }
}

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
            "The saved key is deleted and the default applies. With no default, features that need it stop working.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        ActionRow {
            OutlinedButton(
                onClick = state::cancelClear,
                enabled = !busy,
            ) { Text("Keep it") }
            Button(
                onClick = { state.clearOverride(secret) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
            ) {
                if (busy) {
                    FieldCircularProgressIndicator(
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
        text.uppercase(Locale.ROOT),
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
            text = "Saved here",
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            border = MaterialTheme.colorScheme.primary
        )
        SOURCE_ENVIRONMENT -> ApiKeyBadge(
            text = "Default",
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

private fun apiKeySourceHelp(source: String): String = when (source) {
    SOURCE_DATABASE -> "Saved here · overrides the default"
    SOURCE_ENVIRONMENT -> "Default value"
    else -> "Not set · dependent features are off"
}

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

private fun formatApiKeyStamp(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val instant = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: return null
    return runCatching { apiKeyStampFormatter.format(instant.atZone(ZoneId.systemDefault())) }.getOrNull()
}
