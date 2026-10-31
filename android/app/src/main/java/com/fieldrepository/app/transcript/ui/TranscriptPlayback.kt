package com.fieldrepository.app.transcript.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * What the viewer needs of a player: where it is, whether it plays, and a seek. Karaoke reads
 * [positionSeconds]; a timestamp, a word or a citation chip calls [seek]. The Compose tests drive a
 * [ManualPlayback] instead of real audio.
 */
@Stable
interface TranscriptPlayback {
    val available: Boolean
    val playing: Boolean
    val positionSeconds: Double
    val durationSeconds: Double
    fun toggle()
    /** Moves to [seconds] and, unless [play] is false (the scrubber), plays from there. */
    fun seek(seconds: Double, play: Boolean = true)
}

/** A playback the test (or a recording with no audio yet) moves by hand. */
class ManualPlayback(available: Boolean = true, duration: Double = 0.0) : TranscriptPlayback {
    override var available by mutableStateOf(available)
    override var playing by mutableStateOf(false)
    override var positionSeconds by mutableDoubleStateOf(0.0)
    override var durationSeconds by mutableDoubleStateOf(duration)
    override fun toggle() { playing = !playing }
    override fun seek(seconds: Double, play: Boolean) { positionSeconds = seconds; if (play) playing = true }
}

private class ExoPlayback(private val player: ExoPlayer) : TranscriptPlayback {
    override val available = true
    override var playing by mutableStateOf(false)
    override var positionSeconds by mutableDoubleStateOf(0.0)
    override var durationSeconds by mutableDoubleStateOf(0.0)
    override fun toggle() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }
    override fun seek(seconds: Double, play: Boolean) {
        player.seekTo((seconds * 1000).toLong().coerceAtLeast(0))
        positionSeconds = seconds
        if (play) player.play()
    }
    fun tick() {
        playing = player.isPlaying
        positionSeconds = player.currentPosition / 1000.0
        val d = player.duration
        if (d > 0) durationSeconds = d / 1000.0
    }
}

/**
 * The recording's player, or a [ManualPlayback] that says "no audio" when there is no [uri].
 * Paused when the app leaves the screen, for the reason `ui/MediaPlayers.kt` gives (Android 17
 * silences a hidden app's audio).
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberTranscriptPlayback(uri: Uri?): TranscriptPlayback {
    val context = LocalContext.current
    if (uri == null) return remember { ManualPlayback(available = false) }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = false
        }
    }
    val playback = remember(player) { ExoPlayback(player) }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    LaunchedEffect(player) {
        while (true) {
            playback.tick()
            // Fast enough that a word lights as it is said; a turn is several hundred ms a word.
            delay(80)
        }
    }
    return playback
}
