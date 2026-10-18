package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.apiErrorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Settings → API keys → Transcription on a phone. The Android half of the shared web panel
 * (`frontend/components/settings/TranscriptionPanel.tsx`): the pause, providers on/off, monthly
 * minute budgets, spend this month, active cooldowns, recent problems, and the dry-run "Check failed
 * transcriptions". [canChange] is the master admin's; an admin reads. Never scrolls itself — it
 * renders inside the admin hub's own `verticalScroll`, like [ProviderOrderPanel].
 */

@Stable
class TranscriptionPanelState internal constructor(
    private val api: TranscriptionSettingsApi,
    private val scope: CoroutineScope
) {
    var state by mutableStateOf<TranscriptionStateDto?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf<String?>(null)
        private set
    var retry by mutableStateOf<TranscriptionRetryDto?>(null)
        private set
    val budgetDraft = mutableStateMapOf<String, String>()

    fun load() {
        scope.launch {
            runCatching { api.transcriptionSettings() }
                .onSuccess { state = it; error = null }
                .onFailure { error = it.apiErrorMessage("Couldn't load transcription settings.") }
        }
    }

    private fun save(key: String, body: JsonObject) {
        scope.launch {
            saving = key
            runCatching { api.updateTranscriptionSettings(body) }
                .onSuccess { state = it; error = null }
                .onFailure { error = it.apiErrorMessage("Couldn't save.") }
            saving = null
        }
    }

    fun setOn(on: Boolean) = save("pause", buildJsonObject { put("paused", !on) })

    fun toggleProvider(id: String, enabled: Boolean) {
        val current = state ?: return
        val disabled = transcriptionDisabledAfter(current.providers, id, enabled)
        save("provider:$id", buildJsonObject { put("disabledProviders", buildJsonArray { disabled.forEach { add(JsonPrimitive(it)) } }) })
    }

    fun saveBudget(provider: String) {
        val current = state ?: return
        val raw = budgetDraft[provider] ?: (transcriptionMonthBudget(current, provider)?.toString() ?: "")
        val minutes = when (val parsed = parseTranscriptionBudget(raw)) {
            BudgetInput.Invalid -> {
                error = TRANSCRIPTION_BUDGET_INVALID
                return
            }
            BudgetInput.NoLimit -> JsonNull
            is BudgetInput.Minutes -> JsonPrimitive(parsed.value)
        }
        save(
            "budget:$provider",
            buildJsonObject {
                put(
                    "budgets",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("provider", provider)
                                put("period", "MONTH")
                                put("minutesCap", minutes)
                                put("spendCapUsd", JsonNull)
                            }
                        )
                    }
                )
            }
        )
    }

    fun checkRetry() {
        scope.launch {
            saving = "retry"
            runCatching { api.checkFailedTranscriptions(TranscriptionRetryBody(dryRun = true)) }
                .onSuccess { retry = it; error = null }
                .onFailure { error = it.apiErrorMessage("Couldn't check failed transcriptions.") }
            saving = null
        }
    }
}

@Composable
fun rememberTranscriptionPanelState(api: TranscriptionSettingsApi): TranscriptionPanelState {
    val scope = rememberCoroutineScope()
    return remember(api, scope) { TranscriptionPanelState(api, scope) }
}

@Composable
fun TranscriptionPanel(
    canChange: Boolean,
    modifier: Modifier = Modifier,
    api: TranscriptionSettingsApi = SttProviderClient.transcription(LocalContext.current)
) {
    val panel = rememberTranscriptionPanelState(api)
    val tokens = MaterialTheme.field
    LaunchedEffect(panel) { panel.load() }
    val state = panel.state

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Transcription",
                display = true,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                state?.let(::transcriptionQueueLine) ?: "Loading…",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.muted
            )

            panel.error?.let { message ->
                Text(
                    message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
                        .padding(12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            if (state != null) {
                AppToggle(
                    label = "Transcription is on",
                    description = transcriptionPauseDescription(state.paused),
                    checked = !state.paused,
                    onCheckedChange = panel::setOn,
                    enabled = canChange,
                    busy = panel.saving == "pause"
                )

                Text("Providers", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                state.providers.forEach { provider ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(tokens.surface50, MaterialTheme.shapes.medium)
                            .border(1.dp, tokens.hairline, MaterialTheme.shapes.medium)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AppToggle(
                            label = provider.name.ifBlank { provider.id },
                            description = transcriptionProviderStatus(state, provider, ::transcriptionStamp),
                            checked = provider.enabled,
                            onCheckedChange = { on -> panel.toggleProvider(provider.id, on) },
                            enabled = canChange,
                            busy = panel.saving == "provider:${provider.id}"
                        )
                        if (canChange) {
                            // A form-row button aligned to its input, not an action row.
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = panel.budgetDraft[provider.id]
                                        ?: (transcriptionMonthBudget(state, provider.id)?.toString() ?: ""),
                                    onValueChange = { panel.budgetDraft[provider.id] = it },
                                    singleLine = true,
                                    label = { Text("Monthly minutes") },
                                    placeholder = { Text("No limit", color = tokens.placeholder) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = { panel.saveBudget(provider.id) },
                                    enabled = panel.saving != "budget:${provider.id}"
                                ) { Text("Save") }
                            }
                        }
                    }
                }

                Text(transcriptionWindowLine(state.window), style = MaterialTheme.typography.bodySmall, color = tokens.muted)

                if (state.failures.isNotEmpty()) {
                    var open by remember { mutableStateOf(false) }
                    TextButton(onClick = { open = !open }) {
                        Text("Recent problems (${state.failures.size})")
                    }
                    if (open) {
                        state.failures.forEach { failure ->
                            val name = state.providers.firstOrNull { it.id == failure.provider }?.name ?: failure.provider
                            Text(
                                "${transcriptionStamp(failure.at)} · $name · ${transcriptionOutcomeWords(failure.outcome)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.body
                            )
                        }
                    }
                }

                if (canChange) {
                    // One button: centred.
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = panel::checkRetry, enabled = panel.saving != "retry") {
                            Text("Check failed transcriptions")
                        }
                        panel.retry?.let { answer ->
                            Text(
                                transcriptionRetrySentence(answer, state.paused),
                                modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.muted
                            )
                        }
                    }
                }
            }
        }
    }
}

private val transcriptionStampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

/** "18 Oct, 14:05" in the phone's zone, or the raw value when it cannot be read. */
fun transcriptionStamp(value: String): String {
    val instant = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: return value
    return runCatching { transcriptionStampFormatter.format(instant.atZone(ZoneId.systemDefault())) }.getOrDefault(value)
}
