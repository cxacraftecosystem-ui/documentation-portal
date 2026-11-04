package com.fieldrepository.app.ui

import com.fieldrepository.app.questionnaire.ui.SESSION_LANGUAGES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SessionLanguagesParityTest {
    private fun repoFile(path: String): File =
        generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it, path) }.firstOrNull { it.isFile }
            ?: error("$path not found above ${File(".").absolutePath}")

    @Test fun `the start sheet offers exactly the shared languages`() {
        val root = Json.parseToJsonElement(repoFile("shared/languages.json").readText()) as JsonObject
        val shared = root["languages"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content to it.jsonObject["label"]!!.jsonPrimitive.content }
        assertEquals(shared, SESSION_LANGUAGES.map { it.value to it.label })
    }
}
