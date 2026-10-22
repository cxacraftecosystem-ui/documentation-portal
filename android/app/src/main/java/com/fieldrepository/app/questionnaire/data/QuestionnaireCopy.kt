package com.fieldrepository.app.questionnaire.data

import com.fieldrepository.app.data.Naming
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every questionnaire v2 sentence on Android, worded exactly as `shared/strings/questionnaire.json`.
 *
 * [shared] is a verbatim copy of the shared table's entries this app uses, keyed by the shared key.
 * `SharedStringsTest` fails when one differs from the table by a single character, when an Android
 * key points at a key the table doesn't have, and when an Android-only key appears that isn't listed
 * in [androidOnly]. Sentences with values are filled from the same templates with [t].
 */
object QuestionnaireCopy {
    private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    /** Shared key to text, copied from `shared/strings/questionnaire.json`. */
    val shared: Map<String, String> = linkedMapOf(
        "sessionsTitle" to "Sessions",
        "newSession" to "New session",
        "startSession" to "Start session",
        "fieldWorkshop" to "Workshop",
        "fieldVisitNoWorkshop" to "Field visit (no workshop)",
        "fieldInstrument" to "Questionnaire",
        "fieldDate" to "Date",
        "fieldMode" to "Mode",
        "modeIndividual" to "Individual",
        "modeGroup" to "Group",
        "modeGroupOf" to "Group of {count}",
        "fieldArtisans" to "Artisans",
        "fieldPurpose" to "Purpose",
        "purposeFirstSitting" to "First sitting",
        "purposeFollowUp" to "Follow-up",
        "purposeRedo" to "Redo",
        "fieldPlace" to "Place",
        "fieldLanguage" to "Language",
        "languageOtherLabel" to "Which language?",
        "fieldNote" to "Note",
        "notePlaceholder" to "Anything to remember about this sitting",
        "titlePreviewLabel" to "Saved as",
        "coverageOne" to "{name} has {covered} of {total} sections.",
        "coverageNoneYet" to "{name} none yet",
        "coverageShort" to "{name} {covered} of {total}",
        "coverageMany" to "{withTakes} of {count} have earlier recordings",
        "checkedAgo" to "Checked {age} ago",
        "openMatchTitle" to "Session already open",
        "openMatchBody" to "{researcher} started one with {artisans} at {time}.",
        "openMatchNew" to "New session",
        "openMatchJoin" to "Join",
        "finish" to "Finish",
        "finishTitle" to "Finish session",
        "finishBody" to "{sections} sections recorded for {artisans} artisans. {pending} takes still uploading.",
        "joinCode" to "Join code {code}",
        "present" to "Present",
        "away" to "Away",
        "addArtisan" to "+ Add artisan",
        "typeAnswers" to "Type answers",
        "recordSection" to "Record section",
        "record" to "Record",
        "recordTake" to "Record take",
        "uploadFile" to "Upload file",
        "takesUploading" to "{count} takes uploading",
        "wholeSession" to "Whole session",
        "answeredBy" to "Answered by",
        "justThese" to "Just these…",
        "answerFor" to "Answer for:",
        "answerForAll" to "All {count}",
        "groupAnswer" to "Group answer ({count})",
        "spaceLeft" to "About {minutes} min of space left.",
        "uploadingPercent" to "Uploading {percent}%",
        "uploaded" to "Uploaded",
        "waitingForSignal" to "Waiting for signal",
        "uploadFailedRetrying" to "Upload failed. Retrying.",
        "leaveTitle" to "Leave this session?",
        "leaveBodyAndroid" to "{count} takes will keep uploading in the background.",
        "stay" to "Stay",
        "leave" to "Leave",
        "alreadyRecordedTitle" to "Already recorded",
        "alreadyRecordedBody" to "{target} is recorded for {covered} of {count} here.",
        "alreadyRecordedOne" to "{target} was recorded for {name} on {date}.",
        "alreadyRecordedWhole" to "{target} was recorded whole for {name} on {date}.",
        "firstTakeFor" to "{name} hasn't been recorded for {target}. Their take will be their first.",
        "whyAgain" to "Why again?",
        "reasonRedo" to "Redo",
        "reasonFollowUp" to "Follow-up",
        "reasonReInterview" to "Re-interview",
        "reasonNewSetting" to "New setting",
        "cancel" to "Cancel",
        "recordAgain" to "Record again",
        "uploadAgain" to "Upload again",
        "moreOptions" to "More options",
        "continueAsPart2" to "Continue as part 2",
        "replaceEarlierTake" to "Replace the earlier take",
        "useDifferentQuestion" to "Use a different question",
        "alsoRecordedOn" to "Also recorded on {date}.",
        "p3Title" to "Stop the {target} recording?",
        "p3Body" to "It's {duration} long and will be kept.",
        "p3Keep" to "Keep recording",
        "p3Stop" to "Stop and record {code}",
        "p5Title" to "Already uploaded",
        "p5Body" to "This exact file is on {name}'s session of {date}.",
        "p5AddTo" to "Add {name} to it",
        "p5Merge" to "Merge sessions",
        "ok" to "OK",
        "p7Title" to "Replace take {n} of {target}?",
        "p7Body" to "Take {n} is removed once take {next} uploads. You can restore it.",
        "replace" to "Replace",
        "p7rTitle" to "Remove take {n} of {target}?",
        "p7rBody" to "You can restore it from Removed.",
        "remove" to "Remove",
        "p8Title" to "Replace {name}'s answer to {code}?",
        "p8Body" to "The current answer came from {researcher}'s take of {date}.",
        "p9Title" to "Changed on another device",
        "p9Body" to "{researcher} edited {name}'s answer to {code} at {time}.",
        "keepTheirs" to "Keep theirs",
        "keepMine" to "Keep mine",
        "keep" to "Keep",
        "p10Title" to "Remove {name} from this session?",
        "p10Body" to "Their {count} takes here will no longer count for them.",
        "p11Hint" to "Move or remove its {count} recordings first.",
        "p12Hint" to "Locked once recording starts.",
        "p13Title" to "Recovered a recording",
        "p13Body" to "A {minutes}-minute take of {target} was saved before the app closed.",
        "discard" to "Discard",
        "p14Banner" to "Update when you have Wi-Fi. Your recordings are safe.",
        "update" to "Update",
        "takeOf" to "{researcher}'s take of {date}",
        "takeN" to "Take {n}",
        "solo" to "Solo",
        "transcriptionPaused" to "Transcription paused",
        "transcriptionPausedReason" to "Transcription is paused.",
        "waitingForConsent" to "Waiting to be sent",
        "consentRefusedStatus" to "Not sent: consent refused",
        "recordConsent" to "Change consent",
        "retry" to "Retry",
        "transcribeNow" to "Transcribe now",
        "presentDidntAnswer" to "Present, didn't answer",
        "orderUncertain" to "Order uncertain",
        "estimatedDate" to "Estimated date",
        "needsDecision" to "Needs a decision",
        "onThisDevice" to "On this device",
        "legacyRecord" to "Legacy record",
        "recordedUnderEarlierWording" to "Recorded under earlier wording",
        "fromRecording" to "From recording",
        "accountsDiffer" to "Accounts differ",
        "moreTakes" to "{count} more takes",
        "retiredQuestions" to "Retired questions",
        "showRemoved" to "Show removed ({count})",
        "consentNotice" to "Recordings of {name} may be sent to outside services to be written down or translated.",
        "consentRefusedNotice" to "Recordings of {name} are not sent to outside services: consent was refused.",
        "consentSwitchOff" to "Switch off",
        "consentSwitchOn" to "Switch back on",
        "recordSummary" to "{covered} of {total} sections · {sessions} sessions · {repeats} repeats",
        "openSections" to "Open: {codes}",
        "continueWith" to "Continue with {name}",
        "exportCsv" to "Export CSV",
        "viewTimeline" to "Timeline",
        "viewByQuestion" to "By question",
        "recordLoadError" to "Couldn't load the questionnaire.",
        "tryAgain" to "Try again",
        "noSessionsYet" to "No sessions yet.",
        "inboxTitle" to "Inbox",
        "inboxToFile" to "To file",
        "inboxNeedsDecision" to "Needs a decision",
        "inboxLegacy" to "Legacy question",
        "inboxSetDates" to "Set dates",
        "inboxWhoWasThere" to "Who was there",
        "inboxEmptySession" to "Empty session",
        "inboxPrimaryChanged" to "Primary changed",
        "missingOutboxFile" to "The file for {target} ({date}) is missing on this device.",
        "batteryReason" to "So long recordings aren't stopped.",
        "notificationRecording" to "Recording {target} · {elapsed}",
        "pause" to "Pause",
        "resume" to "Resume",
        "stop" to "Stop",
        "save" to "Save",
        "keepBoth" to "Keep both",
        "keepOne" to "Keep one…",
        "notNeeded" to "Not needed",
        "fileIt" to "File it",
        "confirm" to "Confirm",
        "setDate" to "Set date",
        "delete" to "Delete",
        "twoTakesOf" to "Two takes of {target} for {name}, {date} {first} and {second}",
        "legacySessionsRange" to "{count} legacy sessions, {range}",
        "sessionHasNoArtisans" to "Session of {date} has no artisans",
        "emptyApproved" to "Empty session, approved {date}",
        "mainTakeChanged" to "{name}'s main take of {target} changed (late upload)",
        "keepCurrent" to "Keep current",
        "transcribed" to "Transcribed",
        "transcribing" to "Transcribing",
        "continueOneSession" to "One session",
        "continueOneEach" to "One each",
        "answerExists" to "{name} already has an answer.",
        "mergeWrongInstrument" to "That recording belongs to the {title}.",
        "addResearcher" to "Add researcher",
        "thisSitting" to "This sitting",
        "alsoQuestionTakes" to "{codes} also have question takes.",
        "replacesOnceUploaded" to "Replaces take {n} once uploaded",
        "earlierTitle" to "Earlier title: {title}",
        "transcriptChanged" to "Transcript changed since this answer",
        "history" to "History",
        "flagsLine" to "{repeats} repeats · {decisions} needs a decision · dates estimated",
        "returnForChanges" to "Return",
        "approve" to "Approve",
        "approveBlockedDecide" to "Decide on {count} take first.",
        "approveBlockedNoParticipants" to "Add who was there first.",
        "approveBlockedEmpty" to "Nothing recorded or answered yet.",
        "changesOnApproved" to "{count} changes on an approved session",
        "continueAnotherDay" to "Continue another day",
        "deleteSessionTitle" to "Delete this session?",
        "restore" to "Restore",
        "modeSomeoneElse" to "Someone else",
        "fieldGuestName" to "Their name",
        "guestBadge" to "Guest",
        "addNote" to "Add a note",
        "currentAnswer" to "Current",
        "stateTyped" to "Typed",
        "addRecordingPrompt" to "Add a recording?",
        "stateNeedsReview" to "Needs review",
        "formsDeleted" to "Deleted",
        "deleteSessionBody" to "Admins can restore it from Deleted.",
        "removedTakes" to "Removed takes",
        "formsTitle" to "Forms",
        "newForm" to "New form",
        "needsConnection" to "Needs a connection",
        // Joined the table with the web's fix tray and start sheet (U1, 22 Oct 2026).
        "outboxSendAgain" to "Send again",
        "outboxDiscardTitle" to "Discard this?",
        "outboxSendWithout" to "Send without the conflicting answers",
        "useMyLocation" to "Use my location"
    )

