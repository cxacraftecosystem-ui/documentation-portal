package com.fieldrepository.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fieldrepository.app.MainActivity
import com.fieldrepository.app.R
import com.fieldrepository.app.data.AppScope
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.QuestionnaireV2
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Questionnaire takes record HERE, not in a composable (DESIGN §6.1.1): a foreground service of type
 * `microphone`, started from the Record tap while the app is visible. A locked screen, an app switch
 * or a collapsed sheet no longer ends a 30-minute interview.
 *
 * Every exit path finalises the take, never drops it: Stop, the notification's Stop, the workspace
 * leaving (Back, dispose), a phone call (audio-focus loss), and the recorder's own error or info
 * listener. Finalising remuxes to `.m4a`, hashes it and puts it in the session outbox, so the take is
 * safe even if no screen is open to receive it.
 *
 * The notification reads "Recording Section D · 12:40", with Pause and Stop.
 */
class RecordingService : Service() {

    sealed interface State {
        data object Idle : State
        data class Recording(val draft: RecordingDraft, val startedAtElapsed: Long, val pausedMs: Long, val pausedAt: Long?) : State {
            fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long =
                (pausedAt ?: now) - startedAtElapsed - pausedMs
        }
        /** The last take that ended, and why ("stop", "call", "error:…"). The next Record offers Part 2 after a call. */
        data class Finished(val draft: RecordingDraft, val reason: String) : State
    }

