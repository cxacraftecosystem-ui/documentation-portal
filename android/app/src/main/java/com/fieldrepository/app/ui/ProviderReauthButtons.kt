package com.fieldrepository.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.fieldrepository.app.data.GoogleAuthClient
import com.fieldrepository.app.data.OidcAuthClient
import com.fieldrepository.app.data.OidcProviderId
import com.fieldrepository.app.data.ProviderProof
import com.fieldrepository.app.data.oidcCallbackErrorMessage
import com.fieldrepository.app.data.reauthLabel
import kotlinx.coroutines.launch

/**
 * The sign-in clients the activity owns, handed to the Password card so it can run a provider's
 * sign-in AGAIN before a first password (data/ProviderReauth.kt). Null outside the activity (previews,
 * tests): the card then draws no button.
 */
class ProviderReauth(val google: GoogleAuthClient, val oidc: OidcAuthClient)

val LocalProviderReauth = staticCompositionLocalOf<ProviderReauth?> { null }

/**
 * "Sign in with Google / Microsoft / Yahoo" for a first password. Its answer is a [ProviderProof] the
 * card keeps for five minutes and sends with the password; nothing here signs anybody in. The
 * account's own provider is offered when this build has it, otherwise every provider it has (any one
 * that vouches for the address is accepted).
 */
@Composable
fun ProviderConfirmButtons(
    provider: String,
    enabled: Boolean,
    onConfirmed: (ProviderProof) -> Unit,
    onFailed: (String) -> Unit,
) {
    val reauth = LocalProviderReauth.current ?: return
    val scope = rememberCoroutineScope()
    // "<provider>|<raw nonce>" while a Microsoft or Yahoo tab is open, as the sign-in screen keeps it.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val started = pending?.split('|', limit = 2)
        pending = null
        val id = started?.getOrNull(0)?.let { name -> OidcProviderId.entries.firstOrNull { it.name == name } }
        val rawNonce = started?.getOrNull(1)
        if (id == null || rawNonce == null) return@rememberLauncherForActivityResult
        when (val outcome = reauth.oidc.finish(result.data)) {
            is OidcAuthClient.Outcome.Failed -> onFailed(oidcCallbackErrorMessage(id, outcome.error))
            is OidcAuthClient.Outcome.Code -> onConfirmed(
                ProviderProof.oidc(id, outcome.code, outcome.codeVerifier, outcome.redirectUri, rawNonce, System.currentTimeMillis())
            )
        }
    }

    val oidcOffered = reauth.oidc.providers
    val wantedOidc = oidcOffered.filter { it.id.name == provider }
    val showGoogle = provider == "GOOGLE" || wantedOidc.isEmpty()
    val shownOidc = if (provider == "GOOGLE") emptyList() else wantedOidc.ifEmpty { oidcOffered }

    if (showGoogle) {
        OutlinedButton(
            enabled = enabled,
            onClick = {
                scope.launch {
                    runCatching { reauth.google.getIdToken() }
                        .onSuccess { onConfirmed(ProviderProof.google(it, System.currentTimeMillis())) }
                        .onFailure { failure ->
                            if (failure !is GetCredentialCancellationException) {
                                onFailed("Signing in with Google did not complete. Try again.")
                            }
                        }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Sign in with ${reauthLabel("GOOGLE")}") }
    }
    for (option in shownOidc) {
        OutlinedButton(
            enabled = enabled && pending == null,
            onClick = {
                runCatching { reauth.oidc.start(option) }
                    .onSuccess { started ->
                        pending = "${option.id.name}|${started.rawNonce}"
                        launcher.launch(started.intent)
                    }
                    .onFailure { onFailed(oidcCallbackErrorMessage(option.id, null)) }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Sign in with ${reauthLabel(option.id.name)}") }
    }
}
