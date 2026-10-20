package com.fieldrepository.app.data

import com.fieldrepository.app.ui.questionnaire.joinCodeUnknown
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * A consent answer is never lost to a struggling server, a typed join code is never called unknown
 * for want of a signal, and the consent date reads as the web's does.
 */
class ConsentQueueRulesTest {
    private fun http(code: Int) = HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))

    @Test
    fun aQueuedConsentWaitsOnAnythingARetryCanChange() {
        assertTrue("no signal", consentRetryable(IOException("offline")))
        listOf(408, 429, 500, 502, 503, 504).forEach { assertTrue("HTTP $it waits", consentRetryable(http(it))) }
    }

    @Test
    fun aQueuedConsentIsDroppedOnlyOnARefusal() {
        listOf(400, 403, 404, 409, 422).forEach { assertFalse("HTTP $it is final", consentRetryable(http(it))) }
    }

    @Test
    fun aTypedCodeIsUnknownOnlyWhenTheServerSaysSo() {
        assertTrue(joinCodeUnknown(http(404)))
        assertTrue(joinCodeUnknown(http(400)))
        assertFalse("no signal is not a wrong code", joinCodeUnknown(IOException("offline")))
        assertFalse("a server fault is not a wrong code", joinCodeUnknown(http(503)))
        assertFalse(joinCodeUnknown(http(429)))
    }

    @Test
    fun theConsentDayIsTheIstDayInTheWebsWords() {
        assertEquals("19 Oct 2026", consentDay("2026-10-19T06:00:00Z"))
        // 20:00 UTC is already the next day in India.
        assertEquals("20 Oct 2026", consentDay("2026-10-19T20:00:00Z"))
        assertEquals("19 Oct 2026", consentDay("2026-10-19T06:00:00.123+00:00"))
        assertEquals("5 Oct 2026", consentDay("2026-10-05"))
        assertNull(consentDay(null))
        assertNull(consentDay(" "))
        assertNull(consentDay("not a date"))
    }
}
