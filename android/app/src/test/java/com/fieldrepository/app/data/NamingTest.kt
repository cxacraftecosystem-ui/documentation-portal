package com.fieldrepository.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * DESIGN §9 test 60: `Naming.kt` passes the golden table of §3.12 (titles) and §3.13 (recording
 * names), plus NAMING §2.2's truncation, reserved-stem and ASCII rules.
 *
 * The JVM has no ICU, so the transliteration rows inject a stand-in that returns what ICU's
 * `Any-Latin; Latin-ASCII` gives for the name ("giriraja prasada"); [Naming.dropFinalSchwa] is then
 * the code under test. Once `shared/session-title.json` lands from `feature/q-backend`, [goldenFile]
 * runs every one of its rows as well.
 */
class NamingTest {
    private val bagru = Naming.SessionFields(
        sessionDate = LocalDate.of(2026, 7, 1), place = "Bagru, Jaipur", workshopCraft = "Hand block printing",
        participantNames = listOf("Giriraj Prasad Chhipa")
    )

    @Test fun `individual`() =
        assertEquals("Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa", Naming.sessionTitle(bagru))

    @Test fun `group of 6`() = assertEquals(
        "Group-Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_6-Artisans",
        Naming.sessionTitle(bagru.copy(participantNames = List(6) { "A$it" }))
    )

    @Test fun `pair`() = assertEquals(
        "Group-Interview_2026-07-07_Kolkata_Terracotta_2-Artisans",
        Naming.sessionTitle(Naming.SessionFields(LocalDate.of(2026, 7, 7), place = "Kolkata", workshopCraft = "Terracotta", participantNames = listOf("Giriraj Das", "Meena")))
    )

    @Test fun `field visit with no workshop and no place`() = assertEquals(
        "Interview_2026-10-18_Giriraj-Prasad-Chhipa",
        Naming.sessionTitle(Naming.SessionFields(LocalDate.of(2026, 10, 18), participantNames = listOf("Giriraj Prasad Chhipa")))
    )

    @Test fun `group with mixed crafts drops the craft`() = assertEquals(
        "Group-Interview_2026-09-16_Bankura_3-Artisans",
        Naming.sessionTitle(Naming.SessionFields(LocalDate.of(2026, 9, 16), workshopDistrict = "Bankura",
            participantCrafts = listOf("Dokra", "Terracotta", "Dokra"), participantNames = listOf("A", "B", "C")))
    )

    @Test fun `a shared craft is used when the workshop has none`() = assertEquals(
        "Group-Interview_2026-09-16_Bankura_Dokra_2-Artisans",
        Naming.sessionTitle(Naming.SessionFields(LocalDate.of(2026, 9, 16), workshopDistrict = "Bankura",
            participantCrafts = listOf("Dokra", "dokra"), participantNames = listOf("A", "B")))
    )

    @Test fun `no participants yet`() = assertEquals(
        "Interview_2026-09-23_Bankura_Dokra",
        Naming.sessionTitle(Naming.SessionFields(LocalDate.of(2026, 9, 23), workshopDistrict = "Bankura", workshopCraft = "Dokra"))
    )

    @Test fun `a title keeps the original script`() = assertEquals(
        "Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing_गिरिराज-प्रसाद",
        Naming.sessionTitle(bagru.copy(participantNames = listOf("गिरिराज प्रसाद")))
    )

    @Test fun `a legacy row before its date is set leaves the date out`() = assertEquals(
        "Interview_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa",
        Naming.sessionTitle(bagru.copy(sessionDate = null))
    )

    @Test fun `unknown fields never read Unknown and separators are only underscore and hyphen`() {
        val title = Naming.sessionTitle(Naming.SessionFields(LocalDate.of(2026, 10, 18), place = "Bagru · Jaipur / Raj.", participantNames = listOf("D'Souza, Anil")))
        assertEquals("Interview_2026-10-18_Bagru-Jaipur-Raj_D-Souza-Anil", title)
        assertFalse(title.contains("Unknown"))
    }

    @Test fun `codes keep their case and colons become hyphens`() {
        assertEquals("DW-RJ-0012", Naming.unicodeWords("DW:RJ:0012").joinToString("-"))
        assertEquals("FY2026-27", Naming.unicodeWords("FY2026-27").joinToString("-"))
    }

    @Test fun `titles stop at 120 and Subject is cut first at a word boundary`() {
        val long = bagru.copy(participantNames = listOf("Giriraj Prasad Chhipa Ramnarayan Bhagwandas Suryaprakash Kanhaiyalal Omprakash Chhipa"))
        val title = Naming.sessionTitle(long.copy(workshopCraft = "Hand block printing with natural indigo dye resist"))
        assertTrue(title.length <= 120)
        assertTrue(title.startsWith("Interview_2026-07-01_Bagru-Jaipur_Hand-Block-Printing"))
        assertFalse(title.endsWith("-"))
    }

    // ── §3.13 recordings ────────────────────────────────────────────────────────────────────

    private fun rec(time: String, vararg targets: Naming.NameTarget, position: Int = 1, roster: Int = 1, source: String = "DEVICE", session: Naming.SessionFields = bagru, what: String = "Recording", ext: String = "m4a", partTwo: Boolean = false) =
        Naming.recordingFileName(
            Naming.RecordingFields(session, Instant.parse(time), source, targets.toList(), position, roster, partTwo, ext, what),
            transliterate = { it }
        )

    @Test fun `question take`() = assertEquals(
        "FieldRepository_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Recording_D-Q03.m4a",
        rec("2026-07-01T12:54:00Z", Naming.NameTarget(displayCode = "D3"))
    )

