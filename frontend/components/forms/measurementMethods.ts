/**
 * WHICH DIMENSIONS ON A RECORD SAVE MAY STILL CLAIM A MACHINE MEASURED THEM.
 *
 * `ProductForm` and `ToolForm` both build a `measurementMethods` object on the create POST and the
 * update PATCH; this module decides what may go in it. Pure, no React, no import from a component —
 * because the judgement it holds is the one whose broken state looks EXACTLY like its working state
 * (a number in a box) until somebody reads the record a year later, and there is no React renderer in
 * this repository's devDependencies to exercise a judgement written inside JSX. The same split, for
 * the same reason, as `components/media/gridProposal.ts`.
 * `e2e/record-photo-measure-unit.spec.ts` drives it directly.
 *
 * ── WHAT A MARKER IS, AND THEREFORE WHEN IT MUST STOP BEING SENT ──────────────────────────────
 *
 * `records.merge_field_provenance` stamps every changed field with the `{by, byName, at}` of whoever
 * pressed Save — the dimension columns are not in its `PROVENANCE_SKIP_FIELDS` — so a dimension a
 * machine produced is stored asserting that a NAMED HUMAN measured it. The marker is what puts
 * `method` beside that signature, and the row then reads *a vision model estimated this, and R. Menon
 * accepted it into the record at that moment* — a true sentence, and the one an auditor needs.
 *
 * A MARKER IS A CLAIM ABOUT HOW THIS NUMBER WAS OBTAINED. It is therefore false the instant the
 * number stops being the one the route proposed. A researcher who accepts a geometry reading into
 * "Length (inches)" and then types over it has a TYPED number wearing a `PHOTO_GEOMETRY` claim, which
 * is worse than no marker at all: an absent marker is recorded as `UNRECORDED`, which is honest and
 * distinguishable, while a false one is a lie a later reader has no way to catch. Same for a box
 * emptied after acceptance — it carries no number, so it must carry no claim about one.
 *
 * ── HOW THAT IS ENFORCED, WHICH IS THE WHOLE REASON THIS FILE EXISTS ──────────────────────────
 *
 * {@link rememberAcceptance} stores the accepted number ALONGSIDE its marker, and
 * {@link measurementMethodsFor} re-checks that stored number against what is in the box AT SAVE TIME,
 * character for character. A marker survives into the request body only when the box still holds the
 * exact string the route wrote into it. **Nothing is trusted about HOW the box came to differ** — a
 * keystroke, a paste, a clear, or some future third writer nobody has written yet — because the check
 * is on the value and not on the event that changed it. There is no code path that can attach a
 * marker to a number the route did not produce, and that is a property a reader can verify by reading
 * {@link measurementMethodsFor} alone rather than by auditing every `setState` on two long forms.
 *
 * The forms ALSO forget an acceptance from the box's own `onChange` ({@link forgetAcceptance}), which
 * is not redundancy for its own sake: it is the one case value-equality cannot see, a researcher who
 * types the identical digits back by hand. That is a hand-typed number and must be recorded as one.
 *
 * ── WHY THE KEY IS OMITTED RATHER THAN SENT AS NULL WHEN THERE IS NOTHING TO SAY ──────────────
 *
 * {@link measurementMethodsFor} answers `undefined`, so `JSON.stringify` drops the key entirely and
 * the overwhelming majority of saves — every record whose dimensions were typed off a tape — go out
 * on exactly the bytes they went out on before this existed. That matters in one direction the rest
 * of this repository does not usually have to think about: **the web deploys to Vercel and the API to
 * EC2, separately**, so a NEWER web build can meet an OLDER API. `APIModel` is
 * `ConfigDict(extra="forbid")`, so a `measurementMethods: null` present on the body would be a 422 on
 * the WHOLE save against a server that predates the declaration — and `saveOrQueue` will not queue a
 * 4xx ("the server saw it and said no"), so the researcher's form would be neither saved nor retried.
 * An absent key is refused by nothing, ever. Sending nothing is legal and means `UNRECORDED`; it must
 * never mean TYPED.
 */

import type { MeasurementMethodMarker } from "@/lib/media";

/**
 * The only columns a method marker may name, and the reason this list is duplicated in TypeScript.
 *
 * The API's `measurement_provenance.DIMENSION_FIELDS` is the authority — `{"lengthInches",
 * "breadthInches", "heightInches"}` — and a marker naming anything else is not silently dropped at the
 * API boundary, it is a **422 on the whole save**, by name, which `saveOrQueue` will not queue. So the
 * client has to hold the same list rather than discover the disagreement in a courtyard with no
 * signal.
 *
 * THE CASE THIS ACTUALLY GUARDS IS THE TOOL FORM'S SECOND HEIGHT BOX. `ToolDocumentation` carries
 * both `heightInches` (a documented dimension, `Decimal(10, 2)`, in the unit its own name states) and
 * a plain unit-less `height`, and only the first is in `DIMENSION_FIELDS`. A marker naming `height`
 * is a rejected save, not a dropped hint. Nothing machine-produced lands in `height` any more — see
 * that form's `MEASURE_COLUMNS` — but the list is enforced here as well, because the cost of the two
 * getting out of step is a form the researcher loses rather than a provenance hint they never see.
 *
 * Re-check with:
 *
 *     grep -n "DIMENSION_FIELDS" backend/app/services/measurement_provenance.py
 */
