package com.fieldrepository.app.ui

import com.fieldrepository.app.data.WorkshopDetailDto
import com.fieldrepository.app.data.WorkshopSubmissionCheckDto
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

val WORKSHOP_OFFSET: ZoneOffset = ZoneOffset.ofHoursMinutes(5, 30)

private val WORKSHOP_DISPLAY_LOCALE: Locale = Locale("en", "IN")

private val WORKSHOP_DAY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy", WORKSHOP_DISPLAY_LOCALE)

enum class WorkshopWindowState { NOT_STARTED, IN_WINDOW, ENDED }

fun workshopBoundaryDay(raw: String?): LocalDate? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()
    return runCatching { OffsetDateTime.parse(trimmed).toLocalDate() }.getOrNull()
        ?: runCatching { Instant.parse(trimmed).atOffset(ZoneOffset.UTC).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(trimmed.take(10)) }.getOrNull()
}

fun mostRecentRecordableWorkshop(list: List<WorkshopDetailDto>, today: LocalDate = workshopToday()): WorkshopDetailDto? =
    list.asSequence()
        .filter { it.canRecord != false }
        .filter { w -> workshopBoundaryDay(w.startDate ?: w.date)?.let { !it.isAfter(today) } ?: true }
        .sortedWith(
            compareByDescending<WorkshopDetailDto> { workshopBoundaryDay(it.startDate ?: it.date ?: it.createdAt) }
                .thenByDescending { it.createdAt.orEmpty() }
        )
        .firstOrNull()

fun workshopToday(now: Instant = Instant.now()): LocalDate = now.atOffset(WORKSHOP_OFFSET).toLocalDate()

fun formatWorkshopDay(raw: String?): String? =
    workshopBoundaryDay(raw)?.let { runCatching { it.format(WORKSHOP_DAY_FORMAT) }.getOrNull() }

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
