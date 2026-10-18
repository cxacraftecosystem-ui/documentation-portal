package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The handset's half of the naming convention, held to the same two tables `naming.py` and
 * `naming.ts` answer to, byte-identical in both portals: `shared/session-title.json` (the frozen
 * questionnaire contract, DESIGN §9 test 60) and `shared/naming-golden.json` (every other generated
 * name). Found by walking up from the module, like `DwTextFormatParityTest`'s table.
 */
class NamingTest {
    private fun table(name: String): JsonObject {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "shared/$name")
            if (candidate.isFile) return Json.parseToJsonElement(candidate.readText(Charsets.UTF_8)).jsonObject
            dir = dir.parentFile
        }
        error("shared/$name was not found above ${java.io.File("").absolutePath}")
    }

    private fun golden(): JsonObject = table("naming-golden.json")

    private fun contract(kind: String): List<JsonObject> =
        table("session-title.json")["golden"]!!.jsonArray.map { it.jsonObject }.filter { it.str("kind") == kind }

    /** The contract's rows are written for documentation-portal; designer-portal's word replaces it. */
    private fun here(name: String): String = name.replaceFirst("FieldRepository_", "${Naming.PORTAL}_")

    private fun session(o: JsonObject): Naming.SessionFields {
        val ws = o["workshop"] as? JsonObject
        val people = (o["participants"] as? JsonArray).orEmpty().map { it.jsonObject }
        return Naming.SessionFields(
            sessionDate = o.str("sessionDate")?.let { LocalDate.parse(it.take(10)) },
            place = o.str("place"),
            workshopLocality = ws?.str("locality"),
            workshopDistrict = ws?.str("district"),
            workshopState = ws?.str("state"),
            workshopCraft = ws?.str("craft"),
            participantCrafts = people.map { it.str("craft") },
            participantNames = people.map { it.str("name") ?: it.str("localName").orEmpty() },
            participantLocalNames = people.map { it.str("localName") },
            entity = o.str("entity"),
            subject = o.str("subject"),
        )
    }

    @Test
    fun contractTitlesMatch() {
        for (row in contract("title")) {
            assertEquals(row.str("case"), row.str("title"), Naming.sessionTitle(session(row["input"]!!.jsonObject)))
        }
    }

    @Test
    fun contractRecordingNamesMatch() {
        for (row in contract("recording")) {
            val i = row["input"]!!.jsonObject
            val targets = i["targets"]!!.jsonArray.map { t ->
                Naming.NameTarget(t.jsonObject.str("sectionCode"), t.jsonObject.str("displayCode"))
            }
            val got = Naming.recordingFileName(
                Naming.RecordingFields(
                    session = session(i["session"]!!.jsonObject),
                    capturedAt = Instant.parse(i.str("capturedAt")),
                    capturedAtSource = i.str("capturedAtSource") ?: "DEVICE",
                    targets = targets,
                    position = i.int("position") ?: 1,
                    rosterCount = i.int("rosterCount") ?: 1,
                    extension = i.str("ext") ?: "m4a",
                    what = i.str("what") ?: "Recording",
                    part = i.int("part"),
                )
            )
            assertEquals(row.str("case"), here(row.str("fileName")!!), got)
            assertTrue(got, Regex("^[A-Za-z0-9._-]{1,150}$").matches(got))
        }
    }

    @Test
    fun contractTransliterationExportsAndCollisionsMatch() {
        for (row in contract("transliteration")) {
            assertEquals(row.str("case"), row.str("ascii"), Naming.fieldWords(row.str("input"), true).joinToString("-"))
        }
        for (row in contract("export")) {
            val i = row["input"]!!.jsonObject
            val got = Naming.exportFilename(i.str("entity"), i.str("what"), i.str("ext")!!, day = i.str("day"), subject = i.str("subject"))
            assertEquals(row.str("case"), here(row.str("fileName")!!), got)
        }
        for (row in contract("collision")) {
            assertEquals(row["names"]!!.jsonArray.map { it.text() }, Naming.dedupeNames(row["input"]!!.jsonArray.map { it.text() }))
        }
    }

    @Test
    fun anEstimatedTimeIsLeftOut() {
        val meena = Naming.SessionFields(LocalDate.of(2026, 7, 1), participantNames = listOf("Meena Pal"))
        for (source in Naming.ESTIMATED_SOURCES) {
            assertEquals(
                "${Naming.PORTAL}_Interview_2026-07-01_Meena-Pal_Recording.m4a",
                Naming.recordingFileName(Naming.RecordingFields(meena, Instant.parse("2026-07-01T06:30:00Z"), source)),
            )
        }
        assertTrue(Naming.recordingFileName(Naming.RecordingFields(meena, Instant.parse("2026-07-01T06:30:00Z"))).contains("_1200_"))
    }

    @Test
    fun phoneDisplaysAsPlus91() {
        assertEquals("+91 98765 43210", PhoneDisplay.format("+91", "9876543210"))
        assertEquals("+91 98765 43210", PhoneDisplay.format("91", "98765-43210"))
        assertEquals("", PhoneDisplay.format("+91", ""))
        assertEquals("+44 7911123456", PhoneDisplay.format("+44", "7911123456"))
        assertTrue(PhoneDisplay.isValid("+91", "9876543210"))
        assertFalse(PhoneDisplay.isValid("+91", "98765"))
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
            val people = (input["participants"] as? JsonArray).orEmpty().map { p ->
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
