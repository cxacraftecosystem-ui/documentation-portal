package com.fieldrepository.app.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/** A Coil loader that can decode a frame from a video file/URL for use as a thumbnail. */
@Composable
fun rememberMediaImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
}

/** Full-screen, type-aware media viewer: image, video (ExoPlayer) or audio (inline transport). */
@Composable
fun MediaViewerDialog(uri: Uri, mediaType: String, onSave: (() -> Unit)? = null, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .padding(16.dp)
        ) {
            when (mediaType.uppercase()) {
                "IMAGE" -> AsyncImage(
                    model = uri,
                    contentDescription = "Image preview",
                    modifier = Modifier.fillMaxSize()
                )
                "VIDEO" -> VideoPlayer(uri = uri, modifier = Modifier.align(Alignment.Center).fillMaxWidth())
                "AUDIO" -> Box(modifier = Modifier.align(Alignment.Center).fillMaxWidth()) { AudioPlayer(uri = uri) }
                else -> Text(
                    "This file type opens in an external app.",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Row(modifier = Modifier.align(Alignment.TopEnd)) {
                if (onSave != null) {
                    IconButton(onClick = onSave) {
                        Icon(Icons.Filled.Download, contentDescription = "Save to device", tint = Color.White)
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black, RoundedCornerShape(12.dp)),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
            }
        }
    )
}

/** Inline audio transport: play/pause + scrubber, backed by MediaPlayer. */
@Composable
fun AudioPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    var positionMs by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }

    val player = remember {
        MediaPlayer().apply {
            runCatching {
                setDataSource(context, uri)
                setOnPreparedListener {
                    durationMs = it.duration.coerceAtLeast(0)
                    ready = true
                }
                setOnCompletionListener {
                    playing = false
                    positionMs = 0
                    seekTo(0)
                }
                prepareAsync()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = runCatching { player.currentPosition }.getOrDefault(0)
            delay(200)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            enabled = ready,
            onClick = {
                if (playing) {
                    player.pause(); playing = false
                } else {
                    player.start(); playing = true
                }
            }
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Slider(
                value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                onValueChange = { fraction ->
                    val target = (fraction * durationMs).toInt()
                    positionMs = target
                    runCatching { player.seekTo(target) }
                },
                enabled = ready && durationMs > 0
            )
            Text("${formatMs(positionMs)} / ${formatMs(durationMs)}", color = Body, fontSize = 11.sp)
        }
    }
}

private fun formatMs(ms: Int): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

/**
 * Live recording cue: a blinking red dot, "Recording" + elapsed time, and a WhatsApp-style
 * amplitude waveform driven by MediaRecorder.getMaxAmplitude().
 */
@Composable
fun RecordingIndicator(getAmplitude: () -> Int, modifier: Modifier = Modifier, paused: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "rec")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink"
    )
    // Dedicated slow blink for the "Paused" cue (~1.1s per half-cycle).
    val pausedBlink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "pausedBlink"
    )
    val amplitudes = remember { mutableStateListOf<Float>() }
    var elapsedMs by remember { mutableStateOf(0L) }

    // The elapsed counter keeps its accumulated value across pause/resume. While recording we anchor
    // `start` to (now - elapsedMs) so, after a pause, counting continues from exactly where it stopped
    // instead of restarting at 00:00. Pausing simply cancels this effect, freezing elapsedMs.
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        val start = System.currentTimeMillis() - elapsedMs
        while (true) {
            val amp = runCatching { getAmplitude() }.getOrDefault(0)
            val norm = (amp.coerceIn(0, 32767).toFloat() / 32767f)
            amplitudes.add(norm)
            if (amplitudes.size > 48) amplitudes.removeAt(0)
            elapsedMs = System.currentTimeMillis() - start
            delay(100)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val dotColor = if (paused) Color(0xFF9AA0A6) else Color(0xFFD13438)
        val dotAlpha = if (paused) pausedBlink else blink
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(dotColor.copy(alpha = dotAlpha), CircleShape)
        )
        if (paused) {
            // Slowly blinking "Paused" label, with the (frozen) elapsed time alongside.
            Text("Paused", color = Body.copy(alpha = pausedBlink), fontSize = 12.sp)
            Text(formatMs(elapsedMs.toInt()), color = Body, fontSize = 12.sp)
        } else {
            Text("Recording  ${formatMs(elapsedMs.toInt())}", color = Body, fontSize = 12.sp)
        }
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
        ) {
            val barCount = amplitudes.size
            if (barCount == 0) return@Canvas
            val gap = 3.dp.toPx()
            val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
            amplitudes.forEachIndexed { i, level ->
                val h = (level.coerceIn(0.05f, 1f)) * size.height
                val x = i * (barWidth + gap)
                drawRoundRect(
                    color = Color(0xFFCC785C),
                    topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2f),
                    size = androidx.compose.ui.geometry.Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
    }
}

/** A tappable media row that opens the in-app viewer; reused for captured and saved media. */
@Composable
fun MediaThumb(
    uri: Uri,
    mediaType: String,
    title: String,
    subtitle: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val loader = rememberMediaImageLoader()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF181715), RoundedCornerShape(10.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(64.dp).background(SurfaceCard, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            when (mediaType.uppercase()) {
                "IMAGE" -> AsyncImage(model = uri, contentDescription = title, modifier = Modifier.fillMaxSize())
                "VIDEO" -> {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(uri).build(),
                        imageLoader = loader,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize()
                    )
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White)
                }
                else -> Text(mediaType.take(3).uppercase(), color = Body, fontSize = 11.sp)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Canvas, fontSize = 12.sp, maxLines = 1)
            subtitle?.let { Text(it, color = SurfaceCard, fontSize = 11.sp, maxLines = 1) }
        }
        TextButton(onClick = onOpen) { Text("Open", color = Color(0xFFE0C9B0)) }
    }
}
