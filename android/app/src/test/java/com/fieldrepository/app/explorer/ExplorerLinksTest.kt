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
}
