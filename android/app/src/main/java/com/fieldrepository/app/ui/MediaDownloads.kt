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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.fieldrepository.app.data.AadhaarAtRest
import com.fieldrepository.app.data.OfflineOutbox
import com.fieldrepository.app.data.userMessage
import com.fieldrepository.app.data.PendingEntry
import com.fieldrepository.app.data.PendingMedia
import com.fieldrepository.app.data.isPossibleDuplicate
import com.fieldrepository.app.data.possibleDuplicateTrayText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.util.Locale

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

private val ILLEGAL_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

fun mediaExtensionForMimeType(mimeType: String?): String {
    val base = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    if (base.isEmpty()) return "bin"
    EXTENSION_BY_MIME[base]?.let { return it }
    val subtype = if (base.contains('/')) base.substringAfter('/') else ""
    return if (PLAIN_SUBTYPE.matches(subtype)) subtype else "bin"
}

fun mediaFileStamp(now: LocalDateTime): String =
    String.format(
        Locale.ROOT,
        "%04d-%02d-%02d-%02d%02d%02d",
        now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second
    )

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
            runCatching { resolver.delete(target, null, null) }
            throw error
        }
    }

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

const val TILE_OUTLINE_ALPHA = 0.72f

private data class SavedCopy(val uri: Uri, val name: String, val mimeType: String, val nonce: Long)

enum class MediaAction { DOWNLOAD, RETRY }

@Composable
fun MediaActionButton(
    action: MediaAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    enabled: Boolean = true,
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
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

@Composable
fun MediaDownloadButton(
    source: Uri,
    modifier: Modifier = Modifier,
    name: String? = null,
    mimeType: String? = null,
    onTile: Color? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<SavedCopy?>(null) }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun failed(error: Throwable) {
        Toast.makeText(context, "Couldn't save: ${error.apiErrorMessage("the copy failed")}", Toast.LENGTH_LONG).show()
    }

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

@Composable
fun OutboxPendingBanner(
    pendingUploads: Int,
    modifier: Modifier = Modifier,
    entries: suspend () -> List<PendingEntry>,
    onRetry: suspend (entryId: String) -> Unit,
    onFix: suspend (entryId: String, payloadJson: String) -> Boolean = { _, _ -> false },
    onUseExisting: suspend (entryId: String) -> Boolean = { false },
    onDiscard: suspend (entryId: String) -> Boolean = { false },
    onChanged: () -> Unit = {},
    validateAadhaar: (String) -> String? = { null },
    onRetypeAadhaar: (suspend (entryId: String, number: String) -> Unit)? = null,
    onAnswerDuplicate: (suspend (entryId: String, samePerson: Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ink = MaterialTheme.field.onBrandTile
    var expanded by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<PendingMedia>>(emptyList()) }
    var refused by remember { mutableStateOf<List<PendingEntry>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var trayOpen by remember { mutableStateOf(false) }
    val revision by OfflineOutbox.revision.collectAsState()
    LaunchedEffect(pendingUploads, reload, revision) {
        refused = runCatching { entries() }.getOrDefault(emptyList()).filter { it.failure != null }
    }
    if (trayOpen) {
        OfflineOutboxTray(
            entries = entries,
            onRetry = onRetry,
            onFix = onFix,
            onUseExisting = onUseExisting,
            onDiscard = onDiscard,
            onChanged = { reload++; onChanged() },
            onClose = { trayOpen = false; reload++ }
        )
    }
    val needsAadhaar: (PendingEntry) -> Boolean = { entry ->
        onRetypeAadhaar != null && entry.createdId == null && AadhaarAtRest.numberWithheld(entry.payloadJson)
    }
    val heldAsDuplicate: (PendingEntry) -> Boolean = { entry -> onAnswerDuplicate != null && entry.isPossibleDuplicate() }
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
    LaunchedEffect(expanded, pendingUploads, reload, revision) {
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
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(if (expanded) "Hide files ▲" else "Files ▼", color = ink, style = MaterialTheme.typography.bodySmall)
        }
        val refusedOnly = refused.filterNot { it.needsReview || needsAadhaar(it) || heldAsDuplicate(it) }
        if (refusedOnly.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (refusedOnly.size == 1) "1 save was refused and needs you." else "${refusedOnly.size} saves were refused and need you.",
                    color = ink,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                MediaActionButton(
                    action = MediaAction.RETRY,
                    text = "Fix",
                    onClick = { trayOpen = true },
                    onTile = ink
                )
            }
        }
        refused.filter { needsAadhaar(it) }.forEach { entry ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "“${entry.label}”: ${entry.failure}",
                    color = ink,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                MediaActionButton(
                    action = MediaAction.RETRY,
                    text = "Type it again",
                    onClick = { retyping = entry },
                    onTile = ink
                )
            }
        }
        refused.filter(heldAsDuplicate).forEach { entry ->
            PossibleDuplicateTrayRow(
                entry = entry,
                ink = ink,
                onAnswer = { same -> scope.launch { onAnswerDuplicate?.invoke(entry.id, same); reload++ } }
            )
        }
        if (expanded) {
            refused.filter { it.needsReview && !needsAadhaar(it) && !heldAsDuplicate(it) }.forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Review “${entry.label}”: ${entry.failure}",
                        color = ink,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    MediaActionButton(
                        action = MediaAction.RETRY,
                        text = "Send again",
                        onClick = { scope.launch { onRetry(entry.id); reload++ } }
                    )
                }
            }
            if (files.isEmpty()) {
                Text("No files are queued with these entries.", color = ink, style = MaterialTheme.typography.bodySmall)
            }
            files.forEach { media ->
                val file = File(media.localPath)
                val label = media.originalFilename.ifBlank { file.name }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        color = ink,
                        style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun PossibleDuplicateTrayRow(entry: PendingEntry, ink: Color, onAnswer: (samePerson: Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("May already be recorded", color = ink, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(
            "“${entry.label}”: ${possibleDuplicateTrayText(entry) ?: entry.failure.orEmpty()}",
            color = ink,
            style = MaterialTheme.typography.bodySmall
        )
        ActionRow(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onAnswer(true) }) { Text("Same person") }
            OutlinedButton(onClick = { onAnswer(false) }) { Text("Different person") }
        }
    }
}

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
        title = { Text("Type the Aadhaar number again", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    last4?.let { "“${entry.label}” is safe. Type the number ending $it to send it." }
                        ?: "“${entry.label}” is safe. Type the artisan's Aadhaar number to send it.",
                    style = MaterialTheme.typography.bodyMedium
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
            ) { Text("Send it", style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now", style = MaterialTheme.typography.labelLarge) } }
    )
}
