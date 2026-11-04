package com.fieldrepository.app.ui.trace

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

suspend fun traceRenderPngBytes(
    geometry: TraceGeometry,
    documentWidth: Int,
    documentHeight: Int,
    background: Int?,
): ByteArray? = withContext(Dispatchers.Default) {
    val size = tracePngSize(documentWidth, documentHeight)
    val bitmap = TracePlates.renderTrace(
        geometry = geometry,
        documentWidth = documentWidth,
        documentHeight = documentHeight,
        plateWidth = size.width,
        plateHeight = size.height,
        background = background,
    ) ?: return@withContext null

    try {
        val out = ByteArrayOutputStream(size.width * size.height)
        val encoded = runCatching {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }.getOrDefault(false)
        if (encoded) out.toByteArray() else null
    } finally {
        bitmap.recycle()
    }
}
