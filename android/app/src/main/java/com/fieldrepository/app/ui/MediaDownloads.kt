package com.fieldrepository.app.ui

import com.fieldrepository.app.data.apiErrorMessage
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.fieldrepository.app.data.AadhaarAtRest
import com.fieldrepository.app.data.OfflineOutbox
import com.fieldrepository.app.data.userMessage
import com.fieldrepository.app.data.PendingEntry
import com.fieldrepository.app.data.PendingMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.util.Locale

/*
 * Save a piece of media the researcher is uploading, or has queued to upload, to the phone's own
 * public Downloads folder.
 *
 * A clip recorded in a village sits in this app's storage for as long as the upload takes, and on a
 * bad connection that is minutes; until it lands, the on-device copy is the only copy. The Download
 * control on every attachment tile, recording card and outbox row copies that local file out — it
 * never waits for, pauses or re-reads through the upload, and the copy runs on the IO dispatcher so
 * neither the upload coroutine nor the frame the button was pressed on is held up.
 *
 * Where it lands: MediaStore's Downloads collection on API 29 and up (no permission needed). Below
 * that — minSdk is 26 — writing the public folder would need WRITE_EXTERNAL_STORAGE, which this app
 * does not hold, so the system "save as" picker (SAF CreateDocument) is opened instead, pre-filled
 * with the same name.
 *
 * The filename rules are pure and shared, row for row, with the web's `lib/localMediaDownload.ts`
 * (`downloadLocalMedia`); `MediaDownloadsTest` and `e2e/local-media-download-unit.spec.ts` drive the
 * same table.
 */

private val EXTENSION_BY_MIME: Map<String, String> = mapOf(
    "audio/webm" to "webm",
    "audio/ogg" to "ogg",
    "audio/opus" to "ogg",
    "audio/mp4" to "m4a",
    "audio/x-m4a" to "m4a",
    "audio/m4a" to "m4a",
    "audio/aac" to "aac",
    "audio/mpeg" to "mp3",
    "audio/mp3" to "mp3",
    "audio/wav" to "wav",
    "audio/x-wav" to "wav",
    "audio/wave" to "wav",
    "audio/flac" to "flac",
    "audio/amr" to "amr",
    "audio/3gpp" to "3gp",
    "image/jpeg" to "jpg",
    "image/jpg" to "jpg",
    "image/png" to "png",
    "image/gif" to "gif",
    "image/webp" to "webp",
    "image/heic" to "heic",
    "image/heif" to "heif",
    "image/avif" to "avif",
    "image/bmp" to "bmp",
    "image/tiff" to "tif",
    "video/mp4" to "mp4",
    "video/webm" to "webm",
    "video/quicktime" to "mov",
    "video/x-matroska" to "mkv",
    "video/3gpp" to "3gp",
    "video/x-msvideo" to "avi",
    "application/pdf" to "pdf",
    "text/plain" to "txt",
    "text/csv" to "csv",
    "application/json" to "json",
    "application/msword" to "doc",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
    "application/vnd.ms-excel" to "xls",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx"
)

private val PLAIN_SUBTYPE = Regex("^[a-z0-9][a-z0-9-]{0,15}$")

/** Characters no Windows, macOS or Android filesystem accepts in a name. */
private val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

/**
 * File extension for a MIME type, without the dot. Parameters (`;codecs=…`) are dropped first; an
 * unlisted type uses a plain-word subtype, and anything else is `bin`.
 */
fun mediaExtensionForMimeType(mimeType: String?): String {
    val base = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    if (base.isEmpty()) return "bin"
    EXTENSION_BY_MIME[base]?.let { return it }
    val subtype = if (base.contains('/')) base.substringAfter('/') else ""
    return if (PLAIN_SUBTYPE.matches(subtype)) subtype else "bin"
}

/** `YYYY-MM-DD-HHmmss` in local time — sortable, and legal in every filesystem's names. */
fun mediaFileStamp(now: LocalDateTime): String =
    String.format(
        Locale.ROOT,
        "%04d-%02d-%02d-%02d%02d%02d",
        now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second
    )

/**
 * The name the saved file gets: the ORIGINAL name whenever there is one (illegal characters become
 * `_`, a leading dot is dropped, a missing extension is added from the MIME type), otherwise
 * `voice-note-<stamp>.<ext>` for audio and `media-<stamp>.<ext>` for anything else.
 */
