package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchParityTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val table: JsonObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("placeSearchParity.json")
            ?: error("placeSearchParity.json is missing from app/src/test/resources")
        json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun `the minimum query length matches the web`() {
        assertEquals(table["minQueryLength"]!!.jsonPrimitive.int, PLACE_SEARCH_MIN_QUERY_LENGTH)
    }

    @Test
    fun `every request URL is byte-identical to the web's`() {
        val key = table.str("key")!!
        val mismatches = mutableListOf<String>()
        for (element in table["parse"]!!.jsonArray) {
            val case = element.jsonObject
            val proximity = (case["proximity"] as? JsonObject)?.let {
                it["lon"]!!.jsonPrimitive.double to it["lat"]!!.jsonPrimitive.double
            }
            val actual = placeSearchUrl(case.str("query")!!, key, proximity)
            val expected = case.str("expectedUrl")!!
            if (actual != expected) mismatches += "${case.str("name")}\n  web: $expected\n  kot: $actual"
        }
        assertEquals("URL parity diff:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun `every parsed result matches the web's, hit for hit`() {
        val mismatches = mutableListOf<String>()
        for (element in table["parse"]!!.jsonArray) {
            val case = element.jsonObject
            val name = case.str("name")!!
            val status = case["status"]!!.jsonPrimitive.int
            val notJson = case["notJson"]!!.jsonPrimitive.boolean
            val body = case.str("body")!!

            if (status != 200) {
                val expected = case["expectedError"]!!.jsonObject
                val failure = placeSearchStatusFailure(status)
                if (failure.status != expected["status"]!!.jsonPrimitive.int) {
                    mismatches += "$name: status ${failure.status} vs ${expected["status"]}"
                }
                if (failure.message != expected.str("message")) {
                    mismatches += "$name: message '${failure.message}' vs '${expected.str("message")}'"
                }
                continue
            }

            if (notJson) {
                val thrown = runCatching { parsePlaceSearchResponse(body) }.exceptionOrNull()
                val expected = case["expectedError"]!!.jsonObject
                val failure = thrown as? PlaceSearchException
                if (failure == null) {
                    mismatches += "$name: expected a PlaceSearchException, got $thrown"
                } else {
                    if (failure.status != expected["status"]!!.jsonPrimitive.int) {
                        mismatches += "$name: status ${failure.status} vs ${expected["status"]}"
                    }
                    if (failure.message != expected.str("message")) {
                        mismatches += "$name: message '${failure.message}' vs '${expected.str("message")}'"
                    }
                }
                continue
            }

            val actual = parsePlaceSearchResponse(body)
            val expected = case["expectedHits"]!!.jsonArray
            if (actual.size != expected.size) {
                mismatches += "$name: ${actual.size} hits vs ${expected.size}"
                continue
            }
            expected.forEachIndexed { index, expectedElement ->
                val want = expectedElement.jsonObject
                val got = actual[index]
                val where = "$name[$index]"
                if (got.id != want.str("id")) mismatches += "$where id '${got.id}' vs '${want.str("id")}'"
                if (got.name != want.str("name")) mismatches += "$where name '${got.name}' vs '${want.str("name")}'"
                if (got.context != want.str("context")) {
                    mismatches += "$where context '${got.context}' vs '${want.str("context")}'"
                }
                if (got.lon != want["lon"]!!.jsonPrimitive.double) {
                    mismatches += "$where lon ${got.lon} vs ${want["lon"]}"
                }
                if (got.lat != want["lat"]!!.jsonPrimitive.double) {
                    mismatches += "$where lat ${got.lat} vs ${want["lat"]}"
                }
                val wantBox = want["bbox"] as? JsonArray
                val gotBox = got.bounds
                when {
                    wantBox == null && gotBox != null -> mismatches += "$where bbox $gotBox vs none"
                    wantBox != null && gotBox == null -> mismatches += "$where bbox none vs $wantBox"
                    wantBox != null && gotBox != null -> {
                        val want4 = wantBox.map { it.jsonPrimitive.double }
                        val got4 = listOf(gotBox.west, gotBox.south, gotBox.east, gotBox.north)
                        if (want4 != got4) mismatches += "$where bbox $got4 vs $want4"
                    }
                }
            }
        }
        assertEquals("Hit parity diff:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun `every failure message matches the web's`() {
        val mismatches = mutableListOf<String>()
        for (element in table["failure"]!!.jsonArray) {
            val case = element.jsonObject
            val status = (case["status"] as? JsonPrimitive)?.takeIf { it.content != "null" }?.int
            val actual = describePlaceSearchFailure(PlaceSearchException(status, "x"))
            val expected = case.str("expectedMessage")!!
            if (actual != expected) mismatches += "${case.str("name")}\n  web: $expected\n  kot: $actual"
        }
        assertEquals("Failure parity diff:\n" + mismatches.joinToString("\n"), 0, mismatches.size)
    }

    @Test
    fun `an unwrapped IO failure reads as no connection`() {
        val expected = table["failure"]!!.jsonArray
            .map { it.jsonObject }
            .first { it.str("name") == "no-response" }
            .str("expectedMessage")!!
        assertEquals(expected, describePlaceSearchFailure(java.io.IOException("socket closed")))
        assertEquals(expected, describePlaceSearchFailure(IllegalStateException("something else entirely")))
    }

    @Test
    fun `an area is framed and a village point is not`() {
        val area = PlaceHit(
            id = "subregion.977",
            name = "Bargarh",
            context = "Odisha, India",
            lon = 83.61904889345169,
            lat = 21.333500594158206,
            bounds = PlaceBounds(82.63642966747284, 20.722963153925253, 83.91022648662329, 21.74283862190238)
        )
        assertEquals(
            PlaceCamera.Frame(
                south = 20.722963153925253,
                west = 82.63642966747284,
                north = 21.74283862190238,
                east = 83.91022648662329
            ),
            cameraForPlace(area)
        )

        val village = PlaceHit(
            id = "place.2592481",
            name = "Barpali",
            context = "Paikmal, Odisha, India",
            lon = 82.74589028209448,
            lat = 20.89405824525621,
            bounds = PlaceBounds(82.74589028209448, 20.89405824525621, 82.74589028209448, 20.89405824525621)
        )
        assertEquals(
            PlaceCamera.Point(lat = 20.89405824525621, lon = 82.74589028209448, zoom = PLACE_POINT_ZOOM),
            cameraForPlace(village)
        )

        val noBox = village.copy(bounds = null)
        assertEquals(cameraForPlace(village), cameraForPlace(noBox))
    }

    @Test
    fun `the Leaflet script puts latitude first and never touches the marker`() {
        val frame = leafletCameraScript(
            PlaceCamera.Frame(south = 20.722963153925253, west = 82.63642966747284, north = 21.74283862190238, east = 83.91022648662329)
        )
        assertEquals(
            "if(window.__map){window.__map.fitBounds(" +
                "[[20.722963153925253,82.63642966747284],[21.74283862190238,83.91022648662329]]," +
                "{maxZoom:14,padding:[24,24]});}",
            frame
        )

        val point = leafletCameraScript(PlaceCamera.Point(lat = 20.89405824525621, lon = 82.74589028209448, zoom = 13))
        assertEquals(
            "if(window.__map){window.__map.setView([20.89405824525621,82.74589028209448],13);}",
            point
        )

        for (script in listOf(frame, point)) {
            assertTrue(script, !script.contains("setLatLng"))
            assertTrue(script, !script.contains("AndroidMapBridge"))
            assertTrue(script, !script.contains("onPick"))
        }
    }

    @Test
    fun `a blank key is unavailable and refuses loudly`() {
        assertTrue(placeSearchAvailable("OJJYFRqCD2HD2k3BbXGF"))
        assertTrue(!placeSearchAvailable(""))
        assertTrue(!placeSearchAvailable("   "))

        val thrown = runCatching {
            kotlinx.coroutines.runBlocking { searchPlaces("Barpali", "", null) }
        }.exceptionOrNull()
        assertTrue("expected a PlaceSearchException, got $thrown", thrown is PlaceSearchException)
        assertEquals(503, (thrown as PlaceSearchException).status)
    }
}
