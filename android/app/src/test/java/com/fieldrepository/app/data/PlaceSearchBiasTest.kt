package com.fieldrepository.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceSearchBiasTest {

    private val odisha = 83.6 to 21.3

    @Test
    fun `a real Indian map centre is sent unchanged`() {
        assertEquals(odisha, placeSearchBias(odisha))
        assertEquals(68.1 to 23.7, placeSearchBias(68.1 to 23.7))
        assertEquals(97.4 to 28.2, placeSearchBias(97.4 to 28.2))
        assertEquals(77.2 to 8.1, placeSearchBias(77.2 to 8.1))
        assertEquals(74.9 to 37.0, placeSearchBias(74.9 to 37.0))
    }

    @Test
    fun `no centre at all is not an error`() {
        assertNull(placeSearchBias(null))
    }

    @Test
    fun `a non-finite centre is dropped`() {
        assertNull(placeSearchBias(Double.NaN to 21.3))
        assertNull(placeSearchBias(83.6 to Double.NaN))
        assertNull(placeSearchBias(Double.NaN to Double.NaN))
        assertNull(placeSearchBias(Double.POSITIVE_INFINITY to 21.3))
        assertNull(placeSearchBias(83.6 to Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `a swapped pair is not silently accepted`() {
        assertEquals(100.0 to 0.0, placeSearchBias(100.0 to 0.0))
        assertNull(placeSearchBias(0.0 to 100.0))
    }

    @Test
    fun `an out of range centre is dropped`() {
        assertNull(placeSearchBias(180.1 to 21.3))
        assertNull(placeSearchBias(-180.1 to 21.3))
        assertNull(placeSearchBias(83.6 to 90.1))
        assertNull(placeSearchBias(83.6 to -90.1))
        assertEquals(180.0 to 90.0, placeSearchBias(180.0 to 90.0))
        assertEquals(-180.0 to -90.0, placeSearchBias(-180.0 to -90.0))
    }

    @Test
    fun `a dropped bias leaves no proximity parameter in the URL`() {
        val url = placeSearchUrl("Barpali", "TESTKEY", placeSearchBias(Double.NaN to 21.3))
        assertEquals(
            "https://api.maptiler.com/geocoding/Barpali.json" +
                "?key=TESTKEY&country=in&language=en&limit=8&autocomplete=true",
            url
        )

        val biased = placeSearchUrl("Barpali", "TESTKEY", placeSearchBias(odisha))
        assertEquals(
            "https://api.maptiler.com/geocoding/Barpali.json" +
                "?key=TESTKEY&country=in&language=en&limit=8&autocomplete=true&proximity=83.6%2C21.3",
            biased
        )
    }
}
