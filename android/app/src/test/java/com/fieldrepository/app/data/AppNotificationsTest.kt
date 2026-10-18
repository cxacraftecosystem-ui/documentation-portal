package com.fieldrepository.app.data

import com.fieldrepository.app.ui.NavDestination
import com.fieldrepository.app.ui.FIELD_NAV_ITEMS
import com.fieldrepository.app.ui.notificationWhen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The in-app activity list's rules on the handset (C25): a mark made offline survives the next
 * sync, the first sync for an account alerts nothing, nothing is alerted twice, and a refused replay
 * is dropped while an expired sign-in keeps it. Pure — the file store and the system notification
 * are thin shells over these.
 */
class AppNotificationsTest {
    private val PKG = "com/fieldrepository/app"
    private val REPO = "FieldRepository.kt"

    private fun notice(id: String, read: Boolean = false) =
        AppNotificationDto(id = id, kind = "REVIEW_SENT_BACK", title = "Notice $id", read = read, createdAt = "2026-10-17T12:00:00Z")

    private fun page(vararg items: AppNotificationDto) = NotificationPageDto(items = items.toList(), unreadCount = items.count { !it.read })

    @Test
    fun `a mark made offline is not undone by the next sync`() {
        val local = NotificationInboxRules.markRead(NotificationInbox(userId = "u", items = listOf(notice("a"), notice("b"))), listOf("a"), "now")
        assertEquals(listOf("a"), local.pendingReadIds)
        val merged = NotificationInboxRules.merge(local, page(notice("a"), notice("b"), notice("c")), "later")
        assertEquals(listOf(true, false, false), merged.items.map { it.read })
        assertEquals(2, NotificationInboxRules.unread(merged))
        val all = NotificationInboxRules.markAllRead(merged, "now")
        assertFalse("an all-mark is queued as ids, never as all", all.pendingReadAll)
        assertEquals(listOf("a", "b", "c"), all.pendingReadIds.sorted())
        assertEquals(0, NotificationInboxRules.unread(NotificationInboxRules.merge(all, page(notice("a"), notice("b"), notice("c")), "x")))
        val body = NotificationInboxRules.replayBody(all)!!
        assertFalse(body.all)
        val replayed = NotificationInboxRules.replayed(all, body, sentLegacyAll = false)
        assertTrue(replayed.pendingReadIds.isEmpty())
        assertNull(NotificationInboxRules.replayBody(replayed))
    }

    @Test
    fun `a tap made while the replay is in flight is not wiped by it`() {
        val start = NotificationInbox(userId = "u", items = listOf(notice("a"), notice("x")), seeded = true)
        val before = NotificationInboxRules.markRead(start, listOf("a"), "t0")
        val sent = NotificationInboxRules.replayBody(before)!!
        assertEquals(listOf("a"), sent.ids)
        // The person opens "x" while that call is running: it is queued beside "a".
        val during = NotificationInboxRules.markRead(before, listOf("x"), "t1")
        val after = NotificationInboxRules.replayed(during, sent, sentLegacyAll = false)
        assertEquals(listOf("x"), after.pendingReadIds)
        // The server has not heard about "x" yet; the list must still show it read.
        val merged = NotificationInboxRules.merge(after, page(notice("a", read = true), notice("x")), "t2")
        assertEquals(listOf(true, true), merged.items.map { it.read })
        assertEquals(listOf("x"), NotificationInboxRules.replayBody(merged)!!.ids)
    }

    @Test
    fun `mark all offline leaves a notice that arrived later unread and alerts it`() {
        val held = NotificationInboxRules.remember(NotificationInbox(userId = "u", items = listOf(notice("a"), notice("b"))))
        val all = NotificationInboxRules.markAllRead(held, "t0")
        val merged = NotificationInboxRules.merge(all, page(notice("new"), notice("a"), notice("b")), "t1")
        assertEquals(listOf(false, true, true), merged.items.map { it.read })
        assertEquals(listOf("new"), NotificationInboxRules.freshAlerts(all, merged).map { it.id })
        assertEquals(listOf("a", "b"), NotificationInboxRules.replayBody(merged)!!.ids.sorted())
    }

    @Test
    fun `an old file's all-mark replays as the ids it covered`() {
        val legacy = NotificationInbox(
            userId = "u", items = listOf(notice("a", read = true), notice("b", read = true)), pendingReadAll = true, seeded = true,
        )
        val body = NotificationInboxRules.replayBody(legacy)!!
        assertFalse(body.all)
        assertEquals(listOf("a", "b"), body.ids)
        val merged = NotificationInboxRules.merge(legacy, page(notice("new"), notice("a"), notice("b")), "t1")
        assertEquals("only what the device held is covered", listOf(false, true, true), merged.items.map { it.read })
        val done = NotificationInboxRules.replayed(merged, body, sentLegacyAll = true)
        assertFalse(done.pendingReadAll)
        assertNull(NotificationInboxRules.replayBody(done))
    }

    @Test
    fun `a long queue is sent within the server's ceiling`() {
        val ids = (1..NotificationInboxRules.MAX_IDS_PER_MARK + 20).map { "n$it" }
        val inbox = NotificationInbox(userId = "u", pendingReadIds = ids)
        val body = NotificationInboxRules.replayBody(inbox)!!
        assertEquals(NotificationInboxRules.MAX_IDS_PER_MARK, body.ids.size)
        assertEquals(20, NotificationInboxRules.replayed(inbox, body, sentLegacyAll = false).pendingReadIds.size)
    }

