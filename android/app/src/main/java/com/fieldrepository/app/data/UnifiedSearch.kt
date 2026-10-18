package com.fieldrepository.app.data

import kotlinx.serialization.Serializable

/**
 * One search across both portals, the wire half (docs/SEARCH.md). The server half is
 * `backend/app/api/routes/search.py` (`GET /search/unified`, `GET /search/remote/{type}/{id}`); the
 * panel that draws it is `ui/AppSearch.kt` through `ui/AppSearchSources.kt`.
 *
 * While the server's UNIFIED_SEARCH_ENABLED is off the route answers 404 and the panel keeps its
 * older fan-out over `GET /search` and the list routes.
 */
@Serializable
data class UnifiedSearchItemDto(
    val portal: String = "fieldRepository",
    val local: Boolean = true,
    val type: String = "",
    val id: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val snippet: String? = null,
    val highlights: List<List<Int>> = emptyList(),
    val place: String? = null,
    val craftCodes: List<String> = emptyList(),
    val recordDate: String? = null,
    val href: String? = null,
    val openUrl: String? = null,
    val score: Double = 0.0,
)

@Serializable
data class UnifiedSearchGroupDto(
    val type: String = "",
    val label: String = "",
    val total: Int = 0,
    val localTotal: Int = 0,
    val remoteTotal: Int = 0,
    val items: List<UnifiedSearchItemDto> = emptyList(),
)

@Serializable
data class UnifiedRemoteDto(
    val portal: String = "designer",
    val label: String = "Designer portal",
    val status: String = "off",
)

@Serializable
data class UnifiedSearchResponse(
    val query: String = "",
    val mode: String = "text",
    val groups: List<UnifiedSearchGroupDto> = emptyList(),
    val remote: UnifiedRemoteDto = UnifiedRemoteDto(),
    val typesRefused: List<String> = emptyList(),
)

@Serializable
data class RemoteRecordFieldDto(val label: String = "", val value: String = "")

/** One record from the other portal, as a read-only card. */
@Serializable
data class RemoteRecord(
    val portal: String = "designer",
    val type: String = "",
    val id: String = "",
    val title: String = "",
    val subtitle: String? = null,
    val fields: List<RemoteRecordFieldDto> = emptyList(),
    val snippet: String? = null,
    val recordDate: String? = null,
    val openUrl: String? = null,
)

/** The search vocabulary, in group order — the server's `cross_portal.SEARCH_TYPES`. */
val UNIFIED_SEARCH_TYPES: List<String> = listOf(
    "artisans", "crafts", "clusters", "workshops", "designWorkshops", "interviews",
    "transcripts", "reports", "products", "tools", "processes", "media",
)

/** The portal a remote item came from, in words, for the chip and the card's button. */
fun portalLabel(portal: String?): String = when (portal) {
    "fieldRepository" -> "Field Repository"
    "designer" -> "Designer portal"
    else -> "the other portal"
}

/** "Open in Designer portal". */
fun openInLabel(portal: String?): String = "Open in ${portalLabel(portal)}"

/** Items the panel can draw: known types and an id, in the server's order. Unknown types are dropped. */
fun UnifiedSearchResponse.knownItems(): List<UnifiedSearchItemDto> =
    UNIFIED_SEARCH_TYPES.flatMap { type ->
        groups.filter { it.type == type }.flatMap { group -> group.items.filter { it.id.isNotBlank() } }
    }

/** The card's rows: the other portal's own fields, empty ones dropped, then the record date. */
fun RemoteRecord.cardRows(formatDate: (String) -> String): List<Pair<String, String>> =
    fields.filter { it.label.isNotBlank() && it.value.isNotBlank() }.map { it.label to it.value } +
        listOfNotNull(recordDate?.takeIf { it.isNotBlank() }?.let { "Recorded" to formatDate(it) })
