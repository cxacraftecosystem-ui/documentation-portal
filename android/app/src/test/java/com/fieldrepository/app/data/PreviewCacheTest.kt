package com.fieldrepository.app.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Photo previews kept for offline viewing: per account, bounded, least recently shown out first. */
class PreviewCacheTest {
    private val root: File = Files.createTempDirectory("previews-test").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a stored preview is found again by its media id and only for its account`() {
        PreviewFiles.store(root, "user-a", "media-1", byteArrayOf(1, 2, 3), 1_000)
        val found = PreviewFiles.find(root, "user-a", "media-1")
        assertNotNull(found)
        assertArrayEquals(byteArrayOf(1, 2, 3), found!!.readBytes())
        assertNull(PreviewFiles.find(root, "user-b", "media-1"))
        assertNull(PreviewFiles.find(root, "user-a", "media-2"))
    }

    @Test
    fun `the least recently shown previews go first when the cap is passed`() {
        PreviewFiles.store(root, "a", "old", ByteArray(400), 1_000)
        PreviewFiles.store(root, "a", "mid", ByteArray(400), 1_000)
        PreviewFiles.file(root, "a", "old").setLastModified(1_000_000)
        PreviewFiles.file(root, "a", "mid").setLastModified(2_000_000)
        PreviewFiles.store(root, "a", "new", ByteArray(400), 1_000)
        assertFalse(PreviewFiles.file(root, "a", "old").exists())
        assertTrue(PreviewFiles.file(root, "a", "mid").exists())
        assertTrue(PreviewFiles.file(root, "a", "new").exists())
    }

    @Test
    fun `eviction keeps the total at or under the cap, oldest first`() {
        val entries = listOf(
            PreviewEntry("c", 50, 3),
            PreviewEntry("a", 50, 1),
            PreviewEntry("b", 50, 2)
        )
        assertEquals(emptyList<String>(), PreviewFiles.evictionOrder(entries, 150))
        assertEquals(listOf("a"), PreviewFiles.evictionOrder(entries, 100))
        assertEquals(listOf("a", "b", "c"), PreviewFiles.evictionOrder(entries, 0))
    }

    @Test
    fun `a preview larger than the whole cap is not kept`() {
        PreviewFiles.store(root, "a", "huge", ByteArray(2_000), 1_000)
        assertNull(PreviewFiles.find(root, "a", "huge"))
    }

    @Test
    fun `sign-out clears every account's previews`() {
        PreviewFiles.store(root, "a", "m1", byteArrayOf(1), 1_000)
        PreviewFiles.store(root, "b", "m2", byteArrayOf(2), 1_000)
        PreviewFiles.clear(root)
        assertNull(PreviewFiles.find(root, "a", "m1"))
        assertNull(PreviewFiles.find(root, "b", "m2"))
        assertEquals(0, root.listFiles().orEmpty().size)
    }

    @Test
    fun `a write started before a sign-out is dropped, not filed after it`() {
        val before = PreviewFiles.generation()
        PreviewFiles.invalidate()
        PreviewFiles.store(root, "a", "late", byteArrayOf(1), 1_000, before)
        assertNull(PreviewFiles.find(root, "a", "late"))
        assertFalse(PreviewFiles.accountDir(root, "a").exists())
        PreviewFiles.store(root, "a", "fresh", byteArrayOf(1), 1_000, PreviewFiles.generation())
        assertNotNull(PreviewFiles.find(root, "a", "fresh"))
    }

    @Test
    fun `the running total counts a replaced preview once and still trims at the cap`() {
        PreviewFiles.store(root, "a", "one", ByteArray(400), 1_000)
        PreviewFiles.store(root, "a", "one", ByteArray(400), 1_000)
        PreviewFiles.store(root, "a", "two", ByteArray(400), 1_000)
        assertTrue(PreviewFiles.file(root, "a", "one").exists())
        PreviewFiles.file(root, "a", "one").setLastModified(1_000_000)
        PreviewFiles.file(root, "a", "two").setLastModified(2_000_000)
        PreviewFiles.store(root, "a", "three", ByteArray(400), 1_000)
        assertFalse(PreviewFiles.file(root, "a", "one").exists())
        assertTrue(PreviewFiles.file(root, "a", "two").exists())
        assertTrue(PreviewFiles.file(root, "a", "three").exists())
    }

    @Test
    fun `the cap is 150 MB`() {
        assertEquals(150L * 1024 * 1024, PreviewCache.MAX_BYTES)
    }
}