    private var recorder: AdtsRecorder? = null
    private var focusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val s = state.value as? State.Recording ?: return
            notifyProgress(s)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_CLIP)?.let { start(it) }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> finish(intent.getStringExtra(EXTRA_REASON) ?: "stop")
        }
        return START_NOT_STICKY
    }

    private fun start(clientClipKey: String) {
        if (state.value is State.Recording) return
        val draft = RecordingDrafts.read(filesDir, clientClipKey) ?: run { stopSelf(); return }
        ensureChannel(this)
        val initial = State.Recording(draft, SystemClock.elapsedRealtime(), 0, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(initial), type)
        val rec = AdtsRecorder(this, File(draft.adtsPath)) { why -> handler.post { finish(why) } }
        try {
            rec.start()
        } catch (failed: Exception) {
            rec.stop()
            // The workspace reads this reason and says the recorder could not start (audit M23).
            _state.value = State.Finished(draft, REASON_START_FAILED)
            stopForegroundCompat()
            stopSelf()
            return
        }
        recorder = rec
        requestFocus()
        _state.value = initial
        handler.post(ticker)
    }

    private fun pause() {
        val s = state.value as? State.Recording ?: return
        if (s.pausedAt != null) return
        recorder?.pause()
        _state.value = s.copy(pausedAt = SystemClock.elapsedRealtime())
        notifyProgress(state.value as State.Recording)
    }

    private fun resume() {
        val s = state.value as? State.Recording ?: return
        val pausedAt = s.pausedAt ?: return
        recorder?.resume()
        _state.value = s.copy(pausedMs = s.pausedMs + (SystemClock.elapsedRealtime() - pausedAt), pausedAt = null)
    }

    private fun finish(reason: String) {
        val s = state.value as? State.Recording ?: run { stopSelf(); return }
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(endAfterLongLoss)
        pausedForFocus = false
        recorder?.stop()
        recorder = null
        abandonFocus()
        val draft = s.draft.copy(finished = true, durationMs = s.elapsedMs())
        RecordingDrafts.write(filesDir, draft)
        _state.value = State.Finished(draft, reason)
        stopForegroundCompat()
        val app = applicationContext
        AppScope.io.launch {
            runCatching {
                val queued = QuestionnaireV2.get(app).queueTake(draft)
                _state.value = State.Finished(queued, reason)
                // A group take waits for the workspace's "Answered by" chips (§3.4), which send it;
                // a solo take, or one whose workspace is gone, goes now.
                if (queued.subjects.size <= 1 || reason == "leave" || reason == "destroyed") QuestionnaireV2.get(app).requestSync()
            }.onFailure {
                // Never swallowed (audit C2): the draft stays on the phone unqueued, so P13 offers it
                // back, and the workspace says so now.
                _state.value = State.Finished(draft, REASON_QUEUE_FAILED)
            }
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        // The system is taking the service away: keep what was recorded.
        if (state.value is State.Recording) finish("destroyed")
        super.onDestroy()
    }

    // Audio focus (audit M23: any brief loss used to end the take). A permanent loss ends the take
    // cleanly and the next Record offers Part 2. A brief one (a call, the assistant) pauses it and
    // focus coming back resumes it, so the file stays one take; a brief loss that never comes back
    // ends it after [TRANSIENT_LOSS_LIMIT_MS]. Ducking is ignored: the microphone is unaffected.
    private var pausedForFocus = false
    private val endAfterLongLoss = Runnable { if (pausedForFocus) finish("call") }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        handler.post { onFocusChange(change) }
    }

    private fun onFocusChange(change: Int) {
        val action = focusAction(change, pausedForFocus)
        when (action) {
            FocusAction.END -> finish("call")
            FocusAction.PAUSE -> {
                val s = state.value as? State.Recording ?: return
                if (s.pausedAt != null) return
                pausedForFocus = true
                pause()
                handler.postDelayed(endAfterLongLoss, TRANSIENT_LOSS_LIMIT_MS)
            }
            FocusAction.RESUME -> {
                pausedForFocus = false
                handler.removeCallbacks(endAfterLongLoss)
                resume()
            }
            FocusAction.NONE -> Unit
        }
    }

    private fun requestFocus() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        audio.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonFocus() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun notifyProgress(s: State.Recording) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(s))
    }

    private fun buildNotification(s: State.Recording): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun action(action: String, request: Int) = PendingIntent.getService(
            this, request, Intent(this, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val paused = s.pausedAt != null
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(QuestionnaireCopy.notification(s.draft.label, s.elapsedMs()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, QuestionnaireCopy.s(if (paused) "recorder.resume" else "recorder.pause"), action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, QuestionnaireCopy.s("recorder.stop"), action(ACTION_STOP, 2))
            .build()
    }

    enum class FocusAction { END, PAUSE, RESUME, NONE }

    companion object {
        /** The recorder could not start; the draft holds nothing. */
        const val REASON_START_FAILED = "error:start"
        /** The take was recorded but could not be queued; its draft waits on the phone for P13. */
        const val REASON_QUEUE_FAILED = "error:queue"
        /** A brief focus loss longer than this (a long call) ends the take instead of holding it paused. */
        const val TRANSIENT_LOSS_LIMIT_MS = 10 * 60 * 1000L

        /** What a focus change does to a running take; pure so a JVM test pins it. */
        fun focusAction(change: Int, pausedForFocus: Boolean): FocusAction = when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> FocusAction.END
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusAction.PAUSE
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedForFocus) FocusAction.RESUME else FocusAction.NONE
            else -> FocusAction.NONE
        }

        const val ACTION_START = "com.fieldrepository.app.recording.START"
        const val ACTION_PAUSE = "com.fieldrepository.app.recording.PAUSE"
        const val ACTION_RESUME = "com.fieldrepository.app.recording.RESUME"
        const val ACTION_STOP = "com.fieldrepository.app.recording.STOP"
        const val EXTRA_CLIP = "clientClipKey"
        const val EXTRA_REASON = "reason"
        private const val CHANNEL_ID = "questionnaire-recording"
        private const val NOTIFICATION_ID = 7301

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state

        /** Writes nothing: the caller has already written [draft] with [RecordingDrafts.write]. */
        fun start(context: Context, draft: RecordingDraft) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(ACTION_START).putExtra(EXTRA_CLIP, draft.clientClipKey))
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context, reason: String = "stop") {
            if (state.value !is State.Recording) return
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_REASON, reason))
        }

        private fun send(context: Context, action: String) {
            if (state.value !is State.Recording) return
            context.startService(Intent(context, RecordingService::class.java).setAction(action))
        }

        fun clearFinished() {
            if (_state.value is State.Finished) _state.value = State.Idle
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
    }
}
