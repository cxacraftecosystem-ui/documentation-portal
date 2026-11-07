package com.fieldrepository.app.ui

import com.fieldrepository.app.data.ApiClient
import com.fieldrepository.app.data.UnifiedRemoteDto
import com.fieldrepository.app.data.UnifiedSearchResponse
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSearchRulesTest {
    private val body = """
        {"query": "indigo", "mode": "text", "suggestion": "indigo",
         "groups": [
           {"type": "transcripts", "label": "Transcripts", "total": 21, "localTotal": 21, "remoteTotal": 0,
            "items": [
              {"portal": "fieldRepository", "local": true, "type": "transcripts", "id": "m1", "title": "Section D",
               "snippet": "the mud resists the indigo", "highlights": [[20, 26], [400, 500]],
               "startMs": 724000, "mediaType": "AUDIO", "recordDate": "2026-09-22T10:00:00Z"}
            ]},
           {"type": "artisans", "label": "Artisans", "total": 1, "localTotal": 1, "remoteTotal": 0,
            "items": [
              {"portal": "fieldRepository", "local": true, "type": "artisans", "id": "a1", "title": "Giriraj",
               "alsoIn": {"portal": "designer", "id": "d1", "openUrl": "https://designer.example/artisans/d1", "subtitle": "31 files there"}}
            ]},
           {"type": "tools", "label": "Tools", "total": 2, "localTotal": 0, "remoteTotal": 2,
            "items": [
              {"portal": "designer", "local": false, "type": "tools", "id": "t9", "title": "Stirrer",
               "alsoIn": [{"portal": "designer", "id": "x"}]}
            ]}
         ],
         "remote": {"portal": "designer", "label": "Designer portal", "status": "ok"},
         "typesRefused": []}
    """.trimIndent()

    private fun parse(): UnifiedSearchResponse = ApiClient.json.decodeFromString(UnifiedSearchResponse.serializer(), body)

    @Test fun `the answer decodes with also-in as an object or a list`() {
        val r = parse()
        val artisan = r.groups[1].items.single()
        assertEquals("31 files there", (DataSearchRules.alsoIn(artisan)?.get("subtitle") as? JsonPrimitive)?.content)
        assertEquals("x", (DataSearchRules.alsoIn(r.groups[2].items.single())?.get("id") as? JsonPrimitive)?.content)
        assertNull(DataSearchRules.alsoIn(r.groups[0].items.single()))
        assertEquals("indigo", r.suggestion)
    }

    @Test fun `a transcript plays from its first matching line`() {
        val t = parse().groups[0].items.single()
        assertEquals(724.0, DataSearchRules.startSeconds(t)!!, 0.0)
        assertEquals("12:04", DataSearchRules.clock(724.0))
        assertEquals("1:00:00", DataSearchRules.clock(3600.0))
        assertEquals("media", DataSearchRules.locateType("transcripts"))
        assertEquals(listOf(20 until 26), DataSearchRules.ranges(t, t.snippet!!).filter { !it.isEmpty() })
        assertEquals("22 Sep", DataSearchRules.date(t.recordDate))
    }

    @Test fun `portal counts split this app from the other`() {
        val r = parse()
        assertEquals(22, DataSearchRules.localTotal(r))
        assertEquals(2, DataSearchRules.remoteTotal(r))
    }

    @Test fun `both portals is hidden only after the other portal turns a search away`() {
        val off = UnifiedSearchResponse(remote = UnifiedRemoteDto(status = "off"))
        val refused = UnifiedSearchResponse(remote = UnifiedRemoteDto(status = "notPermitted"))
        val ok = UnifiedSearchResponse(remote = UnifiedRemoteDto(status = "ok"))
        assertFalse(DataSearchRules.crossBlocked("local", off))
        assertTrue(DataSearchRules.crossBlocked("all", off))
        assertTrue(DataSearchRules.crossBlocked("all", refused))
        assertFalse(DataSearchRules.crossBlocked("all", ok))
    }

    @Test fun `record now finds the artisan of a folder`() {
        assertEquals("a1", dataFolderArtisan("by-workshop/w1/crafts/c1/artisans/a1"))
        assertEquals("a1", dataFolderArtisan("by-workshop/w1/crafts/c1/artisans/a1/tools/t1"))
        assertNull(dataFolderArtisan("by-workshop/w1"))
        assertNull(dataFolderArtisan(""))
    }

    @Test fun `every result type the backend can return opens something`() {
        val here = listOf("artisans", "crafts", "workshops", "interviews", "transcripts", "products", "tools", "processes", "media")
        val located = setOf("workshop", "artisan", "craft", "product", "tool", "process", "interview", "media")
        for (type in here) {
            assertEquals(type, DataSearchOpen.LOCATE, DataSearchRules.openOf(type, local = true))
            assertTrue("$type locates as an unknown kind", DataSearchRules.locateType(type) in located)
        }
        for (type in com.fieldrepository.app.data.UNIFIED_SEARCH_TYPES) {
            assertEquals(DataSearchOpen.REMOTE, DataSearchRules.openOf(type, local = false))
        }
    }
}
