package com.fieldrepository.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import kotlinx.coroutines.launch

internal fun heldForAnotherAccountTitle(): String = "Saved by another account"

internal fun heldForAnotherAccountBody(count: Int): String {
    val items = if (count == 1) "1 offline item" else "$count offline items"
    val pronoun = if (count == 1) "it" else "them"
    return "$items on this phone weren't saved under your account, so they won't be sent as you. Keep $pronoun for that account, or discard."
}

@Composable
fun HeldForAnotherAccountPrompt(signedInUserId: String?) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    var held by remember(signedInUserId) { mutableIntStateOf(0) }
    LaunchedEffect(signedInUserId) {
        held = 0
        if (signedInUserId.isNullOrBlank()) return@LaunchedEffect
        held = runCatching { QuestionnaireV2.get(app).consentsHeldForAnotherAccount() }.getOrDefault(0)
    }
    if (held <= 0) return
    AlertDialog(
        onDismissRequest = { held = 0 },
        title = { Text(heldForAnotherAccountTitle()) },
        text = { Text(heldForAnotherAccountBody(held)) },
        confirmButton = { TextButton(onClick = { held = 0 }) { Text("Keep") } },
        dismissButton = {
            TextButton(
                onClick = {
                    held = 0
                    scope.launch {
                        runCatching { QuestionnaireV2.get(app).discardConsentsHeldForAnotherAccount() }
                    }
                }
            ) { Text("Discard") }
        },
    )
}
