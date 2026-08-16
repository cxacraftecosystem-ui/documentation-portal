package com.fieldrepository.app.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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

/**
 * **A RESEARCHER'S OWN PROVIDER KEYS**, on the handset. The twin of the web's `MyAiKeysPanel`.
 *
 * ── WHAT THIS IS, AND HOW IT DIFFERS FROM [ApiKeysScreen] ─────────────────────────────────────
 *
 * [ApiKeysScreen] manages the DEPLOYMENT's keys and is reachable only from the admin hub, by a
 * master admin: those are the organisation's credentials, on the organisation's bill, and that
 * screen has a reveal control because a master admin sometimes has to compare a stored key with a
 * provider dashboard.
 *
 * This screen is the opposite of that in every respect. It is a PERSONAL setting, open to every
 * signed-in account, and it acts only on the caller's own rows — the server takes the owner from
 * the token, so there is no request this screen could make that touches somebody else's key. And
 * **there is no reveal control here and there must never be one**: nobody, administrator included,
 * has any business reading another person's personal credential. The last four characters are the
 * most this app will ever show, which is enough to tell two of your own keys apart and no more.
 *
 * ── THE CAPABILITY LINE IS LOAD-BEARING ───────────────────────────────────────────────────────
 *
 * Each model prints what it can actually be used for, and a provider that cannot do something says
 * so in words — Claude cannot transcribe audio, because no Claude model accepts a sound file. The
 * server enforces that rule regardless; this screen is where a person can see it BEFORE choosing.
 * Without it, somebody pastes a Claude key believing their recordings are now on their own account
 * and discovers otherwise from a bill that never arrives.
 */
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
        }.onFailure { loadError = it.message ?: "Could not load your AI keys." }
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
            ) { CircularProgressIndicator() }

            loadError != null -> Column(Modifier.padding(padding).padding(16.dp)) {
                Text(loadError!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { scope.launch { reload() } }) { Text("Try again") }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column {
                        Text(
                            "Bring your own key and the AI work you ask for — proofreading, " +
                                "expanding, summarising, translating, transcribing and photo " +
                                "descriptions — runs on your account with your provider, at your " +
                                "choice of model, and is billed to you.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Leave this empty and everything works exactly as it does now, on the " +
                                "key this server is set up with. Your key is stored encrypted, is " +
                                "used only for work you personally ask for, and is never shown to " +
                                "anyone — including administrators.",
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

/** The researcher-facing name of each job. The server sends the enum; this is the only place on the
 *  handset that turns it into words, so the six names read the same on every row. */
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
    var modelMenuOpen by remember { mutableStateOf(false) }
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
                    "This key can no longer be decrypted — the server's encryption key changed " +
                        "after it was saved. Paste it again to fix it. Nothing is using it meanwhile.",
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
                    "The model saved here is not one this app offers any more. Pick another below " +
                        "— until you do, your key runs whichever current model fits each job.",
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
                // The one control on this screen that must never echo: a key typed in a courtyard
                // is typed in front of whoever is standing there.
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            ExposedDropdownMenuBox(
                expanded = modelMenuOpen,
                onExpandedChange = { modelMenuOpen = it }
            ) {
                OutlinedTextField(
                    value = chosen?.label ?: chosenModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenuOpen) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(
                        androidx.compose.material3.MenuAnchorType.PrimaryNotEditable, true
                    )
                )
                ExposedDropdownMenu(
                    expanded = modelMenuOpen,
                    onDismissRequest = { modelMenuOpen = false }
                ) {
                    provider.models.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    model.label +
                                        if (model.id == provider.defaultModel) " (recommended)" else ""
                                )
                            },
                            onClick = {
                                chosenModel = model.id
                                modelMenuOpen = false
                            }
                        )
                    }
                }
            }

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
                    // The date travels with the figure, every time. See AiCatalogueDto.
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
                    "${provider.label} cannot transcribe audio — none of its models accepts a " +
                        "sound file — so recordings keep using whatever this server is set up " +
                        "with, whatever you save here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    // The URLs are printed rather than opened, on purpose: getting a key means
                    // signing in to a provider console and copying a secret, which is a laptop job.
                    // A tap that dropped somebody into a mobile browser to do it would be inviting
                    // them to paste a credential into whatever else that browser has open.
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
