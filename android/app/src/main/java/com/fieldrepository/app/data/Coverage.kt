package com.fieldrepository.app.data

import kotlinx.serialization.Serializable

@Serializable
data class CoverageDto(
    val units: List<CoverageUnitDto> = emptyList(),
    val gaps: CoverageGapsDto = CoverageGapsDto(),
    val truncated: Boolean = false,
)

@Serializable
data class CoverageUnitDto(
    val stateLgd: Int = 0,
    val districtLgd: Int? = null,
    val state: String? = null,
    val district: String? = null,
    val counts: CoverageCountsDto = CoverageCountsDto(),
)

@Serializable
data class CoverageCountsDto(
    val workshops: Int = 0,
    val artisans: Int = 0,
    val crafts: Int = 0,
    val products: Int = 0,
    val tools: Int = 0,
    val media: Int = 0,
) {
    val records: Int get() = workshops + artisans + products + tools + media
}

@Serializable
data class CoverageGapsDto(
    val craftsNeverDocumented: List<CoverageCraftDto> = emptyList(),
    val districtsWithoutWorkshop: CoverageDistrictGapsDto = CoverageDistrictGapsDto(),
    val statesWithoutWorkshop: List<CoverageNamedUnitDto> = emptyList(),
)

@Serializable
data class CoverageCraftDto(val code: String = "", val name: String = "")

@Serializable
data class CoverageDistrictGapsDto(
    val count: Int = 0,
    val byState: List<CoverageStateGapDto> = emptyList(),
)

@Serializable
data class CoverageStateGapDto(
    val stateLgd: Int = 0,
    val state: String = "",
    val total: Int = 0,
    val without: List<CoverageNamedUnitDto> = emptyList(),
)

@Serializable
data class CoverageNamedUnitDto(val lgd: Int? = null, val name: String = "")

data class CoverageStateRow(
    val stateLgd: Int,
    val name: String,
    val withWorkshop: Int,
    val total: Int?,
    val records: Int,
)

data class CoverageSummary(
    val states: List<CoverageStateRow>,
    val craftsNeverDocumented: Int,
    val districtsWithoutWorkshop: Int,
    val truncated: Boolean,
) {
    val isEmpty: Boolean get() = states.all { it.records == 0 && it.withWorkshop == 0 }
}

object CoverageCopy {
    fun districts(row: CoverageStateRow): String =
        if (row.total == null) "Every district has a workshop"
        else "${row.withWorkshop} of ${row.total} districts with a workshop"

    fun records(count: Int): String = if (count == 1) "1 record" else "$count records"

    fun crafts(count: Int): String = "Crafts never documented: $count"
}

fun coverageSummary(dto: CoverageDto): CoverageSummary {
    val records = dto.units.filter { it.districtLgd == null }.associate { it.stateLgd to it }
    val gaps = dto.gaps.districtsWithoutWorkshop.byState.associateBy { it.stateLgd }
    val rows = (records.keys + gaps.keys).map { code ->
        val gap = gaps[code]
        val unit = records[code]
        CoverageStateRow(
            stateLgd = code,
            name = gap?.state?.takeIf { it.isNotBlank() } ?: unit?.state.orEmpty(),
            withWorkshop = gap?.let { it.total - it.without.size } ?: 0,
            total = gap?.total,
            records = unit?.counts?.records ?: 0,
        )
    }
    val ordered = rows.sortedWith(
        compareByDescending<CoverageStateRow> { it.total == null }
            .thenByDescending { row -> row.total?.let { if (it == 0) 0.0 else row.withWorkshop.toDouble() / it } ?: 1.0 }
            .thenByDescending { it.records }
            .thenBy { it.name },
    )
    return CoverageSummary(
        states = ordered,
        craftsNeverDocumented = dto.gaps.craftsNeverDocumented.size,
        districtsWithoutWorkshop = dto.gaps.districtsWithoutWorkshop.count,
        truncated = dto.truncated,
    )
}

fun coverageSummary(response: CoverageResponse): CoverageSummary {
    val states = response.units.groupBy { it.stateLgd }.map { (stateLgd, units) ->
        CoverageUnitDto(
            stateLgd = stateLgd,
            state = units.firstNotNullOfOrNull { it.state.takeIf(String::isNotBlank) },
            counts = CoverageCountsDto(
                workshops = units.sumOf { it.counts.workshops },
                artisans = units.sumOf { it.counts.artisans },
                crafts = units.sumOf { it.counts.crafts },
                products = units.sumOf { it.counts.products },
                tools = units.sumOf { it.counts.tools },
                media = units.sumOf { it.counts.media },
            ),
        )
    }
    val gaps = response.gaps
    return coverageSummary(
        CoverageDto(
            units = states,
            gaps = CoverageGapsDto(
                craftsNeverDocumented = gaps.craftsNeverDocumented.map { CoverageCraftDto(it.code, it.name) },
                districtsWithoutWorkshop = CoverageDistrictGapsDto(
                    count = gaps.districtsWithoutWorkshop.count,
                    byState = gaps.districtsWithoutWorkshop.byState.map { row ->
                        CoverageStateGapDto(
                            stateLgd = row.stateLgd,
                            state = row.state,
                            total = row.total,
                            without = row.without.map { CoverageNamedUnitDto(it.lgd, it.name) },
                        )
                    },
                ),
                statesWithoutWorkshop = gaps.statesWithoutWorkshop.map { CoverageNamedUnitDto(it.lgd, it.name) },
            ),
            truncated = response.truncated,
        )
    )
}
