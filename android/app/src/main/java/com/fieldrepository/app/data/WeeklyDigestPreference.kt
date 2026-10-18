package com.fieldrepository.app.data

import kotlinx.serialization.Serializable

/*
 * THE MONDAY SUMMARY'S SWITCH, the handset's half. Reviewers and administrators get one summary
 * every Monday at 08:00 IST (in the activity list always, by e-mail when mail is on). The server
 * decides who gets one (`eligible`) and owns the switch; the phone only shows it and flips it.
 *
 *   GET notifications/digest            -> WeeklyDigestPreferenceDto
 *   PUT notifications/digest {enabled}  -> WeeklyDigestPreferenceDto (403 when not eligible)
 *
 * The same routes and shapes on both servers. There is no summary screen on the phone: the notice's
 * title and body line ARE the summary here, and its `/digest/<id>` link opens nothing (see
 * MainActivity's activity-list branch).
 */

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

/** Pure rules for the switch row, so the tests hold them without Compose. */
object WeeklyDigestRules {
    const val LABEL = "Weekly summary"
    private const val DESCRIPTION = "Mondays at 08:00: what waits for you, what is overdue, what came in."

    /** The row is drawn only for an account that gets a summary, and only once the server said so. */
    fun showSwitch(state: WeeklyDigestPreferenceDto?): Boolean = state?.eligible == true

    fun description(state: WeeklyDigestPreferenceDto): String =
        DESCRIPTION + if (state.emailAvailable) " By e-mail and here." else " Here, in your notifications."

    /**
     * Flip it: show the new value at once, then the server's answer, or put back what was there when
     * the save fails. Returns the state left on screen and the failure, if any.
     */
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
