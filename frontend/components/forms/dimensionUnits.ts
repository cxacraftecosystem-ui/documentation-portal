/**
 * CENTIMETRES ↔ INCHES FOR THE TOOL RECORD FORM, and the four rules that keep a live conversion from
 * eating the number it is converting.
 *
 * `ToolDocumentation` pairs `height` with `heightInches` and `width` with `breadthInches`. They were
 * two unrelated columns until this change — the form's own note said so and told researchers to fill
 * one and not the other — and they are now the SAME measurement in two units, filled from whichever
 * box the researcher types in. `lengthInches` has no centimetre partner and gains none.
 *
 * ── WHY THIS IS A MODULE AND NOT TWO EXPRESSIONS INSIDE THE FORM ────────────────────────────────
 * The same split, for the same reason, as `components/forms/measurementMethods.ts` and
 * `components/media/gridProposal.ts`: there is no React renderer in this repository's
 * devDependencies, so a judgement written inside JSX is exercised by nobody. What this file decides
 * is the class of thing whose broken state looks EXACTLY like its working state — a plausible number
 * in a box — until somebody reads the record a year later. `e2e/dimension-units-unit.spec.ts` drives
 * every row of the table this was specified against.
 *
 * ── THE SAME ARITHMETIC RUNS ON THE HANDSET, AND THE TWO MUST NOT DRIFT ─────────────────────────
 * `android/app/src/main/java/com/fieldrepository/app/ui/DimensionUnits.kt` is the Kotlin twin, with
 * the same four function names and the same worked table in its own JVM test. Kotlin's
 * `String.toDouble` and JavaScript's `Number()` disagree at the edges — `Number("0x1A")` is 26 while
 * `"0x1A".toDouble()` throws, `"1.5f".toDouble()` is 1.5 while `Number("1.5f")` is `NaN`, and both
 * accept `"Infinity"` and `"NaN"` — so the grammar below is a regular expression both languages
 * implement identically rather than either language's own parser. If you change a rule here, change
 * it there.
 *
 * ── AND IT IS **NOT** THE BACKEND'S `_inches_to_cm` ─────────────────────────────────────────────
 * The sibling application's `design_workshops._inches_to_cm` is `round(float(v) * 2.54, 2)` —
 * Python's banker's rounding, which breaks a halfway value TOWARDS THE EVEN hundredth while this
 * file breaks it upwards. The two live on opposite sides of the wire, are applied to different
 * columns, and must not be "unified": a single shared helper would silently move every figure that
 * lands on a halfway value in whichever of the two it was not written for. This repository has no
 * design-workshop carry at all, so there is nothing here to unify them WITH — the paragraph is kept
 * so that the next reader porting a change from the sibling repository does not try.
 */

/**
 * EXACT, by definition rather than by measurement: the inch has been 25.4 mm since the 1959
 * international yard-and-pound agreement. Never "2.5 for readability", and never a pre-divided
 * `0.3937…` — see the association-order note on the two converters below.
 */
export const CM_PER_INCH = 2.54;

/**
 * The largest value `Decimal(10, 2)` can hold, in hundredths: 99,999,999.99.
 *
 * A conversion that overflows the column writes NOTHING rather than a truncated number. Postgres
 * refuses the value at the boundary with `numeric field overflow`, which on a save reaches the
 * researcher as an opaque 500 — long after the box was filled in, and naming a column rather than
 * the box they typed into.
 */
const DECIMAL_10_2_MAX_SCALED = 9999999999;

/**
 * What counts as a number in a dimension box, written as one grammar for both clients.
 *
 * Deliberately NARROWER than either language's own string-to-double: no sign (a negative dimension
 * is not a measurement, and every one of these boxes carries `min={0}`), no exponent, no hexadecimal,
 * no `Infinity`, no `NaN`, no thousands separator. Leading and trailing spaces and tabs are
 * tolerated because a paste carries them.
 *
 * `"1."` AND `".5"` ARE ADMITTED ON PURPOSE. They are what a person typing a decimal produces
 * halfway through, and the consequence is spelled out at {@link propagate}.
 */
const NUMERIC_RE = /^[ \t]*([0-9]+\.?[0-9]*|\.[0-9]+)[ \t]*$/;

/**
 * The number in a dimension box, or `null` when the box does not hold one.
 *
 * A total function: everything the grammar admits is finite and non-negative by construction, and
 * the `Number.isFinite` line below is kept anyway so that the type says so without the reader having
 * to re-derive it from the regular expression.
 */
export function parseDimension(text: string): number | null {
  if (!NUMERIC_RE.test(text)) return null;
  const value = Number(text.trim());
  return Number.isFinite(value) ? value : null;
}

/**
 * A hundredths-scaled integer rendered as the shortest honest decimal string.
 *
 * BUILT FROM THE INTEGER, NEVER FROM `Number.prototype.toString`. JavaScript prints `"3"` for an
 * integral `3.0` where Kotlin prints `"3.0"`, and the two disagree again on large magnitudes — so
 * the only way four clients can agree on the text in the box is to never let either language's
 * float formatter near it.
 */
