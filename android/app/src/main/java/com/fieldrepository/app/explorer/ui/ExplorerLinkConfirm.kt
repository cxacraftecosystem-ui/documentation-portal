package com.fieldrepository.app.explorer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

internal fun explorerLinkSavesFiles(request: ExplorerLinkRequest): Boolean =
    request.go.firstOrNull() == "media" || (request.go.isEmpty() && request.bundle != null)

internal object ExplorerLinkConfirmText {
    const val title = "Download from this link?"
    const val body = "A link asked to save files to this phone. Only continue if you opened it yourself."
    const val confirm = "Download"
    const val cancel = "Cancel"
}

@Composable
internal fun ExplorerLinkSaveConfirm(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(ExplorerLinkConfirmText.title) },
        text = { Text(ExplorerLinkConfirmText.body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(ExplorerLinkConfirmText.confirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(ExplorerLinkConfirmText.cancel) } },
    )
}
