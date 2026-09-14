package com.fieldrepository.app.ui

import com.fieldrepository.app.data.WorkshopDetailDto
import com.fieldrepository.app.data.WorkshopSubmissionCheckDto
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * THE WORKSHOP WINDOW AS THIS HANDSET JUDGES IT: THREE STATES, COUNTED IN IST DAYS.
 *
 * ── THE INCIDENT ────────────────────────────────────────────────────────────────────────────────
 *
 * At 00:55 IST on 2026-09-14 a record form for a workshop running 14–23 Sept said, on both clients:
 *
 *     "This workshop ended on 24 Sept 2026. Saving now is recorded as a late submission."
 *
 * It had not ended. It had not started. Two defects lined up to produce it:
 *
 *  1. `outOfWindow` is a TWO-SIDED flag — true before the start as well as after the end (see
 *     `backend/app/services/workshop_access.py::describe_workshop_submission`). `MainActivity.kt`'s
 *     `WorkshopField` collapsed it with `check.outOfWindow || check.isOver` and then printed the
 *     after-the-end half of it for both halves, so a workshop that had not OPENED was announced as
 *     ENDED. There was no string for "not started" on this client at all. The web made the identical
 *     mistake at `frontend/components/forms/WorkshopSelect.tsx:287`.
 *  2. The server judged the window as UTC instants against days typed in IST, so `now < startDate`
 *     fired for the first 5h30m of every IST day of the run. Fixed server-side; the rules below are
 *     what keeps this client agreeing with the fixed server, and what answers correctly when the
 *     pre-flight cannot be reached at all.
 *
 * ── WHY A FILE OF PURE FUNCTIONS AND NOT AN `if` IN THE COMPOSABLE ──────────────────────────────
 *
 * `app/build.gradle.kts` carries no `ui-test-junit4` and no Robolectric, so the JVM suite cannot
 * compose a picker and look at it — the same constraint `AccessRosterTest` and `RecordPickersTest`
 * already worked around by lifting the ruling out of the composable. And this particular ruling
 * cannot be reproduced at a desk on purpose: it needs a workshop whose window straddles the hour
 * you happen to launch the app, an IST clock, and a build old enough to still be wrong. It reached
 * a user precisely because nothing about it is reproducible on demand.
 *
 * ── NO NEW WIRE FIELD ───────────────────────────────────────────────────────────────────────────
 *
 * The three states are derived from the two booleans `GET /workshops/{id}/submission-check` has
 * always returned: `isOver` is the after-the-end half on its own, `outOfWindow` is the union, so
 * `outOfWindow && !isOver` is exactly "has not started yet". `data/ApiModels.kt` and
 * `frontend/lib/types.ts` are hand-maintained mirrors of one server dict; a thirteenth key would
 * have had to land in three files at once, and whichever client missed the edit would have decoded
 * the missing discriminator as `false` and gone on printing the wrong sentence.
 *
 * ── WEB PARITY ──────────────────────────────────────────────────────────────────────────────────
 *
 * Every function here has a TypeScript twin in `frontend/components/forms/WorkshopSelect.tsx`, and
 * every string is byte-for-byte the one the web prints, tested on both sides
 * (`WorkshopWindowTest.kt` here, `e2e/workshop-window-unit.spec.ts` there). A researcher who does
 * one job on a laptop and on a phone must be told one thing about one workshop.
 */

/**
 * India Standard Time as a fixed offset rather than `ZoneId.of("Asia/Kolkata")`.
 *
 * India has observed one offset with no daylight saving since 1945, and a fixed offset needs no tz
 * database — which matters for a JVM unit test as much as for a handset whose zone the user may have
 * set to anything at all. The server makes the same trade for the same reason (`WORKSHOP_TZ` in
 * `app/services/workshop_access.py`, following `app/api/routes/public.py:61-64`).
 *
 * Note this deliberately does NOT use the handset's own zone. A workshop's days were typed in India
 * and every record carries `recordedTimezone = "Asia/Kolkata"`; a phone left on airport time must
 * not be told its workshop ended a day early.
 */
val WORKSHOP_OFFSET: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)

/**
 * English (India), PINNED, and not the handset's locale — for the same reason [WORKSHOP_OFFSET] is
 * not the handset's zone.
 *
 * `MMM` is a TEXTUAL field, so an unpinned formatter reads `Locale.getDefault()` and renders the
 * month in whatever language the phone is set to: a researcher who has their handset in Hindi or
 * Bengali got that script's month spliced into an otherwise English sentence. It also made the same
 * build print different text on different machines — which is how this was found. A CI runner
 * defaults to en_US, where CLDR abbreviates September "Sep"; this developer's machine is en_IN,
 * where it is "Sept". `WorkshopWindowTest` asserts the string, so it passed here for weeks and
 * failed on the first GitHub run that had unit tests to run at all.
 *
 * en-IN rather than ROOT or en-US, because it is what the product already says elsewhere: the web
 * client formats every date with `Intl.DateTimeFormat("en-IN", …)` (frontend/lib/format.ts:3), and
 * the two surfaces showing a workshop's dates differently would be a bug in its own right. ROOT and
 * en-US would both print "Sep" and disagree with the web.
 *
 * Contrast `DateTimeFields.kt`'s `Locale.ROOT`, which is correct THERE and would be wrong here: that
 * pattern is purely NUMERIC, where the only thing the locale can change is the numbering system.
 */
