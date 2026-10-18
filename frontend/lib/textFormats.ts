/**
 * THE TWO TEXT-FORMAT RULES THAT HAD NOWHERE TO LIVE, AS ONE FUNCTION EACH, IN A FILE THAT CANNOT
 * PULL REACT IN.
 *
 * `FieldSpec.text_format` on the server declares that a stage field holds an email address, an
 * Indian phone number, an Aadhaar number, a Pehchan card number or a PIN code. `coerce_value`
 * enforces it on every save, both clients preview it as the designer types, and THE THREE MUST SAY
 * THE SAME SENTENCE — so the rules that had no home yet live here, once, and every caller reads
 * them rather than restating them.
 *
 * ── WHY THIS FILE EXISTS AT ALL, WHICH IS A MEASURED FAILURE AND NOT A TIDINESS POINT ───────────
 *
 * Before it, "is this a valid email address" had three answers in this repository and nobody knew:
 *
 *   - the record page: `EMAIL_RE` — private to `ArtisanForm.tsx`, and restated a second time seven
 *     hundred lines below as the literal `pattern` attribute of the same input;
 *   - the handset: `StageSchema.kt`'s EMAIL arm of `coerce`, which refused a missing `@` and a
 *     leading or trailing `@` and nothing else;
 *   - the server: NOTHING, on either path — `ArtisanCreate.email` is a bare `str | None`.
 *
 * So the browser accepted what the phone refused, and the repository accepted what the phone had
 * refused. Three implementations, three answers, same intent, and the only reason nobody noticed is
 * that a malformed email address carries fine: it is not wrong until somebody tries to write to it,
 * which is months later and in a different building. Phone was the same story with two answers and
 * a hole where the third should be.
 *
 * ── PURE ON PURPOSE ────────────────────────────────────────────────────────────────────────────
 *
 * No `"use client"`, no React, no `apiFetch`, and it may not gain any: the record forms, the stage
 * form, the dispatch table in `components/designworkshop/stageFieldFormats.ts` and a Playwright
 * unit spec all import it, and a rule that drags a dropdown and a confirm dialog behind it is a
 * rule people copy instead of importing. `lib/countries` is the one dependency and it is a data
 * table.
 *
 * The Aadhaar and PIN code rules are deliberately NOT here. Both already exist, exported, in the
 * record page's own controls (`AadhaarField.aadhaarValidationError`, its Verhoeff table and all;
 * `LocationFields.pincodeValidationError`), and moving them would be churn that proves nothing —
 * the dispatch table imports them where they are. This file holds the two that had nowhere to live.
 */

import {
  DEFAULT_DIAL_CODE,
  DEFAULT_ISO2,
  countryByIso2,
  countryForDialCode,
  splitDialPrefix
} from "@/lib/countries";

/* ────────────────────────────────────────────────────────────────────────────
 * EMAIL
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The email shape, WITHOUT anchors, so the one string can be both a `RegExp` and an HTML `pattern`.
 *
 * THAT IS THE WHOLE REASON IT IS SPLIT OUT. `ArtisanForm` needs the rule twice — once to compute
 * the inline message as the researcher types, and once as the `pattern` attribute that makes the
 * record form's own Submit refuse it natively — and it used to satisfy the second by writing the
 * expression out again by hand. Two literals with no test between them is how a rule comes to have
 * two versions, and the browser would then block one set of strings while the red text underneath
 * named another.
 *
 * `pattern` is implicitly anchored by the HTML spec, so this source is used bare there and wrapped
 * in `^…$` here.
 *
 * DELIBERATELY NOT AN RFC 5322 GRAMMAR. The rule this repository has always applied, and the one
 * its message names, is "something, an @, something, a dot, something, and no spaces" — enough to
 * catch the mistakes a person standing in a courtyard actually makes (a name with no domain, a
 * pasted line with a space in it, a domain with no dot) and permissive enough never to refuse a
 * real address it does not recognise. A stricter grammar here would reject valid addresses in a
 * field app that gives the designer no way to override it, which is the worse of the two failures.
 */
export const EMAIL_PATTERN = "[^\\s@]+@[^\\s@]+\\.[^\\s@]+";

/** {@link EMAIL_PATTERN}, anchored, for use in code. */
export const EMAIL_RE = new RegExp(`^${EMAIL_PATTERN}$`);

