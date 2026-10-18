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

/*
 * THE IN-APP ACTIVITY LIST (C25), the handset's half: the wire shapes, the offline copy, the sync
 * that keeps it, and the LOCAL alert posted after a sync finds something new. No Firebase: the phone
 * asks, on the app-wide poll and on the background sync job, and nothing is pushed to it.
 *
 * The server writes a notice with the same call that queues its e-mail, so the list holds every
 * notice even where mail is off. The four routes, and every name here, are the same in the
 * documentation portal and the designer portal:
 *
 *   GET  notifications?limit=          -> NotificationPageDto
 *   POST notifications/read  {ids}|{all} -> NotificationReadResultDto
 *
 * OFFLINE. The list is kept on the device (one JSON file per account, written temp-then-rename with
 * an fsync) and drawn from there, so it opens with no signal. A notice opened offline is marked read
 * HERE at once and the mark is queued in the same file, AS IDS: "Mark all as read" queues the ids
 * of the unread notices the device holds at the tap, never `all`, so a notice that reached the
 * server afterwards stays unread and is alerted. The next sync replays the queue first and then
 * clears only the ids it sent, so a tap made while that call was in flight stays queued. The routes
 * are idempotent, so a replay that already landed changes nothing.
 *
 * SIGN-OUT. [NotificationSync.signOut] deletes the account's copy, takes its alerts out of the
 * shade and empties the in-memory list: the next person on a shared phone sees none of it.
 *
 * ALERTS. After a sync, every unread notice this device has not alerted before is posted as a
 * system notification — but never on the first sync for an account (a phone signing in must not
 * buzz with a month of history), never without Android 13's POST_NOTIFICATIONS grant, and as one
 * summary when more than [NotificationInboxRules.MAX_SEPARATE_ALERTS] arrive at once.
 */

@Serializable
data class AppNotificationDto(
    val id: String,
    val kind: String = "",
    val title: String = "",
    val body: String? = null,
    /** An app path ("/tasks", "/settings"…), or null. */
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

/** The device's copy for one account: the list, the marks waiting to be sent, and what was alerted. */
@Serializable
data class NotificationInbox(
    val userId: String = "",
    val items: List<AppNotificationDto> = emptyList(),
    val pendingReadIds: List<String> = emptyList(),
    /**
     * Written only by builds before the all-mark was queued as ids. Read so such a file still
     * replays: [NotificationInboxRules.replayBody] turns it into the ids of the notices on the device.
     */
    val pendingReadAll: Boolean = false,
    val alertedIds: List<String> = emptyList(),
    /** False until the first sync for this account: that one seeds [alertedIds] and alerts nothing. */
    val seeded: Boolean = false,
    val syncedAt: String? = null,
)

/** The two calls the sync makes, so the rules can be tested without Retrofit. */
interface NotificationApi {
    suspend fun list(limit: Int): NotificationPageDto
    suspend fun markRead(body: NotificationReadBody): NotificationReadResultDto
}

/** Pure rules over [NotificationInbox]; everything the sync decides is decided here. */
object NotificationInboxRules {
    /** How many notices the device keeps, and asks for in one page. The server's page ceiling. */
    const val KEEP = 100
    const val ALERTED_KEEP = 300
    const val MAX_SEPARATE_ALERTS = 3
    /** The server's ceiling on ids in one mark; a longer queue is sent over several syncs. */
    const val MAX_IDS_PER_MARK = 500

    fun unread(inbox: NotificationInbox): Int = inbox.items.count { !it.read }

    /** Mark these read on the device now, and queue the mark for the server. */
    fun markRead(inbox: NotificationInbox, ids: Collection<String>, now: String): NotificationInbox {
        val wanted = ids.toSet()
        if (wanted.isEmpty()) return inbox
        val items = inbox.items.map { if (!it.read && it.id in wanted) it.copy(read = true, readAt = now) else it }
        val pending = (inbox.pendingReadIds + wanted.filter { id -> inbox.items.any { it.id == id } }).distinct()
        return inbox.copy(items = items, pendingReadIds = pending)
    }

    /**
     * Every notice on the device, read now, queued as THEIR ids. An `all` mark replayed later would
     * also mark whatever reached the server after the tap, which the person never saw.
     */
    fun markAllRead(inbox: NotificationInbox, now: String): NotificationInbox =
        markRead(inbox, inbox.items.filter { !it.read }.map { it.id }, now)

    /** What the next replay sends, or null when nothing is waiting. Always ids, never `all`. */
    fun replayBody(inbox: NotificationInbox): NotificationReadBody? {
        val legacyAll = if (inbox.pendingReadAll) inbox.items.map { it.id } else emptyList()
        val ids = (inbox.pendingReadIds + legacyAll).distinct().take(MAX_IDS_PER_MARK)
        if (ids.isEmpty() && !inbox.pendingReadAll) return null
        return NotificationReadBody(ids = ids)
    }

    /**
     * [sent] was accepted (or refused for good): drop exactly those ids from the queue. A mark queued
     * while the call was in flight is not in [sent] and stays for the next sync. [sentLegacyAll] is
     * whether the replay was built from an old file's all-mark.
     */
    fun replayed(inbox: NotificationInbox, sent: NotificationReadBody, sentLegacyAll: Boolean): NotificationInbox {
        val done = sent.ids.toSet()
        return inbox.copy(
            pendingReadIds = inbox.pendingReadIds.filter { it !in done },
            pendingReadAll = inbox.pendingReadAll && !sentLegacyAll,
        )
    }

    /**
     * The server's page becomes the list, with any mark still waiting laid over it — a notice read
     * offline must not reappear unread because the server has not heard yet.
     */
    fun merge(local: NotificationInbox, page: NotificationPageDto, now: String): NotificationInbox {
        val pending = local.pendingReadIds.toSet()
        // An old file's all-mark covers only what the device held when it was made.
        val held = if (local.pendingReadAll) local.items.map { it.id }.toSet() else emptySet()
        val items = page.items.take(KEEP).map { item ->
            if (!item.read && (item.id in pending || item.id in held)) item.copy(read = true, readAt = now) else item
        }
        return local.copy(items = items, syncedAt = now)
    }

    /** The unread notices to alert about after [after] replaced [before]. None on the first sync. */
    fun freshAlerts(before: NotificationInbox, after: NotificationInbox): List<AppNotificationDto> {
        if (!before.seeded) return emptyList()
        val seen = before.alertedIds.toSet()
        return after.items.filter { !it.read && it.id !in seen }
    }

    /** Remember every notice now on the list as alerted, so none is ever alerted twice. */
    fun remember(inbox: NotificationInbox): NotificationInbox {
        val ids = (inbox.items.map { it.id } + inbox.alertedIds).distinct().take(ALERTED_KEEP)
        return inbox.copy(alertedIds = ids, seeded = true)
    }

    /** A queued mark the server refused for good is dropped; anything else is kept and replayed. */
    fun replayRefusedForGood(status: Int?): Boolean = status != null && status in 400..499 && status != 401

    /** The alert's words: one notice is its own title; several are counted. */
    fun summaryTitle(count: Int): String = if (count == 1) "1 new notification" else "$count new notifications"
}

/**
 * A server time ("2026-10-17T12:00:00+00:00", Python's isoformat, or "...Z"), or null. Offset
 * first: before API 34, [Instant.parse] refuses an offset, and minSdk is 26.
 */
fun notificationInstant(value: String?): Instant? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(text) }.getOrNull()
}

