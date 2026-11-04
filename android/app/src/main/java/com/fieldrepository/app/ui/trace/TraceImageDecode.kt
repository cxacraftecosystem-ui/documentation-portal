package com.fieldrepository.app.ui.trace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun traceDecodeForTrace(context: Context, uri: Uri): Bitmap =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        if (traceRotationTagged(context, uri)) {
            throw TraceHostFailure(
                TraceFailureKind.IMAGE_UNREADABLE,
                "this photo is rotated — straighten it or retake it square-on",
            )
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (opened.isFailure) {
            throw TraceHostFailure(
                TraceFailureKind.IMAGE_UNREADABLE,
                "this photo couldn't be opened",
            )
        }
        val storedWidth = bounds.outWidth
        val storedHeight = bounds.outHeight
        if (storedWidth < 1 || storedHeight < 1) {
            throw TraceHostFailure(TraceFailureKind.IMAGE_EMPTY, "${storedWidth}x$storedHeight")
        }

        val (targetWidth, targetHeight) = traceWorkingSize(storedWidth, storedHeight)
        var sample = 1
        while (maxOf(storedWidth, storedHeight) / (sample * 2) >= maxOf(targetWidth, targetHeight)) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
            ?: throw TraceHostFailure(TraceFailureKind.IMAGE_UNREADABLE, "the decoder refused it")

        if (decoded.width == targetWidth && decoded.height == targetHeight) return@withContext decoded
        val scaled = runCatching {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        }.getOrNull()
        if (scaled == null || scaled === decoded) {
            return@withContext decoded
        }
        decoded.recycle()
        scaled
    }

private fun traceRotationTagged(context: Context, uri: Uri): Boolean {
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrNull() ?: return false
    return orientation != ExifInterface.ORIENTATION_NORMAL &&
        orientation != ExifInterface.ORIENTATION_UNDEFINED
}

internal class TraceDisplayImage(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

internal suspend fun traceDecodeForDisplay(
    context: Context,
    uri: Uri,
    maxEdgePx: Int,
): TraceDisplayImage? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }
    val storedWidth = bounds.outWidth
    val storedHeight = bounds.outHeight
    if (storedWidth < 1 || storedHeight < 1) return@withContext null

    var sample = 1
    while (maxOf(storedWidth, storedHeight) / (sample * 2) >= maxEdgePx) {
        sample *= 2
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val decoded = runCatching {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull() ?: return@withContext null

    TraceDisplayImage(
        bitmap = decoded,
        sourceWidth = storedWidth,
        sourceHeight = storedHeight,
    )
}

internal fun traceDisplayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: "sketch"
}
