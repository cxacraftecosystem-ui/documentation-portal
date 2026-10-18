package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE HANDSET'S STANDARD VALUES, PROVED EQUAL TO THE SERVER'S AND THE BROWSER'S.
 *
 * [StandardValues] writes the lists out; this holds them to `backend/app/data/standard_values.json`
 * and runs `shared/standard-values-vectors.json`, the table the server and the web run too.
 */
class StandardValuesParityTest {
    private fun file(rel: String): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, rel)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$rel was not found above ${java.io.File("").absolutePath}")
    }

    private fun json(rel: String): JsonObject = Json.parseToJsonElement(file(rel).readText()).jsonObject

    private fun JsonElement?.text(): String? = if (this == null || this is JsonNull) null else jsonPrimitive.contentOrNull

    private fun entries(list: JsonElement?, labelKey: String): List<StandardValues.Entry> =
        list!!.jsonArray.map { element ->
            val row = element.jsonObject
            StandardValues.Entry(
                row["code"].text()!!,
                row[labelKey].text()!!,
                row["aliases"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }

    @Test
    fun theListsAreTheServersFile() {
        val values = json("backend/app/data/standard_values.json")
        assertEquals(entries(values["languages"], "name"), StandardValues.LANGUAGES)
        assertEquals(entries(values["genders"], "label"), StandardValues.GENDERS)
        assertEquals(entries(values["traditionTypes"], "label"), StandardValues.TRADITION_TYPES)
        assertEquals(entries(values["materialFamilies"], "label"), StandardValues.MATERIAL_FAMILIES)
        val phone = values["phone"]!!.jsonObject
        assertEquals(phone["defaultCountryCode"].text(), StandardValues.DEFAULT_COUNTRY_CODE)
        val india = phone["nationalDigits"]!!.jsonObject["+91"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
        assertEquals(india, StandardValues.NATIONAL_DIGITS.getValue("+91").toList())
        val other = phone["otherNationalDigits"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
        assertEquals(other, StandardValues.OTHER_NATIONAL_DIGITS.toList())
        assertEquals(phone["e164MaxDigits"].text()!!.toInt(), StandardValues.E164_MAX_DIGITS)
    }

    @Test
    fun phoneVectors() {
        val rows = json("shared/standard-values-vectors.json")["phone"]!!.jsonArray
        assertTrue(rows.size >= 25)
        for (element in rows) {
            val row = element.jsonObject
            val result = StandardValues.normalizePhone(row["countryCode"].text(), row["number"].text())
            val error = row["error"].text()
            if (error != null) {
                assertEquals(row.toString(), error, result.error)
                continue
            }
            assertEquals(row.toString(), StandardValues.PhoneResult(row["code"].text(), row["digits"].text()), result)
            assertEquals(row.toString(), row["display"].text(), StandardValues.formatPhone(result.code, result.digits))
        }
    }

    @Test
    fun listVectors() {
        val vectors = json("shared/standard-values-vectors.json")
        val sections: List<Pair<String, (String?) -> StandardValues.Normalised>> = listOf(
            "language" to StandardValues::normalizeLanguage,
            "gender" to StandardValues::normalizeGender,
            "traditionType" to StandardValues::normalizeTradition,
            "materialFamily" to StandardValues::normalizeMaterialFamily,
        )
        for ((section, normalise) in sections) {
            val rows = vectors[section]!!.jsonArray
            assertTrue(section, rows.isNotEmpty())
            for (element in rows) {
                val row = element.jsonObject
                val result = normalise(row["input"].text())
                val error = row["error"].text()
                if (error != null) assertEquals(row.toString(), error, result.error)
                else assertEquals(row.toString(), StandardValues.Normalised(row["value"].text()), result)
            }
        }
        for (element in vectors["materialFamilyFromText"]!!.jsonArray) {
            val row = element.jsonObject
            assertEquals(row.toString(), row["value"].text(), StandardValues.materialFamilyFromText(row["input"].text()))
        }
        for (element in vectors["languageName"]!!.jsonArray) {
            val row = element.jsonObject
            assertEquals(row.toString(), row["value"].text(), StandardValues.languageName(row["input"].text()))
        }
    }

    @Test
    fun editingComposesTheTwoColumnsAndKeepsALegacyValue() {
        assertEquals("+91 9876543210", StandardValues.phoneForEditing("+91", "9876543210"))
        assertEquals("+91 9876543210", StandardValues.phoneForEditing(null, "+91 9876543210"))
        assertEquals("9876543210", StandardValues.phoneForEditing(null, "9876543210"))
        assertEquals("", StandardValues.phoneForEditing("+91", null))
        assertEquals("Kutchi" to "Kutchi", StandardValues.languageOptions("Kutchi").first())
        assertEquals("hi" to "Hindi", StandardValues.languageOptions(null).first())
    }
}
