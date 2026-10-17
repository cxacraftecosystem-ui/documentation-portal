package com.fieldrepository.app.data

/*
 * THE ARTISAN FORM CONTRACT — the handset's half (owner decision, 2026-10-17).
 *
 * Both portals draw the same artisan form, on the web and here: Date of birth and Craft start date
 * are required on every new save, and experience is two optional boxes, years (0..90) and months
 * (0..11, "Not stated" when unanswered). `shared/artisan-form-contract.json` declares it once, and
 * this file, the server's `services/artisan_form_contract.py` and the web's
 * `lib/artisanFormContract.ts` all run its sentence vectors, in both repositories.
 *
 * [ARTISAN_FORM_CONTRACT] travels in the body ([ArtisanCreateRequest.formContract]), so the outbox
 * stores it with the entry: an entry queued by an older build has none and replays as the old shape
 * the server still accepts.
 */

/** The value the form sends in `formContract`. The server requires both dates from 2 on. */
const val ARTISAN_FORM_CONTRACT = 2

/** The months box's empty option. */
const val EXPERIENCE_NOT_STATED = "Not stated"

/** 0..11: the months are a remainder on top of the years (`CHECK ("experienceMonths" BETWEEN 0 AND 11)`). */
val EXPERIENCE_MONTH_OPTIONS: List<Int> = (0..11).toList()

/** "1 month", "6 months" — the rows of the months box. */
fun experienceMonthsLabel(months: Int): String = if (months == 1) "1 month" else "$months months"

/**
 * "Enter the date of birth and the craft start date." for whichever of the two is empty, or null
 * when both are given. The server's refusal is the same sentence, word for word.
 */
fun missingArtisanDatesSentence(dateOfBirth: String?, craftStartDate: String?): String? {
    val missing = buildList {
        if (dateOfBirth.isNullOrBlank()) add("date of birth")
        if (craftStartDate.isNullOrBlank()) add("craft start date")
    }
    return if (missing.isEmpty()) null else "Enter the ${missing.joinToString(" and the ")}."
}
