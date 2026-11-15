package com.fieldrepository.app.data

import java.util.Locale

const val EDIT_ACCESS_FULL = "FULL"
const val EDIT_ACCESS_FILL_EMPTY = "FILL_EMPTY"
const val EDIT_ACCESS_NONE = "NONE"

const val FILL_EMPTY_NOTE = "You can fill empty fields and add media. Filled fields stay as recorded."
const val VIEW_ONLY_NOTE = "You can view this record. Your workshop access doesn't allow changes to it."
const val KEPT_AFTER_REFUSAL = "Your entries and media are still here. Fix it and save again."

fun fillsOnly(editAccess: String?): Boolean = editAccess == EDIT_ACCESS_FILL_EMPTY

fun viewOnly(editAccess: String?): Boolean = editAccess == EDIT_ACCESS_NONE

fun fieldLocked(editAccess: String?, stored: String?): Boolean =
    fillsOnly(editAccess) && !stored.isNullOrBlank()

fun editAccessNote(editAccess: String?): String? = when (editAccess) {
    EDIT_ACCESS_FILL_EMPTY -> FILL_EMPTY_NOTE
    EDIT_ACCESS_NONE -> VIEW_ONLY_NOTE
    else -> null
}

fun isEditRefusal(code: Int): Boolean = code == 403 || code == 409 || code == 422

fun submissionRefusalReason(canSubmit: Boolean, accessLevel: String?, requestStatus: String?): String? {
    if (canSubmit) return null
    return when (requestStatus?.trim()?.uppercase(Locale.ROOT)) {
        "PENDING" -> "Your access request for this workshop is waiting on an admin."
        "DENIED" -> "Your access request for this workshop was denied. Ask an admin to reconsider."
        "REVOKED" -> "Your access to this workshop was revoked. Request access again if you need it."
        else -> if (accessLevel?.trim()?.uppercase(Locale.ROOT) == "VIEW") {
            "Your access to this workshop is View only. Ask an admin to raise it to Contribute."
        } else {
            "You're not assigned to this workshop. Ask an admin to assign you."
        }
    }
}

fun WorkshopSubmissionCheckDto.refusalReason(): String? =
    submissionRefusalReason(canSubmit, accessLevel, requestStatus)
