package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `roles.CONTENT_ADMIN_ROLES` on the handset (owner's ruling, 2026-10-20): the content admins delete,
 * everybody else adds and edits and never deletes. Set membership, never rank — and not
 * [FieldPermissions.isAdmin], which keeps its two members.
 */
class ContentAdminTest {

    private fun user(role: String) = UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)

    private val everyRole = listOf(
        "CROWDSOURCE_VOLUNTEER",
        "FIELD_CONTRIBUTOR",
        "RESEARCHER",
        "PROFESSOR",
        "ADMIN",
        "MASTER_ADMIN",
    )

    @Test
    fun `the content admins are the three admin tiers by name`() {
        assertEquals(setOf("MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"), FieldPermissions.CONTENT_ADMIN_ROLES)
        for (role in everyRole) {
            assertEquals(role, role == "ADMIN" || role == "MASTER_ADMIN", FieldPermissions.isContentAdmin(user(role)))
        }
        assertTrue(FieldPermissions.isContentAdmin(user("MINISTRY_ADMIN")))
        assertFalse(FieldPermissions.isContentAdmin(null))
    }
}
