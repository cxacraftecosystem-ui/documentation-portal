package com.fieldrepository.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Two-step verification — the handset's half of `/api/auth/two-factor`
 * (backend/app/services/two_factor.py). The wire shape is snake_case because the designer portal and
 * this portal implement one shared spec with one shape. Everything that matters is decided on the
 * server: the code is checked and SPENT there, and the QR square is drawn there. What lives here is
 * the wire types, the shape test that tells a code's kind, and reading the server's square.
 *
 * Offline: the second step is asked only at an ONLINE sign-in. A session already on this device keeps
 * working offline exactly as before; nothing here runs without the network.
 */

/**
 * What `POST /auth/login` answers: a session, or — for an account with two-step verification on — a
 * challenge token and no session. Every first factor (password, Google, Microsoft, Yahoo) answers this.
 */
@Serializable
data class SignInResponse(
    val accessToken: String? = null,
    val tokenType: String = "bearer",
    val user: UserDto? = null,
    @SerialName("two_factor_required") val twoFactorRequired: Boolean = false,
    @SerialName("challenge_token") val challengeToken: String? = null
)

/** A sign-in's outcome, as the screens see it. */
sealed interface SignInOutcome {
    data class SignedIn(val user: UserDto) : SignInOutcome

    /** Nobody is signed in yet; the code screen trades this token and a code for the session. */
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
    @SerialName("recovery_codes_remaining") val recoveryCodesRemaining: Int = 0,
    @SerialName("enabled_at") val enabledAt: String? = null
)

@Serializable
data class TwoFactorSetupDto(
    /** The base32 key, for typing into an authenticator by hand. */
    val secret: String,
    @SerialName("otpauth_uri") val otpauthUri: String,
    /** A white square and one black path, drawn on the server. Read by [QrSquare.parse]. */
    @SerialName("qr_svg") val qrSvg: String
)

@Serializable
data class RecoveryCodesDto(@SerialName("recovery_codes") val recoveryCodes: List<String> = emptyList())

/** The server's sentence for a finished challenge (`two_factor.CHALLENGE_DEAD_DETAIL`). */
const val TWO_STEP_CHALLENGE_ENDED = "This sign-in has timed out. Sign in again."

object TwoFactorCode {
    enum class Kind { TOTP, RECOVERY }

    /**
     * Which kind of code this is, by SHAPE — the server's `code_kind` and the web's `codeKind`, to the
     * character: six digits is an authenticator code, eight letters and digits (hyphen and spaces
     * optional) a recovery code, anything else neither.
     */
    fun kindOf(text: String): Kind? {
        val compact = text.filterNot { it.isWhitespace() || it == '-' }
        if (compact.length == 6 && compact.all { it in '0'..'9' }) return Kind.TOTP
        if (compact.length == 8 && compact.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return Kind.RECOVERY
        return null
    }
}

/**
 * The server's QR square, read rather than rendered: the SVG's shape is fixed by the server
 * (`two_factor.qr_svg`) — one `viewBox="0 0 N N"` and one `<path d=…>` made of `M`, `h`, `v` and `z`
 * only — so the handset draws that path itself and needs no SVG library.
 */
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
    /**
     * Whether "Open in authenticator app" may hand this URI to the system. Only a TOTP `otpauth://`
     * link carrying a secret: anything else would be an arbitrary link opened on the server's say-so.
     */
    fun isOpenable(uri: String): Boolean =
        uri.startsWith("otpauth://totp/") && Regex("""[?&]secret=[A-Z2-7]+(&|$)""").containsMatchIn(uri)
}

/** The text "Copy" and "Share" hand over: a heading, the account, and one code per line. */
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
