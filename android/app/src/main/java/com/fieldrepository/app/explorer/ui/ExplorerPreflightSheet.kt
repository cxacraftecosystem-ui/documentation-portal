package com.fieldrepository.app.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
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
    const val meteredNote = "This could use most of a monthly pack."
    const val waitForWifi = "Wait for Wi-Fi"
    const val waitNote = "Starts by itself when you're on Wi-Fi"
    const val storage = "Phone storage"
    const val enough = "Enough space"
    const val notEnough = "Not enough space"
    const val saveTo = "Save to"
    const val change = "Change"
    const val useDownloads = "Use Downloads instead"
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
    onChangeFolder: (() -> Unit)? = null,
    onResetFolder: (() -> Unit)? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, shape = ExplorerShapes.sheet, containerColor = MaterialTheme.colorScheme.surface, dragHandle = { ExplorerSheetHandle() }) {
        PreflightBody(title, plan, onStart, onDismiss, onChangeFolder, onResetFolder, Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding())
    }
}

@Composable
private fun PreflightRow(modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surface, line: Color = explorerControlLine(), content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().explorerCard(container, line, ExplorerShapes.inner).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun RowText(title: String, note: String, modifier: Modifier, titleColor: Color = MaterialTheme.colorScheme.onSurface, noteColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, noteTag: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, lineHeight = 19.sp, fontWeight = FontWeight.Bold, color = titleColor)
        Text(note, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp, color = noteColor, modifier = if (noteTag != null) Modifier.testTag(noteTag) else Modifier)
    }
}

@Composable
internal fun PreflightBody(
    title: String,
    plan: ExplorerPreflightPlan,
    onStart: (wifiOnly: Boolean, include: String?) -> Unit,
    onDismiss: () -> Unit,
    onChangeFolder: (() -> Unit)? = null,
    onResetFolder: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var wait by rememberSaveable { mutableStateOf(plan.metered) }
    var smaller by rememberSaveable { mutableStateOf(false) }
    val skip = plan.smaller
    val bytes = if (smaller && skip != null) skip.bytes else plan.bytes
    val large = LocalDensity.current.fontScale >= 1.5f
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 20.dp).testTag("explorer.preflight"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(PreflightText.title(title), style = MaterialTheme.typography.titleLarge, lineHeight = 26.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        Text(
            PreflightText.headline(if (smaller && skip != null) skip.files else plan.files, ExplorerBytes.format(bytes), PreflightText.minutes(if (smaller && skip != null) skip.seconds else plan.seconds), plan.metered),
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (plan.metered) {
            val warn = explorerWarnTone()
            PreflightRow(Modifier.testTag("explorer.preflight.metered"), container = warn.container, line = warn.border) {
                Icon(Icons.Filled.SignalCellularAlt, contentDescription = null, tint = warn.muted, modifier = Modifier.size(20.dp))
                RowText(PreflightText.metered, ExplorerBytes.format(bytes)?.let { PreflightText.pack(it) } ?: PreflightText.meteredNote, Modifier.weight(1f), titleColor = warn.content, noteColor = warn.muted)
            }
        }
        PreflightRow(Modifier.toggleable(value = wait, role = Role.Switch, onValueChange = { wait = it }).testTag("explorer.preflight.wifi")) {
            RowText(PreflightText.waitForWifi, PreflightText.waitNote, Modifier.weight(1f))
            ExplorerToggle(wait, { wait = it }, Modifier.clearAndSetSemantics { })
        }
        Column(
            Modifier.fillMaxWidth().explorerCard(MaterialTheme.colorScheme.surface, explorerControlLine(), ExplorerShapes.inner).padding(horizontal = 14.dp, vertical = 12.dp).testTag("explorer.preflight.storage"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ok = plan.enoughSpace || smaller
            val tone = explorerTone(if (ok) ExplorerStatus.SAVED else ExplorerStatus.FAILED)!!
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(PreflightText.storage, style = MaterialTheme.typography.bodyMedium, lineHeight = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(if (ok) PreflightText.enough else PreflightText.notEnough, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tone.accent)
            }
            val free = plan.freeBytes
            if (free != null && bytes > 0) {
                val total = (plan.totalBytes ?: (free + bytes)).coerceAtLeast(free + 1)
                val used = ((total - free).toFloat() / total).coerceIn(0f, 1f)
                val mine = (bytes.toFloat() / total).coerceIn(0f, 1f - used)
                val usedColor = if (MaterialTheme.field.isDark) MaterialTheme.field.placeholder else Color(0xFFA7A3BC)
                Row(Modifier.fillMaxWidth().height(10.dp).clip(ExplorerShapes.pill).background(if (MaterialTheme.field.isDark) MaterialTheme.field.surface200 else Color(0xFFECE9F5))) {
                    if (used > 0f) Box(Modifier.fillMaxHeight().weight(used).background(usedColor))
                    if (mine > 0f) Box(Modifier.fillMaxHeight().weight(mine).background(if (ok) MaterialTheme.colorScheme.primary else tone.accent))
                    val rest = 1f - used - mine
                    if (rest > 0f) Spacer(Modifier.weight(rest))
                }
            }
            Text(
                PreflightText.storageLine(ExplorerBytes.format(bytes), plan.freeBytes?.let { ExplorerBytes.format((it - bytes).coerceAtLeast(0)) }),
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PreflightRow {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(PreflightText.saveTo, style = MaterialTheme.typography.bodyMedium, lineHeight = 19.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(plan.location, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("explorer.preflight.location"))
                if (onResetFolder != null) {
                    ExplorerLinkButton(PreflightText.useDownloads, onClick = onResetFolder, modifier = Modifier.heightIn(min = 40.dp).testTag("explorer.preflight.reset"))
                }
            }
            if (onChangeFolder != null) {
                ExplorerLinkButton(PreflightText.change, onClick = onChangeFolder, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.preflight.change"))
            }
        }
        if (skip != null) {
            PreflightRow(Modifier.toggleable(value = smaller, role = Role.Switch, onValueChange = { smaller = it }).testTag("explorer.preflight.smaller")) {
                RowText(PreflightText.smaller, PreflightText.skipVideos(ExplorerBytes.format(skip.bytes), PreflightText.minutes(skip.seconds)), Modifier.weight(1f))
                ExplorerToggle(smaller, { smaller = it }, Modifier.clearAndSetSemantics { })
            }
        }
        val notNow: @Composable (Modifier) -> Unit = { m ->
            ExplorerSecondaryButton(PreflightText.notNow, onClick = onDismiss, minHeight = 52.dp, style = MaterialTheme.typography.bodyLarge, shape = ExplorerShapes.inner, modifier = m.testTag("explorer.preflight.cancel"))
        }
        val start: @Composable (Modifier) -> Unit = { m ->
            ExplorerPrimaryButton(
                if (wait) PreflightText.downloadOnWifi else PreflightText.download,
                onClick = { onStart(wait, if (smaller) skip?.include else null) },
                enabled = plan.enoughSpace || smaller,
                minHeight = 52.dp,
                style = MaterialTheme.typography.bodyLarge,
                bold = true,
                shape = ExplorerShapes.inner,
                modifier = m.testTag("explorer.preflight.start"),
            )
        }
        if (large) {
            start(Modifier.fillMaxWidth().padding(top = 4.dp))
            notNow(Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                notNow(Modifier.weight(1f))
                start(Modifier.weight(2f))
            }
        }
        Text(PreflightText.shownWhen, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
