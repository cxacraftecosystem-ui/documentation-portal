package com.fieldrepository.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import com.fieldrepository.app.data.AppScope
import com.fieldrepository.app.data.PreviewCache
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal const val PREVIEW_MAX_SIDE = 480

class OfflinePreview(val file: File?, val keep: (coil3.Image) -> Unit)

@Composable
fun rememberOfflinePreview(mediaId: String): OfflinePreview {
    var file by remember(mediaId) { mutableStateOf<File?>(null) }
    LaunchedEffect(mediaId) {
        file = withContext(Dispatchers.IO) { runCatching { PreviewCache.find(mediaId) }.getOrNull() }
    }
    val keeper = remember(mediaId) { PreviewKeeper(mediaId) }
    return remember(mediaId, file) {
        val held = file != null
        OfflinePreview(file) { image -> keeper.offer(held) { encodePreview(image) } }
    }
}

internal class PreviewKeeper(
    private val mediaId: String,
    private val ticket: () -> PreviewCache.Ticket? = PreviewCache::ticket,
    private val launch: (suspend () -> Unit) -> Unit = { block -> AppScope.io.launch { block() } },
    private val store: (PreviewCache.Ticket, String, ByteArray) -> Unit = PreviewCache::store
) {
    private val offered = AtomicBoolean(false)

    fun offer(alreadyHeld: Boolean, encode: () -> ByteArray?) {
        if (alreadyHeld || !offered.compareAndSet(false, true)) return
        val pass = ticket()
        if (pass == null) {
            offered.set(false)
            return
        }
        launch {
            val bytes = encode() ?: return@launch
            runCatching { store(pass, mediaId, bytes) }
        }
    }
}

internal fun previewSize(width: Int, height: Int, maxSide: Int = PREVIEW_MAX_SIDE): Pair<Int, Int>? {
    val longest = maxOf(width, height)
    if (longest <= maxSide) return null
    val scale = maxSide.toFloat() / longest
    return (width * scale).toInt().coerceIn(1, maxSide) to (height * scale).toInt().coerceIn(1, maxSide)
}

internal fun encodePreview(image: coil3.Image): ByteArray? = runCatching {
    var bitmap = image.toBitmap()
    if (bitmap.config == Bitmap.Config.HARDWARE) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    previewSize(bitmap.width, bitmap.height)?.let { (w, h) -> bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true) }
    ByteArrayOutputStream().use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out))
        out.toByteArray()
    }
}.getOrNull()
