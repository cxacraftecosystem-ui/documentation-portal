package com.fieldrepository.app.ui

import com.fieldrepository.app.data.OutboxDuplicateCandidate
import com.fieldrepository.app.data.OutboxKind
import com.fieldrepository.app.data.PendingEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE OPEN TRAY DOES NOT FLICKER. Every queue write bumps the revision the tray keys on, and each
 * re-read used to set the spinner, so an open tray dropped to "Reading the queue…", lost its scroll
 * and moved cards out from under a tap. The spinner is for the first read only; later reads swap
 * the list in place.
 */
class OutboxTrayReloadTest {
    private fun entry(id: String, failure: String? = null, held: Boolean = false) = PendingEntry(
        id = id,
        type = "artisan",
        payloadJson = """{"name":"$id"}""",
        label = id,
        createdAt = "2026-10-19T09:00:00Z",
        kind = OutboxKind.CREATE,
        failure = failure,
        duplicateCandidates = if (held) listOf(OutboxDuplicateCandidate("a1", "Meena Devi", "Bhuj")) else emptyList()
    )

    private val refusedOne = entry("Meena", failure = "Aadhaar is already registered.")
    private val refusedTwo = entry("Ravi", failure = "Phone number is invalid.")

    @Test
    fun a_read_keeps_only_refused_entries_that_are_not_duplicate_holds() {
        val queue = listOf(refusedOne, entry("Queued"), entry("Held", failure = "Not saved yet.", held = true))
        assertEquals(listOf(refusedOne), refusedAfterRead(null, Result.success(queue)))
    }

    @Test
    fun a_re_read_replaces_the_list_in_place() {
        val after = refusedAfterRead(listOf(refusedOne), Result.success(listOf(refusedOne, refusedTwo)))
        assertEquals(listOf(refusedOne, refusedTwo), after)
    }

    @Test
    fun a_failed_re_read_keeps_what_is_on_screen() {
        assertEquals(listOf(refusedOne), refusedAfterRead(listOf(refusedOne), Result.failure(IllegalStateException("disk"))))
        assertEquals(emptyList<PendingEntry>(), refusedAfterRead(null, Result.failure(IllegalStateException("disk"))))
    }

    @Test
    fun the_spinner_is_tied_to_the_first_read_and_the_scroll_outlives_re_reads() {
        val source = repoSource(
            "android/app/src/main/java/com/fieldrepository/app/ui/OutboxTray.kt",
            "src/main/java/com/fieldrepository/app/ui/OutboxTray.kt",
        )
        val effect = source.substringAfter("LaunchedEffect(reload, revision) {").substringBefore("\n    }")
        assertFalse("a re-read must not raise the spinner", "loading" in effect)
        assertTrue("the spinner shows only before the first read", "shown == null -> Row(" in source)
        assertTrue("the scroll state is held above the list", "val scroll = rememberScrollState()" in source && ".verticalScroll(scroll)" in source)
    }
}
