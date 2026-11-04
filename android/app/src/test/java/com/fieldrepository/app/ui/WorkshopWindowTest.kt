package com.fieldrepository.app.ui

import com.fieldrepository.app.data.WorkshopDetailDto
import com.fieldrepository.app.data.WorkshopSubmissionCheckDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

class WorkshopWindowTest {

    private val thirdToolkit = WorkshopDetailDto(
        id = "wsp_3",
        title = "Shristi O Anusandhan 3rd Toolkit Workshop for Mud Craft Tradition",
        startDate = "2026-09-14T00:00:00+00:00",
        endDate = "2026-09-23T23:59:59.999000+00:00",
        date = "2026-09-14T00:00:00+00:00",
    )

    private fun answer(
        outOfWindow: Boolean = false,
        isOver: Boolean = false,
        needsAdminApproval: Boolean = false,
    ) = WorkshopSubmissionCheckDto(
        workshopId = thirdToolkit.id,
        title = thirdToolkit.title,
        endDate = thirdToolkit.endDate,
        isOver = isOver,
        outOfWindow = outOfWindow,
        needsAdminApproval = needsAdminApproval,
    )

    private fun ist(day: Int, hour: Int = 12, minute: Int = 0, month: Int = 9): Instant =
        java.time.OffsetDateTime
            .parse("2026-%02d-%02dT%02d:%02d:00+05:30".format(month, day, hour, minute))
            .toInstant()

