package com.fieldrepository.app.explorer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.explorer.data.ExplorerMore
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.fieldrepository.app.explorer.data.ExplorerReportOptions
import com.fieldrepository.app.explorer.data.ExplorerReportOptionsBody
import com.fieldrepository.app.explorer.data.ExplorerSheet
import com.fieldrepository.app.explorer.data.ExplorerView
import com.fieldrepository.app.explorer.data.ExplorerViewBody
import com.fieldrepository.app.explorer.data.ExplorerViewState
import com.fieldrepository.app.explorer.data.ExplorerViews
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object ReportText {
    const val sheets = "Sheets"
    const val answers = "Answers in"
    const val original = "Original"
    const val english = "English"
    const val both = "Both"
    const val rows = "Rows"
    const val everything = "Everything"
    const val links = "Links to files open in the portal"
    const val fileName = "File name"
    const val remember = "Remember these choices"
    const val remembered = "Saved for next time"
    const val cancel = "Cancel"
    const val make = "Make report"
    const val checked = "checked to open in Excel, Numbers and Google Sheets"
    const val savedViews = "Saved views"
    const val viewsNote = "A view keeps the folder, filters, search and tab. Share the link and others land on the same screen, if they have access."
    const val yours = "Yours"
    const val shared = "Shared"
    const val saveView = "Save current view"
    const val copyLink = "Copy link"
    const val copied = "✓ Link copied"
    const val noViews = "No saved views yet."
    const val viewSaved = "View saved"

    fun filtered(q: String) = "Only what's filtered ($q)"

    fun ready(sheets: Int) = "✓ ${if (sheets == 1) "1 sheet" else "$sheets sheets"} · $checked"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReportOptionsSheet(
    folderTitle: String,
    path: String,
    fileName: String,
    sheets: List<ExplorerSheet>,
    query: String,
    tab: ExplorerTab,
    more: ExplorerMore,
    onMake: (options: ExplorerReportOptions, name: String) -> Unit,
    onOpenView: (ExplorerView) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picked by remember(sheets) { mutableStateOf(sheets.map { it.name }.toSet()) }
    var answers by remember { mutableStateOf("both") }
    var rows by remember { mutableStateOf("all") }
    var links by remember { mutableStateOf(true) }
    var name by remember(fileName) { mutableStateOf(fileName) }
    var views by remember { mutableStateOf<ExplorerViews?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { more.reportOptions().options }.getOrNull()?.let { saved ->
            answers = saved.answers
            rows = if (saved.rows == "filtered" && query.isBlank()) "all" else saved.rows
            links = saved.links
            saved.sheets?.let { wanted -> picked = sheets.map { it.name }.filter { it in wanted }.toSet().ifEmpty { picked } }
        }
        views = try { more.views() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Throwable) { null }
    }

    fun options() = ExplorerReportOptions(
        sheets = if (picked.size == sheets.size || sheets.isEmpty()) null else sheets.map { it.name }.filter { it in picked },
        answers = answers,
        rows = rows,
        links = links,
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().testTag("explorer.report.sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(com.fieldrepository.app.explorer.data.ExplorerText.report, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
                Text(folderTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sheets.isNotEmpty()) {
                Label(ReportText.sheets)
                Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large)) {
                    sheets.forEach { s ->
                        val on = s.name in picked
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(value = on, role = Role.Checkbox) { picked = if (it) picked + s.name else picked - s.name }
                                .padding(horizontal = 12.dp)
                                .testTag("explorer.report.sheet.${s.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Text(s.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            Text("${s.totalRows ?: s.rows.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Label(ReportText.answers)
            Segmented(listOf("original" to ReportText.original, "english" to ReportText.english, "both" to ReportText.both), answers, "answers") { answers = it }
            if (query.isNotBlank()) {
                Label(ReportText.rows)
                Segmented(listOf("all" to ReportText.everything, "filtered" to ReportText.filtered(query)), rows, "rows") { rows = it }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = links, role = Role.Switch) { links = it }.testTag("explorer.report.links"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ReportText.links, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = links, onCheckedChange = null)
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(ReportText.fileName) },
                modifier = Modifier.fillMaxWidth().testTag("explorer.report.name"),
            )
            val count = if (sheets.isEmpty()) 0 else picked.size
            if (count > 0) Text(ReportText.ready(count), style = MaterialTheme.typography.bodySmall, color = explorerTone(ExplorerStatus.SAVED)!!.accent)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching { more.saveReportOptions(ExplorerReportOptionsBody(options())) }
                                .onSuccess { onMessage(ReportText.remembered) }
                                .onFailure { if (it is CancellationException) throw it }
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("explorer.report.remember"),
                ) { Text(ReportText.remember) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(ReportText.cancel) }
                Button(onClick = { onMake(options(), name) }, enabled = sheets.isEmpty() || picked.isNotEmpty(), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.report.make")) { Text(ReportText.make) }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(MaterialTheme.field.hairline))
            Text(ReportText.savedViews, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(ReportText.viewsNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val all = views?.let { it.mine + it.shared }.orEmpty()
            if (views != null && all.isEmpty()) Text(ReportText.noViews, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            all.forEach { v ->
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.field.surface50, MaterialTheme.shapes.large).clickable { onOpenView(v) }.padding(12.dp).testTag("explorer.view.${v.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(v.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(viewLine(v), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (v.mine) ReportText.yours else ReportText.shared, style = MaterialTheme.typography.labelMedium, color = if (v.mine) MaterialTheme.colorScheme.primary else Color(0xFF115E59))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val state = ExplorerViewState(view = tab.name.lowercase(), q = query.ifBlank { null })
                            runCatching { more.saveView(ExplorerViewBody(name = folderTitle, path = path, state = state, shared = true)) }
                                .onSuccess { saved ->
                                    views = (views ?: ExplorerViews()).let { it.copy(mine = listOf(saved) + it.mine) }
                                    onMessage(ReportText.viewSaved)
                                }
                                .onFailure { if (it is CancellationException) throw it }
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.view.save"),
                ) { Text(ReportText.saveView) }
                Button(
                    onClick = {
                        val first = views?.mine?.firstOrNull { it.path == path } ?: views?.mine?.firstOrNull()
                        val link = first?.let { "${ExplorerPortal.WEB_BASE}/data?viewId=${it.id}" } ?: ExplorerPaths.withQuery("${ExplorerPortal.WEB_BASE}/data", "path", path)
                        copy(context, link)
                        copied = link
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.view.copy"),
                ) { Text(ReportText.copyLink) }
            }
            if (copied != null) Text(ReportText.copied, style = MaterialTheme.typography.bodySmall, color = explorerTone(ExplorerStatus.SAVED)!!.accent)
        }
    }
}

private fun viewLine(v: ExplorerView): String = listOfNotNull(
    v.state.view.replaceFirstChar { it.uppercase() },
    v.state.sheet,
    v.state.q?.let { "“$it”" },
    v.path.substringAfterLast('/').takeIf { it.isNotBlank() },
).joinToString(" · ")

private fun copy(context: Context, text: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("link", text))
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Segmented(options: List<Pair<String, String>>, value: String, tag: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.medium).padding(3.dp)) {
        options.forEach { (key, label) ->
            val on = key == value
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.small)
                    .clickable(role = Role.RadioButton) { onPick(key) }
                    .testTag("explorer.seg.$tag.$key"),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, maxLines = 2, modifier = Modifier.padding(horizontal = 6.dp))
            }
        }
    }
}