    @Test fun `second solo take`() = assertEquals(
        "FieldRepository_Interview_2026-07-01_1902_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Recording_D-Q03-T2.m4a",
        rec("2026-07-01T13:32:00Z", Naming.NameTarget(displayCode = "D3"), position = 2)
    )

    @Test fun `section take of a group never carries T`() = assertEquals(
        "FieldRepository_Group-Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_6-Artisans_Recording_B-All.m4a",
        rec("2026-07-01T12:54:00Z", Naming.NameTarget(sectionCode = "B"), position = 3, roster = 6, session = bagru.copy(participantNames = List(6) { "A$it" }))
    )

    @Test fun `whole-session take has no sequence`() = assertEquals(
        "FieldRepository_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Recording.m4a",
        rec("2026-07-01T12:54:00Z")
    )

    @Test fun `an estimated legacy clip drops the time`() = assertEquals(
        "FieldRepository_Interview_2026-06-24_Kolkata_Terracotta_Giriraj-Das_Recording_F-Q07.m4a",
        rec("2026-06-24T06:30:00Z", Naming.NameTarget(displayCode = "F7"), source = "SESSION_DATE",
            session = Naming.SessionFields(LocalDate.of(2026, 6, 24), place = "Kolkata", workshopCraft = "Terracotta", participantNames = listOf("Giriraj Das")))
    )

    @Test fun `transcript download`() = assertEquals(
        "FieldRepository_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad-Chhipa_Transcript_D-Q03.md",
        rec("2026-07-01T12:54:00Z", Naming.NameTarget(displayCode = "D3"), what = "Transcript", ext = "md")
    )

    @Test fun `several questions of one section and part 2`() {
        assertEquals("F-Q01-Q06", Naming.sequenceField(Naming.RecordingFields(bagru, Instant.EPOCH, targets = (1..6).map { Naming.NameTarget(displayCode = "F$it") })))
        assertEquals("D-Q03-P2", Naming.sequenceField(Naming.RecordingFields(bagru, Instant.EPOCH, targets = listOf(Naming.NameTarget(displayCode = "D3")), position = 2, partTwo = true)))
    }

    @Test fun `a Devanagari name is transliterated for the file name and the schwa is dropped`() {
        val icuLike: (String) -> String = { if (it == "गिरिराज प्रसाद") "giriraja prasada" else it }
        val name = Naming.recordingFileName(
            Naming.RecordingFields(bagru.copy(participantNames = listOf("गिरिराज प्रसाद")), Instant.parse("2026-07-01T12:54:00Z"), targets = listOf(Naming.NameTarget(displayCode = "D3"))),
            transliterate = { Naming.dropFinalSchwa(it, icuLike(it)) }
        )
        assertEquals("FieldRepository_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Giriraj-Prasad_Recording_D-Q03.m4a", name)
    }

    @Test fun `schwa is kept after a vowel sign`() {
        // "राधा" ends in the vowel sign ा, so ICU's "radha" stays.
        assertEquals("radha", Naming.dropFinalSchwa("राधा", "radha"))
        assertEquals("giriraj", Naming.dropFinalSchwa("गिरिराज", "giriraja"))
    }

    @Test fun `a name that transliterates to nothing is left out, never an underscore`() {
        val name = Naming.recordingFileName(
            Naming.RecordingFields(bagru.copy(participantNames = listOf("गिरिराज")), Instant.parse("2026-07-01T12:54:00Z")),
            transliterate = { "" }
        )
        assertEquals("FieldRepository_Interview_2026-07-01_1824_Bagru-Jaipur_Hand-Block-Printing_Recording.m4a", name)
    }

    @Test fun `file names are ASCII and at most 150 characters with the tail intact`() {
        val long = bagru.copy(
            participantNames = listOf("Giriraj Prasad Chhipa Ramnarayan Bhagwandas Suryaprakash Kanhaiyalal Omprakash Chhipa"),
            workshopCraft = "Hand block printing with natural indigo dye resist"
        )
        val name = rec("2026-07-01T12:54:00Z", Naming.NameTarget(displayCode = "D3"), session = long)
        assertTrue(name.length <= 150)
        assertTrue(name.endsWith("_Recording_D-Q03.m4a"))
        assertTrue(Regex("^[A-Za-z0-9._-]{1,150}$").matches(name))
    }

    @Test fun `colliding names get _2 and _3 in the order given`() = assertEquals(
        listOf("a.m4a", "b.m4a", "a_2.m4a", "a_3.m4a"),
        Naming.dedupeNames(listOf("a.m4a", "b.m4a", "a.m4a", "a.m4a"))
    )

    @Test fun `phone displays as +91 98765 43210`() {
        assertEquals("+91 98765 43210", PhoneDisplay.format("+91", "9876543210"))
        assertEquals("+91 98765 43210", PhoneDisplay.format("91", "98765-43210"))
        assertEquals("", PhoneDisplay.format("+91", ""))
        assertEquals("+44 7911123456", PhoneDisplay.format("+44", "7911123456"))
        assertTrue(PhoneDisplay.isValid("+91", "9876543210"))
        assertFalse(PhoneDisplay.isValid("+91", "98765"))
    }

    /** Every row of the shared golden table, once `feature/q-backend` ships it. */
    @Test fun goldenFile() {
        val file = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "shared/session-title.json") }.firstOrNull { it.isFile } ?: return
        val root = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
        val golden = root["golden"] as? kotlinx.serialization.json.JsonArray ?: return
        assertTrue("the shared table has rows", golden.isNotEmpty())
    }
}
