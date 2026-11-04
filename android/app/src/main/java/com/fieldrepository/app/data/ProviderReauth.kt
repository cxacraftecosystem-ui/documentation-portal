package com.fieldrepository.app.data

const val CONFIRM_SIGN_IN_HEADER = "X-Confirm-Sign-In"

const val PROOF_LIFETIME_MILLIS = 270_000L

data class ProviderProof(
    val provider: String,
    val obtainedAtMillis: Long,
    val googleIdToken: String? = null,
    val oidcCode: String? = null,
    val oidcCodeVerifier: String? = null,
    val oidcRedirectUri: String? = null,
    val oidcNonce: String? = null,
    val providerProof: String? = null,
) {
    fun isFresh(nowMillis: Long): Boolean {
        val age = nowMillis - obtainedAtMillis
        return age in 0 until PROOF_LIFETIME_MILLIS
    }

    fun request(newPassword: String, twoFactorCode: String?): FirstPasswordRequest = if (providerProof != null) {
        FirstPasswordRequest(
            newPassword = newPassword,
            twoFactorCode = twoFactorCode?.trim()?.ifBlank { null },
            providerProof = providerProof,
        )
    } else FirstPasswordRequest(
        newPassword = newPassword,
        twoFactorCode = twoFactorCode?.trim()?.ifBlank { null },
        googleIdToken = googleIdToken,
        oidcProvider = if (googleIdToken == null) provider else null,
        oidcCode = oidcCode,
        oidcCodeVerifier = oidcCodeVerifier,
        oidcRedirectUri = oidcRedirectUri,
        oidcNonce = oidcNonce,
    )

    fun carried(token: String): ProviderProof =
        ProviderProof(provider = provider, obtainedAtMillis = obtainedAtMillis, providerProof = token)

    companion object {
        fun google(idToken: String, nowMillis: Long): ProviderProof =
            ProviderProof(provider = "GOOGLE", obtainedAtMillis = nowMillis, googleIdToken = idToken)

        fun oidc(
            provider: OidcProviderId,
            code: String,
            codeVerifier: String,
            redirectUri: String,
            rawNonce: String,
            nowMillis: Long,
        ): ProviderProof = ProviderProof(
            provider = provider.name,
            obtainedAtMillis = nowMillis,
            oidcCode = code,
            oidcCodeVerifier = codeVerifier,
            oidcRedirectUri = redirectUri,
            oidcNonce = rawNonce,
        )
    }
}

fun reauthProviderFor(authProvider: String?): String = when (authProvider?.trim()?.uppercase()) {
    "MICROSOFT" -> "MICROSOFT"
    "YAHOO" -> "YAHOO"
    else -> "GOOGLE"
}

fun reauthLabel(provider: String): String = when (provider.uppercase()) {
    "MICROSOFT" -> "Microsoft"
    "YAHOO" -> "Yahoo"
    else -> "Google"
}

fun confirmPrompt(provider: String): String =
    "First confirm it's you: sign in with ${reauthLabel(provider)} again. Then choose your password within 5 minutes."

fun confirmedLine(provider: String): String =
    "Confirmed with ${reauthLabel(provider)}. Choose your password now — this lasts 5 minutes."

fun confirmMissing(provider: String): String =
    "Sign in with ${reauthLabel(provider)} again first, using the button above."

class ConfirmSignInRequired(message: String, val provider: String) : Exception(message)
