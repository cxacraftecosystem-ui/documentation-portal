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
import com.fieldrepository.app.ui.FieldCheckbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.fieldrepository.app.explorer.data.ExplorerReportSheets
import com.fieldrepository.app.explorer.data.ExplorerShare
import com.fieldrepository.app.explorer.data.ExplorerShareBody

object ReportText {
    const val sheets = "Sheets"
    const val answers = "Answers in"
    const val original = "Original"
    const val english = "English"
    const val both = "Both"
    const val rows = "Rows"
    const val everything = "Everything"
    const val links = "Links to files open in the portal"
    const val thumbnails = "Small image thumbnails"
    const val thumbnailsNote = "Makes the file larger"
    const val language = "Headings in"
    const val extras = "Extras"
    const val fileName = "File name"
    const val remember = "Remember these choices"
    const val remembered = "Saved for next time"
    const val cancel = "Cancel"
    const val make = "Make report (.xlsx)"
    const val pack = "Report pack (.zip)"
    const val packNote = "The report, a PDF summary, every transcript as .docx and .srt, and a README"
    const val checked = "checked to open in Excel, Numbers and Google Sheets"
    const val liveLinks = "Live links"
    const val liveNote = "A read-only page with the cover and charts. It expires, can be revoked, and shows only what you can see."
    const val createLink = "Create live link"
    const val expiresIn = "Expires after"
    const val openLink = "Open"
    const val revoke = "Revoke"
    const val revoked = "Link revoked"
    const val noLinks = "No live links for this folder."
    const val savedViews = "Saved views"
    const val viewsNote = "A view keeps the folder, filters, search and tab. Share the link and others land on the same screen, if they have access."
    const val yours = "Yours"
    const val shared = "Shared"
    const val saveView = "Save current view"
    const val copyLink = "Copy link"
    const val copied = "✓ Link copied"
    const val noViews = "No saved views yet."
    const val viewSaved = "View saved"
    const val linkFailed = "That didn't work. Try again."
    const val filters = "Filters"
    const val noFilters = "None. Every record you can see."
    const val allStatuses = "Include all statuses"
    const val nothingMatches = "No records match these filters."
    const val makeUnfiltered = "Make the unfiltered report"
    const val reviewStatus = "Review status"
    const val search = "Search"

    fun withoutFilters(line: String) = "Without filters: $line"

    fun filtered(q: String) = "Only what's filtered ($q)"

    fun ready(sheets: Int) = "✓ ${if (sheets == 1) "1 sheet" else "$sheets sheets"} · $checked"

    fun days(n: Int) = if (n == 1) "1 day" else "$n days"

    fun until(at: String?) = at?.take(10)?.let { "Until $it" }

    fun localeLabel(code: String) = when (code) {
        "hi" -> "हिन्दी"
        "en" -> "English"
        else -> code
    }
}

internal object ReportFilters {
    fun chips(statusLabel: String?, allStatuses: Boolean, search: String): List<Pair<String, String>> = buildList {
        if (statusLabel != null && !allStatuses) add(ReportText.reviewStatus to statusLabel)
        if (search.isNotBlank()) add(ReportText.search to search.trim())
    }

    fun statusLabel(status: String?, options: List<Pair<String, String>>): String? =
        status?.let { value -> options.firstOrNull { it.first == value }?.second ?: value }

    fun recordLine(list: ExplorerReportSheets?): String =
        list?.sheets.orEmpty().filter { it.record && it.count != null }.joinToString(" · ") { "${it.name} ${it.count}" }

    fun leavesNothing(list: ExplorerReportSheets?, filtered: Boolean): Boolean = filtered && list != null && list.records == 0
}

