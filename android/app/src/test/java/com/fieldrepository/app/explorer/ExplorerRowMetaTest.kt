package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerEntry
import com.fieldrepository.app.explorer.data.ExplorerJson
import com.fieldrepository.app.explorer.ui.capturedDay
import com.fieldrepository.app.explorer.ui.fileMeta
import com.fieldrepository.app.explorer.ui.folderKind
import com.fieldrepository.app.explorer.ui.folderMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerRowMetaTest {
    @QFeature("X2")
    @Test fun `a folder shows its stored files and size before it is opened`() {
        val folder = ExplorerEntry(name = "Bagru", path = "w/b", kind = "folder", recordType = "workshop", count = 212, bytes = 3_800_000_000)
        assertEquals("Workshop · 212 files · 3.54 GB", folderMeta(folder))
    }

    @QFeature("X2")
    @Test fun `a folder without counts draws nothing extra`() {
        assertEquals("Artisan", folderMeta(ExplorerEntry(name = "G", path = "a", kind = "folder", recordType = "artisan")))
        assertNull(folderMeta(ExplorerEntry(name = "G", path = "a", kind = "folder")))
        assertEquals("0 files", folderMeta(ExplorerEntry(name = "G", path = "a", kind = "folder", count = 0, bytes = 0)))
    }

    @Test fun `a folder kind reads as words, never as the model name`() {
        assertEquals("Design workshop stage · 1 file", folderMeta(ExplorerEntry(name = "01 Setup", path = "s", kind = "folder", recordType = "designWorkshopStage", count = 1, bytes = 0)))
        assertEquals("Design workshop", folderKind("designWorkshop"))
        assertEquals("Process step", folderKind("processStep"))
        assertEquals("Category", folderKind("category"))
        assertEquals("Some new kind", folderKind("someNewKind"))
        assertNull(folderKind(" "))
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

    @QFeature("X2")
    @Test fun `measured facts join the row line only when present`() {
        val photo = ExplorerEntry(name = "a.jpg", path = "a", mediaId = "m", mediaType = "IMAGE", sizeBytes = 3_400_000, pixels = com.fieldrepository.app.explorer.data.ExplorerPixels(4032, 3024))
        assertEquals("Image · 3.2 MB · 4032 × 3024", fileMeta(photo))
        assertEquals("Image · 3.2 MB", fileMeta(photo.copy(pixels = null)))
        val pdf = ExplorerEntry(name = "c.pdf", path = "c", mediaId = "d", mediaType = "PDF", sizeBytes = 838_861, pages = 2)
        assertEquals("Document · 0.8 MB · 2 pages", fileMeta(pdf))
        assertEquals("Document · 0.8 MB · 1 page", fileMeta(pdf.copy(pages = 1)))
        assertEquals("Document · 0.8 MB", fileMeta(pdf.copy(pages = null)))
    }
}
