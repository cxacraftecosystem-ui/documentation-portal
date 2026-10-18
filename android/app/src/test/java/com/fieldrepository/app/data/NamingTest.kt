package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handset's half of the naming convention, held to `shared/naming-golden.json`: the same table
 * `naming.py` and `naming.ts` answer to, byte-identical in both portals. Found by walking up from
 * the module, like `DwTextFormatParityTest`'s table.
 */
class NamingTest {
    private fun golden(): JsonObject {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "shared/naming-golden.json")
            if (candidate.isFile) return Json.parseToJsonElement(candidate.readText(Charsets.UTF_8)).jsonObject
            dir = dir.parentFile
        }
        error("shared/naming-golden.json was not found above ${java.io.File("").absolutePath}")
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonElement.text(): String = jsonPrimitive.content

    @Test
    fun theTableIsPresent() {
        val g = golden()
        assertEquals(1, g["version"]!!.jsonPrimitive.intOrNull)
        assertTrue(g["fileNames"]!!.jsonArray.size > 30)
        assertTrue(g["titles"]!!.jsonArray.size > 8)
    }

    @Test
    fun fileNamesMatchTheGoldenTable() {
        for (row in golden()["fileNames"]!!.jsonArray) {
            val input = row.jsonObject["input"]!!.jsonObject
            val got = Naming.fileName(
                entity = input.str("entity"),
                what = input.str("what"),
                ext = input.str("ext").orEmpty(),
                portal = input.str("portal"),
                date = input.str("date"),
                time = input.bool("time"),
                period = input.str("period"),
                place = input.str("place"),
                craft = input.str("craft"),
                subject = input.str("subject"),
                sequence = input.str("sequence"),
                n = input.int("n"),
            )
            assertEquals(input.toString(), row.jsonObject["name"]!!.text(), got)
        }
    }

    @Test
    fun titlesMatchTheGoldenTable() {
        for (row in golden()["titles"]!!.jsonArray) {
            val input = row.jsonObject["input"]!!.jsonObject
            val people = (input["participants"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { p ->
                if (p is JsonObject) Naming.Participant(p.str("name"), p.str("romanisedName")) else Naming.Participant(p.text())
            }
            val got = Naming.sessionTitle(
                participants = people,
                participantCount = input.int("participantCount"),
                date = input.str("date"),
                place = input.str("place"),
                craft = input.str("craft"),
                entity = input.str("entity"),
                subject = input.str("subject"),
            )
            assertEquals(input.toString(), row.jsonObject["title"]!!.text(), got)
        }
    }

    @Test
    fun fieldsPlacesAndSequencesMatchTheGoldenTable() {
        val g = golden()
        for (key in listOf("transliteration", "fields")) {
            for (row in g[key]!!.jsonArray) {
                val o = row.jsonObject
                assertEquals(o["input"]!!.text(), o["field"]!!.text(), Naming.field(o["input"]!!.text()))
            }
        }
        for (row in g["places"]!!.jsonArray) {
            val i = row.jsonObject["input"]!!.jsonObject
            assertEquals(i.toString(), row.jsonObject["place"]!!.text(), Naming.placeField(i.str("locality"), i.str("district"), i.str("state")))
        }
        for (row in g["sequences"]!!.jsonArray) {
            val i = row.jsonObject["input"]!!.jsonObject
            val got = if ("question" in i) {
                Naming.questionSequence(i.str("section"), i.str("question"), take = i.int("take"), part = i.int("part"))
            } else {
                Naming.sectionSequence(i.str("section"))
            }
            assertEquals(i.toString(), row.jsonObject["sequence"]!!.text(), got)
        }
    }

    @Test
    fun leavesAndCollisionsMatchTheGoldenTable() {
        val g = golden()
        for (row in g["asciiLeaves"]!!.jsonArray) {
            val o = row.jsonObject
            assertEquals(o["input"]!!.text(), o["leaf"]!!.text(), Naming.asciiLeaf(o["input"]!!.text()))
        }
        for (row in g["dedupe"]!!.jsonArray) {
            val o = row.jsonObject
            assertEquals(o["names"]!!.jsonArray.map { it.text() }, Naming.dedupeNames(o["input"]!!.jsonArray.map { it.text() }))
        }
    }

    @Test
    fun workshopNamesMatchTheGoldenTable() {
        for (row in golden()["workshops"]!!.jsonArray) {
            val i = row.jsonObject["input"]!!.jsonObject
            val w = i["workshop"]!!.jsonObject
            val got = Naming.workshopFilename(
                workshopCode = w.str("workshopCode"),
                title = w.str("title"),
                clusterName = w.str("clusterName"),
                district = w.str("district"),
                state = w.str("state"),
                craftName = w.str("craftName"),
                what = i.str("what"),
                ext = i.str("ext").orEmpty(),
                at = i.str("at"),
                portal = "DesignWorkshop",
            )
            assertEquals(i.toString(), row.jsonObject["name"]!!.text(), got)
        }
    }

    @Test
    fun aLeafIsAlwaysAnAsciiS3SafeName() {
        for (raw in listOf("गिरिराज प्रसाद.m4a", "a/b\\c.JPG", "  ", "NUL", "x".repeat(400))) {
            assertTrue(raw, Regex("^[A-Za-z0-9._-]{1,150}$").matches(Naming.asciiLeaf(raw)))
        }
    }
}
