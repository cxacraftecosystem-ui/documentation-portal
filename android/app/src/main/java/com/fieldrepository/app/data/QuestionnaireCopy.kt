package com.fieldrepository.app.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every questionnaire v2 sentence on Android, worded exactly as DESIGN §4 and the web.
 *
 * BUILD_PLAN §0.1 makes `shared/strings/questionnaire.json` the single table, generated into
 * `res/values/strings_questionnaire.xml` and `frontend/lib/strings/questionnaire.ts` by the BACKEND
 * stream's generator. Until that commit lands, this object holds the same keys and copy, and
 * `SharedStringsTest` checks it against the shared table the moment the file exists.
 */
object QuestionnaireCopy {
    private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    /** Keyed copy, the same keys as `shared/strings/questionnaire.json`. Fixed strings only. */
    val strings: Map<String, String> = linkedMapOf(
        "sessions.title" to "Sessions",
        "sessions.new" to "New session",
        "sessions.empty" to "No sessions yet.",
        "sessions.builder" to "Builder",
        "sessions.inbox" to "Inbox",
        "sessions.join" to "Join session",
        "start.workshop" to "Workshop",
        "start.fieldVisit" to "Field visit (no workshop)",
        "start.instrument" to "Instrument",
        "start.date" to "Date",
        "start.mode" to "Mode",
        "start.mode.individual" to "Individual",
        "start.mode.group" to "Group",
        "start.artisans" to "Artisans",
        "start.artisan" to "Artisan",
        "start.purpose" to "Purpose",
        "purpose.FIRST_SITTING" to "First sitting",
        "purpose.FOLLOW_UP" to "Follow-up",
        "purpose.REDO" to "Redo",
        "start.place" to "Place",
        "start.language" to "Language",
        "start.languageOther" to "Language name",
        "start.note" to "Note",
        "start.notePlaceholder" to "Anything to remember about this sitting",
        "start.submit" to "Start session",
        "p1.title" to "Session already open",
        "p1.new" to "New session",
        "p1.join" to "Join",
        "consent.notNow" to "Not now",
        "consent.yes" to "Yes, recorded",
        "consent.no" to "No",
        "consent.more" to "More",
        "consent.waiting" to "Waiting for consent to transcribe",
        "consent.record" to "Record consent",
        "workspace.finish" to "Finish",
        "workspace.joinCode" to "Join code",
        "workspace.addArtisan" to "+ Add artisan",
        "workspace.typeAnswers" to "Type answers",
        "workspace.recordSection" to "Record section",
        "workspace.record" to "Record",
        "workspace.recordTake" to "Record take",
        "workspace.uploadFile" to "Upload file",
        "workspace.present" to "Present",
        "workspace.away" to "Away",
        "workspace.answeredBy" to "Answered by",
        "workspace.justThese" to "Just these…",
        "upload.uploaded" to "Uploaded",
        "upload.waiting" to "Waiting for signal",
        "upload.failed" to "Upload failed. Retrying.",
        "upload.onDevice" to "On this device",
        "leave.title" to "Leave this session?",
        "leave.stay" to "Stay",
        "leave.leave" to "Leave",
        "finish.title" to "Finish session",
        "finish.button" to "Finish",
        "p2.title" to "Already recorded",
        "p2.why" to "Why again?",
        "p2.cancel" to "Cancel",
        "p2.recordAgain" to "Record again",
        "p2.uploadAgain" to "Upload again",
        "p2.more" to "More options",
        "p2.partTwo" to "Continue as part 2",
        "p2.replace" to "Replace the earlier take",
        "p2.different" to "Use a different question",
        "reason.REDO" to "Redo",
        "reason.FOLLOW_UP" to "Follow-up",
        "reason.RE_INTERVIEW" to "Re-interview",
        "reason.NEW_SETTING" to "New setting",
        "p3.keep" to "Keep recording",
        "p5.title" to "Already uploaded",
        "p5.ok" to "OK",
        "p5.merge" to "Merge sessions",
        "p7.cancel" to "Cancel",
        "p7.replace" to "Replace",
        "p7.body" to "Take 1 is removed once take 2 uploads. You can restore it.",
        "p7r.body" to "You can restore it from Removed.",
        "p7r.remove" to "Remove",
        "p9.title" to "Changed on another device",
        "p9.theirs" to "Keep theirs",
        "p9.mine" to "Keep mine",
        "p10.remove" to "Remove",
        "p12.hint" to "Locked once recording starts.",
        "p13.title" to "Recovered a recording",
        "p13.discard" to "Discard",
        "p13.keep" to "Keep",
        "p14.banner" to "Update when you have Wi-Fi. Your recordings are safe.",
        "p14.update" to "Update",
        "transcript.paused" to "Transcription paused",
        "transcript.pausedReason" to "Transcription is paused.",
        "transcript.refused" to "Not transcribed: consent refused",
        "transcript.retry" to "Retry",
        "transcript.now" to "Transcribe now",
        "record.error" to "Couldn't load the questionnaire.",
        "record.tryAgain" to "Try again",
        "record.empty" to "No sessions yet.",
        "record.startSession" to "Start session",
        "record.timeline" to "Timeline",
        "record.byQuestion" to "By question",
        "record.uncertain" to "Order uncertain",
        "record.present" to "Present, didn't answer",
        "record.wholeSession" to "Whole session",
        "record.needsDecision" to "Needs a decision",
        "record.estimated" to "Estimated date",
        "record.legacy" to "Legacy record",
        "record.exportCsv" to "Export CSV",
        "record.earlierWording" to "Recorded under earlier wording",
        "record.accountsDiffer" to "Accounts differ",
        "record.retired" to "Retired questions",
        "record.fromRecording" to "From recording",
        "record.groupAnswer" to "Group answer",
        "inbox.title" to "Inbox",
        "inbox.toFile" to "To file",
        "inbox.needsDecision" to "Needs a decision",
        "inbox.legacy" to "Legacy question",
        "inbox.setDates" to "Set dates",
        "inbox.noParticipants" to "Who was there",
        "inbox.empty" to "Empty session",
        "inbox.primaryChanged" to "Primary changed",
        "inbox.nothing" to "Nothing to sort out.",
        "join.scan" to "Scan QR code",
        "join.pick" to "Pick an image",
        "join.code" to "Join code",
        "join.submit" to "Join",
        "join.notFound" to "No session has that code.",
        "join.noQr" to "No join code found in that picture.",
        "join.onlineOnly" to "The code needs a signal. Scan the QR code to join offline.",
        "battery.title" to "Keep recording in the background",
        "battery.reason" to "So long recordings aren't stopped.",
        "recorder.pause" to "Pause",
        "recorder.resume" to "Resume",
        "recorder.stop" to "Stop"
    )

