"use client";

import { useId, useMemo, useState } from "react";

import { Dropdown } from "@/components/ui/Dropdown";
import { useConfirm } from "@/components/dialogs";
import {
  COUNTRIES,
  DEFAULT_DIAL_CODE,
  DEFAULT_ISO2,
  countryByIso2,
  flagEmoji
} from "@/lib/countries";
/*
 * THE RULE AND THE PARSER BOTH MOVED OUT OF THIS FILE, AND THE MOVE IS THE WHOLE POINT.
 *
 * They were `parsePhone` and an inline ternary in the body below — private, unexported, and
 * therefore enforced on the record page and NOWHERE ELSE. The design workshop mounts this very
 * component for `participant.phone` with `mirror={false}`, which drops the native `pattern` and
 * leaves the message advisory, and `coerce_value` had no phone rule at all on either path. So a
 * nine-digit number typed into a stage saved cleanly and printed in a ministry roster.
 *
 * `FieldSpec.text_format = PHONE_IN` now declares the rule on the field and the server enforces it,
 * with `lib/textFormats` as the one implementation all three sides read. This component keeps the
 * inline message it always had — same function, so it cannot disagree with the refusal.
 */
import { isPhoneNumberShaped, phoneValidationError, splitStoredPhone } from "@/lib/textFormats";

const FOREIGN_CONFIRM = "This marks the artisan as a foreign resident. Continue?";

/**
 * Phone input with an ISD-prefix picker (flag emoji + dial code, default +91). Submits ONE string
 * ("+91 9876543210") under the existing `phone` field name via a zero-size mirror input, so the
 * FormData handlers and API payload are unchanged. +91 requires exactly 10 digits; any other code
 * 4-14 digits (both blocked natively via the mirror's pattern AND surfaced as an inline error).
 * Switching away from +91 asks for confirmation — it marks the artisan as a foreign resident.
 *
 * ALSO MOUNTED BY THE DESIGN WORKSHOP, on every registry field the registry typed `PHONE`
 * (`participant.phone`, `surveyResponse.contact`). That is why the two props below exist and why
 * neither has a default that changes anything for the record forms:
 *
 * - `disabled` — a stage that has been submitted is read-only, and a control with no way to say so
 *   is a control a designer can edit after the report has gone to the ministry.
 * - `mirror` — a stage form is not a `<form>` and reads nothing from FormData, so the zero-size
 *   mirror there would contribute a stray `name="phone"` and a `pattern` to any form a later change
 *   happened to wrap the page in. Native constraint validation is deliberately absent from the
 *   workshop (see `FieldInput`'s DATE branch on why `required` is never passed): completeness is
 *   judged by `stage_completeness` when a report is generated, not by the browser at save time.
 *
 * The handset makes the same choice and states it: `FieldRenderer.kt` gives `DwFieldType.PHONE` its
 * own arm and calls `ArtisanPhoneField`, the record form's control reused whole, "because the
 * alternative is a second phone field without the measured dial column, the country search and the
 * foreign-resident confirmation this one already carries."
 */
