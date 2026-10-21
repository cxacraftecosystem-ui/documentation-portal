package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The record page's "Danger zone": content admins in admin view, and nobody else. */
class RecordDangerZoneTest {

    private fun user(role: String) = UserDto(id = role, email = "$role@example.org", name = role, role = role)

    @Test
    fun `content admins in admin view see it`() {
        FieldPermissions.CONTENT_ADMIN_ROLES.forEach { role ->
            assertTrue(role, RecordDangerZone.visible(user(role), adminView = true))
            assertFalse("$role with admin view off", RecordDangerZone.visible(user(role), adminView = false))
        }
    }

    @Test
    fun `no other role ever sees it`() {
        // A sample below the content admins, not the ladder: `isContentAdmin` is set membership.
        listOf("RESEARCHER", "DESIGNER", "PROFESSOR")
            .forEach { role -> assertFalse(role, RecordDangerZone.visible(user(role), adminView = true)) }
        assertFalse(RecordDangerZone.visible(null, adminView = true))
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
