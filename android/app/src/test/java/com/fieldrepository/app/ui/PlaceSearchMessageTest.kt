package com.fieldrepository.app.ui

import com.fieldrepository.app.data.PLACE_SEARCH_MIN_QUERY_LENGTH
import com.fieldrepository.app.data.PlaceHit
import com.fieldrepository.app.data.PlaceSearchException
import com.fieldrepository.app.data.describePlaceSearchFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchMessageTest {

    private val hit = PlaceHit(
        id = "place.2592481",
        name = "Barpali",
        context = "Paikmal, Odisha, India",
        lon = 82.74589028209448,
        lat = 20.89405824525621
    )

    private fun line(
        state: PlaceSearchState,
        query: String = "Barpali",
        hits: List<PlaceHit> = emptyList(),
        problem: String? = null
    ) = placeSearchStatusLine(state, query, hits, problem)

    private val offline = describePlaceSearchFailure(PlaceSearchException(null, "x"))
    private val serviceDown = describePlaceSearchFailure(PlaceSearchException(500, "x"))
    private val refused = describePlaceSearchFailure(PlaceSearchException(403, "x"))

    @Test
    fun `no results is its own sentence and not any of the three failures`() {
        val empty = line(PlaceSearchState.Done)

        assertNotEquals("no results must not be worded as the offline failure", offline, empty)
        assertNotEquals("no results must not be worded as the service failure", serviceDown, empty)
        assertNotEquals("no results must not be worded as the refusal", refused, empty)

        assertTrue(empty, !empty.contains("connection", ignoreCase = true))
        assertTrue(empty, !empty.contains("key", ignoreCase = true))
        assertTrue(empty, !empty.contains("Try again", ignoreCase = true))
    }

    @Test
    fun `no results names the query it searched for`() {
        assertTrue(line(PlaceSearchState.Done, query = "Barpalii").contains("Barpalii"))
        assertTrue(line(PlaceSearchState.Done, query = "  Bagru  ").contains("“Bagru”"))
    }

    @Test
    fun `each failure bucket is passed through unaltered and the three stay distinct`() {
        assertEquals(offline, line(PlaceSearchState.Failed, problem = offline))
        assertEquals(serviceDown, line(PlaceSearchState.Failed, problem = serviceDown))
        assertEquals(refused, line(PlaceSearchState.Failed, problem = refused))

        assertEquals(
            "the three failure buckets must be three different sentences",
            3,
            setOf(offline, serviceDown, refused).size
        )
    }

    @Test
    fun `a failure with no description still produces a sentence`() {
        val fallback = line(PlaceSearchState.Failed, problem = null)
        assertTrue(fallback, fallback.isNotBlank())
    }

    @Test
    fun `too short says how many letters are needed`() {
        val short = line(PlaceSearchState.Short, query = "Ba")
        assertTrue(short, short.contains(PLACE_SEARCH_MIN_QUERY_LENGTH.toString()))
        assertNotEquals(offline, short)
    }

    @Test
    fun `idle is silent`() {
        assertEquals("", line(PlaceSearchState.Idle, query = ""))
    }

    @Test
    fun `every outcome is distinguishable from every other`() {
        val lines = listOf(
            line(PlaceSearchState.Idle, query = ""),
            line(PlaceSearchState.Short, query = "Ba"),
            line(PlaceSearchState.Searching),
            line(PlaceSearchState.Done),
            line(PlaceSearchState.Done, hits = listOf(hit)),
            line(PlaceSearchState.Failed, problem = offline),
            line(PlaceSearchState.Failed, problem = serviceDown),
            line(PlaceSearchState.Failed, problem = refused)
        )
        assertEquals("two outcomes are wearing the same sentence: $lines", lines.size, lines.toSet().size)
    }

    @Test
    fun `the found count is singular for one hit`() {
        assertTrue(line(PlaceSearchState.Done, hits = listOf(hit)).contains("1 place found"))
        assertTrue(line(PlaceSearchState.Done, hits = listOf(hit, hit)).contains("2 places found"))
    }
}
