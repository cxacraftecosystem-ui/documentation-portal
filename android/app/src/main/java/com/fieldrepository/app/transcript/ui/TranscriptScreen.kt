package com.fieldrepository.app.transcript.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.fieldrepository.app.data.AppScope
import com.fieldrepository.app.transcript.data.AskAnswer
import com.fieldrepository.app.transcript.data.AutoDraft
import com.fieldrepository.app.transcript.data.AutoDraftResult
import com.fieldrepository.app.transcript.data.CopyTrack
import com.fieldrepository.app.transcript.data.GlossaryTerm
import com.fieldrepository.app.transcript.data.LoadedTranscript
import com.fieldrepository.app.transcript.data.PipelineStatus
import com.fieldrepository.app.transcript.data.QuoteCard
import com.fieldrepository.app.transcript.data.SpeakerMapping
import com.fieldrepository.app.transcript.data.SpeakerSuggestion
import com.fieldrepository.app.transcript.data.Transcript
import com.fieldrepository.app.transcript.data.TranscriptEdit
import com.fieldrepository.app.transcript.data.TranscriptFormat
import com.fieldrepository.app.transcript.data.TranscriptNames
import com.fieldrepository.app.transcript.data.TranscriptRepository
import com.fieldrepository.app.transcript.data.TranscriptStrings
import com.fieldrepository.app.transcript.data.TranscriptSummary
import com.fieldrepository.app.transcript.data.TranscriptSummaryBatcher
import com.fieldrepository.app.transcript.data.TranscriptTurn
import com.fieldrepository.app.transcript.data.TranscriptUiText
import com.fieldrepository.app.transcript.data.TranscriptUnavailable
import com.fieldrepository.app.transcript.data.TurnHistoryEntry
import com.fieldrepository.app.transcript.data.transcriptError
import com.fieldrepository.app.transcript.portal.TranscriptPortal
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import com.fieldrepository.app.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.net.URL

private sealed interface ScreenState {
    data object Loading : ScreenState
    data object Unavailable : ScreenState
    data class Failed(val message: String) : ScreenState
    data class Ready(val loaded: LoadedTranscript, val failures: List<String>) : ScreenState
}

/**
 * A recording's transcript, full screen, over the app (both portals mount it from every place a
 * recording appears). [audio] is the mount's own URI for the file when it has one; the view's
 * signed URL otherwise. [acceptDraft] is the questionnaire's own answer path, given only where the
 * recording is a questionnaire take (Fill from recording).
 */
@Composable
fun TranscriptDialog(
    mediaId: String,
    audio: Uri?,
    onDismiss: () -> Unit,
    acceptDraft: ((AutoDraft, String) -> Unit)? = null,
    headerExtra: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("transcript.close")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = TranscriptUiText.close)
                    }
                    Text(TranscriptUiText.open, style = MaterialTheme.typography.titleMedium)
                }
                TranscriptScreen(mediaId, audio, acceptDraft, headerExtra)
            }
        }
    }
}

@Composable
fun TranscriptScreen(
    mediaId: String,
    audio: Uri?,
    acceptDraft: ((AutoDraft, String) -> Unit)? = null,
    headerExtra: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val repository = remember(mediaId) { TranscriptPortal.repository(context) }
    var state by remember(mediaId) { mutableStateOf<ScreenState>(ScreenState.Loading) }
    var reloads by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        val repo = repository ?: run { state = ScreenState.Unavailable; return }
        state = try {
            ScreenState.Ready(repo.load(mediaId), repo.failed(mediaId).mapNotNull { it.failure })
        } catch (e: CancellationException) {
            throw e
        } catch (e: TranscriptUnavailable) {
            ScreenState.Unavailable
        } catch (e: IOException) {
            ScreenState.Failed(TranscriptUiText.couldNotLoad)
        } catch (e: HttpException) {
            ScreenState.Failed(e.transcriptError(TranscriptPortal.json)?.message?.ifBlank { null } ?: TranscriptUiText.couldNotLoad)
        }
    }

    LaunchedEffect(mediaId, reloads) { reload() }
    // A transcript still on its way is looked at again until it is ready (or needs a person).
    val working = (state as? ScreenState.Ready)?.loaded?.view?.status?.let(PipelineStatus::working) == true
    LaunchedEffect(working) {
        while (working) {
            delay(15_000)
            reload()
        }
    }

    when (val s = state) {
        ScreenState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FieldCircularProgressIndicator(Modifier.size(32.dp)) }
        ScreenState.Unavailable -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(TranscriptUiText.couldNotLoad, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodyMedium)
        }
        is ScreenState.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.message, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { reloads++ }) { Text(TranscriptUiText.tryAgain) }
        }
        is ScreenState.Ready -> {
            val repo = repository!!
            val view = s.loaded.view
            // Voice Isolator (0.0.5): the cleaned copy unless the reader ticks "the original recording".
            var playingOriginal by remember(view.mediaId) { mutableStateOf(false) }
            val uri = audio ?: (if (playingOriginal && view.audio.cleaned) view.audio.originalUrl else view.audio.url)?.let(Uri::parse)
            val playback = rememberTranscriptPlayback(uri)
            val host = remember(view, s.loaded.offline) {
                RepositoryHost(context, repo, view, s.loaded.offline, acceptDraft, scope,
                    onChanged = { updated -> state = s.copy(loaded = s.loaded.copy(view = updated, waitingEdits = s.loaded.waitingEdits + 1)) },
                    onReload = { reloads++ })
            }
            TranscriptViewer(
                view, playback, host,
                offline = s.loaded.offline, waitingEdits = s.loaded.waitingEdits, failures = s.failures, headerExtra = headerExtra,
                playingOriginal = playingOriginal,
                onPlayOriginal = if (audio == null && view.audio.cleaned && view.audio.originalUrl != null) { on -> playingOriginal = on } else null,
            )
        }
    }
}

