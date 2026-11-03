package com.fieldrepository.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.OffsetDateTime

@Serializable
data class AppNotificationDto(
    val id: String,
    val kind: String = "",
    val title: String = "",
    val body: String? = null,
    val link: String? = null,
    val read: Boolean = false,
    val readAt: String? = null,
    val createdAt: String = "",
)

@Serializable
data class NotificationPageDto(
    val items: List<AppNotificationDto> = emptyList(),
    val unreadCount: Int = 0,
    val hasMore: Boolean = false,
)

@Serializable
data class NotificationReadBody(
    val ids: List<String> = emptyList(),
    val all: Boolean = false,
)

@Serializable
data class NotificationReadResultDto(
    val updated: Int = 0,
    val unreadCount: Int = 0,
)

@Serializable
data class NotificationInbox(
    val userId: String = "",
    val items: List<AppNotificationDto> = emptyList(),
    val pendingReadIds: List<String> = emptyList(),
    val pendingReadAll: Boolean = false,
    val alertedIds: List<String> = emptyList(),
    val seeded: Boolean = false,
    val syncedAt: String? = null,
)

interface NotificationApi {
    suspend fun list(limit: Int): NotificationPageDto
    suspend fun markRead(body: NotificationReadBody): NotificationReadResultDto
}

object NotificationInboxRules {
    const val KEEP = 100
    const val ALERTED_KEEP = 300
    const val MAX_SEPARATE_ALERTS = 3
    const val MAX_IDS_PER_MARK = 500

    fun unread(inbox: NotificationInbox): Int = inbox.items.count { !it.read }

    fun markRead(inbox: NotificationInbox, ids: Collection<String>, now: String): NotificationInbox {
        val wanted = ids.toSet()
        if (wanted.isEmpty()) return inbox
        val items = inbox.items.map { if (!it.read && it.id in wanted) it.copy(read = true, readAt = now) else it }
        val pending = (inbox.pendingReadIds + wanted.filter { id -> inbox.items.any { it.id == id } }).distinct()
        return inbox.copy(items = items, pendingReadIds = pending)
    }

    fun markAllRead(inbox: NotificationInbox, now: String): NotificationInbox =
        markRead(inbox, inbox.items.filter { !it.read }.map { it.id }, now)

    fun replayBody(inbox: NotificationInbox): NotificationReadBody? {
        val legacyAll = if (inbox.pendingReadAll) inbox.items.map { it.id } else emptyList()
        val ids = (inbox.pendingReadIds + legacyAll).distinct().take(MAX_IDS_PER_MARK)
        if (ids.isEmpty() && !inbox.pendingReadAll) return null
        return NotificationReadBody(ids = ids)
    }

    fun replayed(inbox: NotificationInbox, sent: NotificationReadBody, sentLegacyAll: Boolean): NotificationInbox {
        val done = sent.ids.toSet()
        return inbox.copy(
            pendingReadIds = inbox.pendingReadIds.filter { it !in done },
            pendingReadAll = inbox.pendingReadAll && !sentLegacyAll,
        )
    }

    fun merge(local: NotificationInbox, page: NotificationPageDto, now: String): NotificationInbox {
        val pending = local.pendingReadIds.toSet()
        val held = if (local.pendingReadAll) local.items.map { it.id }.toSet() else emptySet()
        val items = page.items.take(KEEP).map { item ->
            if (!item.read && (item.id in pending || item.id in held)) item.copy(read = true, readAt = now) else item
        }
        return local.copy(items = items, syncedAt = now)
    }

    fun freshAlerts(before: NotificationInbox, after: NotificationInbox): List<AppNotificationDto> {
        if (!before.seeded) return emptyList()
        val seen = before.alertedIds.toSet()
        return after.items.filter { !it.read && it.id !in seen }
    }

    fun remember(inbox: NotificationInbox): NotificationInbox {
        val ids = (inbox.items.map { it.id } + inbox.alertedIds).distinct().take(ALERTED_KEEP)
        return inbox.copy(alertedIds = ids, seeded = true)
    }

    fun replayRefusedForGood(status: Int?): Boolean = status != null && status in 400..499 && status != 401

    fun summaryTitle(count: Int): String = if (count == 1) "1 new notification" else "$count new notifications"
}

fun notificationInstant(value: String?): Instant? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(text) }.getOrNull()
}

enum class NotificationTarget { TASKS, SETTINGS, REVIEW }

fun notificationTarget(link: String?): NotificationTarget? =
    when (link?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')) {
        "/tasks" -> NotificationTarget.TASKS
        "/settings" -> NotificationTarget.SETTINGS
        "/review" -> NotificationTarget.REVIEW
        else -> null
    }

object NotificationStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val mutex = Mutex()

    private fun file(context: Context, userId: String): File {
        val safe = userId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "account" }
        return File(File(context.noBackupFilesDir, "notifications"), "$safe.json")
    }

    fun load(context: Context, userId: String): NotificationInbox {
        val target = file(context, userId)
        if (!target.exists()) return NotificationInbox(userId = userId)
        return runCatching { json.decodeFromString(NotificationInbox.serializer(), target.readText()) }
            .getOrNull()
            ?.takeIf { it.userId == userId }
            ?: NotificationInbox(userId = userId)
    }

    fun save(context: Context, inbox: NotificationInbox) {
        val target = file(context, inbox.userId)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(json.encodeToString(NotificationInbox.serializer(), inbox).toByteArray())
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    suspend fun update(context: Context, userId: String, change: (NotificationInbox) -> NotificationInbox): NotificationInbox =
        mutex.withLock {
            val next = change(load(context, userId))
            save(context, next)
            NotificationState.publish(next)
            next
        }

    fun forget(context: Context, userId: String) {
        runCatching { file(context, userId).delete() }
    }
}

object NotificationState {
    private val _inbox = MutableStateFlow<NotificationInbox?>(null)
    val inbox: StateFlow<NotificationInbox?> = _inbox

    private val _openRequests = MutableStateFlow(0L)
    val openRequests: StateFlow<Long> = _openRequests

    fun publish(inbox: NotificationInbox) {
        _inbox.value = inbox
    }

    fun clear() {
        _inbox.value = null
    }

    fun requestOpen() {
        _openRequests.value = System.currentTimeMillis()
    }
}

object NotificationSync {
    private val running = Mutex()

    suspend fun sync(context: Context, api: NotificationApi, userId: String): Int? {
        if (userId.isBlank()) return null
        if (!running.tryLock()) return null
        try {
            val app = context.applicationContext
            val before = NotificationStore.load(app, userId)
            NotificationState.publish(before)
            val body = NotificationInboxRules.replayBody(before)
            if (body != null) {
                val sent = if (body.ids.isEmpty()) Result.success(Unit) else runCatching { api.markRead(body) }
                if (sent.isSuccess || NotificationInboxRules.replayRefusedForGood((sent.exceptionOrNull() as? HttpException)?.code())) {
                    NotificationStore.update(app, userId) { NotificationInboxRules.replayed(it, body, before.pendingReadAll) }
                }
            }
            val page = runCatching { api.list(NotificationInboxRules.KEEP) }.getOrNull() ?: return null
            var alerts: List<AppNotificationDto> = emptyList()
            val stored = NotificationStore.update(app, userId) { current ->
                val merged = NotificationInboxRules.merge(current, page, Instant.now().toString())
                alerts = NotificationInboxRules.freshAlerts(current, merged)
                NotificationInboxRules.remember(merged)
            }
            if (alerts.isNotEmpty()) LocalAlerts.post(app, alerts)
            return NotificationInboxRules.unread(stored)
        } finally {
            running.unlock()
        }
    }

    suspend fun markRead(context: Context, userId: String, ids: Collection<String>) {
        NotificationStore.update(context.applicationContext, userId) {
            NotificationInboxRules.markRead(it, ids, Instant.now().toString())
        }
    }

    suspend fun markAllRead(context: Context, userId: String) {
        NotificationStore.update(context.applicationContext, userId) {
            NotificationInboxRules.markAllRead(it, Instant.now().toString())
        }
    }

    fun signOut(context: Context, userId: String?) {
        val app = context.applicationContext
        if (!userId.isNullOrBlank()) NotificationStore.forget(app, userId)
        LocalAlerts.cancelAll(app)
        NotificationState.clear()
    }
}

object LocalAlerts {
    const val CHANNEL_ID = "activity"
    const val EXTRA_OPEN_NOTIFICATIONS = "open_notifications"
    private const val SUMMARY_ID = 0x4E_0001

    fun permissionNeeded(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun permitted(context: Context): Boolean {
        if (permissionNeeded() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notices about your work"
            }
        )
    }

    private fun openIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launch.putExtra(EXTRA_OPEN_NOTIFICATIONS, true)
        return PendingIntent.getActivity(
            context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun post(context: Context, alerts: List<AppNotificationDto>) {
        if (alerts.isEmpty() || !permitted(context)) return
        runCatching {
            ensureChannel(context)
            val manager = NotificationManagerCompat.from(context)
            val tap = openIntent(context)
            fun builder() = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(com.fieldrepository.app.R.drawable.ic_stat_notification)
                .setAutoCancel(true)
                .setContentIntent(tap)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            if (alerts.size > NotificationInboxRules.MAX_SEPARATE_ALERTS) {
                manager.notify(
                    SUMMARY_ID,
                    builder()
                        .setContentTitle(NotificationInboxRules.summaryTitle(alerts.size))
                        .setContentText(alerts.first().title)
                        .build()
                )
                return@runCatching
            }
            alerts.forEach { alert ->
                manager.notify(
                    alert.id.hashCode(),
                    builder()
                        .setContentTitle(alert.title)
                        .apply { alert.body?.takeIf { it.isNotBlank() }?.let { setContentText(it); setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
                        .build()
                )
            }
        }
    }

    fun cancelAll(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.activeNotifications
                .filter { it.notification.channelId == CHANNEL_ID }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }

    fun takeTap(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOTIFICATIONS, false) == true) {
            intent.removeExtra(EXTRA_OPEN_NOTIFICATIONS)
            NotificationState.requestOpen()
        }
    }
}
