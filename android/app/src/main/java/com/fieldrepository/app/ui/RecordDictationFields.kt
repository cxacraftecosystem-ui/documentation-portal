package com.fieldrepository.app.ui

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *  WHICH BOXES ON A RECORD FORM CARRY A MICROPHONE — AS DATA, NOT AS A PATTERN.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * ── WHY A TABLE AND NOT A FLAG AT EACH CALL SITE ──────────────────────────────────────────────
 *
 * The instruction is "every record field that can reasonably be dictated, on both clients". Both
 * halves of that are claims about a SET, and a set that exists only as ~120 scattered call sites in
 * a 13,604-line file is a set nobody can check. Two things go wrong, and both go wrong quietly:
 *
 *  1. A COLUMN ADDED NEXT YEAR gets a microphone or does not get one depending on which neighbour
 *     the author copied. Nothing reports it; the box simply has no mic, which is indistinguishable
 *     from a decision that it should not.
 *  2. THE TWO CLIENTS DRIFT. `frontend/components/forms/ProductForm.tsx` answers the identical
 *     instruction in a language nobody re-reads while editing Kotlin. A researcher who fills a
 *     product in on a handset and corrects it in a browser then meets two different forms.
 *
 * So the classification is data. Every record box asks `recordDictates`; `RecordProseTest` checks
 * the two tables against each other AND against the wire columns the `*CreateRequest` DTOs declare,
 * and requires every exclusion to carry a written reason; `RecordDictationParityTest` reads BOTH the
 * Kotlin call sites and the web's own `.tsx` and holds the two to a stated relationship. A column in
 * neither table fails on a laptop rather than shipping as an omission that looks like a decision.
 *
 * ── `recordDictates` FAILS CLOSED, AND THE TESTS ARE WHAT MAKE THAT SAFE ──────────────────────
 *
 * An unknown column returns false — no microphone — because the alternative (throwing) would crash
 * a record form in a courtyard over a typo. A silent false is only acceptable because
 * `RecordDictationParityTest` turns every unknown literal that reaches this function into a red
 * build; delete that test and this becomes the failure mode it exists to prevent.
 *
 * ── THE KEYS ARE API COLUMN NAMES, NOT LABELS ────────────────────────────────────────────────
 *
 * "Toolkit name" on the handset is "Toolkit name" in the browser, but "Or new craft name" is
 * `craftName` in both payloads and "Time taken to complete" is `timeTakenToCompleteProduct`. The
 * LABELS are allowed to differ between the clients; the columns are not, and the columns are what
 * the payload, the CSV and the review diff are keyed by.
 *
 * FOUR GROUPS OF KEYS ARE NOT `*CreateRequest` FIELD NAMES, and each is named where it is declared:
 * PROCESS's `step*` keys (columns on `ProcessStepRequest`, prefixed so `name` on this surface can
 * still mean the process's own name), PROCESS's `artisanId` (a selector that narrows the product
 * list and is never sent), MEDIA's two form keys (the upload is multipart form-data and has no
 * create-request at all), and LOCATION's `locationAddress` (the browser's own box name for what
 * `LocationRequest` spells `address` — `frontend/components/forms/LocationFields.tsx:2105`).
 *
 * ── THIS FILE HAS NO ANDROID IMPORTS, AND THAT IS LOAD-BEARING ───────────────────────────────
 *
 * Same split as `ui/RecordProseText.kt:20-26`: a JUnit assertion needs a function with no
 * `Context`, no `SpeechRecognizer` and no composition in it. `RecordProseTest` reads these two maps
 * directly on a desktop JVM, which is only possible while nothing here drags `android.*` in.
 */

/** One record surface. `LOCATION` is the shared address card, not a record of its own. */
enum class RecordFormKind { CRAFT, ARTISAN, WORKSHOP, PRODUCT, TOOL, PROCESS, MEDIA, LOCATION }

/**
 * The boxes that carry a microphone, per surface.
 *
 * Read against the seven forms under `frontend/components/forms` and the three page-inline
 * forms; the parity test compares the two sets directly, so an edit here that the browser did
 * not get is a red build — and `RecordDictationParityTest` carries two shrinking allow-lists,
 * `WEB_NOT_YET` and `WEB_ONLY_TODAY`, for the columns where one client is still ahead of the
 * other. Both are meant to reach zero; neither is a place to park a disagreement.
 */
