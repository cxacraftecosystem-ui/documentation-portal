"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";

import { apiFetch, buildQuery } from "@/lib/api";
import type { AadhaarLookupResult, ArtisanIdentityMatch } from "@/lib/types";

/** UIDAI numbers are always 12 digits — the box refuses to hold a 13th. */
const AADHAAR_LENGTH = 12;

// Verhoeff tables, ported from backend/app/services/artisan_identity.py (and matching the Android
// port in MainActivity.kt): `D` is the dihedral-group multiplication table, `P` the position
// permutation applied to each digit by its distance from the right. UIDAI computes this checksum
// over the first 11 digits because it catches every single-digit error and every adjacent
// transposition — the two ways a 12-digit number gets misread off a card.
const VERHOEFF_D = [
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
  [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
  [2, 3, 4, 0, 1, 7, 8, 9, 5, 6],
  [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
  [4, 0, 1, 2, 3, 9, 5, 6, 7, 8],
  [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
  [6, 5, 9, 8, 7, 1, 0, 4, 3, 2],
  [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
  [8, 7, 6, 5, 9, 3, 2, 1, 0, 4],
  [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]
];

const VERHOEFF_P = [
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
  [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
  [5, 8, 0, 3, 7, 9, 6, 1, 4, 2],
  [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
  [9, 4, 5, 3, 1, 2, 6, 8, 7, 0],
  [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
  [2, 7, 9, 3, 8, 0, 6, 4, 1, 5],
  [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]
];

/** True when `digits` satisfies the Verhoeff checksum (the 12th digit checks the first 11). */
function verhoeffOk(digits: string): boolean {
  let checksum = 0;
  for (let index = 0; index < digits.length; index += 1) {
    const digit = digits.charCodeAt(digits.length - 1 - index) - 48;
    checksum = VERHOEFF_D[checksum][VERHOEFF_P[index % 8][digit]];
  }
  return checksum === 0;
}

/**
 * The reason `value` is not a usable Aadhaar number, or null when it is fine (blank included — the
 * field is optional).
 *
 * A mistyped number is worse than a blank one: it collides with nobody, so it silently creates
 * exactly the duplicate the field exists to prevent. These are the same three checks, in the same
 * order and with the same sentences, as `backend/app/services/artisan_identity.py` and the Android
 * form — so the researcher reads one message wherever they are standing, and reads it before the
 * round trip rather than as a 422 after it.
 */
export function aadhaarValidationError(value: string | null | undefined): string | null {
  const digits = (value ?? "").trim();
  if (!digits) return null;
  // Unreachable from the visible box (it filters as you type), kept so the exported helper is safe
  // for any caller — including a value that arrived from somewhere other than this field.
  if (!/^\d+$/.test(digits)) return "Aadhaar number must be 12 digits — remove any letters or symbols.";
  if (digits.length !== AADHAAR_LENGTH) {
    return `Aadhaar number must be exactly 12 digits (this one has ${digits.length}).`;
  }
  if (digits[0] === "0" || digits[0] === "1") {
    return "Aadhaar numbers never start with 0 or 1 — please re-check the first digit.";
  }
  if (!verhoeffOk(digits)) {
    return "That Aadhaar number fails its checksum, so at least one digit is wrong. Please re-read the card and enter it again.";
  }
  return null;
}

/**
 * True when `value` is the `XXXX XXXX 9012` stand-in the API sends in place of an identity number
 * the caller is not entitled to read, rather than a number.
 *
 * Same rule as `is_masked_aadhaar` in backend/app/services/artisan_identity.py — an X anywhere — so
 * the two ends of the round trip cannot disagree about what counts as a mask.
 *
 * Not Aadhaar-specific despite living here: `public_encode` masks the Pehchan card number through
 * the very same `mask_identity_number`, so the artisan form tests that one with this too.
 */
export function isMaskedIdentityNumber(value: string | null | undefined): boolean {
  return typeof value === "string" && /x/i.test(value);
}

/**
 * Said when the field is required and empty. It names the reason rather than the rule, because the
 * researcher has to repeat that reason out loud to a stranger before the stranger reads out a
 * government ID number.
 */
const AADHAAR_REQUIRED =
  "Enter the artisan's 12-digit Aadhaar number — it is what stops the same artisan being recorded twice.";

/**
 * Long enough that a fast typist finishes the last group before we ask the server, short enough that
 * the answer lands while the researcher is still looking at the field rather than three fields later.
 */
const LOOKUP_DEBOUNCE_MS = 400;

function digitsOnly(value: string | null | undefined) {
  return (value ?? "").replace(/\D/g, "").slice(0, AADHAAR_LENGTH);
}

/** "123456789012" -> "1234 5678 9012". */
function grouped(digits: string) {
  return digits.replace(/(\d{4})(?=\d)/g, "$1 ");
}

/** "Kamla Devi — Bhuj · Ajrakh printing · Kutch workshop", skipping whatever the match lacks. */
function describeMatch(match: ArtisanIdentityMatch) {
  return [match.name, match.place, match.craft, match.workshop].filter(Boolean).join(" · ");
}

/**
 * Aadhaar number input with a pre-flight duplicate check.
 *
 * Two deliberate choices here:
 *
 * 1. The box DISPLAYS the number in the 4-4-4 grouping printed on the card ("1234 5678 9012") but
 *    SUBMITS the bare 12 digits through a zero-size mirror input, the same trick PhoneField and
 *    DosDontsField use so the surrounding FormData handlers stay unchanged. The grouping is not
 *    decoration: a researcher reading a number off a card compares it group by group, and a dropped
 *    or doubled digit is visible in "1234 5678 901" in a way it is not in "123456789 01".
 * 2. Aadhaar is the repository's artisan deduplication key, so the moment 12 digits exist we ask
 *    `GET /artisans/lookup/aadhaar` whether someone already holds it. Finding out here — mid-form,
 *    before place, craft, Do's and Don'ts have been filled in — is the whole point; the alternative
 *    is a 409 after five minutes of typing. It is a WARNING, never a block: the researcher may be
 *    legitimately correcting a number, and the unique index behind the save is the real gate.
 *
 * 3. `defaultValue` may be a MASK rather than a number. `GET /artisans/{id}` returns
 *    "XXXX XXXX 9012" to anyone who is neither the artisan's own researcher nor a professor and
 *    above, and those callers may still edit the record. The mask is then held aside and posted
 *    back verbatim, which the API reads as "unchanged" and drops before the write
 *    (`_drop_unchanged_masked_aadhaar`). It must never reach `digits`: stripping the Xs turned
 *    "XXXX XXXX 9012" into the four-digit "9012", which failed validation on a field the editor
 *    could not see well enough to fix, so a reviewer who opened the record to correct a phone
 *    number could not save at all — and had the browser accepted it, a real Aadhaar would have been
 *    overwritten with those four digits.
 *
 * A number that fails `aadhaarValidationError`, by contrast, DOES block the save — through
 * `setCustomValidity` on the visible box rather than a parent-owned error flag, so the browser
 * focuses and scrolls to the field and names the problem, exactly as the Android form focuses it.
 * The message only appears once a submit has been attempted (Android sets its `aadhaarError` in
 * `submit()`), so a half-typed number is never scolded mid-keystroke.
 *
 * `required` blocks an EMPTY number the same way, through the same custom validity rather than the
 * native `required` attribute: with both set, which of the two sentences the browser shows in its
 * bubble is up to the browser, and the one worth reading is ours. A kept mask satisfies it: the
 * record does hold a number, and requiring one the editor is not allowed to read would be a demand
 * they cannot meet.
 */
export function AadhaarField({
  name = "aadhaarNumber",
  defaultValue,
  excludeArtisanId,
  required = false,
  onValueChange
}: {
  name?: string;
  /**
   * The artisan's stored number as `GET /artisans/{id}` gave it: the raw 12 digits for the artisan's
   * own researcher and for professor-and-above, and `XXXX XXXX 9012` for everyone else who may edit
   * the record. Both are handled — see the mask note in the component docstring.
   */
  defaultValue?: string | null;
  /** The record being edited: it is expected to match its own Aadhaar, which is not a duplicate. */
  excludeArtisanId?: string | null;
  /**
   * Refuse an empty number. The caller decides when that is fair — the artisan form requires it on
   * create but not when editing a record that never had one (see ArtisanForm).
   */
  required?: boolean;
  onValueChange?: (digits: string) => void;
}) {
  const baseId = useId();
  const inputId = `${baseId}-aadhaar`;
  const hintId = `${baseId}-aadhaar-hint`;
  const replaceId = `${baseId}-aadhaar-replace`;
  const warningId = `${baseId}-aadhaar-warning`;
  const inputRef = useRef<HTMLInputElement>(null);
  // The mask the record arrived with, or null when the caller was given the real number. It is never
  // folded into `digits`, which holds bare typed digits and nothing else.
  const storedMask = isMaskedIdentityNumber(defaultValue) ? String(defaultValue).trim() : null;
  // Set when the editor chooses to type a new number over one they cannot read. Until then the mask
  // is what the form submits, and the box is theirs to leave alone.
  const [replacing, setReplacing] = useState(false);
  const [digits, setDigits] = useState(() => (isMaskedIdentityNumber(defaultValue) ? "" : digitsOnly(defaultValue)));
  const [match, setMatch] = useState<ArtisanIdentityMatch | null>(null);
  const [checking, setChecking] = useState(false);
  // Raised the first time the browser refuses the form because of this box; until then the problem
  // is enforced but not shown, so typing the number is not narrated back as a series of errors.
  const [problemShown, setProblemShown] = useState(false);

  /** The mask still standing in for the stored number: what to show, and what to post back. */
  const keptMask = replacing ? null : storedMask;
  const complete = digits.length === AADHAAR_LENGTH;
  // A kept mask is a stored number, so there is nothing here to be wrong or missing. Validating it
  // would refuse the whole form over a field the editor is not permitted to read.
  const problem = keptMask ? null : aadhaarValidationError(digits) ?? (required && !digits ? AADHAAR_REQUIRED : null);
  // Android parity: only a number that could genuinely BE somebody's is worth asking the server
  // about. A 12-digit string that fails its checksum can never match a stored (validated) Aadhaar,
  // so looking it up only spends a request to be told "no".
  const lookupReady = complete && !problem;

  // Native constraint validation is what actually stops the submit: the box is a real control
  // inside the form, so an invalid one blocks `requestSubmit()` and the browser reports it in
  // place. (The mirror below is deliberately NOT the validated element — it is zero-size, and a
  // bubble anchored to nothing helps nobody.)
  useEffect(() => {
    inputRef.current?.setCustomValidity(problem ?? "");
  }, [problem]);

  useEffect(() => {
    // Anything short of a complete, well-formed number cannot identify anybody, so there is nothing
    // to ask about.
    if (!lookupReady) {
      setMatch(null);
      setChecking(false);
      return;
    }
    // Drop the previous answer immediately: it belongs to the number that was typed before this one.
    setMatch(null);
    setChecking(true);
    const controller = new AbortController();
    const timer = setTimeout(() => {
      apiFetch<AadhaarLookupResult>(`/artisans/lookup/aadhaar${buildQuery({ number: digits })}`, {
        signal: controller.signal
      })
        .then((result) => {
          const found = result.found ? (result.artisan ?? null) : null;
          setMatch(found && found.id !== excludeArtisanId ? found : null);
        })
        .catch(() => {
          // Aborted mid-type, offline, or the endpoint is unhappy — stay quiet. This lookup is a
          // courtesy; failing it must never block the researcher or cry duplicate without evidence.
        })
        .finally(() => {
          if (!controller.signal.aborted) setChecking(false);
        });
    }, LOOKUP_DEBOUNCE_MS);
    // Cancels both the pending debounce and any request already in flight, on every keystroke and
    // on unmount, so a slow answer for an old number can never overwrite a newer one.
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [digits, lookupReady, excludeArtisanId]);

  /** Start over with an empty box: the editor is replacing a number they were never shown. */
  function replaceStoredNumber() {
    setReplacing(true);
    setDigits("");
    setProblemShown(false);
    inputRef.current?.focus();
  }

  /** Back to posting the mask — the way out of an accidental "Replace", and of a half-typed number. */
  function keepStoredNumber() {
    setReplacing(false);
    setDigits("");
    setProblemShown(false);
  }

  return (
    <div className="relative grid content-start gap-1">
      <label className="field-label" htmlFor={inputId}>
        Aadhaar number{required ? " *" : ""}
      </label>
      <input
        ref={inputRef}
        id={inputId}
        className="field-input read-only:bg-surface-50 read-only:text-ink-500"
        type="text"
        inputMode="numeric"
        autoComplete="off"
        placeholder="1234 5678 9012"
        value={keptMask ?? grouped(digits)}
        readOnly={Boolean(keptMask)}
        aria-required={required || undefined}
        aria-invalid={problemShown && problem ? true : undefined}
        aria-describedby={[hintId, storedMask && replacing ? replaceId : null, match ? warningId : null]
          .filter(Boolean)
          .join(" ")}
        onInvalid={() => setProblemShown(true)}
        onChange={(event) => {
          const next = digitsOnly(event.target.value);
          setDigits(next);
          setProblemShown(false);
          onValueChange?.(next);
        }}
      />
      {problemShown && problem ? (
        <p id={hintId} className="text-xs text-error-600">
          {problem}
        </p>
      ) : keptMask ? (
        <p id={hintId} className="text-xs text-ink-muted">
          On file, but hidden from you: the full number is shown only to the researcher who recorded
          this artisan and to professors upwards. Saving leaves it exactly as it is.{" "}
          <button type="button" className="font-semibold text-purple-700 underline" onClick={replaceStoredNumber}>
            Replace this number
          </button>
        </p>
      ) : (
        <p id={hintId} className="text-xs text-ink-muted">
          {required
            ? "Required: 12 digits from the artisan's card. It is the only identifier that tells two researchers they have documented the same person, so it is what keeps one artisan from becoming two records — that is the reason to give when you ask for it."
            : "12 digits from the artisan's card. Used to recognise someone another researcher has already documented."}
          {digits.length > 0 && !complete ? ` ${digits.length} of ${AADHAAR_LENGTH} entered.` : ""}
        </p>
      )}
      {/* Deliberately OUTSIDE the block above: it used to sit inside the hint paragraph, which the
          error message replaces the moment a submit is refused — so someone who hit "Replace this
          number" by mistake, could not produce a number they are not allowed to read, and then tried
          to save was shown the error INSTEAD of the only way back to the stored value. */}
      {storedMask && replacing ? (
        <p id={replaceId} className="text-xs text-ink-muted">
          All 12 digits replace the number already on file.{" "}
          <button type="button" className="font-semibold text-purple-700 underline" onClick={keepStoredNumber}>
            Keep the stored number
          </button>
        </p>
      ) : null}
      {checking ? <p className="text-xs text-ink-muted">Checking for an existing artisan...</p> : null}
      {match ? (
        <div
          id={warningId}
          role="status"
          className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800"
        >
          <p className="font-medium">This Aadhaar number is already on an artisan record.</p>
          <p className="mt-0.5">{describeMatch(match)}</p>
          <Link className="mt-1 inline-block font-medium underline" href={`/artisans/${match.id}/edit`}>
            Open that artisan instead
          </Link>
          <p className="mt-1 text-xs">
            You can still save — a genuine duplicate will be refused by the server, so open the
            existing record if this is the same person.
          </p>
        </div>
      ) : null}
      {/* Zero-size (not hidden) mirror input: submits the BARE digits under the existing field name
          while the visible box keeps the card's 4-4-4 grouping — or the mask verbatim, which is how
          the API is told the number was left alone. */}
      <input
        type="text"
        name={name}
        value={keptMask ?? digits}
        onChange={() => undefined}
        tabIndex={-1}
        aria-hidden="true"
        className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
      />
    </div>
  );
}
