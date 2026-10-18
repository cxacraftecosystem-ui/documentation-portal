package com.fieldrepository.app.ui.questionnaire

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.JoinQr
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.ui.field

data class HeaderParticipant(val artisanId: String, val name: String, val present: Boolean)

/**
 * Title (generated, never typed), the note under it, the join code and QR, and the participant strip
 * with Present / Away per artisan and "+ Add artisan" (DESIGN §4.3).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionHeader(
    title: String,
    note: String?,
    joinCode: String?,
    joinQr: String?,
    participants: List<HeaderParticipant>,
    locked: Boolean,
    onTogglePresent: (artisanId: String, present: Boolean) -> Unit,
    onAddArtisan: () -> Unit,
    onFinish: () -> Unit
) {
    var showQr by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!locked) TextButton(onClick = onFinish) { Text(QuestionnaireCopy.s("workspace.finish")) }
        }
        note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted) }
        val joinLine = listOfNotNull(
            participants.joinToString(", ") { QuestionnaireCopy.firstName(it.name) }.ifBlank { null },
            joinCode?.let { "${QuestionnaireCopy.s("workspace.joinCode")} $it" }
        ).joinToString(" · ")
        if (joinLine.isNotBlank()) Text(joinLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            participants.forEach { p ->
                FilterChip(
                    selected = p.present,
                    onClick = { onTogglePresent(p.artisanId, !p.present) },
                    label = {
                        Text(QuestionnaireCopy.firstName(p.name) + if (p.present) "" else " · ${QuestionnaireCopy.s("workspace.away")}")
                    }
                )
            }
            if (!locked) TextButton(onClick = onAddArtisan) { Text(QuestionnaireCopy.s("workspace.addArtisan")) }
            if (joinQr != null) TextButton(onClick = { showQr = true }) { Text("QR") }
        }
    }
    if (showQr && joinQr != null) {
        val bitmap = remember(joinQr) { qrBitmap(joinQr) }
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text(QuestionnaireCopy.s("sessions.join"), style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(bitmap.asImageBitmap(), contentDescription = QuestionnaireCopy.s("join.scan"), modifier = Modifier.size(240.dp))
                    joinCode?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                }
            },
            confirmButton = { PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("p5.ok"), primary = true) { showQr = false })) }
        )
    }
}

internal fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = JoinQr.matrix(text, size)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
