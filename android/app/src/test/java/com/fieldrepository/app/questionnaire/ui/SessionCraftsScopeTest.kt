package com.fieldrepository.app.questionnaire.ui

import com.fieldrepository.app.questionnaire.data.QSessionLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCraftsScopeTest {
    @Test fun `two artisans with different crafts give both, a hand-set craft stays when an artisan leaves`() {
        assertEquals(listOf("Hand Block Printing", "Dokra"), craftsFor(listOf("Hand Block Printing", "Dokra", " hand block printing "), emptyList()))
        assertEquals(listOf("Hand Block Printing", "Weaving"), craftsFor(listOf("Hand Block Printing"), listOf("Weaving")))
        assertEquals(listOf("Weaving"), craftsFor(emptyList(), listOf("Weaving")))
        assertEquals(emptyList<String>(), craftsFor(listOf(null, ""), emptyList()))
    }

    @Test fun `the artisan picker offers all records only once a workshop is chosen`() {
        assertNull(scopeOption(workshopChosen = false, showAll = false))
        assertEquals(ALL_ARTISANS, scopeOption(workshopChosen = true, showAll = false)!!.value)
        assertEquals("Show from all records", scopeOption(workshopChosen = true, showAll = false)!!.label)
        assertEquals(WORKSHOP_ARTISANS, scopeOption(workshopChosen = true, showAll = true)!!.value)
    }

    @Test fun `editing sends the crafts and keeps hand-set ones apart`() {
        val before = SessionEdit.of("2026-11-09", null, "Bagru", null, null, null, QSessionLocation(26.8, 75.5), listOf("Hand Block Printing", "Weaving"), listOf("Weaving"))
        assertNull(before.patch(2, before).crafts)
        val added = before.copy(crafts = listOf("Hand Block Printing", "Weaving", "Dokra")).patch(2, before)
        assertEquals(listOf("Hand Block Printing", "Weaving", "Dokra"), added.crafts)
        assertEquals(listOf("Weaving", "Dokra"), added.craftsByHand)
    }
}