    @Test
    fun `a workshop opening this morning is in window at five past midnight IST`() {
        val moment = ist(14, 0, 55)
        assertEquals("the premise", "2026-09-13T19:25:00Z", moment.toString())

        val today = workshopToday(moment)
        assertEquals(LocalDate.parse("2026-09-14"), today)
        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, thirdToolkit, today))
        assertNull(
            workshopWindowNotice(
                state = workshopWindowState(null, thirdToolkit, today),
                startLabel = formatWorkshopDay(thirdToolkit.startDate),
                endLabel = formatWorkshopDay(thirdToolkit.endDate),
                needsAdminApproval = true,
            )
        )
    }

    @Test
    fun `the server's in-window answer produces no warning either`() {
        assertEquals(
            WorkshopWindowState.IN_WINDOW,
            workshopWindowState(answer(outOfWindow = false, isOver = false), thirdToolkit, workshopToday(ist(14, 0, 55)))
        )
    }

    @Test
    fun `not started, in window and ended are told apart with no new wire field`() {
        val today = workshopToday(ist(18))

        assertEquals(
            WorkshopWindowState.NOT_STARTED,
            workshopWindowState(answer(outOfWindow = true, isOver = false), thirdToolkit, today)
        )
        assertEquals(
            WorkshopWindowState.IN_WINDOW,
            workshopWindowState(answer(outOfWindow = false, isOver = false), thirdToolkit, today)
        )
        assertEquals(
            WorkshopWindowState.ENDED,
            workshopWindowState(answer(outOfWindow = true, isOver = true), thirdToolkit, today)
        )
    }

    @Test
    fun `a workshop that has not started is never called ended`() {
        val notice = workshopWindowNotice(
            state = WorkshopWindowState.NOT_STARTED,
            startLabel = "20 Sept 2026",
            endLabel = "29 Sept 2026",
            needsAdminApproval = true,
        )!!

        assertFalse("the word that made the sentence false", notice.contains("ended"))
        assertTrue(notice.contains("starts on 20 Sept 2026"))
        assertTrue(notice.contains("early submission"))
    }

    @Test
    fun `the ended sentence keeps the wording researchers already know`() {
        assertEquals(
            "This workshop ended on 23 Sept 2026. Saving now counts as a late submission and needs " +
                "an admin's approval.",
            workshopWindowNotice(WorkshopWindowState.ENDED, null, "23 Sept 2026", needsAdminApproval = true)
        )
        assertEquals(
            "This workshop ended on 23 Sept 2026. Saving now is recorded as a late submission.",
            workshopWindowNotice(WorkshopWindowState.ENDED, null, "23 Sept 2026", needsAdminApproval = false)
        )
    }

    @Test
    fun `an in-window workshop earns no sentence at all`() {
        assertNull(
            workshopWindowNotice(WorkshopWindowState.IN_WINDOW, "14 Sept 2026", "23 Sept 2026", needsAdminApproval = true)
        )
    }

    @Test
    fun `a start day we do not know degrades to a dateless sentence rather than a wrong one`() {
        assertEquals(
            "This workshop has not started yet. Saving now is recorded as an early submission.",
            workshopWindowNotice(WorkshopWindowState.NOT_STARTED, null, null, needsAdminApproval = false)
        )
    }

    @Test
    fun `both boundary days belong to the workshop in full`() {
        assertEquals(WorkshopWindowState.NOT_STARTED, workshopWindowState(null, thirdToolkit, workshopToday(ist(13))))
        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, thirdToolkit, workshopToday(ist(14))))
        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, thirdToolkit, workshopToday(ist(23))))
        assertEquals(WorkshopWindowState.ENDED, workshopWindowState(null, thirdToolkit, workshopToday(ist(24))))
    }

    @Test
    fun `a workshop ending today has not ended, at either end of that day`() {
        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, thirdToolkit, workshopToday(ist(23, 0, 5))))
        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, thirdToolkit, workshopToday(ist(23, 23, 55))))
        assertEquals(WorkshopWindowState.ENDED, workshopWindowState(null, thirdToolkit, workshopToday(ist(24, 0, 5))))
    }

    @Test
    fun `a workshop with no dates at all is not declared over or unopened`() {
        val undated = WorkshopDetailDto(id = "wsp_6", title = "Dates never recorded")

        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, undated, workshopToday(ist(18))))
        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, null, workshopToday(ist(18))))
    }

    @Test
    fun `the last millisecond of the 23rd reads as the 23rd`() {
        assertEquals(LocalDate.parse("2026-09-23"), workshopBoundaryDay("2026-09-23T23:59:59.999000+00:00"))

        val rendered = formatWorkshopDay("2026-09-23T23:59:59.999000+00:00")
        assertTrue(
            "the workshop day must read as the 23rd of September 2026 in English, and it read '$rendered'",
            rendered != null && Regex("^23 Sept? 2026$").matches(rendered),
        )
    }

    @Test
    fun `the suite runs under a locale that is not India, or the date tests prove nothing`() {
        val default = Locale.getDefault()
        assertFalse(
            "The unit-test JVM is running as $default. A formatter with no explicit Locale would " +
                "then be built as en_IN and every date assertion here would pass whether or not the " +
                "formatter is pinned — which is exactly how the \"Sept\"/\"Sep\" bug reached main. " +
                "Restore the systemProperty lines in app/build.gradle.kts testOptions.",
            default.country == "IN" || default.toString().endsWith("_IN"),
        )
    }

    @Test
    fun `an offset-bearing stamp names the same day as a UTC one`() {
        assertEquals(LocalDate.parse("2026-09-23"), workshopBoundaryDay("2026-09-23T23:59:59.999+05:30"))
        assertEquals(LocalDate.parse("2026-09-14"), workshopBoundaryDay("2026-09-14"))
        assertNull(workshopBoundaryDay(null))
        assertNull(workshopBoundaryDay("   "))
        assertNull(workshopBoundaryDay("not a date"))
        assertNull(formatWorkshopDay(null))
    }

    @Test
    fun `today is counted in IST, across the 18_30Z rollover`() {
        assertEquals(LocalDate.parse("2026-09-13"), workshopToday(Instant.parse("2026-09-13T18:29:00Z")))
        assertEquals(LocalDate.parse("2026-09-14"), workshopToday(Instant.parse("2026-09-13T18:30:00Z")))
        assertEquals(LocalDate.parse("2026-09-14"), workshopToday(Instant.parse("2026-09-13T19:25:00Z")))
    }
}
