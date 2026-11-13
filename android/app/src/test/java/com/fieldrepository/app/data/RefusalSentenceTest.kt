package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RefusalSentenceTest {

    private fun http(code: Int, body: String, type: String = "application/json"): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody(type.toMediaTypeOrNull()))
    )

    private val oldPydanticCraftName =
        """{"detail":[{"type":"string_too_short","loc":["body","craftName"],"msg":"String should have at least 1 character","input":"","ctx":{"min_length":1}}]}"""

    private val newValidationShape =
        """{"detail":[{"type":"string_too_short","loc":["body","craftName"],"field":"craftName","label":"Craft name","msg":"Craft name can't be empty."}],""" +
            """"message":"Craft name can't be empty.","code":"validation_failed"}"""

    private val nginxTooLarge =
        "<html><head><title>413 Request Entity Too Large</title></head><body><center><h1>413 Request Entity Too Large</h1></center><hr><center>nginx</center></body></html>"

    private fun assertNoStatusNumber(said: String, code: Int) {
        assertFalse(said, said.contains("HTTP"))
        assertFalse(said, said.contains(code.toString()))
    }

    @Test
    fun `an old server's pydantic list names the field it refused`() {
        val failure = http(422, oldPydanticCraftName).apiFailure("Unable to save tool")
        assertEquals("Craft name: String should have at least 1 character.", failure.message)
        assertEquals(listOf("craftName"), failure.fields)
    }

    @Test
    fun `a new server's validation sentence is shown as written`() {
        val failure = http(422, newValidationShape).apiFailure("Unable to save tool")
        assertEquals("Craft name can't be empty.", failure.message)
        assertEquals(listOf("craftName"), failure.fields)
        assertEquals("Craft name can't be empty.", http(422, newValidationShape).userMessage("Unable to save tool"))
    }

    @Test
    fun `a new list entry without a top-level message keeps its own sentence`() {
        val body = """{"detail":[{"loc":["body","place"],"field":"place","label":"Place","msg":"Place can't be empty."}]}"""
        assertEquals("Place can't be empty.", http(422, body).userMessage("x"))
    }

    @Test
    fun `several old entries join into one line, each naming its field`() {
        val body = """{"detail":[""" +
            """{"type":"missing","loc":["body","toolkitName"],"msg":"Field required"},""" +
            """{"type":"string_too_short","loc":["body","artisanIds",0],"msg":"String should have at least 1 character"}]}"""
        assertEquals(
            "Toolkit name: Field required. Artisan ids 1: String should have at least 1 character.",
            http(422, body).userMessage("x"),
        )
    }

    @Test
    fun `a validator's own sentence is not prefixed again`() {
        val body = """{"detail":[{"type":"value_error","loc":["body","aadhaarNumber"],"msg":"Value error, That Aadhaar number fails its checksum."}]}"""
        assertEquals("That Aadhaar number fails its checksum.", http(422, body).userMessage("x"))
    }

    @Test
    fun `a string detail and an object detail still read as before`() {
        assertEquals("Record not found here", http(404, """{"detail":"Record not found here"}""").userMessage("x"))
        assertEquals(
            "Giriraj Prasad already holds that Aadhaar",
            http(409, """{"detail":{"code":"artisan_identity_conflict","message":"Giriraj Prasad already holds that Aadhaar"}}""")
                .userMessage("x"),
        )
        assertEquals(
            "artisan_identity_conflict",
            http(409, """{"detail":{"code":"artisan_identity_conflict","message":"m"}}""").apiFailure("x").code,
        )
    }

    @Test
    fun `400, 409, 413 and 422 with an empty body get a sentence, never a number`() {
        for (code in listOf(400, 409, 413, 422)) {
            val said = http(code, "").userMessage("Unable to save tool")
            assertNoStatusNumber(said, code)
            assertEquals(statusSentence(code), said)
        }
    }

    @Test
    fun `nginx's 413 page reads as too large to upload`() {
        val said = http(413, nginxTooLarge, "text/html").userMessage("Unable to save tool")
        assertTrue(said, said.startsWith("This is too large to upload."))
        assertNoStatusNumber(said, 413)
    }

    @Test
    fun `the outbox tray shows the same sentence as the form`() {
        val refusal = http(422, oldPydanticCraftName).outboxRefusal("The server rejected this record.")
        assertEquals("Craft name: String should have at least 1 character.", refusal.reason)
        assertEquals(listOf("craftName"), refusal.fields)
        val empty = http(413, "").outboxRefusal("The server rejected this record.")
        assertEquals(TOO_LARGE_SENTENCE, empty.reason)
    }

    @Test
    fun `an IOException carrying HTTP and a number never reaches the screen`() {
        assertEquals("Unable to save", java.io.IOException("HTTP 422").userMessage("Unable to save"))
        assertEquals("Unable to save", IllegalStateException("HTTP 413").apiFailure("Unable to save").message)
    }

    @Test
    fun `the transport helper prefers the server, then the status, then the caller`() {
        assertEquals("Craft name can't be empty.", httpRefusalSentence(422, newValidationShape, "x"))
        assertEquals(TOO_LARGE_SENTENCE, httpRefusalSentence(413, nginxTooLarge, "x"))
        assertEquals(statusSentence(409), httpRefusalSentence(409, """{"detail":"Conflict"}""", "x"))
        assertEquals("x", httpRefusalSentence(418, null, "x"))
    }

    @Test
    fun `refusal field paths read both the old and the new list`() {
        assertEquals(listOf("craftName"), refusalFieldPaths(Json.parseToJsonElement(oldPydanticCraftName).let { (it as kotlinx.serialization.json.JsonObject)["detail"] }))
        assertEquals(listOf("craftName"), refusalFieldPaths(Json.parseToJsonElement(newValidationShape).let { (it as kotlinx.serialization.json.JsonObject)["detail"] }))
    }

    @Test
    fun `field labels come from the loc path`() {
        assertEquals("Craft name", fieldLabelOf(Json.parseToJsonElement("""["body","craftName"]""")))
        assertEquals("Answer text", fieldLabelOf(Json.parseToJsonElement("""["body","answers",3,"answerText"]""")))
        assertEquals("Artisan ids 2", fieldLabelOf(Json.parseToJsonElement("""["body","artisanIds",1]""")))
        assertEquals(null, fieldLabelOf(Json.parseToJsonElement("""["body"]""")))
    }
}
