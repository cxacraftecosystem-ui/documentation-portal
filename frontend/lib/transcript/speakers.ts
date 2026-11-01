import type { CSSProperties } from "react";

/**
 * ONE COLOUR PER DIARIZED SPEAKER, the same on every surface: their label on a turn, in the reading
 * view and in a list preview, and their marks on the audio timeline (owner, 1 Nov 2026).
 *
 * The palette and the rule are the transcript contract's (`speakerPalette`, `fixtures.speakerColor`,
 * contract v2), so the server's `colorKey`, this client and Android agree: `speaker_<n>` → `s<n mod
 * 8>`; no key and a default label "Speaker <n>" → `s<n − 1>`; any other key, else the label → FNV-1a
 * of its UTF-8 bytes mod 8; neither → `neutral`. The colour depends on the speaker KEY only, so a
 * rename never moves it. A view that carries `colorKey` is read as sent; this is the fallback for
 * one that does not (an older server, a preview built here).
 *
 * Drawn through two custom properties and one literal class pair, `text-[var(--speaker-light)]
 * dark:text-[var(--speaker-dark)]` (and the `bg-` pair for a mark), because Tailwind compiles only
 * the class strings it can read and the palette is hex.
 */

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

/** The label ink, in this speaker's colour in either theme. */
export const SPEAKER_TEXT = "text-[var(--speaker-light)] dark:text-[var(--speaker-dark)]";
/** A mark's fill: the same colour as the label, so the two read as one person. */
export const SPEAKER_FILL = "bg-[var(--speaker-light)] dark:bg-[var(--speaker-dark)]";

/** 32-bit FNV-1a over UTF-8, as the server's `preview.fnv1a`. */
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

/** A server key as sent, when it names a palette slot; else the rule. */
export function resolveColorKey(sent: string | null | undefined, speakerKey: string | null | undefined, label?: string | null): SpeakerColorKey {
  return sent && sent in SPEAKER_PALETTE ? (sent as SpeakerColorKey) : speakerColorKey(speakerKey, label);
}

/** The inline style that carries a speaker's two colours to `SPEAKER_TEXT` / `SPEAKER_FILL`. */
export function speakerStyle(key: SpeakerColorKey): CSSProperties {
  const colour = SPEAKER_PALETTE[key] ?? SPEAKER_PALETTE.neutral;
  return { "--speaker-light": colour.light, "--speaker-dark": colour.dark } as CSSProperties;
}
