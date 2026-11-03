package com.fieldrepository.app.ui

import com.fieldrepository.app.data.AccessRosterDto
import com.fieldrepository.app.data.CONNECTION_FAILED_SENTENCE
import com.fieldrepository.app.data.apiFailure
import com.fieldrepository.app.data.statusSentence
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class AccessRosterTest {

    @Test
    fun `every status has the web's wording`() {
        assertEquals("Awaiting approval", accessStatusLabel("PENDING"))
        assertEquals("May sign in", accessStatusLabel("ACTIVE"))
        assertEquals("Not approved", accessStatusLabel("REJECTED"))
        assertEquals("Suspended", accessStatusLabel("SUSPENDED"))
    }

    @Test
    fun `an unknown status is shown as itself rather than swallowed`() {
        assertEquals("ARCHIVED", accessStatusLabel("ARCHIVED"))
    }

    @Test
    fun `an admitted address that has never signed in says so without saying never`() {
        assertEquals("Not signed in yet", accessInvitationLabel("ACTIVE", null))
        assertEquals("Not signed in yet", accessInvitationLabel("ACTIVE", "   "))
    }

    @Test
    fun `an address that is not admitted has no invitation outstanding`() {
        assertEquals("No access to take up", accessInvitationLabel("PENDING", null))
        assertEquals("No access to take up", accessInvitationLabel("REJECTED", null))
        assertEquals("No access to take up", accessInvitationLabel("SUSPENDED", null))
    }

    @Test
    fun `a first sign-in outranks the status`() {
        assertEquals("Signed in", accessInvitationLabel("SUSPENDED", "2026-03-04T10:00:00Z"))
    }

    @Test
    fun `a row nobody asked for is not described as a request`() {
        assertEquals("Added by an admin", accessRequestLabel(0))
    }

    @Test
    fun `repeat attempts are counted, because that is how an admin notices somebody stuck`() {
        assertEquals("Asked once", accessRequestLabel(1))
        assertEquals("Asked 7 times", accessRequestLabel(7))
    }

    @Test
    fun `a queue showing everything says nothing`() {
        assertNull(accessQueueNotice(shown = 4, total = 4))
        assertNull(accessQueueNotice(shown = 0, total = 0))
    }

    @Test
    fun `a truncated queue says so and says where the rest is`() {
        val notice = accessQueueNotice(shown = 25, total = 62)
        requireNotNull(notice)
        assertTrue("the sentence must carry both figures", notice.contains("25") && notice.contains("62"))
        assertTrue(
            "a truncation notice that does not say how to reach the rest is only half an admission",
            notice.contains("Awaiting approval")
        )
    }

    @Test
    fun `an empty roster prints page zero of zero, exactly as the web does`() {
        assertEquals("Page 0 of 0 · 0 entries", accessRosterPageNotice(page = 1, pages = 0, total = 0))
        assertEquals("Page 2 of 4 · 71 entries", accessRosterPageNotice(page = 2, pages = 4, total = 71))
        assertEquals("Page 1 of 1 · 1 entry", accessRosterPageNotice(page = 1, pages = 1, total = 1))
    }

    @Test
    fun `an admin cannot offer a tier above their own`() {
        val offered = assignableRoleOptions("ADMIN").map { it.value }
        assertEquals(
            listOf("ADMIN", "PROFESSOR", "RESEARCHER", "FIELD_CONTRIBUTOR", "CROWDSOURCE_VOLUNTEER"),
            offered
        )
    }

    @Test
    fun `the master admin may mint anything, highest first`() {
        val offered = assignableRoleOptions("MASTER_ADMIN").map { it.value }
        assertEquals("MASTER_ADMIN", offered.first())
        assertEquals(6, offered.size)
    }

    @Test
    fun `the labels come from the one shared map`() {
        assertEquals("Crowdsource Volunteer", roleLabelFor("CROWDSOURCE_VOLUNTEER"))
        assertEquals("Master Admin", roleLabelFor("MASTER_ADMIN"))
    }

    private fun httpFailure(status: Int, body: String): HttpException =
        HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun `the pending refusal reaches the screen instead of HTTP 403 Forbidden`() {
        val failure = httpFailure(
            403,
            """{"detail":{"code":"ACCESS_PENDING","message":"Your access request is awaiting approval by an administrator."}}"""
        ).apiFailure("Login failed")

        assertEquals("ACCESS_PENDING", failure.code)
        assertEquals("Your access request is awaiting approval by an administrator.", failure.message)
    }

    @Test
    fun `wrong credentials stay the plain sentence with no code, so the screen cannot confuse them`() {
        val failure = httpFailure(401, """{"detail":"Invalid email or password"}""").apiFailure("Login failed")

        assertNull(failure.code)
        assertEquals("Invalid email or password", failure.message)
    }

    @Test
    fun `a refused person and a suspended person get different answers`() {
        val rejected = httpFailure(
            403,
            """{"detail":{"code":"ACCESS_REJECTED","message":"This address is not approved for access to the repository."}}"""
        ).apiFailure("Login failed")
        val suspended = httpFailure(
            403,
            """{"detail":{"code":"ACCESS_SUSPENDED","message":"Your access to the repository has been suspended."}}"""
        ).apiFailure("Login failed")

        assertEquals("ACCESS_REJECTED", rejected.code)
        assertEquals("ACCESS_SUSPENDED", suspended.code)
        assertTrue(
            "telling a rejected person they are awaiting approval has them waiting forever",
            rejected.message != suspended.message
        )
    }

    @Test
    fun `a transport failure says the request never got there, in words and never a host name`() {
        val failure = RuntimeException("Unable to resolve host \"api.example.org\"").apiFailure("Login failed")

        assertNull(failure.code)
        assertEquals(CONNECTION_FAILED_SENTENCE, failure.message)
        assertEquals(CONNECTION_FAILED_SENTENCE, java.net.UnknownHostException("api.example.org").apiFailure("x").message)
    }

    @Test
    fun `a body-less refusal is a plain sentence, never the status line`() {
        val failure = httpFailure(500, "").apiFailure("Login failed")

        assertNull(failure.code)
        assertEquals(statusSentence(500), failure.message)
        assertFalse(failure.message.contains("HTTP"))
        assertEquals("Login failed", httpFailure(418, "").apiFailure("Login failed").message)
    }

    @Test
    fun `a bare reason phrase from FastAPI is worded per status`() {
        assertEquals(statusSentence(404), httpFailure(404, """{"detail":"Not Found"}""").apiFailure("x").message)
        assertEquals(statusSentence(403), httpFailure(403, "").apiFailure("x").message)
        assertEquals("Unable to load", RuntimeException("HTTP 403 Forbidden").apiFailure("Unable to load").message)
    }

    @Test
    fun `an approval that kept an account's role shows the server's sentence, and nothing else does`() {
        val said = "a@example.org already has an account, as Researcher. Its role stays; change it on Manage users."
        assertEquals(said, heldSentence(AccessRosterDto(email = "a@example.org", accountPromotionHeld = "  $said ")))
        assertNull(heldSentence(AccessRosterDto(email = "a@example.org")))
        assertNull(heldSentence(AccessRosterDto(email = "a@example.org", accountPromotionHeld = "   ")))
        assertNull(heldSentence(Unit))
    }
}