export function PhoneField({
  name = "phone",
  defaultValue,
  onValueChange,
  disabled,
  mirror = true,
  /**
   * Refuse the submit when no number has been typed — the designer profile's `phone` box, and so far
   * only that one.
   *
   * ── IT LANDS ON THE VISIBLE INPUT, NOT ON THE MIRROR, AND THAT IS THE WHOLE OF THE DESIGN ──────
   *
   * §12.2's rule is that a themed control's mirror must be `type="text"` and never `type="hidden"`,
   * because a hidden input is exempt from constraint validation and a `required` on one would never
   * block anything. That rule is about which ELEMENT can carry a constraint at all; it is not an
   * instruction to put every constraint there. The mirror below is `absolute h-0 w-0 opacity-0`, so
   * a browser refusing the submit anchors its bubble to a box of zero size — measured in Chromium:
   * the submit IS blocked and the message IS "Please fill out this field", pointed at nothing a
   * designer can see. The visible number input is an ordinary `<input>` in the same `<form>` with no
   * `name` of its own, so `required` there is enforced identically, focuses the box that is empty,
   * and draws the bubble where the answer goes.
   *
   * The mirror keeps `pattern`, which is a different question — that one is about the COMPOSED
   * value's shape (dial code plus the right number of digits) and cannot be asked of the digits box
   * alone. A pattern only fails when digits have been typed, i.e. after this constraint is satisfied,
   * so the two never compete to report the same fault.
   *
   * DEFAULT FALSE, and `mirror={false}` callers must leave it that way. Native constraint validation
   * is deliberately absent from the design-workshop stage forms — completeness there is judged by
   * `stage_completeness` when a report is generated, not by the browser at save time — so a
   * `required` reaching that surface would refuse a save the repository is happy to accept.
   */
  required = false,
  countryCode,
  countryCodeName
}: {
  name?: string;
  defaultValue?: string | null;
  /** The stored `phoneCountryCode` beside a digits-only `defaultValue` (the record columns). */
  countryCode?: string | null;
  /**
   * Set on a record form: the mirror then submits the digits under `name` and the dial code under
   * this name, the two columns the server stores (lib/standardValues). Unset, it submits the one
   * "+91 9876543210" string a stage box carries.
   */
  countryCodeName?: string;
  onValueChange?: (value: string) => void;
  disabled?: boolean;
  mirror?: boolean;
  required?: boolean;
}) {
  const [{ iso2, digits }, setState] = useState(() =>
    splitStoredPhone(
      countryCode && defaultValue && !defaultValue.trim().startsWith("+")
        ? `${countryCode} ${defaultValue.trim()}`
        : defaultValue
    )
  );
  /**
   * Whether anybody has touched this control since it was seeded. Only ever set, never cleared.
   *
   * It exists for `unshown` below and for nothing else: once the designer has typed, the box IS the
   * value and the stored string it was seeded with is history. `defaultValue` does not change while
   * they type — this component is uncontrolled and `StagePhoneField` re-keys it only when the value
   * arrives from somewhere other than its own `onValueChange` — so without this flag the notice
   * would stand under a box the designer had already corrected.
   */
  const [edited, setEdited] = useState(false);
  const errorId = useId();
  const country = countryByIso2(iso2) ?? countryByIso2(DEFAULT_ISO2)!;
  const dialCode = country.dialCode;
  const combined = digits ? `${dialCode} ${digits}` : "";

  /*
   * ONE FUNCTION, MEASURED ON THE STORED STRING, so this box cannot disagree with the server about
   * the number it is holding.
   *
   * Android parity (ui/PhoneField.kt artisanPhoneValidationError): same rule, same two sentences, so
   * a researcher who corrects a number on the phone reads the same instruction on the laptop. It
   * used to be a ternary written out here, which is how the rule came to exist in two places and be
   * enforced in one.
   *
   * `combined` AND NOT `(dialCode, digits)`: the stored string is what `coerce_value`,
   * `report_builder` and the handset's coerce port all see, so validating the composed value is the
   * only way the message under this box and the refusal from the repository are answers to the same
   * question. It round-trips exactly — `splitStoredPhone` splits at the first space and resolves the
   * prefix, and the rule only depends on whether that prefix is +91 and on the digit count.
   */
  /**
   * The stored string this box CANNOT SHOW, or "" when there is no such thing.
   *
   * ── THE DEFECT THIS CLOSES, WHICH IS A SILENT REVERT AND NOT A COSMETIC ONE ───────────────────
   *
   * This control renders `digits` — what `splitStoredPhone` could parse — and composes `combined`
   * from it. A stored value with no digits in it therefore drew AN EMPTY BOX: `combined` was "",
   * `phoneValidationError("")` was null, and the design workshop's wrapper stays deliberately quiet
   * for a PHONE field (`formatShownByControl`) because this control is supposed to be the one saying
   * it. So `participant.phone` holding "not a number" — copied there by `hydrate_entries` from a
   * column nothing has ever validated — was refused by the server on every save, restored from
   * `previous`, and reported by nobody: the value snapped back on the next GET with no red text
   * anywhere and an empty box where the fault was.
   *
   * A value that IS number-shaped needs none of this: its digits are in the box, so a wrong count is
   * something the designer can see and fix. What this names is the case where the box and the column
   * hold different things.
   *
   * PRINTED VERBATIM, because "there is something here you cannot see" is not actionable and the
   * string itself is: it is usually a note somebody typed into a phone column ("ask his son"), which
   * tells the designer where the number actually is.
   */
  const stored = (defaultValue ?? "").trim();
  const unshown = !edited && stored !== "" && !isPhoneNumberShaped(stored) ? stored : "";

  /*
   * MEASURED ON THE UNSHOWABLE STORED VALUE WHERE THERE IS ONE, so the message under this box is
   * about the thing that will actually be saved. Android has always done it this way round —
   * `FieldRenderer`'s PHONE arm computes `DwTextFormats.error(field.format, DwValues.text(value))`
   * from the stored value and hands it in — and the note there says why: "a HYDRATED number that is
   * already malformed is flagged the moment the stage opens rather than only after somebody happens
   * to edit it".
   */
  const error = phoneValidationError(unshown || combined);

  /**
   * THE NAME IS ON THE ROW AND IT IS WHAT THE FILTER BOX SEARCHES.
   *
   * ── WHAT THIS FIXED ─────────────────────────────────────────────────────────────────────────────
   * The label used to be the flag and the dial code and nothing else, while `lib/countries.ts`
   * carries `name` for all ~246 rows. This is the longest list in the application, so it is well
   * past `SEARCH_THRESHOLD` and DID get a filter box — over text that contained no country name. So
   * typing "india", "uruguay" or "nepal" into the country picker answered "No matches", and the only
   * thing that matched was the dial code, which the reader is opening this control to look up. Dial
   * codes are not unique either: "+1" is shared by the United States and Canada and prefixes about
   * twenty more territories, so a dozen rows read identically apart from an emoji nobody can search.
   * `RENDER_CAP` compounds it — 80 of 246 rows are drawn, so scrolling does not reach the rest and
   * the notice says "keep typing", which was the one thing that did not work.
   *
   * ── WHY THE NAME IS THE `hint` AND NOT THE `label`, WHICH IS THE OTHER WAY ROUND FROM ANDROID ───
   * `SearchableSelect` renders the option's LABEL in the closed trigger, and this trigger is a 9rem
   * column beside the number box — the dial code is the whole of what it has room to say, and the
   * dial code is what the composed value `+91 9876543210` is built from. Android does not have that
   * constraint: `PhoneField.kt` keeps a separate read-only "Code" box always showing the prefix, so
   * its rows are free to lead with the name (`SelectOption(value = dialCode, label = it.name, hint =
   * it.dialCode)`). Same two columns on both clients, same searchability on both clients, opposite
   * order — because on one of them the row's first column is also the collapsed control's only text.
   * Stated here rather than left to look like a divergence nobody noticed.
   */
  const options = useMemo(
    () =>
      COUNTRIES.map((entry) => ({
        value: entry.iso2,
        label: `${flagEmoji(entry.iso2)} ${entry.dialCode}`,
        hint: entry.name
      })),
    []
  );

  function emit(next: { iso2: string; digits: string }) {
    setState(next);
    setEdited(true);
    const nextCountry = countryByIso2(next.iso2);
    onValueChange?.(next.digits && nextCountry ? `${nextCountry.dialCode} ${next.digits}` : "");
  }

  const confirm = useConfirm();
  async function handleCountry(nextIso2: string) {
    if (nextIso2 === iso2) return;
    const next = countryByIso2(nextIso2);
    if (!next) return;
    if (dialCode === DEFAULT_DIAL_CODE && next.dialCode !== DEFAULT_DIAL_CODE) {
      // Not destructive — a "did you mean" check — so this warns rather than alarms.
      // Cancel keeps the +91 prefix untouched.
      const ok = await confirm({
        title: "Use an international number?",
        body: FOREIGN_CONFIRM,
        confirmLabel: `Use ${next.dialCode}`,
        cancelLabel: "Keep +91",
        tone: "warning"
      });
      if (!ok) return;
    }
    emit({ iso2: nextIso2, digits });
  }

  return (
    <div className="relative grid gap-1">
      <div className="flex gap-2">
        <Dropdown
          className="w-36 shrink-0"
          value={iso2}
          onChange={handleCountry}
          options={options}
          disabled={disabled}
          ariaLabel="Country dial code"
        />
        <input
          className="field-input min-w-0 flex-1"
          type="text"
          inputMode="numeric"
          autoComplete="tel-national"
          placeholder={dialCode === DEFAULT_DIAL_CODE ? "10-digit mobile number" : "Phone number"}
          value={digits}
          /*
           * NAMED HERE AND NOT BY THE SURROUNDING LABEL. The caller wraps this component in a block
           * whose heading names the FIELD ("Phone"), and the first labelable element inside it is
           * the dial-code trigger, not this box — so without a name of its own the number input fell
           * back to its own placeholder, which is the one piece of text that disappears the instant
           * somebody types into it. A reader who tabbed back to check a number they had already
           * entered heard an unnamed edit box holding ten digits.
           */
          aria-label="Phone number"
          // See the `required` prop's note: the constraint belongs on the box a reader can see, not
          // on the zero-size mirror below. `aria-required` is not added beside it — `required` on a
          // real input already computes it, and setting both is a second source for one fact.
          required={required}
          aria-invalid={!!error}
          // `aria-invalid` was already set and pointed at nothing: the browser said "invalid" and
          // the REASON — which of the two length rules was broken — was painted in red under the
          // box and announced nowhere. The two halves only work as a pair.
          aria-describedby={error ? errorId : undefined}
          disabled={disabled}
          onChange={(event) => emit({ iso2, digits: event.target.value.replace(/\D/g, "").slice(0, 15) })}
        />
      </div>
      {/* `role="alert"`: the message appears as the researcher types, so it must reach them at the
          moment the number stops being valid rather than when they next happen to pass the box. */}
      {error ? (
        <p id={errorId} role="alert" className="text-xs text-error-600">
          {error}
        </p>
      ) : null}
      {/* WHAT THE COLUMN HOLDS, WHEN IT IS NOT WHAT THE BOX IS SHOWING. See `unshown` above: without
          this line the sentence above stands over an empty box, which reads as an error about
          nothing and gives the designer no way to know a value is there at all. `ink-500`, not
          `error-600` — the refusal is one sentence and this is the fact behind it, not a second
          fault. Deliberately NOT in `aria-describedby`: the error already is, and a screen reader
          reading a stored fragment as part of the field's description on every focus would bury the
          instruction. It is a `<p>` in reading order immediately after it. */}
      {unshown ? (
        <p className="text-xs leading-5 text-ink-500">
          This field holds “{unshown}”, which is not a phone number this box can show. Type the number to replace
          it.
        </p>
      ) : null}
      {/* Zero-size (not hidden) mirror input: submits the single combined value under the existing
          field name AND participates in native constraint validation via the pattern. Suppressed by
          `mirror={false}` for a caller that reads the value through `onValueChange` and has no form
          to submit — see the component doc block. */}
      {mirror ? (
        countryCodeName ? (
          <>
            <input
              type="text"
              name={name}
              value={digits}
              pattern={digits ? (dialCode === DEFAULT_DIAL_CODE ? "\\d{10}" : "\\d{4,14}") : undefined}
              onChange={() => undefined}
              tabIndex={-1}
              aria-hidden="true"
              className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
            />
            <input type="hidden" name={countryCodeName} value={digits ? dialCode : ""} />
          </>
        ) : (
          <input
            type="text"
            name={name}
            value={combined}
            pattern={digits ? (dialCode === DEFAULT_DIAL_CODE ? "\\+91 \\d{10}" : "\\+\\d{1,4} \\d{4,14}") : undefined}
            onChange={() => undefined}
            tabIndex={-1}
            aria-hidden="true"
            className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
          />
        )
      ) : null}
    </div>
  );
}
