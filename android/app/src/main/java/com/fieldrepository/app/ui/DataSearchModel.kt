package com.fieldrepository.app.ui

import com.fieldrepository.app.data.SearchResultsDto
import com.fieldrepository.app.data.UNIFIED_SEARCH_TYPES
import com.fieldrepository.app.data.UnifiedAlsoInDto
import com.fieldrepository.app.data.UnifiedRemoteDto
import com.fieldrepository.app.data.UnifiedSearchGroupDto
import com.fieldrepository.app.data.UnifiedSearchItemDto
import com.fieldrepository.app.data.UnifiedSearchResponse
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DataSearchPill(val type: String?, val label: String, val count: Int)

data class DataSearchHit(
    val type: String,
    val id: String,
    val title: String,
    val subtitle: String?,
    val snippet: String?,
    val highlights: List<IntRange>,
    val local: Boolean,
    val portal: String,
    val startMs: Long?,
    val openUrl: String?,
    val recordDate: String?,
    val alsoIn: List<UnifiedAlsoInDto>,
) {
    val key: String get() = "$portal|$type|$id"
    val inBoth: Boolean get() = alsoIn.isNotEmpty()
}

object DataSearchModel {
    const val PREVIEW = 5
    const val PAGE = 20
    const val HERE = "fieldRepository"
    const val OTHER = "designer"
    const val HERE_LABEL = "Field Repository"
    const val OTHER_LABEL = "Design Workshop"

    private val singular = mapOf(
        "artisans" to "Artisan",
        "crafts" to "Craft",
        "clusters" to "Cluster",
        "workshops" to "Workshop",
        "designWorkshops" to "Design workshop",
        "interviews" to "Interview",
        "transcripts" to "Transcript",
        "reports" to "Report",
        "products" to "Product",
        "tools" to "Tool",
        "processes" to "Process",
        "media" to "File",
    )

    private val locatable = mapOf(
        "artisans" to "artisan",
        "crafts" to "craft",
        "workshops" to "workshop",
        "interviews" to "interview",
        "transcripts" to "media",
        "products" to "product",
        "tools" to "tool",
        "processes" to "process",
        "media" to "media",
    )

    fun portalName(portal: String?): String = if (portal == OTHER) OTHER_LABEL else HERE_LABEL

    fun locateType(type: String): String? = locatable[type]

    fun typeLabel(type: String): String = singular[type] ?: type.replaceFirstChar { it.uppercase() }

    fun groups(response: UnifiedSearchResponse): List<UnifiedSearchGroupDto> =
        UNIFIED_SEARCH_TYPES.mapNotNull { type -> response.groups.firstOrNull { it.type == type } }

    fun pills(response: UnifiedSearchResponse): List<DataSearchPill> {
        val known = groups(response).filter { it.total > 0 }
        if (known.isEmpty()) return emptyList()
        return listOf(DataSearchPill(null, "All", known.sumOf { it.total })) + known.map { DataSearchPill(it.type, it.label.ifBlank { typeLabel(it.type) }, it.total) }
    }

    fun portalCounts(response: UnifiedSearchResponse): Pair<Int, Int> {
        val known = groups(response)
        return known.sumOf { it.localTotal } to known.sumOf { it.remoteTotal }
    }

    fun hits(response: UnifiedSearchResponse, type: String? = null): List<DataSearchHit> =
        groups(response).filter { type == null || it.type == type }.flatMap { group -> group.items.filter { it.id.isNotBlank() }.map { hit(it, group.type) } }

    fun hit(item: UnifiedSearchItemDto, groupType: String = item.type): DataSearchHit {
        val snippet = item.snippet?.trim()?.takeIf { it.isNotEmpty() }
        val length = snippet?.length ?: 0
        val ranges = item.highlights.mapNotNull { pair ->
            val start = pair.getOrNull(0) ?: return@mapNotNull null
            val end = pair.getOrNull(1) ?: return@mapNotNull null
            val from = start.coerceIn(0, length)
            val to = end.coerceIn(0, length)
            if (to > from) from until to else null
        }
        return DataSearchHit(
            type = item.type.ifBlank { groupType },
            id = item.id,
            title = item.title.ifBlank { item.id },
            subtitle = item.subtitle?.takeIf { it.isNotBlank() } ?: item.place?.takeIf { it.isNotBlank() },
            snippet = snippet,
            highlights = ranges,
            local = item.local,
            portal = if (item.local) HERE else item.portal.ifBlank { OTHER },
            startMs = item.startMs?.takeIf { it >= 0 },
            openUrl = item.openUrl?.takeIf { it.isNotBlank() },
            recordDate = item.recordDate,
            alsoIn = item.alsoIn.filter { it.id.isNotBlank() },
        )
    }

    fun more(previous: List<DataSearchHit>, next: List<DataSearchHit>): List<DataSearchHit> {
        val seen = previous.map { it.key }.toHashSet()
        return previous + next.filter { seen.add(it.key) }
    }

    fun headline(hit: DataSearchHit): String {
        val parts = ArrayList<String>()
        parts += if (hit.inBoth) "${typeLabel(hit.type)} · in both" else typeLabel(hit.type)
        hit.startMs?.let { parts += clock(it) }
        day(hit.recordDate)?.let { parts += it }
        return parts.joinToString(" · ")
    }

    fun bothLine(hit: DataSearchHit): String? {
        if (!hit.inBoth) return null
        val other = hit.alsoIn.first()
        return listOfNotNull("Same person in both portals", other.subtitle?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun day(iso: String?): String? = runCatching {
        val raw = iso?.takeIf { it.length >= 10 } ?: return null
        DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(LocalDate.parse(raw.substring(0, 10)))
    }.getOrNull()

    fun searchesOther(remote: UnifiedRemoteDto): Boolean = remote.status == "ok" || remote.status == "unavailable" || remote.status == "filteredOut"

    fun legacy(query: String, results: SearchResultsDto): UnifiedSearchResponse {
        fun item(type: String, id: String, title: String, subtitle: String?) =
            UnifiedSearchItemDto(portal = HERE, local = true, type = type, id = id, title = title, subtitle = subtitle?.takeIf { it.isNotBlank() })
        fun group(type: String, label: String, total: Int, items: List<UnifiedSearchItemDto>) =
            UnifiedSearchGroupDto(type = type, label = label, total = maxOf(total, items.size), localTotal = maxOf(total, items.size), items = items)
        val groups = listOf(
            group("artisans", "Artisans", results.totals.artisans, results.artisans.map { item("artisans", it.id, it.name, it.place) }),
            group("workshops", "Workshops", results.totals.workshops, results.workshops.map { item("workshops", it.id, it.title, it.place) }),
            group("products", "Products", results.totals.products, results.products.map { item("products", it.id, it.productName, it.artisanName) }),
            group("tools", "Tools", results.totals.tools, results.tools.map { item("tools", it.id, it.toolkitName, it.artisanName) }),
            group("media", "Files", results.totals.media, results.media.map { item("media", it.id, it.originalFilename, it.caption ?: it.mediaType) }),
        ).filter { it.total > 0 }
        return UnifiedSearchResponse(query = query, mode = "text", groups = groups)
    }
}
