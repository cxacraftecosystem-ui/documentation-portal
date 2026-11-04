package com.fieldrepository.app.explorer.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fieldrepository.app.explorer.data.ExplorerDownloads
import com.fieldrepository.app.explorer.data.ExplorerEngine
import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.ui.FieldCircularProgressIndicator
import com.fieldrepository.app.ui.MediaViewerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

private sealed interface PreviewLoad {
    data object Resolving : PreviewLoad
    data class Ready(val url: String) : PreviewLoad
    data class Pdf(val file: File) : PreviewLoad
    data object Failed : PreviewLoad
}

@Composable
internal fun ExplorerPreviewDialog(entry: ExplorerEntry, onSave: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var load by remember(entry.key) { mutableStateOf<PreviewLoad>(PreviewLoad.Resolving) }
    LaunchedEffect(entry.key) {
        load = withContext(Dispatchers.IO) {
            val signed = ExplorerPaths.preview(entry)?.let { ExplorerDownloads.resolveSigned(context, it) }
            val url = signed ?: entry.url?.takeIf { it.isNotBlank() }
            when {
                url == null -> PreviewLoad.Failed
                entry.mediaType == "PDF" -> fetchPdf(context, entry, url)?.let { PreviewLoad.Pdf(it) } ?: PreviewLoad.Failed
                else -> PreviewLoad.Ready(url)
            }
        }
    }
    when (val now = load) {
        is PreviewLoad.Ready -> MediaViewerDialog(uri = Uri.parse(now.url), mediaType = entry.mediaType.orEmpty(), onSave = onSave, onDismiss = onDismiss)
        is PreviewLoad.Pdf -> PdfDialog(now.file, entry.name, onDismiss)
        else -> Dialog(onDismissRequest = onDismiss) {
            Column(
                Modifier.background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large).padding(24.dp).testTag("explorer.preview.status"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (now is PreviewLoad.Resolving) {
                    FieldCircularProgressIndicator()
                    Text(ExplorerText.preparing, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(ExplorerText.previewFailed, style = MaterialTheme.typography.bodyMedium)
                    SheetButtons(primary = ExplorerText.dismiss, onPrimary = onDismiss)
                }
            }
        }
    }
}

private const val PDF_LIMIT = 60L * 1024L * 1024L

private fun fetchPdf(context: android.content.Context, entry: ExplorerEntry, url: String): File? = runCatching {
    if ((entry.sizeBytes ?: 0) > PDF_LIMIT) return@runCatching null
    val dir = File(context.cacheDir, "explorer-preview").apply { mkdirs() }
    val target = File(dir, (entry.mediaId ?: entry.path.hashCode().toString()) + ".pdf")
    if (target.isFile && target.length() > 0) return@runCatching target
    val part = File(dir, target.name + ".part")
    ExplorerEngine.defaultStorage().newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return@runCatching null
        response.body.byteStream().use { input -> part.outputStream().use { input.copyTo(it, 64 * 1024) } }
    }
    if (!part.renameTo(target)) return@runCatching null
    target
}.getOrNull()

@Composable
private fun PdfDialog(file: File, title: String, onDismiss: () -> Unit) {
    val renderer = remember(file) {
        runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xF0101010)).testTag("explorer.preview.pdf")) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = ExplorerText.dismiss, tint = Color.White) }
            }
            if (renderer == null) {
                Text(ExplorerText.previewFailed, color = Color.White, modifier = Modifier.padding(24.dp))
                return@Column
            }
            val pages = remember(renderer) { (0 until renderer.pageCount).toList() }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pages) { index -> PdfPage(renderer, index) }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, index: Int) {
    var bitmap by remember(renderer, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(renderer, index) {
        bitmap = withContext(Dispatchers.IO) {
            synchronized(renderer) {
                runCatching {
                    renderer.openPage(index).use { page ->
                        val width = 1080
                        val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(android.graphics.Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) {
            FieldCircularProgressIndicator(modifier = Modifier.padding(24.dp))
        } else {
            Image(image.asImageBitmap(), contentDescription = "Page ${index + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
        }
    }
}