val RECORD_DICTATED: Map<RecordFormKind, Set<String>> = mapOf(
    RecordFormKind.CRAFT to setOf("name", "localName", "category", "place", "description"),
    RecordFormKind.ARTISAN to setOf("name", "localName", "craftName", "place", "address", "notes"),
    RecordFormKind.WORKSHOP to setOf("title", "place", "description", "notes"),
    RecordFormKind.PRODUCT to setOf(
        "productName", "localName", "craftName", "artisanName", "place",
        "timeTakenToCompleteProduct", "size",
        "rawMaterialsUsed", "mainToolsUsed", "productFunctionUse", "remarks",
    ),
    RecordFormKind.TOOL to setOf(
        "toolkitName", "localName", "englishName", "craftName", "artisanName", "place",
        "processUsedIn", "material",
        "suggestionsForToolImprovement", "remarks",
    ),
    // `stepName` and `stepNotes` are `ProcessStepRequest.name` and `.notes` — they are columns on
    // the step rows inside the process payload, not on the process itself, and they are spelled
    // apart here because `name` on this surface already means the process's own name.
    RecordFormKind.PROCESS to setOf("name", "stepName", "stepNotes"),
    // The media upload creates no record and has no `*CreateRequest`; `mediaTitle` and `caption` are
    // the two form keys the web's own upload form uses
    // (`app/(protected)/media/page.tsx:343,369`) and the two `TextInput`s Android draws
    // (`MainActivity.kt:10269,10293`).
    RecordFormKind.MEDIA to setOf("mediaTitle", "caption"),
    /*
     * LOCATION IS CLASSIFIED HERE BUT NOT YET WIRED ON THE HANDSET — 2026-09-13.
     *
     * The address card's village box lives in `ui/LocationFields.kt` and is still a bare
     * `OutlinedTextField(… singleLine = true)`. Converting it onto `RecordProseField` was left
     * out of this commit because that file belonged to another change in flight, and a
     * half-applied edit to a shared address card is worse than a late one. It is the first item
     * of this work's hand-off list, and it is now the URGENT one: the browser has ALREADY shipped
     * the microphone (`components/forms/LocationFields.tsx` mounts a dictated input on this very
     * box), so the two clients differ on it today.
     *
     * The CLASSIFICATION is nonetheless correct and belongs here now: `RecordProseTest`'s coverage
     * arms assert every one of `LocationRequest`'s thirteen form columns is decided, and twelve of
     * the thirteen carry the argument for why a microphone must NOT appear there. When the box is
     * converted, the conversion must ALSO fold line breaks on write — `RecordProseField` has no
     * `singleLine` parameter, so the converted box gains a newline key and a microphone that can
     * commit a phrase containing one, and a stored newline in a village name is an invisible
     * broken cell in the "Village/Place" export column. Until that lands, this one entry promises
     * a control the handset does not draw, and this paragraph is here so nobody reads the gap as
     * a decision.
     */
    RecordFormKind.LOCATION to setOf("village"),
)

/**
 * The boxes that deliberately do not, and the argument for each.
 *
 * A REASON, NOT A LABEL. "This box has no microphone" is an observation anybody can make from the
 * screen; the only thing worth writing down is why. `RecordProseTest` requires each of these to be
 * at least sixty characters and to end in a full stop, because an empty string or the column name
 * restated would satisfy a coverage test while classifying nothing.
 *
 * SOME OF THESE ARE NOT BOXES AT ALL — `Workshop.date`, `Process.notes`, `stepSortOrder`,
 * `Location.capturedAt` and the two geocoder columns have no control on either client. They are
 * classified anyway, and that is the point: a wire column this test cannot see is a wire column
 * nobody has to think about.
 *
 * AND FOUR ARE BOXES IN THE BROWSER ONLY — `Artisan.craftStartDate`, `Artisan.experienceMonths`,
 * `Workshop.workshopType` and `Tool.heightInches` reached the wire with the record-parity work and
 * the handset draws none of them yet. Their entries carry the dictation argument AND say so, for
 * the reason the whole file exists: "no microphone" and "no box" are different facts, and a reader
 * who cannot tell them apart will read the second as the first and close the wrong gap. Each is a
 * closed picker, a calendar or a measurement, so the dictation answer will not change on the day
 * the control lands — which is exactly why deciding it now costs nothing and settles it.
 */
