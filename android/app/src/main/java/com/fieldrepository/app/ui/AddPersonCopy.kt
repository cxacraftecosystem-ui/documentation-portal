package com.fieldrepository.app.ui

import com.fieldrepository.app.data.CreateAccountBody
import com.fieldrepository.app.data.PasswordMode

val ALL_ROLES = listOf(
    "CROWDSOURCE_VOLUNTEER",
    "FIELD_CONTRIBUTOR",
    "RESEARCHER",
    "PROFESSOR",
    "ADMIN",
    "MASTER_ADMIN",
)

fun creatableRoles(actorRole: String?, actorIsMaster: Boolean): List<String> {
    val ceiling = if (actorIsMaster) FieldPermissions.RANK_MASTER_ADMIN else FieldPermissions.rank(actorRole)
    return ALL_ROLES
        .filter { FieldPermissions.rank(it) < ceiling }
        .filter { it != "ADMIN" || actorIsMaster }
        .sortedByDescending { FieldPermissions.rank(it) }
}

fun defaultNewRole(offered: List<String>): String? =
    if ("RESEARCHER" in offered) "RESEARCHER" else offered.firstOrNull()

const val ADD_PERSON_HEADING = "Add a person"
const val ADD_PERSON_INTRO = "The account is created now, at the role you choose."
const val ADD_PERSON_SIGN_IN_QUESTION = "How will they sign in?"
const val ADD_PERSON_MODE_SET = "Set a password now"
const val ADD_PERSON_MODE_GENERATE = "Make a temporary password for me"
const val ADD_PERSON_MODE_NONE = "Google, Microsoft or Yahoo only"
const val ADD_PERSON_MUST_CHANGE = "Ask them to choose a new one at first sign-in"
const val ADD_PERSON_SEND_LINK = "Email them a link to set a password too"
const val ADD_PERSON_TEMPORARY_NOTE = "Shown once — copy it and send it yourself. They change it at first sign-in."
const val OPEN_THEIR_ACCOUNT = "Open their account"

fun addPersonProblem(name: String, email: String, role: String?, mode: String, password: String): String? {
    if (name.isBlank()) return "Enter their full name."
    forgotPasswordEmailProblem(email)?.let { return it }
    if (role.isNullOrBlank()) return "Choose a role."
    if (mode == PasswordMode.SET) return temporaryPasswordProblem(password)
    return null
}

fun addPersonBody(
    name: String,
    email: String,
    role: String,
    mode: String,
    password: String,
    mustChange: Boolean,
    sendLink: Boolean,
    mailAvailable: Boolean,
): CreateAccountBody = CreateAccountBody(
    email = email.trim().lowercase(),
    name = name.trim(),
    role = role,
    passwordMode = mode,
    password = if (mode == PasswordMode.SET) password else null,
    mustChangePassword = if (mode == PasswordMode.SET) mustChange else null,
    sendPasswordLink = if (mode == PasswordMode.NONE && mailAvailable && sendLink) true else null,
)

fun addPersonDoneLine(name: String, mode: String, linkEmailed: Boolean, linkError: String?): String {
    val base = "$name's account is ready."
    return when {
        mode == PasswordMode.GENERATE -> "$base Their temporary password is below."
        mode == PasswordMode.SET -> "$base Send them the password you set."
        linkEmailed -> "$base A link to set a password is on its way."
        linkError != null -> "$base $linkError"
        else -> "$base They sign in with Google, Microsoft or Yahoo."
    }
}
