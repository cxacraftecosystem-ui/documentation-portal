package com.fieldrepository.app.questionnaire.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a list leaves behind (owner, 1 Nov 2026): Back from a record finds the same memory, any other
 * way in a new one; the newest screens are kept and the oldest dropped.
 */
class ScreenMemoryTest {
    @Test fun `back from a record finds what the list left, a fresh way in starts over`() {
        val memories = ScreenMemories<String>()
        val first = memories.arrive("artisans", restoring = false)
        first.getOrPut("query") { "loom" }
        assertSame(first, memories.arrive("artisans", restoring = true))
        assertEquals("loom", memories.arrive("artisans", restoring = true).getOrPut("query") { "" })
        val fresh = memories.arrive("artisans", restoring = false)
        assertNotSame(first, fresh)
        assertEquals("", fresh.getOrPut("query") { "" })
    }

    @Test fun `a nested memory is kept until it is forgotten`() {
        val home = ScreenMemory()
        val recordings = home.child("recordings")
        recordings.getOrPut("rows") { listOf("r1") }
        assertSame(recordings, home.child("recordings"))
        home.forgetChild("recordings")
        assertNotSame(recordings, home.child("recordings"))
    }

    @Test fun `only the newest screens are kept`() {
        val memories = ScreenMemories<Int>(cap = 3)
        (1..4).forEach { memories.arrive(it, restoring = false) }
        assertFalse(memories.has(1))
        assertTrue(memories.has(4))
        // Restoring a screen that was dropped starts it over rather than failing.
        assertEquals(0, memories.arrive(1, restoring = true).size)
    }
}
