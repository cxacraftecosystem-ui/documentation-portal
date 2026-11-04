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

class ToolScopeParityTest {
    private fun file(rel: String): java.io.File {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, rel)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$rel was not found above ${java.io.File("").absolutePath}")
    }

    private val vectors: JsonObject =
        Json.parseToJsonElement(file("shared/tool-scope-vectors.json").readText()).jsonObject

    private fun JsonElement?.text(): String? =
        if (this == null || this is JsonNull) null else jsonPrimitive.contentOrNull

    private fun artisan(element: JsonElement?): ArtisanDto? {
        if (element == null || element is JsonNull) return null
        val row = element.jsonObject
        return ArtisanDto(id = row["id"].text()!!, name = row["name"].text().orEmpty(), place = "", status = "PENDING")
    }

    private fun tool(row: JsonObject, toolkitName: String = "Tool"): ToolDetailDto = ToolDetailDto(
        id = "t",
        toolkitName = toolkitName,
        artisanId = row["artisanId"].text(),
        artisanName = row["artisanName"].text().orEmpty(),
        artisanLinks = row["artisanLinks"]!!.jsonArray.map { link ->
            val obj = link.jsonObject
            ToolArtisanLinkDto(artisanId = obj["artisanId"].text()!!, artisan = artisan(obj["artisan"]))
        },
    )

    @Test
    fun artisanNamesMatchTheSharedVectors() {
        val rows = vectors["artisanNames"]!!.jsonArray
        assertTrue(rows.isNotEmpty())
        for (element in rows) {
            val row = element.jsonObject
            assertEquals(row["case"].text(), row["expected"].text(), tool(row["tool"]!!.jsonObject).toolArtisanNames())
        }
    }

    @Test
    fun aToolRowNamesEveryLinkedArtisanAndAToolWithNoneKeepsItsOwnName() {
        val rows = vectors["artisanNames"]!!.jsonArray
        assertEquals(
            "Wooden block · Kamla Devi, Ramesh Chhipa",
            tool(rows[1].jsonObject["tool"]!!.jsonObject, "Wooden block").toolRowLabel(),
        )
        assertEquals("Mallet", tool(rows[5].jsonObject["tool"]!!.jsonObject, "Mallet").toolRowLabel())
    }

    @Test
    fun theToolPickersLabelThroughTheSharedHelper() {
        val source = file("android/app/src/main/java/com/fieldrepository/app/MainActivity.kt").readText()
        assertTrue(source.contains("it.toolRowLabel()"))
        assertTrue(!source.contains("\${it.toolkitName} · \${it.artisanName}"))
        assertEquals(
            "Wooden block · Kamla Devi, Ramesh Chhipa",
            com.fieldrepository.app.ui.recordOptionLabel(tool(vectors["artisanNames"]!!.jsonArray[1].jsonObject["tool"]!!.jsonObject, "Wooden block")),
        )
    }
}
