package com.fieldrepository.app

import com.fieldrepository.app.questionnaire.QFeature
import com.fieldrepository.app.questionnaire.data.QuestionnaireCopy
import com.fieldrepository.app.questionnaire.data.SessionOutbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DESIGN §9 test 59 and the `shared/` parity check (BUILD_PLAN §0.1).
 *
 * Every sentence the app shows comes from `shared/strings/questionnaire.json`, word for word, or is on
 * the short pinned Android-only list. The generated `strings_questionnaire.xml` must hold the table.
 */
class SharedStringsTest {
    private fun repoFile(path: String): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it, path) }.firstOrNull { it.isFile }
            ?: error("$path not found above ${File(".").absolutePath}")

    private val table: Map<String, String> by lazy {
        val root = Json.parseToJsonElement(repoFile("shared/strings/questionnaire.json").readText()) as JsonObject
        (root["strings"] as JsonObject).mapValues { it.value.jsonPrimitive.content }
    }

    @QFeature("B37")
    @Test
    fun `every shared sentence the app holds is the table's, character for character`() {
        val differ = QuestionnaireCopy.shared.filter { (key, text) -> table[key] != text }
            .mapValues { (key, text) -> "app: $text | table: ${table[key]}" }
        assertEquals("sentences that differ from shared/strings/questionnaire.json", emptyMap<String, String>(), differ)
    }

    @Test
    fun `every screen key points at a shared key that exists`() {
        val dangling = QuestionnaireCopy.sharedKeyOf.filterValues { it !in table || it !in QuestionnaireCopy.shared }
        assertEquals(emptyMap<String, String>(), dangling)
        assertEquals(QuestionnaireCopy.sharedKeyOf.size + QuestionnaireCopy.androidOnly.size, QuestionnaireCopy.strings.size)
    }

    @Test
    fun `the Android-only sentences are the pinned few, and none duplicates a shared one`() {
        assertEquals(
            setOf("sessions.builder", "sessions.join", "start.artisan", "inbox.nothing", "inbox.error", "join.scan", "join.pick",
                "join.code", "join.notFound", "join.noQr", "join.onlineOnly", "join.rateLimited", "battery.title",
                // Audit 21 Oct 2026: the hard-coded transcript words and errors (Low), the fix tray (H20),
                // the session, take and inbox actions (H13, H14, §1), paging, offline registration, CSV.
                "transcript.failed", "transcript.queued", "error.removeTake", "error.readFile", "error.recover", "error.startSession",
                "error.recorderStart", "error.queueTake", "error.needsSignal", "error.saveOnPhone", "error.changedElsewhere",
                "tray.title", "tray.sendAgain", "tray.notAllowed", "tray.theirs", "tray.mine", "tray.session", "tray.newArtisan",
                "tray.participant", "tray.answers", "tray.finish", "tray.discardTitle",
                "session.more", "session.edit", "session.continue", "session.merge", "session.removeArtisan", "session.deleteTitle",
                "session.deleteBody", "session.mergeBody", "session.noOtherSessions",
                "take.restore", "take.removed", "take.makePrimary", "take.madePrimary", "take.propose", "take.noProposals", "take.accept", "take.whose",
                "inbox.fileTitle", "inbox.fileSession", "inbox.fileTarget", "inbox.fileWho", "inbox.keepThis", "inbox.keepOther", "inbox.noOtherTake",
                "sessions.search", "sessions.loadMore", "sessions.capped",
                "artisan.new", "artisan.name", "artisan.place", "artisan.aadhaar", "artisan.aadhaarNotShared", "artisan.dos", "artisan.donts",
                "artisan.register", "artisan.onPhone", "csv.saved", "csv.failed"),
            QuestionnaireCopy.androidOnly.keys
        )
        val duplicates = QuestionnaireCopy.androidOnly.filterValues { it in table.values }
        assertEquals("these have a shared key; map them instead", emptyMap<String, String>(), duplicates)
    }

    @Test
    fun `templates fill from the table`() {
        assertEquals("Their 2 takes here will no longer count for them.", QuestionnaireCopy.p10Body(2))
        assertEquals("Their 1 take here will no longer count for them.", QuestionnaireCopy.p10Body(1))
        assertEquals("Ravi hasn't been recorded for D3. Their take will be their first.", QuestionnaireCopy.p2FirstTake("Ravi", "D3"))
        assertEquals("Take 1 is removed once take 2 uploads. You can restore it.", QuestionnaireCopy.p7Body())
        assertEquals("Join code K7Q2MX", QuestionnaireCopy.joinCode("K7Q2MX"))
        assertEquals("Group answer (3)", QuestionnaireCopy.groupAnswer(3))
        assertEquals("Group", QuestionnaireCopy.groupAnswer(null))
        assertEquals("14 of 24 sections · 1 session · 2 repeats", QuestionnaireCopy.recordSummary(14, 24, 1, 2))
        assertEquals(
            QuestionnaireCopy.t("missingOutboxFile", "target" to "Section D", "date" to "7 Jul"),
            SessionOutbox.missingFileMessage("Section D (7 Jul)")
        )
    }

    @Test
    fun `strings_questionnaire xml is generated from the table`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(repoFile("android/app/src/main/res/values/strings_questionnaire.xml"))
        val nodes = doc.getElementsByTagName("string")
        val xml = (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent.replace("\\'", "'").replace("\\\"", "\"").replace("%%", "%")
        }
        fun snake(key: String) = "q_" + key.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_").lowercase()
        val wrong = table.filter { (key, text) ->
            val names = Regex("\\{([A-Za-z][A-Za-z0-9]*)\\}").findAll(text).map { it.groupValues[1] }.distinct().toList()
            val expected = Regex("\\{([A-Za-z][A-Za-z0-9]*)\\}").replace(text) { "%${names.indexOf(it.groupValues[1]) + 1}\$s" }
            xml[snake(key)] != expected
        }.keys
        assertTrue("xml entries missing or stale: $wrong (run python scripts/generate_shared.py)", wrong.isEmpty())
        assertEquals(table.size, xml.size)
    }

    @Test
    fun `the design's fixed copy`() {
        val s = QuestionnaireCopy::s
        assertEquals("Start session", s("start.submit"))
        assertEquals("Questionnaire", s("start.instrument"))
        assertEquals("Which language?", s("start.languageOther"))
        assertEquals("Not sent: consent refused", s("transcript.refused"))
        assertEquals("Anything to remember about this sitting", s("start.notePlaceholder"))
        assertEquals("Session already open", s("p1.title"))
        assertEquals("Recovered a recording", s("p13.title"))
        assertEquals("So long recordings aren't stopped.", s("battery.reason"))
        assertEquals("Transcription paused", s("transcript.paused"))
    }
}
