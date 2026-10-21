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
 * A content admin sees Delete (REQUIREMENTS_2026-10-20 §5.4), drawn on a device, whether or not
 * admin view is on (owner, 2026-10-21). The content admins are ADMIN, MASTER_ADMIN and, by name,
 * MINISTRY_ADMIN.
 *
 * The comment row is the record screen's real delete control: its Remove is offered on
 * [LocalMayDelete], which `MainActivity` provides as [mayDelete]. A content admin sees it with admin
 * view on and off, and a researcher never does. The
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
                // As MainActivity provides them: chrome follows admin view, delete does not.
                CompositionLocalProvider(
                    LocalContentAdminChrome provides contentAdminChrome(viewer, adminView),
                    LocalMayDelete provides mayDelete(viewer),
                ) {
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
        // Asked first: nothing is removed until the confirmation.
        compose.onNodeWithText("Delete this comment?").assertIsDisplayed()
        assertEquals(0, removed)
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, removed)
        compose.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun aMasterAdminInAdminViewSeesRemove() {
        show(user("master", "MASTER_ADMIN"), adminView = true)
        compose.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun anAdminWithAdminViewOffStillSeesRemove() {
        show(user("admin", "ADMIN"), adminView = false)
        compose.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun aMinistryAdminWithAdminViewOffStillSeesRemove() {
        show(user("ma", "MINISTRY_ADMIN"), adminView = false)
        compose.onNodeWithText("Remove").assertIsDisplayed()
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

    // ── The record page's "Danger zone" card (owner, 2026-10-21) ─────────────────────────────────

    private fun showDangerZone(viewer: UserDto, adminView: Boolean, delete: () -> Unit = {}, deleted: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                // `adminView` is what MainActivity would hold; the card does not read it (2026-10-21).
                if (RecordDangerZone.visible(viewer)) {
                    RecordDangerZoneCard(noun = "artisan", delete = { delete() }, onDeleted = deleted, onError = {})
                }
            }
        }
    }

    @Test
    fun aMinistryAdminSeesTheDangerZoneAndDeletesOnlyAfterConfirming() {
        var calls = 0
        var done = 0
        showDangerZone(user("ma", "MINISTRY_ADMIN"), adminView = true, delete = { calls++ }, deleted = { done++ })
        compose.onNodeWithText("Danger zone").assertIsDisplayed()
        compose.onNodeWithText("Delete this artisan").assertIsDisplayed().performClick()
        compose.onNodeWithText("Delete artisan?").assertIsDisplayed()
        assertEquals("nothing is deleted before the confirmation", 0, calls)
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals(1, calls)
        assertEquals(1, done)
    }

    @Test
    fun aMinistryAdminWithAdminViewOffStillSeesTheDangerZone() {
        var calls = 0
        showDangerZone(user("ma", "MINISTRY_ADMIN"), adminView = false, delete = { calls++ })
        compose.onNodeWithText("Danger zone").assertIsDisplayed()
        compose.onNodeWithText("Delete this artisan").assertIsDisplayed().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals(1, calls)
    }

    @Test
    fun aResearcherNeverSeesTheDangerZone() {
        showDangerZone(user("r1", "RESEARCHER"), adminView = true)
        compose.onNodeWithText("Danger zone").assertDoesNotExist()
        compose.onNodeWithText("Delete this artisan").assertDoesNotExist()
        showDangerZone(user("r1", "RESEARCHER"), adminView = false)
        compose.onNodeWithText("Danger zone").assertDoesNotExist()
    }
}
