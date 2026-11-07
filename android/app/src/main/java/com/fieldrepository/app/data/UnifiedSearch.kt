package com.fieldrepository.app.data

import kotlinx.serialization.Serializable

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
    val startMs: Long? = null,
    val mediaType: String? = null,
    val alsoIn: kotlinx.serialization.json.JsonElement? = null,
    val locatable: Boolean? = null,
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
    val suggestion: String? = null,
    val suggestionTotal: Int = 0,
    val type: String? = null,
    val page: Int? = null,
)

@Serializable
data class RemoteRecordFieldDto(val label: String = "", val value: String = "")

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

val UNIFIED_SEARCH_TYPES: List<String> = listOf(
    "artisans", "crafts", "clusters", "workshops", "designWorkshops", "interviews",
    "transcripts", "reports", "products", "tools", "processes", "media",
)

fun portalLabel(portal: String?): String = when (portal) {
    "fieldRepository" -> "Field Repository"
    "designer" -> "Designer portal"
    else -> "the other portal"
}

fun openInLabel(portal: String?): String = "Open in ${portalLabel(portal)}"

fun UnifiedSearchResponse.knownItems(): List<UnifiedSearchItemDto> =
    UNIFIED_SEARCH_TYPES.flatMap { type ->
        groups.filter { it.type == type }.flatMap { group -> group.items.filter { it.id.isNotBlank() } }
    }

fun RemoteRecord.cardRows(formatDate: (String) -> String): List<Pair<String, String>> =
    fields.filter { it.label.isNotBlank() && it.value.isNotBlank() }.map { it.label to it.value } +
        listOfNotNull(recordDate?.takeIf { it.isNotBlank() }?.let { "Recorded" to formatDate(it) })
