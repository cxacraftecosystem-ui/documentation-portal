package com.fieldrepository.app.data

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

enum class OidcProviderId(val label: String) {
    MICROSOFT("Microsoft"),
    YAHOO("Yahoo"),
}

data class OidcProvider(
    val id: OidcProviderId,
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val redirectUri: String,
    val scope: String = "openid email profile",
) {
    val label: String get() = id.label
}

const val APP_STATE_PREFIX = "app."

private val MICROSOFT_TENANT_KEYWORDS = setOf("common", "organizations", "consumers")
private val TENANT_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

fun microsoftTenant(raw: String?): String? {
    val value = raw?.trim()?.lowercase().orEmpty().ifEmpty { "common" }
    return value.takeIf { it in MICROSOFT_TENANT_KEYWORDS || TENANT_ID.matches(it) }
}

internal fun isLoopbackHttp(uri: String): Boolean {
    val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return false
    return parsed.scheme == "http" && parsed.host in setOf("localhost", "127.0.0.1")
}

fun configuredOidcProviders(
    microsoftClientId: String,
    microsoftTenant: String,
    yahooClientId: String,
    redirectUri: String,
    allowLoopback: Boolean = false,
): List<OidcProvider> {
    val redirect = redirectUri.trim()
    if (!redirect.startsWith("https://") && !(allowLoopback && isLoopbackHttp(redirect))) return emptyList()
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

fun randomUrlSafe(bytes: Int = 32): String {
    val buffer = ByteArray(bytes)
    random.nextBytes(buffer)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
}

fun nonceDigest(rawNonce: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray(Charsets.UTF_8))
    )

fun newAppState(): String = APP_STATE_PREFIX + randomUrlSafe()

@Serializable
data class OidcLoginRequest(
    val oidcProvider: String,
    val oidcCode: String,
    val oidcCodeVerifier: String,
    val oidcRedirectUri: String,
    val oidcNonce: String,
)

fun oidcCallbackErrorMessage(provider: OidcProviderId, error: String?): String =
    if (error == "access_denied" || error == "cancelled") {
        "${provider.label} sign-in cancelled."
    } else {
        "${provider.label} sign-in didn't finish. Try again."
    }
