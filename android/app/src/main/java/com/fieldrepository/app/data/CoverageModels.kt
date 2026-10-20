package com.fieldrepository.app.data

import kotlinx.serialization.Serializable

/*
 * THE COVERAGE MAP'S WIRE SHAPES — `GET map/coverage` and `GET map/coverage/units/{state}/{district}/records`,
 * field for field the web's `components/map/coverage/coverageTypes.ts`.
 *
 * Shared with the Field Repository byte for byte apart from the package line. The designer portal
 * sends a few fields the Field Repository never does (design and planned workshops, the peer row);
 * they default here, and every unknown key is ignored by the client's decoder, so one file serves both.
 */

/** How a unit's outline is drawn: its own polygon, the parent district's, or its state's. */
object CoveragePolygon {
    const val OWN = "OWN"
    const val PARENT = "PARENT"
    const val STATE = "STATE"
}

object CoverageLevel {
    const val DISTRICT = "DISTRICT"
    const val STATE = "STATE"
}

/** What the choropleth colours by. `records` is read as [ALL]: it was the designer portal's old name. */
object CoverageMetric {
    const val WORKSHOPS = "workshops"
    const val ARTISANS = "artisans"
    const val ALL = "all"

    /** The labels, in the web's order: the same words as the web's "Colour by" control. */
    val OPTIONS: List<Pair<String, String>> = listOf(
        WORKSHOPS to "Workshops",
        ARTISANS to "Artisans",
        ALL to "All records"
    )

    fun normalise(value: String?): String = when (value) {
        "records", ALL -> ALL
        ARTISANS -> ARTISANS
        else -> WORKSHOPS
    }

    fun label(value: String): String = OPTIONS.firstOrNull { it.first == value }?.second ?: "Workshops"
}

@Serializable
data class CoverageCounts(
    val workshops: Int = 0,
    val artisans: Int = 0,
    val crafts: Int = 0,
    val products: Int = 0,
    val tools: Int = 0,
    val media: Int = 0,
    /** Designer portal only. */
    val designWorkshops: Int = 0,
    val plannedWorkshops: Int = 0
)

/** Designer portal only: the Field Repository's figures for the same unit, never added to ours. */
@Serializable
data class CoveragePeerCounts(
    val workshops: Int = 0,
    val artisans: Int = 0,
    val products: Int = 0,
    val tools: Int = 0,
    val media: Int = 0
)

@Serializable
data class CoverageUnit(
    val stateLgd: Int = 0,
    val districtLgd: Int? = null,
    val state: String = "",
    val district: String? = null,
    val polygon: String = CoveragePolygon.OWN,
    val parentLgd: Int? = null,
    val counts: CoverageCounts = CoverageCounts(),
    val peer: CoveragePeerCounts? = null,
    val craftCodes: List<String> = emptyList(),
    val clusters: List<String> = emptyList()
) {
    /** "Barmer, Rajasthan", or the state alone for a state-level unit. */
    val title: String get() = if (district.isNullOrBlank()) state else "$district, $state"
}

@Serializable
data class CoverageMarker(
    /** DESIGN_WORKSHOP | PLANNED | CLUSTER | WORKSHOP. */
    val kind: String = "WORKSHOP",
    val id: String = "",
    val title: String = "",
    val clusterName: String? = null,
    val craftCode: String? = null,
    val stateLgd: Int = 0,
    val districtLgd: Int? = null,
    val lon: Double = 0.0,
    val lat: Double = 0.0,
    val precision: String = "DISTRICT_CENTROID",
    val href: String = ""
)

@Serializable
data class CoverageNamedCode(val lgd: Int? = null, val name: String = "")

@Serializable
data class CoverageCraftCode(val code: String = "", val name: String = "")

@Serializable
data class CoverageGapState(
    val stateLgd: Int = 0,
    val state: String = "",
    val total: Int = 0,
    val without: List<CoverageNamedCode> = emptyList()
)

@Serializable
data class CoverageDistrictGaps(
    val count: Int = 0,
    val byState: List<CoverageGapState> = emptyList()
)

