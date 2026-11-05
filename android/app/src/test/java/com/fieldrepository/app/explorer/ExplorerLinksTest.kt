package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.ui.ExplorerViewLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExplorerLinksTest {
    private val web = "https://portal.example.org"

    @QFeature("X1")
    @Test fun `a saved-view link names its view`() {
        val got = ExplorerViewLinks.parse("https://portal.example.org/data?viewId=v42", web)
        assertEquals("v42", got?.viewId)
        assertNull(got?.path)
    }

    @QFeature("X1")
    @Test fun `a folder link carries its decoded path`() {
        val got = ExplorerViewLinks.parse("https://portal.example.org/data/?path=by-workshop%2Fw1%2Fcrafts", web)
        assertNull(got?.viewId)
        assertEquals("by-workshop/w1/crafts", got?.path)
    }

    @QFeature("X1")
    @Test fun `links to other hosts, schemes or pages are not ours`() {
        assertNull(ExplorerViewLinks.parse("https://evil.example.com/data?viewId=v1", web))
        assertNull(ExplorerViewLinks.parse("http://portal.example.org/data?viewId=v1", web))
        assertNull(ExplorerViewLinks.parse("https://portal.example.org/set-password?token=x", web))
        assertNull(ExplorerViewLinks.parse("not a url", web))
    }

    @QFeature("X1")
    @Test fun `a bare data link opens the data screen where the user was`() {
        val got = ExplorerViewLinks.parse("https://portal.example.org/data", web)
        assertNull(got?.viewId)
        assertNull(got?.path)
        assertEquals(true, got != null)
    }

    @QFeature("X1")
    @Test fun `durable report links are ours and split into their parts`() {
        assertEquals(listOf("media", "m1", "download"), ExplorerViewLinks.parse("https://portal.example.org/data/go/media/m1/download", web)?.go)
        assertEquals(listOf("open", "artisan", "a 1"), ExplorerViewLinks.parse("https://portal.example.org/data/go/open/artisan/a%201", web)?.go)
        assertEquals(listOf("transcript", "m2", "srt"), ExplorerViewLinks.parse("https://portal.example.org/data/go/transcript/m2/srt", web)?.go)
        assertNull(ExplorerViewLinks.parse("https://portal.example.org/data/go/", web))
        assertNull(ExplorerViewLinks.parse("https://portal.example.org/data/gone/x", web))
    }

    @QFeature("X1")
    @Test fun `preview, play-from and bundle links carry their values`() {
        val p = ExplorerViewLinks.parse("https://portal.example.org/data?preview=m9&t=12:04", web)
        assertEquals("m9", p?.preview)
        assertEquals(724.0, p?.seconds ?: -1.0, 0.0)
        assertEquals(90.5, ExplorerViewLinks.parse("https://portal.example.org/data?preview=m9&t=90.5", web)?.seconds ?: -1.0, 0.0)
        assertEquals(3723.0, ExplorerViewLinks.seconds("1:02:03") ?: -1.0, 0.0)
        assertNull(ExplorerViewLinks.seconds("soon"))
        assertEquals("b7", ExplorerViewLinks.parse("https://portal.example.org/data?bundle=b7", web)?.bundle)
    }
}
