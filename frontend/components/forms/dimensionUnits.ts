export const CM_PER_INCH = 2.54;

const DECIMAL_10_2_MAX_SCALED = 9999999999;

const NUMERIC_RE = /^[ \t]*([0-9]+\.?[0-9]*|\.[0-9]+)[ \t]*$/;

export function parseDimension(text: string): number | null {
  if (!NUMERIC_RE.test(text)) return null;
  const value = Number(text.trim());
  return Number.isFinite(value) ? value : null;
}

function renderScaled(scaled: number): string {
  const whole = Math.floor(scaled / 100);
  const frac = scaled % 100;
  if (frac === 0) return String(whole);
  if (frac % 10 === 0) return `${whole}.${frac / 10}`;
  return `${whole}.${String(frac).padStart(2, "0")}`;
}

function scaleHalfUp(value: number): number | null {
  const scaled = Math.floor(value + 0.5);
  return scaled > DECIMAL_10_2_MAX_SCALED ? null : scaled;
}

export function cmTextFromInches(text: string): string | null {
  const inches = parseDimension(text);
  if (inches === null) return null;
  const scaled = scaleHalfUp(inches * CM_PER_INCH * 100);
  return scaled === null ? null : renderScaled(scaled);
}

export function inchesTextFromCm(text: string): string | null {
  const cm = parseDimension(text);
  if (cm === null) return null;
  const scaled = scaleHalfUp((cm / CM_PER_INCH) * 100);
  return scaled === null ? null : renderScaled(scaled);
}

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
