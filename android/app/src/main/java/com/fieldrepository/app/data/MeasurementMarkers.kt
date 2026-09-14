package com.fieldrepository.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * HOW a record's dimension came to be known, as the request body says it — and the rule that stops it
 * saying so once it has stopped being true.
 *
 * ── WHAT THE MARKER IS ────────────────────────────────────────────────────────────────────────
 *
 * `ProductCreateRequest` / `ToolCreateRequest` carry `lengthInches` / `breadthInches` /
 * `heightInches`, and four different processes write them: somebody types a number off a tape,
 * somebody dictates one, somebody accepts a reading from `RecordMeasureField` (deterministic plane
 * geometry, on this handset), or somebody accepts an estimate from `GridMeasurementSection` (a vision
 * model, over the network). The server stamps every changed field with the identity of whoever
 * pressed Save, so until this key existed a machine's number was stored asserting that a NAMED HUMAN
 * had measured it. `measurementMethods` puts the METHOD beside the signature, and the row goes back to
 * saying something true: a vision model estimated this, and a named researcher accepted it into the
 * record at that moment.
 *
 * The wire shape is the API's measurement-provenance contract, and that service is the authority for
 * every token below — [MEASUREMENT_DIMENSIONS] mirrors its dimension-field set, [TECHNIQUE_SCALE] /
 * [TECHNIQUE_RECTIFIED] its geometry techniques. Re-check with:
 *
 *     grep -rn "PHOTO_GEOMETRY\|GEOMETRY_TECHNIQUES" backend/app
 *
 * ── SENDING NOTHING IS ALWAYS LEGAL, AND THAT IS WHAT MAKES THIS SAFE TO SHIP ─────────────────
 *
 * An absent marker is read as UNRECORDED, never as TYPED, and that is the single most important rule
 * here: the rows already in the database include model estimates that auto-filled a form field, so
 * defaulting absent to TYPED would assert that a human measured a number a machine guessed, for
 * exactly the rows where that is false. So a typed number needs no marker, an older handset that has
 * never heard of this key keeps saving exactly as it does today, and [MeasurementMarkers.body]
 * returning null is a correct answer rather than a failure. NOTHING HERE EVER COMPOSES A `TYPED`
 * MARKER: typing is the absence of a machine, and this client cannot tell a number read off a tape
 * from one copied out of a message. Saying `TYPED` would add a claim without adding a fact.
 *
 * ── WHY A REFUSAL IS A DATA-LOSS QUESTION ON THIS PLATFORM ────────────────────────────────────
 *
 * The server's validation of this key is strict and every violation is a 422 on the WHOLE save. The
 * offline outbox will not queue a 4xx — the server saw it and said no — so a malformed marker does not
 * merely fail to record a method, it throws away a record filled in somewhere with no signal. That is
 * why [MeasurementMarkers.body] is conservative in one direction only: when in doubt it sends NO
 * marker, which costs a fact and loses nothing.
 */

/**
 * The only three columns a method may describe. The server's dimension-field set, verbatim.
 *
 * The tool form's `height`, `width`, `thickness`, `weight` and `radius` are deliberately NOT here.
 * THE CASE THIS ACTUALLY GUARDS IS THE TOOL FORM'S SECOND HEIGHT BOX: `ToolCreateRequest` carries
 * both `heightInches` (a documented dimension, in the unit its own name states) and a plain unit-less
 * `height`, and only the first is a dimension field. A marker naming `height` is a REJECTED SAVE, by
 * name, not a dropped hint — and on this platform a rejected save is a lost record rather than a
 * message. The others are ordinary typed boxes with no measurement route pointed at them.
 */
val MEASUREMENT_DIMENSIONS: Set<String> = setOf("lengthInches", "breadthInches", "heightInches")

/** Deterministic plane geometry, on this device. [PhotoMeasure.METHOD_SCALE]'s wire spelling. */
const val TECHNIQUE_SCALE = "SCALE"

/** Deterministic plane geometry via a homography. [PhotoMeasure.METHOD_RECTIFIED]'s spelling. */
const val TECHNIQUE_RECTIFIED = "RECTIFIED"