@Serializable
data class CoverageGaps(
    val craftsNeverDocumented: List<CoverageCraftCode> = emptyList(),
    val districtsWithoutWorkshop: CoverageDistrictGaps = CoverageDistrictGaps(),
    val statesWithoutWorkshop: List<CoverageNamedCode> = emptyList()
)

@Serializable
data class CoverageScheme(val value: String = "", val label: String = "")

@Serializable
data class CoverageYear(val fy: Int = 0, val label: String = "")

@Serializable
data class CoverageCatalogue(
    val crafts: List<CoverageCraftCode> = emptyList(),
    val schemes: List<CoverageScheme> = emptyList(),
    val years: List<CoverageYear> = emptyList()
)

/** The filters the server applied, echoed back. */
@Serializable
data class CoverageFilters(
    val level: String = CoverageLevel.DISTRICT,
    val craftCode: List<String> = emptyList(),
    val scheme: List<String> = emptyList(),
    val fy: List<Int> = emptyList(),
    val stateLgd: Int? = null
)

@Serializable
data class CoverageUnmatched(val count: Int = 0, val unplaced: Int? = null)

/** Designer portal only: whether the Field Repository's figures were asked for and reached. */
@Serializable
data class CoveragePeerStatus(val status: String = "OFF", val asOf: String? = null)

@Serializable
data class CoverageResponse(
    val asOf: String = "",
    val filters: CoverageFilters = CoverageFilters(),
    val catalogue: CoverageCatalogue = CoverageCatalogue(),
    val units: List<CoverageUnit> = emptyList(),
    val markers: List<CoverageMarker> = emptyList(),
    val markersTruncated: Boolean = false,
    val gaps: CoverageGaps = CoverageGaps(),
    val unmatched: CoverageUnmatched = CoverageUnmatched(),
    val peer: CoveragePeerStatus? = null,
    val truncated: Boolean = false
)

@Serializable
data class CoverageRecordStub(
    val id: String = "",
    val title: String = "",
    val href: String = "",
    val craftCode: String? = null,
    val fy: Int? = null
)

@Serializable
data class CoverageBucketPage(
    val total: Int = 0,
    val items: List<CoverageRecordStub> = emptyList(),
    val nextCursor: String? = null
)

@Serializable
data class CoverageUnitRecords(
    val unit: CoverageUnit = CoverageUnit(),
    /** Keyed by bucket: designWorkshops, plannedWorkshops, workshops, artisans, products, tools, media. */
    val buckets: Map<String, CoverageBucketPage> = emptyMap(),
    val peerHref: String? = null
)

/** The buckets a unit's records come in, in the order and with the words the web prints. */
val COVERAGE_BUCKETS: List<Pair<String, String>> = listOf(
    "designWorkshops" to "Design workshops",
    "plannedWorkshops" to "Planned workshops",
    "workshops" to "Workshops",
    "artisans" to "Artisans",
    "products" to "Products",
    "tools" to "Tools",
    "media" to "Media"
)

/** The filters a coverage view is drawn with — the web's `CoverageQuery`. */
@Serializable
data class CoverageQuery(
    val level: String = CoverageLevel.DISTRICT,
    val craftCode: List<String> = emptyList(),
    val scheme: List<String> = emptyList(),
    val fy: List<Int> = emptyList(),
    val stateLgd: Int? = null,
    /** Designer portal only: the Field Repository's figures beside ours. */
    val peer: Boolean = false
) {
    /**
     * The query string, as the web's `coverageParams` builds it: arrays comma-joined, defaults left
     * out. [forUnit] drops the state scope and the peer switch, as the unit-records route wants.
     */
    fun params(forUnit: Boolean = false): Map<String, String> = buildMap {
        if (level != CoverageLevel.DISTRICT) put("level", level)
        if (craftCode.isNotEmpty()) put("craftCode", craftCode.joinToString(","))
        if (scheme.isNotEmpty()) put("scheme", scheme.joinToString(","))
        if (fy.isNotEmpty()) put("fy", fy.joinToString(","))
        if (!forUnit) {
            stateLgd?.let { put("stateLgd", it.toString()) }
            if (peer) put("peer", "1")
        }
    }
}
