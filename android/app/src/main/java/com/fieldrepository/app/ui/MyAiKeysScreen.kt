package com.fieldrepository.app.ui

import com.fieldrepository.app.data.apiErrorMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.AiCatalogueDto
import com.fieldrepository.app.data.AiProviderDto
import com.fieldrepository.app.data.UserAiKeyDto
import com.fieldrepository.app.data.FieldRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyAiKeysScreen(
    repository: FieldRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var catalogue by remember { mutableStateOf<AiCatalogueDto?>(null) }
    var keys by remember { mutableStateOf<List<UserAiKeyDto>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    suspend fun reload() {
        loading = true
        loadError = null
        runCatching {
            catalogue = repository.aiProviders()
            keys = repository.myAiKeys()
        }.onFailure { loadError = it.apiErrorMessage("Could not load your AI keys.") }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My AI keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { FieldCircularProgressIndicator() }

            loadError != null -> Column(Modifier.padding(padding).padding(16.dp)) {
                Text(loadError!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                ActionRow {
                    Button(onClick = { scope.launch { reload() } }) { Text("Try again") }
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column {
                        Text(
                            "Your AI requests run on your own provider account and model, billed to you.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Optional. Stored encrypted, used only for your requests, never shown to anyone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(catalogue?.providers.orEmpty(), key = { it.provider }) { provider ->
                    val state = keys.firstOrNull { it.provider == provider.provider }
                    if (state != null) {
                        ProviderKeyCard(
                            provider = provider,
                            state = state,
                            pricesCheckedOn = catalogue?.pricesCheckedOn.orEmpty(),
                            onSave = { key, model ->
                                scope.launch {
                                    runCatching { repository.setMyAiKey(provider.provider, key, model) }
                                        .onSuccess { updated ->
                                            keys = keys.map { if (it.provider == updated.provider) updated else it }
                                        }
                                }
                            },
                            onTest = {
                                scope.launch {
                                    runCatching { repository.testMyAiKey(provider.provider) }
                                        .onSuccess { updated ->
                                            keys = keys.map { if (it.provider == updated.provider) updated else it }
                                        }
                                }
                            },
                            onRemove = {
                                scope.launch {
                                    runCatching { repository.deleteMyAiKey(provider.provider) }
                                        .onSuccess { updated ->
                                            keys = keys.map { if (it.provider == updated.provider) updated else it }
                                        }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private val TASK_LABELS = mapOf(
    "PROOFREAD" to "Proofread",
    "EXPAND" to "Expand",
    "SUMMARISE" to "Summarise",
    "TRANSLATE" to "Translate",
    "TRANSCRIBE" to "Transcribe audio",
    "CAPTION" to "Describe photos"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderKeyCard(
    provider: AiProviderDto,
    state: UserAiKeyDto,
    pricesCheckedOn: String,
    onSave: (String?, String) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    var typedKey by remember(state.provider) { mutableStateOf("") }
    var chosenModel by remember(state.provider, state.model) { mutableStateOf(state.model) }
    var howToOpen by remember { mutableStateOf(false) }

    val chosen = provider.models.firstOrNull { it.id == chosenModel }
    val transcribes = provider.models.any { "TRANSCRIBE" in it.tasks }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    provider.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        state.unreadable -> "Paste it again"
                        !state.configured -> "Not set"
                        state.lastStatus == "OK" -> "Working"
                        state.lastStatus == "FAILED" -> "Not working"
                        else -> "Untested"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        state.unreadable || state.lastStatus == "FAILED" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (state.hint != null) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Ends …${state.hint}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.unreadable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This key can't be read. Paste it again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (!state.lastError.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    state.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (!state.modelKnown) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This model is no longer offered. Pick another.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = typedKey,
                onValueChange = { typedKey = it },
                label = { Text(if (state.configured) "Replace the key" else "Paste your key") },
                placeholder = { Text(provider.keyPrefix?.let { "$it…" } ?: "Your API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            SearchableSelectField(
                label = "Model",
                options = provider.models.map { model ->
                    SelectOption(
                        model.id,
                        model.label + if (model.id == provider.defaultModel) " (recommended)" else ""
                    )
                },
                selectedValue = chosenModel,
                placeholder = chosenModel.ifBlank { "Choose a model" },
                includeNone = false,
                onSelect = { chosenModel = it }
            )

            if (chosen != null) {
                Spacer(Modifier.height(6.dp))
                Text(chosen.note, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Used for: " + chosen.tasks.joinToString(" · ") { TASK_LABELS[it] ?: it } + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val input = chosen.inputPricePerMTok
                val output = chosen.outputPricePerMTok
                if (input != null && output != null) {
                    Text(
                        "About \$$input in / \$$output out per million words-ish, checked $pricesCheckedOn",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!transcribes) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${provider.label} can't transcribe audio, so recordings use the app's key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            ActionRow {
                Button(
                    onClick = {
                        onSave(typedKey.takeIf { it.isNotBlank() }, chosenModel)
                        typedKey = ""
                    },
                    enabled = typedKey.isNotBlank() || chosenModel != state.model
                ) { Text("Save") }
                OutlinedButton(onClick = onTest, enabled = state.configured) { Text("Test") }
                if (state.configured || state.unreadable) {
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            TextButton(onClick = { howToOpen = !howToOpen }, modifier = Modifier.fillMaxWidth()) {
                Text("How to get a ${provider.label} key")
                Spacer(Modifier.weight(1f))
                Icon(
                    if (howToOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
            }
            if (howToOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    provider.howTo.forEachIndexed { index, step ->
                        Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Keys page: ${provider.consoleUrl}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Current prices: ${provider.pricingUrl}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
