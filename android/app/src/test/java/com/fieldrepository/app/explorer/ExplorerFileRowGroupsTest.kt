package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.ui.fileRowGroups
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerFileRowGroupsTest {
    private val wide = setOf("details.txt", "notes.md")

    @Test fun `a phone lists one file per row`() {
        assertEquals(listOf(listOf("a.jpg"), listOf("details.txt"), listOf("b.jpg")), fileRowGroups(listOf("a.jpg", "details.txt", "b.jpg"), 1) { it in wide })
    }

    @Test fun `a roomy pane pairs media and gives a text file the whole row`() {
        val files = listOf("a.jpg", "b.jpg", "c.mp4", "details.txt", "d.m4a", "notes.md", "e.pdf", "f.pdf", "g.pdf")
        assertEquals(
            listOf(listOf("a.jpg", "b.jpg"), listOf("c.mp4"), listOf("details.txt"), listOf("d.m4a"), listOf("notes.md"), listOf("e.pdf", "f.pdf"), listOf("g.pdf")),
            fileRowGroups(files, 2) { it in wide },
        )
    }

    @Test fun `order and count never change`() {
        val files = (1..41).map { if (it % 7 == 0) "details-$it.txt" else "f$it.jpg" }
        val rows = fileRowGroups(files, 2) { it.endsWith(".txt") }
        assertEquals(files, rows.flatten())
        rows.forEach { row -> assert(row.size in 1..2) }
        rows.filter { row -> row.any { it.endsWith(".txt") } }.forEach { assertEquals(1, it.size) }
    }

    @Test fun `the lone details file sits by itself`() {
        assertEquals(listOf(listOf("details.txt")), fileRowGroups(listOf("details.txt"), 2) { it in wide })
    }
}
