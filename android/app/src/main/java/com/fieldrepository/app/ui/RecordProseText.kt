package com.fieldrepository.app.ui

import com.fieldrepository.app.ui.richtext.Align
import com.fieldrepository.app.ui.richtext.BlockKind
import com.fieldrepository.app.ui.richtext.EMPTY_RICH_DOC
import com.fieldrepository.app.ui.richtext.RichDoc
import com.fieldrepository.app.ui.richtext.fromJson
import com.fieldrepository.app.ui.richtext.isEmptyDoc
import com.fieldrepository.app.ui.richtext.toJson
import com.fieldrepository.app.ui.richtext.toPlain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *  THE RECORD FORMS' HALF OF DICTATION AND RICH TEXT — THE PART A DESKTOP JVM CAN CHECK.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `RecordProseField.kt` next door draws the controls; everything in THIS file is a pure function,
 * and the split is not tidiness. The two decisions that matter most here fail SILENTLY when they are
 * wrong — a clip going somewhere it must not, and a document coming back out of a `String?` column
 * as visible JSON braces — and neither symptom is a crash, a red screen or a failing build. They are
 * catchable only by assertion, and an assertion needs a function with no `SpeechRecognizer`, no
 * `Context` and no composition in it. That is what this file is, and `RecordProseTest` is what reads
 * it.
 *
 * ── THE REQUIREMENT, AND THE THREE REFINEMENTS IT ARRIVED WITH ────────────────────────────────
 *
 * *"In the existing pages apart from the designer workshop as well, the dictate option along with
 * the rich text formatting for the bigger fields should be there."* Refined by the person who asked
 * for it into: **on-device dictation only**, **the larger boxes only**, and **the questionnaire
 * screens are out of scope** — they already have a per-section audio workflow built around how those
 * interviews are actually conducted, and a live microphone beside it would put two capture models in
 * front of one researcher.
 *
 * ── "ON-DEVICE ONLY" IS A RULE ABOUT THIS APPLICATION'S SERVER, AND IT HAS A DOOR TO WALK PAST ─
 *
 * This is the specific version of the rule, because the general one ("nothing leaves the phone") is
 * not quite what is meant and a later reader who believes it will either weaken it or over-apply it:
 *
 *  * WHAT IS ALLOWED is the handset's own speech recogniser — the same facility the keyboard's
 *    microphone key uses. On a phone with an offline pack it never leaves the device at all; on one
 *    without, the platform's recogniser may reach the phone-maker's speech service, exactly as the
 *    keyboard would. That is the phone's own arrangement with its owner, it is what the browser's
 *    `SpeechRecognition` does on the web side of this same feature, and neither this app's server nor
 *    its transcription provider is in it.
 *
 *  * WHAT IS FORBIDDEN is this application uploading a clip. And there IS a door: `POST
 *    /media/transcribe` (`backend/app/api/routes/media.py:228`) takes an uploaded file, hands it to
 *    `transcribe_audio`, and gates on nothing but `get_current_user` — any signed-in researcher, any
 *    clip. It exists for the questionnaire's section-audio workflow, where the person being recorded
 *    has been told, and where the clip becomes a listed, playable, deletable `MediaFile` they can be
 *    shown. A microphone on the artisan form has none of that apparatus: nobody is told, nothing is
 *    stored to point at, and there is nothing to delete. Wiring this file to that route would turn
 *    "the phone is listening" into "this person's voice was uploaded" without anybody deciding it.
 *
 * So: grep this file and `RecordProseField.kt` for `FieldRepository`, `Api`, `okhttp`, `upload` or
 * `MediaRecorder` and find nothing. The guarantee is made of absence, which is why it is stated here
 * in words — an absent thing cannot be asserted by a test, but it can be read.
 */

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  1. THE LADDER — TWO RUNGS, AND NO THIRD ONE IS EXPRESSIBLE
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/**
 * The engines a record-form microphone may walk, in the order it walks them.
 *
 * TWO, WHERE THE DESIGN PROTOTYPE WORKSHOP APPLICATION HAS FOUR. Its ladder also carries this app's
 * own bundled speech model and a server route; this repository ships neither — there is no ASR model
 * in `assets/`, no model-download screen, and (see the header) no route a record form may post to.
 * A rung that does not exist is not written down as a disabled branch, because an enum member is
 * something a later `when` has to handle and something a later reader will try to enable.
 */
