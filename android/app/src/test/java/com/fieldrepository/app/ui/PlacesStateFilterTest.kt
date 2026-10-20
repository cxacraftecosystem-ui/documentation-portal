package com.fieldrepository.app.ui

import com.fieldrepository.app.data.MapPointChildDto
import com.fieldrepository.app.data.MapPointDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Places map's State filter: which states it offers, and which places it keeps. */
class PlacesStateFilterTest {
    private val nation = MapPointDto(
        key = "IN",
        label = "India",
        children = listOf(
            MapPointChildDto(key = "RJ", level = "STATE", label = "Rajasthan"),
            MapPointChildDto(key = "GJ", level = "STATE", label = "Gujarat", state = "Gujarat"),
        ),
    )
    private val bagru = MapPointDto(key = "a", label = "Jaipur", state = "Rajasthan")
    private val bhuj = MapPointDto(key = "b", label = "Kachchh", state = "Gujarat")
    private val udaipur = MapPointDto(key = "c", label = "Udaipur", state = "Rajasthan")

    @Test
    fun `the states come from the places and the children of the country, A to Z, once each`() {
        assertEquals(listOf("Gujarat", "Rajasthan"), placesStateNames(listOf(nation)))
        assertEquals(listOf("Gujarat", "Rajasthan"), placesStateNames(listOf(bagru, bhuj, udaipur)))
    }

    @Test
    fun `a state keeps its own places, and empty keeps them all`() {
        val all = listOf(bagru, bhuj, udaipur)
        assertEquals(listOf(bagru, udaipur), placesInState(all, "Rajasthan"))
        assertEquals(all, placesInState(all, ""))
        // The country point has no state and stays, so the country level still draws its dot.
        assertEquals(listOf(nation), placesInState(listOf(nation), "Gujarat"))
    }
}