    fun s(key: String): String = strings[key] ?: error("No questionnaire string '$key'")

    fun day(date: LocalDate): String = date.format(DAY_MONTH)
    fun clock(instant: Instant): String = instant.atZone(Naming.IST).format(CLOCK)

    fun reasonLabel(reason: String) = strings["reason.$reason"] ?: reason
    fun purposeLabel(purpose: String) = strings["purpose.$purpose"] ?: purpose

    fun mode(count: Int): String = if (count <= 1) "Individual" else "Group of $count"
    fun setting(rosterCount: Int): String = if (rosterCount <= 1) "Solo" else "Group of $rosterCount"

    /** "12 min", "0:42" below a minute. */
    fun minutes(durationMs: Long): String {
        val seconds = durationMs / 1000
        return if (seconds < 60) "0:${seconds.toString().padStart(2, '0')}" else "${seconds / 60} min"
    }

    /** "12:40", "1:02:05". */
    fun elapsed(durationMs: Long): String {
        val total = durationMs / 1000
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
    }

    fun checkedAgo(age: Duration): String? = when {
        age < Duration.ofMinutes(1) -> null
        age < Duration.ofHours(1) -> "Checked ${age.toMinutes()} min ago"
        age < Duration.ofDays(1) -> "Checked ${age.toHours()} h ago"
        else -> "Checked ${age.toDays()} d ago"
    }

    // ── Start sheet ───────────────────────────────────────────────────────────────────────────

    data class ArtisanCoverage(val firstName: String, val sectionsCovered: Int)

    /** §4.2: one, up to three, or more than three artisans; the snapshot suffix when offline. */
    fun coverageLine(artisans: List<ArtisanCoverage>, sectionsTotal: Int, checkedAgo: String?): String? {
        if (artisans.isEmpty()) return null
        val body = when {
            artisans.size == 1 -> artisans.single().let { "${it.firstName} has ${it.sectionsCovered} of $sectionsTotal sections." }
            artisans.size <= 3 -> artisans.joinToString(" · ") {
                if (it.sectionsCovered == 0) "${it.firstName} none yet" else "${it.firstName} ${it.sectionsCovered} of $sectionsTotal"
            } + "."
            else -> "${artisans.count { it.sectionsCovered > 0 }} of ${artisans.size} have earlier recordings ▾"
        }
        return if (checkedAgo == null) body else "$body · $checkedAgo"
    }

    fun p1Body(researcher: String, names: List<String>, startedAt: Instant): String =
        "$researcher started one with ${joinNames(names)} at ${clock(startedAt)}."

