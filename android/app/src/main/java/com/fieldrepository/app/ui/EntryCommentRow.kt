package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.EntryCommentDto
import com.fieldrepository.app.data.UserDto

/**
 * A content admin ([FieldPermissions.isContentAdmin], this portal's content-admin roles) with admin
 * view on: the handset's `adminMode`, and the value [LocalContentAdminChrome] carries. Admin-view
 * chrome only — no delete reads it any more (owner, 2026-10-21); deletes read [mayDelete].
 */
fun contentAdminChrome(user: UserDto?, adminView: Boolean): Boolean =
    FieldPermissions.isContentAdmin(user) && adminView

/**
 * Whether this account is offered every delete — record, row, file, comment, section, take: a content
 * admin ([FieldPermissions.isContentAdmin]: Admin, Master Admin, Ministry Admin), WHETHER OR NOT
 * admin view is on (owner, 2026-10-21), and nobody else; the server refuses everyone else regardless.
 * The value [LocalMayDelete] carries, and the web's `canDeleteRecords`.
 */
fun mayDelete(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

/** The author edits their own comment, and nobody else does — `update_comment`'s rule, and the web's. */
fun mayEditComment(comment: EntryCommentDto, viewerId: String?): Boolean =
    viewerId != null && comment.authorId == viewerId

/**
 * One comment on a record: the words, who and when, the author's Edit and — for a content admin,
 * admin view or not — Remove. The web twin is a row of `components/CollabPanel.tsx`.
 */
@Composable
fun EntryCommentRow(
    comment: EntryCommentDto,
    viewerId: String?,
    busy: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mayRemove = viewerId != null && LocalMayDelete.current
    var confirmRemove by remember(comment.id) { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.weight(1f)) {
            Text(comment.body, color = Body, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${comment.author?.name ?: "Someone"} · ${comment.createdAt.take(10)}",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (mayEditComment(comment, viewerId)) {
            TextButton(enabled = !busy, onClick = onEdit) { Text("Edit", style = MaterialTheme.typography.labelMedium) }
        }
        if (mayRemove) {
            TextButton(enabled = !busy, onClick = { confirmRemove = true }) { Text("Remove", style = MaterialTheme.typography.labelMedium) }
        }
    }
    // Asked first, as the web's CollabPanel asks: a removed comment is gone for everyone.
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(COMMENT_REMOVE_TITLE) },
            text = { Text(COMMENT_REMOVE_BODY) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}

const val COMMENT_REMOVE_TITLE = "Delete this comment?"
const val COMMENT_REMOVE_BODY = "It is removed for everyone who can see this record. This cannot be undone."
