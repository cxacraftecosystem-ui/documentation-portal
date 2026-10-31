package com.fieldrepository.app.transcript

import com.fieldrepository.app.transcript.TranscriptContractTest.Companion.example
import com.fieldrepository.app.transcript.data.TranscriptKaraoke
import com.fieldrepository.app.transcript.data.TranscriptMarkdown
import com.fieldrepository.app.transcript.data.TranscriptSummary
import com.fieldrepository.app.transcript.data.TranscriptSummaryBatcher
import com.fieldrepository.app.transcript.data.contentDispositionName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptKaraokeTest {
    private val turns = example.turns

    @Test fun `the turn lit is the last one started`() {
        assertEquals(-1, TranscriptKaraoke.turnAt(turns, 1000))
        assertEquals(0, TranscriptKaraoke.turnAt(turns, 4000))
        assertEquals(0, TranscriptKaraoke.turnAt(turns, 8000))
        assertEquals(1, TranscriptKaraoke.turnAt(turns, 9000))
        assertEquals(1, TranscriptKaraoke.turnAt(turns, 99_000))
    }

    @Test fun `the word lit follows the word timings and lingers briefly in a pause`() {
        val a = turns[0]
        assertEquals(0, TranscriptKaraoke.wordAt(a, 4100))
        assertEquals(2, TranscriptKaraoke.wordAt(a, 4700))
        // 4300..4350 is the gap after word 0: still word 0.
        assertEquals(0, TranscriptKaraoke.wordAt(a, 4320))
        assertEquals(5, TranscriptKaraoke.wordAt(a, 7600 + 100))
        assertEquals(-1, TranscriptKaraoke.wordAt(a, 7600 + TranscriptKaraoke.LINGER_MS + 1))
        assertEquals(TranscriptKaraoke.Position(0, 3), TranscriptKaraoke.at(turns, 5200))
    }

    @Test fun `word ranges come from the offsets`() {
        val a = turns[0]
        val ranges = TranscriptKaraoke.wordRanges(a)
        assertEquals("रंग", a.text.substring(ranges[2]!!.first, ranges[2]!!.last + 1))
        assertEquals("हैं?", a.text.substring(ranges[5]!!.first, ranges[5]!!.last + 1))
    }

    @Test fun `a tap on a word seeks to it, a tap elsewhere to the turn`() {
        val a = turns[0]
        assertEquals(4650L, TranscriptKaraoke.seekFor(a, 7))
        assertEquals(4000L, TranscriptKaraoke.seekFor(a, 2))
        assertEquals(9000L, TranscriptKaraoke.seekFor(turns[1], 5))
    }

    @Test fun `low confidence spans map into the text`() {
        val b = turns[1]
        val d = TranscriptKaraoke.doubts(b)
        assertEquals(1, d.size)
        assertEquals("dabu", b.text.substring(d[0].first, d[0].last + 1))
        assertTrue(TranscriptKaraoke.doubts(b.copy(lowConfidence = listOf(listOf(5, 2), listOf(0, 999)))).isEmpty())
    }
}

class TranscriptMarkdownTest {
    @Test fun `the copy text reads back as heading, table and turns`() {
        val blocks = TranscriptMarkdown.parse(com.fieldrepository.app.transcript.data.TranscriptFormat.all(example, com.fieldrepository.app.transcript.data.CopyTrack.BOTH))
        assertTrue(blocks[0] is TranscriptMarkdown.Block.Heading)
        assertEquals(6, (blocks[1] as TranscriptMarkdown.Block.Table).cells.size)
        val firstTurn = blocks[2] as TranscriptMarkdown.Block.Paragraph
        assertTrue(firstTurn.inline.text.startsWith("Nandini (researcher) · 00:00:04"))
        assertEquals(TranscriptMarkdown.Style.BOLD, firstTurn.inline.spans[0].style)
        assertTrue(blocks.any { it is TranscriptMarkdown.Block.Quote && it.inline.text == "How do you make these colours?" })
    }

    @Test fun `html is never interpreted, only shown as typed`() {
        val inline = TranscriptMarkdown.inline("<script>alert(1)</script> <b>x</b>")
        assertEquals("<script>alert(1)</script> <b>x</b>", inline.text)
        assertTrue(inline.spans.isEmpty())
        assertEquals("<img src=x onerror=y>", TranscriptMarkdown.plain("<img src=x onerror=y>"))
    }

    @Test fun `emphasis, code and escapes`() {
        val i = TranscriptMarkdown.inline("*bandhani* (tie-dye) uses `snake_case` and \\*stars\\* in a_b")
        assertEquals("bandhani (tie-dye) uses snake_case and *stars* in a_b", i.text)
        assertEquals(listOf(TranscriptMarkdown.Style.ITALIC, TranscriptMarkdown.Style.CODE), i.spans.map { it.style })
    }

    @Test fun `an unclosed marker stays literal`() {
        assertEquals("**open and *half", TranscriptMarkdown.inline("**open and *half").text)
    }
}

class TranscriptSmallPartsTest {
    @Test fun `the server's file name is taken only when it is plain ascii`() {
        assertEquals(
            "DesignWorkshop_Interview_2026-06-24_Giriraj-Prasad-Chhipa_Transcript.md",
            contentDispositionName("attachment; filename=\"DesignWorkshop_Interview_2026-06-24_Giriraj-Prasad-Chhipa_Transcript.md\"; filename*=UTF-8''x")
        )
        assertNull(contentDispositionName("attachment; filename=\"../../etc/passwd\""))
        assertNull(contentDispositionName(null))
    }

    @Test fun `summaries asked together go in one request`() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val batcher = TranscriptSummaryBatcher(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { ids ->
            synchronized(calls) { calls += ids }
            ids.filter { it != "hidden" }.map { TranscriptSummary(it, "READY") }
        }
        val got = listOf("a", "b", "hidden").map { id -> async { batcher.get(id) } }.awaitAll()
        assertEquals(listOf("a", "b", null), got.map { it?.mediaId })
        assertEquals(1, calls.size)
        assertFalse(calls[0].isEmpty())
    }
}