/** The viewer's host over the repository, the clipboard and the share sheet. */
private class RepositoryHost(
    private val context: Context,
    private val repo: TranscriptRepository,
    private val view: Transcript,
    private val offline: Boolean,
    override val acceptDraft: ((AutoDraft, String) -> Unit)?,
    private val scope: CoroutineScope,
    private val onChanged: (Transcript) -> Unit,
    private val onReload: () -> Unit,
) : TranscriptHost() {
    private val server get() = repo.server

    override fun copy(text: String) = copyToClipboard(context, text)

    /** Every change goes through the queue: applied on the phone now, sent when there is a network. */
    private fun change(edit: TranscriptEdit) {
        scope.launch {
            onChanged(repo.queue(view, edit))
            TranscriptPortal.requestSync(context)
            if (runCatching { repo.drain() }.getOrDefault(1) == 0) onReload()
        }
    }

    override fun editTurn(turn: TranscriptTurn, text: String?, english: String?) {
        if (text == null && english == null) return
        change(TranscriptEdit(mediaId = view.mediaId, type = TranscriptEdit.TURN, turnId = turn.id, text = text, english = english, baseRevision = turn.revision))
    }

    override fun retranslate(turn: TranscriptTurn) =
        change(TranscriptEdit(mediaId = view.mediaId, type = TranscriptEdit.RETRANSLATE, turnId = turn.id, baseRevision = turn.revision))

    override fun mapSpeakers(mappings: List<SpeakerMapping>) =
        change(TranscriptEdit(mediaId = view.mediaId, type = TranscriptEdit.SPEAKERS, mappings = mappings, baseRevision = view.revision))

    override fun statusAction() {
        scope.launch {
            val updated = runCatching {
                if (view.status == PipelineStatus.QUEUED && view.permissions.transcribeNow) server.transcribeNow(view.mediaId) else server.retry(view.mediaId)
            }.getOrNull()
            if (updated != null) repo.accept(updated)
            onReload()
        }
    }

    override suspend fun suggestions(): List<SpeakerSuggestion> = server.speakerSuggestions(view.mediaId)
    override suspend fun history(turn: TranscriptTurn): List<TurnHistoryEntry> = server.turnHistory(view.mediaId, turn.id)
    override suspend fun ask(question: String): AskAnswer = server.ask(view.mediaId, question)
    override suspend fun autodraft(): AutoDraftResult = server.autodraft(view.mediaId)
    override suspend fun glossary(): List<GlossaryTerm> = server.glossary(null, null)

    override suspend fun quoteCard(turnIds: List<String>): QuoteCard = try {
        server.quoteCard(view.mediaId, turnIds)
    } catch (e: HttpException) {
        val detail = e.transcriptError(TranscriptPortal.json)
        if (detail?.code == "CONSENT_REFUSED") throw QuoteCardRefused(TranscriptStrings.quoteCardWithdrawn)
        throw e
    }

    override fun shareQuoteCard(card: QuoteCard) {
        AppScope.io.launch {
            val dir = shareDir(context)
            val image = File(dir, TranscriptNames.forView(view, "png")).also { it.writeBytes(QuoteCardImage.png(card)) }
            val clip = card.clip.url?.let { url ->
                runCatching { File(dir, TranscriptNames.forView(view, "m4a")).also { f -> URL(url).openStream().use { input -> f.outputStream().use { input.copyTo(it) } } } }.getOrNull()
            }
            withContext(Dispatchers.Main) { shareFiles(context, listOfNotNull(image, clip), card.markdown) }
        }
    }

    override fun download(format: String, track: CopyTrack) = download(format, track, true)

    override fun download(format: String, track: CopyTrack, timestamps: Boolean) {
        AppScope.io.launch {
            val file = runCatching {
                if (offline) throw IOException("offline")
                val got = server.download(view.mediaId, format, track, timestamps)
                File(shareDir(context), got.name ?: TranscriptNames.forView(view, format)).also { it.writeBytes(got.bytes) }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                // No connection: everything but Word is built on the phone from the same turns.
                val text = when (format) {
                    "md" -> TranscriptFormat.all(view, track, timestamps)
                    "srt" -> TranscriptFormat.srt(view, track)
                    "vtt" -> TranscriptFormat.vtt(view, track)
                    else -> null
                } ?: return@launch
                File(shareDir(context), TranscriptNames.forView(view, format)).also { it.writeText(text) }
            }
            withContext(Dispatchers.Main) { shareFiles(context, listOf(file), null) }
        }
    }
}