export const DIMENSION_FIELDS = ["lengthInches", "breadthInches", "heightInches"] as const;

export type DimensionField = (typeof DIMENSION_FIELDS)[number];

/** Is this the name of a column a method may describe? The runtime half of {@link DIMENSION_FIELDS}. */
export function isDimensionField(key: string): key is DimensionField {
  return (DIMENSION_FIELDS as readonly string[]).includes(key);
}

/**
 * One acceptance: the number a measurement route proposed, and the marker that says what produced it.
 *
 * THE VALUE IS STORED WITH THE MARKER AND NOT DERIVED FROM FORM STATE, which is the whole mechanism.
 * Holding the marker alone would leave nothing to compare the box against at save time, and "is this
 * still the machine's number?" would have to be answered by trusting that every writer of that box
 * remembered to clear the marker.
 */
export type Acceptance = { value: string; marker: MeasurementMethodMarker };

/** What a form holds between an acceptance and the save. Empty on mount, including on an edit form. */
export type AcceptedMeasurements = Partial<Record<DimensionField, Acceptance>>;

/** A form that has accepted nothing. A shared frozen literal so a fresh `{}` is never a "change". */
export const NO_ACCEPTED_MEASUREMENTS: AcceptedMeasurements = Object.freeze({});

/**
 * A number this column can store: non-blank and finite.
 *
 * Deliberately the same test as both forms' own `toNum`, because the two must agree. A body that
 * sends `lengthInches: null` together with a marker for `lengthInches` is a **422**: the server
 * computes the present dimensions from the NON-NULL values on the same model and refuses a method
 * that states how a dimension was measured when the request sends no value for it. So an emptied box
 * has to drop its marker for a second reason beyond honesty — keeping it loses the record.
 */
function isStorableNumber(text: string): boolean {
  return text.trim() !== "" && Number.isFinite(Number(text));
}

/**
 * Record that a person pressed an accept button, so the save can say what produced the number.
 *
 * Returns a NEW object — call it from a `setState` updater. Three things refuse to be remembered, and
 * each is a marker that would be a false claim or a rejected save:
 *
 * * a key outside {@link DIMENSION_FIELDS} — see that constant for the tool form's `height`;
 * * a value the column cannot store, so there would be no number for the claim to be about;
 * * **a missing marker.** `GridMeasurement` hands `null` when the server sent none (an older API), and
 *   the reflex — keep whatever was there — is exactly wrong: the box has just been overwritten with a
 *   NEW machine number, so any marker already stored for it describes a value that is gone. The
 *   acceptance is FORGOTTEN instead and the save records `UNRECORDED`, which is what actually
 *   happened: this client was told a number and was not told how it was arrived at.
 */
export function rememberAcceptance(
  accepted: AcceptedMeasurements,
  key: string,
  value: string,
  marker: MeasurementMethodMarker | null | undefined
): AcceptedMeasurements {
  if (!isDimensionField(key)) return accepted;
  if (!marker || !isStorableNumber(value)) return forgetAcceptance(accepted, key);
  return { ...accepted, [key]: { value, marker } };
}

/**
 * Forget an acceptance, because a person has touched the box themselves.
 *
 * Returns the SAME object when there is nothing to forget, which is not a micro-optimisation: this is
 * called from a number input's `onChange`, on every keystroke, inside a `setState` updater. A fresh
 * `{}` each time would re-render the whole form on every character typed into a dimension.
 */
export function forgetAcceptance(accepted: AcceptedMeasurements, key: string): AcceptedMeasurements {
  if (!isDimensionField(key) || accepted[key] === undefined) return accepted;
  const next = { ...accepted };
  delete next[key];
  return next;
}

/**
 * The `measurementMethods` object for the save body, or `undefined` when there is nothing true to say.
 *
 * `values` is what each dimension box holds RIGHT NOW, keyed by column name — the same record both
 * forms already build for `RecordPhotoMeasure`'s `values` prop. A marker is emitted only for a key
 * whose current text is byte-identical to the text the route proposed and which the column can still
 * store, so:
 *
 * * typed over after acceptance → the strings differ → no marker → `UNRECORDED`;
 * * cleared after acceptance → not storable → no marker, and no 422 for a method about a null;
 * * accepted and left alone → the marker travels, and the row records what actually produced it.
 *
 * NOTHING IS EVER SYNTHESISED HERE. There is no branch that composes `{method: "TYPED"}` for a box
 * somebody typed into, and that is deliberate rather than unfinished: this client cannot tell a number
 * read off a tape from one copied out of a message, and `UNRECORDED` is the honest answer to a
 * question nobody recorded the answer to. `TYPED` would be an assertion about a human act, made by a
 * form that watched no human act. **`UNRECORDED` is never a synonym for TYPED.**
 */
export function measurementMethodsFor(
  accepted: AcceptedMeasurements,
  values: Record<string, string>
): Record<string, MeasurementMethodMarker> | undefined {
  const out: Record<string, MeasurementMethodMarker> = {};
  for (const key of DIMENSION_FIELDS) {
    const acceptance = accepted[key];
    if (!acceptance) continue;
    const current = values[key];
    if (typeof current !== "string" || !isStorableNumber(current)) continue;
    if (current !== acceptance.value) continue;
    out[key] = acceptance.marker;
  }
  return Object.keys(out).length ? out : undefined;
}
