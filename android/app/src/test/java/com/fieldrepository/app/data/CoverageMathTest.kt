package com.fieldrepository.app.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The coverage choropleth's arithmetic against `coverage-parity.json` — the same fixture, byte for
 * byte, that the web's `e2e/coverage-parity-unit.spec.ts` reads in both portals. Same breaks, same
 * bins, same polygon fills: the phone and the web cannot shade one district differently.
 */
class CoverageMathTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(): JsonObject {
        val text = javaClass.classLoader!!.getResource("coverage-parity.json")!!.readText()
        return json.parseToJsonElement(text).jsonObject
    }

    private fun ints(array: JsonArray): List<Int> = array.map { it.jsonPrimitive.int }

    @Test
    fun breaksAndBinsMatchTheSharedFixture() {
        val rows = fixture()["quantile"]!!.jsonArray
        check(rows.isNotEmpty())
        for (row in rows) {
            val case = row.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val values = ints(case["values"]!!.jsonArray)
            val breaks = quantileBreaks(values)
            assertEquals(name, ints(case["breaks"]!!.jsonArray), breaks)
            assertEquals(name, ints(case["bins"]!!.jsonArray), values.map { binOf(it, breaks) })
        }
    }

    @Test
    fun fillsMatchTheSharedFixture() {
        val rows = fixture()["fills"]!!.jsonArray
        check(rows.isNotEmpty())
        for (row in rows) {
            val case = row.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val units = json.decodeFromJsonElement(ListSerializer(CoverageUnit.serializer()), case["units"]!!)
            val shapes = case["shapes"]!!.jsonArray.map {
                CoverageShapeKey(it.jsonObject["lgd"]!!.jsonPrimitive.int, it.jsonObject["stateLgd"]!!.jsonPrimitive.int)
            }
            val got = fillsFor(
                units,
                case["level"]!!.jsonPrimitive.content,
                case["metric"]!!.jsonPrimitive.content,
                shapes
            ).toSortedMap()
            val want = case["fills"]!!.jsonArray.associate {
                val o = it.jsonObject
                o["lgd"]!!.jsonPrimitive.int to CoverageFill(
                    o["value"]!!.jsonPrimitive.int,
                    o["hostsChild"]!!.jsonPrimitive.boolean
                )
            }.toSortedMap()
            assertEquals(name, want, got)
        }
    }

    @Test
    fun legendLabelsAndTheMetricVocabulary() {
        assertEquals(listOf("1–2", "3–4", "5–6", "7–8", "9–10"), binLabels(listOf(2, 4, 6, 8, 10)))
        assertEquals(listOf("1"), binLabels(listOf(1)))
        assertEquals(CoverageMetric.ALL, CoverageMetric.normalise("records"))
        assertEquals(CoverageMetric.WORKSHOPS, CoverageMetric.normalise("crafts"))
        val counts = CoverageCounts(
            workshops = 1, artisans = 4, crafts = 9, products = 2, tools = 1, media = 3,
            designWorkshops = 2, plannedWorkshops = 7
        )
        assertEquals(3, coverageMetricValue(counts, CoverageMetric.WORKSHOPS))
        assertEquals(4, coverageMetricValue(counts, CoverageMetric.ARTISANS))
        assertEquals(3 + 4 + 2 + 1 + 3, coverageMetricValue(counts, CoverageMetric.ALL))
    }

    @Test
    fun crowdedMarkersFoldAtTheirAveragedPosition() {
        val at = mapOf("a" to (10f to 10f), "b" to (14f to 12f), "c" to (200f to 200f))
        val markers = listOf(CoverageMarker(id = "a"), CoverageMarker(id = "b"), CoverageMarker(id = "c"))
        val clusters = clusterMarkers(markers, 22f) { at.getValue(it.id) }
        assertEquals(2, clusters.size)
        assertEquals(12f, clusters[0].x, 1e-4f)
        assertEquals(11f, clusters[0].y, 1e-4f)
        assertEquals(listOf("a", "b"), clusters[0].markers.map { it.id })
    }

    @Test
    fun theQueryStringIsTheWebs() {
        val query = CoverageQuery(
            level = "STATE", craftCode = listOf("ajrakh", "bagru"), fy = listOf(2025), stateLgd = 8, peer = true
        )
        assertEquals(
            mapOf("level" to "STATE", "craftCode" to "ajrakh,bagru", "fy" to "2025", "stateLgd" to "8", "peer" to "1"),
            query.params()
        )
        assertEquals(
            mapOf("level" to "STATE", "craftCode" to "ajrakh,bagru", "fy" to "2025"),
            query.params(forUnit = true)
        )
        assertEquals(emptyMap<String, String>(), CoverageQuery().params())
    }
}
