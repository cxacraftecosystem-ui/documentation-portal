package com.fieldrepository.app.transcript.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.transcript.data.AudioIsolationSetting
import com.fieldrepository.app.transcript.data.BackfillView
import com.fieldrepository.app.transcript.data.BudgetMeter
import com.fieldrepository.app.transcript.data.TranscriptBackend
import com.fieldrepository.app.transcript.data.TranscriptionSwitchBody
import com.fieldrepository.app.transcript.data.TranscriptionSwitches
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranscriptUiText
import com.fieldrepository.app.transcript.portal.TranscriptPortal
import com.fieldrepository.app.ui.AppToggle
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The month's transcription spend, read only, where admins already see the AI settings (§4.7).
 * Setting the cap is web-only in the designer portal (ministry and admin authoring is), so the
 * phone shows the meter and never a control. Draws nothing for a reader the route refuses.
 */
@Composable
fun TranscriptBudgetCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var meter by remember { mutableStateOf<BudgetMeter?>(null) }
    LaunchedEffect(Unit) {
        meter = try {
            TranscriptPortal.backend(context).budget()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    meter?.let { BudgetMeterCard(it, modifier) }
}

@Composable
fun BudgetMeterCard(meter: BudgetMeter, modifier: Modifier = Modifier) {
    val cap = meter.monthlyUsd
    Column(
        modifier.fillMaxWidth().border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp)).padding(16.dp).testTag("transcript.budget"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(TranscriptStrings.budgetTitle, style = MaterialTheme.typography.titleMedium)
        if (cap != null && cap > 0) {
            val fraction = (meter.spentUsd / cap).toFloat().coerceIn(0f, 1f)
            val tone = when (meter.state) {
                "PAUSED" -> MaterialTheme.colorScheme.error
                "ALERT" -> MaterialTheme.field.warning
                else -> MaterialTheme.colorScheme.primary
            }
            FieldLinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = tone)
            Text(
                TranscriptStrings.budgetSpent(TranscriptUiText.usd(meter.spentUsd), TranscriptUiText.usd(cap)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.field.body
            )
        } else {
            Text(TranscriptUiText.usd(meter.spentUsd), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.body)
        }
        Text(
            "Scribe ${TranscriptUiText.usd(meter.sttUsd)} · Gemini ${TranscriptUiText.usd(meter.translationUsd)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.field.muted
        )
        when {
            meter.state == "PAUSED" || meter.pausedByBudget -> Text(TranscriptStrings.budgetPaused, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            meter.state == "ALERT" -> Text(TranscriptStrings.budgetAlert, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        }
    }
}

/**
 * Settings → API keys (0.0.5): ElevenLabs Voice Isolator on each newly transcribed recording. OFF
 * until a master admin turns it on; the original recording is always kept. Draws nothing for a
 * reader the route refuses. [canChange] is the master admin; the server refuses anyone else anyway.
 */
@Composable
fun AudioIsolationCard(canChange: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var setting by remember { mutableStateOf<AudioIsolationSetting?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        setting = try {
            TranscriptPortal.backend(context).audioIsolation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val current = setting ?: return
    AppToggle(
        label = "Remove background noise",
        description = when {
            current.enabled -> "On. Each new recording is cleaned with ElevenLabs Voice Isolator after it is transcribed. The original is kept."
            current.keyConfigured -> "Off. Turn on to clean new recordings with ElevenLabs Voice Isolator, billed by ElevenLabs."
            else -> "Off. Needs an ElevenLabs key with the Voice Isolator permission."
        },
        checked = current.enabled,
        enabled = canChange,
        busy = busy,
        onCheckedChange = { on ->
            busy = true
            scope.launch {
                setting = try {
                    TranscriptPortal.backend(context).setAudioIsolation(on) ?: current
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    current
                }
                busy = false
            }
        },
        modifier = modifier.testTag("transcript.isolation"),
    )
}

@Composable
fun TranscriptAutoSwitchesCard(canChange: Boolean, modifier: Modifier = Modifier, backend: TranscriptBackend? = null) {
    val context = LocalContext.current
    val server = remember(backend) { backend ?: TranscriptPortal.backend(context) }
    val scope = rememberCoroutineScope()
    var switches by remember { mutableStateOf<TranscriptionSwitches?>(null) }
    var backfill by remember { mutableStateOf<BackfillView?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(server) {
        switches = try {
            server.transcriptionSwitches()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (switches != null) {
            backfill = try {
                server.backfill()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    val current = switches ?: return

    fun save(key: String, body: TranscriptionSwitchBody) {
        busy = key
        scope.launch {
            val updated = try {
                server.setTranscriptionSwitches(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (updated != null) switches = updated
            busy = null
        }
    }

    val estimate = backfill?.estimate
    val progress = backfill?.progress ?: current.backfill
    Column(modifier.fillMaxWidth().testTag("transcript.autoSwitches"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppToggle(
            label = TranscriptStrings.settingsAutoVideo,
            description = if (current.autoTranscribe.video) TranscriptStrings.settingsAutoVideoOn else TranscriptStrings.settingsAutoVideoOff,
            checked = current.autoTranscribe.video,
            enabled = canChange,
            busy = busy == "video",
            onCheckedChange = { on -> save("video", TranscriptionSwitchBody(autoTranscribeVideo = on)) },
            modifier = Modifier.testTag("transcript.autoVideo"),
        )
        AppToggle(
            label = TranscriptStrings.settingsAutoAudio,
            description = if (current.autoTranscribe.audio) TranscriptStrings.settingsAutoAudioOn else TranscriptStrings.settingsAutoAudioOff,
            checked = current.autoTranscribe.audio,
            enabled = canChange,
            busy = busy == "audio",
            onCheckedChange = { on -> save("audio", TranscriptionSwitchBody(autoTranscribeAudio = on)) },
            modifier = Modifier.testTag("transcript.autoAudio"),
        )
        AppToggle(
            label = TranscriptStrings.settingsBackfill,
            description = if (current.backfill.enabled) TranscriptStrings.settingsBackfillOn else TranscriptStrings.settingsBackfillOff,
            checked = current.backfill.enabled,
            enabled = canChange && (current.backfill.enabled || estimate != null),
            busy = busy == "backfill",
            onCheckedChange = { on ->
                if (on) confirming = true else save("backfill", TranscriptionSwitchBody(transcriptBackfill = false))
            },
            modifier = Modifier.testTag("transcript.backfill"),
        )
        Text(
            estimate?.let { backfillEstimateText(it.recordings, it.hours, it.costUsd) } ?: TranscriptStrings.settingsBackfillCounting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.field.muted,
            modifier = Modifier.testTag("transcript.backfill.estimate"),
        )
        if (progress.enabled || progress.queued > 0) {
            Text(
                TranscriptStrings.settingsBackfillProgress(progress.done, progress.queued, progress.waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted,
                modifier = Modifier.testTag("transcript.backfill.progress"),
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(TranscriptStrings.settingsBackfillConfirmTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    estimate?.let { Text(backfillEstimateText(it.recordings, it.hours, it.costUsd), style = MaterialTheme.typography.bodyMedium) }
                    Text(TranscriptStrings.settingsBackfillConfirmBody, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        save("backfill", TranscriptionSwitchBody(transcriptBackfill = true, confirmBackfill = true))
                    },
                    modifier = Modifier.testTag("transcript.backfill.confirm"),
                ) { Text(TranscriptStrings.settingsBackfillConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }, modifier = Modifier.testTag("transcript.backfill.cancel")) {
                    Text(TranscriptStrings.settingsBackfillCancel)
                }
            },
        )
    }
}

fun backfillEstimateText(count: Int, hours: Double, costUsd: Double): String =
    TranscriptStrings.settingsBackfillEstimate(count, String.format(java.util.Locale.ROOT, "%.1f", hours), String.format(java.util.Locale.ROOT, "%.2f", costUsd))
