package com.fieldrepository.app.ui

import com.fieldrepository.app.data.UserDto

/**
 * Who may EDIT a questionnaire form and everything in it -- rename or re-describe it, add, edit,
 * reorder or move its sections and questions, re-upload a workbook over it -- and delete from it:
 * the form's AUTHOR ([createdById] on the instrument) or a content admin (Admin, Master Admin,
 * Ministry Admin; [FieldPermissions.isContentAdmin]). Owner, 22 Oct 2026, applied strictly; the
 * server answers anyone else 403. Other questionnaire managers (Professors, holders of the
 * canManageQuestionnaire grant) only read a form they did not write. A form with no recorded author
 * is content admins' alone.
 *
 * Deleting a form's sections and questions reads this same predicate (the builder's Remove buttons
 * sit behind the same `canEdit`); there is deliberately no second, looser delete rule for forms.
 *
 * Creating a NEW form stays the questionnaire-manager gate, and deleting sessions and recordings
 * stays content admins only ([mayDelete]) -- neither is this rule.
 */
fun mayEditQuestionnaire(user: UserDto?, createdById: String?): Boolean {
    if (user == null) return false
    if (FieldPermissions.isContentAdmin(user)) return true
    val author = createdById?.takeIf { it.isNotBlank() } ?: return false
    return author == user.id
}

/** Shown where a whole editor is read-only for this viewer. */
const val QUESTIONNAIRE_READ_ONLY_NOTE = "Only the form's author or an admin can edit it."