private const val METHOD_PHOTO_GEOMETRY = "PHOTO_GEOMETRY"
private const val METHOD_VISION_MODEL = "VISION_MODEL"

/**
 * The marker for a reading [PhotoMeasure] produced on this handset.
 *
 * [technique] arrives as `MeasureResult.Measurement.method`, which is already `"SCALE"` or
 * `"RECTIFIED"` — the same two words the server's geometry-technique set holds. That is not a
 * coincidence to be tidied into a mapping table: [PhotoMeasure] is a port of the web client's
 * `photoMeasure` module and the server took its vocabulary from that module on purpose, so passing
 * the value straight through is what keeps the three surfaces from drifting. A technique this server
 * does not know is DROPPED rather than sent, because the server refuses an unknown one — a 422 on the
 * whole save — and the method is worth recording without it.
 *
 * KEYS WHOSE ANSWER IS NOT A KNOWN FACT ARE OMITTED, never sent as a placeholder: there is no
 * `"technique": null` and no `"technique": "UNKNOWN"` branch below, because either would be this
 * client asserting something it was not told.
 */
fun geometryMarker(technique: String?): JsonObject = buildJsonObject {
    put("method", JsonPrimitive(METHOD_PHOTO_GEOMETRY))
    if (technique == TECHNIQUE_SCALE || technique == TECHNIQUE_RECTIFIED) {
        put("technique", JsonPrimitive(technique))
    }
}

/**
 * The marker for a reading the measurement-analysis endpoint produced, which is the server's OWN
 * marker echoed back unchanged.
 *
 * ECHOED, NOT REBUILT, and the server asks for exactly that: its marker is documented as what a
 * client echoes back, unchanged, when it saves the value it was given, and the validation leaves a
 * marker's key set OPEN specifically so a handset relaying a newer server's extra key is not refused
 * mid-deploy. Rebuilding it here from `provider` / `modelId` / `selfReportedConfidence` would be a
 * second implementation of a shape the server already handed us, and the first thing it would lose is
 * the confidence — the only number on the stamp.
 *
 * [fromServer] is null when the response carried no marker: an older deployment, or one of the
 * failure paths. THAT IS THE CASE THIS CLIENT IS IN TODAY — `FieldRepository.analyzeMeasurement` and
 * `analyzeMeasurementLengthBreadth` return bare numbers, and `MeasurementAnalysisDto` has nowhere for
 * a marker to ride, so `GridMeasurementSection` calls this with null. The bare
 * `{"method": "VISION_MODEL"}` fallback still records the fact that matters most — a model produced
 * this and a person accepted it — and every other key is optional. When the repository learns to
 * carry the server's marker (see [MeasurementReading]), the call site passes it here and the extra
 * keys travel with no change to anything else.
 */
fun visionMarker(fromServer: JsonObject?): JsonObject =
    fromServer ?: buildJsonObject { put("method", JsonPrimitive(METHOD_VISION_MODEL)) }

/**
 * What the measurement-analysis endpoint answered: the numbers, and the marker that says a model
 * produced them.
 *
 * THE MARKER TRAVELS WITH THE NUMBER, in one object, because the two are only ever correct together.
 * `FieldRepository.analyzeMeasurement` returns a bare `Double?` today and the marker has nowhere to
 * ride, which is precisely how a model's estimate reaches a record wearing a researcher's name. This
 * type is the shape that closes that: it is declared here, beside the marker rules, so that the
 * repository change is a return-type change and nothing else.
 *
 * [valueInches] is the single-dimension answer (the `height` capture); [lengthInches] /
 * [breadthInches] are the footprint pair. One call fills one side or the other, never both — the
 * endpoint answers in whichever shape it was asked in — and the caller already knows which it asked
 * for.
 */
data class MeasurementReading(
    val lengthInches: Double? = null,
    val breadthInches: Double? = null,
    val valueInches: Double? = null,
    /** The server's own marker, verbatim. Null from a server that sends none; see [visionMarker]. */
    val marker: JsonObject? = null,
)

