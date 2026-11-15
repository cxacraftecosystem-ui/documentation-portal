package com.fieldrepository.app.questionnaire.ui

import android.os.StatFs
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.recording.RecordingService
import com.fieldrepository.app.ui.FieldCheckbox
import com.fieldrepository.app.ui.FieldTextStyles
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.delay

internal fun spaceWarning(freeBytes: Long): String? =
    if (freeBytes >= 500L * 1024 * 1024) null else QuestionnaireCopy.spaceLeft((freeBytes / 8_000 / 60).coerceAtLeast(0))

internal fun recordingLabel(label: String): String =
    if (label.startsWith("Section ")) QuestionnaireCopy.t("recordingSection", "code" to label.removePrefix("Section "))
    else QuestionnaireCopy.t("recordingTarget", "target" to label)

internal fun clockText(elapsedMs: Long): String {
    val total = elapsedMs.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}

private const val WAVE_BARS = 36

@Composable
fun RecorderControl(state: RecordingService.State.Recording, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(state) {
        while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(250) }
    }
    val level by RecordingService.level.collectAsState()
    val bars = remember { mutableStateListOf<Float>().apply { repeat(WAVE_BARS) { add(0f) } } }
    LaunchedEffect(level) {
        bars.removeAt(0)
        bars.add(level)
    }
    val free = remember(now / 10_000) { runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE) }
    val red = MaterialTheme.colorScheme.error
    val paused = state.pausedAt != null
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth().testTag("questionnaire.recorder")) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(red))
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(red))
                        Text(
                            recordingLabel(state.draft.label), color = red, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        clockText(state.elapsedMs(now)),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { contentDescription = QuestionnaireCopy.elapsed(state.elapsedMs(now)) }
                    )
                }
                Waveform(bars, red, Modifier.fillMaxWidth().height(38.dp))
                spaceWarning(free)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.warning, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    OutlinedIconButton(
                        onClick = { if (paused) RecordingService.resume(context) else RecordingService.pause(context) },
                        modifier = Modifier.size(56.dp),
                        border = BorderStroke(1.dp, MaterialTheme.field.surface300),
                        colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = MaterialTheme.field.surface100, contentColor = MaterialTheme.colorScheme.onSurface)
                    ) {
                        Icon(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = QuestionnaireCopy.s(if (paused) "recorder.resume" else "recorder.pause")
                        )
                    }
                    FilledIconButton(
                        onClick = { RecordingService.stop(context) },
                        modifier = Modifier.size(84.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = red, contentColor = MaterialTheme.colorScheme.background)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = QuestionnaireCopy.s("recorder.stop"), modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.size(56.dp))
                }
            }
        }
    }
}

@Composable
private fun Waveform(levels: List<Float>, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Canvas(modifier) {
        val bar = 4.dp.toPx()
        val gap = 3.dp.toPx()
        val count = levels.size
        val total = count * bar + (count - 1) * gap
        var x = (size.width - total) / 2
        val min = 6.dp.toPx()
        levels.forEach { l ->
            val h = (min + (size.height - min) * l).coerceAtMost(size.height)
            drawRoundRect(color, topLeft = Offset(x, (size.height - h) / 2), size = Size(bar, h), cornerRadius = CornerRadius(bar / 2))
            x += bar + gap
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnsweredBySheet(
    participants: List<HeaderParticipant>,
    takeLine: String,
    initial: Set<String>,
    onNotNow: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val present = participants.filter { it.present }.map { it.artisanId }.toSet()
    var chosen by remember(participants) { mutableStateOf(initial.intersect(present).ifEmpty { present }) }
    ModalBottomSheet(
        onDismissRequest = onNotNow,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(QuestionnaireCopy.s("workspace.answeredBy").uppercase(), style = FieldTextStyles.FieldLabel, color = MaterialTheme.field.muted)
                Text(
                    QuestionnaireCopy.t("whoAnsweredTitle"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(takeLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
            }
            Text(QuestionnaireCopy.t("presentArePicked"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                participants.forEach { p ->
                    val on = p.artisanId in chosen
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clip(shape)
                            .background(if (on) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.field.surface300, shape)
                            .toggleable(value = on, enabled = p.present, role = Role.Checkbox) {
                                chosen = if (on) chosen - p.artisanId else chosen + p.artisanId
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.field.surface200),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                initials(p.name), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.field.body
                            )
                        }
                        Text(
                            p.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.field.body
                        )
                        if (!p.present) Text(
                            QuestionnaireCopy.s("workspace.away"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.field.muted,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.field.surface100).padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                        FieldCheckbox(checked = on, onCheckedChange = null, enabled = p.present)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onNotNow, modifier = Modifier.weight(1f).height(56.dp),
                    border = BorderStroke(1.dp, MaterialTheme.field.surface300)
                ) { Text(QuestionnaireCopy.t("notNow"), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) }
                Button(
                    onClick = { onSave(chosen.ifEmpty { present }) }, modifier = Modifier.weight(1f).height(56.dp)
                ) { Text(QuestionnaireCopy.t("saveTake"), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

internal fun initials(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
