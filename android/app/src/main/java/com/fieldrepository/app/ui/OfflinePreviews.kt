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

/** The longest side of a kept preview, in pixels: a tile is 64dp, the viewer may stretch it. */
private const val PREVIEW_MAX_SIDE = 480

/**
 * A saved file's preview for offline viewing: [file] is what the device already holds (null until
 * looked up, or when nothing is kept), and [keep] stores a freshly loaded tile image for next time.
 */
class OfflinePreview(val file: File?, val keep: (coil3.Image) -> Unit)

@Composable
fun rememberOfflinePreview(mediaId: String): OfflinePreview {
    var file by remember(mediaId) { mutableStateOf<File?>(null) }
    LaunchedEffect(mediaId) {
        file = withContext(Dispatchers.IO) { runCatching { PreviewCache.find(mediaId) }.getOrNull() }
    }
    return remember(mediaId, file) {
        OfflinePreview(file) { image ->
            // Refreshed at most once per showing: a tile that recomposes does not re-encode.
            if (file == null) {
                AppScope.io.launch {
                    val bytes = encodePreview(image) ?: return@launch
                    runCatching { PreviewCache.store(mediaId, bytes) }
                }
            }
        }
    }
}

/** A small JPEG of [image], or null when it cannot be drawn. */
internal fun encodePreview(image: coil3.Image): ByteArray? = runCatching {
    var bitmap = image.toBitmap()
    if (bitmap.config == Bitmap.Config.HARDWARE) bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest > PREVIEW_MAX_SIDE) {
        val scale = PREVIEW_MAX_SIDE.toFloat() / longest
        bitmap = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }
    ByteArrayOutputStream().use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out))
        out.toByteArray()
    }
}.getOrNull()