    /** The keys the screens use, each pointing at its shared key. */
    val sharedKeyOf: Map<String, String> = linkedMapOf(
        "sessions.title" to "sessionsTitle",
        "sessions.new" to "newSession",
        "sessions.empty" to "noSessionsYet",
        "sessions.inbox" to "inboxTitle",
        "start.workshop" to "fieldWorkshop",
        "start.fieldVisit" to "fieldVisitNoWorkshop",
        "start.instrument" to "fieldInstrument",
        "start.date" to "fieldDate",
        "start.mode" to "fieldMode",
        "start.mode.individual" to "modeIndividual",
        "start.mode.group" to "modeGroup",
        "start.artisans" to "fieldArtisans",
        "start.purpose" to "fieldPurpose",
        "purpose.FIRST_SITTING" to "purposeFirstSitting",
        "purpose.FOLLOW_UP" to "purposeFollowUp",
        "purpose.REDO" to "purposeRedo",
        "start.place" to "fieldPlace",
        "start.language" to "fieldLanguage",
        "start.languageOther" to "languageOtherLabel",
        "start.note" to "fieldNote",
        "start.notePlaceholder" to "notePlaceholder",
        "start.titlePreview" to "titlePreviewLabel",
        "start.submit" to "startSession",
        "p1.title" to "openMatchTitle",
        "p1.new" to "openMatchNew",
        "p1.join" to "openMatchJoin",
        "consent.ok" to "ok",
        "consent.switchOff" to "consentSwitchOff",
        "consent.switchOn" to "consentSwitchOn",
        "consent.waiting" to "waitingForConsent",
        "consent.record" to "recordConsent",
        "workspace.finish" to "finish",
        "workspace.addArtisan" to "addArtisan",
        "workspace.typeAnswers" to "typeAnswers",
        "workspace.recordSection" to "recordSection",
        "workspace.record" to "record",
        "workspace.recordTake" to "recordTake",
        "workspace.uploadFile" to "uploadFile",
        "workspace.present" to "present",
        "workspace.away" to "away",
        "workspace.answeredBy" to "answeredBy",
        "workspace.justThese" to "justThese",
        "upload.uploaded" to "uploaded",
        "upload.waiting" to "waitingForSignal",
        "upload.failed" to "uploadFailedRetrying",
        "upload.onDevice" to "onThisDevice",
        "leave.title" to "leaveTitle",
        "leave.stay" to "stay",
        "leave.leave" to "leave",
        "finish.title" to "finishTitle",
        "finish.button" to "finish",
        "p2.title" to "alreadyRecordedTitle",
        "p2.why" to "whyAgain",
        "p2.cancel" to "cancel",
        "p2.recordAgain" to "recordAgain",
        "p2.uploadAgain" to "uploadAgain",
        "p2.more" to "moreOptions",
        "p2.partTwo" to "continueAsPart2",
        "p2.replace" to "replaceEarlierTake",
        "p2.different" to "useDifferentQuestion",
        "reason.REDO" to "reasonRedo",
        "reason.FOLLOW_UP" to "reasonFollowUp",
        "reason.RE_INTERVIEW" to "reasonReInterview",
        "reason.NEW_SETTING" to "reasonNewSetting",
        "p3.keep" to "p3Keep",
        "p5.title" to "p5Title",
        "p5.ok" to "ok",
        "p5.merge" to "p5Merge",
        "p7.cancel" to "cancel",
        "p7.replace" to "replace",
        "p7r.body" to "p7rBody",
        "p7r.remove" to "remove",
        "p9.title" to "p9Title",
        "p9.theirs" to "keepTheirs",
        "p9.mine" to "keepMine",
        "p10.remove" to "remove",
        "p12.hint" to "p12Hint",
        "p13.title" to "p13Title",
        "p13.discard" to "discard",
        "p13.keep" to "keep",
        "p14.banner" to "p14Banner",
        "p14.update" to "update",
        "transcript.paused" to "transcriptionPaused",
        "transcript.pausedReason" to "transcriptionPausedReason",
        "transcript.refused" to "consentRefusedStatus",
        "transcript.retry" to "retry",
        "transcript.now" to "transcribeNow",
        "record.error" to "recordLoadError",
        "record.tryAgain" to "tryAgain",
        "record.empty" to "noSessionsYet",
        "record.startSession" to "startSession",
        "record.timeline" to "viewTimeline",
        "record.byQuestion" to "viewByQuestion",
        "record.uncertain" to "orderUncertain",
        "record.present" to "presentDidntAnswer",
        "record.wholeSession" to "wholeSession",
        "record.needsDecision" to "needsDecision",
        "record.estimated" to "estimatedDate",
        "record.legacy" to "legacyRecord",
        "record.exportCsv" to "exportCsv",
        "record.earlierWording" to "recordedUnderEarlierWording",
        "record.accountsDiffer" to "accountsDiffer",
        "record.retired" to "retiredQuestions",
        "record.fromRecording" to "fromRecording",
        "record.group" to "modeGroup",
        "inbox.title" to "inboxTitle",
        "inbox.toFile" to "inboxToFile",
        "inbox.needsDecision" to "inboxNeedsDecision",
        "inbox.legacy" to "inboxLegacy",
        "inbox.setDates" to "inboxSetDates",
        "inbox.noParticipants" to "inboxWhoWasThere",
        "inbox.empty" to "inboxEmptySession",
        "inbox.primaryChanged" to "inboxPrimaryChanged",
        "join.submit" to "openMatchJoin",
        "battery.reason" to "batteryReason",
        "recorder.pause" to "pause",
        "recorder.resume" to "resume",
        "recorder.stop" to "stop",
        "save" to "save",
        "inbox.keepBoth" to "keepBoth",
        "inbox.keepOne" to "keepOne",
        "inbox.notNeeded" to "notNeeded",
        "inbox.fileIt" to "fileIt",
        "inbox.confirm" to "confirm",
        "inbox.setDate" to "setDate",
        "inbox.save" to "save",
        "inbox.keep" to "keep",
        "inbox.delete" to "delete",
        "inbox.ok" to "ok",
        "inbox.partTwo" to "continueAsPart2",
        "p8.keepCurrent" to "keepCurrent",
        "p8.replace" to "replace",
        "transcript.done" to "transcribed",
        "transcript.running" to "transcribing",
        "continue.one" to "continueOneSession",
        "continue.each" to "continueOneEach",
        "tray.discard" to "discard",
        "session.delete" to "delete",
        "session.cancel" to "cancel",
        "take.replace" to "replace",
        "researcher.add" to "addResearcher",
        "answer.history" to "history",
        "answer.transcriptChanged" to "transcriptChanged",
        "workspace.thisSitting" to "thisSitting",
        "read.approve" to "approve",
        "read.return" to "returnForChanges",
        "read.blockedNoParticipants" to "approveBlockedNoParticipants",
        "read.blockedEmpty" to "approveBlockedEmpty",
        "answer.note" to "fieldNote",
        "take.history" to "history",
        "home.sessions" to "sessionsTitle",
        "session.continue" to "continueAnotherDay",
        "session.deleteTitle" to "deleteSessionTitle",
        "take.restore" to "restore",
        "guest.someoneElse" to "modeSomeoneElse",
        "guest.name" to "fieldGuestName",
        "guest.label" to "guestBadge",
        "answer.noteAdd" to "addNote",
        "answer.current" to "currentAnswer",
        "mark.typed" to "stateTyped",
        "mark.addRecording" to "addRecordingPrompt",
        "override.NEEDS_REVIEW" to "stateNeedsReview",
        "sessions.deleted" to "formsDeleted",
        "session.restore" to "restore",
        "session.softDeleteBody" to "deleteSessionBody",
        "session.removedTakes" to "removedTakes",
        "home.forms" to "formsTitle",
        "home.newForm" to "newForm",
        "form.needsSignal" to "needsConnection",
        "tray.sendAgain" to "outboxSendAgain",
        "tray.discardTitle" to "outboxDiscardTitle",
        "tray.sendWithout" to "outboxSendWithout",
        "place.useLocation" to "useMyLocation"
    )

