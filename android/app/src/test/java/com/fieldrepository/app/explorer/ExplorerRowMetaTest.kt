package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerJson
import com.fieldrepository.app.explorer.ui.capturedDay
import com.fieldrepository.app.explorer.ui.fileMeta
import com.fieldrepository.app.explorer.ui.folderMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerRowMetaTest {
    @QFeature("X2")
    @Test fun `a folder shows its stored files and size before it is opened`() {
        val folder = ExplorerEntry(name = "Bagru", path = "w/b", kind = "folder", recordType = "workshop", count = 212, bytes = 3_800_000_000)
        assertEquals("Workshop · 212 files · 3.5 GB", folderMeta(folder))
    }

    @QFeature("X2")
    @Test fun `a folder without counts draws nothing extra`() {
        assertEquals("Artisan", folderMeta(ExplorerEntry(name = "G", path = "a", kind = "folder", recordType = "artisan")))
        assertNull(folderMeta(ExplorerEntry(name = "G", path = "a", kind = "folder")))
        assertEquals("0 files", folderMeta(ExplorerEntry(name = "G", path = "a", kind = "folder", count = 0, bytes = 0)))
    }

    @QFeature("X2")
    @Test fun `a file row carries the day it was captured`() {
        assertTrue(capturedDay("2026-09-22T10:15:00Z")!!.endsWith("2026"))
        assertNull(capturedDay(null))
        assertNull(capturedDay("not a date"))
        val meta = fileMeta(ExplorerEntry(name = "a.jpg", path = "a", mediaId = "m", mediaType = "IMAGE", sizeBytes = 3_400_000, capturedAt = "2026-09-22T10:15:00+05:30"))
        assertTrue(meta, meta.startsWith("Image · 3.2 MB · ") && meta.endsWith("2026"))
    }

    @QFeature("X2")
    @Test fun `the new tree fields decode and stay optional`() {
        val e = ExplorerJson.decodeFromString(ExplorerEntry.serializer(), """{"name":"B","path":"b","kind":"folder","count":5,"bytes":null}""")
        assertEquals(5, e.count)
        assertNull(e.bytes)
        assertNull(e.capturedAt)
    }
}
