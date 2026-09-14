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

/**
 * "THIS WORKSHOP ENDED ON 24 SEPT 2026", SAID TO SOMEONE STANDING IN IT ON THE 14TH.
 *
 * ── THE INCIDENT ────────────────────────────────────────────────────────────────────────────────
 *
 * At 00:55 IST on 2026-09-14 a record form for the 3rd Toolkit Workshop — running 14–23 Sept —
 * carried this under the picker, on the phone and on the laptop alike:
 *
 *     "This workshop ended on 24 Sept 2026. Saving now is recorded as a late submission."
 *
 * The workshop had not ended. It had not started. Three independent mistakes produced one sentence:
 *
 *  1. `MainActivity.kt`'s `WorkshopField` said `check.outOfWindow || check.isOver` and then printed
 *     the after-the-end half of it. `outOfWindow` is TWO-SIDED — before the start OR after the end —
 *     so a workshop that had not opened was announced as over, and this client had no string for
 *     "not started" at all.
 *  2. The server judged the window as UTC instants against days typed in IST, so a workshop read as
 *     not-started for the first 5h30m of every IST day of its run.
 *  3. `2026-09-23T23:59:59.999Z` resolved in IST is 2026-09-24 05:29, so the end day printed as the
 *     24th — a date the workshop does not contain.
 *
 * ── WHY A JUNIT TEST AND NOT A SCREENSHOT ───────────────────────────────────────────────────────
 *
 * `app/build.gradle.kts` carries no `ui-test-junit4` and no Robolectric, so the JVM suite cannot
 * compose a picker and look at it, and an instrumented test needs a device CI has not got. Every
 * ruling worth pinning was therefore lifted out of the composable into `ui/WorkshopOptions.kt` — the
 * same trade `AccessRosterTest` and `RecordPickersTest` already made.
 *
 * And none of these states can be produced at a desk on purpose. They need a workshop whose window
 * straddles the hour the build happens to launch, a particular device clock, and a server old enough
 * to still be wrong. That is exactly why the sentence reached a user rather than a reviewer, and it
 * is the whole argument for asserting strings and days at NAMED instants instead of eyeballing a
 * screen.
 *
 * ── THE WEB TWIN ────────────────────────────────────────────────────────────────────────────────
 *
 * Every assertion below has a TypeScript twin in `frontend/e2e/workshop-window-unit.spec.ts`,
 * against `frontend/components/forms/WorkshopSelect.tsx`. The strings are a CONTRACT, not copy: a
 * researcher doing one job on a laptop and on a phone must be told one thing about one workshop.
 * If you change a rule there and this suite still passes unchanged, you have just made them
 * disagree.
 */
class WorkshopWindowTest {

    /** The production row. The boundary columns are IST calendar days, stored as these instants. */
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

    /** An instant named on the clock the researcher is actually reading. */
    private fun ist(day: Int, hour: Int = 12, minute: Int = 0, month: Int = 9): Instant =
        java.time.OffsetDateTime
            .parse("2026-%02d-%02dT%02d:%02d:00+05:30".format(month, day, hour, minute))
            .toInstant()

    // ── The regression ──────────────────────────────────────────────────────────────────────────

    /**
     * THE ONE ASSERTION THIS FILE EXISTS FOR, pinned to the real numbers off the real row.
     *
     * 00:55 IST on the workshop's opening day is 2026-09-13T19:25Z — the day BEFORE it, in UTC.
     * Nothing about that moment is outside the window, and no sentence about it may contain the
     * word "ended".
     */
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

    /** And the fixed server's own answer for that moment says the same thing, by the other path. */
    @Test
    fun `the server's in-window answer produces no warning either`() {
        assertEquals(
            WorkshopWindowState.IN_WINDOW,
            workshopWindowState(answer(outOfWindow = false, isOver = false), thirdToolkit, workshopToday(ist(14, 0, 55)))
        )
    }