fun localMediaFileName(name: String?, mimeType: String?, now: LocalDateTime): String {
    val cleaned = name.orEmpty().replace(ILLEGAL_NAME_CHARS, "_").trim().trimStart('.')
    val extension = mediaExtensionForMimeType(mimeType)
    if (cleaned.isNotEmpty()) {
        val dot = cleaned.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < cleaned.length - 1
        return if (hasExtension || extension == "bin") cleaned else "$cleaned.$extension"
    }
    val isAudio = mimeType.orEmpty().trim().lowercase(Locale.ROOT).startsWith("audio/")
    return "${if (isAudio) "voice-note" else "media"}-${mediaFileStamp(now)}.$extension"
}

/** What a Uri calls itself — the content resolver's display name, else its last path segment. */
private fun displayNameOf(context: Context, uri: Uri): String? {
    val queried = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getString(index) else null
        }
    }.getOrNull()
    return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment
}

private fun mimeTypeOf(context: Context, uri: Uri, hint: String?): String =
    hint?.takeIf { it.isNotBlank() }
        ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
        ?: "application/octet-stream"

/**
 * Copy [source] into the public Downloads collection and return the new entry's Uri. API 29+ only;
 * below that the caller goes through [MediaDownloadButton]'s CreateDocument fallback. Runs on the IO
 * dispatcher, so it is safe to call from any coroutine — including while [source] is uploading.
 */
@RequiresApi(Build.VERSION_CODES.Q)
suspend fun saveToDownloads(context: Context, source: Uri, fileName: String, mimeType: String): Uri =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Couldn't create the file in Downloads")
        try {
            copyUri(context, source, target)
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            target
        } catch (error: Throwable) {
            // A half-written pending row would sit in Downloads under the person's file name.
            runCatching { resolver.delete(target, null, null) }
            throw error
        }
    }

/** Stream [source] into [target] on the IO dispatcher. */
private suspend fun copyUri(context: Context, source: Uri, target: Uri) = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    resolver.openOutputStream(target)?.use { out ->
        resolver.openInputStream(source)?.use { input -> input.copyTo(out) }
            ?: throw IllegalStateException("Couldn't read the file")
    } ?: throw IllegalStateException("Couldn't open the destination")
}

private fun openSaved(context: Context, uri: Uri, mimeType: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Open").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, "No app on this phone can open this file.", Toast.LENGTH_LONG).show() }
}

/** The outline of a button drawn on a coloured tile: the label's own colour, a little quieter. */
const val TILE_OUTLINE_ALPHA = 0.72f

private data class SavedCopy(val uri: Uri, val name: String, val mimeType: String, val nonce: Long)

/** Which of the two media-card actions a [MediaActionButton] is. */
enum class MediaAction { DOWNLOAD, RETRY }

/**
 * The visible action buttons on a media card — "Retry" and "Download" — drawn ONE way on every
 * tile, row and banner (capture tiles, uploaded media, the outbox). The web's `MediaActionButton`.
 *
 * Owner, 2026-10-17: these were not clearly visible. They were 12sp TextButtons in a light tint
 * with 2dp of vertical padding — a word floating on the card. Now each is a TONAL button with an
 * icon AND a word, 14sp semibold, at least 48dp tall, with a 1dp edge of the next rung:
 *
 *   Download  light: purple-800 on purple-100   dark: purple-100 on purple-900
 *   Retry     light: error-800 on error-100     dark: error-400 on error-950
 *
 * every pair far above WCAG AA's 4.5:1 for text. Retry takes the error rung because it sits under a
 * failure line and has to read as the way out of it. [text] overrides the word ("Try again",
 * "Send again") where the outbox already uses another; the icon stays.
 */
@Composable
fun MediaActionButton(
    action: MediaAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    enabled: Boolean = true,
    /**
     * The on-tile variant: on a coloured tile (Purple800 light, Purple900 dark) the tonal pair would be
     * a purple chip on a purple card, so the button is drawn OUTLINED in the tile's own ink instead —
     * the word and icon in [onTile], the edge in [onTile] at [TILE_OUTLINE_ALPHA], no container. The
     * ink is the theme's onBrandTile, far above AA's 4.5:1 on both purples. Same size, icon and word.
     */
    onTile: Color? = null
) {
    if (onTile != null) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, onTile.copy(alpha = TILE_OUTLINE_ALPHA)),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = onTile)
        ) { MediaActionLabel(action, text) }
        return
    }
    val dark = MaterialTheme.field.isDark
    val (container, content, edge) = when (action) {
        MediaAction.DOWNLOAD ->
            if (dark) Triple(FieldPalette.Purple900, FieldPalette.Purple100, FieldPalette.Purple700)
            else Triple(FieldPalette.Purple100, FieldPalette.Purple800, FieldPalette.Purple200)
        MediaAction.RETRY ->
            if (dark) Triple(FieldPalette.Error950, FieldPalette.Error400, FieldPalette.Error800)
            else Triple(FieldPalette.Error100, FieldPalette.Error800, FieldPalette.Error400.copy(alpha = 0.6f))
    }
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, edge),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = content)
    ) { MediaActionLabel(action, text) }
}

