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
        assertTrue(all.pendingReadAll)
        assertEquals(0, NotificationInboxRules.unread(NotificationInboxRules.merge(all, page(notice("a"), notice("d")), "x")))
        val replayed = NotificationInboxRules.replayed(all)
        assertFalse(replayed.pendingReadAll)
        assertTrue(replayed.pendingReadIds.isEmpty())
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
