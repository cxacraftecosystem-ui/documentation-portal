package com.fieldrepository.app.ui

import androidx.compose.ui.unit.dp
import com.fieldrepository.app.data.CONNECTION_FAILED_SENTENCE
import com.fieldrepository.app.data.replayRefusedFilesSentence
import com.fieldrepository.app.mediaUploadButtonLabel
import com.fieldrepository.app.queuedMediaSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The review of the smoke-test fixes (SmokeFixesTest): the gaps it found, pinned. */
class SmokeFixesFollowUpTest {

    private val main: String by lazy {
        repoSource(
            "src/main/java/com/fieldrepository/app/MainActivity.kt",
            "app/src/main/java/com/fieldrepository/app/MainActivity.kt",
            "android/app/src/main/java/com/fieldrepository/app/MainActivity.kt",
        )
    }

    private fun ui(name: String): String = repoSource(
        "src/main/java/com/fieldrepository/app/ui/$name",
        "app/src/main/java/com/fieldrepository/app/ui/$name",
        "android/app/src/main/java/com/fieldrepository/app/ui/$name",
    )

    // ── The error hides only behind a banner that is actually drawn ─────────────────────────

    @Test
    fun `a banner counts as drawn only while it reserves space`() {
        // Offline with the keyboard up: the root reserves nothing, so the error must show.
        assertFalse(bannerDrawn(bannerClearance(48.dp, shown = false)))
        assertFalse(bannerDrawn(0.dp))
        assertTrue(bannerDrawn(bannerClearance(48.dp, shown = true)))
        assertFalse(
            bannerAlreadySays(CONNECTION_FAILED_SENTENCE, AppMessageTone.ERROR, bannerDrawn(bannerClearance(48.dp, false)))
        )
    }

    @Test
    fun `showMessage asks the drawn banners, not the network`() {
        val start = main.indexOf("fun showMessage(")
        val body = main.substring(start, start + 900)
        assertTrue(body.contains("bannerDrawn(bannerClearanceState?.value"))
        assertFalse(body.contains("repository.isOnline(context)"))
    }

    // ── Every surface under the banners pads by them ────────────────────────────────────────

    @Test
    fun `the search results and the password gate scroll clear of the banners`() {
        assertTrue(ui("AppSearch.kt").contains("bottom = 24.dp + (LocalBannerClearance.current?.value ?: 0.dp)"))
        val gate = ui("PasswordGate.kt")
        val screen = gate.substring(gate.indexOf("fun PasswordGateScreen("), gate.indexOf("fun SetPasswordLinkScreen("))
        assertTrue(screen.contains("LocalBannerClearance.current?.value"))
        val branch = main.indexOf("mustChangePasswordBlocks(user) -> ")
        assertTrue(main.substring(branch, branch + 200).contains("LocalBannerClearance provides bannerReserve"))
    }

    // ── Upload media says what actually happened ────────────────────────────────────────────

    @Test
    fun `a queued batch reads saved on device, never uploaded`() {
        assertEquals("Saved on device ✓", mediaUploadButtonLabel(false, succeeded = true, failed = false, queued = true, hasRecordType = true))
        assertEquals("Uploaded ✓", mediaUploadButtonLabel(false, succeeded = true, failed = false, queued = false, hasRecordType = true))
        assertEquals("Uploading...", mediaUploadButtonLabel(true, succeeded = false, failed = false, queued = true, hasRecordType = true))
        assertEquals("Upload batch", mediaUploadButtonLabel(false, succeeded = false, failed = false, queued = false, hasRecordType = true))
    }

    @Test
    fun `a part-way batch counts the files that went up as well as the ones kept`() {
        assertEquals(
            "2 uploaded, 1 saved on this device. It uploads when you're back online.",
            queuedMediaSentence(1, uploaded = 2)
        )
        assertEquals(
            "1 uploaded, 3 saved on this device. They upload when you're back online.",
            queuedMediaSentence(3, uploaded = 1)
        )
        assertEquals("1 file saved on this device. It uploads when you're back online.", queuedMediaSentence(1))
        val start = main.indexOf("private fun AndroidMediaForm(")
        val body = main.substring(start, main.indexOf("\nprivate fun ", start + 1))
        assertTrue(body.contains("queueBatch(failedUris.toList(), uploaded = success)"))
    }

    // ── A rejected unlinked batch names no record ───────────────────────────────────────────

    @Test
    fun `a refused file in a batch with no record does not send the reader to one`() {
        val refused = listOf("\"a.jpg\": too large.", "\"a.jpg\": too large.")
        assertEquals("2 file(s) couldn't be uploaded: \"a.jpg\": too large.", replayRefusedFilesSentence(refused, unlinked = true))
        assertEquals(
            "It was saved, but 2 file(s) couldn't be uploaded: \"a.jpg\": too large. Attach them again on the record.",
            replayRefusedFilesSentence(refused, unlinked = false)
        )
    }
}
