package com.fieldrepository.app.transcript.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import com.fieldrepository.app.ui.FieldCheckbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.transcript.data.SpokenLanguageChoices
import com.fieldrepository.app.transcript.data.SpokenLanguageMemory
import com.fieldrepository.app.transcript.data.TranscribeChoices
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.UploadPolicy
import com.fieldrepository.app.transcript.data.UploadPolicyCache
import com.fieldrepository.app.transcript.data.UploadTranscribe
import com.fieldrepository.app.transcript.portal.TranscriptPortal
import com.fieldrepository.app.ui.AppToggle
import com.fieldrepository.app.ui.field

data class TranscribeChoiceItem(val key: String, val name: String, val typeOrMime: String?)

@Composable
fun rememberUploadPolicy(): UploadPolicy? {
    val context = LocalContext.current
    LaunchedEffect(Unit) { UploadPolicyCache.refresh(TranscriptPortal.backend(context)) }
    return UploadPolicyCache.current
}

@Composable
fun TranscribeChoiceRows(
    items: List<TranscribeChoiceItem>,
    modifier: Modifier = Modifier,
    policy: UploadPolicy? = rememberUploadPolicy(),
    enabled: Boolean = true,
    isOn: (String) -> Boolean = TranscribeChoices::isOn,
    onChange: (String, Boolean) -> Unit = TranscribeChoices::set,
    artisanIds: List<String> = emptyList(),
    spokenLanguageOf: (String) -> String? = SpokenLanguageChoices::forKey,
    onSpokenLanguage: (String, String) -> Unit = SpokenLanguageChoices::set,
) {
    val eligible = items.filter { UploadTranscribe.eligible(it.typeOrMime) }
    if (eligible.isEmpty()) return
    val open = eligible.filterNot { UploadTranscribe.locked(it.typeOrMime, policy) }
    val note = UploadTranscribe.note(eligible.map { it.typeOrMime }, policy)
    val context = LocalContext.current
    val transcribing = eligible.filter { UploadTranscribe.locked(it.typeOrMime, policy) || isOn(it.key) }.map { it.key }
    val spoken = rememberSpokenLanguageChoice(artisanIds)
    val stored = remember { eligible.firstNotNullOfOrNull { spokenLanguageOf(it.key) } }
    LaunchedEffect(Unit) {
        if (stored != null && !spoken.touched) spoken.choose(stored)
    }
    LaunchedEffect(spoken.value, transcribing) {
        transcribing.forEach { key -> if (spokenLanguageOf(key) != spoken.value) onSpokenLanguage(key, spoken.value) }
    }
    Column(
        modifier.fillMaxWidth().testTag("upload.transcribe"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (open.size >= 2) {
            val allOn = open.all { isOn(it.key) }
            AppToggle(
                label = TranscriptStrings.uploadTranscribeAll,
                checked = allOn,
                onCheckedChange = { on -> open.forEach { onChange(it.key, on) } },
                enabled = enabled,
                modifier = Modifier.testTag("upload.transcribe.all"),
            )
        }
        eligible.forEach { item ->
            val locked = UploadTranscribe.locked(item.typeOrMime, policy)
            val checked = locked || isOn(item.key)
            val interactive = enabled && !locked
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        enabled = interactive,
                        role = Role.Checkbox,
                        onValueChange = { onChange(item.key, it) },
                    )
                    .padding(vertical = 2.dp)
                    .testTag("upload.transcribe.item:${item.key}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FieldCheckbox(checked = checked, onCheckedChange = null, enabled = interactive)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(TranscriptStrings.uploadTranscribe, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.field.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (transcribing.isNotEmpty()) {
            SpokenLanguagePicker(
                value = spoken.value,
                onChange = { code ->
                    spoken.choose(code)
                    SpokenLanguageMemory.remember(context, code)
                },
                hint = spoken.hint,
                enabled = enabled,
                modifier = Modifier.padding(top = 4.dp).testTag("upload.spokenLanguage"),
            )
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted,
                modifier = Modifier.testTag("upload.transcribe.note"),
            )
        }
    }
}
