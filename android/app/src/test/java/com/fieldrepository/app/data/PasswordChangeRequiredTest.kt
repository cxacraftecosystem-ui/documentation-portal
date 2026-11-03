package com.fieldrepository.app.data

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class PasswordChangeRequiredTest {

    private fun answer(code: Int, vararg headers: String): HttpException = HttpException(
        Response.error<Any>(
            "{\"detail\":\"Choose a new password to continue.\"}"
                .toResponseBody("application/json".toMediaTypeOrNull()),
            okhttp3.Response.Builder()
                .code(code)
                .message("refused")
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .headers(Headers.headersOf(*headers))
                .request(okhttp3.Request.Builder().url("http://localhost/api/records").build())
                .build()
        )
    )

    private fun gated(): HttpException = answer(401, PASSWORD_CHANGE_REQUIRED_HEADER, "1")

    @Test
    fun `a 401 carrying the header is the password gate`() {
        assertTrue(gated().isPasswordChangeRequired())
    }

    @Test
    fun `a 401 without it is an ordinary 401`() {
        assertFalse(answer(401).isPasswordChangeRequired())
    }

    @Test
    fun `a 403 carrying it is still a 403`() {
        assertFalse(answer(403, PASSWORD_CHANGE_REQUIRED_HEADER, "1").isPasswordChangeRequired())
    }

    @Test
    fun `the header is found whatever case a proxy left its name in`() {
        assertTrue(answer(401, "x-password-change-required", "1").isPasswordChangeRequired())
    }

    @Test
    fun `a blank value is no header`() {
        assertFalse(answer(401, PASSWORD_CHANGE_REQUIRED_HEADER, " ").isPasswordChangeRequired())
    }

    @Test
    fun `no answer at all is not the gate`() {
        assertFalse(IOException("no signal").isPasswordChangeRequired())
    }

    @Test
    fun `the interceptor's question and the failure's question agree`() {
        assertTrue(isPasswordChangeRequired(401, "1"))
        assertFalse(isPasswordChangeRequired(401, null))
        assertFalse(isPasswordChangeRequired(403, "1"))
        assertFalse(isPasswordChangeRequired(200, "1"))
    }

    @Test
    fun `a gated 401 keeps the session and asks for a new password`() {
        assertEquals(SessionVerdict.CHOOSE_NEW_PASSWORD, gated().sessionVerdict())
    }

    @Test
    fun `every other 401 still ends the session`() {
        assertEquals(SessionVerdict.EXPIRED, answer(401).sessionVerdict())
    }

    @Test
    fun `a 403 still signs out with its refusal, header or not`() {
        assertEquals(SessionVerdict.REFUSED, answer(403).sessionVerdict())
        assertEquals(SessionVerdict.REFUSED, answer(403, PASSWORD_CHANGE_REQUIRED_HEADER, "1").sessionVerdict())
    }

    @Test
    fun `no answer, or an answer about the server, keeps the cached session`() {
        assertEquals(SessionVerdict.KEEP, IOException("no signal").sessionVerdict())
        assertEquals(SessionVerdict.KEEP, answer(500).sessionVerdict())
        assertEquals(SessionVerdict.KEEP, answer(504).sessionVerdict())
    }

    @Test
    fun `classifying reads no body, so the server's sentence is still there afterwards`() {
        val failure = gated()
        failure.isPasswordChangeRequired()
        failure.sessionVerdict()
        assertEquals("Choose a new password to continue.", failure.apiErrorMessage("fallback"))
    }

    @Test
    fun `every raise is a change the session root can see`() {
        val before = PasswordChangeSignal.raises.value
        PasswordChangeSignal.raise()
        PasswordChangeSignal.raise()
        assertEquals(before + 2, PasswordChangeSignal.raises.value)
    }

    @Test
    fun `an ended session is a plain 401 to the token still held, and nothing else is`() {
        assertTrue(isSessionEnded(401, null, "held", "held"))
        assertTrue("a blank header is no header", isSessionEnded(401, " ", "held", "held"))
        assertFalse("the gate's 401 is a live session", isSessionEnded(401, "1", "held", "held"))
        assertFalse("a refused sign-in sent no token", isSessionEnded(401, null, null, null))
        assertFalse("nor does a blank one count", isSessionEnded(401, null, "", ""))
        assertFalse("a token replaced in flight", isSessionEnded(401, null, "held", "newer"))
        assertFalse("a sign-out in flight", isSessionEnded(401, null, "held", null))
        assertFalse("a 403 refuses the account, not the session", isSessionEnded(403, null, "held", "held"))
        assertFalse(isSessionEnded(200, null, "held", "held"))
    }

    @Test
    fun `every ended-session raise is a change the session root can see`() {
        val before = SessionEndedSignal.raises.value
        SessionEndedSignal.raise()
        SessionEndedSignal.raise()
        assertEquals(before + 2, SessionEndedSignal.raises.value)
    }
}
