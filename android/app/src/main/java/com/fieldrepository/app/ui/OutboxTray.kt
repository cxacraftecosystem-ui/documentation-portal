package com.fieldrepository.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.data.OfflineOutbox
import com.fieldrepository.app.data.PayloadField
import com.fieldrepository.app.data.PendingEntry
import com.fieldrepository.app.data.applyPayloadEdits
import com.fieldrepository.app.data.editablePayloadFields
import com.fieldrepository.app.data.fixablePayload
import com.fieldrepository.app.data.isFixable
import com.fieldrepository.app.data.isPossibleDuplicate
import com.fieldrepository.app.data.offersExistingRecord
import com.fieldrepository.app.data.outboxDiscardSentence
import com.fieldrepository.app.data.useExistingSentence
import com.fieldrepository.app.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * THE REFUSED-SAVES TRAY (B1, 2026-10-17) — the designer portal's `OfflineOutboxTray`, ported.
 *
 * A save the server refused sat in the outbox banner with one button, "Try again", that sent the
 * same bytes to the same refusal. This tray gives each refused entry the doors it actually has:
 *
 *  1. FIX AND SEND — the queued body opens with the field the server named highlighted, and goes
 *     again with the correction. First, because it is the one that usually works.
 *  2. USE EXISTING ARTISAN — on a duplicate-Aadhaar refusal, drop this copy and send its photographs
 *     to the artisan already on the register.
 *  3. TRY AGAIN — always offered: the phone cannot know that an administrator has just fixed the
 *     cause on the server.
 *  4. DISCARD — a separate, named, confirmed act, last, and the only way an unsent entry leaves.
 *
 * The server's reason is shown verbatim: those sentences were written for the person holding the
 * phone. Only the signed-in account's entries are listed (`FieldRepository.pendingEntries`).
 */
