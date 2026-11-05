package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.explorer.data.ExplorerBytes
import com.fieldrepository.app.explorer.data.ExplorerPreflightPlan
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field

object PreflightText {
    const val metered = "You're on mobile data"
    const val waitForWifi = "Wait for Wi-Fi"
    const val waitNote = "Starts by itself when you're on Wi-Fi"
    const val storage = "Phone storage"
    const val enough = "Enough space"
    const val notEnough = "Not enough space"
    const val saveTo = "Save to"
    const val smaller = "Smaller download"
    const val notNow = "Not now"
    const val downloadOnWifi = "Download on Wi-Fi"
    const val download = "Download"
    const val shownWhen = "Shown only for downloads over 500 MB or on mobile data."

    fun title(name: String) = "Download $name"

    fun pack(size: String) = "$size could use most of a monthly pack."

    fun storageLine(size: String?, freeAfter: String?) =
        listOfNotNull(size?.let { "This download $it" }, freeAfter?.let { "$it free after" }).joinToString(" · ")

    fun skipVideos(size: String?, time: String?) = listOfNotNull("Skip videos", size, time).joinToString(" · ")

    fun headline(files: Int, size: String?, time: String?, metered: Boolean) = listOfNotNull(
        ExplorerBytes.count(files, "file"),
        size,
        time?.let { "about $it on this ${if (metered) "mobile data" else "Wi-Fi"}" },
    ).joinToString(" · ")

    fun minutes(seconds: Long?): String? = seconds?.let { s ->
        when {
            s < 60 -> "under a minute"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreflightSheet(
    title: String,
    plan: ExplorerPreflightPlan,
    onStart: (wifiOnly: Boolean, include: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var wait by rememberSaveable { mutableStateOf(plan.metered) }
    var smaller by rememberSaveable { mutableStateOf(false) }
    val skip = plan.smaller
    val bytes = if (smaller && skip != null) skip.bytes else plan.bytes
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().testTag("explorer.preflight"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(PreflightText.title(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
            Text(
                PreflightText.headline(if (smaller && skip != null) skip.files else plan.files, ExplorerBytes.format(bytes), PreflightText.minutes(if (smaller && skip != null) skip.seconds else plan.seconds), plan.metered),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (plan.metered) {
                val warn = explorerWarnTone()
                Column(
                    Modifier.fillMaxWidth().background(warn.container, MaterialTheme.shapes.large).border(1.dp, warn.border, MaterialTheme.shapes.large).padding(12.dp).testTag("explorer.preflight.metered"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(PreflightText.metered, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = warn.content)
                    ExplorerBytes.format(bytes)?.let { Text(PreflightText.pack(it), style = MaterialTheme.typography.bodySmall, color = warn.content) }
                }
            }
            SwitchRow(PreflightText.waitForWifi, PreflightText.waitNote, wait, "explorer.preflight.wifi") { wait = it }
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.field.surface50, MaterialTheme.shapes.large).padding(12.dp).testTag("explorer.preflight.storage"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(PreflightText.storage, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    val ok = plan.enoughSpace
                    val tone = explorerTone(if (ok) ExplorerStatus.SAVED else ExplorerStatus.FAILED)!!
                    Text(if (ok) PreflightText.enough else PreflightText.notEnough, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = tone.accent)
                }
                val free = plan.freeAfter
                if (free != null && bytes > 0) {
                    FieldLinearProgressIndicator(progress = { (bytes.toFloat() / (bytes + free.coerceAtLeast(0)).toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp))
                }
                Text(PreflightText.storageLine(ExplorerBytes.format(bytes), free?.let { ExplorerBytes.format(it.coerceAtLeast(0)) }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.field.surface50, MaterialTheme.shapes.large).padding(12.dp)) {
                Text(PreflightText.saveTo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(plan.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("explorer.preflight.location"))
            }
            if (skip != null) {
                SwitchRow(PreflightText.smaller, PreflightText.skipVideos(ExplorerBytes.format(skip.bytes), PreflightText.minutes(skip.seconds)), smaller, "explorer.preflight.smaller") { smaller = it }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.preflight.cancel")) { Text(PreflightText.notNow) }
                Button(
                    onClick = { onStart(wait, if (smaller) skip?.include else null) },
                    enabled = plan.enoughSpace || smaller,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.preflight.start"),
                ) { Text(if (wait) PreflightText.downloadOnWifi else PreflightText.download) }
            }
            Text(PreflightText.shownWhen, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ExplorerText.keepsGoing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, note: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    com.fieldrepository.app.ui.AppToggle(
        label = title,
        checked = on,
        onCheckedChange = onChange,
        description = note,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.field.surface50, MaterialTheme.shapes.large).testTag(tag),
    )
}