    // ── Three states, from the two booleans already on the wire ──────────────────────────────────

    /**
     * The fix that needed no DTO change. `isOver` is the after-the-end half alone and `outOfWindow`
     * is the union, so `outOfWindow && !isOver` is exactly "has not started". `data/ApiModels.kt`
     * and `frontend/lib/types.ts` are hand-maintained mirrors of one server dict; a thirteenth key
     * would have had to land in three files at once, and whichever client missed the edit would have
     * decoded the missing discriminator as `false` and gone on printing the old wrong sentence.
     */
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

    /** THE DEFECT ITSELF, stated as the thing that may never happen again. */
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

    /** The ended sentence is the one researchers already know; it was never the wrong one. */
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

    /**
     * A workshop in its window is told NOTHING. That row is the only thing on a record form that can
     * make a researcher doubt a save they are entitled to make.
     */
    @Test
    fun `an in-window workshop earns no sentence at all`() {
        assertNull(
            workshopWindowNotice(WorkshopWindowState.IN_WINDOW, "14 Sept 2026", "23 Sept 2026", needsAdminApproval = true)
        )
    }

    /**
     * The pre-flight carries no `startDate` — keeping the wire stable was the point — so a workshop
     * that is not in the loaded list leaves the start day unknown. "Not started yet" is still worth
     * saying; a date we do not have is not worth inventing.
     */
    @Test
    fun `a start day we do not know degrades to a dateless sentence rather than a wrong one`() {
        assertEquals(
            "This workshop has not started yet. Saving now is recorded as an early submission.",
            workshopWindowNotice(WorkshopWindowState.NOT_STARTED, null, null, needsAdminApproval = false)
        )
    }

    // ── The local fallback, when the pre-flight cannot be reached ────────────────────────────────