function renderScaled(scaled: number): string {
  const whole = Math.floor(scaled / 100);
  const frac = scaled % 100;
  if (frac === 0) return String(whole);
  if (frac % 10 === 0) return `${whole}.${frac / 10}`;
  return `${whole}.${String(frac).padStart(2, "0")}`;
}

/**
 * Round half UP (towards +∞), in one expression, on a value the grammar guarantees is non-negative.
 *
 * `Math.round` is not used, and the reason is not style: `kotlin.math.round` is half-AWAY-FROM-ZERO
 * while the JVM's `Math.round` is `floor(x + 0.5)`, so the twin implementations would agree on every
 * value this form can produce and disagree the first time somebody reuses one of them on a signed
 * quantity. `floor(x + 0.5)` is the rule written down rather than inherited.
 *
 * ONE ROUNDING, NEVER TWO. Rounding to two decimals and then formatting from a re-derived double
 * rounds twice and can move the last hundredth.
 */
function scaleHalfUp(value: number): number | null {
  const scaled = Math.floor(value + 0.5);
  return scaled > DECIMAL_10_2_MAX_SCALED ? null : scaled;
}

/**
 * The centimetre text for an inch box's contents, or `null` when there is nothing to write.
 *
 * `value * CM_PER_INCH * 100` IS LEFT-TO-RIGHT AND MUST STAY SO. Rewriting it as `value * 254` is
 * not the same computation in binary64: each rewrite moves the last ulp and can flip a hundredth.
 * The worked table in `e2e/dimension-units-unit.spec.ts` is what catches that, and `1.25 → 3.18`
 * (whose intermediate is exactly 317.5) is the row that catches it first.
 */
export function cmTextFromInches(text: string): string | null {
  const inches = parseDimension(text);
  if (inches === null) return null;
  const scaled = scaleHalfUp(inches * CM_PER_INCH * 100);
  return scaled === null ? null : renderScaled(scaled);
}

/** The inch text for a centimetre box's contents, or `null`. Same association rule as above. */
export function inchesTextFromCm(text: string): string | null {
  const cm = parseDimension(text);
  if (cm === null) return null;
  const scaled = scaleHalfUp((cm / CM_PER_INCH) * 100);
  return scaled === null ? null : renderScaled(scaled);
}

/**
 * Write the partner box for one keystroke in the box the researcher is actually typing in.
 *
 * ── THE THREE RULES, IN THE ORDER THEY ARE TESTED ───────────────────────────────────────────────
 *
 * 1. **AN EMPTY BOX CLEARS ITS PARTNER**, and this branch comes BEFORE the parse because it is the
 *    one input that is not a number and still means something: empty means *no value*, and leaving a
 *    stale converted number standing beside it is a lie the record cannot detect afterwards.
 * 2. **ANYTHING ELSE THAT IS NOT A NUMBER WRITES NOTHING.** `"abc"`, `"1.2.3"`, `"1e3"` and an
 *    overflow of `Decimal(10, 2)` all leave the partner exactly as it is.
 * 3. **A PARTIALLY TYPED DECIMAL IS A NUMBER, NOT A MISTAKE:** `1.` parses and converts, so the
 *    partner tracks the keystrokes instead of blanking and refilling. Only a string that cannot be a
 *    number at all leaves the partner alone — and only an EMPTY box clears it, because empty is the
 *    one input that means "no value". Typing `1` → `1.` → `1.5` walks the partner `2.54` → `2.54` →
 *    `3.81`; typing `1.2` → `1.2.` holds `3.05` for one keystroke and corrects on the next, which is
 *    strictly better than a partner that blanks, because a blank partner is rule 1's statement and
 *    somebody mid-decimal has not made it.
 *
 * ── IT IS CALLED FROM THE SOURCE BOX'S OWN `onChange` AND FROM NOWHERE ELSE ─────────────────────
 * One-directional per keystroke: the box being typed in writes its partner, and the partner never
 * writes back. A `useEffect` watching both values is the shape that cannot work — it fires for
 * whichever of the two changed, so 1 cm becomes 0.39 in becomes 0.99 cm and a round trip eats the
 * value. React's `setState` does not re-invoke the target input's `onChange`, so the natural shape
 * is already the correct one; it is written down here because the effect version reads like a
 * tidy-up.
 *
 * ── AND IT NEVER RUNS ON LOAD ──────────────────────────────────────────────────────────────────
 * Historic rows genuinely hold unrelated numbers in `height` and `heightInches` — that is what the
 * old on-screen note was about — so a conversion at mount would rewrite a saved value the moment
 * somebody merely OPENED the record. Each box is seeded from its own column and nothing else.
 */
export function propagate(
  text: string,
  convert: (text: string) => string | null,
  setPartner: (value: string) => void
): void {
  if (text.trim() === "") {
    setPartner("");
    return;
  }
  const next = convert(text);
  if (next !== null) setPartner(next);
}
