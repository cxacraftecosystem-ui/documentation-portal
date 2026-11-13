package com.fieldrepository.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StagedObjectsTest {
    private fun staged(key: String) = StagedMedia(key, "b", null, "audio/mp4", "AUDIO", 10, "m4a")

    @After fun clear() = StagedObjects.reset()

    @Test fun `a staged object is deleted at most once`() {
        assertTrue(StagedObjects.claimDelete("media/u/x/staged-1-a.m4a"))
        assertFalse(StagedObjects.claimDelete("media/u/x/staged-1-a.m4a"))
        assertTrue(StagedObjects.claimDelete("media/u/x/staged-2-b.m4a"))
    }

    @Test fun `a clip that left the screen keeps its upload and gets it back on return`() {
        StagedObjects.keep("content://clip/1", staged("k1"))
        assertEquals(staged("k1"), StagedObjects.kept("content://clip/1"))
        StagedObjects.settled("k1")
        assertNull(StagedObjects.kept("content://clip/1"))
    }

    @Test fun `a discarded clip is never handed back`() {
        StagedObjects.keep("content://clip/2", staged("k2"))
        assertTrue(StagedObjects.claimDelete("k2"))
        assertNull(StagedObjects.kept("content://clip/2"))
        StagedObjects.keep("content://clip/2", staged("k2"))
        assertNull(StagedObjects.kept("content://clip/2"))
    }

    @Test fun `no screen deletes staged uploads when it leaves composition`() {
        val main = File("src/main/java/com/fieldrepository/app/MainActivity.kt").readText()
        val disposals = Regex("""onDispose \{(?:[^{}]|\{(?:[^{}]|\{[^{}]*\})*\})*\}""").findAll(main).map { it.value }.toList()
        assertTrue("found the screens' onDispose blocks", disposals.isNotEmpty())
        val deleting = disposals.filter { it.contains("deleteStaged") || it.contains("stagedDeferred") }
        assertEquals("an unsaved clip is only deleted on an explicit discard", emptyList<String>(), deleting)
        val repository = File("src/main/java/com/fieldrepository/app/data/FieldRepository.kt").readText()
        val delete = repository.substringAfter("suspend fun deleteStaged(objectKey: String) {").substringBefore("}")
        assertTrue(delete.trim().startsWith("if (!StagedObjects.claimDelete(objectKey)) return"))
    }
}
