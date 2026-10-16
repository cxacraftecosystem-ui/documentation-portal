package com.fieldrepository.app.ui

import com.fieldrepository.app.data.PreviewCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The offline preview wiring that does not need a device: the size cap and the keep-once rule. */
class OfflinePreviewsTest {
    @Test
    fun `a preview is scaled so its longest side is 480, keeping its shape`() {
        assertEquals(480 to 360, previewSize(4000, 3000))
        assertEquals(270 to 480, previewSize(1080, 1920))
        assertEquals(480 to 1, previewSize(10_000, 3))
    }

    @Test
    fun `a small image is kept as it is`() {
        assertNull(previewSize(480, 200))
        assertNull(previewSize(64, 64))
    }

    private class Harness(var ticket: PreviewCache.Ticket? = PreviewCache.Ticket("user-a", 3)) {
        val pending = ArrayList<suspend () -> Unit>()
        val stored = ArrayList<Triple<PreviewCache.Ticket, String, Int>>()
        var encodes = 0
        val keeper = PreviewKeeper(
            mediaId = "media-1",
            ticket = { ticket },
            launch = { pending += it },
            store = { t, id, bytes -> stored += Triple(t, id, bytes.size) }
        )

        fun offer(alreadyHeld: Boolean = false) = keeper.offer(alreadyHeld) { encodes++; ByteArray(5) }

        fun runPending() = kotlinx.coroutines.runBlocking { pending.toList().forEach { it() }; pending.clear() }
    }

    @Test
    fun `a tile that loads again is encoded and written once`() {
        val h = Harness()
        h.offer()
        h.offer()
        h.offer()
        h.runPending()
        assertEquals(1, h.encodes)
        assertEquals(listOf(Triple(PreviewCache.Ticket("user-a", 3), "media-1", 5)), h.stored)
    }

    @Test
    fun `the write goes to the account signed in when the image loaded`() {
        val h = Harness()
        h.offer()
        h.ticket = PreviewCache.Ticket("user-b", 4) // a quick sign-out and sign-in before the encode
        h.runPending()
        assertEquals("user-a", h.stored.single().first.account)
    }

    @Test
    fun `nothing is written when the device already holds the preview or nobody is signed in`() {
        val held = Harness()
        held.offer(alreadyHeld = true)
        held.runPending()
        assertEquals(0, held.encodes)

        val signedOut = Harness(ticket = null)
        signedOut.offer()
        signedOut.runPending()
        assertEquals(0, signedOut.encodes)
        signedOut.ticket = PreviewCache.Ticket("user-a", 1)
        signedOut.offer()
        signedOut.runPending()
        assertEquals(1, signedOut.stored.size)
    }
}
