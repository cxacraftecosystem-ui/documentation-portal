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

fun contentAdminChrome(user: UserDto?, adminView: Boolean): Boolean =
    FieldPermissions.isContentAdmin(user) && adminView

fun mayDelete(user: UserDto?): Boolean = FieldPermissions.isContentAdmin(user)

fun mayEditComment(comment: EntryCommentDto, viewerId: String?): Boolean =
    viewerId != null && comment.authorId == viewerId

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
