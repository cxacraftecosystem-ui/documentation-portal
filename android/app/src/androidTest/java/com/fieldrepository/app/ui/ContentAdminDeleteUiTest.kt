package com.fieldrepository.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fieldrepository.app.data.EntryCommentDto
import com.fieldrepository.app.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A content admin sees Delete in admin chrome (REQUIREMENTS_2026-10-20 §5.4), drawn on a device.
 * This portal has no Ministry Admin tier, so the content admins are ADMIN and MASTER_ADMIN.
 *
 * The comment row is the record screen's real delete control: its Remove is offered on
 * [LocalContentAdminChrome], which `MainActivity` provides as [contentAdminChrome]. A content admin
 * with admin view on sees it, with admin view off does not, and a researcher never does. The
 * author's Edit (the PATCH the web already had) is held here too.
 */
@RunWith(AndroidJUnit4::class)
class ContentAdminDeleteUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun user(id: String, role: String) = UserDto(id = id, email = "$id@example.org", name = id, role = role)

    private val comment = EntryCommentDto(
        id = "c1",
        recordType = "artisan",
        recordId = "a1",
        authorId = "author",
        body = "Loom count checked twice.",
        createdAt = "2026-10-20T09:00:00Z"
    )

    private fun show(viewer: UserDto, adminView: Boolean, onEdit: () -> Unit = {}, onRemove: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalContentAdminChrome provides contentAdminChrome(viewer, adminView)) {
                    EntryCommentRow(comment = comment, viewerId = viewer.id, busy = false, onEdit = onEdit, onRemove = onRemove)
                }
            }
        }
    }

    @Test
    fun anAdminInAdminViewSeesRemoveAndItFires() {
        var removed = 0
        show(user("admin", "ADMIN"), adminView = true, onRemove = { removed++ })
        compose.onNodeWithText("Remove").assertIsDisplayed().performClick()
        assertEquals(1, removed)
        compose.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun aMasterAdminInAdminViewSeesRemove() {
        show(user("master", "MASTER_ADMIN"), adminView = true)
        compose.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun anAdminWithAdminViewOffSeesNoRemove() {
        show(user("admin", "ADMIN"), adminView = false)
        compose.onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test
    fun aResearcherNeverSeesRemove() {
        show(user("r1", "RESEARCHER"), adminView = true)
        compose.onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test
    fun theAuthorSeesEditAndNotRemove() {
        var edited = 0
        show(user("author", "RESEARCHER"), adminView = false, onEdit = { edited++ })
        compose.onNodeWithText("Edit").assertIsDisplayed().performClick()
        assertEquals(1, edited)
        compose.onNodeWithText("Remove").assertDoesNotExist()
    }
}