internal data class ReportSheetChoice(val key: String, val name: String, val count: Int?, val group: String?, val description: String?)

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
    onMake: (options: ExplorerReportOptions, name: String, status: String?) -> Unit,
    onOpenView: (ExplorerView) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    status: String? = null,
    onMakePack: ((options: ExplorerReportOptions, name: String, status: String?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var listed by remember(path) { mutableStateOf<ExplorerReportSheets?>(null) }
    var unfiltered by remember(path) { mutableStateOf<ExplorerReportSheets?>(null) }
    var allStatuses by remember(path, status) { mutableStateOf(status == null) }
    val statusLabel = ReportFilters.statusLabel(status, ExplorerPortal.statusOptions)
    val choices = listed?.sheets?.map { ReportSheetChoice(it.key.ifBlank { it.name }, it.name, it.count, it.group, it.description) }
        ?: sheets.map { ReportSheetChoice(it.name, it.name, it.totalRows ?: it.rows.size, null, null) }
    var picked by remember(choices.map { it.key }) { mutableStateOf(choices.map { it.key }.toSet()) }
    var answers by remember { mutableStateOf("both") }
    var rows by remember { mutableStateOf("all") }
    var links by remember { mutableStateOf(true) }
    var thumbnails by remember { mutableStateOf(false) }
    var locale by remember { mutableStateOf("en") }
    var extras by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var name by remember(fileName) { mutableStateOf(fileName) }
    var views by remember { mutableStateOf<ExplorerViews?>(null) }
    var shares by remember(path) { mutableStateOf<List<ExplorerShare>?>(null) }
    var expiry by remember { mutableStateOf("7") }
    var copied by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<ExplorerReportOptions?>(null) }
    val chosenStatus = if (allStatuses) null else status
    val search = if (rows == "filtered") query.trim() else ""
    val chips = ReportFilters.chips(statusLabel, allStatuses, search)
    val empty = ReportFilters.leavesNothing(listed, chips.isNotEmpty())

    LaunchedEffect(path, chosenStatus, search) {
        listed = try { more.reportSheets(path, chosenStatus, search.ifBlank { null }) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Throwable) { null }
    }
    LaunchedEffect(path, empty) {
        if (!empty || unfiltered != null) return@LaunchedEffect
        unfiltered = try { more.reportSheets(path, null, null) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Throwable) { null }
    }
    LaunchedEffect(path) {
        saved = runCatching { more.reportOptions().options }.getOrNull()
        shares = try { more.shares(path).items } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Throwable) { null }
        views = try { more.views() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Throwable) { null }
    }
    LaunchedEffect(saved, choices.map { it.key }) {
        val s = saved ?: return@LaunchedEffect
        answers = s.answers
        rows = if (s.rows == "filtered" && query.isBlank()) "all" else s.rows
        links = s.links
        thumbnails = s.thumbnails
        locale = s.locale
        extras = s.extras
        s.sheets?.let { wanted -> picked = choices.filter { it.key in wanted || it.name in wanted }.map { it.key }.toSet().ifEmpty { picked } }
    }

    fun options() = ExplorerReportOptions(
        sheets = if (picked.size == choices.size || choices.isEmpty()) null else choices.map { it.key }.filter { it in picked },
        answers = answers,
        rows = rows,
        links = links,
        thumbnails = thumbnails,
        locale = locale,
        extras = extras.filterValues { it },
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
            Column(
                Modifier.fillMaxWidth().border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large).padding(12.dp).testTag("explorer.report.filters"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Label(ReportText.filters)
                if (chips.isEmpty()) {
                    Text(ReportText.noFilters, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        chips.forEach { (label, value) ->
                            Text(
                                "$label: $value",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge).padding(horizontal = 10.dp, vertical = 6.dp).testTag("explorer.report.filter.$label"),
                            )
                        }
                    }
                }
                if (status != null) {
                    com.fieldrepository.app.ui.AppToggle(label = ReportText.allStatuses, checked = allStatuses, onCheckedChange = { allStatuses = it }, modifier = Modifier.fillMaxWidth().testTag("explorer.report.allStatuses"))
                }
            }
            if (empty) {
                val tone = explorerWarnTone()
                Column(
                    Modifier.fillMaxWidth().background(tone.container, MaterialTheme.shapes.large).border(1.dp, tone.border, MaterialTheme.shapes.large).padding(12.dp).testTag("explorer.report.empty"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(ReportText.nothingMatches, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tone.content)
                    ReportFilters.recordLine(listed).takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = tone.content) }
                    ReportFilters.recordLine(unfiltered).takeIf { it.isNotBlank() }?.let { Text(ReportText.withoutFilters(it), style = MaterialTheme.typography.bodySmall, color = tone.content) }
                    Button(
                        onClick = { onMake(options().copy(rows = "all"), name, null) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("explorer.report.unfiltered"),
                    ) { Text(ReportText.makeUnfiltered) }
                }
            }
            if (choices.isNotEmpty()) {
                Label(ReportText.sheets)
                Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.large)) {
                    var lastGroup: String? = null
                    choices.forEach { s ->
                        if (s.group != null && s.group != lastGroup) {
                            Text(s.group, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 10.dp))
                        }
                        lastGroup = s.group
                        val on = s.key in picked
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(value = on, role = Role.Checkbox) { picked = if (it) picked + s.key else picked - s.key }
                                .padding(horizontal = 12.dp)
                                .testTag("explorer.report.sheet.${s.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FieldCheckbox(checked = on, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                                Text(s.name, style = MaterialTheme.typography.bodyMedium)
                                s.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            s.count?.let { Text("$it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            val locales = listed?.locales?.takeIf { it.size > 1 }
            if (locales != null) {
                Label(ReportText.language)
                Segmented(locales.map { it to ReportText.localeLabel(it) }, locale, "locale") { locale = it }
            }
            com.fieldrepository.app.ui.AppToggle(label = ReportText.links, checked = links, onCheckedChange = { links = it }, modifier = Modifier.fillMaxWidth().testTag("explorer.report.links"))
            com.fieldrepository.app.ui.AppToggle(label = ReportText.thumbnails, checked = thumbnails, onCheckedChange = { thumbnails = it }, description = ReportText.thumbnailsNote, modifier = Modifier.fillMaxWidth().testTag("explorer.report.thumbnails"))
            val offered = listed?.extras.orEmpty()
            if (offered.isNotEmpty()) {
                Label(ReportText.extras)
                offered.forEach { x ->
                    com.fieldrepository.app.ui.AppToggle(
                        label = x.title,
                        checked = extras[x.key] == true,
                        onCheckedChange = { on -> extras = extras + (x.key to on) },
                        modifier = Modifier.fillMaxWidth().testTag("explorer.report.extra.${x.key}"),
                    )
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(ReportText.fileName) },
                modifier = Modifier.fillMaxWidth().testTag("explorer.report.name"),
            )
            val count = if (choices.isEmpty()) 0 else picked.size
            if (count > 0) Text(ReportText.ready(count), style = MaterialTheme.typography.bodySmall, color = explorerTone(ExplorerStatus.SAVED)!!.accent)
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(ReportText.cancel) }
                Button(onClick = { onMake(options(), name, chosenStatus) }, enabled = choices.isEmpty() || picked.isNotEmpty(), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("explorer.report.make")) { Text(ReportText.make, maxLines = 2) }
            }
            if (onMakePack != null) {
                OutlinedButton(
                    onClick = { onMakePack(options(), name.removeSuffix(".xlsx"), chosenStatus) },
                    enabled = choices.isEmpty() || picked.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("explorer.report.pack"),
                ) { Text(ReportText.pack) }
                Text(ReportText.packNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(MaterialTheme.field.hairline))
            Text(ReportText.liveLinks, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(ReportText.liveNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val active = shares.orEmpty().filter { it.state == "active" }
            if (shares != null && active.isEmpty()) Text(ReportText.noLinks, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            active.forEach { link ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.field.surface50, MaterialTheme.shapes.large).padding(12.dp).testTag("explorer.share.${link.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(link.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ReportText.until(link.expiresAt)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { openShare(context, link.url) }, modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.share.open.${link.id}")) { Text(ReportText.openLink) }
                        TextButton(onClick = { copy(context, link.url); copied = link.url }, modifier = Modifier.heightIn(min = 44.dp)) { Text(ReportText.copyLink) }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching { more.revokeShare(link.id) }
                                        .onSuccess { shares = shares.orEmpty().filterNot { it.id == link.id }; onMessage(ReportText.revoked) }
                                        .onFailure { if (it is CancellationException) throw it; onMessage(ReportText.linkFailed) }
                                }
                            },
                            modifier = Modifier.heightIn(min = 44.dp).testTag("explorer.share.revoke.${link.id}"),
                        ) { Text(ReportText.revoke, color = explorerTone(ExplorerStatus.FAILED)!!.accent) }
                    }
                }
            }
            Label(ReportText.expiresIn)
            Segmented(listOf("1" to ReportText.days(1), "7" to ReportText.days(7), "30" to ReportText.days(30)), expiry, "expiry") { expiry = it }
            Button(
                onClick = {
                    scope.launch {
                        runCatching { more.createShare(ExplorerShareBody(path = path, options = options(), filters = chosenStatus?.let { mapOf("status" to it) }, expiresInDays = expiry.toInt())) }
                            .onSuccess { made -> shares = listOf(made) + shares.orEmpty(); copy(context, made.url); copied = made.url }
                            .onFailure { if (it is CancellationException) throw it; onMessage(ReportText.linkFailed) }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("explorer.share.create"),
            ) { Text(ReportText.createLink) }
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
                        Text(v.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                .onSuccess { made ->
                                    views = (views ?: ExplorerViews()).let { it.copy(mine = listOf(made) + it.mine) }
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

internal fun openShare(context: Context, url: String) {
    val uri = android.net.Uri.parse(url)
    val tab = runCatching {
        androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        true
    }.getOrDefault(false)
    if (!tab) {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