    /**
     * Sentences only Android shows (the join screen, the battery request, the menu), with no entry in
     * the shared table yet. `SharedStringsTest` pins this list, so a new one is a deliberate choice.
     * They move into `shared/strings/questionnaire.json` with the next BACKEND table revision.
     */
    val androidOnly: Map<String, String> = linkedMapOf(
        "sessions.builder" to "Builder",
        "sessions.join" to "Join session",
        "start.artisan" to "Artisan",
        "inbox.nothing" to "Nothing to sort out.",
        "inbox.error" to "Couldn't load the inbox.",
        "join.scan" to "Scan QR code",
        "join.pick" to "Pick an image",
        "join.code" to "Join code",
        "join.notFound" to "No session has that code.",
        "join.noQr" to "No join code found in that picture.",
        "join.onlineOnly" to "The code needs a signal. Scan the QR code to join offline.",
        "join.rateLimited" to "Too many wrong codes. Wait a few minutes, or scan the QR code.",
        "battery.title" to "Keep recording in the background",
        // Transcript words and errors that were hard-coded in the screens (audit Low, 21 Oct 2026).
        "transcript.failed" to "Transcription failed",
        "transcript.queued" to "Queued to transcribe",
        "error.removeTake" to "Couldn't remove the take. Try again with a signal.",
        "error.readFile" to "Couldn't read that file.",
        "error.recover" to "Couldn't recover that recording.",
        "error.startSession" to "Couldn't start the session on this device.",
        "error.recorderStart" to "Couldn't start the recorder.",
        "error.queueTake" to "That take couldn't be queued. It is kept on this phone.",
        "error.needsSignal" to "That needs a signal. Try again when you're online.",
        "error.saveOnPhone" to "Couldn't save that on this phone.",
        "error.changedElsewhere" to "This session changed on another device. It has been reloaded.",
        // The outbox fix tray (audit H20).
        "tray.title" to "Not sent",
        "tray.notAllowed" to "The server won't take this answer from you.",
        "tray.theirs" to "On the server",
        "tray.mine" to "On this phone",
        "tray.session" to "The session",
        "tray.newArtisan" to "A new artisan",
        "tray.participant" to "A change to who was there",
        "tray.answers" to "Typed answers",
        "tray.finish" to "Finishing the session",
        // Session actions.
        "session.more" to "More",
        "session.edit" to "Edit session",
        "session.merge" to "Merge into another session",
        "session.removeArtisan" to "Remove an artisan",
        "session.deleteBody" to "This can't be undone.",
        "session.mergeBody" to "Its takes and answers move into the session you pick. This can't be undone.",
        "session.noOtherSessions" to "No other session on this questionnaire.",
        // Takes.
        "take.removed" to "Removed",
        "take.makePrimary" to "Make main take",
        "take.madePrimary" to "This is now the main take.",
        "take.propose" to "Draft answers",
        "take.noProposals" to "Nothing to draft from this take yet.",
        "take.accept" to "Accept",
        "take.whose" to "Whose answer is this?",
        // Inbox (audit H13, H14).
        "inbox.fileTitle" to "File this recording",
        "inbox.fileSession" to "Session",
        "inbox.fileTarget" to "Question or section",
        "inbox.fileWho" to "Who answered",
        "inbox.keepThis" to "Keep this take",
        "inbox.keepOther" to "Keep the earlier take",
        "inbox.noOtherTake" to "The other take could not be found. Open the session to decide.",
        // Sessions list.
        "sessions.search" to "Search sessions",
        "sessions.loadMore" to "Load more",
        "sessions.capped" to "Showing the first results. Narrow the search to see more.",
        // Offline artisan registration.
        "artisan.new" to "+ New artisan",
        "artisan.name" to "Name",
        "artisan.place" to "Village or town",
        "artisan.aadhaar" to "Aadhaar number",
        "artisan.aadhaarNotShared" to "Aadhaar not shared by the artisan",
        "artisan.dos" to "Do's",
        "artisan.donts" to "Don'ts",
        "artisan.register" to "Register artisan",
        "artisan.onPhone" to "Registered on this phone",
        // Artisan record export.
        "csv.saved" to "Saved to Downloads.",
        "csv.failed" to "Couldn't export the CSV.",
        // ── Designer portal additions (UNIFY §1, 22 Oct 2026). They join the shared table with the
        // web's copy (U1); until then they are pinned here, word for word as the spec writes them.
        // Guests (U6, A17, A18).
        "guest.myself" to "Myself",
        "guest.notes" to "Notes",
        "guest.add" to "+ Add someone else",
        "guest.edit" to "Edit name and notes",
        "guest.register" to "Register as an artisan",
        "guest.registerPick" to "Which artisan is this?",
        "guest.registered" to "Their answers and takes moved to the artisan.",
        "guest.needsName" to "Give their name.",
        // Answers: locks, notes, history (U9, A11, A20, B21).
        "answer.prefill" to "Fill from profile",
        "answer.noHistory" to "No earlier versions.",
        "answer.locked" to "Only its author, a lead or an admin can change it.",
        // Coverage (D1, D4).
        "override.COMPLETED" to "Marked complete",
        "override.NEEDS_REDO" to "Needs redo",
        "coverage.title" to "Workshop coverage",
        "coverage.legend" to "● recorded  ✎ typed  ◐ partial  ◎ uploading  ⚠ needs decision  ○ none",
        "coverage.pickForm" to "Form",
        "coverage.empty" to "No artisans recorded on this form yet.",
        // Sessions: deleted, restored, researchers, read view (A14, B6, B27).
        "sessions.deletedEmpty" to "No deleted sessions.",
        "session.restored" to "Session restored.",
        "session.read" to "Read view",
        "researcher.pick" to "Researcher",
        "researcher.role" to "Role",
        "researcher.roleContributor" to "Researcher",
        "researcher.roleVolunteer" to "Volunteer",
        "researcher.added" to "Researcher added.",
        "read.approved" to "Approved.",
        "read.returned" to "Returned for changes.",
        "read.returnNote" to "What should change?",
        "take.noHistory" to "Nothing has changed on this take.",
        // The questionnaire home (UNIFY §4.2 Android).
        "home.back" to "Back",
        "place.notFound" to "Could not find where you are. Type the place.",
        "place.permission" to "Allow location to fill the place, or type it.",
        "form.pick" to "Pick a form",
        "form.bound" to "Bound to this workshop",
        "sessions.filters" to "Filters",
        "filter.from" to "From",
        "filter.to" to "To"
    )

