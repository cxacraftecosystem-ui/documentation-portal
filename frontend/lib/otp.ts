export const OTP_LENGTH = 6;

export function onlyDigits(text: string): string {
  return text.replace(/[^0-9]/g, "");
}

export function emptyDigits(): string[] {
  return Array.from({ length: OTP_LENGTH }, () => "");
}

export function splitCode(code: string): string[] {
  const clean = onlyDigits(code).slice(0, OTP_LENGTH);
  return Array.from({ length: OTP_LENGTH }, (_, index) => clean[index] ?? "");
}

export function joinDigits(digits: readonly string[]): string {
  return digits.join("");
}

export function isCompleteCode(code: string): boolean {
  return new RegExp(`^[0-9]{${OTP_LENGTH}}$`).test(code);
}

export type OtpEdit = { digits: string[]; focus: number };

export function enterDigits(digits: readonly string[], index: number, text: string): OtpEdit {
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

export function digitLabel(index: number): string {
  return `Digit ${index + 1} of ${OTP_LENGTH}`;
}