enum class RecordDictationRung {
    /**
     * The platform's OFFLINE recogniser (API 33+, `createOnDeviceSpeechRecognizer`).
     *
     * FIRST, and the order is the whole argument of this enum. It works in a courtyard with no
     * signal, it is free, and the audio provably does not leave the handset. The one thing it does
     * badly is a language whose pack has not been downloaded — which it reports immediately, before
     * anybody has spoken, so falling through to the next rung costs nothing.
     */
    ON_DEVICE_PACK,

    /**
     * The platform's ordinary recogniser, which may reach the phone-maker's speech service.
     *
     * SECOND, not because it is worse at hearing — it is usually better — but because it needs a
     * connection this app's users frequently do not have, and because it is the rung that involves a
     * third party. Anything the offline engine can do, it does first.
     */
    NETWORK_RECOGNISER,
}

/**
 * Everything the ladder is allowed to know about this handset, this moment.
 *
 * A value class rather than eight parameters threaded through three functions, and read FRESH on
 * every tap rather than remembered: each of these can change between two presses of the same
 * microphone — the researcher walks out of the courtyard and the connection appears, or the engine
 * refuses a language and that refusal becomes a measured fact for the rest of the run.
 */
data class RecordDictationConditions(
    /** The chosen language, as it is written on the control. Used in the sentences, never matched on. */
    val languageLabel: String,
    /** This phone has an offline recogniser (API 33+ and the platform says so). */
    val onDeviceEngine: Boolean,
    /** This phone has a recogniser of any kind. False on a handset with no speech service at all. */
    val networkRecogniser: Boolean,
    /** There is a usable connection right now. */
    val online: Boolean,
    /**
     * The OFFLINE engine has already refused this language in this run.
     *
     * Only ever set from the offline engine's refusal, and that asymmetry is deliberate: a "no" from
     * a network speech service says nothing about the pack on the phone, and writing it down under
     * the same flag would retire a rung that works the next time there is signal.
     */
    val deviceRefusedLanguage: Boolean,
)

/**
 * The rungs this tap will walk, in order. **Empty means say so now rather than after two failures.**
 *
 * Computed ONCE per tap and then walked, rather than re-derived after each failure. Re-deriving
 * would let a rung that has just failed be chosen again, and with two engines and no natural cap
 * that is a bounce with no exit.
 *
 * The offline rung is offered whether or not there is a connection — that is the point of it — and
 * is dropped the moment this run has measured it refusing this language. The network rung is offered
 * only with a connection, because `SpeechRecognizer`'s network failure arrives as a several-second
 * timeout and a researcher who is plainly offline should be told so in the same second they tapped.
 */
fun recordDictationRungs(conditions: RecordDictationConditions): List<RecordDictationRung> =
    buildList {
        if (conditions.onDeviceEngine && !conditions.deviceRefusedLanguage) {
            add(RecordDictationRung.ON_DEVICE_PACK)
        }
        if (conditions.networkRecogniser && conditions.online) {
            add(RecordDictationRung.NETWORK_RECOGNISER)
        }
    }

/**
 * What a record form says when its ladder has nothing left. **Its own words, naming no workshop.**
 *
 * ── THE DEFECT THIS EXISTS TO AVOID, WHICH THE SIBLING REPOSITORY SHIPPED AND THEN FIXED ──────
 *
 * The Design Prototype Workshop application's equivalent sentence is a good one on a stage screen
 * and unusable anywhere else: five of its arms are about a design workshop — an unanswered consent
 * question, a refused one, a spent per-designer allowance, a workshop with no server record, a
 * deployment with no transcription provider — and every one of them sends the reader somewhere
 * ("record the artisan's answer on the workshop screen", "tell whoever runs the server"). This
 * application has no workshops of that kind, no consent column and no dictation allowance, so every
 * one of those destinations would be a wrong turn. A researcher filling in a product's remarks, told
 * to go and record consent on a workshop that has nothing to do with their record, reads the app as
 * broken and stops tapping the microphone. That is a feature lost permanently to a sentence.
 *
 * ── THE RULE EVERY ARM BELOW OBEYS ────────────────────────────────────────────────────────────
 *
 * **Name a next move capable of a different outcome.** No arm says "try again" where trying again
 * reaches the same refusal, and no arm sends anybody to a settings screen that would offer them
 * nothing when they got there — which is why none of them names one in this application at all.
 * There is no screen here that installs a speech service or downloads a language pack; the sibling
 * has one and can point at it, and copying that sentence across would be advice with no destination.
 */
