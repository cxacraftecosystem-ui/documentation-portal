package com.fieldrepository.app.transcript.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.AppScope
import com.fieldrepository.app.data.Naming
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.DataRecording
import com.fieldrepository.app.transcript.data.DataTranscriptFile
import com.fieldrepository.app.transcript.data.DataTranscriptFilters
import com.fieldrepository.app.transcript.data.TranscriptBackend
import com.fieldrepository.app.transcript.data.TranscriptFormat
import com.fieldrepository.app.transcript.data.TranscriptNames
import com.fieldrepository.app.transcript.data.TranscriptUiText
import com.fieldrepository.app.transcript.portal.TranscriptPortal
import com.fieldrepository.app.ui.FieldLinearProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * TRANSCRIPTS IN VIEW DATA AND DOWNLOAD DATA (0.0.6), the same on both portals.
 *
 * Every recording the reader may see, with what the transcript pipeline added: speakers in their
 * colours, the opening turns with times and English, the summary, quotes and glossary terms. The
 * recording filters narrow both the list and the downloads; the three datasets (recordings, turns,
 * glossary) stream as CSV or NDJSON to the share sheet. [extraFilters] are any questionnaire filters
 * the host already holds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptDataPanel(
    modifier: Modifier = Modifier,
    extraFilters: Map<String, String> = emptyMap(),
    backend: TranscriptBackend? = null,
    onMessage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val server = remember(backend) { backend ?: TranscriptPortal.backend(context) }
    var q by rememberSaveable { mutableStateOf("") }
    var speaker by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("") }
    var englishOnly by rememberSaveable { mutableStateOf(false) }
    val filters = DataTranscriptFilters(q = q, speaker = speaker, language = language, hasEnglish = if (englishOnly) true else null, extra = extraFilters)
    var rows by remember { mutableStateOf<List<DataRecording>>(emptyList()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val query = filters.toQuery()

    LaunchedEffect(query) {
        if (q.isNotBlank() || speaker.isNotBlank()) delay(350)
        loading = true; failed = false
        runCatching { server.dataPage(query, null) }
            .onSuccess { rows = it.items; cursor = it.nextCursor }
            .onFailure { if (it is CancellationException) throw it; rows = emptyList(); cursor = null; failed = true }
        loading = false
    }

    fun download(kind: String, fmt: String) {
        preparing = kind
        AppScope.io.launch {
            val result = runCatching {
                val tmp = File(shareDir(context), "dataset-$kind.$fmt.part")
                val name = server.dataFile(kind, fmt, query, tmp)
                    ?: Naming.fileName(entity = "Dataset", what = DATASET_WHAT.getValue(kind), ext = fmt, date = LocalDate.now().toString())
                File(shareDir(context), name).also { out -> out.delete(); tmp.renameTo(out) }
            }
            withContext(Dispatchers.Main) {
                preparing = null
                result.onSuccess { shareFiles(context, listOf(it), null) }
                    .onFailure { if (it !is CancellationException) onMessage(TranscriptUiText.dataDownloadFailed) }
            }
        }
    }

    Column(modifier.fillMaxWidth().testTag("transcript.data"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(TranscriptUiText.dataTitle, style = MaterialTheme.typography.titleMedium)
        Text(TranscriptUiText.dataAbout, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted)
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            label = { Text(TranscriptUiText.dataWords) },
            modifier = Modifier.fillMaxWidth().testTag("transcript.data.q")
        )
        OutlinedTextField(
            value = speaker, onValueChange = { speaker = it }, singleLine = true,
            label = { Text(TranscriptUiText.dataSpeaker) },
            modifier = Modifier.fillMaxWidth().testTag("transcript.data.speaker")
        )
        val languages = (rows.flatMap { it.languages } ).distinctBy { it.code }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = englishOnly, onClick = { englishOnly = !englishOnly },
                label = { Text(TranscriptUiText.dataHasEnglish) }, modifier = Modifier.testTag("transcript.data.english")
            )
            (languages.map { it.code to it.label.ifBlank { it.code } } + listOfNotNull((language to language).takeIf { language.isNotBlank() && languages.none { l -> l.code == language } }))
                .forEach { (code, label) ->
                    FilterChip(
                        selected = language == code, onClick = { language = if (language == code) "" else code },
                        label = { Text(label) }, modifier = Modifier.testTag("transcript.data.language.$code")
                    )
                }
        }
        // Download data: three datasets, each as CSV or NDJSON, narrowed by the same filters.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DataTranscriptFilters.KINDS.forEach { kind ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    AssistChip(
                        onClick = { menu = true }, enabled = preparing == null,
                        label = { Text(if (preparing == kind) TranscriptUiText.dataPreparing else DATASET_LABEL.getValue(kind)) },
                        modifier = Modifier.testTag("transcript.data.download.$kind")
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DataTranscriptFilters.FORMATS.forEach { fmt ->
                            DropdownMenuItem(
                                text = { Text(fmt.uppercase()) },
                                onClick = { menu = false; download(kind, fmt) },
                                modifier = Modifier.testTag("transcript.data.download.$kind.$fmt")
                            )
                        }
                    }
                }
            }
        }
        if (loading) FieldLinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(TranscriptUiText.couldNotLoad, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (!loading && !failed && rows.isEmpty()) Text(TranscriptUiText.dataNone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.field.muted)
        rows.forEach { DataRecordingCard(it) }
        if (cursor != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Button(onClick = {
                val at = cursor ?: return@Button
                scope.launch {
                    runCatching { server.dataPage(query, at) }
                        .onSuccess { next -> rows = rows + next.items.filter { n -> rows.none { it.mediaId == n.mediaId } }; cursor = next.nextCursor }
                        .onFailure { if (it is CancellationException) throw it; onMessage(TranscriptUiText.couldNotLoad) }
                }
            }, modifier = Modifier.testTag("transcript.data.more")) { Text(TranscriptUiText.dataMore) }
        }
    }
}