    /**
     * A researcher in a courtyard with no signal is exactly who reads this sentence, and the
     * fallback is all they get. It must count the days the server counts. The expression it replaced
     * (`LocalDate.now(ZoneId.systemDefault()).isAfter(end)`) also asked the HANDSET what day it was,
     * so a phone left on airport time would have closed the window early.
     */
    @Test
    fun `both boundary days belong to the workshop in full`() {
        assertEquals(WorkshopWindowState.NOT_STARTED, workshopWindowState(null, thirdToolkit, workshopToday(ist(13))))
        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, thirdToolkit, workshopToday(ist(14))))
        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, thirdToolkit, workshopToday(ist(23))))
        assertEquals(WorkshopWindowState.ENDED, workshopWindowState(null, thirdToolkit, workshopToday(ist(24))))
    }

    /**
     * A WORKSHOP ENDING TODAY HAS NOT ENDED — the invariant the sibling repository pins under that
     * exact name in `designer-portal/android/.../ui/WorkshopOptionsTest.kt`. Asserted at both ends of
     * the last IST day, which is where the UTC skew used to flip the answer.
     */
    @Test
    fun `a workshop ending today has not ended, at either end of that day`() {
        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, thirdToolkit, workshopToday(ist(23, 0, 5))))
        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, thirdToolkit, workshopToday(ist(23, 23, 55))))
        assertEquals(WorkshopWindowState.ENDED, workshopWindowState(null, thirdToolkit, workshopToday(ist(24, 0, 5))))
    }

    /** A blank column is not evidence of anything, and never a reason to flag real fieldwork. */
    @Test
    fun `a workshop with no dates at all is not declared over or unopened`() {
        val undated = WorkshopDetailDto(id = "wsp_6", title = "Dates never recorded")

        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, undated, workshopToday(ist(18))))
        assertEquals(WorkshopWindowState.IN_WINDOW, workshopWindowState(null, null, workshopToday(ist(18))))
    }

    // ── The day a column names ───────────────────────────────────────────────────────────────────

    /**
     * THE "24 SEPT" HALF OF THE INCIDENT. The boundary columns are DAYS, and the last millisecond of
     * the 23rd is the 23rd on every clock the person who typed it owns.
     */
    @Test
    fun `the last millisecond of the 23rd reads as the 23rd`() {
        assertEquals(LocalDate.parse("2026-09-23"), workshopBoundaryDay("2026-09-23T23:59:59.999000+00:00"))
        assertEquals("23 Sept 2026", formatWorkshopDay("2026-09-23T23:59:59.999000+00:00"))
    }

    /**
     * THE GUARD THAT MAKES THE ASSERTION ABOVE MEAN SOMETHING — and it is not the obvious test.
     *
     * The assertion above passed on this developer's machine for weeks and failed on CI with
     * `org.junit.ComparisonFailure`. Nothing about the date was wrong.
     * `DateTimeFormatter.ofPattern("dd MMM yyyy")` carried no locale, so `MMM` — a TEXTUAL field —
     * was rendered with `Locale.getDefault()`: "Sept" on an en_IN machine, "Sep" on the en_US
     * runner. One build, two different strings.
     *
     * That is user-facing and not a test artefact. A researcher with their handset in Hindi or
     * Bengali was shown that script's month inside an otherwise English sentence, and the web client
     * — which pins `en-IN` (frontend/lib/format.ts:3) — disagreed with the app about the same
     * workshop's dates. Fixed by pinning `WORKSHOP_DISPLAY_LOCALE` in ui/WorkshopOptions.kt.
     *
     * ⚠ THE OBVIOUS TEST FOR THIS DOES NOT WORK, AND WRITING IT WAS THE FIRST ATTEMPT HERE.
     * `Locale.setDefault(Locale.US)` inside a test body proves nothing: `ofPattern` captures the
     * locale AT CONSTRUCTION, and the formatter is a `private val` built once at class load, long
     * before any test method runs. Measured — with the pin removed from ui/WorkshopOptions.kt, a
     * suite containing exactly that test still passed on an en_IN laptop.
     *
     * The locale has to be wrong BEFORE THE JVM STARTS, so it is pinned on the forked test JVM in
     * app/build.gradle.kts (`testOptions.unitTests.all { systemProperty("user.country", "US") }`).
     * Under that, the plain assertion above is the real regression test.
     *
     * Which leaves one hole: somebody deletes those two lines, the suite goes back to running as
     * en_IN, and the assertion above silently stops testing anything. THIS test is that hole's lid —
     * it asserts the guard is still in force, and it is the reason a reader who sees a date test fail
     * knows to pin the formatter rather than "fix" the build file.
     */
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

    /**
     * And a day stamped from a handset is the same day as one stamped UTC. Whatever offset the
     * writing client attached, the date part is the day the researcher typed.
     */
    @Test
    fun `an offset-bearing stamp names the same day as a UTC one`() {
        assertEquals(LocalDate.parse("2026-09-23"), workshopBoundaryDay("2026-09-23T23:59:59.999+05:30"))
        assertEquals(LocalDate.parse("2026-09-14"), workshopBoundaryDay("2026-09-14"))
        assertNull(workshopBoundaryDay(null))
        assertNull(workshopBoundaryDay("   "))
        assertNull(workshopBoundaryDay("not a date"))
        assertNull(formatWorkshopDay(null))
    }

    /**
     * "Today" is an IST day and not the handset's, across the 18:30Z rollover. A picker that
     * disagreed with the researcher's own watch about what day it is would be arguing with them
     * about the one fact they are certain of.
     */
    @Test
    fun `today is counted in IST, across the 18_30Z rollover`() {
        assertEquals(LocalDate.parse("2026-09-13"), workshopToday(Instant.parse("2026-09-13T18:29:00Z")))
        assertEquals(LocalDate.parse("2026-09-14"), workshopToday(Instant.parse("2026-09-13T18:30:00Z")))
        assertEquals(LocalDate.parse("2026-09-14"), workshopToday(Instant.parse("2026-09-13T19:25:00Z")))
    }
}