val RECORD_NOT_DICTATED: Map<RecordFormKind, Map<String, String>> = mapOf(
    RecordFormKind.CRAFT to mapOf(
        "workshopId" to "A closed vocabulary behind a picker; the answer is chosen from workshops the server already knows, and there is no free text to speak.",
    ),
    RecordFormKind.ARTISAN to mapOf(
        "workshopId" to "A closed vocabulary behind a picker; nothing is typed here, so a microphone would have nowhere to put a word.",
        "craftId" to "A closed vocabulary behind a dropdown. The free-text escape hatch beside it is `craftName`, and that one is dictated.",
        "gender" to "Four fixed options behind a dropdown; picking is already faster than a spoken sentence and a recogniser can only mis-hear one of four.",
        "dateOfBirth" to "A calendar field. A recogniser returns \"the fourth of March nineteen seventy one\", and the two readings of an ambiguous date are both valid dates, so a mis-transcribed birthday is a defect nothing anywhere reports.",
        // Added 2026-09-14: the record-parity work put this column on the wire and the coverage test
        // found it in neither table.
        "craftStartDate" to "A calendar date behind a date picker, and the argument the `dateOfBirth` entry directly above makes, one column later: both readings of a spoken date are valid dates. It is worse here, because the record sheet DERIVES the printed experience figure from this in preference to `experienceYears` — a mis-heard year is wrong on every sheet printed from then on, and the sheet is read years after the visit.",
        "experienceYears" to "A digits-only box bounded 0..90. Recognisers spell numbers out in words, which this box's own filter discards silently, leaving it empty after a spoken answer.",
        // Added 2026-09-14, with `craftStartDate` above and for the same reason.
        "experienceMonths" to "A closed 0..11 list behind a picker; nothing is typed, so a microphone would have nowhere to put a word, and the browser's own box says exactly that beside it. Eleven and not twelve because this is a REMAINDER — twelve months is a year the `experienceYears` box above already holds — and \"twelve\" is precisely the answer a spoken sentence offers into a column whose CHECK constraint then refuses it.",
        "phone" to "A number behind a dial-code control, with the same digits-as-words problem and no tolerance at all for a wrong digit.",
        "email" to "A recogniser writes \"at\" for the @ sign and punctuates a domain, so the box would reliably produce a value its own validator then refuses.",
        "aadhaarNumber" to "A regulated identity number. A mis-heard digit fails the checksum on the server, or worse passes it and files this artisan under somebody else's number.",
        "pehchanCardAvailable" to "A yes/no answer behind a dropdown; a recogniser answers a yes/no question with a sentence.",
        "pehchanCardNumber" to "A regulated identity number on a unique index. A wrong one is stored against the wrong person and then blocks the artisan who genuinely holds that card.",
        "dos" to "`NumberedListInput` is a numbered-list control whose whole interaction is \"press Enter for each new point\", and a microphone appending into one row would fight it — see its own comment for the full argument.",
        "donts" to "The same control and the same argument as `dos`; widening the shared control for one caller is the change that would have to happen first.",
        "status" to "A closed vocabulary behind a control most users cannot even set, so there is no typing here to reduce.",
    ),
    RecordFormKind.WORKSHOP to mapOf(
        // Added 2026-09-14 by the coverage test, which found the kind column classified nowhere.
        "workshopType" to "Two fixed options behind a dropdown, DESIGN_PROTOTYPE or OTHER, and the answer is picked from a closed pair. This client draws no control for it at all: the kind is carried on the wire so that a correction sent from a handset cannot silently demote a workshop somebody marked in the browser, which is a reason to send the column and none whatever to speak into it.",
        "startDate" to "A calendar field, excluded for the reason every calendar field is: a spoken date is ambiguous and both readings are valid dates.",
        "endDate" to "A calendar field, and the same argument as the start date it is paired with.",
        // Added 2026-09-13 by the coverage test in this commit, which found it unclassified.
        "date" to "Not a box on either client: the legacy single-date column, written from the start date at `MainActivity.kt:5824` so older readers keep working. There is nothing on the screen to speak into.",
        "status" to "A closed vocabulary behind a control most users cannot even set, so there is no typing here to reduce.",
        "artisanIds" to "A multi-select over people the server already knows; the answer is ticked, never typed.",
        "craftIds" to "A multi-select over crafts the server already knows; the answer is ticked, never typed.",
    ),
    RecordFormKind.PRODUCT to mapOf(
        "workshopId" to "A closed vocabulary behind a picker; nothing is typed here, so a microphone would have nowhere to put a word.",
        "productType" to "Six fixed options behind a dropdown; there is no free text for a spoken answer to land in.",
        "craftId" to "A closed vocabulary behind a dropdown whose free-text escape hatch, `craftName`, is dictated instead.",
        "artisanId" to "A closed vocabulary behind a dropdown whose free-text escape hatch, `artisanName`, is dictated instead.",
        "lengthInches" to "A decimal measurement. Recognisers spell digits out in words and this box's numeric keyboard and parse discard that silently, so a spoken answer reads as no answer.",
        "breadthInches" to "A decimal measurement, and a measurement is the one thing on this form a machine also writes — a dictated word here would sit beside a grid-measured number claiming equal authority.",
        "heightInches" to "A decimal measurement, excluded for the reason the other two dimensions are: spoken digits arrive as words and are dropped without a word to the researcher.",
        "costOfMaking" to "Money. Nobody dictates \"one thousand two hundred and fifty rupees fifty paise\" into a costing sheet, and the column wants a number.",
        "sellingPrice" to "Money, and the same argument as the cost of making it is compared against.",
        "marketDemand" to "Five fixed options behind a dropdown; the answer is picked, not spoken.",
        "status" to "A closed vocabulary behind a control most users cannot even set, so there is no typing here to reduce.",
    ),
    RecordFormKind.TOOL to mapOf(
        "workshopId" to "A closed vocabulary behind a picker; nothing is typed here, so a microphone would have nowhere to put a word.",
        "craftId" to "A closed vocabulary behind a dropdown whose free-text escape hatch, `craftName`, is dictated instead.",
        "artisanId" to "A closed vocabulary behind a dropdown whose free-text escape hatch, `artisanName`, is dictated instead.",
        "yearsInUse" to "A whole number behind a numeric keyboard; spoken digits arrive as words and are discarded without a word to the researcher.",
        "height" to "A decimal measurement, and the unit-less legacy one at that — the least forgiving box on the form to put a mis-heard word into.",
        "width" to "A decimal measurement; spoken digits arrive as words and this box drops them silently.",
        "lengthInches" to "A decimal measurement that the grid-measurement route also writes, so a dictated word would compete with a machine-measured number.",
        "breadthInches" to "A decimal measurement that the grid-measurement route also writes, and the same argument as the length beside it.",
        // Added 2026-09-14: the third of the inch triple reached the wire without being classified.
        "heightInches" to "A decimal measurement, the third of the inch triple, and the same argument as the length and breadth above it. There is no box for it on this client yet either — the browser's tool form draws one and the handset's grid panel still writes the unit-less `height` above, which is the hand-off `ToolCreateRequest.heightInches`'s own KDoc names.",
        "thickness" to "A decimal measurement; spoken digits arrive as words and this box drops them silently.",
        "weight" to "A decimal measurement; spoken digits arrive as words and this box drops them silently.",
        "radius" to "A decimal measurement; spoken digits arrive as words and this box drops them silently.",
        "maker" to "Seven fixed options behind a dropdown; the answer is picked, not spoken.",
        "traditionType" to "Four fixed options behind a dropdown; the answer is picked, not spoken.",
        "replacementCost" to "Money, and the column wants a number rather than the words a recogniser hands back.",
        "status" to "A closed vocabulary behind a control most users cannot even set, so there is no typing here to reduce.",
    ),
    RecordFormKind.PROCESS to mapOf(
        "workshopId" to "A closed vocabulary behind a picker; nothing is typed here, so a microphone would have nowhere to put a word.",
        "artisanId" to "A closed vocabulary over people the server already knows, and this form has no free-text fallback beside it. Not a wire column either — it narrows the product list and is never sent.",
        "productId" to "A closed vocabulary cascaded off the chosen artisan; the list is the answer, and typing into it is not offered on either client.",
        "preProcessAvailable" to "A checkbox, and a recogniser answers a yes-or-no question with a sentence rather than with a tick.",
        "notes" to "The process-level notes column has no control on THIS client at all: it is loaded, kept in the dirty signature and sent, but never drawn, so there is no box here to put a microphone on. The browser gained one on 2026-09-13 and the two forms are now genuinely different — see RecordDictationParityTest.WEB_ONLY_TODAY, which carries that divergence and the reason a dictation sweep is not the change that closes it.",
        "status" to "A closed vocabulary behind a control most users cannot even set, so there is no typing here to reduce.",
        // The two step columns the coverage test found unclassified, 2026-09-13.
        "stepType" to "A step is added as a sequential step or as a group from the `+ Add` menu, so the kind is decided by which menu item is tapped and there is no box for a spoken word to land in.",
        "stepSortOrder" to "The row's position in the list, written by the order the steps were added and rearranged; it has no control of its own on either client and nothing to dictate into.",
    ),
    RecordFormKind.MEDIA to mapOf(
        "linkedRecordType" to "Eight fixed record types behind a dropdown; the answer is picked from a list this upload cannot invent.",
        "linkedRecordId" to "A list of entries the server already holds, narrowed by the type above; there is nothing free-text to speak.",
    ),
    RecordFormKind.LOCATION to mapOf(
        "state" to "Thirty-six options served as a list, and the district below cascades off the exact value — a near-miss transcription would silently empty the district dropdown.",
        "district" to "Seven hundred and ninety-five served names that must match exactly, because a new record is refused by the API without one.",
        "pincode" to "Six digits with a zone cross-check; spoken digits arrive as words and this box's own filter strips everything that is not 0-9, so a dictated answer lands as nothing.",
        "latitude" to "A signed decimal coordinate written by the map picker and the GPS fix, where a mis-heard digit moves the record by a hundred kilometres and looks exactly like a real answer.",
        "longitude" to "A signed decimal coordinate, and the same argument as the latitude it is meaningless without.",
        "altitude" to "A machine reading taken from the device's own fix; a person does not know it and would have nothing to say into the box.",
        "accuracy" to "A machine reading in metres, produced by the fix rather than answered by anybody.",
        "placeName" to "Written by the reverse geocoder to record what the SERVICE called this point; typing or speaking into it would destroy the only evidence of what the machine actually said.",
        "locationAddress" to "Written by the reverse geocoder for the same reason as the place name, and overwritten by the next fix. It is `LocationRequest.address` on the wire and `locationAddress` on the browser's own box.",
        "capturedAt" to "A timestamp stamped by the capture itself; there is no box on the screen at all.",
        // The artisan pin, classified 2026-09-13 when the coverage test asked about it.
        "subjectLatitude" to "Half of the pin a researcher drops on the subject's own place, written by tapping a map rather than by typing, and the server refuses half a pin anyway.",
        "subjectLongitude" to "The other half of that pin, and the same argument as the latitude it must always travel with.",
    ),
)

/** Does this box carry a microphone? **Fails closed** — see the file header for why that is safe. */
fun recordDictates(kind: RecordFormKind, column: String): Boolean =
    RECORD_DICTATED[kind]?.contains(column) == true

/**
 * The per-form lookup a record screen binds once and then asks by column.
 *
 * One `val dictates = recordDictationFor(RecordFormKind.PRODUCT)` at the top of a form, then
 * `dictate = dictates("size")` at each box — so a call site names the COLUMN, which is the thing
 * both clients agree on, and never a boolean, which is the thing that drifts.
 */
fun recordDictationFor(kind: RecordFormKind): (String) -> Boolean =
    { column -> recordDictates(kind, column) }
