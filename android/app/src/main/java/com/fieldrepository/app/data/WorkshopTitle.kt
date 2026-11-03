package com.fieldrepository.app.data

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

fun workshopCreateTitle(title: String?, draftCreatedAt: String?): String =
    title?.trim()?.takeIf { it.isNotEmpty() }
        ?: Naming.sessionTitle(entity = "Workshop", date = draftCreatedAt?.takeIf { it.isNotBlank() })
