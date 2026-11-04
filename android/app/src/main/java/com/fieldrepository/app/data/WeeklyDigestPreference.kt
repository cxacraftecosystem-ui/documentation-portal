package com.fieldrepository.app.data

import kotlinx.serialization.Serializable

@Serializable
data class WeeklyDigestCoverageDto(
    val label: String = "",
    val value: Int = 0,
    val of: Int? = null,
    val percent: Int? = null,
)

@Serializable
data class WeeklyDigestSummaryDto(
    val id: String,
    val weekStart: String = "",
    val waiting: Int = 0,
    val overdue: Int = 0,
    val newThisWeek: Int = 0,
    val coverage: WeeklyDigestCoverageDto? = null,
)

@Serializable
data class WeeklyDigestPreferenceDto(
    val eligible: Boolean = false,
    val enabled: Boolean = false,
    val emailAvailable: Boolean = false,
    val schedule: String = "Mondays, 08:00 IST",
    val latest: WeeklyDigestSummaryDto? = null,
)

@Serializable
data class WeeklyDigestPreferenceBody(val enabled: Boolean)

object WeeklyDigestRules {
    const val LABEL = "Weekly summary"
    private const val DESCRIPTION = "Mondays at 08:00: what waits for you, what is overdue, what came in."

    fun showSwitch(state: WeeklyDigestPreferenceDto?): Boolean = state?.eligible == true

    fun description(state: WeeklyDigestPreferenceDto): String =
        DESCRIPTION + if (state.emailAvailable) " By e-mail and here." else " Here, in your notifications."

    suspend fun toggle(
        before: WeeklyDigestPreferenceDto,
        next: Boolean,
        save: suspend (Boolean) -> WeeklyDigestPreferenceDto,
        show: (WeeklyDigestPreferenceDto) -> Unit,
    ): Pair<WeeklyDigestPreferenceDto, Throwable?> {
        show(before.copy(enabled = next))
        return try {
            val saved = save(next)
            show(saved)
            saved to null
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            show(before)
            before to failure
        }
    }
}
