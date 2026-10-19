package com.fieldrepository.app.data

/**
 * The title a workshop is shown under: the one its people typed, never rewritten, or — only when it
 * is blank — the generated default `Workshop_Date_Place_Craft_Code` (NAMING 2.4), in place of the old
 * "Untitled workshop" / "Untitled design workshop" every blank workshop shared. The same rule as the
 * web's `lib/workshopTitle.ts`; identical in both portals apart from the package line.
 */
fun workshopDisplayTitle(
    title: String?,
    date: String? = null,
    place: String? = null,
    workshopCode: String? = null,
    clusterName: String? = null,
    district: String? = null,
    state: String? = null,
    craftName: String? = null,
): String {
    val typed = title?.trim().orEmpty()
    if (typed.isNotEmpty()) return typed
    val where = place?.trim()?.takeIf { it.isNotEmpty() }
        ?: Naming.placeField(clusterName, district, state, asciiOnly = false)
    return Naming.sessionTitle(
        entity = "Workshop",
        date = date?.takeIf { it.isNotBlank() },
        place = where,
        craft = craftName,
        subject = workshopCode,
    )
}

/**
 * THE TITLE A CREATE SENDS, and the one an interrupted create is looked up by: the typed title, or
 * `Workshop_<day the draft was started>`. Built only from what never changes after the draft
 * exists, so the lookup cannot miss the record its own POST made.
 */
fun workshopCreateTitle(title: String?, draftCreatedAt: String?): String =
    title?.trim()?.takeIf { it.isNotEmpty() }
        ?: Naming.sessionTitle(entity = "Workshop", date = draftCreatedAt?.takeIf { it.isNotBlank() })
