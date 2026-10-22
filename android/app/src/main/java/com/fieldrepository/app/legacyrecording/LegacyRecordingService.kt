package com.fieldrepository.app.legacyrecording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fieldrepository.app.MainActivity
import com.fieldrepository.app.R

/**
 * Keeps the legacy questionnaire recorder alive: a foreground service of type `microphone` for as
 * long as a [LegacyTake] runs. Without it a locked screen or an app switch silences the microphone,
 * and a kill leaves an MPEG-4 file with no index, which nothing can play.
 *
 * The recorder itself stays in [LegacyTake]; the form drives Pause and Stop. When the system takes
 * the service away, or the app is swiped from recents, the take is finalised so the file plays, and
 * the form claims it the next time its recorder stops or is disposed.
 */
class LegacyRecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!LegacyTake.active) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        LegacyTake.finaliseForLater()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        LegacyTake.finaliseForLater()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(TITLE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val TITLE = "Recording interview"
        // The same channel the v2 recorder posts to, so settings show one "Recording" channel.
        private const val CHANNEL_ID = "questionnaire-recording"
        private const val NOTIFICATION_ID = 7302

        /** Call right after [LegacyTake.begin], from the Record tap while the app is visible. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LegacyRecordingService::class.java)) }
        }

        /** Call after [LegacyTake.finalise]; the take is already safe. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LegacyRecordingService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
    }
}