@Composable
fun OfflineOutboxTray(
    entries: suspend () -> List<PendingEntry>,
    onRetry: suspend (entryId: String) -> Unit,
    onFix: suspend (entryId: String, payloadJson: String) -> Boolean,
    onUseExisting: suspend (entryId: String) -> Boolean,
    onDiscard: suspend (entryId: String) -> Boolean,
    onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Null until the first read lands: the spinner is for opening the tray, never for a re-read.
    var refused by remember { mutableStateOf<List<PendingEntry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var fixing by remember { mutableStateOf<PendingEntry?>(null) }
    var confirmDiscard by remember { mutableStateOf<PendingEntry?>(null) }

    // A refusal the sync worker records while the tray is open joins the list without a reopen.
    val revision by OfflineOutbox.revision.collectAsState()

    // Later re-reads swap the list in place: no spinner, no reset scroll, no card moving under a tap.
    LaunchedEffect(reload, revision) {
        val read = runCatching { entries() }
        (read.exceptionOrNull() as? CancellationException)?.let { throw it }
        refused = refusedAfterRead(refused, read)
    }
    val shown = refused
    val scroll = rememberScrollState()

    fun act(block: suspend () -> String?) {
        if (busy) return
        busy = true
        note = null
        scope.launch {
            runCatching { block() }
                .onSuccess { message -> note = message; reload++; onChanged() }
                .onFailure { error -> if (error !is CancellationException) note = error.userMessage("That didn't work. Try again.") }
            busy = false
        }
    }

    fixing?.let { entry ->
        FixAndSendDialog(
            entry = entry,
            busy = busy,
            onDismiss = { fixing = null },
            onSend = { body ->
                fixing = null
                act { if (onFix(entry.id, body)) "“${entry.label}” will send with your correction." else "This entry is no longer queued." }
            }
        )
    }

    confirmDiscard?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            title = { Text(if (entry.offersExistingRecord()) "Discard this copy?" else "Discard this save?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    outboxDiscardSentence(entry.label, entry.media.size, clash = entry.offersExistingRecord()),
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val doomed = entry
                    confirmDiscard = null
                    act { if (onDiscard(doomed.id)) "“${doomed.label}” deleted from this phone." else "This entry is no longer queued." }
                }) { Text("Discard", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("Keep it", style = MaterialTheme.typography.bodyMedium) } }
        )
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Saves that need you", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when {
                    shown == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Reading the queue…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    shown.isEmpty() -> Text("Nothing is waiting on you.", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text("The server refused these. Nothing was deleted.", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                        shown.forEach { entry ->
                            RefusedEntryCard(
                                entry = entry,
                                busy = busy,
                                onFix = { fixing = entry },
                                onUseExisting = {
                                    act {
                                        if (onUseExisting(entry.id)) useExistingSentence(entry.label, entry.existingRecordLabel, entry.media.size)
                                        else "This entry is no longer queued."
                                    }
                                },
                                onRetry = { act { onRetry(entry.id); "“${entry.label}” will be tried again." } },
                                onDiscard = { confirmDiscard = entry }
                            )
                        }
                    }
                }
                note?.let {
                    HorizontalDivider(color = MaterialTheme.field.hairline)
                    Text(it, color = MaterialTheme.field.body, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", style = MaterialTheme.typography.bodyMedium) } }
    )
}

/**
 * The tray's list after one read of the queue. A read that fails keeps what is already on screen
 * (blank only before the first read): a hiccup mid-session must not empty an open tray.
 */
internal fun refusedAfterRead(previous: List<PendingEntry>?, read: Result<List<PendingEntry>>): List<PendingEntry> =
    read.map { queue -> queue.filter { it.failure != null && !it.isPossibleDuplicate() } }
        .getOrElse { previous ?: emptyList() }

@Composable
private fun RefusedEntryCard(
    entry: PendingEntry,
    busy: Boolean,
    onFix: () -> Unit,
    onUseExisting: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    val clash = entry.offersExistingRecord()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(entry.label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                entry.type.replaceFirstChar { it.uppercase() },
                when (entry.media.size) { 0 -> null; 1 -> "1 file with it"; else -> "${entry.media.size} files with it" }
            ).joinToString(" · "),
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (clash) {
            Text(
                "ALREADY ON THE REGISTER" + (entry.existingRecordLabel?.let { ": $it" } ?: ""),
                color = MaterialTheme.field.warning,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        // Verbatim, never truncated: the server's sentence, written for this person.
        Text(entry.failure.orEmpty(), color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
        ActionRow(
            destructive = {
                OutlinedButton(onClick = onDiscard, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Discard", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        ) {
            if (clash) {
                Button(onClick = onUseExisting, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Use existing artisan", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (entry.isFixable()) {
                if (clash) {
                    OutlinedButton(onClick = onFix, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fix and send", style = MaterialTheme.typography.bodySmall) }
                } else {
                    Button(onClick = onFix, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Fix and send", style = MaterialTheme.typography.bodySmall) }
                }
            }
            MediaActionButton(action = MediaAction.RETRY, text = "Try again", onClick = onRetry, enabled = !busy)
        }
    }
}

/**
 * The queued body, editable. The fields the refusal named come first, marked, under the server's
 * sentence; the rest of the plain answers are one tap away. Nothing else in the body changes.
 */
@Composable
private fun FixAndSendDialog(
    entry: PendingEntry,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    val payload = remember(entry.id) { entry.fixablePayload() }
    val fields = remember(entry.id) { editablePayloadFields(payload, entry.failureFields) }
    val edits = remember(entry.id) { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.path, it.value) } } }
    var showAll by remember(entry.id) { mutableStateOf(fields.none { it.highlighted }) }
    val shown: List<PayloadField> = if (showAll) fields else fields.filter { it.highlighted }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fix “${entry.label}”", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(entry.failure.orEmpty(), color = MaterialTheme.field.warning, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
                if (fields.isEmpty()) {
                    Text("Nothing in this save can be edited here. Try again, or discard it.", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
                }
                shown.forEach { field ->
                    val marked = Modifier.then(
                        if (field.highlighted) {
                            Modifier.border(2.dp, MaterialTheme.field.warning, RoundedCornerShape(6.dp)).padding(4.dp)
                        } else Modifier
                    )
                    Column(modifier = marked, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (field.highlighted) {
                            Text("Named by the server", color = MaterialTheme.field.warning, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedTextField(
                            value = edits[field.path].orEmpty(),
                            onValueChange = { edits[field.path] = it },
                            label = { Text(field.label) },
                            isError = field.highlighted,
                            singleLine = field.kind != "string",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (!showAll && fields.any { !it.highlighted }) {
                    TextButton(onClick = { showAll = true }) { Text("Show every field") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && fields.isNotEmpty(),
                onClick = { onSend(applyPayloadEdits(payload, fields, edits.toMap())) }
            ) { Text("Send", style = MaterialTheme.typography.bodyMedium) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", style = MaterialTheme.typography.bodyMedium) } }
    )
}