/**
 * ONE SENTENCE FOR A MALFORMED EMAIL ADDRESS, EVERYWHERE.
 *
 * The record page's own wording, character for character, and now also the handset's and the
 * server's. Android used to say "Email is not a valid email address" — the registry label followed
 * by a restatement of the label — which is both worse prose and a second sentence for one fault; a
 * designer who corrects an address on the phone and again on a laptop must not have to work out
 * whether two differently-phrased lines are the same complaint. It names the SHAPE
 * (`name@example.com`) because that is the only actionable thing anyone can say about an address
 * nobody can look up.
 */
export const EMAIL_MESSAGE = "Enter a valid email address (name@example.com).";

/**
 * The reason `value` is not a usable email address, or null when it is fine — BLANK INCLUDED.
 *
 * Blank is never this function's complaint. Whether an empty answer is allowed is a question about
 * `required`, asked by `validate_entry` on the server and by the required-count on both clients,
 * and a format rule that also refused emptiness would paint every untouched optional email box red
 * the moment a stage opened.
 */
export function emailValidationError(value: string | null | undefined): string | null {
  const trimmed = (value ?? "").trim();
  if (!trimmed) return null;
  return EMAIL_RE.test(trimmed) ? null : EMAIL_MESSAGE;
}

/* ────────────────────────────────────────────────────────────────────────────
 * PHONE (the +91 rule)
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Split a STORED phone string — "+CC rest", "+CCrest", or bare digits — into a country and its
 * national digits.
 *
 * MOVED HERE OUT OF `PhoneField.tsx`, WHERE IT WAS PRIVATE, AND THE MOVE IS THE POINT: it is the
 * authoritative definition of how one stored string comes apart, and the validator has to use the
 * SAME one or the two will disagree about which rule applies to a number. `PhoneField` imports it
 * back; there is no second parser.
 *
 * THE LEGACY ARM IS LOAD-BEARING AND MUST NOT BE "TIDIED" INTO A REFUSAL. A bare run of digits with
 * no `+` is how every row written before the dial-code picker existed is stored, and those rows are
 * Indian nationals — ten digits under +91. `coerce_value` re-coerces EVERY field on EVERY save, so
 * a parser that read a bare "9876543210" as "no country, therefore invalid" would refuse every
 * pre-existing artisan and every pre-existing stage entry the next time anybody touched the stage
 * they sit on — and `save_stage` would then paint a permanent error on a box nobody had edited,
 * naming a fault the designer cannot fix because the value is correct.
 */
export function splitStoredPhone(raw: string | null | undefined): { iso2: string; digits: string } {
  const value = (raw ?? "").trim();
  if (!value) return { iso2: DEFAULT_ISO2, digits: "" };
  if (value.startsWith("+")) {
    const space = value.indexOf(" ");
    if (space > 0) {
      const country = countryForDialCode(value.slice(0, space));
      if (country) return { iso2: country.iso2, digits: value.slice(space + 1).replace(/\D/g, "") };
    }
    const match = splitDialPrefix(value);
    if (match) return { iso2: match.country.iso2, digits: match.rest };
    return { iso2: DEFAULT_ISO2, digits: value.replace(/\D/g, "") };
  }
  // Bare numbers (legacy rows) are Indian nationals: 10 digits under +91.
  return { iso2: DEFAULT_ISO2, digits: value.replace(/\D/g, "") };
}

/**
 * The characters that may sit BETWEEN the digits of a phone number: the space both clients compose,
 * the tabs and newlines a paste can carry, the no-break space an IME produces, the ASCII hyphen and
 * the six dashes U+2010 to U+2015.
 *
 * ENUMERATED RATHER THAN `\s`, WHICH IS THE ONLY WAY THREE LANGUAGES CAN AGREE. `\s` means three
 * different sets here: JavaScript's matches the no-break space and U+FEFF, Python's matches the
 * no-break space, and Kotlin's (Java's, without `UNICODE_CHARACTER_CLASS`) matches neither. A rule
 * built on it would be STRICTER ON THE HANDSET than on the server, which is the one direction this
 * whole feature refuses: a red line under a value the repository accepts, on a stage a designer
 * cannot get past. Spelled with escapes for the reason `SEPARATORS` in `stageFieldFormats.ts` gives
 * — three indistinguishable dashes in a character class are not reviewable in a diff.
 */
const PHONE_SEPARATORS = /[ \t\r\n\u00a0\u2010-\u2015-]+/g;
/** What is left once those come off: an optional `+` and then digits, nothing else. */
const PHONE_COMPACT = /^\+?[0-9]+$/;