fun recordDictationNothingLeftSentence(conditions: RecordDictationConditions): String {
    val label = conditions.languageLabel
    return when {
        /*
         * NO SIGNAL FIRST, ALWAYS. With no connection the connection is the true blocker, and naming
         * anything else prints a false cause over a real one — the researcher goes looking for a
         * setting when what they need is to walk to the road.
         */

        // Offline, and this phone HAS an offline engine — so the only way to be here is that the
        // engine has already told us it cannot take this language. Nothing on the phone can fix that
        // now, so the sentence names the two things that can: signal, or a different language.
        !conditions.online && conditions.onDeviceEngine ->
            "This phone's offline recogniser has no $label to work from, and there is no connection " +
                "to dictate through. Type the answer in, pick a language this phone does have from " +
                "the control beside the microphone, or dictate the rest where there is signal."

        // Offline, and this phone cannot be asked what it has (below API 33) or has no engine of its
        // own. Claiming a pack is missing would be inventing the one fact we do not have. The
        // keyboard is named because on these handsets it is usually the thing that does work.
        !conditions.online ->
            "Dictation in $label on this phone needs a connection and there is none. Your keyboard's " +
                "own microphone may have an offline language pack; otherwise type the answer in and " +
                "dictate the rest later."

        // Online, and this phone has no speech service of any kind. Common on the budget handsets
        // this app is used on, and the one case where there is genuinely nothing to do here — so it
        // says so plainly rather than implying that another tap might work.
        !conditions.networkRecogniser && !conditions.onDeviceEngine -> RECORD_DICTATION_UNAVAILABLE

        // Online, an engine exists, and it has told us in this run that it will not take this
        // language. Naming the language is the point: the fix is picking a different one, and that
        // is reachable from the control the reader is looking at.
        conditions.deviceRefusedLanguage ->
            "This phone's speech recogniser would not take $label. Pick a different dictation " +
                "language from the control beside the microphone, or type the answer in."

        // Everything else. Deliberately vague about the cause, because by here we do not know one,
        // and inventing a cause is how the sentences above lose their credibility.
        else ->
            "Dictation in $label is not available on this phone just now. Type the answer in, or " +
                "try a different dictation language."
    }
}

/**
 * What a box that asked for a microphone and cannot have one says instead of nothing.
 *
 * Said out loud rather than left as a blank space, because "this screen has no dictation" and "this
 * phone cannot dictate" look identical to somebody who was told the feature exists — and the second
 * is a fact about their handset they can act on. It names the phone, which is where the missing
 * thing actually is, and it sends nobody to a settings screen: there is no setting in this app that
 * installs a speech service, and pointing at one would be advice incapable of a different outcome.
 *
 * Shared with the ladder's own exhaustion sentence for the identical state, so a researcher cannot
 * be told two different things about one handset depending on whether they tapped.
 */
const val RECORD_DICTATION_UNAVAILABLE: String =
    "This phone has no speech recogniser installed, so there is no dictation here. Type the answer in."

/**
 * Spoken text joined to what is already in the box.
 *
 * A space is inserted only where one is missing, so dictating twice into the same field does not
 * produce "…in Bhuj.The second" and does not produce a double space either. Both are trivial and
 * both end up in an exported workbook verbatim, because nobody proof-reads four hundred fields.
 *
 * The same rule as the web's `appendDictated` in `frontend/components/richtext/DictatedTextArea.tsx`,
 * stated identically on purpose: two microphones on two clients writing the same column must not
 * disagree about whether a sentence begins with a space.
 */
