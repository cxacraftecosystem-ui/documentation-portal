package com.fieldrepository.app.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * THE BANNER UPDATES LIVE. A 409 duplicate hold is recorded by the sync worker, off-screen, and it
 * leaves the queue's size alone, so the banner keyed on the count alone kept the old list until it
 * was reopened. Every queue write now bumps [OfflineOutbox.revision], and the banner keys on that.
 */
class OutboxRevisionTest {
    private val dir: File = Files.createTempDirectory("outbox-revision").toFile()
    private val queue = File(dir, "queue.json")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val queued = PendingEntry(
        id = "e1",
        type = "artisan",
        payloadJson = """{"name":"Meena","place":"Bhuj"}""",
        label = "Meena",
        createdAt = "2026-10-19T09:00:00Z",
        kind = OutboxKind.CREATE
    )

    private fun held(entry: PendingEntry): PendingEntry {
        val withCandidates = entry.copy(duplicateCandidates = listOf(OutboxDuplicateCandidate("a1", "Meena Devi", "Bhuj")))
        return withCandidates.copy(failure = possibleDuplicateTrayText(withCandidates), failedAt = "2026-10-19T09:01:00Z")
    }

    @Test
    fun every_queue_write_bumps_the_revision() {
        val before = OfflineOutbox.revision.value
        OfflineQueueFile.write(queue, listOf(queued))
        OfflineQueueFile.write(queue, listOf(held(queued)))
        assertEquals(before + 2, OfflineOutbox.revision.value)
    }

    @Test
    fun a_hold_recorded_while_the_banner_waits_wakes_it_with_the_held_entry() = runBlocking {
        OfflineQueueFile.write(queue, listOf(queued))
        val seen = OfflineOutbox.revision.value
        assertEquals(0, OfflineQueueFile.read(queue) {}.count { it.isPossibleDuplicate() })

        // The banner's side: waiting on the revision, as its LaunchedEffect key does.
        val woken = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { OfflineOutbox.revision.first { it > seen } }
            OfflineQueueFile.read(queue) {}
        }
        // The worker's side: the same count of entries, now held.
        OfflineQueueFile.write(queue, listOf(held(queued)))

        val list = woken.await()
        assertEquals(1, list.size)
        assertTrue(list.single().isPossibleDuplicate())
        assertEquals("Not saved yet. This looks like Meena Devi (Bhuj).", list.single().failure)
    }
}
