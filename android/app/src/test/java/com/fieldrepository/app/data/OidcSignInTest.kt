package com.fieldrepository.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Microsoft and Yahoo sign-in on the handset: which providers a build offers, the nonce rule it
 * shares with the backend, the body it posts, and the scheme the manifest answers on. AppAuth itself
 * needs a device; everything it is fed is decided here.
 */
class OidcSignInTest {
    private val https = "https://portal.example.org/login/callback"

    @Test
    fun `no client ID means no provider at all`() {
        assertTrue(configuredOidcProviders("", "common", "", https).isEmpty())
        assertTrue(configuredOidcProviders("  ", "common", " ", https).isEmpty())
    }

    @Test
    fun `no redirect URI means no provider, because the code could never come back`() {
        assertTrue(configuredOidcProviders("ms", "common", "y", "").isEmpty())
        assertTrue(configuredOidcProviders("ms", "common", "y", "http://portal.example.org/cb").isEmpty())
    }

    @Test
    fun `each configured provider is offered, Microsoft first, at its own endpoints`() {
        val providers = configuredOidcProviders("ms-id", "organizations", "y-id", https)
        assertEquals(listOf(OidcProviderId.MICROSOFT, OidcProviderId.YAHOO), providers.map { it.id })
        assertEquals(
            "https://login.microsoftonline.com/organizations/oauth2/v2.0/authorize",
            providers[0].authorizationEndpoint,
        )
        assertEquals("https://api.login.yahoo.com/oauth2/request_auth", providers[1].authorizationEndpoint)
        assertTrue(providers.all { it.redirectUri == https && it.scope == "openid email profile" })
    }

    @Test
    fun `a tenant Microsoft would not accept hides the Microsoft button rather than failing later`() {
        assertEquals("common", microsoftTenant(""))
        assertEquals("consumers", microsoftTenant("Consumers"))
        assertEquals("72f988bf-86f1-41af-91ab-2d7cd011db47", microsoftTenant("72F988BF-86F1-41AF-91AB-2D7CD011DB47"))
        assertNull(microsoftTenant("contoso.onmicrosoft.com"))
        assertEquals(listOf(OidcProviderId.YAHOO), configuredOidcProviders("ms", "contoso.com", "y", https).map { it.id })
    }

    @Test
    fun `the nonce digest is the backend's base64url sha-256 without padding`() {
        // RFC 7636 appendix B: the same function the backend's nonce_digest and the web's
        // sha256Base64Url compute, on the RFC's own example.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            nonceDigest("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `app states carry the prefix the web callback hands back to the app, and do not repeat`() {
        val first = newAppState()
        assertTrue(first.startsWith(APP_STATE_PREFIX))
        assertFalse(first == newAppState())
        assertTrue(randomUrlSafe().matches(Regex("^[A-Za-z0-9_-]{43}$")))
    }

    @Test
    fun `the login body uses the backend's field names`() {
        val body = Json.encodeToString(
            OidcLoginRequest.serializer(),
            OidcLoginRequest("YAHOO", "code", "v".repeat(64), https, "raw-nonce-value-0123"),
        )
        val fields = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("oidcProvider", "oidcCode", "oidcCodeVerifier", "oidcRedirectUri", "oidcNonce"), fields.keys)
        assertEquals("raw-nonce-value-0123", fields["oidcNonce"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a cancelled sign-in says so, and no message mentions a server`() {
        assertEquals(
            "Signing in with Yahoo was cancelled. You can try again.",
            oidcCallbackErrorMessage(OidcProviderId.YAHOO, "cancelled"),
        )
        val failed = oidcCallbackErrorMessage(OidcProviderId.MICROSOFT, "server_error")
        assertFalse(Regex("server|endpoint|http|coming soon", RegexOption.IGNORE_CASE).containsMatchIn(failed))
    }

    @Test
    fun `the manifest registers the redirect scheme the web callback sends`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("net.openid.appauth.RedirectUriReceiverActivity"))
        assertTrue(manifest.contains("android:scheme=\"\${appAuthRedirectScheme}\""))
        assertTrue(manifest.contains("android:host=\"oidc\""))
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("manifestPlaceholders[\"appAuthRedirectScheme\"] = \"com.fieldrepository.app.signin\""))
    }
}