fun appendSpokenToRecord(existing: String, spoken: String): String = when {
    spoken.isBlank() -> existing
    existing.isBlank() -> spoken
    existing.last().isWhitespace() -> existing + spoken
    else -> "$existing $spoken"
}

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  2. RICH TEXT IN A `String?` COLUMN
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/*
 * ── THE STORAGE DECISION, WHICH IS THE ONE THING IN THIS LANE THAT CAN CORRUPT DATA ───────────
 *
 * Every column a record form writes to is `String?` and **stays** `String?`. No migration, no
 * `prisma generate`, no sidecar column, no schema change of any kind. That much was decided before
 * this file existed and is not its to revisit.
 *
 * What IS decided here is what those `String?` columns hold once a rich editor is pointed at them.
 * **THE ANSWER IS THE WEB'S ANSWER, FUNCTION FOR FUNCTION**, and the pairing is the point:
 *
 *      frontend/components/richtext/storedRichText.ts   this file
 *      ──────────────────────────────────────────────   ───────────────────────
 *      decodeStoredRichText(raw)                        recordDocFromStored(stored)
 *      isPlainProse(blocks)                             isPlainProse(doc)
 *      encodeStoredRichText(doc)                        recordStoredFromDoc(doc)
 *
 * THE RULE: **a document is stringified only when it is not expressible as plain text.** An
 * unformatted answer — which is nearly all of them, because typing and dictating produce paragraphs
 * and nothing else — is written back as `toPlain` prose, so the column keeps EXACTLY the bytes it
 * keeps today and search, the CSV exports, the `/data/report` workbook, `details.txt` and the review
 * panel see no change at all. A document is written only once somebody has actually applied a mark,
 * a heading, a list, a quote, an alignment, a table or a photograph.
 *
 * ── WHY IT MUST BE THE SAME RULE AND NOT MERELY A COMPATIBLE ONE ──────────────────────────────
 *
 * The obvious alternative for a phone — flatten ALWAYS, never write JSON — is safer read-side and is
 * what the sibling repository's Android lane chose, because over there the record forms are the only
 * client of these columns that has an editor at all. Here they are not: `frontend/components/
 * richtext/RichTextField.tsx` writes these same columns from the browser, today, and it writes a
 * document whenever one is formatted. An Android that always flattened would mean a researcher who
 * bolds a phrase in the browser, then corrects a typo on the phone, silently loses the bold — no
 * error, no prompt, and no way to tell afterwards that it was the phone that did it. Two clients,
 * one column, one rule.
 *
 * ── WHAT THE RULE COSTS, SAID PLAINLY RATHER THAN HIDDEN ──────────────────────────────────────
 *
 * `fromJson` here, `fromStored` in `frontend/lib/richText.ts` and every other reader of these
 * columns treat a `str` as PROSE — deliberately, because that is what lets a column that has always
 * held typed text keep holding it. So a stored document is shown as literal `{"blocks":[{"kind":…` by
 * any reader that has not been taught otherwise: a CSV cell, the data-browser panel, the reviewer's
 * edit box on both platforms. Not a crash — the braces, verbatim, in front of somebody.
 *
 * That cost is bounded to records somebody formatted, it is the same cost the browser lane accepted
 * for the same reason, and it disappears when `cell()` in `backend/app/services/record_fields.py`
 * and the two review renderers learn to flatten. Making the encoding unconditional would move the
 * cost from "records that were formatted" to "every record", which is the trade nobody has agreed
 * to. `RECORD_RICH_TEXT_NOTE` tells the researcher which half they are in, in one line, under the
 * box — because a control that silently changes how an answer is stored is worse than one that never
 * offered to.
 */

/**
 * Whether [stored] looks like a rich document rather than prose somebody typed.
 *
 * A CHEAP SHAPE TEST BEFORE AN EXPENSIVE PARSE, and the cheapness is not the reason for it — the
 * conservatism is. `Json.parseToJsonElement` will happily accept `123`, `true` and `"hello"` as
 * valid JSON, so parsing first and asking questions later would reinterpret a remark that happens to
 * read "true" as a boolean. The only thing this app and the browser ever write into these columns is
 * a `{"blocks": …}` object, so nothing that does not look like one is offered to the parser.
 *
 * The braces test is BOTH ENDS, matching `decodeStoredRichText`'s `startsWith("{") &&
 * endsWith("}")`: the two clients must agree about what counts as a document, or a value one of them
 * stored is prose to the other and comes back with braces in it.
 *
 * Somebody who literally types `{"blocks": []}` into a remarks box gets it read as an empty
 * document. A real false positive, vanishingly unlikely on a craft record, and the alternative —
 * braces printed into an exported workbook because the phone refused to recognise a document the
 * browser wrote — is the failure worth avoiding.
 */
