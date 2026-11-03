package com.fieldrepository.app.data

object PossibleDuplicates {
    const val SHOW_THRESHOLD = 0.60

    const val SHOWN_MAX = 3

    const val ACKNOWLEDGED_MAX = 20

    fun toShow(candidates: List<DuplicateCandidateDto>, acknowledged: Set<String>): List<DuplicateCandidateDto> =
        candidates
            .filter { it.score >= SHOW_THRESHOLD && it.artisan.id !in acknowledged }
            .sortedByDescending { it.score }
            .take(SHOWN_MAX)

    fun acknowledged(ids: Collection<String>): List<String> = ids.distinct().take(ACKNOWLEDGED_MAX)

    fun rowLine(candidate: DuplicateCandidateDto): String =
        listOfNotNull(
            candidate.artisan.name.trim().ifBlank { null },
            candidate.artisan.place?.trim()?.ifBlank { null },
            candidate.artisan.craft?.trim()?.ifBlank { null }
        ).joinToString(" · ")
}
