/**
 * The arithmetic behind `OtpCodeInput` (components/ui/OtpCodeInput.tsx) — pure, so the rules are
 * pinned by `e2e/primitives-unit.spec.ts` without a React renderer.
 *
 * THE RULES (owner, 2026-10-17): an authenticator code is EXACTLY six digits, drawn as six separate
 * boxes, and nothing but a digit may ever land in a box. Pasting a six-digit string fills all six;
 * typing advances; Backspace on an empty box steps back and clears the one before; the code is
 * complete when all six boxes hold a digit. Recovery codes are a different shape (`xxxx-xxxx`) and go
 * in their own text field, never in these boxes.
 *
 * Android's twin is `ui/OtpCode.kt` (`OtpCodeField`), which follows the same rules to the character.
 */

export const OTP_LENGTH = 6;

/** Every non-digit removed. Full-width and other Unicode digits are NOT digits here: only 0-9. */
export function onlyDigits(text: string): string {
  return text.replace(/[^0-9]/g, "");
}

/** Six empty boxes. */
export function emptyDigits(): string[] {
  return Array.from({ length: OTP_LENGTH }, () => "");
}

/** A code string (anything — stray characters are dropped) spread over the six boxes. */
export function splitCode(code: string): string[] {
  const clean = onlyDigits(code).slice(0, OTP_LENGTH);
  return Array.from({ length: OTP_LENGTH }, (_, index) => clean[index] ?? "");
}

/** The boxes read left to right; empty boxes contribute nothing. */
export function joinDigits(digits: readonly string[]): string {
  return digits.join("");
}

/** All six boxes hold a digit. */
export function isCompleteCode(code: string): boolean {
  return new RegExp(`^[0-9]{${OTP_LENGTH}}$`).test(code);
}

export type OtpEdit = { digits: string[]; focus: number };

/**
 * `text` typed or pasted into box `index`.
 *
 * - Nothing that is a digit → nothing changes (a letter is refused, not stored).
 * - Six or more digits → a whole code: the boxes are filled from the FIRST box whichever box held
 *   focus, because a pasted or autofilled code is always the whole code.
 * - Fewer → written from `index` onwards, and focus moves to the box after the last one written.
 */
export function enterDigits(digits: readonly string[], index: number, text: string): OtpEdit {
  // Only ASCII 0-9 counts: an Arabic-Indic or full-width digit is not one of the server's digits.
  const clean = onlyDigits(text);
  const at = Math.min(Math.max(index, 0), OTP_LENGTH - 1);
  if (!clean) return { digits: [...digits], focus: at };
  if (clean.length >= OTP_LENGTH) {
    return { digits: splitCode(clean), focus: OTP_LENGTH - 1 };
  }
  const next = [...digits];
  let cursor = at;
  for (const digit of clean) {
    if (cursor >= OTP_LENGTH) break;
    next[cursor] = digit;
    cursor += 1;
  }
  return { digits: next, focus: Math.min(cursor, OTP_LENGTH - 1) };
}

/**
 * Backspace in box `index`: a box with a digit is emptied and keeps focus; an EMPTY box moves focus
 * back one and empties that one, so holding Backspace walks the code away right to left.
 */
export function backspaceAt(digits: readonly string[], index: number): OtpEdit {
  const next = [...digits];
  if (next[index]) {
    next[index] = "";
    return { digits: next, focus: index };
  }
  if (index === 0) return { digits: next, focus: 0 };
  next[index - 1] = "";
  return { digits: next, focus: index - 1 };
}

/** What a screen reader hears for box `index`. */
export function digitLabel(index: number): string {
  return `Digit ${index + 1} of ${OTP_LENGTH}`;
}
