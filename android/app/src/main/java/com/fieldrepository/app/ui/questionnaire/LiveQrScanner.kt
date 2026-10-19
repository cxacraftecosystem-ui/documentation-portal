package com.fieldrepository.app.ui.questionnaire

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fieldrepository.app.data.JoinQr
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A live camera preview that reads the join QR as soon as it is in frame (DESIGN §3.7). Works with no
 * signal: the payload carries the session itself. [onRead] fires once, with the decoded text; the
 * camera is released when the composable leaves. The caller holds the camera permission.
 */
@Composable
fun LiveQrScanner(onRead: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val analysisThread = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    DisposableEffect(lifecycle) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisThread) { image ->
                image.use {
                    if (done.get()) return@use
                    val plane = it.planes[0]
                    val buffer = plane.buffer
                    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                    val text = JoinQr.readLuminance(bytes, plane.rowStride, it.width, it.height)
                    if (text != null && JoinQr.decode(text) != null && done.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute { onRead(text) }
                    }
                }
            }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            analysisThread.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier.fillMaxWidth().aspectRatio(1f))
}