// ── The mount ──────────────────────────────────────────────────────────────────────────────────

private object Summaries {
    @Volatile private var batcher: TranscriptSummaryBatcher? = null

    fun of(context: Context): TranscriptSummaryBatcher {
        batcher?.let { return it }
        val backend = TranscriptPortal.backend(context)
        return TranscriptSummaryBatcher(AppScope.io) { ids -> backend.batch(ids) }.also { batcher = it }
    }
}

private sealed interface EntryState {
    data object Unknown : EntryState
    data object Legacy : EntryState
    data class Known(val summary: TranscriptSummary) : EntryState
}

/**
 * WHERE EVERY RECORDING SHOWS ITS TRANSCRIPT, IN BOTH APPS: a compact row with the status and the
 * header badges, and one button that opens [TranscriptDialog]. While the pipeline is switched off
 * the server answers 404, and the row draws [legacy] — the plain transcript this app showed
 * before — exactly as the contract asks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranscriptEntry(
    mediaId: String,
    audio: Uri?,
    modifier: Modifier = Modifier,
    acceptDraft: ((AutoDraft, String) -> Unit)? = null,
    headerExtra: (@Composable () -> Unit)? = null,
    legacy: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    var state by remember(mediaId) { mutableStateOf<EntryState>(EntryState.Unknown) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(mediaId) {
        state = try {
            Summaries.of(context).get(mediaId)?.let { EntryState.Known(it) } ?: EntryState.Legacy
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // No connection: a transcript kept on the phone still opens.
            TranscriptPortal.repository(context)?.cached(mediaId)?.let {
                EntryState.Known(TranscriptSummary(it.mediaId, it.status, it.languages, it.durationMs, it.speakerCount, it.badges))
            } ?: EntryState.Legacy
        } catch (e: Exception) {
            EntryState.Legacy
        }
    }
    when (val s = state) {
        is EntryState.Known -> Column(
            modifier.fillMaxWidth().background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp)).padding(12.dp).testTag("transcript.entry.$mediaId"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(TranscriptUiText.status(s.summary.status), tone = if (s.summary.status == PipelineStatus.READY) PillTone.MODEL else PillTone.PLAIN)
                if (s.summary.languages.isNotEmpty()) Pill(s.summary.languages.joinToString(", ") { it.label.ifBlank { it.code } })
                s.summary.durationMs?.let { Pill(TranscriptFormat.length(it)) }
                s.summary.badges.forEach { Pill(it.label, tone = PillTone.QUIET) }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("transcript.open.$mediaId")) {
                    Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  " + TranscriptUiText.open)
                }
            }
        }
        else -> legacy()
    }
    if (open) TranscriptDialog(mediaId, audio, onDismiss = { open = false }, acceptDraft = acceptDraft, headerExtra = headerExtra)
}

// ── Clipboard and share sheet ──────────────────────────────────────────────────────────────────

fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(TranscriptUiText.open, text))
}

private fun shareDir(context: Context): File = File(context.cacheDir, "transcript-share").apply { mkdirs() }

private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
    "md" -> "text/markdown"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "srt" -> "application/x-subrip"
    "vtt" -> "text/vtt"
    "png" -> "image/png"
    "m4a" -> "audio/mp4"
    else -> "application/octet-stream"
}

private fun shareFiles(context: Context, files: List<File>, text: String?) {
    if (files.isEmpty()) return
    val authority = TranscriptPortal.fileProviderAuthority(context)
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, authority, it) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { type = mimeOf(files[0]); putExtra(Intent.EXTRA_STREAM, uris[0]) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
    }
    text?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, TranscriptUiText.share).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