private val DATASET_LABEL = mapOf("recordings" to TranscriptUiText.dataRecordings, "turns" to TranscriptUiText.dataTurns, "glossary" to TranscriptUiText.dataGlossary)
private val DATASET_WHAT = mapOf("recordings" to "Transcripts", "turns" to "Transcript-Turns", "glossary" to "Transcript-Glossary")

/** One recording as View data shows it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DataRecordingCard(r: DataRecording) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp)).padding(12.dp).testTag("transcript.data.row.${r.mediaId}"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(r.file?.ifBlank { null } ?: r.mediaId, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val facts = listOfNotNull(
            r.uploadedAt?.take(10),
            r.languages.joinToString(", ") { it.label.ifBlank { it.code } }.ifBlank { null },
            r.durationMs?.let(TranscriptFormat::length),
            r.uploadedBy?.ifBlank { null },
            r.models?.let { m -> listOfNotNull(m.stt, m.translation).joinToString(" · ").ifBlank { null } },
            r.costUsd?.let(TranscriptUiText::usd),
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.field.muted)
        if (r.withheld != null) {
            Text(TranscriptUiText.dataWithheld, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.muted, modifier = Modifier.testTag("transcript.data.withheld"))
            return@Column
        }
        if (r.speakers.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            r.speakers.forEach { s -> Text(s.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = speakerColor(s.colorKey)) }
        }
        r.summary.take(3).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.field.body) }
        val body = MaterialTheme.field.body
        val muted = MaterialTheme.field.muted
        r.turns.take(3).forEach { t ->
            val tint = speakerColor(t.colorKey)
            Text(
                buildAnnotatedString {
                    t.start?.let { withStyle(SpanStyle(color = muted)) { append("$it  ") } }
                    withStyle(SpanStyle(color = tint, fontWeight = FontWeight.SemiBold)) { append(t.speaker) }
                    append("  ")
                    withStyle(SpanStyle(color = body)) { append(t.text) }
                    t.english?.takeIf { it.isNotBlank() && it != t.text }?.let { withStyle(SpanStyle(color = muted)) { append("\n$it") } }
                },
                style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis
            )
        }
        if (r.turns.size > 3) Text(TranscriptUiText.dataMoreTurns(r.turns.size - 3), style = MaterialTheme.typography.labelSmall, color = muted)
        if (r.quotes.isNotEmpty()) Text(TranscriptUiText.dataQuotes(r.quotes.size), style = MaterialTheme.typography.labelSmall, color = muted)
        if (r.glossary.isNotEmpty()) Text(r.glossary.joinToString(", ") { it.term }, style = MaterialTheme.typography.labelMedium, color = body, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A recording's transcript files on its data-browser card (0.0.6): md and Word with and without
 * timestamps, and subtitles when the turns are timed. A tap downloads one to the share sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptFileChips(mediaId: String, files: List<DataTranscriptFile>, modifier: Modifier = Modifier, backend: TranscriptBackend? = null, onMessage: (String) -> Unit = {}) {
    if (files.isEmpty()) return
    val context = LocalContext.current
    val server = remember(backend) { backend ?: TranscriptPortal.backend(context) }
    var busy by remember { mutableStateOf<String?>(null) }
    FlowRow(modifier.testTag("transcript.files.$mediaId"), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        files.forEach { f ->
            val key = f.name.ifBlank { "${f.format}.${f.track}.${f.timestamps}" }
            AssistChip(
                onClick = {
                    busy = key
                    AppScope.io.launch {
                        val result = runCatching {
                            val got = server.download(mediaId, f.format, DataTranscriptFilters.trackOf(f), f.timestamps)
                            File(shareDir(context), got.name ?: f.name.ifBlank { TranscriptNames.fileName(TranscriptNames.whatFor(f.format), f.format) }).also { it.writeBytes(got.bytes) }
                        }
                        withContext(Dispatchers.Main) {
                            busy = null
                            result.onSuccess { shareFiles(context, listOf(it), null) }
                                .onFailure { if (it !is CancellationException) onMessage(TranscriptUiText.dataDownloadFailed) }
                        }
                    }
                },
                enabled = busy == null,
                label = { Text(TranscriptUiText.fileLabel(f.format, DataTranscriptFilters.trackOf(f) == CopyTrack.ENGLISH, f.timestamps)) },
                modifier = Modifier.testTag("transcript.file.$mediaId.${f.format}.${f.track}.${f.timestamps}")
            )
        }
    }
}
