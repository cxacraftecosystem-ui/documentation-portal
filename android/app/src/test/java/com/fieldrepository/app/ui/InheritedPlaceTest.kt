package com.fieldrepository.app.ui

import com.fieldrepository.app.data.LocationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C17: a child record's stated address is filled from its artisan, marked, and never overwrites. */
class InheritedPlaceTest {

    private val ramesh = InheritedPlace(
        fromName = "Ramesh Chhipa",
        sourceId = "a1",
        state = "Rajasthan",
        district = "Jaipur",
        village = "Bagru"
    )

    @Test
    fun `empty boxes take the artisan's state, district and village`() {
        val result = inheritStatedAddress("", "", "", ramesh)
        assertEquals("Rajasthan", result.state)
        assertEquals("Jaipur", result.district)
        assertEquals("Bagru", result.village)
        assertEquals(listOf("state", "district", "village"), result.filled)
        assertEquals("state, district and village", inheritedFieldWords(result.filled))
    }

    @Test
    fun `a box somebody already answered is never replaced`() {
        val result = inheritStatedAddress("Gujarat", "", "Bhuj", ramesh)
        assertEquals("Gujarat", result.state)
        // The artisan's district belongs to another state, so it is not put under this one.
        assertEquals("", result.district)
        assertEquals("Bhuj", result.village)
        assertTrue(result.filled.isEmpty())
    }

    @Test
    fun `the district is taken when the state already agrees`() {
        val result = inheritStatedAddress("rajasthan", "", "", ramesh)
        assertEquals("Jaipur", result.district)
        assertEquals(listOf("district", "village"), result.filled)
        assertEquals("district and village", inheritedFieldWords(result.filled))
    }

    @Test
    fun `the requirement message names the heading the form draws, not an artisan it may not have`() {
        val noState = LocationRequest(latitude = 22.3, longitude = 87.3)
        assertEquals(
            "Choose the state under 'Workshop location' before saving.",
            artisanLocationRequirementError(noState, "Workshop location")
        )
        assertEquals(
            "Choose the district under 'Artisan location' before saving.",
            artisanLocationRequirementError(noState.copy(state = "Rajasthan"))
        )
    }
}
