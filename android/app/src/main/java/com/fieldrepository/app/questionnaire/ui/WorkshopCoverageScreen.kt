package com.fieldrepository.app.questionnaire.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import com.fieldrepository.app.questionnaire.data.CoverageRecordings
import com.fieldrepository.app.transcript.ui.TranscriptDialog
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.questionnaire.data.QFormOption
import com.fieldrepository.app.questionnaire.data.QGrid
import com.fieldrepository.app.questionnaire.data.QGridCell
import com.fieldrepository.app.questionnaire.data.QWorkshopOption
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import com.fieldrepository.app.questionnaire.portal.QuestionnairePortal
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.SearchableSelectField
import com.fieldrepository.app.ui.SelectOption
import com.fieldrepository.app.ui.field

/** A server grid cell as the session grid draws it: ● recorded, ✎ typed, an override wins (D1, D4). */
internal fun cellOf(cell: QGridCell): GridCell {
    val mark = when {
        cell.needsDecision -> CellMark.NEEDS_DECISION
        cell.source == "RECORDED" && cell.covered >= cell.total && cell.total > 0 || cell.state == "RECORDED" -> CellMark.RECORDED
        cell.source == "RECORDED" -> CellMark.PARTIAL
        cell.source == "TYPED" -> CellMark.TYPED
        else -> CellMark.NONE
    }
    val partial = (cell.covered to cell.total).takeIf { mark == CellMark.PARTIAL || (mark == CellMark.TYPED && cell.covered < cell.total) }
    return GridCell(cell.subjectId, mark, count = cell.takes, partial = partial, override = cell.override)
}

/**
 * The workshop coverage page (B12): one form at a time, every artisan the form has recorded or typed
 * for, section by section, with the admin overrides. Online (the server's grid); the legend reads as
 * the session grid's does.
 */
@Composable
fun WorkshopCoverageScreen(
    v2: QuestionnaireV2,
    workshops: List<QWorkshopOption>,
    forms: List<QFormOption>,
    loadInstrument: suspend (questionnaireId: String) -> Instrument?,
    onError: (String) -> Unit
) {
    var formId by remember { mutableStateOf(forms.firstOrNull { it.isDefault }?.id ?: forms.firstOrNull()?.id.orEmpty()) }
    var workshopId by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf<QGrid?>(null) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    // A dot's takes (0.0.6): one opens its transcript, several open the chooser first.
    var choosing by remember { mutableStateOf<List<CoverageRecordings.Take>?>(null) }
    var opened by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun open(takes: List<CoverageRecordings.Take>) {
        when (val o = CoverageRecordings.opens(takes)) {
            CoverageRecordings.Opens.Nothing -> Unit
            is CoverageRecordings.Opens.One -> opened = o.take.mediaFileId
            is CoverageRecordings.Opens.Choose -> choosing = o.takes
        }
    }
    fun openDot(sectionId: String, subjectId: String, cell: QGridCell) {
        val listed = CoverageRecordings.ofCell(cell)
        if (cell.recordings.isNotEmpty()) return open(listed)
        // A server older than 0.0.6 sends only the count: ask the recordings list.
        scope.launch {
            val filters = CoverageRecordings.filters(formId, sectionId, subjectId, workshopId, QuestionnairePortal.FILES_UNDER_DESIGN_WORKSHOP)
            val rows = runCatching { v2.recordingsPage(filters, limit = 50).items }.getOrElse { onError(it.userSentence()); return@launch }
            open(CoverageRecordings.fromList(rows, sectionId, subjectId))
        }
    }
    LaunchedEffect(formId, workshopId) {
        if (formId.isBlank()) return@LaunchedEffect
        loading = true
        val fetched = runCatching {
            if (QuestionnairePortal.FILES_UNDER_DESIGN_WORKSHOP) v2.api.coverageGrid(formId, designWorkshopId = workshopId.ifBlank { null })
            else v2.api.coverageGrid(formId, workshopId = workshopId.ifBlank { null })
        }
        grid = fetched.getOrNull()
        fetched.exceptionOrNull()?.let { onError(it.userSentence()) }
        // The grid names its artisans once; the cells carry ids.
        names = grid?.names.orEmpty()
        loading = false
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(QuestionnaireCopy.s("coverage.title"), style = MaterialTheme.typography.titleLarge)
        SearchableSelectField(
            label = QuestionnaireCopy.s("coverage.pickForm"),
            options = forms.map { SelectOption(it.id, it.title, it.kindLabel.ifBlank { null }) },
            selectedValue = formId, includeNone = false, searchable = true
        ) { formId = it }
        SearchableSelectField(
            label = QuestionnaireCopy.s("start.workshop"),
            options = workshops.map { SelectOption(it.id, it.title, it.place) },
            selectedValue = workshopId, includeNone = true, searchable = true
        ) { workshopId = it }
        if (loading) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
        val g = grid
        val subjects = g?.sections?.flatMap { s -> s.cells.map { it.subjectId } }?.distinct().orEmpty()
        if (!loading && g != null && subjects.isEmpty()) Text(QuestionnaireCopy.s("coverage.empty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
        if (g != null && subjects.isNotEmpty()) {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row(Modifier.padding(vertical = 6.dp)) {
                    Text("Section", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.field.muted, modifier = Modifier.width(180.dp))
                    subjects.forEach { id ->
                        Box(Modifier.width(88.dp)) { CoverageName(names[id] ?: id.take(6), 84.dp, tag = "coverage.name.$id") }
                    }
                }
                g.sections.forEach { section ->
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text("${section.code}  ${section.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        subjects.forEach { id ->
                            val wire = section.cells.firstOrNull { it.subjectId == id }
                            val cell = wire?.let(::cellOf) ?: GridCell(id, CellMark.NONE)
                            CoverageDot(
                                cell.glyph,
                                color = if (cell.mark == CellMark.TYPED || cell.mark == CellMark.NONE) MaterialTheme.field.muted else MaterialTheme.field.body,
                                width = 88.dp,
                                describe = "${names[id] ?: ""}, ${section.code} ${section.title}: ${cell.glyph}",
                                onOpen = if (wire != null && CoverageRecordings.hasTakes(wire)) ({ openDot(section.sectionId, id, wire) }) else null,
                                modifier = Modifier.testTag("coverage.dot.${section.sectionId}.$id")
                            )
                        }
                    }
                }
            }
            Text(QuestionnaireCopy.s("coverage.legend"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted)
            val overrides = g.sections.flatMap { it.cells }.mapNotNull { it.override }.distinct()
            if (overrides.isNotEmpty()) Text(
                overrides.mapNotNull { o -> QuestionnaireCopy.overrideLabel(o)?.let { "${GridCell("", CellMark.NONE, override = o).glyph} $it" } }.joinToString("  "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.field.muted
            )
        }
    }
    choosing?.let { takes ->
        CoverageTakeChooser(takes, onPick = { r -> choosing = null; opened = r.mediaFileId }, onDismiss = { choosing = null })
    }
    opened?.let { id -> TranscriptDialog(id, audio = null, onDismiss = { opened = null }) }
}
