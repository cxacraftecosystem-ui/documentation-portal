package com.fieldrepository.app.data

/**
 * LIKELY DUPLICATES OF AN ARTISAN BEING SAVED — the handset's half of `POST /artisan-duplicates/check`
 * and of the 409 `artisan_possible_duplicate` hold on `POST /artisans` (backend
 * `services/artisan_duplicates.py`).
 *
 * THE SERVER IS THE ONLY SCORER. Nothing here matches names; a score is read only to decide whether
 * to draw a row. An offline create is checked when it syncs, never on the phone. Pure, so
 * `OutboxPossibleDuplicateTest` holds every decision on the JVM.
 */
object PossibleDuplicates {
    /** Below this the server does not return a candidate; the form draws nothing under it either. */
    const val SHOW_THRESHOLD = 0.60

    /** The rows the sheet draws at most. */
    const val SHOWN_MAX = 3

    /** `ArtisanCreate.duplicateAcknowledged` takes at most 20 ids. */
    const val ACKNOWLEDGED_MAX = 20

    /** The candidates the sheet shows: at or above the threshold, not already set aside. */
    fun toShow(candidates: List<DuplicateCandidateDto>, acknowledged: Set<String>): List<DuplicateCandidateDto> =
        candidates
            .filter { it.score >= SHOW_THRESHOLD && it.artisan.id !in acknowledged }
            .sortedByDescending { it.score }
            .take(SHOWN_MAX)

    /** What a create sends: the ids set aside as "Different person", or `[]` when nothing was shown. */
    fun acknowledged(ids: Collection<String>): List<String> = ids.distinct().take(ACKNOWLEDGED_MAX)

    /** One line per candidate: name · place · craft. */
    fun rowLine(candidate: DuplicateCandidateDto): String =
        listOfNotNull(
            candidate.artisan.name.trim().ifBlank { null },
            candidate.artisan.place?.trim()?.ifBlank { null },
            candidate.artisan.craft?.trim()?.ifBlank { null }
        ).joinToString(" · ")
}