/**
 * True when `stored` holds nothing but a dial code, digits and separators.
 *
 * THE HOLE THE 4–14 WINDOW LEAVES OPEN, AND IT IS THE ONE A PRINTED ROSTER SHOWS. The rule below
 * counts DIGITS — everything else is stripped before it looks — so it says nothing whatever about
 * the string. Measured: `"+91 9876543210 call his son Ramesh on the landline instead"` and
 * `"abc9876543210def"` were both accepted, on all three sides, and printed verbatim. The registry's
 * `max_length` closes the first (it is 57 characters against a bound of 20) and cannot close the
 * second (sixteen), so the shape has to be checked as well as the count.
 *
 * IT CANNOT REFUSE ANYTHING EITHER PICKER COMPOSES: both write `${dialCode} ${digits}` and nothing
 * else, and the legacy shape is a bare run of digits. What it refuses is what arrived through the
 * API or was typed into a box that had no rule — which is the population this format exists for.
 *
 * Exported because `PhoneField` needs the same question answered for a different purpose: a value
 * this returns false for is a value that control cannot SHOW, and it says so rather than drawing an
 * empty box.
 */
export function isPhoneNumberShaped(stored: string | null | undefined): boolean {
  const compact = (stored ?? "").trim().replace(PHONE_SEPARATORS, "");
  return compact !== "" && PHONE_COMPACT.test(compact);
}

/** The +91 sentence. Both clients and the server say exactly this. */
export const PHONE_IN_MESSAGE = "Enter a 10-digit number for +91.";
/** The everywhere-else sentence. The dash is an EN DASH, as on the record page and on Android. */
export const PHONE_INTL_MESSAGE = "Enter a valid phone number (4–14 digits).";

/**
 * The reason a STORED phone string is not a usable number, or null when it is fine (blank included).
 *
 * TAKES THE STORED STRING, NOT A (COUNTRY, DIGITS) PAIR, because the stored string is the only thing
 * the server, the draft store, the report writer and Android's coerce port all hold. A validator
 * shaped around the picker's internal state could only ever be called from the picker, which is
 * exactly how this rule came to be enforced on the record page and nowhere else.
 *
 * Same two sentences and the same order as `PhoneField`'s inline expression was, and as
 * `ui/PhoneField.kt artisanPhoneValidationError` still is.
 *
 * THE SHAPE IS CHECKED AS WELL AS THE COUNT — see {@link isPhoneNumberShaped}. Both arms below
 * measure DIGITS, having stripped everything else, so on their own they accept a paragraph with a
 * number in it. A failed shape reuses the arm's own sentence rather than adding a third: "Enter a
 * 10-digit number for +91." is the right instruction for `"abc9876543210def"`, and a new sentence
 * would have to be ported to the server, the handset and the fixture table to say the same thing.
 */
export function phoneValidationError(stored: string | null | undefined): string | null {
  // Blank is `required`'s question, never a format's — the same convention as every other rule here.
  if (!(stored ?? "").trim()) return null;
  const { iso2, digits } = splitStoredPhone(stored);
  /*
   * `!digits` USED TO RETURN NULL HERE, AND THAT WAS THE ONE ROW THE VECTOR TABLE HELD UNDER
   * `server_only`, ON THE GROUND THAT "no client can reach it".
   *
   * That was true of the CONTROL and false of the DATA. `PhoneField`'s box only accepts digits, so
   * nothing a designer types can compose "not a number" — but nothing has ever validated
   * `Artisan.phone`, `hydrate_entries` copies it straight into `participant.phone`, and
   * `coerce_value` re-coerces every field on every save. So a stored value with no digits in it was
   * refused by the server on EVERY save, restored from `previous`, and called clean by both
   * previews: no error on the page, Save pressed, and the value silently back on the next GET. That
   * is the exact failure this feature was written to end, and it was sitting inside the feature.
   *
   * The row has moved out of `server_only` into the shared table, and the guard above is what keeps
   * an empty BOX quiet: it composes to "", which is blank, not "no digits".
   */
  const shaped = isPhoneNumberShaped(stored);
  const dialCode = (countryByIso2(iso2) ?? countryByIso2(DEFAULT_ISO2)!).dialCode;
  if (dialCode === DEFAULT_DIAL_CODE) return shaped && digits.length === 10 ? null : PHONE_IN_MESSAGE;
  return shaped && digits.length >= 4 && digits.length <= 14 ? null : PHONE_INTL_MESSAGE;
}
