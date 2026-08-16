package com.fieldrepository.app.ui

import com.fieldrepository.app.data.apiFailure
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * The access roster's rules, asserted rather than eyeballed.
 *
 * WHY THIS FILE EXISTS. Reproducing any of these by hand means arranging for somebody to actually
 * be turned away at sign-in — a real Google account this institution has never admitted, a real
 * refusal, a real pending row — and then reading a phone screen carefully. Nobody arranges that
 * before a release, and every failure mode here is silent: a queue that quietly stops looks like an
 * empty queue, a wrong status word looks like a design choice, and a refusal that renders "HTTP 403
 * Forbidden" looks like the app being broken rather than like the feature working.
 *
 * So the decisions were lifted out of the composables into pure functions, where one assertion can
 * hold them. If somebody later folds `accessQueueNotice` back into an `if` inside a composable,
 * these tests stop compiling — which is the point.
 *
 * THE WEB TWIN OF EVERY STRING BELOW IS `frontend/lib/accessRoster.ts`. Change one, change both: an
 * admin who reads "Awaiting approval" on a laptop and "Pending" on a phone is looking at two
 * features, not one.
 */
class AccessRosterTest {

    // -----------------------------------------------------------------------
    // The status vocabulary — the contract with the web
    // -----------------------------------------------------------------------

    @Test
    fun `every status has the web's wording`() {
        assertEquals("Awaiting approval", accessStatusLabel("PENDING"))
        assertEquals("May sign in", accessStatusLabel("ACTIVE"))
        assertEquals("Not approved", accessStatusLabel("REJECTED"))
        assertEquals("Suspended", accessStatusLabel("SUSPENDED"))
    }

    @Test
    fun `an unknown status is shown as itself rather than swallowed`() {
        // The server may grow a fifth state before this client is rebuilt. Printing the raw value is
        // ugly and honest; mapping it to "Suspended" or to a blank cell would be neither.
        assertEquals("ARCHIVED", accessStatusLabel("ARCHIVED"))
    }

    // -----------------------------------------------------------------------
    // The invitation — "has this admitted address ever actually been used?"
    // -----------------------------------------------------------------------

    @Test
    fun `an admitted address that has never signed in says so without saying never`() {
        // "Never" is a statement about a person; this is a statement about an invitation, and an
        // admin chasing five addresses added in March needs to be able to tell the two apart.
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
        // A suspended person demonstrably DID take the access up; the row must not claim otherwise
        // just because the access has since been ended.
        assertEquals("Signed in", accessInvitationLabel("SUSPENDED", "2026-03-04T10:00:00Z"))
    }

    // -----------------------------------------------------------------------
    // How the person got here
    // -----------------------------------------------------------------------

    @Test
    fun `a row nobody asked for is not described as a request`() {
        // requestCount is 0 for rows an administrator created — including every account
        // grandfathered by the gate's migration. Calling those "asked once" would misattribute the
        // whole existing user base as applicants.
        assertEquals("Added by an admin", accessRequestLabel(0))
    }

    @Test
    fun `repeat attempts are counted, because that is how an admin notices somebody stuck`() {
        assertEquals("Asked once", accessRequestLabel(1))
        assertEquals("Asked 7 times", accessRequestLabel(7))
    }

    // -----------------------------------------------------------------------
    // Truncation — the most repeated bug class in this repository
    // -----------------------------------------------------------------------

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
        // The web's Pagination does this deliberately, and the two screens are compared side by side
        // by the same admin. "Page 1 of 0" on one of them would look like a bug in that one.
        assertEquals("Page 0 of 0 · 0 entries", accessRosterPageNotice(page = 1, pages = 0, total = 0))
        assertEquals("Page 2 of 4 · 71 entries", accessRosterPageNotice(page = 2, pages = 4, total = 71))
        assertEquals("Page 1 of 1 · 1 entry", accessRosterPageNotice(page = 1, pages = 1, total = 1))
    }

    // -----------------------------------------------------------------------
    // The tiers an admin may hand out — mirroring users.assert_role
    // -----------------------------------------------------------------------

    @Test
    fun `an admin cannot offer a tier above their own`() {
        // Offering MASTER_ADMIN to an ADMIN is a button that can only ever fail, and it fails
        // halfway through approving somebody who is standing there waiting.
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

    // -----------------------------------------------------------------------
    // THE DEFECT THIS FEATURE DIED ON: the refusal a phone could not show
    // -----------------------------------------------------------------------

    private fun httpFailure(status: Int, body: String): HttpException =
        HttpException(Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun `the pending refusal reaches the screen instead of HTTP 403 Forbidden`() {
        // THE DEFECT, in one assertion. Retrofit collapses every non-2xx into an HttpException whose
        // `message` is the literal "HTTP 403 Forbidden", and this client used to render exactly
        // that — so the sentence the whole feature exists to deliver ("you are waiting for an
        // administrator, your details are correct") was invisible on Android, and a person waiting
        // on an approval was shown a status line that reads as the app being broken.
        val failure = httpFailure(
            403,
            """{"detail":{"code":"ACCESS_PENDING","message":"Your access request is awaiting approval by an administrator."}}"""
        ).apiFailure("Login failed")

        assertEquals("ACCESS_PENDING", failure.code)
        assertEquals("Your access request is awaiting approval by an administrator.", failure.message)
    }

    @Test
    fun `wrong credentials stay the plain sentence with no code, so the screen cannot confuse them`() {
        // The 401 is UNCHANGED and carries a bare string detail. A null code is what tells the
        // sign-in screen to draw its red error rather than the amber waiting card — the two answers
        // must never render alike, which is the ruling this feature turns on.
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
    fun `a transport failure is never swallowed behind a generic sentence`() {
        // "Unable to resolve host" is the one clue that the request never left the handset. A
        // fallback that replaced it would turn a network problem into a mystery.
        val failure = RuntimeException("Unable to resolve host").apiFailure("Login failed")

        assertNull(failure.code)
        assertEquals("Unable to resolve host", failure.message)
    }

    @Test
    fun `a body-less refusal falls back rather than rendering nothing`() {
        val failure = httpFailure(500, "").apiFailure("Login failed")

        assertNull(failure.code)
        // HttpException's own text — still more informative than anything this layer could invent.
        assertTrue(failure.message.isNotBlank())
    }
}
