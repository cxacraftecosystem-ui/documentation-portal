package com.fieldrepository.app.data

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Getting back into an account — the handset's half of the routes both portals share:
 *
 *   * `POST /auth/forgot-password` — "Forgot your password?" on the sign-in card. Always the same
 *     answer for a well-formed address, known or not; only a malformed address (422) or too many
 *     requests from this connection (429) answers differently, and both carry the server's sentence.
 *   * `POST /auth/change-password` with no current password — a Google, Microsoft or Yahoo account
 *     adding its first password. Allowed only on a fresh sign-in; otherwise a 400 carrying
 *     [SIGN_IN_AGAIN_HEADER].
 *   * `POST /auth/password-links` with `delivery: "EMAIL"` — an administrator e-mailing the person a
 *     link instead of copying one. The answer carries no link.
 *   * `PATCH /users/{id}` with a password — a temporary password, changed at the next sign-in.
 *   * `POST /users/{id}/sign-out-everywhere` — every device that account is signed in on has to sign in
 *     again.
 *   * `GET /preferences/notifications` — read only for `available`: whether this deployment sends mail,
 *     which decides whether "Email them a link" is offered.
 *
 * A SERVICE OF ITS OWN, built from [ApiClient.retrofit] as `TwoFactorApi` is, so this file is the wire
 * and nothing else, and copyable between the two apps. None of these routes mints a session: a person
 * who sets a password through a link or the forgotten-password e-mail signs in afterwards and meets
 * the two-step code like any other sign-in.
 */
interface AccountRecoveryApi {
    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest): ForgotPasswordAnswer

    /** A first password for an account that has none. A `Response` for the session-token header. */
    @POST("auth/change-password")
    suspend fun setFirstPassword(@Body body: FirstPasswordRequest): Response<ChangePasswordResponse>

    @POST("auth/password-links")
    suspend fun issuePasswordLink(@Body body: PasswordLinkDeliveryRequest): IssuedPasswordLinkDto

    @PATCH("users/{id}")
    suspend fun setTemporaryPassword(@Path("id") userId: String, @Body body: TemporaryPasswordBody): UserDto

    @POST("users/{id}/sign-out-everywhere")
    suspend fun signOutEverywhere(@Path("id") userId: String): UserDto

    @GET("preferences/notifications")
    suspend fun mailStatus(): MailStatusDto

    /**
     * "Add a person": the account, its allow-list admission and its role's own list, in one step on
     * the server. A `Response` so a 409 can be read for [EXISTING_ACCOUNT_HEADER].
     */
    @POST("users")
    suspend fun createAccount(@Body body: CreateAccountBody): Response<CreatedAccountDto>
}

/** How a new person will sign in — the `passwordMode` of `POST /users`. */
object PasswordMode {
    const val SET = "SET"
    const val GENERATE = "GENERATE"
    const val NONE = "NONE"
}

/**
 * `POST /users` for "Add a person". Null fields are absent from the body (`explicitNulls = false`):
 * `password` only for [PasswordMode.SET], `sendPasswordLink` only for [PasswordMode.NONE].
 */
@Serializable
data class CreateAccountBody(
    val email: String,
    val name: String,
    val role: String,
    val passwordMode: String,
    val password: String? = null,
    val mustChangePassword: Boolean? = null,
    val sendPasswordLink: Boolean? = null
)

/**
 * The account as created, plus what came with it ONCE: a generated [temporaryPassword], the
 * e-mailed [passwordLink] (no link inside it — it went to their inbox), or why it could not be sent.
 */
@Serializable
data class CreatedAccountDto(
    val id: String = "",
    val email: String = "",
    val name: String = "",
    val role: String = "",
    val temporaryPassword: String? = null,
    val passwordLink: IssuedPasswordLinkDto? = null,
    val passwordLinkError: String? = null
)

/** The header a duplicate-address 409 names the existing account in. */
const val EXISTING_ACCOUNT_HEADER = "X-Existing-Account-Id"

/** The address already has an account: the server's sentence, and which account, when it said. */
class AccountAlreadyExists(message: String, val existingId: String?) : Exception(message)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ForgotPasswordAnswer(val message: String = "")

/** No `currentPassword` key at all: there is none to send, and the server reads its absence. */
@Serializable
data class FirstPasswordRequest(val newPassword: String)

@Serializable
data class PasswordLinkDeliveryRequest(
    val userId: String,
    /** `COPY_LINK` or `EMAIL` — see [PASSWORD_LINK_BY_EMAIL]. */
    val delivery: String
)

/** A password typed for somebody else is temporary: they choose their own at the next sign-in. */
@Serializable
data class TemporaryPasswordBody(
    val password: String,
    val mustChangePassword: Boolean = true
)

@Serializable
data class MailStatusDto(val available: Boolean = false)

/** The `delivery` value that e-mails the link to the account's own address. */
const val PASSWORD_LINK_BY_EMAIL = "EMAIL"

/**
 * The header a refused first-password answer carries when the sign-in it was sent from is too old.
 * The person signs out and in again, then sets the password straight away.
 */
const val SIGN_IN_AGAIN_HEADER = "X-Sign-In-Again"

/** Thrown by `FieldRepository.saveOwnPassword` for the [SIGN_IN_AGAIN_HEADER] refusal. */
class SignInAgainRequired(message: String) : Exception(message)

/**
 * Does this account hold a password of its own?
 *
 * The server's `hasPassword` when it sends one. An older server does not, and then the account is
 * read as password-less only when it signs in with a provider AND has never had a password set — a
 * password account from before `passwordSetAt` existed carries LOCAL, and must keep being asked for
 * its current password rather than offered a "set a first password" form the server would refuse.
 */
fun accountHasPassword(user: UserDto?): Boolean {
    if (user == null) return true
    user.hasPassword?.let { return it }
    val provider = user.authProvider?.trim()?.uppercase().orEmpty()
    val providerAccount = provider == "GOOGLE" || provider == "MICROSOFT" || provider == "YAHOO"
    return !(providerAccount && user.passwordSetAt.isNullOrBlank())
}