    fun consentQuestion(firstName: String) =
        "May recordings of $firstName be sent to outside services to be written down or translated?"

    // ── Workspace ─────────────────────────────────────────────────────────────────────────────

    fun uploading(percent: Int) = "Uploading $percent%"
    fun takesUploading(count: Int) = if (count == 1) "1 take uploading" else "$count takes uploading"
    fun leaveBody(pending: Int) = if (pending == 1) "1 take will keep uploading in the background." else "$pending takes will keep uploading in the background."
    fun finishBody(sections: Int, artisans: Int, uploading: Int): String {
        val first = "$sections ${if (sections == 1) "section" else "sections"} recorded for $artisans ${if (artisans == 1) "artisan" else "artisans"}."
        return if (uploading == 0) first else "$first ${if (uploading == 1) "1 take still uploading." else "$uploading takes still uploading."}"
    }
    fun spaceLeft(minutes: Long) = "About $minutes min of space left."
    fun notification(label: String, elapsedMs: Long) = "Recording $label · ${elapsed(elapsedMs)}"
    fun missingFile(label: String) = SessionOutbox.missingFileMessage(label)

    // ── P2 (§4.4) ─────────────────────────────────────────────────────────────────────────────

    /** "Section D is recorded for 2 of 3 here." or the one-subject variants. */
    fun p2Body(targetLabel: String, coveredNames: List<String>, total: Int, onDate: LocalDate?, viaSection: Boolean): String {
        if (total == 1 && coveredNames.size == 1) {
            val whole = if (viaSection) " whole" else ""
            val day = onDate?.let { " on ${day(it)}" } ?: ""
            return "$targetLabel was recorded$whole for ${coveredNames.single()}$day."
        }
        return "$targetLabel is recorded for ${coveredNames.size} of $total here."
    }

    /** "Giriraj · 14 min · Nandini · 7 Jul · Group of 3". */
    fun p2TakeLine(name: String, durationMs: Long, researcher: String?, date: LocalDate?, rosterCount: Int): String =
        listOfNotNull(name, minutes(durationMs), researcher?.ifBlank { null }, date?.let(::day), setting(rosterCount)).joinToString(" · ")

    fun p2FirstTake(name: String, targetCode: String) = "$name hasn't been recorded for $targetCode. Their take will be their first."

    fun alsoRecorded(date: LocalDate) = "Also recorded on ${day(date)}."

    // ── Other prompts (§4.5) ──────────────────────────────────────────────────────────────────

    fun p3Title(label: String) = "Stop the $label recording?"
    fun p3Body(elapsedMs: Long) = "It's ${elapsed(elapsedMs)} long and will be kept."
    fun p3Stop(nextCode: String) = "Stop and record $nextCode"
    fun p5Body(name: String, date: LocalDate) = "This exact file is on $name's session of ${day(date)}."
    fun p5Add(name: String) = "Add $name to it"
    fun p7Title(label: String) = "Replace take 1 of $label?"
    fun p7rTitle(position: Int, label: String) = "Remove take $position of $label?"
    fun p8Title(name: String, code: String) = "Replace $name's answer to $code?"
    fun p8Body(researcher: String, date: LocalDate) = "The current answer came from $researcher's take of ${day(date)}."
    fun p9Body(researcher: String, name: String, code: String, at: Instant) = "$researcher edited $name's answer to $code at ${clock(at)}."
    fun p10Title(name: String) = "Remove $name from this session?"
    fun p10Body(count: Int) = if (count == 1) "Their 1 take here will no longer count for them." else "Their $count takes here will no longer count for them."
    fun p11(count: Int) = if (count == 1) "Move or remove its 1 recording first." else "Move or remove its $count recordings first."
    fun p13Body(minutes: Long, label: String) = "A $minutes-minute take of $label was saved before the app closed."
    fun takeOf(researcher: String, date: LocalDate) = "$researcher's take of ${day(date)}"

    // ── Artisan record (§4.6) ────────────────────────────────────────────────────────────────

    fun recordSummary(covered: Int, total: Int, sessions: Int, repeats: Int): String = listOf(
        "$covered of $total sections",
        if (sessions == 1) "1 session" else "$sessions sessions",
        if (repeats == 1) "1 repeat" else "$repeats repeats"
    ).joinToString(" · ")
    fun openSections(codes: List<String>) = "Open: ${codes.joinToString(", ")}"
    fun continueWith(firstName: String) = "Continue with $firstName"
    fun showRemoved(count: Int) = "Show removed ($count)"
    fun moreTakes(count: Int) = if (count == 1) "1 more take" else "$count more takes"
    fun groupAnswer(count: Int) = "Group answer ($count)"
    fun take(position: Int) = "Take $position"

    private fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    fun firstName(name: String): String = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
}
