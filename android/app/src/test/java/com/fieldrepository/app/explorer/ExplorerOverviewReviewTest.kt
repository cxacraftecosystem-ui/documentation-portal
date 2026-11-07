package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerJson
import com.fieldrepository.app.explorer.data.ExplorerOverview
import com.fieldrepository.app.explorer.data.ExplorerReviewTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerOverviewReviewTest {
    @Test fun `the review column reads as a word with the counts beside it`() {
        assertEquals("Approved", ExplorerReviewTotals(3, 0, 3).word)
        assertEquals("Pending", ExplorerReviewTotals(0, 3, 3).word)
        assertEquals("Partly approved", ExplorerReviewTotals(2, 1, 3).word)
        assertEquals("2 / 3", ExplorerReviewTotals(2, 1, 3).counts)
        assertTrue(ExplorerReviewTotals(3, 0, 3).approvedAll)
        assertFalse(ExplorerReviewTotals(2, 1, 3).approvedAll)
        assertEquals("", ExplorerReviewTotals().word)
    }

    @Test fun `the backend overview decodes with per-artisan review and transcript counts`() {
        val body = """
            {"path":"by-workshop/w1","title":"Bagru","totals":{"files":3,"bytes":10,"fields":{"filled":1,"total":2},
            "transcripts":{"total":2,"ready":2,"translating":0,"failed":0},"review":{"approved":2,"pending":1,"total":3}},
            "sections":[{"key":"A","label":"Section A"}],
            "artisans":[{"id":"a1","name":"Kamla","path":"p","fields":{"filled":1,"total":2},"media":{"files":3,"bytes":10},
            "transcripts":{"total":2,"ready":2,"translating":0,"failed":0},"review":{"approved":2,"pending":1,"total":3},"sections":[]}]}
        """.trimIndent()
        val overview = ExplorerJson.decodeFromString(ExplorerOverview.serializer(), body)
        val artisan = overview.artisans.single()
        assertEquals(2, artisan.transcripts)
        assertEquals(ExplorerReviewTotals(2, 1, 3), artisan.review)
        assertEquals("Partly approved", artisan.review?.word)
    }

    @Test fun `a plain transcript count still decodes`() {
        val body = """{"artisans":[{"id":"a1","transcripts":4}]}"""
        assertEquals(4, ExplorerJson.decodeFromString(ExplorerOverview.serializer(), body).artisans.single().transcripts)
    }
}
