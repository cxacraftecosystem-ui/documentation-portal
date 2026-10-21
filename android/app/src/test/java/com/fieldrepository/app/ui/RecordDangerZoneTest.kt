package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The record page's "Danger zone": content admins, admin view or not, and nobody else. */
class RecordDangerZoneTest {

    private fun user(role: String) = UserDto(id = role, email = "$role@example.org", name = role, role = role)

    @Test
    fun `content admins see it whether or not admin view is on`() {
        FieldPermissions.CONTENT_ADMIN_ROLES.forEach { role -> assertTrue(role, RecordDangerZone.visible(user(role))) }
        // The gate takes no admin view: a Ministry Admin with it off is shown the card (2026-10-21).
        assertTrue(RecordDangerZone.visible(user("MINISTRY_ADMIN")))
        assertTrue(mayDelete(user("MINISTRY_ADMIN")))
        assertFalse("admin view chrome still needs the switch", contentAdminChrome(user("MINISTRY_ADMIN"), adminView = false))
    }

    @Test
    fun `no other role ever sees it`() {
        // A sample below the content admins, not the ladder: `isContentAdmin` is set membership.
        listOf("RESEARCHER", "DESIGNER", "PROFESSOR")
            .forEach { role -> assertFalse(role, RecordDangerZone.visible(user(role))) }
        assertFalse(RecordDangerZone.visible(null))
    }

    @Test
    fun `the words are the documentation portal's`() {
        assertEquals("Danger zone", RecordDangerZone.TITLE)
        assertEquals("Permanently deletes this artisan and its links.", RecordDangerZone.body("artisan"))
        assertEquals("Delete this artisan", RecordDangerZone.button("artisan"))
        assertEquals("Delete artisan?", RecordDangerZone.confirmTitle("artisan"))
        assertEquals("This permanently deletes the record.", RecordDangerZone.CONFIRM_BODY)
    }
}
