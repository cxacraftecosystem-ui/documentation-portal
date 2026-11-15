package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.explorer.ui.ExplorerAudioExpand
import com.fieldrepository.app.questionnaire.data.OverlayTake
import com.fieldrepository.app.questionnaire.data.QRecording
import com.fieldrepository.app.questionnaire.data.QTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakesInPlaceTest {
    private fun rec(id: String, target: String, at: String, ms: Long, media: String? = "m-$id", part: String? = null, removed: String? = null, clip: String? = null) =
        QRecording(id = id, mediaFileId = media, clientClipKey = clip, capturedAt = at, durationMs = ms, targets = listOf(QTarget(targetKey = target)), continuationOfId = part, removedAt = removed)

    private fun clip(key: String, target: String, at: String, ms: Long, line: String = "On this device", path: String? = "/data/clips/$key.m4a") =
        LocalClip(OverlayTake(key, "session-1", "a1", target, at, ms, 1, uploading = false), line, path)

    @Test fun `a section lists each take in order with its length and date, parts named as parts`() {
        val takes = InPlaceTakes.section(
            "s1",
            listOf(
                rec("r2", "S:s1", "2026-11-15T05:50:00Z", 291_000, part = "r1"),
                rec("r1", "S:s1", "2026-11-15T05:12:00Z", 846_000)
            ),
            listOf(clip("c1", "S:s1", "2026-11-15T06:30:00Z", 30_000, line = "Uploading 40%"))
        )
        assertEquals(listOf("Section take · 14:06", "Part 2 · 4:51", "Take 3 · 0:30"), takes.map { it.title })
        assertEquals(listOf("15 Nov · 10:42", "15 Nov · 11:20", "Uploading 40%"), takes.map { it.subtitle })
        assertEquals(listOf("m-r1", "m-r2", "local:c1"), takes.map { it.mediaKey })
        assertEquals(listOf(false, false, true), takes.map { it.local })
    }

    @Test fun `removed takes, other targets and takes without media do not show`() {
        val takes = InPlaceTakes.section(
            "s1",
            listOf(
                rec("r1", "S:s1", "2026-11-15T05:12:00Z", 60_000, removed = "2026-11-15T07:00:00Z"),
                rec("r2", "S:s2", "2026-11-15T05:12:00Z", 60_000),
                rec("r3", "S:s1", "2026-11-15T05:12:00Z", 60_000, media = null),
                rec("r4", "Q:q1", "2026-11-15T05:12:00Z", 60_000)
            ),
            emptyList()
        )
        assertTrue(takes.isEmpty())
    }

    @Test fun `a clip that has uploaded shows once, as the uploaded take`() {
        val takes = InPlaceTakes.section(
            "s1",
            listOf(rec("r1", "S:s1", "2026-11-15T05:12:00Z", 60_000, clip = "c1")),
            listOf(clip("c1", "S:s1", "2026-11-15T05:12:00Z", 60_000), clip("c1", "S:s1", "2026-11-15T05:12:00Z", 60_000))
        )
        assertEquals(listOf("r1"), takes.map { it.key })
    }

    @Test fun `a clip whose file is gone from this phone is not offered for playback`() {
        assertTrue(InPlaceTakes.section("s1", emptyList(), listOf(clip("c1", "S:s1", "2026-11-15T05:12:00Z", 60_000, path = null))).isEmpty())
        assertNull(TakeRow("local:c1", 0, "1 min", "On this device", null, local = true, removable = false).inPlace())
    }

    @Test fun `a question with one take shows its date, several show their lengths too`() {
        val one = InPlaceTakes.question("q1", listOf(rec("r1", "Q:q1", "2026-11-15T05:12:00Z", 192_000)), emptyList())
        assertEquals("15 Nov · 10:42", one.single().title)
        assertNull(one.single().subtitle)
        val two = InPlaceTakes.question(
            "q1",
            listOf(rec("r1", "Q:q1", "2026-11-15T05:12:00Z", 192_000)),
            listOf(clip("c9", "Q:q1", "2026-11-15T06:00:00Z", 65_000))
        )
        assertEquals(listOf("Question take · 3:12", "Question take · 1:05"), two.map { it.title })
        assertEquals(listOf("15 Nov · 10:42", "On this device"), two.map { it.subtitle })
    }

    @Test fun `an uploaded take is the explorer's own entry, played through the explorer's preview and gate`() {
        val take = InPlaceTakes.question("q1", listOf(rec("r1", "Q:q1", "2026-11-15T05:12:00Z", 192_000)), emptyList()).single()
        val entry = InPlaceTakes.entry(take, "E2")
        assertEquals(ExplorerAudioExpand.entry("m-r1", "E2"), entry)
        assertNull(entry.previewPath)
        assertEquals("/api/data/media/m-r1/preview", ExplorerPaths.preview(entry))
        assertTrue(entry.transcriptAvailable)
    }

    @Test fun `the open take's media is what the list still shows`() {
        val local = listOf(clip("c1", "S:s1", "2026-11-15T05:12:00Z", 30_000))
        val open = "S:s1|local:c1"
        assertEquals("local:c1", InPlaceTakes.openMedia(open, emptyList(), local))
        assertNull(InPlaceTakes.openMedia(open, listOf(rec("r1", "S:s1", "2026-11-15T05:12:00Z", 30_000, clip = "c1")), local))
        val server = listOf(rec("r1", "Q:q1", "2026-11-15T05:12:00Z", 60_000))
        assertEquals("m-r1", InPlaceTakes.openMedia("Q:q1|r1", server, emptyList()))
        assertNull(InPlaceTakes.openMedia("Q:q1|r1", listOf(rec("r1", "Q:q1", "2026-11-15T05:12:00Z", 60_000, removed = "2026-11-15T07:00:00Z")), emptyList()))
        assertNull(InPlaceTakes.openMedia("Q:q1|r1", emptyList(), emptyList()))
        assertNull(InPlaceTakes.openMedia(null, server, emptyList()))
    }

    @Test fun `a take still on the phone plays its file and asks for no transcript`() {
        val take = InPlaceTakes.section("s1", emptyList(), listOf(clip("c1", "S:s1", "2026-11-15T05:12:00Z", 30_000))).single()
        val entry = InPlaceTakes.entry(take, "Section E")
        assertEquals("local:c1", entry.mediaId)
        assertTrue(entry.previewPath!!.startsWith("file:"))
        assertTrue(entry.previewPath!!.endsWith("/data/clips/c1.m4a"))
        assertFalse(entry.transcriptAvailable)
    }

    @Test fun `one take is open at a time and the open key names its place`() {
        val takes = InPlaceTakes.section(
            "s1",
            listOf(rec("r1", "S:s1", "2026-11-15T05:12:00Z", 60_000), rec("r2", "S:s1", "2026-11-15T06:12:00Z", 60_000)),
            emptyList()
        )
        val open = InPlaceTakes.openKey("S:s1", takes[1])
        assertEquals("S:s1|r2", open)
        assertTrue(InPlaceTakes.isOpen(open, "S:s1", takes[1]))
        assertFalse(InPlaceTakes.isOpen(open, "S:s1", takes[0]))
        assertTrue(InPlaceTakes.openIn(open, "S:s1"))
        assertFalse(InPlaceTakes.openIn(open, "Q:q1"))
        assertFalse(InPlaceTakes.openIn(null, "S:s1"))
        assertEquals("m-r2", InPlaceTakes.mediaOf(open, takes))
    }

    @Test fun `a take sheet row maps to the same preview, removed rows do not`() {
        val server = TakeRow("r1", 1, "Take 1  3 min", "Solo", null, local = false, removable = false, mediaFileId = "m1", durationMs = 180_000)
        assertEquals("m1", server.inPlace()!!.mediaKey)
        assertEquals("r1", server.inPlace()!!.recordingId)
        val local = TakeRow("local:c1", 0, "1 min", "On this device", null, local = true, removable = false, localPath = "/data/c1.m4a")
        assertEquals("local:c1", local.inPlace()!!.mediaKey)
        assertTrue(local.inPlace()!!.local)
        assertNull(TakeRow("r2", 0, "3 min", "", null, local = false, removable = true, removed = true, mediaFileId = "m2").inPlace())
        assertNull(TakeRow("r3", 1, "3 min", "", null, local = false, removable = false).inPlace())
    }
}
