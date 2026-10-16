package com.fieldrepository.app.data

/**
 * SETTING A FIRST PASSWORD: THE PROVIDER SIGN-IN, RUN AGAIN.
 *
 * A Google, Microsoft or Yahoo account with no password may add one, but the server first asks for
 * that provider's sign-in RUN AGAIN in the last five minutes, for the account's own address — a
 * session on this phone alone is not enough (`_provider_proof` in backend/app/api/routes/auth.py).
 * A refusal carries [CONFIRM_SIGN_IN_HEADER] naming the provider. The web's `lib/providerReauth.ts`
 * says the same sentences.
 *
 * Google answers with an ID token (Credential Manager); Microsoft and Yahoo with an authorization
 * code the server redeems only when the password is saved, so nobody is signed in by either.
 */

/** The header naming the provider to sign in with again: `GOOGLE`, `MICROSOFT` or `YAHOO`. */
const val CONFIRM_SIGN_IN_HEADER = "X-Confirm-Sign-In"

/** Used for a little under the server's five minutes, so a save sent at the edge is not refused. */
const val PROOF_LIFETIME_MILLIS = 270_000L

/** What a provider answered, kept until the password is saved. Never written to disk. */
data class ProviderProof(
    val provider: String,
    val obtainedAtMillis: Long,
    val googleIdToken: String? = null,
    val oidcCode: String? = null,
    val oidcCodeVerifier: String? = null,
    val oidcRedirectUri: String? = null,
    val oidcNonce: String? = null,
) {
    fun isFresh(nowMillis: Long): Boolean {
        val age = nowMillis - obtainedAtMillis
        return age in 0 until PROOF_LIFETIME_MILLIS
    }

    /** The first-password body, with this confirmation and (when two-step is on) the code. */
    fun request(newPassword: String, twoFactorCode: String?): FirstPasswordRequest = FirstPasswordRequest(
        newPassword = newPassword,
        twoFactorCode = twoFactorCode?.trim()?.ifBlank { null },
        googleIdToken = googleIdToken,
        oidcProvider = if (googleIdToken == null) provider else null,
        oidcCode = oidcCode,
        oidcCodeVerifier = oidcCodeVerifier,
        oidcRedirectUri = oidcRedirectUri,
        oidcNonce = oidcNonce,
    )

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

/** The provider to sign in with again: the account's own, or Google when it is none of the three. */
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

/** Thrown by `FieldRepository.saveOwnPassword` for the [CONFIRM_SIGN_IN_HEADER] refusal. */
class ConfirmSignInRequired(message: String, val provider: String) : Exception(message)
