package com.fieldrepository.app.data

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface AccountRecoveryApi {
    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest): ForgotPasswordAnswer

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

    @POST("users")
    suspend fun createAccount(@Body body: CreateAccountBody): Response<CreatedAccountDto>
}

object PasswordMode {
    const val SET = "SET"
    const val GENERATE = "GENERATE"
    const val NONE = "NONE"
}

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

const val EXISTING_ACCOUNT_HEADER = "X-Existing-Account-Id"

class AccountAlreadyExists(message: String, val existingId: String?) : Exception(message)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ForgotPasswordAnswer(val message: String = "")

@Serializable
data class FirstPasswordRequest(
    val newPassword: String,
    val twoFactorCode: String? = null,
    val googleIdToken: String? = null,
    val oidcProvider: String? = null,
    val oidcCode: String? = null,
    val oidcCodeVerifier: String? = null,
    val oidcRedirectUri: String? = null,
    val oidcNonce: String? = null,
    val providerProof: String? = null,
)

@Serializable
data class PasswordLinkDeliveryRequest(
    val userId: String,
    val delivery: String
)

@Serializable
data class TemporaryPasswordBody(
    val password: String,
    val mustChangePassword: Boolean = true
)

@Serializable
data class MailStatusDto(val available: Boolean = false)

const val PASSWORD_LINK_BY_EMAIL = "EMAIL"

const val TWO_FACTOR_REQUIRED_HEADER = "X-Two-Factor-Required"

const val TWO_FACTOR_CODE_FOR_PASSWORD = "Enter your authenticator code to set a password."

const val SIGN_IN_PROOF_HEADER = "X-Sign-In-Proof"

class TwoFactorCodeRequired(message: String, val signInProof: String? = null) : Exception(message)

fun accountHasPassword(user: UserDto?): Boolean {
    if (user == null) return true
    user.hasPassword?.let { return it }
    val provider = user.authProvider?.trim()?.uppercase().orEmpty()
    val providerAccount = provider == "GOOGLE" || provider == "MICROSOFT" || provider == "YAHOO"
    return !(providerAccount && user.passwordSetAt.isNullOrBlank())
}
