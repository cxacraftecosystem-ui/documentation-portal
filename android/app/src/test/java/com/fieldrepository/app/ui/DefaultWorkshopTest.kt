package com.fieldrepository.app.ui

import com.fieldrepository.app.data.WorkshopDetailDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The questionnaire's default workshop: the most recent one the user may record in, by the day it
 * ran, then by when it was entered. Same cases as the web's
 * `frontend/e2e/questionnaire-default-workshop-unit.spec.ts`.
 */
class DefaultWorkshopTest {
    private val today = LocalDate.of(2026, 10, 20)

    private fun ws(id: String, startDate: String?, canRecord: Boolean? = null, createdAt: String = "2026-01-01T00:00:00.000Z") =
        WorkshopDetailDto(id = id, title = id, startDate = startDate, date = startDate ?: "2026-01-01T00:00:00.000Z", createdAt = createdAt, canRecord = canRecord)

    @Test
    fun picksTheMostRecentByTheDayItRanNotListOrder() {
        val list = listOf(ws("older", "2026-09-14T00:00:00.000Z"), ws("recent", "2026-10-12T00:00:00.000Z"), ws("oldest", "2026-08-01T00:00:00.000Z"))
        assertEquals("recent", mostRecentRecordableWorkshop(list, today)?.id)
    }

    @Test
    fun skipsWorkshopsTheUserMayNotRecordIn() {
        val list = listOf(ws("closed", "2026-10-18T00:00:00.000Z", canRecord = false), ws("mine", "2026-10-01T00:00:00.000Z", canRecord = true))
        assertEquals("mine", mostRecentRecordableWorkshop(list, today)?.id)
    }

    @Test
    fun anOlderServerWithoutTheFlagReadsAsRecordable() {
        assertEquals("legacy", mostRecentRecordableWorkshop(listOf(ws("legacy", "2026-10-01T00:00:00.000Z")), today)?.id)
    }

    @Test
    fun anEndedWorkshopCountsOneNotStartedDoesNot() {
        val list = listOf(ws("next-month", "2026-11-10T00:00:00.000Z"), ws("ended", "2026-09-14T00:00:00.000Z"))
        assertEquals("ended", mostRecentRecordableWorkshop(list, today)?.id)
    }

    @Test
    fun aWorkshopStartingTodayCountsOnTheIstDay() {
        val list = listOf(ws("today", "2026-10-21T00:00:00.000Z"))
        // 00:30 IST on 21 Oct is still 20 Oct in UTC.
        assertEquals("today", mostRecentRecordableWorkshop(list, workshopToday(Instant.parse("2026-10-20T19:00:00Z")))?.id)
        assertNull(mostRecentRecordableWorkshop(list, today))
    }

    @Test
    fun sameDayTheOneEnteredLastWins() {
        val list = listOf(
            ws("first", "2026-10-12T00:00:00.000Z", createdAt = "2026-10-01T10:00:00.000Z"),
            ws("second", "2026-10-12T00:00:00.000Z", createdAt = "2026-10-02T10:00:00.000Z")
        )
        assertEquals("second", mostRecentRecordableWorkshop(list, today)?.id)
    }

    @Test
    fun noneRecordableLeavesTheFieldEmpty() {
        assertNull(mostRecentRecordableWorkshop(emptyList(), today))
        assertNull(mostRecentRecordableWorkshop(listOf(ws("closed", "2026-10-01T00:00:00.000Z", canRecord = false)), today))
    }
}
