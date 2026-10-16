package com.fieldrepository.app.ui

/**
 * OPENING A RECORD READS IT; ONLY "UPDATE" EDITS IT — the handset half of the owner's 2026-10-16
 * ruling, and the twin of the web's `frontend/lib/recordView.ts`.
 *
 * A tap anywhere on a record's card or row opens the record READ-ONLY (`Screen.View` in
 * `MainActivity.kt`, which draws `ViewDataDetail`). The edit form is reached only through the
 * "Update" button on that view or on the card. Before this, a recent-submission row, a recent
 * artisan, a search hit, a map pin and a My Activity row all opened the EDIT form straight away, so
 * a researcher who only wanted to read a record landed in a form with a Save button under their
 * thumb.
 *
 * These are the pure decisions, so `RecordOpenTest` can pin them without a device. The routing
 * itself is file-private to `MainActivity.kt` and is pinned there by reading the source.
 */

/** The one word for "change this record", on every card, view and screen title. Never "Edit". */
internal const val RECORD_UPDATE_LABEL = "Update"

/** The record types with an edit form. Media has none: a file is viewed, never updated. */
internal val UPDATABLE_RECORD_TYPES = setOf("artisan", "product", "process", "tool", "craft", "workshop", "questionnaire")

/**
 * Whether this account sees the Update button for a record of [recordType]. Mirrors the server's
 * gate on the PATCH and adds nothing, exactly as the web's `canUpdateRecord` does:
 *
 *  - crafts and workshops: `require_craft_manager` / `require_workshop_manager`, Professor and up;
 *  - every other record: any signed-in account. Their PATCH takes `get_current_user` and decides
 *    field by field in `guard_record_edit` — a contributor may fill a blank field but not overwrite
 *    one — which is why these forms have always been reachable by everybody. Hiding Update would take
 *    that "fill in what is missing" path away from the tiers that exist for it.
 */
internal fun recordUpdateAllowed(
    recordType: String,
    canManageCrafts: Boolean,
    canManageWorkshops: Boolean
): Boolean = when (val type = recordType.trim().lowercase().let { if (it == "interview") "questionnaire" else it }) {
    "craft" -> canManageCrafts
    "workshop" -> canManageWorkshops
    else -> type in UPDATABLE_RECORD_TYPES
}

/** The view's screen title: the record type's own label, with nothing claiming it is being edited. */
internal fun recordViewTitle(label: String): String = label

/** The edit screen's title, in the same word as the button that opened it. */
internal fun recordUpdateTitle(label: String): String = "$RECORD_UPDATE_LABEL ${label.lowercase()}"
