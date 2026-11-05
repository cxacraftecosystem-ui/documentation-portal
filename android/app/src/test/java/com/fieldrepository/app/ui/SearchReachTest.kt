package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.SearchResultsDto
import com.fieldrepository.app.data.UnifiedSearchResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchReachTest {
    private fun artisans(total: Int, page: Int, size: Int): SearchResultsDto {
        val from = (page - 1) * size
        val rows = (from until minOf(total, from + size)).joinToString(",") { i ->
            """{"id":"a${i + 1}","name":"Artisan ${i + 1}","place":"Kutch","status":"APPROVED"}"""
        }
        return ApiClient.json.decodeFromString(
            SearchResultsDto.serializer(),
            """{"page":$page,"pageSize":$size,"artisans":[$rows],"totals":{"artisans":$total},"total":$total}""",
        )
    }

    private val all = """
        {"query": "indigo", "suggestion": null, "mode": "text", "page": 1, "limit": 5,
         "groups": [
           {"type": "artisans", "label": "Artisans", "total": 31, "localTotal": 31, "remoteTotal": 0,
            "items": [
              {"portal": "designer", "local": true, "type": "artisans", "id": "a1", "title": "Artisan 1", "subtitle": null, "snippet": "", "highlights": [], "place": null, "craftCodes": [], "recordDate": null, "href": "/artisans/a1", "openUrl": null, "score": 0.016393},
              {"portal": "designer", "local": true, "type": "artisans", "id": "a2", "title": "Artisan 2", "subtitle": null, "snippet": "", "highlights": [], "place": null, "craftCodes": [], "recordDate": null, "href": "/artisans/a2", "openUrl": null, "score": 0.016129},
              {"portal": "designer", "local": true, "type": "artisans", "id": "a3", "title": "Artisan 3", "subtitle": null, "snippet": "", "highlights": [], "place": null, "craftCodes": [], "recordDate": null, "href": "/artisans/a3", "openUrl": null, "score": 0.015873},
              {"portal": "designer", "local": true, "type": "artisans", "id": "a4", "title": "Artisan 4", "subtitle": null, "snippet": "", "highlights": [], "place": null, "craftCodes": [], "recordDate": null, "href": "/artisans/a4", "openUrl": null, "score": 0.015625},
              {"portal": "designer", "local": true, "type": "artisans", "id": "a5", "title": "Artisan 5", "subtitle": null, "snippet": "", "highlights": [], "place": null, "craftCodes": [], "recordDate": null, "href": "/artisans/a5", "openUrl": null, "score": 0.015385}
            ]},
           {"type": "products", "label": "Products", "total": 3, "localTotal": 3, "remoteTotal": 0,
            "items": [
              {"portal": "designer", "local": true, "type": "products", "id": "p1", "title": "Stole", "subtitle": null, "snippet": "", "highlights": [], "place": null, "craftCodes": [], "recordDate": null, "href": "/products/p1", "openUrl": null, "score": 0.016393},
              {"portal": "designer", "local": true, "type": "products", "id": "p2", "title": "Sari", "subtitle": null, "snippet": "", "highlights": [], "place": null, "craftCodes": [], "recordDate": null, "href": "/products/p2", "openUrl": null, "score": 0.016129},
              {"portal": "designer", "local": true, "type": "products", "id": "p3", "title": "Dupatta", "subtitle": null, "snippet": "", "highlights": [], "place": null, "craftCodes": [], "recordDate": null, "href": "/products/p3", "openUrl": null, "score": 0.015873}
            ]}
         ],
         "remote": {"portal": "fieldRepository", "label": "Field Repository", "status": "off"},
         "typesRefused": []}
    """.trimIndent()

    private fun parse(): UnifiedSearchResponse = ApiClient.json.decodeFromString(UnifiedSearchResponse.serializer(), all)

    @Test fun `the all view keeps every item the server sends for a type-less search`() {
        val r = parse()
        val items = DataSearchRules.usable(r, null)
        assertEquals(8, items.size)
        assertEquals(listOf("artisans", "products"), DataSearchRules.sections(r.groups, items).map { it.first.type })
        assertEquals(5, DataSearchRules.limitFor(null))
        assertEquals(20, DataSearchRules.limitFor("artisans"))
    }

    @Test fun `the all view knows there is more and how much each group still holds`() {
        val r = parse()
        val items = DataSearchRules.usable(r, null)
        assertTrue(DataSearchRules.more(null, r.groups, items))
        assertEquals(26, DataSearchRules.remaining(r.groups[0], items))
        assertEquals(0, DataSearchRules.remaining(r.groups[1], items))
        assertFalse(DataSearchRules.more("products", r.groups, items))
        assertTrue(DataSearchRules.more("artisans", r.groups, items))
        assertEquals(20, DataSearchRules.nextBatch("artisans", r.groups, items))
        assertEquals("Show all 31 artisans", DataSearchText.showAll(31, "Artisans"))
        assertEquals("8 of 34 shown", DataSearchText.shownLine(items.size, r.groups.sumOf { it.total }))
        assertEquals("All 34 shown", DataSearchText.shownLine(34, 34))
    }

    @Test fun `a type paged to its end stops offering more`() {
        val r = parse()
        val full = (1..31).map { com.fieldrepository.app.data.UnifiedSearchItemDto(type = "artisans", id = "a$it") }
        assertFalse(DataSearchRules.more("artisans", r.groups, full))
        assertEquals(11, DataSearchRules.nextBatch("artisans", r.groups, full.take(20)))
    }

    @Test fun `everything with nothing typed runs once a chip is tapped`() {
        assertFalse(quickSearchRuns(SearchFilters(), browse = false))
        assertTrue(quickSearchRuns(SearchFilters(), browse = true))
        assertFalse(quickSearchRuns(SearchFilters(query = "a"), browse = false))
        assertTrue(quickSearchRuns(SearchFilters(query = "in"), browse = false))
        assertTrue(quickSearchRuns(SearchFilters(types = setOf(SearchRecordTypes.ARTISAN)), browse = false))
    }

    @Test fun `show all pages a quick search to its thirty-first record`() = runBlocking {
        val asked = mutableListOf<Pair<List<String>?, Int>>()
        val state = SearchAllState(SearchFilters(types = setOf(SearchRecordTypes.ARTISAN))) { types, page, size ->
            asked += types to page
            artisans(31, page, size)
        }
        state.start()
        assertEquals(31, state.total)
        assertEquals(20, state.shown)
        assertTrue(state.sections.single().more)
        state.more(SearchRecordTypes.ARTISAN)
        assertEquals(31, state.shown)
        assertEquals("a31", state.sections.single().rows.last().id)
        assertFalse(state.sections.single().more)
        state.more(SearchRecordTypes.ARTISAN)
        assertEquals(listOf(listOf("artisans") to 1, listOf("artisans") to 2), asked)
    }

    @Test fun `everything pages each type on its own`() = runBlocking {
        val asked = mutableListOf<Pair<List<String>?, Int>>()
        val state = SearchAllState(SearchFilters()) { types, page, size ->
            asked += types to page
            artisans(45, page, size)
        }
        state.start()
        state.more(SearchRecordTypes.ARTISAN)
        state.more(SearchRecordTypes.ARTISAN)
        assertEquals(45, state.shown)
        assertEquals(listOf(null to 1, listOf("artisans") to 2, listOf("artisans") to 3), asked)
    }

    @Test fun `a page that adds nothing ends the section instead of looping`() = runBlocking {
        val state = SearchAllState(SearchFilters()) { _, _, size -> artisans(31, 1, size) }
        state.start()
        state.more(SearchRecordTypes.ARTISAN)
        assertEquals(20, state.shown)
        assertFalse(state.sections.single().more)
    }

    @Test fun `a production-sized list of 1200 pages through to its last record`() = runBlocking {
        val state = SearchAllState(SearchFilters(types = setOf(SearchRecordTypes.ARTISAN))) { _, page, size -> artisans(1200, page, size) }
        state.start()
        var taps = 0
        while (state.sections.single().more && taps < 100) {
            state.more(SearchRecordTypes.ARTISAN)
            taps++
        }
        assertEquals(1200, state.shown)
        assertEquals("a1200", state.sections.single().rows.last().id)
        assertEquals(59, taps)
    }

    @Test fun `six hundred and forty-nine matches under one type page in twenties to the end`() {
        val group = com.fieldrepository.app.data.UnifiedSearchGroupDto(type = "media", label = "Media", total = 649)
        var loaded = (1..5).map { com.fieldrepository.app.data.UnifiedSearchItemDto(type = "media", id = "m$it") }
        assertTrue(DataSearchRules.more(null, listOf(group), loaded))
        loaded = emptyList()
        var pages = 0
        while (pages == 0 || DataSearchRules.more("media", listOf(group), loaded)) {
            val from = pages * DataSearchRules.TYPE_LIMIT
            loaded = loaded + (from until minOf(649, from + DataSearchRules.TYPE_LIMIT)).map { com.fieldrepository.app.data.UnifiedSearchItemDto(type = "media", id = "m${it + 1}") }
            pages++
        }
        assertEquals(649, loaded.size)
        assertEquals(33, pages)
    }

    @Test fun `a suggestion says how many results it would bring`() {
        val r = ApiClient.json.decodeFromString(UnifiedSearchResponse.serializer(), """{"query":"indgo","suggestion":"indigo","suggestionTotal":38,"groups":[]}""")
        assertEquals(38, r.suggestionTotal)
        assertEquals("Did you mean indigo? 38 results.", DataSearchText.didYouMean("indigo") + DataSearchText.suggestionCount(r.suggestionTotal))
        assertEquals(" 1 result.", DataSearchText.suggestionCount(1))
        assertEquals("", DataSearchText.suggestionCount(0))
    }
}
