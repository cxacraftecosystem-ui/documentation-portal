package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.ArtisanDto
import com.fieldrepository.app.data.SearchResultsDto
import com.fieldrepository.app.data.SearchTotalsDto
import com.fieldrepository.app.data.UnifiedRemoteDto
import com.fieldrepository.app.data.UnifiedSearchResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSearchModelTest {
    private val body = """
        {"query": "indigo", "mode": "text", "suggestion": null,
         "groups": [
           {"type": "tools", "label": "Tools", "total": 3, "localTotal": 1, "remoteTotal": 2,
            "items": [
              {"portal": "designer", "local": false, "type": "tools", "id": "t9", "title": "Indigo vat stirrer",
               "subtitle": "2 artisans · 2 workshops", "openUrl": "https://designer.example/tools/t9"}
            ]},
           {"type": "transcripts", "label": "Transcripts", "total": 21, "localTotal": 21, "remoteTotal": 0,
            "items": [
              {"portal": "fieldRepository", "local": true, "type": "transcripts", "id": "m1", "title": "Section D · Materials",
               "snippet": "the mud resists the indigo where we press the block", "highlights": [[23, 29], [400, 500]],
               "startMs": 724000, "mediaType": "AUDIO", "recordDate": "2026-09-22T10:00:00Z", "subtitle": "Giriraj Prasad Chhipa"}
            ]},
           {"type": "artisans", "label": "Artisans", "total": 1, "localTotal": 1, "remoteTotal": 0,
            "items": [
              {"portal": "fieldRepository", "local": true, "type": "artisans", "id": "a1", "title": "Giriraj Prasad Chhipa",
               "alsoIn": [{"portal": "designer", "id": "d1", "openUrl": "https://designer.example/artisans/d1", "subtitle": "48 files here, 31 in Design Workshop"}]}
            ]},
           {"type": "holograms", "label": "?", "total": 4, "items": [{"id": "h1", "title": "?"}]}
         ],
         "remote": {"portal": "designer", "label": "Designer portal", "status": "ok"},
         "typesRefused": []}
    """.trimIndent()

    private fun parse(): UnifiedSearchResponse = ApiClient.json.decodeFromString(UnifiedSearchResponse.serializer(), body)

    @Test fun `pills count every known type and lead with all`() {
        val pills = DataSearchModel.pills(parse())
        assertEquals(listOf(null, "artisans", "transcripts", "tools"), pills.map { it.type })
        assertEquals(25, pills.first().count)
        assertEquals("Transcripts", pills[2].label)
        assertEquals(23 to 2, DataSearchModel.portalCounts(parse()))
    }

    @Test fun `a transcript hit plays from its line and opens in data as media`() {
        val hit = DataSearchModel.hits(parse(), "transcripts").single()
        assertEquals("Transcript · 12:04 · 22 Sep", DataSearchModel.headline(hit))
        assertEquals(724_000L, hit.startMs)
        assertEquals(listOf(23 until 29), hit.highlights)
        assertEquals("media", DataSearchModel.locateType(hit.type))
        assertTrue(hit.local)
    }

    @Test fun `results from the other portal keep their link and badge`() {
        val tool = DataSearchModel.hits(parse(), "tools").single()
        assertFalse(tool.local)
        assertEquals(DataSearchModel.OTHER, tool.portal)
        assertEquals("Design Workshop", DataSearchModel.portalName(tool.portal))
        assertEquals("https://designer.example/tools/t9", tool.openUrl)
    }

    @Test fun `the same artisan in both portals is one result`() {
        val artisan = DataSearchModel.hits(parse(), "artisans").single()
        assertTrue(artisan.inBoth)
        assertEquals("Artisan · in both", DataSearchModel.headline(artisan))
        assertEquals("Same person in both portals · 48 files here, 31 in Design Workshop", DataSearchModel.bothLine(artisan))
    }

    @Test fun `show more never repeats a result`() {
        val first = DataSearchModel.hits(parse())
        val again = DataSearchModel.more(first, first + DataSearchModel.hits(parse(), "tools"))
        assertEquals(first.size, again.size)
    }

    @Test fun `the legacy search fills the same shape when unified search is off`() {
        val legacy = DataSearchModel.legacy(
            "giri",
            SearchResultsDto(artisans = listOf(ArtisanDto(id = "a1", name = "Giriraj", place = "Bagru", status = "APPROVED")), totals = SearchTotalsDto(artisans = 4)),
        )
        val pills = DataSearchModel.pills(legacy)
        assertEquals(listOf(null, "artisans"), pills.map { it.type })
        assertEquals(4, pills.first().count)
        assertEquals("Giriraj", DataSearchModel.hits(legacy).single().title)
        assertNull(legacy.suggestion)
    }

    @Test fun `the other portal counts only when it was searched`() {
        assertTrue(DataSearchModel.searchesOther(UnifiedRemoteDto(status = "ok")))
        assertFalse(DataSearchModel.searchesOther(UnifiedRemoteDto(status = "off")))
        assertFalse(DataSearchModel.searchesOther(UnifiedRemoteDto(status = "notPermitted")))
    }

    @Test fun `clocks read minutes and seconds`() {
        assertEquals("03:12", DataSearchModel.clock(192_000))
        assertEquals("1:00:00", DataSearchModel.clock(3_600_000))
        assertNull(DataSearchModel.day("bad"))
    }
}
