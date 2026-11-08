package com.fieldrepository.app.explorer.data

import kotlinx.serialization.Serializable

enum class ExplorerFilterKey(val param: String, val facet: String, val label: String) {
    PORTAL("portals", "portal", "Portal"),
    WORKSHOP("workshopIds", "workshop", "Workshop"),
    DESIGN_WORKSHOP("designWorkshopIds", "designWorkshop", "Design workshop"),
    CRAFT("craftIds", "craft", "Craft"),
    ARTISAN("artisanIds", "artisan", "Artisan"),
    MEDIA_TYPE("mediaTypes", "mediaType", "Media type"),
    STATUS("statuses", "status", "Review status"),
    RECORDED_BY("recordedByIds", "recordedBy", "Recorded by"),
    LANGUAGE("languages", "language", "Language"),
}

@Serializable
data class ExplorerFilterOption(val id: String = "", val label: String = "", val count: Int? = null)

@Serializable
data class ExplorerFilterChip(val key: String, val value: String, val label: String)

@Serializable
data class ExplorerFilters(
    val values: Map<String, List<String>> = emptyMap(),
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val labels: Map<String, String> = emptyMap(),
) {
    operator fun get(key: ExplorerFilterKey): Set<String> = values[key.name].orEmpty().toSet()

    fun with(key: ExplorerFilterKey, picked: Collection<String>, names: Map<String, String> = emptyMap()): ExplorerFilters {
        val next = values.toMutableMap()
        val ordered = (if (key == ExplorerFilterKey.PORTAL) ExplorerFacetKeys.portalSelection(picked.toSet()) else picked).distinct()
        if (ordered.isEmpty()) next.remove(key.name) else next[key.name] = ordered
        return copy(values = next).withLabels(names)
    }

    fun withLabels(names: Map<String, String>): ExplorerFilters {
        val chosen = values.values.flatten().toSet()
        val kept = (labels + names.filterKeys { it in chosen }).filterKeys { it in chosen }
        return if (kept == labels) this else copy(labels = kept)
    }

    fun dates(from: String?, to: String?): ExplorerFilters {
        val a = from?.takeIf { it.isNotBlank() }
        val b = to?.takeIf { it.isNotBlank() }
        return if (a != null && b != null && b < a) copy(dateFrom = b, dateTo = a) else copy(dateFrom = a, dateTo = b)
    }

    fun without(chip: ExplorerFilterChip): ExplorerFilters = when (chip.key) {
        DATES -> copy(dateFrom = null, dateTo = null)
        else -> ExplorerFilterKey.entries.firstOrNull { it.name == chip.key }?.let { key -> with(key, this[key] - chip.value) } ?: this
    }

    fun cleared(): ExplorerFilters = ExplorerFilters()

    val portals: Set<String> get() = this[ExplorerFilterKey.PORTAL]

    fun picked(key: ExplorerFilterKey): Set<String> = if (key == ExplorerFilterKey.PORTAL && portals.isEmpty()) setOf(THIS_PORTAL) else this[key]

    val includesThis: Boolean get() = portals.isEmpty() || THIS_PORTAL in portals

    val includesOther: Boolean get() = OTHER_PORTAL in portals

    val narrowing: Boolean
        get() = dateFrom != null || dateTo != null || ExplorerFilterKey.entries.any { it != ExplorerFilterKey.PORTAL && this[it].isNotEmpty() }

    val active: Boolean get() = narrowing || includesOther

    val count: Int get() = chips().size

    fun labelOf(key: ExplorerFilterKey, value: String): String = when (key) {
        ExplorerFilterKey.PORTAL -> portalLabel(value)
        ExplorerFilterKey.MEDIA_TYPE -> labels[value] ?: mediaLabel(value)
        else -> labels[value] ?: value
    }

    fun chips(): List<ExplorerFilterChip> {
        val out = ArrayList<ExplorerFilterChip>()
        ExplorerFilterKey.entries.forEach { key ->
            if (key == ExplorerFilterKey.PORTAL && !includesOther) return@forEach
            values[key.name].orEmpty().forEach { value -> out += ExplorerFilterChip(key.name, value, "${key.label}: ${labelOf(key, value)}") }
        }
        if (dateFrom != null || dateTo != null) out += ExplorerFilterChip(DATES, "", "Date: ${rangeLabel(dateFrom, dateTo)}")
        return out
    }

    fun summary(): String = chips().joinToString(" · ") { it.label }

    fun query(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ExplorerFilterKey.entries.forEach { key ->
            val picked = values[key.name].orEmpty()
            if (key == ExplorerFilterKey.PORTAL) return@forEach
            if (picked.isNotEmpty()) out[key.param] = picked.joinToString(",")
        }
        dateFrom?.let { out[DATE_FROM] = it }
        dateTo?.let { out[DATE_TO] = it }
        return out
    }

    fun portalParam(): String = when {
        includesThis && includesOther -> "$THIS_PORTAL,$OTHER_PORTAL"
        includesOther -> OTHER_PORTAL
        else -> THIS_PORTAL
    }

    fun key(): String = (query().entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" } + "|" + portalParam())

    companion object {
        const val DATES = "DATES"
        const val DATE_FROM = "dateFrom"
        const val DATE_TO = "dateTo"
        const val THIS_PORTAL = "this"
        const val OTHER_PORTAL = "other"

        fun portalLabel(value: String): String = if (value == OTHER_PORTAL) "Other portal" else "This portal"

        fun mediaLabel(value: String): String = value.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

        fun rangeLabel(from: String?, to: String?): String = when {
            from != null && to != null && from == to -> day(from)
            from != null && to != null -> "${day(from)} – ${day(to)}"
            from != null -> "from ${day(from)}"
            to != null -> "until ${day(to)}"
            else -> ""
        }

        private fun day(iso: String): String = runCatching {
            java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))
        }.getOrDefault(iso)
    }
}

object ExplorerViewFilters {
    fun of(saved: Map<String, String>, current: ExplorerFilters): ExplorerFilters {
        var next = ExplorerFilters()
        ExplorerFilterKey.entries.forEach { key ->
            saved[key.param]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { next = next.with(key, it) }
        }
        saved["status"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "ALL" && next[ExplorerFilterKey.STATUS].isEmpty() }?.let { next = next.with(ExplorerFilterKey.STATUS, listOf(it)) }
        return next.dates(saved[ExplorerFilters.DATE_FROM], saved[ExplorerFilters.DATE_TO]).withLabels(current.labels)
    }

    fun toSaved(filters: ExplorerFilters): Map<String, String> {
        val out = LinkedHashMap(filters.query())
        if (filters.includesOther) out[ExplorerFilterKey.PORTAL.param] = filters.portalParam()
        return out
    }
}