internal fun looksLikeRichDocument(stored: String): Boolean {
    val trimmed = stored.trim()
    return trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.contains("\"blocks\"")
}

/**
 * Whatever is in the column, as a document the editor can open.
 *
 * ── IT ACCEPTS BOTH SHAPES, WHICH IS THE INTEROPERABILITY GUARANTEE ───────────────────────────
 *
 * A `{"blocks": …}` document — what the browser stores for a formatted answer, and what
 * [recordStoredFromDoc] stores for one — is parsed as a document. Anything else is prose. So this
 * build renders correctly against a column written by either client, which is the property that lets
 * the two land in either order without a release that shows braces to somebody.
 *
 * ── AND WHY THE PROSE PATH DOES **NOT** RE-READ LIST MARKERS ──────────────────────────────────
 *
 * `fromJson` on a bare string makes every line a PARAGRAPH, and that is exactly right here, though
 * it is the opposite of what the sibling repository's Android does. Over there a bullet list is
 * FLATTENED into the column as "• the warp is sized", so its reader has to parse the marker back or
 * the glyph accumulates on every save. Here a list is never flattened — a list is not plain prose,
 * so [recordStoredFromDoc] stores it as a document — and a reader that parsed markers would instead
 * REINTERPRET somebody's typed "1. wash in cold water" as an ordered item they never asked for, and
 * then store it back as a document because a list is not plain prose. The browser's `fromStored`
 * makes paragraphs for the same reason. Two clients, one reading.
 */
fun recordDocFromStored(stored: String?): RichDoc {
    val text = stored?.takeIf { it.isNotBlank() } ?: return EMPTY_RICH_DOC
    if (looksLikeRichDocument(text)) {
        val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        // A `{"blocks": …}` that will not parse is a truncated or corrupt value. The honest reading
        // is the one below — show the characters that are actually in the record, rather than an
        // empty box inviting somebody to overwrite it with nothing.
        if (parsed is JsonObject) return fromJson(parsed)
    }
    // Through `fromJson` as a JSON string rather than through a private splitter, so the prose
    // reading is the model's own — one paragraph per line, blank lines dropped, `cleanText` applied.
    return fromJson(JsonPrimitive(text))
}

/**
 * Whether every block in this document survives a trip through plain text unchanged.
 *
 * A TRANSCRIPTION OF `isPlainProse` IN `storedRichText.ts`, deliberately strict and deliberately a
 * whitelist of "nothing interesting is set" rather than a blacklist of known-lossy features: a block
 * kind or a mark added to the model later must default to "store as JSON", never to "quietly drop
 * it". The failure of the other polarity is invisible — a researcher's table would flatten to
 * pipe-separated lines on save and they would not find out until it was read back.
 *
 * `level == 0` and `align == LEFT` are tested even though a PARAGRAPH rarely carries either, because
 * both are storable on a paragraph and both are lost by `toPlain`.
 */
internal fun isPlainProse(doc: RichDoc): Boolean =
    doc.blocks.all { block ->
        block.kind == BlockKind.PARAGRAPH &&
            block.level == 0 &&
            block.align == Align.LEFT &&
            block.media.isBlank() &&
            block.rows.isEmpty() &&
            block.spans.all { span -> span.marks.isEmpty() }
    }

/**
 * What lands in the `String?` column. **The single decision point named in the block comment above.**
 *
 * Null for an empty document rather than `""`, because a blank box on a record form means "this
 * field is not filled in" and every one of these columns is nullable — an empty string would make a
 * never-answered field indistinguishable from one somebody cleared, and the review screen's diff
 * would show a change where nothing was said.
 *
 * THE JOIN IS ALWAYS NEWLINE, WHERE THE WEB HAS A `"paragraph"` MODE, and the difference is not an
 * omission. That mode exists for `Artisan.notes` and the process form's step notes, whose columns
 * have a settled blank-line-separated contract that `MultiNoteInput` splits back into rows. Those
 * columns are not edited by this control on Android: `MultiNoteInput` keeps its own boxes and gets a
 * microphone, never the editor — see its comment in `MainActivity.kt`. Adding the parameter here
 * would be an unused switch that reads as though somebody had pointed the editor at a multi-note
 * column, which is the one thing that must not happen: a document in row two would have to survive
 * being concatenated with rows one and three and torn apart again on the next load.
 */
