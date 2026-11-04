package com.fieldrepository.app.data

const val ARTISAN_FORM_CONTRACT = 2

const val EXPERIENCE_NOT_STATED = "Not stated"

val EXPERIENCE_MONTH_OPTIONS: List<Int> = (0..11).toList()

fun experienceMonthsLabel(months: Int): String = if (months == 1) "1 month" else "$months months"

fun missingArtisanDatesSentence(dateOfBirth: String?, craftStartDate: String?): String? {
    val missing = buildList {
        if (dateOfBirth.isNullOrBlank()) add("date of birth")
        if (craftStartDate.isNullOrBlank()) add("craft start date")
    }
    return if (missing.isEmpty()) null else "Enter the ${missing.joinToString(" and the ")}."
}