    @Test
    fun `server times with an offset are read`() {
        // Python's isoformat sends "+00:00". Before API 34, Instant.parse refuses it on the phone.
        val utc = Instant.parse("2026-10-17T12:00:00Z")
        assertEquals(utc, notificationInstant("2026-10-17T12:00:00+00:00"))
        assertEquals(utc, notificationInstant("2026-10-17T17:30:00+05:30"))
        assertEquals(utc, notificationInstant("2026-10-17T12:00:00Z"))
        assertEquals(Instant.parse("2026-10-17T12:00:00.123456Z"), notificationInstant("2026-10-17T12:00:00.123456+00:00"))
        assertNull(notificationInstant("not a date"))
        assertNull(notificationInstant(""))
        assertEquals("5 min ago", notificationWhen("2026-10-17T11:55:00+00:00", utc))
        // The screen goes through the offset-first parser, not Instant.parse alone.
        val screen = listOf(File("src/main/java/$PKG/ui/NotificationsScreen.kt"), File("app/src/main/java/$PKG/ui/NotificationsScreen.kt"))
            .first { it.exists() }.readText()
        assertTrue(screen.contains("notificationInstant(createdAt)"))
        assertFalse(screen.contains("Instant.parse(createdAt)"))
    }

    @Test
    fun `signing out forgets the activity list`() {
        NotificationState.publish(NotificationInbox(userId = "u", items = listOf(notice("a"))))
        NotificationState.clear()
        assertNull(NotificationState.inbox.value)
        val repository = listOf(File("src/main/java/$PKG/data/$REPO"), File("app/src/main/java/$PKG/data/$REPO"))
            .first { it.exists() }.readText()
        val logout = repository.replace("\r\n", "\n").substringAfter("fun logout(").substringBefore("\n    }\n")
        assertTrue("logout must call NotificationSync.signOut", logout.contains("NotificationSync.signOut("))
    }

    @Test
    fun `every sign-out in the app passes a context, so the list is forgotten`() {
        val main = listOf(File("src/main/java/$PKG/MainActivity.kt"), File("app/src/main/java/$PKG/MainActivity.kt"))
            .first { it.exists() }.readText()
        assertFalse(main.contains("repository.logout()"))
    }

    @Test
    fun `a mark for a notice the device does not hold is not queued`() {
        val local = NotificationInboxRules.markRead(NotificationInbox(userId = "u", items = listOf(notice("a"))), listOf("zzz"), "now")
        assertTrue(local.pendingReadIds.isEmpty())
    }

    @Test
    fun `the first sync alerts nothing and nothing is alerted twice`() {
        val empty = NotificationInbox(userId = "u")
        val first = NotificationInboxRules.merge(empty, page(notice("a"), notice("b")), "t1")
        assertTrue("a phone signing in must not buzz with history", NotificationInboxRules.freshAlerts(empty, first).isEmpty())
        val seeded = NotificationInboxRules.remember(first)
        assertTrue(seeded.seeded)
        val second = NotificationInboxRules.merge(seeded, page(notice("c"), notice("a"), notice("b"), notice("r", read = true)), "t2")
        assertEquals(listOf("c"), NotificationInboxRules.freshAlerts(seeded, second).map { it.id })
        val third = NotificationInboxRules.merge(NotificationInboxRules.remember(second), page(notice("c"), notice("a")), "t3")
        assertTrue(NotificationInboxRules.freshAlerts(NotificationInboxRules.remember(second), third).isEmpty())
    }

    @Test
    fun `only a lasting refusal drops a queued mark`() {
        assertTrue(NotificationInboxRules.replayRefusedForGood(404))
        assertTrue(NotificationInboxRules.replayRefusedForGood(422))
        assertFalse("an expired sign-in keeps the mark", NotificationInboxRules.replayRefusedForGood(401))
        assertFalse(NotificationInboxRules.replayRefusedForGood(503))
        assertFalse("a dropped connection keeps the mark", NotificationInboxRules.replayRefusedForGood(null))
    }

    @Test
    fun `words for the alert and the time`() {
        assertEquals("1 new notification", NotificationInboxRules.summaryTitle(1))
        assertEquals("4 new notifications", NotificationInboxRules.summaryTitle(4))
        val now = Instant.parse("2026-10-17T12:00:00Z")
        assertEquals("Just now", notificationWhen("2026-10-17T11:59:30Z", now))
        assertEquals("5 min ago", notificationWhen("2026-10-17T11:55:00Z", now))
        assertEquals("3 h ago", notificationWhen("2026-10-17T09:00:00Z", now))
        assertEquals("Yesterday", notificationWhen("2026-10-16T06:00:00Z", now))
        assertTrue(notificationWhen("2026-10-01T06:00:00Z", now).endsWith("2026"))
        assertEquals("", notificationWhen("not a date", now))
    }

    @Test
    fun `a link opens the screen it names and nothing else does`() {
        assertEquals(NotificationTarget.TASKS, notificationTarget("/tasks"))
        assertEquals(NotificationTarget.TASKS, notificationTarget("/tasks/?x=1"))
        assertEquals(NotificationTarget.SETTINGS, notificationTarget("/settings"))
        assertEquals(NotificationTarget.REVIEW, notificationTarget("/review"))
        assertNull(notificationTarget("/artisans/a1"))
        assertNull(notificationTarget("https://elsewhere.example/tasks"))
        assertNull(notificationTarget(null))
    }

    @Test
    fun `the menu row is the web's`() {
        val entry = FIELD_NAV_ITEMS.single { it.destination == NavDestination.NOTIFICATIONS }
        assertEquals("Notifications", entry.label)
        val web = listOf(File("../frontend/components/DynamicIslandNav.tsx"), File("../../frontend/components/DynamicIslandNav.tsx"))
            .firstOrNull { it.exists() }
        if (web != null) {
            assertTrue(
                "the web's menu row must carry the same label",
                web.readText().contains("{ href: \"/notifications\", label: \"Notifications\"")
            )
        }
    }
}
