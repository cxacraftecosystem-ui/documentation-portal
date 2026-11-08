package com.fieldrepository.app.explorer

import com.fieldrepository.app.explorer.data.ExplorerActiveFilter
import com.fieldrepository.app.explorer.data.ExplorerActiveValue
import com.fieldrepository.app.explorer.data.ExplorerAppliedFilters
import com.fieldrepository.app.explorer.data.ExplorerFacet
import com.fieldrepository.app.explorer.data.ExplorerFacetKeys
import com.fieldrepository.app.explorer.data.ExplorerFacetOption
import com.fieldrepository.app.explorer.data.ExplorerFacets
import com.fieldrepository.app.explorer.data.ExplorerFilterKey
import com.fieldrepository.app.explorer.data.ExplorerFilterOption
import com.fieldrepository.app.explorer.data.ExplorerFilters
import com.fieldrepository.app.explorer.data.ExplorerPaths
import com.fieldrepository.app.explorer.data.ExplorerPeerPage
import com.fieldrepository.app.explorer.data.ExplorerPeerRow
import com.fieldrepository.app.explorer.data.ExplorerPeerTypes
import com.fieldrepository.app.explorer.data.ExplorerPortalChoices
import com.fieldrepository.app.explorer.data.ExplorerPortalOption
import com.fieldrepository.app.explorer.data.ExplorerViewFilters
import com.fieldrepository.app.explorer.ui.ExplorerOptionMatch
import com.fieldrepository.app.explorer.ui.ExplorerPeerRows
import com.fieldrepository.app.explorer.ui.ExplorerResume
import com.fieldrepository.app.explorer.ui.ExplorerTab
import com.fieldrepository.app.explorer.ui.ExplorerViewLinks
import com.fieldrepository.app.explorer.ui.PeerLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerFiltersTest {
    private val bamboo = ExplorerFilters().with(ExplorerFilterKey.CRAFT, listOf("c1", "c2"), mapOf("c1" to "Bamboo", "c2" to "Clay", "c9" to "Unpicked"))

    @QFeature("X13")
    @Test fun `every dimension is a comma-separated parameter with the contract's name`() {
        val f = bamboo
            .with(ExplorerFilterKey.WORKSHOP, listOf("w1"))
            .with(ExplorerFilterKey.DESIGN_WORKSHOP, listOf("d1", "d2"))
            .with(ExplorerFilterKey.ARTISAN, listOf("a1"))
            .with(ExplorerFilterKey.MEDIA_TYPE, listOf("VIDEO", "AUDIO"))
            .with(ExplorerFilterKey.STATUS, listOf("APPROVED"))
            .with(ExplorerFilterKey.RECORDED_BY, listOf("u1"))
            .with(ExplorerFilterKey.LANGUAGE, listOf("ben"))
            .dates("2026-01-01", "2026-02-01")
        assertEquals(
            mapOf(
                "workshopIds" to "w1",
                "designWorkshopIds" to "d1,d2",
                "craftIds" to "c1,c2",
                "artisanIds" to "a1",
                "mediaTypes" to "VIDEO,AUDIO",
                "statuses" to "APPROVED",
                "recordedByIds" to "u1",
                "languages" to "ben",
                "dateFrom" to "2026-01-01",
                "dateTo" to "2026-02-01",
            ),
            f.query(),
        )
        assertTrue(f.narrowing)
        assertEquals(12, f.count)
    }

    @QFeature("X13")
    @Test fun `no selection sends nothing and the portal defaults to this one`() {
        val f = ExplorerFilters()
        assertTrue(f.query().isEmpty())
        assertFalse(f.active)
        assertTrue(f.includesThis)
        assertFalse(f.includesOther)
        assertEquals("this", f.portalParam())
        assertEquals(setOf("this"), f.picked(ExplorerFilterKey.PORTAL))
    }

    @QFeature("X13")
    @Test fun `labels are kept only for chosen values`() {
        assertEquals(mapOf("c1" to "Bamboo", "c2" to "Clay"), bamboo.labels)
        val fewer = bamboo.with(ExplorerFilterKey.CRAFT, listOf("c2"))
        assertEquals(mapOf("c2" to "Clay"), fewer.labels)
        assertEquals("Craft: Clay", fewer.summary())
    }

    @QFeature("X13")
    @Test fun `a chip removes one value and clear drops everything`() {
        val chip = bamboo.chips().first()
        assertEquals("Craft: Bamboo", chip.label)
        val left = bamboo.without(chip)
        assertEquals(setOf("c2"), left[ExplorerFilterKey.CRAFT])
        val dated = bamboo.dates("2026-03-01", null)
        val dateChip = dated.chips().last()
        assertEquals(ExplorerFilters.DATES, dateChip.key)
        assertNull(dated.without(dateChip).dateFrom)
        assertEquals(ExplorerFilters(), bamboo.cleared())
    }

    @QFeature("X13")
    @Test fun `a reversed range is put in order`() {
        val f = ExplorerFilters().dates("2026-05-10", "2026-05-01")
        assertEquals("2026-05-01", f.dateFrom)
        assertEquals("2026-05-10", f.dateTo)
        assertEquals("Date: 1 May 2026 – 10 May 2026", f.chips().single().label)
    }

    @QFeature("X13")
    @Test fun `the portal choice keeps this, other, or both`() {
        val other = ExplorerFilters().with(ExplorerFilterKey.PORTAL, listOf("other"))
        assertFalse(other.includesThis)
        assertTrue(other.includesOther)
        assertEquals("other", other.portalParam())
        assertTrue(other.active)
        assertFalse(other.narrowing)
        assertTrue(other.query().isEmpty())
        val both = ExplorerFilters().with(ExplorerFilterKey.PORTAL, listOf("this", "other"))
        assertTrue(both.includesThis && both.includesOther)
        assertEquals("this,other", both.portalParam())
        val back = both.with(ExplorerFilterKey.PORTAL, listOf("this"))
        assertTrue(back[ExplorerFilterKey.PORTAL].isEmpty())
        assertTrue(ExplorerFilters().with(ExplorerFilterKey.PORTAL, emptyList()).includesThis)
    }

    @QFeature("X13")
    @Test fun `the filter set survives a saved state round trip`() {
        val f = bamboo.with(ExplorerFilterKey.PORTAL, listOf("this", "other")).dates("2026-01-01", "2026-01-31")
        val text = ExplorerResume.encode(f)
        assertEquals(f, ExplorerResume.decode(text))
        assertEquals(ExplorerFilters(), ExplorerResume.decode("not json"))
        val resume = ExplorerResume("w", ExplorerTab.TABLES, null, f)
        assertEquals(f, resume.filters)
    }

    @QFeature("X13")
    @Test fun `a saved view and a web link carry the same parameters`() {
        val saved = ExplorerViewFilters.toSaved(bamboo.with(ExplorerFilterKey.PORTAL, listOf("other")))
        assertEquals("c1,c2", saved["craftIds"])
        assertEquals("other", saved["portals"])
        val back = ExplorerViewFilters.of(saved, bamboo)
        assertEquals(setOf("c1", "c2"), back[ExplorerFilterKey.CRAFT])
        assertTrue(back.includesOther)
        assertEquals("Bamboo", back.labels["c1"])
        val legacy = ExplorerViewFilters.of(mapOf("status" to "PENDING"), ExplorerFilters())
        assertEquals(setOf("PENDING"), legacy[ExplorerFilterKey.STATUS])
        val link = ExplorerViewLinks.parse("https://portal.example.org/data?path=by-type&craftIds=c1%2Cc2&dateFrom=2026-01-01&portals=other&q=x", "https://portal.example.org")
        assertEquals(mapOf("craftIds" to "c1,c2", "dateFrom" to "2026-01-01", "portals" to "other"), link?.filters)
    }

    @QFeature("X13")
    @Test fun `facets decide which multi-selects are drawn, in the contract order`() {
        val facets = ExplorerFacets(
            facets = listOf(
                ExplorerFacet(facet = "craft", param = "craftIds", label = "Craft", options = listOf(ExplorerFacetOption("c1", "Bamboo", count = 3))),
                ExplorerFacet(facet = "designWorkshop", param = "designWorkshopIds", label = "Design workshop", options = listOf(ExplorerFacetOption("d1", "Kutch 2026", count = 9))),
                ExplorerFacet(facet = "workshop", param = "workshopIds", label = "Workshop", options = listOf(ExplorerFacetOption("w1", "Bagru"))),
            ),
            portal = ExplorerPortalChoices(listOf(ExplorerPortalOption("this", "Design Workshop", true, "ok"), ExplorerPortalOption("other", "Field Repository", true, "ok"))),
        )
        assertEquals(
            listOf(ExplorerFilterKey.PORTAL, ExplorerFilterKey.WORKSHOP, ExplorerFilterKey.DESIGN_WORKSHOP, ExplorerFilterKey.CRAFT),
            ExplorerFacetKeys.keys(facets, ExplorerFilters()),
        )
        assertEquals(listOf(ExplorerFilterOption("d1", "Kutch 2026", 9)), ExplorerFacetKeys.options(facets, ExplorerFilterKey.DESIGN_WORKSHOP))
        assertEquals(listOf("this", "other"), ExplorerFacetKeys.options(facets, ExplorerFilterKey.PORTAL).map { it.id })
        assertEquals("Field Repository", ExplorerFacetKeys.options(facets, ExplorerFilterKey.PORTAL).last().label)
        val noPeer = facets.copy(portal = ExplorerPortalChoices(listOf(ExplorerPortalOption("other", "Field Repository", false, "off"))))
        assertFalse(ExplorerFilterKey.PORTAL in ExplorerFacetKeys.keys(noPeer, ExplorerFilters()))
        assertEquals(listOf("this"), ExplorerFacetKeys.options(noPeer, ExplorerFilterKey.PORTAL).map { it.id })
        val fr = facets.copy(facets = facets.facets.filter { it.facet != "designWorkshop" })
        assertFalse(ExplorerFilterKey.DESIGN_WORKSHOP in ExplorerFacetKeys.keys(fr, ExplorerFilters()))
        assertTrue(ExplorerFilterKey.LANGUAGE in ExplorerFacetKeys.keys(fr, ExplorerFilters().with(ExplorerFilterKey.LANGUAGE, listOf("ben"))))
    }

    @QFeature("X13")
    @Test fun `the empty state names the filters the server applied`() {
        val applied = ExplorerAppliedFilters(
            active = listOf(
                ExplorerActiveFilter("craftIds", "craft", "Craft", listOf(ExplorerActiveValue("c1", "Bamboo"))),
                ExplorerActiveFilter("mediaTypes", "mediaType", "Media type", listOf(ExplorerActiveValue("VIDEO", "Video"))),
            ),
            count = 2,
        )
        assertEquals("Craft: Bamboo, Media type: Video", ExplorerFacetKeys.summary(applied, bamboo))
        assertEquals(mapOf("c1" to "Bamboo", "VIDEO" to "Video"), ExplorerFacetKeys.labels(applied))
        assertEquals("Craft: Bamboo · Craft: Clay", ExplorerFacetKeys.summary(null, bamboo))
    }

    @QFeature("X13")
    @Test fun `a typed search finds options and keeps the chosen ones on top`() {
        val options = listOf(ExplorerFilterOption("a", "Ajrakh"), ExplorerFilterOption("b", "Bamboo"), ExplorerFilterOption("k", "Kantha বাংলা"))
        assertEquals(listOf("b", "a", "k"), ExplorerOptionMatch.ordered(options, setOf("b"), "").map { it.id })
        assertEquals(listOf("k"), ExplorerOptionMatch.ordered(options, emptySet(), "বাংলা").map { it.id })
        assertEquals(listOf("a"), ExplorerOptionMatch.ordered(options, emptySet(), "  ajr ").map { it.id })
    }

    @QFeature("X13")
    @Test fun `a filtered folder download asks for a filtered manifest`() {
        val url = ExplorerPaths.manifest("w/bagru", "videos", mapOf("craftIds" to "c1,c2"))
        assertEquals("/api/data/manifest?stream=1&path=w%2Fbagru&include=videos&craftIds=c1%2Cc2", url)
    }

    @QFeature("X13")
    @Test fun `other-portal rows open by their cross-portal link and page to the last one`() {
        val row = ExplorerPeerRow(portal = "fieldRepository", portalLabel = "Field Repository", type = "artisans", id = "a9", title = "Meena", href = "/remote/artisans/a9")
        assertEquals("artisans" to "a9", row.remote)
        assertEquals("media" to "m1", ExplorerPeerRow(type = "media", id = "m1").remote)
        val first = ExplorerPeerPage(items = listOf(row), total = 2, page = 1, pageSize = 1, pages = 2)
        val merged = ExplorerPeerRows.merge(emptyList(), first)
        assertTrue(ExplorerPeerRows.hasMore(PeerLoad.Ready(merged, first)))
        val second = ExplorerPeerPage(items = listOf(row, row.copy(id = "a10")), total = 2, page = 2, pageSize = 1, pages = 2)
        val all = ExplorerPeerRows.merge(merged, second)
        assertEquals(listOf("a9", "a10"), all.map { it.id })
        assertFalse(ExplorerPeerRows.hasMore(PeerLoad.Ready(all, second)))
        assertEquals("media", ExplorerPeerTypes.browseKindOf("by-type/videos"))
        assertNull(ExplorerPeerTypes.browseKindOf("by-workshop/w1"))
        assertEquals("Nothing in Field Repository can match these filters.", ExplorerPeerTypes.statusLine(ExplorerPeerPage(portalLabel = "Field Repository", status = "filteredOut")))
    }
}
