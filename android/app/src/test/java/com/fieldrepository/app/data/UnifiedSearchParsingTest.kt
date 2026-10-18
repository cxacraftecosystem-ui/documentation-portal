package com.fieldrepository.app.data

import com.fieldrepository.app.ui.AppSearchGroup
import com.fieldrepository.app.ui.unifiedSearchItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `GET /search/unified` and `GET /search/remote/{type}/{id}` as the phone reads them
 * (docs/SEARCH.md): decoded with the app's own `Json`, unknown types ignored, local rows open this
 * app's screens and remote rows the read-only card.
 */
class UnifiedSearchParsingTest {

    private val body = """
        {"query": "indigo", "mode": "hybrid",
         "groups": [
           {"type": "media", "label": "Media", "total": 1, "localTotal": 1, "remoteTotal": 0,
            "items": [{"portal": "fieldRepository", "local": true, "type": "media", "id": "m1", "title": "vat.mp3",
                       "href": "/media/m1", "highlights": [[0, 3]], "score": 0.016}]},
           {"type": "artisans", "label": "Artisans", "total": 6, "localTotal": 1, "remoteTotal": 5,
            "items": [
              {"portal": "fieldRepository", "local": true, "type": "artisans", "id": "a1", "title": "Ramesh",
               "subtitle": "Indigo dyeing", "href": "/artisans/a1", "openUrl": null, "score": 0.0164},
              {"portal": "designer", "local": false, "type": "artisans", "id": "dp9", "title": "Meena",
               "href": "/remote/artisans/dp9", "openUrl": "https://designer.example/artisans/dp9", "score": 0.0164,
               "somethingNew": true}
            ]},
           {"type": "reports", "label": "Reports", "total": 1, "localTotal": 0, "remoteTotal": 1,
            "items": [{"portal": "designer", "local": false, "type": "reports", "id": "r1", "title": "camp.pdf",
                       "href": "/remote/reports/r1", "openUrl": "https://designer.example/design-workshops/dw7"}]},
           {"type": "transcripts", "label": "Transcripts", "total": 1, "localTotal": 1, "remoteTotal": 0,
            "items": [{"portal": "fieldRepository", "local": true, "type": "transcripts", "id": "m2", "title": "talk.m4a",
                       "href": "/media/m2"}]},
           {"type": "holograms", "label": "?", "total": 1, "items": [{"id": "h1", "title": "?"}]}
         ],
         "remote": {"portal": "designer", "label": "Designer portal", "status": "ok"},
         "typesRefused": []}
    """.trimIndent()

    private fun parse(): UnifiedSearchResponse = ApiClient.json.decodeFromString(UnifiedSearchResponse.serializer(), body)

    @Test
    fun `the answer decodes, and items come in vocabulary order with unknown types dropped`() {
        val response = parse()
        assertEquals("hybrid", response.mode)
        assertEquals("ok", response.remote.status)
        assertEquals("designer", response.remote.portal)
        assertEquals(listOf("a1", "dp9", "m2", "r1", "m1"), response.knownItems().map { it.id })
        assertEquals(listOf(0, 3), response.knownItems().last().highlights.single())
    }

    @Test
    fun `local rows open this app, remote rows carry their portal and open the card`() {
        val opened = mutableListOf<String>()
        val items = unifiedSearchItems(
            parse(),
            openRecord = { type, id -> opened += "record:$type:$id" },
            openRemote = { type, id -> opened += "remote:$type:$id" }
        )
        assertEquals(
            listOf(
                AppSearchGroup.ARTISANS, AppSearchGroup.ARTISANS, AppSearchGroup.TRANSCRIPTS,
                AppSearchGroup.REPORTS, AppSearchGroup.MEDIA
            ),
            items.map { it.group }
        )
        assertNull(items[0].portal)
        assertEquals("designer", items[1].portal)
        items.forEach { it.onOpen() }
        assertEquals(
            listOf("record:artisan:a1", "remote:artisans:dp9", "record:media:m2", "remote:reports:r1", "record:media:m1"),
            opened
        )
    }

    @Test
    fun `a designer-only type that claims to be local is not drawn`() {
        val response = UnifiedSearchResponse(
            groups = listOf(
                UnifiedSearchGroupDto(
                    type = "clusters",
                    items = listOf(UnifiedSearchItemDto(local = true, type = "clusters", id = "bagru", title = "Bagru"))
                )
            )
        )
        assertEquals(emptyList<Any>(), unifiedSearchItems(response, { _, _ -> }, { _, _ -> }))
    }

    @Test
    fun `a remote record decodes into its card rows`() {
        val record = ApiClient.json.decodeFromString(
            RemoteRecord.serializer(),
            """{"portal": "designer", "type": "designWorkshops", "id": "dw7", "title": "Bagru camp",
                "fields": [{"label": "Craft", "value": "Bagru"}, {"label": "Place", "value": ""}],
                "recordDate": "2026-09-02T00:00:00Z", "openUrl": "https://designer.example/design-workshops/dw7"}"""
        )
        assertEquals(listOf("Craft" to "Bagru", "Recorded" to "2026-09-02"), record.cardRows { it.take(10) })
        assertEquals("Open in Designer portal", openInLabel(record.portal))
    }
}
