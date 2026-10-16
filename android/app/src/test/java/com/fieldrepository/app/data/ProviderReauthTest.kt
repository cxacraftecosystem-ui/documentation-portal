package com.fieldrepository.app.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderReauthTest {
    private val wire = Json { explicitNulls = false; encodeDefaults = false }

    @Test
    fun `the provider to run again is the account's own, and Google otherwise`() {
        assertEquals("MICROSOFT", reauthProviderFor("microsoft"))
        assertEquals("YAHOO", reauthProviderFor("YAHOO"))
        assertEquals("GOOGLE", reauthProviderFor("GOOGLE"))
        assertEquals("GOOGLE", reauthProviderFor("LOCAL"))
        assertEquals("GOOGLE", reauthProviderFor(null))
    }

    @Test
    fun `a confirmation lasts a little under the server's five minutes`() {
        val proof = ProviderProof.google("id-token", nowMillis = 1_000)
        assertTrue(proof.isFresh(1_000 + PROOF_LIFETIME_MILLIS - 1))
        assertFalse(proof.isFresh(1_000 + PROOF_LIFETIME_MILLIS))
        assertFalse(proof.isFresh(999))
        assertTrue(PROOF_LIFETIME_MILLIS < 300_000L)
    }

    @Test
    fun `a Google confirmation travels as the ID token beside the password and the code`() {
        val body = wire.encodeToString(ProviderProof.google("id-token", 0).request("longenough1", " 123456 "))
        assertEquals("""{"newPassword":"longenough1","twoFactorCode":"123456","googleIdToken":"id-token"}""", body)
    }

    @Test
    fun `a Microsoft confirmation travels as the five fields the sign-in sends`() {
        val proof = ProviderProof.oidc(OidcProviderId.MICROSOFT, "the-code", "v".repeat(64), "https://x.test/login/callback", "raw-nonce-0123456789", 0)
        val body = wire.encodeToString(proof.request("longenough1", null))
        assertEquals(
            """{"newPassword":"longenough1","oidcProvider":"MICROSOFT","oidcCode":"the-code",""" +
                """"oidcCodeVerifier":"${"v".repeat(64)}","oidcRedirectUri":"https://x.test/login/callback",""" +
                """"oidcNonce":"raw-nonce-0123456789"}""",
            body
        )
    }

    @Test
    fun `the words name the provider and never the mechanism`() {
        for (line in listOf(confirmPrompt("YAHOO"), confirmedLine("GOOGLE"), confirmMissing("MICROSOFT"))) {
            assertFalse(line, Regex("token|header|proof|endpoint", RegexOption.IGNORE_CASE).containsMatchIn(line))
        }
        assertTrue(confirmPrompt("YAHOO").contains("sign in with Yahoo again"))
        assertEquals("X-Confirm-Sign-In", CONFIRM_SIGN_IN_HEADER)
    }
}
