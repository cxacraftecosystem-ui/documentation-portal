package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * The words and rules behind the password screens, ported from the Design Prototype Workshop's
 * `PasswordSetupCopy` with this app's additions (forgot-password, the settings card, the
 * administrator's "Help them back in", the access list's sign-in choice). All pure, so a JVM test
 * can walk every branch.
 */
class PasswordSetupCopyTest {

    private fun user(
        mustChange: Boolean? = null,
        hasPassword: Boolean? = null,
        passwordSetAt: String? = null,
    ) = UserDto(
        id = "u1",
        email = "researcher@example.org",
        name = "A Researcher",
        role = "RESEARCHER",
        mustChangePassword = mustChange,
        hasPassword = hasPassword,
        passwordSetAt = passwordSetAt
    )

    private fun answer(code: Int, vararg headers: String): HttpException = HttpException(
        Response.error<Any>(
            "{\"detail\":\"the server's sentence\"}".toResponseBody("application/json".toMediaTypeOrNull()),
            okhttp3.Response.Builder()
                .code(code)
                .message("refused")
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .headers(Headers.headersOf(*headers))
                .request(okhttp3.Request.Builder().url("http://localhost/api/auth/change-password").build())
                .build()
        )
    )

    // ── The first-password gate ──────────────────────────────────────────────────────────────────

    @Test
    fun `only a true flag gates`() {
        assertTrue(mustChangePasswordBlocks(user(mustChange = true)))
        assertFalse(mustChangePasswordBlocks(user(mustChange = false)))
        assertFalse("a server older than the column blocks nobody", mustChangePasswordBlocks(user(mustChange = null)))
        assertFalse(mustChangePasswordBlocks(null))
    }

    @Test
    fun `a new password must meet the floor, match, and differ from the current one`() {
        assertEquals(passwordRuleLine(), newPasswordRefusal("short", "short"))
        assertEquals("The two passwords do not match.", newPasswordRefusal("longenough1", "longenough2"))
        assertNotNull(newPasswordRefusal("samesame1", "samesame1", current = "samesame1"))
        assertNull(newPasswordRefusal("longenough1", "longenough1", current = "different"))
        assertNotNull(newPasswordRefusal("x".repeat(MAX_PASSWORD_LENGTH + 1), "x".repeat(MAX_PASSWORD_LENGTH + 1)))
    }

    @Test
    fun `the gate asks for the current password only after a 400`() {
        assertTrue(passwordGateAsksForCurrentAfter(400))
        assertFalse(passwordGateAsksForCurrentAfter(401))
        assertFalse(passwordGateAsksForCurrentAfter(429))
        assertFalse(passwordGateAsksForCurrentAfter(null))
    }

    // ── Links ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every link refusal is its own sentence, and an unknown word still says something`() {
        val words = listOf("missing", "malformed", "expired", "revoked", "spent", "unknown-account")
        val sentences = words.map(::passwordLinkRefusal)
        assertEquals(words.size, sentences.toSet().size)
        assertTrue(passwordLinkRefusal("spent").contains("Sign in"))
        assertEquals("This password link is not valid.", passwordLinkRefusal("brand-new-reason"))
    }

    @Test
    fun `a pasted link yields its token, from the query or the fragment`() {
        assertEquals("abc.def", passwordLinkToken("https://field-repository.vercel.app/set-password?token=abc.def"))
        assertEquals("abc.def", passwordLinkToken("https://field-repository.vercel.app/set-password#token=abc.def"))
        assertEquals("a=b.c", passwordLinkToken("https://x/set-password?token=a%3Db.c&utm=1"))
        assertEquals("abc.def", passwordLinkToken("Your link: https://x/set-password?token=abc.def It expires soon"))
        assertEquals("abc.def", passwordLinkToken("  abc.def  "))
        assertEquals("", passwordLinkToken("   "))
    }

    @Test
    fun `only an https link to this app's set-password path is an administrator's link`() {
        assertTrue(isSetPasswordLinkAddress("https", "field-repository.vercel.app", "/set-password"))
        assertTrue(isSetPasswordLinkAddress("HTTPS", "Field-Repository.vercel.app", "/set-password/"))
        assertFalse(isSetPasswordLinkAddress("http", "field-repository.vercel.app", "/set-password"))
        assertFalse(isSetPasswordLinkAddress("https", "designer-repository.vercel.app", "/set-password"))
        assertFalse(isSetPasswordLinkAddress("content", "field-repository.vercel.app", "/set-password"))
        assertFalse(isSetPasswordLinkAddress("https", "field-repository.vercel.app", "/set-password-now"))
    }

    @Test
    fun `a link is offered only to an account that has a password`() {
        assertTrue(passwordLinkOffered(user(hasPassword = true)))
        assertTrue(passwordLinkOffered(user(passwordSetAt = "2026-01-01T00:00:00Z")))
        assertFalse("a Google-only account is offered no link", passwordLinkOffered(user(hasPassword = false)))
    }

    @Test
    fun `the purpose line names the kind of link`() {
        assertNotEquals(passwordLinkPurposeLine("INVITE"), passwordLinkPurposeLine("RESET"))
        assertEquals("One-time password link.", passwordLinkPurposeLine(null))
    }


}