/** Where a notice's link leads on this handset, or null when it has no screen here. */
enum class NotificationTarget { TASKS, SETTINGS, REVIEW }

fun notificationTarget(link: String?): NotificationTarget? =
    when (link?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')) {
        "/tasks" -> NotificationTarget.TASKS
        "/settings" -> NotificationTarget.SETTINGS
        "/review" -> NotificationTarget.REVIEW
        else -> null
    }

/** The device copy, one file per account in no-backup storage (it holds names of people and work). */
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
            // A damaged copy is only a copy: drop it and the next sync rebuilds it from the server.
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

    /** Read, change, write — under one lock, so the sync and a tap on the screen never interleave. */
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

/** What the screens draw from: the current account's list and unread count, app-wide. */
object NotificationState {
    private val _inbox = MutableStateFlow<NotificationInbox?>(null)
    val inbox: StateFlow<NotificationInbox?> = _inbox

    private val _openRequests = MutableStateFlow(0L)
    /** Bumped when the person taps a posted alert; the home screen opens the list for it. */
    val openRequests: StateFlow<Long> = _openRequests

    fun publish(inbox: NotificationInbox) {
        _inbox.value = inbox
    }

    /** Signed out: no account's list is held. */
    fun clear() {
        _inbox.value = null
    }

    fun requestOpen() {
        _openRequests.value = System.currentTimeMillis()
    }
}

object NotificationSync {
    private val running = Mutex()

    /**
     * One pass: send any marks waiting, read the list, keep it, alert about what is new. Returns the
     * unread count, or null when the server could not be reached (the device copy stands).
     */
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
                // A refusal (4xx) will never succeed on a replay; a network failure or an expired
                // sign-in (401) keeps the mark for the next pass.
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

    /** A tap on the screen: read here now, and queued for the server. */
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

    /**
     * Sign-out: the account's copy is deleted, its alerts leave the shade and the in-memory list is
     * emptied. Best-effort: signing out must finish even if a file will not delete.
     */
    fun signOut(context: Context, userId: String?) {
        val app = context.applicationContext
        if (!userId.isNullOrBlank()) NotificationStore.forget(app, userId)
        LocalAlerts.cancelAll(app)
        NotificationState.clear()
    }
}

/** The system notifications posted after a sync. Local only; nothing is pushed to the phone. */
object LocalAlerts {
    const val CHANNEL_ID = "activity"
    const val EXTRA_OPEN_NOTIFICATIONS = "open_notifications"
    private const val SUMMARY_ID = 0x4E_0001

    /** Android 13+ needs the person's grant; before that, alerts are allowed unless switched off. */
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

    /** Takes every alert this channel posted out of the shade (sign-out). Other channels are left. */
    fun cancelAll(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.activeNotifications
                .filter { it.notification.channelId == CHANNEL_ID }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }

    /** Called from the activity with the intent it was opened by. */
    fun takeTap(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOTIFICATIONS, false) == true) {
            intent.removeExtra(EXTRA_OPEN_NOTIFICATIONS)
            NotificationState.requestOpen()
        }
    }
}