fun recordStoredFromDoc(doc: RichDoc): String? = when {
    // `isEmptyDoc` and not `toPlain(doc).isBlank()`, matching `encodeStoredRichText`'s order: a
    // photograph with no caption flattens to nothing at all, and judging emptiness on the flattened
    // text would throw one away as "the researcher cleared this field".
    isEmptyDoc(doc) -> null
    // Not expressible as prose. Stored as the document it is — see the block comment above for what
    // that costs and where the cost ends.
    !isPlainProse(doc) -> toJson(doc).toString()
    else -> toPlain(doc).takeIf { it.isNotBlank() }
}

/**
 * The one line printed under a rich box, so nobody discovers the trade-off by losing work — or by
 * finding braces in a spreadsheet a month later.
 *
 * SHORT ON PURPOSE. A thirty-five-word lecture under a control is a lecture nobody reads twice, and
 * the second time it appears they stop reading the sentences that matter. This says what is kept,
 * says what it costs, and stops.
 *
 * IT WILL NEED DELETING, and that is the point of saying it here: the moment `cell()` in
 * `record_fields.py` and the two review renderers flatten a document, the second sentence stops
 * being true and must go. A note that outlives its defect is how a screen ends up lying quietly.
 */
const val RECORD_RICH_TEXT_NOTE: String =
    "Formatting is saved with the answer. Until the exports are taught to read it, an answer you " +
        "format here shows as its raw stored text in CSV downloads; plain typing and dictation are " +
        "unchanged."

// ═══════════════════════════════════════════════════════════════════════════════════════════════
//  3. THE LANGUAGES
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/** One offer in the language control: the BCP 47 tag the recogniser wants, and what a person calls it. */
data class RecordDictationLanguage(val tag: String, val label: String)

/**
 * The languages this repository's fieldwork is actually conducted in.
 *
 * **THE SAME ELEVEN, IN THE SAME ORDER, AS `DICTATION_LANGUAGES` IN
 * `frontend/components/dictation/onDeviceSpeech.ts`.** That list is the one this repository's web
 * lane settled on for the same forms, and a researcher who dictates an artisan record in the browser
 * and the same artisan's products on a phone must not find a different set of languages on the two.
 * The sibling repository offers nineteen and defaults to Hindi; copying that here would have made
 * the two clients of THIS application disagree in order to agree with a third application.
 *
 * English (India) is first and is the default because field notes are usually written in it even
 * where the interview is not. The rest are in the order the web list has them, which is by speaker
 * population — the only ordering that is not somebody's ranking.
 *
 * The tags are the full BCP 47 forms and not the bare subtags: `hi` falls back to a generic model
 * that mangles Indian place names, where `hi-IN` does not.
 */
val RECORD_DICTATION_LANGUAGES: List<RecordDictationLanguage> = listOf(
    RecordDictationLanguage("en-IN", "English (India)"),
    RecordDictationLanguage("hi-IN", "हिन्दी — Hindi"),
    RecordDictationLanguage("bn-IN", "বাংলা — Bengali"),
    RecordDictationLanguage("mr-IN", "मराठी — Marathi"),
    RecordDictationLanguage("te-IN", "తెలుగు — Telugu"),
    RecordDictationLanguage("ta-IN", "தமிழ் — Tamil"),
    RecordDictationLanguage("gu-IN", "ગુજરાતી — Gujarati"),
    RecordDictationLanguage("kn-IN", "ಕನ್ನಡ — Kannada"),
    RecordDictationLanguage("ml-IN", "മലയാളം — Malayalam"),
    RecordDictationLanguage("or-IN", "ଓଡ଼ିଆ — Odia"),
    RecordDictationLanguage("pa-IN", "ਪੰਜਾਬੀ — Punjabi"),
)

/** The label for a tag, falling back to the tag itself so a stale preference is still legible. */
fun recordDictationLabel(tag: String): String =
    RECORD_DICTATION_LANGUAGES.firstOrNull { it.tag == tag }?.label ?: tag
