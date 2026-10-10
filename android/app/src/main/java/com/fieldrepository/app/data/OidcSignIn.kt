package com.fieldrepository.app.data

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * "CONTINUE WITH MICROSOFT" AND "CONTINUE WITH YAHOO" — the parts of the handset's half that are
 * plain Kotlin, so they are unit-tested without a device. The AppAuth glue is [OidcAuthClient].
 *
 * ── THE FLOW ────────────────────────────────────────────────────────────────────────────────────
 *
 * The provider is opened in a browser tab with an authorization-code request carrying PKCE (S256,
 * AppAuth's own verifier), a `state` that starts [APP_STATE_PREFIX] and the SHA-256 of a raw nonce
 * this app keeps. The provider sends the tab to the web app's `/login/callback` — the one redirect
 * URI both app registrations hold — and that route, seeing an app `state`, hands the code to this
 * app on `com.fieldrepository.app.signin://oidc/callback`, where AppAuth checks the `state`.
 * The code, the verifier, the redirect URI and the RAW nonce then go to `POST /auth/login`, which
 * redeems the code with the client secret (Yahoo accepts nothing else, so no phone could) and
 * verifies the ID token against the provider's keys — `backend/app/services/oidc_sign_in.py`.
 *
 * ── SHOWN ONLY WHEN CONFIGURED ──────────────────────────────────────────────────────────────────
 *
 * A provider is offered only when this build carries its client ID AND the redirect URI (see
 * `app/build.gradle.kts`). Unconfigured means no button — not a disabled one, not a badge.
 */
enum class OidcProviderId(val label: String) {
    MICROSOFT("Microsoft"),
    YAHOO("Yahoo"),
}

data class OidcProvider(
    val id: OidcProviderId,
    val clientId: String,
    val authorizationEndpoint: String,
    /** AppAuth's configuration wants one; the phone never calls it — the backend redeems the code. */
    val tokenEndpoint: String,
    val redirectUri: String,
    val scope: String = "openid email profile",
) {
    val label: String get() = id.label
}

/** A `state` this app minted; the web callback route hands only these to the app. */
const val APP_STATE_PREFIX = "app."

private val MICROSOFT_TENANT_KEYWORDS = setOf("common", "organizations", "consumers")
private val TENANT_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** The tenant segment, or null when the configured value is not one Microsoft accepts. */
fun microsoftTenant(raw: String?): String? {
    val value = raw?.trim()?.lowercase().orEmpty().ifEmpty { "common" }
    return value.takeIf { it in MICROSOFT_TENANT_KEYWORDS || TENANT_ID.matches(it) }
}

/** The providers this build can offer, Microsoft first — the web card's order. */
fun configuredOidcProviders(
    microsoftClientId: String,
    microsoftTenant: String,
    yahooClientId: String,
    redirectUri: String,
): List<OidcProvider> {
    val redirect = redirectUri.trim()
    if (!redirect.startsWith("https://") && !redirect.startsWith("http://localhost")) return emptyList()
    val providers = mutableListOf<OidcProvider>()
    val tenant = microsoftTenant(microsoftTenant)
    if (microsoftClientId.isNotBlank() && tenant != null) {
        providers += OidcProvider(
            id = OidcProviderId.MICROSOFT,
            clientId = microsoftClientId.trim(),
            authorizationEndpoint = "https://login.microsoftonline.com/$tenant/oauth2/v2.0/authorize",
            tokenEndpoint = "https://login.microsoftonline.com/$tenant/oauth2/v2.0/token",
            redirectUri = redirect,
        )
    }
    if (yahooClientId.isNotBlank()) {
        providers += OidcProvider(
            id = OidcProviderId.YAHOO,
            clientId = yahooClientId.trim(),
            authorizationEndpoint = "https://api.login.yahoo.com/oauth2/request_auth",
            tokenEndpoint = "https://api.login.yahoo.com/oauth2/get_token",
            redirectUri = redirect,
        )
    }
    return providers
}

private val random = SecureRandom()

/** A URL-safe random string; 32 bytes is 43 characters. */
fun randomUrlSafe(bytes: Int = 32): String {
    val buffer = ByteArray(bytes)
    random.nextBytes(buffer)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
}

/** What the provider is sent as `nonce`: `base64url(sha256(raw))`, unpadded — the backend's rule. */
fun nonceDigest(rawNonce: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray(Charsets.UTF_8))
    )

fun newAppState(): String = APP_STATE_PREFIX + randomUrlSafe()

/** The body `POST /auth/login` takes for these two providers. */
@Serializable
data class OidcLoginRequest(
    val oidcProvider: String,
    val oidcCode: String,
    val oidcCodeVerifier: String,
    val oidcRedirectUri: String,
    val oidcNonce: String,
)

/** What the card says when the provider sent nothing back. `access_denied` is a person's choice. */
fun oidcCallbackErrorMessage(provider: OidcProviderId, error: String?): String =
    if (error == "access_denied" || error == "cancelled") {
        "Signing in with ${provider.label} was cancelled. You can try again."
    } else {
        "Signing in with ${provider.label} did not complete. Try again, or use another way to sign in."
    }
