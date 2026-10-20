package com.fieldrepository.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.EntryCommentDto
import com.fieldrepository.app.data.UserDto

/**
 * Whether this account, in this view, is offered every delete: a content admin
 * ([FieldPermissions.isContentAdmin], this portal's content-admin roles) with admin view on. The
 * handset's `adminMode`, and the value [LocalContentAdminChrome] carries (owner's ruling, 2026-10-20).
 */
fun contentAdminChrome(user: UserDto?, adminView: Boolean): Boolean =
    FieldPermissions.isContentAdmin(user) && adminView

/** The author edits their own comment, and nobody else does — `update_comment`'s rule, and the web's. */
fun mayEditComment(comment: EntryCommentDto, viewerId: String?): Boolean =
    viewerId != null && comment.authorId == viewerId

/**
 * One comment on a record: the words, who and when, the author's Edit and — in admin view, for a
 * content admin — Remove. The web twin is a row of `components/CollabPanel.tsx`.
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
    val mayRemove = viewerId != null && LocalContentAdminChrome.current
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
            TextButton(enabled = !busy, onClick = onRemove) { Text("Remove", style = MaterialTheme.typography.labelMedium) }
        }
    }
}
