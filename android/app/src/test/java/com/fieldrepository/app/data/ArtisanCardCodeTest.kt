package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handset's card parser against `shared/artisan-card-vectors.json`, the file the server
 * (`test_artisan_card.py`) and the web (`artisan-card-unit.spec.ts`) read too, so the three cannot
 * drift. Plus the offline register match and the pending-card helpers the outbox uses.
 */
class ArtisanCardCodeTest {

    private fun vectors(): JsonObject {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "shared/artisan-card-vectors.json")
            if (candidate.isFile) return Json.parseToJsonElement(candidate.readText()).jsonObject
            dir = dir.parentFile
        }
        error("shared/artisan-card-vectors.json was not found above ${java.io.File("").absolutePath}")
    }

    private fun JsonObject.text(key: String): String? =
        this[key].let { if (it == null || it is JsonNull) null else it.jsonPrimitive.content }

    @Test
    fun `the alphabet and short codes match the shared vectors`() {
        val v = vectors()
        assertEquals(v.text("alphabet"), ArtisanCardCode.ALPHABET)
        for (row in v["shortCodes"]!!.jsonArray.map { it.jsonObject }) {
            val issuer = row.text("issuer")!![0]
            assertEquals(row.text("check"), ArtisanCardCode.check(issuer, row.text("code")!!))
            assertEquals(row.text("shortCode"), ArtisanCardCode.shortCode(issuer, row.text("code")!!))
        }
    }

    @Test
    fun `every parse vector reads the same here as on the server`() {
        val cases = vectors()["parse"]!!.jsonArray.map { it.jsonObject }
        assertTrue("the vector table is empty", cases.size >= 10)
        for (row in cases) {
            val input = row.text("input")
            val result = ArtisanCardCode.parse(input)
            val error = row.text("error")
            if (error != null) {
                assertEquals(input, ArtisanCardCode.Result.Failed(error), result)
            } else {
                val expect = row["expect"]!!.jsonObject
                val card = (result as? ArtisanCardCode.Result.Ok)?.card ?: error("$input did not parse: $result")
                assertEquals(input, expect.text("issuer")!![0], card.issuer)
                assertEquals(input, expect.text("code"), card.code)
                assertEquals(input, expect["keyVersion"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.intOrNull }, card.keyVersion)
                assertEquals(input, expect.text("tag"), card.tag)
                assertEquals(input, expect["typed"]!!.jsonPrimitive.boolean, card.typed)
            }
        }
    }

    @Test
    fun `a QR payload parses to its code and tag and holds no personal data`() {
        for (row in vectors()["tags"]!!.jsonObject["cases"]!!.jsonArray.map { it.jsonObject }) {
            val payload = row.text("payload")!!
            val card = (ArtisanCardCode.parse(payload) as ArtisanCardCode.Result.Ok).card
            assertEquals(row.text("code"), card.code)
            assertEquals(row.text("tag"), card.tag)
            assertEquals(row["keyVersion"]!!.jsonPrimitive.int, card.keyVersion)
            assertFalse(Regex("\\d{12}").containsMatchIn(payload))
        }
    }

    @Test
    fun `an old DPW1 code is not a card, and a mistyped card says so`() {
        assertEquals(ArtisanCardCode.Result.Failed(ArtisanCardCode.NOT_A_CARD), ArtisanCardCode.parse("DPW1:A:clx0abc:7"))
        assertEquals(ArtisanCardCode.NOT_A_CARD_MESSAGE, ArtisanCardCode.failureMessage("DPW1:A:clx0abc:7", ArtisanCardCode.NOT_A_CARD))
        assertEquals(ArtisanCardCode.CHECK_MESSAGE, ArtisanCardCode.failureMessage("F-01234-56789-S4", ArtisanCardCode.BAD_CHECK))
    }

    private fun row(id: String, code: String? = null, linked: List<String> = emptyList()) = ArtisanDto(
        id = id,
        name = "Artisan $id",
        place = "Bhuj",
        status = "APPROVED",
        card = code?.let { ArtisanCardCodesDto(it, ArtisanCardCode.shortCode('F', it), "CXA1:F1:$it:0000000000000000") },
        linkedCardCodes = linked
    )

    private fun parsed(text: String) = (ArtisanCardCode.parse(text) as ArtisanCardCode.Result.Ok).card

    @Test
    fun `the offline register matches this portal's card by code and a designer card by link`() {
        val rows = listOf(row("a1", code = "0123456789"), row("a2", code = "ABCDEFGHJK", linked = listOf("7K3QM9XA2F")))
        assertEquals("a1", ArtisanCardCode.matchInRegister(parsed("F-01234-56789-S5"), rows)?.id)
        assertEquals("a2", ArtisanCardCode.matchInRegister(parsed("D-7K3QM-9XA2F-HQ"), rows)?.id)
        // A designer card carrying one of THIS portal's codes is not that artisan.
        assertNull(ArtisanCardCode.matchInRegister(parsed(ArtisanCardCode.shortCode('D', "0123456789")), rows))
        assertNull(ArtisanCardCode.matchInRegister(parsed(ArtisanCardCode.shortCode('F', "ZZZZZZZZZZ")), rows))
    }

    @Test
    fun `a pending card is found in a queued body and replaced by its artisan id only`() {
        val card = parsed("F-01234-56789-S5")
        val value = ArtisanCardCode.pendingValue(card)
        assertEquals("card:F-01234-56789-S5", value)
        assertTrue(ArtisanCardCode.isPending(value))
        assertFalse(ArtisanCardCode.isPending("clx0artisan"))
        assertEquals("Card F-01234… links when you're online.", ArtisanCardCode.pendingChip(value))
        val body = """{"artisanId":"$value","artisanIds":["a9","$value"],"notes":"card:F-01234-56789-S5 typed in a note"}"""
        assertEquals(setOf("F-01234-56789-S5"), ArtisanCardCode.pendingIn(body))
        val swapped = ArtisanCardCode.substitutePending(body, mapOf("F-01234-56789-S5" to "a1"))
        assertEquals("""{"artisanId":"a1","artisanIds":["a9","a1"],"notes":"card:F-01234-56789-S5 typed in a note"}""", swapped)
        assertEquals(body, ArtisanCardCode.substitutePending(body, emptyMap()))
    }
}
