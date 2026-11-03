package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto

fun mayEditQuestionnaire(user: UserDto?, createdById: String?): Boolean {
    if (user == null) return false
    if (FieldPermissions.isContentAdmin(user)) return true
    val author = createdById?.takeIf { it.isNotBlank() } ?: return false
    return author == user.id
}

const val QUESTIONNAIRE_READ_ONLY_NOTE = "Only the form's author or an admin can edit it."
