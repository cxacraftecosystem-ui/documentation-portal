package com.fieldrepository.app.data

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * The one mapper from a failure to words on screen (UX audit 2026-10-16, DC-A02; ported from the designer portal). The property that
 * matters: a reader never sees "HTTP 404", "Unable to resolve host" or an engine's own words, and
 * still sees every sentence the SERVER wrote for people.
 */
class UserMessageTest {

    private fun http(code: Int, body: String): HttpException = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaTypeOrNull()))
    )

    @Test
    fun `a sentence the server wrote for people is kept verbatim`() {
        assertEquals(
            "Giriraj Prasad already holds that Aadhaar",
            http(409, """{"detail":{"message":"Giriraj Prasad already holds that Aadhaar"}}""").userMessage("x"),
        )
    }

    @Test
    fun `an empty answer becomes a plain sentence by status, never HTTP and a number`() {
        for (code in listOf(401, 403, 404, 500, 502, 503)) {
            val said = http(code, "").userMessage("Unable to load artisan")
            assertFalse(said, said.contains("HTTP"))
            assertFalse(said, said.contains(code.toString()))
        }
        assertTrue(http(404, "").userMessage("x").contains("could not be found"))
        assertTrue(http(503, "<html>gateway</html>").userMessage("x").contains("Try again"))
    }

    @Test
    fun `FastAPI's bare reason phrase is not a sentence`() {
        val said = http(404, """{"detail":"Not Found"}""").userMessage("Unable to load artisan")
        assertFalse(said, said == "Not Found")
    }

    @Test
    fun `an unanswered request reads as the connection sentence`() {
        val said = java.net.UnknownHostException("Unable to resolve host \"api.example.org\"").userMessage("x")
        assertEquals(CONNECTION_FAILED_SENTENCE, said)
    }

    @Test
    fun `a developer's exception message gives way to the caller's sentence`() {
        assertEquals("Couldn't trace.", IllegalStateException("edge.x was set to NaN").userMessage("Couldn't trace."))
        assertEquals("Couldn't record.", RuntimeException("start failed.").userMessage("Couldn't record."))
        assertEquals("Fallback.", SecurityException("Permission Denial: starting Intent").userMessage("Fallback."))
        assertEquals("Fallback.", RuntimeException("java.lang.IllegalStateException: boom").userMessage("Fallback."))
    }

    @Test
    fun `a sentence the app itself threw for people is kept`() {
        val mine = "That file is larger than this phone can upload at once."
        assertEquals(mine, IllegalStateException(mine).userMessage("x"))
    }
}