/**
 * A number a dimension column can actually store: non-blank, and parseable as a finite number.
 *
 * DELIBERATELY THE SAME TEST THE FORMS THEMSELVES MAKE. Both record forms send
 * `lengthInches = length.toDoubleOrNull()`, so a box holding "12,5" or "about 12" sends NO VALUE for
 * that dimension — and a body that sends a marker for a dimension it sends no value for is a 422 by
 * name ("send the measurement in the same request, or leave the method out"). On this platform that
 * is a lost record, not a message. Checking the same way the form converts is what keeps the two from
 * ever disagreeing.
 */
private fun storableNumber(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    val parsed = trimmed.toDoubleOrNull() ?: return false
    return parsed.isFinite()
}

/**
 * One accepted proposal: the marker to send, and the EXACT text that was written into the box.
 *
 * [acceptedText] is the whole anti-staleness mechanism and is stored for no other purpose. See
 * [MeasurementMarkers.body].
 */
private data class AcceptedMeasurement(val acceptedText: String, val marker: JsonObject)

/**
 * What a record form remembers about the proposals somebody accepted into its dimension boxes.
 *
 * ── THE RULE THIS TYPE EXISTS TO ENFORCE ──────────────────────────────────────────────────────
 *
 * A marker is a CLAIM ABOUT HOW A NUMBER WAS OBTAINED, so it has to stop being sent the moment it
 * stops being true. A researcher accepts a geometry reading into "Length (inches)", looks at the
 * object again, and types over it: the value is now typed, and a `PHOTO_GEOMETRY` marker on it is a
 * FALSE CLAIM — strictly worse than no marker at all, because UNRECORDED is honest and distinguishable
 * and this is a lie a later reader has no way to catch. Emptying the box is the same failure with
 * nothing left in it to describe.
 *
 * ── HOW IT IS ENFORCED, WHICH IS BY COMPARISON AND NOT BY INTERCEPTION ────────────────────────
 *
 * [accept] records the exact string the proposal put in the box. [body] is handed what the boxes hold
 * AT SAVE TIME and emits a marker only for a column whose current text is still that string. So a
 * marker survives exactly one condition — nobody changed the digits since accepting — and any hand
 * edit, any clearing, any retyping, any dictation into the box drops it with no listener and no flag
 * to keep in step.
 *
 * That is deliberate. The alternative is to clear the marker ONLY from each box's own text callback,
 * and it fails the first time somebody adds a fifth way to write a dimension (a paste handler, a
 * prefill from a carried record, an undo) and does not know there was a marker to clear. A comparison
 * cannot be forgotten by code that has not been written yet: a new writer changes the text, the text
 * stops matching, and the marker drops itself.
 *
 * TRIMMED ON BOTH SIDES, so re-entering a box and leaving a trailing space does not drop a marker
 * whose number is unchanged. The digits are compared exactly: "12.0" and "12.00" are a hand edit and
 * drop it, which is right — the panel rounds to the precision its error bar reaches, so the number of
 * digits is itself part of the reading.
 *
 * ── AND THE BOXES FORGET FROM THEIR OWN onValueChange AS WELL ─────────────────────────────────
 *
 * [forget] is called from each dimension box's `onValueChange`, and it is NOT redundancy for its own
 * sake: it is the one case value-equality cannot see, a researcher who deletes the accepted number
 * and types the identical digits back by hand. That is a hand-typed number and must be recorded as
 * one. The comparison in [body] is what catches every writer nobody has thought of; [forget] is what
 * catches the one writer whose result is indistinguishable from the machine's.
 *
 * (The sibling design-workshop implementation keeps that acceptance, documenting the false positive
 * rather than hiding it. The shared contract this port was written against goes the other way, the
 * web client's `forgetAcceptance` is called from its inputs, and a marker dropped in doubt costs a
 * fact while a marker kept in doubt costs the truth of the row — so this client forgets.)
 *
 * NOT SNAPSHOT STATE. Nothing recomposes on this; it is written from the accept buttons and the boxes'
 * own callbacks and read once inside `submit()`, so a plain map inside a `remember { }` is the whole
 * requirement.
 */
