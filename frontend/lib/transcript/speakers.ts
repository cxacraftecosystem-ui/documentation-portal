import type { CSSProperties } from "react";

export type SpeakerColorKey = "neutral" | "s0" | "s1" | "s2" | "s3" | "s4" | "s5" | "s6" | "s7";

export const SPEAKER_PALETTE: Readonly<Record<SpeakerColorKey, { light: string; dark: string }>> = {
  neutral: { light: "#52525B", dark: "#A1A1AA" },
  s0: { light: "#1D4ED8", dark: "#60A5FA" },
  s1: { light: "#B45309", dark: "#FBBF24" },
  s2: { light: "#047857", dark: "#34D399" },
  s3: { light: "#BE185D", dark: "#F472B6" },
  s4: { light: "#6D28D9", dark: "#A78BFA" },
  s5: { light: "#0E7490", dark: "#22D3EE" },
  s6: { light: "#B91C1C", dark: "#F87171" },
  s7: { light: "#4D7C0F", dark: "#A3E635" }
};

const PALETTE_SIZE = 8;

export const SPEAKER_TEXT = "text-[var(--speaker-light)] dark:text-[var(--speaker-dark)]";
export const SPEAKER_FILL = "bg-[var(--speaker-light)] dark:bg-[var(--speaker-dark)]";

export function fnv1a(text: string): number {
  let value = 0x811c9dc5;
  for (const byte of new TextEncoder().encode(text)) {
    value ^= byte;
    value = Math.imul(value, 0x01000193) >>> 0;
  }
  return value >>> 0;
}

export function speakerColorKey(speakerKey: string | null | undefined, label?: string | null): SpeakerColorKey {
  const key = (speakerKey ?? "").trim();
  if (key) {
    const numbered = /^speaker_(\d+)$/.exec(key);
    return `s${(numbered ? Number(numbered[1]) : fnv1a(key)) % PALETTE_SIZE}` as SpeakerColorKey;
  }
  const name = String(label ?? "").split(/\s+/).filter(Boolean).join(" ");
  if (!name) return "neutral";
  const numbered = /^Speaker (\d+)$/.exec(name);
  if (numbered && Number(numbered[1]) >= 1) return `s${(Number(numbered[1]) - 1) % PALETTE_SIZE}` as SpeakerColorKey;
  return `s${fnv1a(name) % PALETTE_SIZE}` as SpeakerColorKey;
}

export function resolveColorKey(sent: string | null | undefined, speakerKey: string | null | undefined, label?: string | null): SpeakerColorKey {
  return sent && sent in SPEAKER_PALETTE ? (sent as SpeakerColorKey) : speakerColorKey(speakerKey, label);
}

export function speakerStyle(key: SpeakerColorKey): CSSProperties {
  const colour = SPEAKER_PALETTE[key] ?? SPEAKER_PALETTE.neutral;
  return { "--speaker-light": colour.light, "--speaker-dark": colour.dark } as CSSProperties;
}
