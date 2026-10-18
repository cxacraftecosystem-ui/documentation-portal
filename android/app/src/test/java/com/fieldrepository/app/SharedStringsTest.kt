package com.fieldrepository.app

import com.fieldrepository.app.data.QuestionnaireCopy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * DESIGN §9 test 59 and the `shared/` parity check.
 *
 * `shared/strings/questionnaire.json` is the BACKEND stream's (BUILD_PLAN §0.1). When it is present,
 * every key this app uses must exist there with the same words, and the generated
 * `strings_questionnaire.xml` must match it. Until then, the design's fixed copy is pinned here.
 */
class SharedStringsTest {
    private fun shared(path: String): File? =
        generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it, path) }.firstOrNull { it.isFile }

    @Test
    fun `the app's copy matches the shared table`() {
        val file = shared("shared/strings/questionnaire.json") ?: return
        val root = Json.parseToJsonElement(file.readText()) as JsonObject
        val table = (root["strings"] as? JsonObject ?: root)
        val missing = QuestionnaireCopy.strings.filter { (key, value) -> (table[key] as? JsonPrimitive)?.content?.let { it != value } ?: false }
        assertEquals("strings that differ from shared/strings/questionnaire.json", emptyMap<String, String>(), missing)
    }

    @Test
    fun `the design's fixed copy`() {
        val s = QuestionnaireCopy::s
        assertEquals("Start session", s("start.submit"))
        assertEquals("Anything to remember about this sitting", s("start.notePlaceholder"))
        assertEquals("Field visit (no workshop)", s("start.fieldVisit"))
        assertEquals("Session already open", s("p1.title"))
        assertEquals("Upload failed. Retrying.", s("upload.failed"))
        assertEquals("Leave this session?", s("leave.title"))
        assertEquals("Changed on another device", s("p9.title"))
        assertEquals("Recovered a recording", s("p13.title"))
        assertEquals("Couldn't load the questionnaire.", s("record.error"))
        assertEquals("So long recordings aren't stopped.", s("battery.reason"))
        assertEquals("Waiting for consent to transcribe", s("consent.waiting"))
        assertEquals("Transcription paused", s("transcript.paused"))
        assertEquals("Transcription is paused.", s("transcript.pausedReason"))
    }
}
