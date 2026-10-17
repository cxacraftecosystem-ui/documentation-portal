package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE HANDSET'S HALF OF THE ARTISAN FORM CONTRACT (2026-10-17), against the one shared table.
 *
 * `shared/artisan-form-contract.json` is byte-identical in the designer portal and the documentation
 * portal and is read unmodified here, by the server's `test_artisan_form_contract.py` and by the web's
 * `artisan-form-contract-unit.spec.ts`. The server's test also reads this app's artisan form for the
 * labels, their order and the retired words; this test proves the sentence and the month rows.
 */
class ArtisanFormContractTest {

    private fun contract() = Json.parseToJsonElement(contractFile().readText()).jsonObject

    private fun contractFile(): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "shared/artisan-form-contract.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("shared/artisan-form-contract.json was not found above ${java.io.File("").absolutePath}")
    }

    @Test
    fun `the contract value is the one the server requires both dates from`() {
        assertEquals(contract()["datesRequiredFrom"]!!.jsonPrimitive.int, ARTISAN_FORM_CONTRACT)
    }

    @Test
    fun `the missing-dates sentence matches every shared vector`() {
        val vectors = contract()["refusalVectors"]!!.jsonArray
        assertTrue("the vector table is empty", vectors.size >= 4)
        for (element in vectors) {
            val row = element.jsonObject
            fun text(key: String): String? = row[key].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.content }
            assertEquals(
                row.toString(),
                text("sentence"),
                missingArtisanDatesSentence(text("dateOfBirth"), text("craftStartDate")),
            )
        }
    }

    @Test
    fun `the months box offers 0 to 11 under the shared words`() {
        val months = contract()["experience"]!!.jsonObject["months"]!!.jsonObject
        assertEquals(months["empty"]!!.jsonPrimitive.content, EXPERIENCE_NOT_STATED)
        assertEquals((months["min"]!!.jsonPrimitive.int..months["max"]!!.jsonPrimitive.int).toList(), EXPERIENCE_MONTH_OPTIONS)
        for (pair in months["optionVectors"]!!.jsonArray) {
            val (value, label) = pair.jsonArray
            assertEquals(label.jsonPrimitive.content, experienceMonthsLabel(value.jsonPrimitive.int))
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun `an old outbox entry decodes without the key and is sent as the old shape`() {
        val old = """{"name":"Asha","place":"Bagru","aadhaarNumber":"234567890124","dos":"a","donts":"b"}"""
        val decoded = Json { ignoreUnknownKeys = true; encodeDefaults = true }.decodeFromString(ArtisanCreateRequest.serializer(), old)
        assertNull("an entry queued before the contract must not gain it on replay", decoded.formContract)
        val wire = Json { explicitNulls = false }.encodeToString(ArtisanCreateRequest.serializer(), decoded)
        assertTrue(wire, "formContract" !in wire)
    }
}
