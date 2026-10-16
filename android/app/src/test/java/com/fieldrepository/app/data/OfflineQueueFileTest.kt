package com.fieldrepository.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The outbox's file on disk: what survives an app update, a damaged file and a kill mid-write, and
 * whose entries a sync pass may replay. The file is the only copy of a day's fieldwork until it
 * syncs, so every rule here is a rule about losing data.
 */
class OfflineQueueFileTest {
    private val dir: File = Files.createTempDirectory("outbox-test").toFile()
    private val queue = File(dir, "queue.json")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun entry(id: String, owner: String? = null, kind: String = OutboxKind.CREATE) = PendingEntry(
        id = id,
        type = "craft",
        payloadJson = """{"name":"Ajrakh"}""",
        label = "Ajrakh",
        createdAt = "2026-10-16T09:00:00Z",
        kind = kind,
        ownerUserId = owner
    )

    @Test
    fun `a queue written by a build before 2026-10-16 still decodes after the update`() {
        // Exactly the shape the previous release wrote: no kind, no target, no owner, and media
        // without location or capture time.
        queue.writeText(
            """[{"id":"e1","type":"artisan","payloadJson":"{}","label":"Old","createdAt":"2026-10-01T00:00:00Z",""" +
                """"media":[{"localPath":"/x/a.jpg","originalFilename":"a.jpg","mimeType":"image/jpeg","mediaType":"IMAGE"}],""" +
                """"createdId":null,"createdStepIds":[],"uploadedMedia":[],"failure":null,"failedAt":null}]"""
        )
        var damage: String? = null
        val read = OfflineQueueFile.read(queue) { damage = it }
        assertNull(damage)
        assertEquals(1, read.size)
        val old = read.single()
        assertEquals(OutboxKind.CREATE, old.kind)
        assertNull(old.targetId)
        assertNull(old.ownerUserId)
        assertNull(old.media.single().capturedAt)
        assertNull(old.media.single().location)
    }

    @Test
    fun `every new field round-trips`() {
        val edit = entry("e2", owner = "u1", kind = OutboxKind.UPDATE).copy(
            targetId = "rec-9",
            media = listOf(
                PendingMedia(
                    localPath = "/x/b.mp4", originalFilename = "b.mp4", mimeType = "video/mp4", mediaType = "VIDEO",
                    capturedAt = "2026-10-16T08:59:00Z"
                )
            )
        )
        OfflineQueueFile.write(queue, listOf(edit))
        val back = OfflineQueueFile.read(queue) { error("no damage expected: $it") }
        assertEquals(listOf(edit), back)
    }

    @Test
    fun `a file that will not parse is set aside, never read as an empty queue`() {
        queue.writeText("[{\"id\":")
        var damage: String? = null
        val read = OfflineQueueFile.read(queue) { damage = it }
        assertTrue(read.isEmpty())
        assertNotNull(damage)
        assertFalse("the damaged file must not stay where the next write would overwrite it", queue.exists())
        val kept = dir.listFiles()!!.single { it.name.startsWith("queue.damaged-") }
        assertEquals("[{\"id\":", kept.readText())
    }

    @Test
    fun `an empty file is the mark of an interrupted write and is set aside too`() {
        queue.writeText("")
        var damage: String? = null
        OfflineQueueFile.read(queue) { damage = it }
        assertNotNull(damage)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("queue.damaged-") })
    }

    @Test
    fun `an empty queue is written as two bytes, not as an empty file`() {
        OfflineQueueFile.write(queue, emptyList())
        assertEquals("[]", queue.readText())
    }

    @Test
    fun `an atomic write replaces the whole file and leaves no temp file behind`() {
        OfflineQueueFile.write(queue, listOf(entry("a"), entry("b")))
        OfflineQueueFile.write(queue, listOf(entry("c")))
        assertEquals(listOf("c"), OfflineQueueFile.read(queue) { error(it) }.map { it.id })
        assertEquals(listOf("queue.json"), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun `an edit queued as an ordered unit keeps its steps, its progress and its key`() {
        val sequence = entry("q1", owner = "u1", kind = OutboxKind.SEQUENCE).copy(
            targetId = "interview-9",
            payloadJson = "",
            steps = listOf(
                OutboxStep(op = "questionnaire.update", targetId = "interview-9", payloadJson = """{"title":"Ajrakh"}""")
            ),
            completedSteps = 1,
            idempotencyKey = "key-0001"
        )
        OfflineQueueFile.write(queue, listOf(sequence))
        assertEquals(listOf(sequence), OfflineQueueFile.read(queue) { error(it) })
    }

    @Test
    fun `an entry from before keys and sequences decodes with neither`() {
        queue.writeText("""[{"id":"e1","type":"craft","payloadJson":"{}","label":"Old","createdAt":"2026-10-01T00:00:00Z"}]""")
        val old = OfflineQueueFile.read(queue) { error(it) }.single()
        assertNull(old.idempotencyKey)
        assertTrue(old.steps.isEmpty())
        assertEquals(0, old.completedSteps)
    }

    @Test
    fun `a sync pass replays only the signed-in account's entries and legacy ones`() {
        val queue = listOf(entry("mine", owner = "u1"), entry("legacy", owner = null), entry("theirs", owner = "u2"))
        assertEquals(listOf("mine", "legacy"), queue.replayableBy("u1").map { it.id })
        assertEquals(listOf("legacy", "theirs"), queue.replayableBy("u2").map { it.id })
        // Signed out: nothing that belongs to anyone.
        assertEquals(listOf("legacy"), queue.replayableBy(null).map { it.id })
    }
}
