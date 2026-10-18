package com.fieldrepository.app.ui.questionnaire

import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.QuestionnaireCopy
import com.fieldrepository.app.recording.RecordingService
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.delay

/** Below about 500 MB the recorder warns with the minutes left at 64 kbps (8 000 bytes a second). */
internal fun spaceWarning(freeBytes: Long): String? =
    if (freeBytes >= 500L * 1024 * 1024) null else QuestionnaireCopy.spaceLeft((freeBytes / 8_000 / 60).coerceAtLeast(0))

/** Pause, resume and stop for the running take, its elapsed time, and the storage warning. */
@Composable
fun RecorderControl(state: RecordingService.State.Recording) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(state) {
        while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(500) }
    }
    val free = remember(now / 10_000) { runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE) }
    Surface(color = MaterialTheme.field.surface100, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(QuestionnaireCopy.notification(state.draft.label, state.elapsedMs(now)), style = MaterialTheme.typography.titleMedium)
            spaceWarning(free)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val paused = state.pausedAt != null
            PromptButtons(
                listOf(
                    PromptButton(QuestionnaireCopy.s(if (paused) "recorder.resume" else "recorder.pause")) {
                        if (paused) RecordingService.resume(context) else RecordingService.pause(context)
                    },
                    PromptButton(QuestionnaireCopy.s("recorder.stop"), primary = true) { RecordingService.stop(context) }
                )
            )
        }
    }
}

/**
 * "Answered by" after Stop (§3.4): every artisan in the roster preselected; unticking one makes them
 * PRESENT, never deleted. Remembers the last choice within a section through [initial].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnsweredByChips(roster: List<HeaderParticipant>, initial: Set<String>, onConfirm: (Set<String>) -> Unit) {
    var chosen by remember(roster) { mutableStateOf(initial.ifEmpty { roster.map { it.artisanId }.toSet() }) }
    Surface(color = MaterialTheme.field.surface50, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(QuestionnaireCopy.s("workspace.answeredBy"), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                roster.forEach { p ->
                    FilterChip(
                        selected = p.artisanId in chosen,
                        onClick = { chosen = if (p.artisanId in chosen) chosen - p.artisanId else chosen + p.artisanId },
                        label = { Text(QuestionnaireCopy.firstName(p.name)) }
                    )
                }
            }
            PromptButtons(listOf(PromptButton(QuestionnaireCopy.s("p5.ok"), primary = true, onClick = { onConfirm(chosen.ifEmpty { roster.map { it.artisanId }.toSet() }) })))
        }
    }
}
