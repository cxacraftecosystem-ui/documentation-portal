package com.fieldrepository.app.explorer.data

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ExplorerAudioLine(val startMs: Long, val endMs: Long = 0, val speaker: String? = null, val text: String)

data class ExplorerAudioItem(
    val mediaId: String,
    val title: String,
    val subtitle: String? = null,
    val url: String,
    val lines: List<ExplorerAudioLine> = emptyList(),
)

data class ExplorerAudioState(
    val item: ExplorerAudioItem? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val line: ExplorerAudioLine? = null,
    val failed: Boolean = false,
) {
    val active: Boolean get() = item != null
}

object ExplorerAudioLines {
    fun index(lines: List<ExplorerAudioLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines.first().startMs) return -1
        var low = 0
        var high = lines.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lines[mid].startMs <= positionMs) low = mid else high = mid - 1
        }
        return low
    }

    fun at(lines: List<ExplorerAudioLine>, positionMs: Long): ExplorerAudioLine? = lines.getOrNull(index(lines, positionMs))

    fun spoken(line: ExplorerAudioLine?): String? = line?.let { l ->
        val text = l.text.trim().takeIf { it.isNotEmpty() } ?: return@let null
        l.speaker?.takeIf { it.isNotBlank() }?.let { "$it: “$text”" } ?: "“$text”"
    }

    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

object ExplorerAudio {
    const val STEP_MS = 10_000L
    val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

    private val mutable = MutableStateFlow(ExplorerAudioState())
    val state: StateFlow<ExplorerAudioState> = mutable.asStateFlow()

    @Volatile internal var current: ExplorerAudioItem? = null
        private set

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var controller: MediaController? = null
    private var pending: ListenableFuture<MediaController>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            mutable.value = mutable.value.copy(playing = false, buffering = false, failed = true)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            publish()
            if (controller?.isPlaying == true) main.postDelayed(this, TICK_MS)
        }
    }

    fun play(context: Context, item: ExplorerAudioItem, startMs: Long = 0) {
        val app = context.applicationContext
        current = item
        mutable.value = ExplorerAudioState(item = item, buffering = true, positionMs = startMs, line = ExplorerAudioLines.at(item.lines, startMs))
        scope.launch {
            val url = withContext(Dispatchers.IO) { playable(app, item.url) }
            if (current?.mediaId != item.mediaId) return@launch
            withController(app) { player ->
                player.setMediaItem(mediaItem(item, url, ExplorerAudioLines.spoken(ExplorerAudioLines.at(item.lines, startMs))), startMs)
                player.prepare()
                player.play()
            }
        }
    }

    fun toggle() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else player.play()
        publish()
    }

    fun seekBy(deltaMs: Long) {
        val player = controller ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val next = (player.currentPosition + deltaMs).coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it }
        player.seekTo(next)
        publish()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
        publish()
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackParameters(PlaybackParameters(speed))
        publish()
    }

    fun nextSpeed(now: Float): Float = SPEEDS.firstOrNull { it > now + 0.01f } ?: SPEEDS.first()

    fun stop() {
        current = null
        main.removeCallbacks(ticker)
        controller?.let { player ->
            player.stop()
            player.clearMediaItems()
            player.removeListener(listener)
            player.release()
        }
        controller = null
        pending?.let { MediaController.releaseFuture(it) }
        pending = null
        mutable.value = ExplorerAudioState()
    }

    private fun withController(context: Context, block: (MediaController) -> Unit) {
        controller?.let {
            block(it)
            return
        }
        val future = pending ?: MediaController.Builder(context, SessionToken(context, ComponentName(context, ExplorerAudioService::class.java)))
            .buildAsync()
            .also { pending = it }
        future.addListener({
            val built = runCatching { future.get() }.getOrNull()
            if (built == null) {
                pending = null
                mutable.value = mutable.value.copy(buffering = false, failed = true)
                return@addListener
            }
            if (controller == null) {
                controller = built
                built.addListener(listener)
            }
            block(built)
            publish()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun publish() {
        val player = controller ?: return
        val item = current ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        mutable.value = ExplorerAudioState(
            item = item,
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = position,
            durationMs = duration,
            speed = player.playbackParameters.speed,
            line = ExplorerAudioLines.at(item.lines, position),
            failed = player.playerError != null,
        )
        main.removeCallbacks(ticker)
        if (player.isPlaying) main.postDelayed(ticker, TICK_MS)
    }

    internal fun mediaItem(item: ExplorerAudioItem, url: String, line: String?): MediaItem = MediaItem.Builder()
        .setMediaId(item.mediaId)
        .setUri(Uri.parse(url))
        .setMediaMetadata(metadata(item, line))
        .build()

    internal fun metadata(item: ExplorerAudioItem, line: String?): MediaMetadata = MediaMetadata.Builder()
        .setTitle(item.title)
        .setArtist(line ?: item.subtitle ?: ExplorerPortal.PRODUCT)
        .setAlbumTitle(listOfNotNull(ExplorerPortal.PRODUCT, item.subtitle).joinToString(" · "))
        .setDisplayTitle(item.title)
        .setSubtitle(line ?: item.subtitle)
        .build()

    private fun playable(context: Context, url: String): String {
        if (ExplorerPaths.absolute(url)) return url
        return ExplorerDownloads.resolveSigned(context, url) ?: ExplorerPaths.resolve(ExplorerPortal.baseUrl(), url)
    }

    private const val TICK_MS = 250L
}

class ExplorerAudioService : MediaSessionService() {
    private var session: MediaSession? = null
    private val main = Handler(Looper.getMainLooper())
    private var shown: String? = null

    private val lines = object : Runnable {
        override fun run() {
            refreshLine()
            main.postDelayed(this, LINE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(ExplorerAudio.STEP_MS)
            .setSeekForwardIncrementMs(ExplorerAudio.STEP_MS)
            .build()
        val builder = MediaSession.Builder(this, player)
        launchIntent()?.let { builder.setSessionActivity(it) }
        session = builder.build()
        main.post(lines)
    }

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: return null
        return PendingIntent.getActivity(this, 0x4E02, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun refreshLine() {
        val player = session?.player ?: return
        val item = ExplorerAudio.current ?: return
        if (player.mediaItemCount == 0 || player.currentMediaItem?.mediaId != item.mediaId) return
        val spoken = ExplorerAudioLines.spoken(ExplorerAudioLines.at(item.lines, player.currentPosition))
        if (spoken == shown) return
        shown = spoken
        val now = player.currentMediaItem ?: return
        val next = now.buildUpon().setMediaMetadata(ExplorerAudio.metadata(item, spoken)).build()
        runCatching { player.replaceMediaItem(player.currentMediaItemIndex, next) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(lines)
        session?.let { s ->
            s.player.release()
            s.release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val LINE_MS = 500L
    }
}
