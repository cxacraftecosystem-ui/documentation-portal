const pad = (value: number) => String(value).padStart(2, "0");

/** A turn's timestamp: HH:MM:SS, floored to the second, always two-digit hours (fixtures.timestamp). */
export function clock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  return `${pad(Math.floor(total / 3600))}:${pad(Math.floor((total % 3600) / 60))}:${pad(total % 60)}`;
}

/** A recording's length: M:SS under an hour, H:MM:SS from an hour (fixtures.length). */
export function lengthLabel(ms: number | null): string {
  const total = Math.max(0, Math.floor((ms ?? 0) / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

/** US dollars, two places ("12.40"). */
export function usd(amount: number): string {
  return (Math.round(amount * 100) / 100).toFixed(2);
}
