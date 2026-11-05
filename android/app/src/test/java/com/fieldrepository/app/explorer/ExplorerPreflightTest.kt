package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerAudio
import com.fieldrepository.app.explorer.data.ExplorerAudioLine
import com.fieldrepository.app.explorer.data.ExplorerAudioLines
import com.fieldrepository.app.explorer.data.ExplorerKind
import com.fieldrepository.app.explorer.data.ExplorerLink
import com.fieldrepository.app.explorer.data.ExplorerPreflight
import com.fieldrepository.app.explorer.data.ExplorerSize
import com.fieldrepository.app.explorer.data.ExplorerSpeedMemory
import com.fieldrepository.app.explorer.data.ExplorerText
import com.fieldrepository.app.explorer.data.ExplorerTypeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerPreflightTest {
    private val mb = 1024L * 1024L
    private val gb = 1024L * mb

    private val workshop = ExplorerSize(
        files = 926,
        bytes = 7 * gb,
        byKind = mapOf(
            ExplorerKind.VIDEOS to ExplorerTypeStats(57, 5 * gb),
            ExplorerKind.IMAGES to ExplorerTypeStats(600, 1 * gb),
            ExplorerKind.AUDIOS to ExplorerTypeStats(269, 1 * gb),
        ),
        largest = 701 * mb,
    )

    @Test fun `a big download on wi-fi shows the sheet with time, space and a smaller choice`() {
        val plan = ExplorerPreflight.of(workshop, ExplorerLink(online = true, metered = false), freeBytes = 28 * gb, speedBps = 6 * mb, location = "Downloads/FieldRepository/W.zip")
        assertTrue(plan.show)
        assertFalse(plan.metered)
        assertTrue(plan.enoughSpace)
        assertEquals(21 * gb, plan.freeAfter)
        assertEquals((7 * gb + 6 * mb - 1) / (6 * mb), plan.seconds)
        val smaller = plan.smaller!!
        assertEquals(2 * gb, smaller.bytes)
        assertEquals(869, smaller.files)
        assertEquals("images,audios", smaller.include)
        assertEquals(ExplorerKind.VIDEOS, smaller.skipped)
        assertEquals("926 files · 7.0 GB · about 20 min on this Wi-Fi", ExplorerText.preflightLine(plan.files, plan.bytes, plan.seconds, plan.metered))
        assertEquals("Skip videos · 2.0 GB · 6 min", ExplorerText.skip(smaller.skipped, "2.0 GB", smaller.seconds))
    }

    @Test fun `small downloads on wi-fi go straight through, mobile data always asks`() {
        val small = ExplorerSize(files = 3, bytes = 40 * mb, largest = 20 * mb)
        assertFalse(ExplorerPreflight.of(small, ExplorerLink(true, false), 10 * gb, null, "x").show)
        assertTrue(ExplorerPreflight.of(small, ExplorerLink(true, true), 10 * gb, null, "x").show)
    }

    @Test fun `too little room shows the sheet and says so`() {
        val plan = ExplorerPreflight.of(workshop, ExplorerLink(true, false), freeBytes = 6 * gb, speedBps = null, location = "x")
        assertTrue(plan.show)
        assertFalse(plan.enoughSpace)
        assertEquals(0L, plan.freeAfter)
        assertTrue(plan.needed > 7 * gb)
        assertEquals(ExplorerPreflight.seconds(7 * gb, ExplorerPreflight.FALLBACK_WIFI_BPS), plan.seconds)
    }

    @Test fun `no smaller choice when there are no videos or only videos`() {
        val photos = ExplorerSize(files = 10, bytes = 900 * mb, byKind = mapOf(ExplorerKind.IMAGES to ExplorerTypeStats(10, 900 * mb)))
        assertNull(ExplorerPreflight.of(photos, ExplorerLink(true, false), null, null, "x").smaller)
        val films = ExplorerSize(files = 2, bytes = 900 * mb, byKind = mapOf(ExplorerKind.VIDEOS to ExplorerTypeStats(2, 900 * mb)))
        assertNull(ExplorerPreflight.of(films, ExplorerLink(true, false), null, null, "x").smaller)
    }

    @Test fun `remembered speed leans on the newest download`() {
        assertEquals(8 * mb, ExplorerSpeedMemory.blend(10 * mb, 5 * mb))
    }

    private val lines = listOf(
        ExplorerAudioLine(1_069_000, 1_094_000, "Sunita", "Black clay from the pond, lime, gum and wheat husk."),
        ExplorerAudioLine(1_094_000, 1_116_000, "Sunita", "Without the lime it cracks when it dries."),
        ExplorerAudioLine(1_116_000, 1_138_000, "Kamla", "My mother used less gum."),
    )

    @Test fun `the spoken line follows the playhead`() {
        assertNull(ExplorerAudioLines.at(lines, 1_000))
        assertEquals("Sunita", ExplorerAudioLines.at(lines, 1_069_000)?.speaker)
        assertEquals(1, ExplorerAudioLines.index(lines, 1_100_000))
        assertEquals(2, ExplorerAudioLines.index(lines, 9_999_999))
        assertEquals(-1, ExplorerAudioLines.index(emptyList(), 5))
        assertEquals("Sunita: “Without the lime it cracks when it dries.”", ExplorerAudioLines.spoken(lines[1]))
        assertNull(ExplorerAudioLines.spoken(ExplorerAudioLine(0, 0, "A", "  ")))
    }

    @Test fun `clock and speed steps read like the player`() {
        assertEquals("18:20", ExplorerAudioLines.clock(1_100_000))
        assertEquals("1:02:03", ExplorerAudioLines.clock(3_723_000))
        assertEquals("0:00", ExplorerAudioLines.clock(-5))
        assertEquals(1.25f, ExplorerAudio.nextSpeed(1f))
        assertEquals(0.75f, ExplorerAudio.nextSpeed(2f))
    }
}
