package com.fieldrepository.app.ui

internal const val RECORD_UPDATE_LABEL = "Update"

internal val UPDATABLE_RECORD_TYPES = setOf("artisan", "product", "process", "tool", "craft", "workshop", "questionnaire")

internal fun recordUpdateAllowed(
    recordType: String,
    canManageCrafts: Boolean,
    canManageWorkshops: Boolean
): Boolean = when (val type = recordType.trim().lowercase().let { if (it == "interview") "questionnaire" else it }) {
    "craft" -> canManageCrafts
    "workshop" -> canManageWorkshops
    else -> type in UPDATABLE_RECORD_TYPES
}

internal fun recordViewTitle(label: String): String = label

internal fun recordUpdateTitle(label: String): String = "$RECORD_UPDATE_LABEL ${label.lowercase()}"
