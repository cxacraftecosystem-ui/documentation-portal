package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two-step verification's pure half on the handset: the code-shape rule the server and the web share,
 * reading the server's QR square, the guard on "Open in authenticator app", and decoding a sign-in
 * that answered a challenge instead of a session.
 */
class TwoFactorTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test
    fun `a code's kind is its shape`() {
        assertEquals(TwoFactorCode.Kind.TOTP, TwoFactorCode.kindOf("123456"))
        assertEquals(TwoFactorCode.Kind.TOTP, TwoFactorCode.kindOf("123 456"))
        assertEquals(TwoFactorCode.Kind.RECOVERY, TwoFactorCode.kindOf("abcd-efgh"))
        assertEquals(TwoFactorCode.Kind.RECOVERY, TwoFactorCode.kindOf("ABCD EFGH"))
        assertNull(TwoFactorCode.kindOf("12345"))
        assertNull(TwoFactorCode.kindOf("abcd-efg"))
        assertNull(TwoFactorCode.kindOf("abcd-efgé"))
        assertNull(TwoFactorCode.kindOf(""))
    }

    @Test
    fun `the server's square is read as a size and one path`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 29 29" shape-rendering="crispEdges">""" +
            """<rect width="29" height="29" fill="#ffffff"/><path fill="#000000" d="M4 4h7v1h-7zM12 4h1v1h-1z"/></svg>"""
        val drawing = QrSquare.parse(svg)
        assertNotNull(drawing)
        assertEquals(29, drawing!!.size)
        assertEquals("M4 4h7v1h-7zM12 4h1v1h-1z", drawing.pathData)
    }

    @Test
    fun `a square of any other shape is refused rather than misdrawn`() {
        assertNull(QrSquare.parse("<svg></svg>"))
        assertNull(QrSquare.parse("""<svg viewBox="0 0 29 30"><path d="M0 0h1v1h-1z"/></svg>"""))
        assertNull(QrSquare.parse("""<svg viewBox="0 0 29 29"><path d="M0 0 C1 1 2 2 3 3"/></svg>"""))
    }

    @Test
    fun `only a TOTP otpauth link with a secret is handed to the system`() {
        assertTrue(
            OtpauthLink.isOpenable(
                "otpauth://totp/Field%20Repository%3Ameera%40example.org?secret=GEZDGNBVGY3TQOJQ&issuer=Field%20Repository"
            )
        )
        assertFalse(OtpauthLink.isOpenable("otpauth://hotp/x?secret=GEZDGNBV"))
        assertFalse(OtpauthLink.isOpenable("https://example.org/?secret=GEZDGNBV"))
        assertFalse(OtpauthLink.isOpenable("otpauth://totp/x?issuer=y"))
    }

    @Test
    fun `a challenge decodes as a challenge and a session as a session`() {
        val challenge = json.decodeFromString(
            SignInResponse.serializer(),
            """{"two_factor_required": true, "challenge_token": "abc"}"""
        )
        assertTrue(challenge.twoFactorRequired)
        assertEquals("abc", challenge.challengeToken)
        assertNull(challenge.accessToken)

        val session = json.decodeFromString(
            SignInResponse.serializer(),
            """{"accessToken": "t", "tokenType": "bearer", "user": {"id": "u", "email": "e@x.org", "name": "E", "role": "RESEARCHER", "twoFactorEnabled": true}}"""
        )
        assertFalse(session.twoFactorRequired)
        assertEquals("t", session.accessToken)
        assertTrue(session.user!!.twoFactorEnabled)
    }

    @Test
    fun `the shared recovery-code text carries every code on its own line`() {
        val text = recoveryCodesShareText(listOf("abcd-efgh", "jkmn-pqrs"), "meera@example.org")
        val lines = text.lines()
        assertTrue("meera@example.org" in lines)
        assertTrue("abcd-efgh" in lines && "jkmn-pqrs" in lines)
    }

    @Test
    fun `the timed-out sentence is the server's`() {
        val service = java.io.File("../../backend/app/services/two_factor.py").takeIf { it.exists() }
            ?: java.io.File("../backend/app/services/two_factor.py")
        if (!service.exists()) return
        assertTrue(service.readText().contains("CHALLENGE_DEAD_DETAIL = \"$TWO_STEP_CHALLENGE_ENDED\""))
    }
}
