package com.fieldrepository.app.transcript.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.transcript.data.AudioIsolationSetting
import com.fieldrepository.app.transcript.data.BudgetMeter
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranscriptUiText
import com.fieldrepository.app.transcript.portal.TranscriptPortal
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
    Row(
        modifier.fillMaxWidth().border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp)).padding(16.dp).testTag("transcript.isolation"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Remove background noise", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    current.enabled -> "On. Each new recording is cleaned with ElevenLabs Voice Isolator after it is transcribed. The original is kept."
                    current.keyConfigured -> "Off. Turn on to clean new recordings with ElevenLabs Voice Isolator, billed by ElevenLabs."
                    else -> "Off. Needs an ElevenLabs key with the Voice Isolator permission."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted
            )
        }
        Switch(
            checked = current.enabled,
            enabled = canChange && !busy,
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
            modifier = Modifier.testTag("transcript.isolation.switch")
        )
    }
}
