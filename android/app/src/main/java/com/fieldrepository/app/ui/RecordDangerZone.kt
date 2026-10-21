package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.UserDto
import kotlinx.coroutines.launch

/**
 * The "Danger zone" card that ends a record's page: its Delete, behind a confirmation.
 *
 * Identical in designer-portal and documentation-portal apart from the package and import lines, so the two apps
 * delete a record with the same card, words and gate. Drawn for content admins
 * ([FieldPermissions.isContentAdmin]: Admin, Master Admin, Ministry Admin) WHETHER OR NOT admin view
 * is on (owner, 2026-10-21) — [mayDelete], the gate every other delete on the handset reads — and for
 * nobody else; the server refuses everyone else regardless.
 */
object RecordDangerZone {
    const val TITLE = "Danger zone"
    const val CONFIRM_BODY = "This permanently deletes the record."
    const val CONFIRM = "Delete"
    const val CANCEL = "Cancel"
    const val DELETING = "Deleting…"
    const val FAILED = "Unable to delete (admin only)"

    fun body(noun: String): String = "Permanently deletes this $noun and its links."
    fun button(noun: String): String = "Delete this $noun"
    fun confirmTitle(noun: String): String = "Delete $noun?"

    /** Whether [user] is shown the card. Admin view does not enter into it. */
    fun visible(user: UserDto?): Boolean = mayDelete(user)
}

@Composable
fun RecordDangerZoneCard(
    /** The record's kind in lower case — "artisan", "media file". */
    noun: String,
    delete: suspend () -> Unit,
    onDeleted: () -> Unit,
    onError: (Throwable) -> Unit,
    /** In place of "Permanently deletes…", for a record that is not deleted permanently. */
    body: String? = null,
    /** In place of the confirmation's "This permanently deletes the record." */
    confirmBody: String? = null,
) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Canvas),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(RecordDangerZone.TITLE, display = true, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(body ?: RecordDangerZone.body(noun), color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { confirm = true },
                enabled = !deleting,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (deleting) RecordDangerZone.DELETING else RecordDangerZone.button(noun))
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirm = false },
            title = { Text(RecordDangerZone.confirmTitle(noun)) },
            text = { Text(confirmBody ?: RecordDangerZone.CONFIRM_BODY) },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        scope.launch {
                            deleting = true
                            runCatching { delete() }
                                .onSuccess { confirm = false; deleting = false; onDeleted() }
                                .onFailure { deleting = false; onError(it) }
                        }
                    }
                ) { Text(RecordDangerZone.CONFIRM, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { confirm = false }) { Text(RecordDangerZone.CANCEL) } }
        )
    }
}
