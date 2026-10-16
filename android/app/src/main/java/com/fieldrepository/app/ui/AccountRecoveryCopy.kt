package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto

/*
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * GETTING BACK IN — the words and the small rules behind ui/AccountRecovery.kt
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Pure functions and constants, so `AccountRecoveryCopyTest` can pin every one of them without a
 * device. The web says the same things in `app/forgot-password/page.tsx`,
 * `components/settings/ChangePasswordCard.tsx` and the users and access screens.
 */

const val FORGOT_PASSWORD_HEADING = "Forgot your password?"

const val FORGOT_PASSWORD_INTRO = "Enter the email address you sign in with. If it belongs to an " +
    "account that uses a password, we will email it a link to choose a new one. The link works once " +
    "and lasts 2 hours."

const val FORGOT_PASSWORD_TWO_STEP_NOTE = "If two-step verification is on for your account, you will " +
    "still need your authenticator app to sign in afterwards."

const val FORGOT_PASSWORD_NO_EMAIL_NOTE = "No email? Ask an administrator — they can make you a link."

/** Shown only if a server answers 200 with no sentence of its own. The server's words win. */
const val FORGOT_PASSWORD_FALLBACK = "If that address belongs to an account that signs in with a " +
    "password, a link to choose a new password is on its way. If nothing arrives within a few " +
    "minutes, check your spam folder, or ask an administrator to make you a link."

/**
 * What is wrong with the typed address before it is sent, or null. As shallow as the access screen's
 * check — one '@' with something either side — because the server is the judge of an address and a
 * stricter rule here would refuse real ones.
 */
fun forgotPasswordEmailProblem(email: String): String? {
    val cleaned = email.trim()
    if (cleaned.isEmpty()) return "Enter your email address."
    val looksUsable = cleaned.count { it == '@' } == 1 &&
        cleaned.substringBefore('@').isNotBlank() &&
        cleaned.substringAfter('@').contains('.')
    return if (looksUsable) null else "That does not look like an email address."
}

/** The server's one answer, verbatim; the fallback only when it sent none. */
fun forgotPasswordShown(serverMessage: String?): String =
    serverMessage?.trim()?.takeIf { it.isNotEmpty() } ?: FORGOT_PASSWORD_FALLBACK

// ── Settings → Password ─────────────────────────────────────────────────────────────────────────

fun passwordCardTitle(hasPassword: Boolean): String =
    if (hasPassword) "Change your password" else "Set a password"

fun passwordCardIntro(hasPassword: Boolean, user: UserDto?): String =
    if (hasPassword) {
        "Enter your current password, then choose a new one. $PASSWORD_CHANGE_SESSIONS"
    } else {
        val provider = providerLabel(user?.authProvider)
        "You sign in with $provider. Set a password too and you can sign in with either — your " +
            "email address and this password, or $provider as before."
    }

fun passwordSavedLine(hadPassword: Boolean): String =
    if (hadPassword) {
        "Your password has been changed. $PASSWORD_CHANGE_SESSIONS"
    } else {
        "Your password is set. You can now sign in with your email address and this password, or " +
            "the way you did before."
    }

/** "Google", "Microsoft" or "Yahoo"; a generic phrase for anything else. */
fun providerLabel(authProvider: String?): String = when (authProvider?.trim()?.uppercase()) {
    "GOOGLE" -> "Google"
    "MICROSOFT" -> "Microsoft"
    "YAHOO" -> "Yahoo"
    else -> "another sign-in"
}

const val SIGN_IN_AGAIN_ACTION = "Sign out and sign in again"

// ── Users → Help them back in ───────────────────────────────────────────────────────────────────

const val HELP_THEM_BACK_IN_HEADING = "Help them back in"

fun emailedLinkLine(name: String, email: String): String =
    "A password link is on its way to $name at $email. It works once."

fun temporaryPasswordIntro(name: String): String =
    "$name signs in with this password once and is then asked to choose their own. Send it to them " +
        "yourself. Every device they are signed in on has to sign in again."

fun temporaryPasswordSetLine(name: String): String =
    "Temporary password set for $name. They will choose their own at their next sign-in."

/** Length rules for a password typed for somebody else, or null when it may be sent. */
fun temporaryPasswordProblem(password: String): String? {
    val length = password.codePointCount(0, password.length)
    return when {
        length < MIN_PASSWORD_LENGTH -> passwordRuleLine()
        length > MAX_PASSWORD_LENGTH -> "At most $MAX_PASSWORD_LENGTH characters."
        password.isBlank() -> passwordRuleLine()
        else -> null
    }
}

fun signOutEverywhereTitle(name: String): String = "Sign $name out of every device?"

fun signOutEverywhereBody(name: String): String =
    "Every phone and browser $name is signed in on has to sign in again. Their password, two-step " +
        "verification and access stay as they are."

fun signedOutEverywhereLine(name: String): String = "$name has been signed out of every device."

/**
 * May "Email them a password link" be offered? Only where mail is sent at all, and only on the
 * accounts a copied link is offered for — `passwordLinkOffered`, the same test the button beside it
 * uses — so the two deliveries of one act can never disagree about whom it is for.
 */
fun emailLinkOffered(target: UserDto, mailAvailable: Boolean): Boolean =
    mailAvailable && passwordLinkOffered(target)

// ── Access list → How will they sign in? ────────────────────────────────────────────────────────

enum class AccessSignIn { PROVIDER, PASSWORD }

const val ACCESS_SIGN_IN_QUESTION = "How will they sign in?"
const val ACCESS_SIGN_IN_PROVIDER = "With Google, Microsoft or Yahoo"
const val ACCESS_SIGN_IN_PASSWORD = "With a password"
const val ACCESS_MUST_CHANGE_LABEL = "Ask them to choose a new one at first sign-in"

fun accessSignInNote(method: AccessSignIn): String = when (method) {
    AccessSignIn.PROVIDER ->
        "Their account is made the first time they sign in with that address."
    AccessSignIn.PASSWORD ->
        "Their account is made now, with this password. Send it to them yourself. They can also " +
            "sign in with Google on the same address later."
}

/** Why the add form cannot be sent yet, or null. A password account needs a name and a password. */
fun accessAddProblem(method: AccessSignIn, password: String, fullName: String): String? {
    if (method == AccessSignIn.PROVIDER) return null
    if (fullName.isBlank()) return "Enter their full name — it becomes the account's name."
    return temporaryPasswordProblem(password)
}

fun accessAddedLine(email: String, method: AccessSignIn): String = when (method) {
    AccessSignIn.PROVIDER -> "$email may sign in."
    AccessSignIn.PASSWORD -> "$email may sign in with the password you set."
}