/** The icon and the word of a [MediaActionButton], in the button's content colour, in both variants. */
@Composable
private fun MediaActionLabel(action: MediaAction, text: String?) {
    Icon(
        if (action == MediaAction.DOWNLOAD) Icons.Filled.Download else Icons.Filled.Refresh,
        contentDescription = null,
        modifier = Modifier.size(18.dp)
    )
    Spacer(Modifier.width(8.dp))
    Text(
        text ?: if (action == MediaAction.DOWNLOAD) "Download" else "Retry",
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

/**
 * The one "Download" control on every upload, recording and outbox row: a [MediaActionButton] —
 * the Download icon and the word "Download". [source] is the copy on this phone (a content or file
 * Uri); it works before, during and after the upload. On success a snackbar confirms where the file
 * went and offers "Open".
 */
@Composable
fun MediaDownloadButton(
    source: Uri,
    modifier: Modifier = Modifier,
    name: String? = null,
    mimeType: String? = null,
    /**
     * The ink of a coloured tile this button sits on (the purple "files ready" card). Non-null draws
     * the on-tile [MediaActionButton] — outlined in that ink — instead of the tonal default.
     */
    onTile: Color? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<SavedCopy?>(null) }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun failed(error: Throwable) {
        Toast.makeText(context, "Couldn't save: ${error.apiErrorMessage("the copy failed")}", Toast.LENGTH_LONG).show()
    }

    // Below API 29: the system "save as" picker. It answers with a document Uri, or null if dismissed.
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val (fileName, mime) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (target == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { copyUri(context, source, target) }
                .onSuccess { saved = SavedCopy(target, fileName, mime, System.nanoTime()) }
                .onFailure(::failed)
        }
    }

    MediaActionButton(
        action = MediaAction.DOWNLOAD,
        onTile = onTile,
        onClick = {
            scope.launch {
                val (fileName, mime) = withContext(Dispatchers.IO) {
                    val mime = mimeTypeOf(context, source, mimeType)
                    localMediaFileName(name ?: displayNameOf(context, source), mime, LocalDateTime.now()) to mime
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { saveToDownloads(context, source, fileName, mime) }
                        .onSuccess { saved = SavedCopy(it, fileName, mime, System.nanoTime()) }
                        .onFailure(::failed)
                } else {
                    pending = fileName to mime
                    runCatching { createDocument.launch(fileName) }.onFailure { pending = null; failed(it) }
                }
            }
        },
        modifier = modifier.semantics { contentDescription = "Download ${name ?: "this file"}" }
    )

    saved?.let { copy ->
        LaunchedEffect(copy.nonce) {
            delay(6_000L)
            if (saved?.nonce == copy.nonce) saved = null
        }
        Popup(
            alignment = Alignment.BottomCenter,
            onDismissRequest = { saved = null },
            properties = PopupProperties(focusable = false)
        ) {
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { openSaved(context, copy.uri, copy.mimeType); saved = null }) {
                        Text("Open")
                    }
                }
            ) {
                Text(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Saved “${copy.name}” to Downloads"
                    else "Saved “${copy.name}”",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The "saved on this device — uploading when you're online" banner, with the queued files listed
 * under it on request so each can be saved out of the outbox before it drains.
 *
 * Expanded, it also lists every entry the server REFUSED, with the server's reason and a "Try
 * again" ([onRetry]). Those entries never drain on their own, and before this list the only trace
 * of a refusal was a toast at the moment it happened.
 *
 * [entries] is the signed-in account's queue; another account's entries on a shared handset are
 * not shown here.
 *
 * An entry this phone is HOLDING because it could not keep the Aadhaar number typed into it
 * (`AadhaarAtRest.numberWithheld`, 2026-10-18) gets "Type it again" in place of "Try again": a retry
 * would hold it again for the same reason. [validateAadhaar] is the form's own rule (the sentence, or
 * null when the number is fine); [onRetypeAadhaar] stores it sealed and sends the entry.
 */
@Composable
fun OutboxPendingBanner(
    pendingUploads: Int,
    modifier: Modifier = Modifier,
    entries: suspend () -> List<PendingEntry>,
    onRetry: suspend (entryId: String) -> Unit,
    validateAadhaar: (String) -> String? = { null },
    onRetypeAadhaar: (suspend (entryId: String, number: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ink = MaterialTheme.field.onBrandTile
    var expanded by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<PendingMedia>>(emptyList()) }
    var refused by remember { mutableStateOf<List<PendingEntry>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    // The held entry whose Aadhaar number is being typed again, or null.
    var retyping by remember { mutableStateOf<PendingEntry?>(null) }
    retyping?.let { entry ->
        RetypeAadhaarDialog(
            entry = entry,
            validate = validateAadhaar,
            onDismiss = { retyping = null },
            onTyped = { number ->
                retyping = null
                scope.launch {
                    runCatching { onRetypeAadhaar?.invoke(entry.id, number) }
                        .onFailure { error ->
                            Toast.makeText(context, error.userMessage("That didn't work. Try again."), Toast.LENGTH_LONG).show()
                        }
                    reload++
                }
            },
        )
    }
    LaunchedEffect(expanded, pendingUploads, reload) {
        if (!expanded) return@LaunchedEffect
        val queue = runCatching { entries() }.getOrDefault(emptyList())
        refused = queue.filter { it.failure != null }
        files = runCatching {
            val queued = queue.flatMap { it.media }
            withContext(Dispatchers.IO) { queued.filter { File(it.localPath).exists() } }
        }.getOrDefault(emptyList())
    }
    Column(
        modifier = modifier
            .background(MaterialTheme.field.brandTile, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            Text(
                "$pendingUploads entr${if (pendingUploads == 1) "y" else "ies"} waiting to upload.",
                color = ink,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            Text(if (expanded) "Hide files ▲" else "Files ▼", color = ink, fontSize = 12.sp)
        }
        if (expanded) {
            refused.forEach { entry ->
                val needsAadhaar = onRetypeAadhaar != null && entry.createdId == null &&
                    AadhaarAtRest.numberWithheld(entry.payloadJson)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            needsAadhaar -> "“${entry.label}”: ${entry.failure}"
                            entry.needsReview -> "Review “${entry.label}”: ${entry.failure}"
                            else -> "“${entry.label}” couldn't upload: ${entry.failure}"
                        },
                        color = ink,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (needsAadhaar) {
                        MediaActionButton(
                            action = MediaAction.RETRY,
                            text = "Type it again",
                            onClick = { retyping = entry }
                        )
                    } else {
                        MediaActionButton(
                            action = MediaAction.RETRY,
                            text = if (entry.needsReview) "Send again" else "Try again",
                            onClick = { scope.launch { onRetry(entry.id); reload++ } }
                        )
                    }
                }
            }
            if (files.isEmpty()) {
                Text("No files are queued with these entries.", color = ink, fontSize = 12.sp)
            }
            files.forEach { media ->
                val file = File(media.localPath)
                val label = media.originalFilename.ifBlank { file.name }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        color = ink,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    MediaDownloadButton(source = Uri.fromFile(file), name = label, mimeType = media.mimeType)
                }
            }
        }
    }
}

/**
 * Type the Aadhaar number again for a queued artisan whose typed number this phone could not keep,
 * then send it. Checked here by the form's own rule ([validate]), so a mistyped digit is said in the
 * box rather than after a send; when the last four were kept, the number must end in them.
 */
@Composable
private fun RetypeAadhaarDialog(
    entry: PendingEntry,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onTyped: (String) -> Unit
) {
    val last4 = remember(entry.id) { AadhaarAtRest.withheldLast4(entry.payloadJson) }
    var typed by remember(entry.id) { mutableStateOf("") }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type the Aadhaar number again", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    last4?.let { "“${entry.label}” is safe. Type the number ending $it to send it." }
                        ?: "“${entry.label}” is safe. Type the artisan's Aadhaar number to send it.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { input ->
                        typed = input.filter { it in '0'..'9' }.take(12)
                        error = null
                    },
                    label = { Text("Aadhaar number") },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    singleLine = true,
                    // NumberPassword: the digit pad, and no keyboard learning a regulated identifier.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = typed.length == 12,
                onClick = {
                    val problem = validate(typed)
                        ?: last4?.takeIf { !typed.endsWith(it) }?.let { "That number doesn't end in $it. Check the card." }
                    if (problem != null) error = problem else onTyped(typed)
                }
            ) { Text("Send it", fontSize = 13.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", fontSize = 13.sp) } }
    )
}
