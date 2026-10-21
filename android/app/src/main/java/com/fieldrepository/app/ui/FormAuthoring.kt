package com.fieldrepository.app.ui

import com.fieldrepository.app.data.QuestionnaireDto
import com.fieldrepository.app.data.UserDto

/**
 * Who may edit and delete a questionnaire form and everything in it (its sections and questions):
 * the form's AUTHOR or a content admin (Admin, Master Admin, Ministry Admin). Owner, 22 Oct 2026
 * (docs/questionnaire/UNIFY_2026-10-21.md, amendment). Everyone else reads the form; the server
 * answers them 403, so the builder hides the controls.
 *
 * Deleting sessions and recordings is NOT this rule: that stays content admins only ([mayDelete]).
 */
fun canEditQuestionnaire(user: UserDto?, form: QuestionnaireDto?): Boolean {
    if (mayDelete(user)) return true
    val author = form?.createdById ?: return false
    return user != null && author == user.id
}