class MeasurementMarkers {
    private val accepted = mutableMapOf<String, AcceptedMeasurement>()

    /**
     * Record that [column] now holds [text] because somebody accepted a proposal carrying [marker].
     *
     * Called from an accept path and nowhere else — the same discipline the proposal routes
     * themselves hold, and for the same reason: a marker written anywhere but an acceptance is a
     * claim nobody made.
     *
     * A column outside [MEASUREMENT_DIMENSIONS] and a value the column could not store are both
     * REFUSED rather than remembered, because each would be a marker the server rejects the whole
     * save over.
     */
    fun accept(column: String, text: String, marker: JsonObject) {
        if (column !in MEASUREMENT_DIMENSIONS) return
        if (!storableNumber(text)) return
        accepted[column] = AcceptedMeasurement(text, marker)
    }

    /**
     * Forget [column]'s acceptance, because a person has touched that box themselves.
     *
     * Safe to call on every keystroke and for a column that never had an acceptance — it is a map
     * removal and nothing else. See this class's header for why this exists alongside the comparison
     * in [body] rather than instead of it.
     */
    fun forget(column: String) {
        accepted.remove(column)
    }

    /**
     * The `measurementMethods` body for a save, or null when there is nothing true to say.
     *
     * [current] is what the form's boxes hold right now, keyed by column. NULL RATHER THAN AN EMPTY
     * OBJECT ON PURPOSE, and this is the load-bearing half of the contract: `ApiClient.json` is
     * `explicitNulls = false`, so a null drops the key from the request entirely and the save goes out
     * byte-for-byte as it does today. The web deploys separately from the API, so a NEWER client meets
     * an OLDER server; that server's request models forbid unknown keys, so a `"measurementMethods":
     * null` on the body is a 422 on the WHOLE save — and the outbox will not queue a 4xx, so the
     * researcher's form would be neither saved nor retried. An absent key is refused by nothing, ever.
     *
     * A COLUMN WITH NO VALUE IS NEVER MARKED, and that is not only honesty — it is a refusal the
     * server makes by name: it rejects a marker for a dimension the same request sends no value for.
     * [storableNumber] below is what keeps that from ever being reachable, and it is the same check
     * that handles a cleared box.
     */
    fun body(current: Map<String, String>): JsonObject? {
        val live = accepted.filter { (column, entry) ->
            val text = current[column].orEmpty()
            // ⚠ COMPARED RAW, NOT TRIMMED, AND THE HANDSET USED TO TRIM BOTH SIDES.
            //
            // That looked like the kinder reading — a trailing space does not change the number, so
            // why drop a true claim over it? — and it made this client disagree with the browser
            // about the same keystroke: `frontend/components/forms/measurementMethods.ts` compares
            // the strings as they stand, so " 6.25" loses its marker there and kept it here. Each
            // suite then asserted its own behaviour, so both looked correct and the product stored
            // two different provenances for one act.
            //
            // The disagreement is settled toward the browser, and not only because it was first. The
            // rule this mechanism rests on is that the check is on the VALUE and never on the event
            // that changed it — nothing is trusted about HOW the box came to differ. Trimming is a
            // small exception carved into exactly that rule, and the failure it risks is the one
            // this whole file exists to prevent: a marker surviving onto a number a person touched.
            // Dropping a true claim costs UNRECORDED, which is honest and distinguishable; keeping a
            // false one is a lie a later reader has no way to catch. When the two are not equally
            // bad, the tie goes to the cheaper mistake.
            //
            // `storableNumber` still reads the raw text, exactly as the form's own
            // `toDoubleOrNull()` does — so a box holding " 6.25" sends no value AND no marker, which
            // is the pair the server requires.
            storableNumber(text) && text == entry.acceptedText
        }
        if (live.isEmpty()) return null
        return buildJsonObject { live.forEach { (column, entry) -> put(column, entry.marker) } }
    }
}
