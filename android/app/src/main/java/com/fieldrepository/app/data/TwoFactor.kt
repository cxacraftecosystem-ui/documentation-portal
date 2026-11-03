package com.fieldrepository.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SignInResponse(
    val accessToken: String? = null,
    val tokenType: String = "bearer",
    val user: UserDto? = null,
    @SerialName("two_factor_required") val twoFactorRequired: Boolean = false,
    @SerialName("challenge_token") val challengeToken: String? = null
)

sealed interface SignInOutcome {
    data class SignedIn(val user: UserDto) : SignInOutcome

    data class TwoStepRequired(val challengeToken: String) : SignInOutcome
}

@Serializable
data class TwoFactorVerifyRequest(
    @SerialName("challenge_token") val challengeToken: String,
    val code: String
)

@Serializable
data class TwoFactorCodeRequest(val code: String)

@Serializable
data class TwoFactorStatusDto(
    val enabled: Boolean = false,
    val pending: Boolean = false,
    @SerialName("recovery_codes_remaining") val recoveryCodesRemaining: Int = 0,
    @SerialName("enabled_at") val enabledAt: String? = null
)

@Serializable
data class TwoFactorSetupDto(
    val secret: String,
    @SerialName("otpauth_uri") val otpauthUri: String,
    @SerialName("qr_svg") val qrSvg: String,
    @SerialName("expires_in_seconds") val expiresInSeconds: Int = 900
)

@Serializable
data class RecoveryCodesDto(@SerialName("recovery_codes") val recoveryCodes: List<String> = emptyList())

const val TWO_STEP_CHALLENGE_ENDED = "This sign-in has timed out. Sign in again."

object TwoFactorCode {
    enum class Kind { TOTP, RECOVERY }

    fun kindOf(text: String): Kind? {
        val compact = text.filterNot { it.isWhitespace() || it == '-' }
        if (compact.length == 6 && compact.all { it in '0'..'9' }) return Kind.TOTP
        if (compact.length == 8 && compact.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return Kind.RECOVERY
        return null
    }
}

object QrSquare {
    data class Drawing(val size: Int, val pathData: String)

    private val viewBox = Regex("""viewBox="0 0 (\d+) (\d+)"""")
    private val path = Regex("""<path[^>]*\sd="([Mhvz0-9 \-]+)"""")

    fun parse(svg: String): Drawing? {
        val box = viewBox.find(svg) ?: return null
        val size = box.groupValues[1].toIntOrNull() ?: return null
        if (size <= 0 || box.groupValues[2] != box.groupValues[1]) return null
        val d = path.find(svg)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: return null
        return Drawing(size, d)
    }
}

object OtpauthLink {
    fun isOpenable(uri: String): Boolean =
        uri.startsWith("otpauth://totp/") && Regex("""[?&]secret=[A-Z2-7]+(&|$)""").containsMatchIn(uri)
}

fun recoveryCodesShareText(codes: List<String>, email: String): String =
    (
        listOf(
            "Field Repository — recovery codes",
            email,
            "",
            "Each code works once, in place of the 6-digit code from your authenticator app.",
            ""
        ) + codes
        ).joinToString("\n")

object TwoStepSwitch {
    enum class View { OFF, SETTING_UP, ON }

    const val AWAY_LIMIT_MILLIS: Long = 5L * 60L * 1000L

    fun view(status: TwoFactorStatusDto?, settingUpHere: Boolean): View = when {
        status?.enabled == true -> View.ON
        settingUpHere -> View.SETTING_UP
        else -> View.OFF
    }

    fun abandonedAfterAway(settingUpHere: Boolean, awayMillis: Long): Boolean =
        settingUpHere && awayMillis > AWAY_LIMIT_MILLIS

    fun statusLine(status: TwoFactorStatusDto?, view: View, formatDate: (String) -> String): String {
        if (view == View.SETTING_UP) return "Setting up… It turns on once the app's code is checked."
        if (status == null || view == View.OFF) return "Off. Turn it on to ask for a code from your phone at sign-in."
        val left = status.recoveryCodesRemaining
        val codes = if (left == 1) "1 recovery code left" else "$left recovery codes left"
        return status.enabledAt?.let { "On since ${formatDate(it)} · $codes" } ?: "On · $codes"
    }
}