    /** Every fixed string the screens ask for by key. */
    val strings: Map<String, String> = sharedKeyOf.mapValues { (_, key) -> shared.getValue(key) } + androidOnly

    fun s(key: String): String = strings[key] ?: error("No questionnaire string '$key'")

    private val PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9]*)\\}")
    private val ONE_PLURAL = Regex("\\b1 (takes|sections|artisans|sessions|repeats|recordings)\\b")

    /**
     * Fills a shared template. A count of 1 reads "1 take", not "1 takes": the table writes the plural
     * and both clients singularise the noun right after a lone 1.
     */
    fun t(key: String, vararg values: Pair<String, Any?>): String {
        val template = shared[key] ?: error("No shared questionnaire string '$key'")
        val map = values.toMap()
        val filled = PLACEHOLDER.replace(template) { m ->
            val name = m.groupValues[1]
            require(name in map) { "'$key' needs {$name}" }
            map[name]?.toString().orEmpty()
        }
        return ONE_PLURAL.replace(filled) { "1 " + it.groupValues[1].removeSuffix("s") }
    }

    fun day(date: LocalDate): String = date.format(DAY_MONTH)
    fun clock(instant: Instant): String = instant.atZone(Naming.IST).format(CLOCK)

    fun reasonLabel(reason: String) = strings["reason.$reason"] ?: reason
    fun purposeLabel(purpose: String) = strings["purpose.$purpose"] ?: purpose

    fun mode(count: Int): String = if (count <= 1) t("modeIndividual") else t("modeGroupOf", "count" to count)
    fun setting(rosterCount: Int): String = if (rosterCount <= 1) t("solo") else t("modeGroupOf", "count" to rosterCount)

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
        age < Duration.ofHours(1) -> t("checkedAgo", "age" to "${age.toMinutes()} min")
        age < Duration.ofDays(1) -> t("checkedAgo", "age" to "${age.toHours()} h")
        else -> t("checkedAgo", "age" to "${age.toDays()} d")
    }

    // ── Start sheet ───────────────────────────────────────────────────────────────────────────

    data class ArtisanCoverage(val firstName: String, val sectionsCovered: Int)

    /** §4.2: one, up to three, or more than three artisans; the snapshot suffix when offline. */
    fun coverageLine(artisans: List<ArtisanCoverage>, sectionsTotal: Int, checkedAgo: String?): String? {
        if (artisans.isEmpty()) return null
        val body = when {
            artisans.size == 1 -> artisans.single().let { t("coverageOne", "name" to it.firstName, "covered" to it.sectionsCovered, "total" to sectionsTotal) }
            artisans.size <= 3 -> artisans.joinToString(" · ") {
                if (it.sectionsCovered == 0) t("coverageNoneYet", "name" to it.firstName)
                else t("coverageShort", "name" to it.firstName, "covered" to it.sectionsCovered, "total" to sectionsTotal)
            } + "."
            // The ▾ is the expand affordance, not copy.
            else -> t("coverageMany", "withTakes" to artisans.count { it.sectionsCovered > 0 }, "count" to artisans.size) + " ▾"
        }
        return if (checkedAgo == null) body else "$body · $checkedAgo"
    }

    fun p1Body(researcher: String, names: List<String>, startedAt: Instant): String =
        t("openMatchBody", "researcher" to researcher, "artisans" to joinNames(names), "time" to clock(startedAt))

    /** Consent is assumed by default (19 Oct 2026): the notice, or the refusal on record. */
    fun consentNotice(firstName: String, refused: Boolean = false) =
        t(if (refused) "consentRefusedNotice" else "consentNotice", "name" to firstName)

    // ── Workspace ─────────────────────────────────────────────────────────────────────────────

    fun joinCode(code: String) = t("joinCode", "code" to code)
    fun uploading(percent: Int) = t("uploadingPercent", "percent" to percent)
    fun takesUploading(count: Int) = t("takesUploading", "count" to count)
    fun leaveBody(pending: Int) = t("leaveBodyAndroid", "count" to pending)

    /** The second sentence is left out when nothing is still uploading. */
    fun finishBody(sections: Int, artisans: Int, uploading: Int): String {
        val full = t("finishBody", "sections" to sections, "artisans" to artisans, "pending" to uploading)
        return if (uploading == 0) full.substringBefore(". ") + "." else full
    }
    fun spaceLeft(minutes: Long) = t("spaceLeft", "minutes" to minutes)
    fun notification(label: String, elapsedMs: Long) = t("notificationRecording", "target" to label, "elapsed" to elapsed(elapsedMs))
    fun missingFile(label: String) = SessionOutbox.missingFileMessage(label)

    // ── P2 (§4.4) ─────────────────────────────────────────────────────────────────────────────

    /** "Section D is recorded for 2 of 3 here." or the one-subject variants. */
    fun p2Body(targetLabel: String, coveredNames: List<String>, total: Int, onDate: LocalDate?, viaSection: Boolean): String {
        if (total == 1 && coveredNames.size == 1) {
            val key = if (viaSection) "alreadyRecordedWhole" else "alreadyRecordedOne"
            val text = t(key, "target" to targetLabel, "name" to coveredNames.single(), "date" to (onDate?.let(::day) ?: ""))
            return if (onDate == null) text.replace(" on .", ".") else text
        }
        return t("alreadyRecordedBody", "target" to targetLabel, "covered" to coveredNames.size, "count" to total)
    }

    /** "Giriraj · 14 min · Nandini · 7 Jul · Group of 3". */
    fun p2TakeLine(name: String, durationMs: Long, researcher: String?, date: LocalDate?, rosterCount: Int): String =
        listOfNotNull(name, minutes(durationMs), researcher?.ifBlank { null }, date?.let(::day), setting(rosterCount)).joinToString(" · ")

    fun p2FirstTake(name: String, targetCode: String) = t("firstTakeFor", "name" to name, "target" to targetCode)

    fun alsoRecorded(date: LocalDate) = t("alsoRecordedOn", "date" to day(date))

    // ── Other prompts (§4.5) ──────────────────────────────────────────────────────────────────

    fun p3Title(label: String) = t("p3Title", "target" to label)
    fun p3Body(elapsedMs: Long) = t("p3Body", "duration" to elapsed(elapsedMs))
    fun p3Stop(nextCode: String) = t("p3Stop", "code" to nextCode)
    fun p5Body(name: String, date: LocalDate) = t("p5Body", "name" to name, "date" to day(date))
    fun p5Add(name: String) = t("p5AddTo", "name" to name)
    fun p7Title(label: String, position: Int = 1) = t("p7Title", "n" to position, "target" to label)
    fun p7Body(position: Int = 1) = t("p7Body", "n" to position, "next" to position + 1)
    fun p7rTitle(position: Int, label: String) = t("p7rTitle", "n" to position, "target" to label)
    fun p8Title(name: String, code: String) = t("p8Title", "name" to name, "code" to code)
    fun p8Body(researcher: String, date: LocalDate) = t("p8Body", "researcher" to researcher, "date" to day(date))
    fun p9Body(researcher: String, name: String, code: String, at: Instant) =
        t("p9Body", "researcher" to researcher, "name" to name, "code" to code, "time" to clock(at))
    fun p10Title(name: String) = t("p10Title", "name" to name)
    fun p10Body(count: Int) = t("p10Body", "count" to count)
    fun p11(count: Int) = t("p11Hint", "count" to count)
    fun p13Body(minutes: Long, label: String) = t("p13Body", "minutes" to minutes, "target" to label)
    fun takeOf(researcher: String, date: LocalDate) = t("takeOf", "researcher" to researcher, "date" to day(date))

    // ── Artisan record (§4.6) ────────────────────────────────────────────────────────────────

    fun recordSummary(covered: Int, total: Int, sessions: Int, repeats: Int): String =
        t("recordSummary", "covered" to covered, "total" to total, "sessions" to sessions, "repeats" to repeats)
    fun openSections(codes: List<String>) = t("openSections", "codes" to codes.joinToString(", "))
    fun continueWith(firstName: String) = t("continueWith", "name" to firstName)
    fun showRemoved(count: Int) = t("showRemoved", "count" to count)
    fun moreTakes(count: Int) = t("moreTakes", "count" to count)
    /** "Group answer (3)"; plain "Group" when the record carries no roster size for the answer. */
    fun groupAnswer(count: Int?) = if (count == null || count < 2) s("record.group") else t("groupAnswer", "count" to count)
    fun take(position: Int) = t("takeN", "n" to position)
    fun answerForLabel() = t("answerFor").removeSuffix(":")

    private fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    fun firstName(name: String): String = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

    // ── Designer portal additions ────────────────────────────────────────────────────────────

    /** P15, inline: "Answered by Nandini. Only they or a lead can change it." */
    fun locked(authorName: String?): String {
        val who = authorName?.trim()?.ifBlank { null } ?: "someone else"
        return "Answered by $who. Only they or a lead can change it."
    }

    /** A19: Finish reports what is left, and never blocks. */
    fun requiredUnanswered(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 required question unanswered."
        else -> "$count required questions unanswered."
    }

    fun earlierTitle(title: String) = t("earlierTitle", "title" to title)
    fun flagsLine(repeats: Int, decisions: Int, estimated: Boolean): String {
        val full = t("flagsLine", "repeats" to repeats, "decisions" to decisions)
        return if (estimated) full else full.removeSuffix(" · dates estimated")
    }
    fun approveBlockedDecide(count: Int) = t("approveBlockedDecide", "count" to count)
    fun changesOnApproved(count: Int) = t("changesOnApproved", "count" to count)
    fun alsoQuestionTakes(codes: List<String>) = t("alsoQuestionTakes", "codes" to codes.joinToString(", "))
    fun replacesOnceUploaded(n: Int) = t("replacesOnceUploaded", "n" to n)
    fun overrideLabel(status: String?): String? = status?.let { strings["override.$it"] }
}
