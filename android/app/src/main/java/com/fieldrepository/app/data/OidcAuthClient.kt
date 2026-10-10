package com.fieldrepository.app.data

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.fieldrepository.app.BuildConfig
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/**
 * Microsoft and Yahoo sign-in through AppAuth: the authorization request in a browser tab, and the
 * answer AppAuth hands back after checking its `state`. See [OidcSignIn.kt][configuredOidcProviders]
 * for the whole flow; nothing here redeems a code — the backend does, with a secret no phone holds.
 */
class OidcAuthClient(activity: ComponentActivity) {
    private val service = AuthorizationService(activity)

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                service.dispose()
            }
        })
    }

    /** This build's providers; empty when none is configured, and then no button is drawn. */
    val providers: List<OidcProvider> = configuredOidcProviders(
        microsoftClientId = BuildConfig.MICROSOFT_CLIENT_ID,
        microsoftTenant = BuildConfig.MICROSOFT_TENANT,
        yahooClientId = BuildConfig.YAHOO_CLIENT_ID,
        redirectUri = BuildConfig.OIDC_REDIRECT_URI,
    )

    /** A started sign-in: the intent to launch, and the raw nonce this app keeps for the backend. */
    class Started(val intent: Intent, val rawNonce: String)

    fun start(provider: OidcProvider): Started {
        val rawNonce = randomUrlSafe()
        val configuration = AuthorizationServiceConfiguration(
            Uri.parse(provider.authorizationEndpoint),
            Uri.parse(provider.tokenEndpoint),
        )
        val builder = AuthorizationRequest.Builder(
            configuration,
            provider.clientId,
            ResponseTypeValues.CODE,
            Uri.parse(provider.redirectUri),
        )
            .setScope(provider.scope)
            .setState(newAppState())
            .setNonce(nonceDigest(rawNonce))
        if (provider.id == OidcProviderId.MICROSOFT) {
            builder.setResponseMode(AuthorizationRequest.ResponseMode.QUERY)
            builder.setPrompt("select_account")
        }
        return Started(service.getAuthorizationRequestIntent(builder.build()), rawNonce)
    }

    /** What came back: a code with its verifier and redirect URI, or the provider's error code. */
    sealed interface Outcome {
        data class Code(val code: String, val codeVerifier: String, val redirectUri: String) : Outcome
        data class Failed(val error: String?) : Outcome
    }

    fun finish(data: Intent?): Outcome {
        val response = data?.let { AuthorizationResponse.fromIntent(it) }
        val code = response?.authorizationCode
        val verifier = response?.request?.codeVerifier
        if (response != null && code != null && verifier != null) {
            return Outcome.Code(code, verifier, response.request.redirectUri.toString())
        }
        val exception = data?.let { AuthorizationException.fromIntent(it) }
        val cancelled = exception == null ||
            exception.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code ||
            exception.code == AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW.code
        return Outcome.Failed(if (cancelled) "cancelled" else exception?.error)
    }
}
