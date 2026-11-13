package com.fieldrepository.app.transcript.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.fieldrepository.app.transcript.data.SpokenLanguageMemory
import com.fieldrepository.app.transcript.data.SpokenLanguageSuggested
import com.fieldrepository.app.transcript.data.SpokenLanguages
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.portal.TranscriptPortal
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SpokenLanguagePicker(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(TranscriptStrings.spokenLanguage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("spokenLanguage.field"),
        ) {
            Text(
                SpokenLanguages.label(value),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted,
                modifier = Modifier.testTag("spokenLanguage.hint"),
            )
        }
    }
    if (open) {
        SpokenLanguageDialog(
            value = value,
            onPick = { code -> open = false; onChange(code) },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun SpokenLanguageDialog(value: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query) { SpokenLanguages.filter(query) }
    val featured = SpokenLanguages.languages.count { it.featured }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp).testTag("spokenLanguage.dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(TranscriptStrings.spokenLanguage, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(TranscriptStrings.spokenLanguageSearch) },
                    modifier = Modifier.fillMaxWidth().testTag("spokenLanguage.search"),
                )
                if (shown.isEmpty()) {
                    Text(
                        TranscriptStrings.spokenLanguageNoMatch,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.field.muted,
                        modifier = Modifier.padding(vertical = 12.dp).testTag("spokenLanguage.empty"),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(shown, key = { _, option -> option.code }) { index, option ->
                        val selected = option.code == SpokenLanguages.normalise(value)
                        Column(Modifier.fillMaxWidth()) {
                            if (query.isBlank() && (index == 2 || index == 2 + featured)) HorizontalDivider()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable { onPick(option.code) }
                                    .padding(horizontal = 8.dp)
                                    .testTag("spokenLanguage.option.${option.code}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    option.label,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                if (selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(TranscriptStrings.cancel) }
                }
            }
        }
    }
}

@Stable
class SpokenLanguageChoice(initial: String = SpokenLanguages.AUTO) {
    var value by mutableStateOf(SpokenLanguages.normalise(initial))
        private set
    var hint by mutableStateOf<String?>(null)
        private set
    var touched by mutableStateOf(false)
        private set

    fun choose(code: String) {
        value = SpokenLanguages.normalise(code)
        touched = true
        hint = null
    }

    fun offer(suggested: SpokenLanguageSuggested?) {
        if (touched || suggested == null) return
        value = SpokenLanguages.normalise(suggested.code)
        hint = SpokenLanguages.hint(suggested.source)
    }
}

@Composable
fun rememberSpokenLanguageChoice(artisanIds: List<String> = emptyList()): SpokenLanguageChoice {
    val context = LocalContext.current
    val choice = remember { SpokenLanguageChoice() }
    val ids = artisanIds.filter { it.isNotBlank() && !it.startsWith("local") }.distinct().sorted()
    LaunchedEffect(ids) {
        val server = runCatching { TranscriptPortal.backend(context).spokenLanguageSuggestion(ids).suggested }.getOrNull()
        choice.offer(SpokenLanguages.suggest(server, SpokenLanguageMemory.last(context)))
    }
    return choice
}

@Composable
fun SpokenLanguageField(choice: SpokenLanguageChoice, modifier: Modifier = Modifier, enabled: Boolean = true) {
    SpokenLanguagePicker(
        value = choice.value,
        onChange = { choice.choose(it) },
        modifier = modifier,
        hint = choice.hint,
        enabled = enabled,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpokenLanguageLine(view: Transcript, onWrongLanguage: () -> Unit) {
    val spoken = view.spokenLanguage ?: return
    if (view.version == null) return
    val requested = spoken.requested
    val heard = when {
        requested != null && requested != SpokenLanguages.AUTO ->
            TranscriptStrings.transcribedAs(spoken.requestedLabel?.ifBlank { null } ?: SpokenLanguages.label(requested))
        spoken.detectedLabel != null -> TranscriptStrings.detectedLanguage(spoken.detectedLabel)
        else -> null
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (heard != null) {
                Text(
                    heard,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("transcript.spokenLanguage"),
                )
            }
            if (spoken.uncertain) {
                Text(
                    TranscriptStrings.languageUncertain,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.field.onWarningContainer,
                    modifier = Modifier
                        .background(MaterialTheme.field.warningContainer, RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("transcript.languageUncertain"),
                )
            }
        }
        when {
            spoken.retranscribing -> Text(
                TranscriptStrings.retranscribing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted,
                modifier = Modifier.testTag("transcript.retranscribing"),
            )
            view.permissions.retranscribe -> TextButton(
                onClick = onWrongLanguage,
                modifier = Modifier.heightIn(min = 48.dp).testTag("transcript.wrongLanguage"),
            ) { Text(TranscriptStrings.wrongLanguage) }
        }
    }
}

internal fun minutesText(minutes: Double): String =
    if (minutes >= 10 || minutes == Math.floor(minutes)) String.format(Locale.ROOT, "%.0f", minutes) else String.format(Locale.ROOT, "%.1f", minutes)

internal fun usdText(usd: Double): String = String.format(Locale.ROOT, "%.2f", usd)

@Composable
internal fun RetranscribeDialog(view: Transcript, host: TranscriptHost, onDismiss: () -> Unit) {
    val spoken = view.spokenLanguage
    var value by remember(view.mediaId) { mutableStateOf(SpokenLanguages.retranscribeStart(spoken)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("transcript.retranscribeDialog"),
        title = { Text(TranscriptStrings.retranscribeTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SpokenLanguagePicker(value = value, onChange = { value = it; error = null }, enabled = !busy)
                val minutes = spoken?.estimateMinutes ?: 0.0
                if (minutes > 0) {
                    Text(
                        TranscriptStrings.retranscribeMinutes(minutesText(minutes)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("transcript.retranscribeMinutes"),
                    )
                }
                spoken?.estimateUsd?.let { usd ->
                    Text(
                        TranscriptStrings.retranscribeCost(usdText(usd)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("transcript.retranscribeCost"),
                    )
                }
                Text(TranscriptStrings.retranscribeKeeps, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("transcript.retranscribeError"))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        val refusal = host.retranscribe(value)
                        busy = false
                        if (refusal == null) onDismiss() else error = refusal
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag("transcript.retranscribeConfirm"),
            ) { Text(TranscriptStrings.retranscribeConfirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(TranscriptStrings.cancel) }
        },
    )
}