private val WORKSHOP_DISPLAY_LOCALE: Locale = Locale("en", "IN")

private val WORKSHOP_DAY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy", WORKSHOP_DISPLAY_LOCALE)

/** Not started / in window / over. The three answers a record form has to tell apart. */
enum class WorkshopWindowState { NOT_STARTED, IN_WINDOW, ENDED }

/**
 * The calendar day a workshop's `startDate` / `endDate` NAMES.
 *
 * Read off the stored wall clock, never converted into another zone, because the column is a DAY
 * wearing a datetime's clothes: the forms write the start at 00:00:00 and the end at 23:59:59.999,
 * and whichever offset the writing client stamped on it, the date part is the day the researcher
 * typed. "2026-09-23T23:59:59.999+00:00" and "…+05:30" are both the 23rd.
 *
 * Converting first is exactly what printed "24 Sept" for a workshop whose last day is the 23rd:
 * that instant resolved in IST is 2026-09-24 05:29. `MainActivity.kt::parseIsoToLocalDate` gets this
 * right for an offset-bearing stamp (it tries `OffsetDateTime` first) but falls back to
 * `ZoneId.systemDefault()` for a bare `Instant`, which would put the same value a day out on a
 * handset west of UTC. This one has no system-zone branch at all.
 */
fun workshopBoundaryDay(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()
    return runCatching { OffsetDateTime.parse(trimmed).toLocalDate() }.getOrNull()
        ?: runCatching { Instant.parse(trimmed).atOffset(ZoneOffset.UTC).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(trimmed.take(10)) }.getOrNull()
}

/** The IST calendar day an instant falls on — the "today" a workshop window is judged against. */
fun workshopToday(now: Instant = Instant.now()): LocalDate = now.atOffset(WORKSHOP_OFFSET).toLocalDate()

/** "23 Sept 2026" for a boundary column — the day it names, never the instant. Null when unknown. */
fun formatWorkshopDay(raw: String?): String? =
    workshopBoundaryDay(raw)?.let { runCatching { it.format(WORKSHOP_DAY_FORMAT) }.getOrNull() }

/**
 * Which of the three states the current pick is in.
 *
 * The server's verdict wins when there is one. When the pre-flight is unavailable — offline, a dead
 * tunnel, a transient 5xx — fall back to the workshop's own dates, judged the way the server judges
 * them: whole IST calendar days, INCLUSIVE at both ends. A researcher in a courtyard with no signal
 * is exactly who reads this sentence, and a workshop ending today has not ended — the invariant the
 * sibling repository pins by that name in its `WorkshopOptionsTest`. One day out marks a workshop
 * the researcher is standing in as over, and the confirmation then asks them to confirm a late
 * submission that is not late.
 *
 * A workshop with no dates recorded is IN_WINDOW: a blank column is not evidence of anything, and
 * flagging one would pin real fieldwork to PENDING over a field nobody filled in.
 */
fun workshopWindowState(
    check: WorkshopSubmissionCheckDto?,
    workshop: WorkshopDetailDto?,
    today: LocalDate = workshopToday(),
): WorkshopWindowState {
    if (check != null) {
        if (check.isOver) return WorkshopWindowState.ENDED
        return if (check.outOfWindow) WorkshopWindowState.NOT_STARTED else WorkshopWindowState.IN_WINDOW
    }
    if (workshop == null) return WorkshopWindowState.IN_WINDOW
    val endDay = workshopBoundaryDay(workshop.endDate ?: workshop.date ?: workshop.startDate)
    if (endDay != null && today.isAfter(endDay)) return WorkshopWindowState.ENDED
    val startDay = workshopBoundaryDay(workshop.startDate ?: workshop.date)
    if (startDay != null && today.isBefore(startDay)) return WorkshopWindowState.NOT_STARTED
    return WorkshopWindowState.IN_WINDOW
}

/**
 * The sentence under the picker for a pick outside its window, or null when it is inside one.
 *
 * Each state gets copy that is TRUE of it, and the word "ended" appears in exactly one of them. The
 * date half degrades to a dateless form when the day is unknown — the pre-flight carries no
 * `startDate`, so a workshop that is not in the loaded list leaves `startLabel` null — because
 * "This workshop has not started yet" is still worth saying and a date we do not have is not worth
 * inventing.
 *
 * Pass `needsAdminApproval = true` when there is no server answer: that wording promises the most,
 * and is therefore the safe one to be wrong about on a form a researcher is about to save.
 */
fun workshopWindowNotice(
    state: WorkshopWindowState,
    startLabel: String?,
    endLabel: String?,
    needsAdminApproval: Boolean,
): String? = when (state) {
    WorkshopWindowState.IN_WINDOW -> null
    WorkshopWindowState.NOT_STARTED ->
        (if (startLabel == null) "This workshop has not started yet." else "This workshop starts on $startLabel.") +
            " " +
            if (needsAdminApproval) {
                "Saving now counts as an early submission and needs an admin's approval."
            } else {
                "Saving now is recorded as an early submission."
            }
    WorkshopWindowState.ENDED ->
        (if (endLabel == null) "This workshop has already ended." else "This workshop ended on $endLabel.") +
            " " +
            if (needsAdminApproval) {
                "Saving now counts as a late submission and needs an admin's approval."
            } else {
                "Saving now is recorded as a late submission."
            }
}
