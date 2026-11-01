import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { plainSnippet, previewMarkdown, previewOf, type PreviewTurn } from "../lib/transcript/preview";
import { fnv1a, SPEAKER_FILL, SPEAKER_PALETTE, SPEAKER_TEXT, speakerColorKey } from "../lib/transcript/speakers";

/**
 * A transcript read for a list row, and one colour per speaker (owner, 1 Nov 2026), held to the
 * transcript contract v2 (`fixtures.preview`, `fixtures.speakerColor`, `speakerPalette`), which the
 * server's `preview.py` reproduces too. Pure: shared by both portals (`shared/transcript-web.sha256`).
 */

const CONTRACT = JSON.parse(readFileSync(join(__dirname, "..", "..", "shared", "transcript-contract.json"), "utf8"));

type PreviewCase = { name: string; markdown?: string; turns?: { speakerKey: string | null; speaker: string | null; text: string }[]; expected: unknown };

test.describe("transcript preview", () => {
  for (const vector of CONTRACT.fixtures.preview.cases as PreviewCase[]) {
    test(`fixtures.preview: ${vector.name}`, () => {
      const got = vector.markdown !== undefined ? previewMarkdown(vector.markdown) : previewOf((vector.turns ?? []) as PreviewTurn[]);
      expect(got).toEqual(vector.expected);
    });
  }

  test("the owner's example reads as labelled text with no asterisks and no Transcript prefix", () => {
    const got = previewMarkdown("Transcript **Speaker 1:** Wow!\n**Speaker 2:** Yes, my uncle carved it.");
    expect(got?.lines.map((line) => [line.speaker, line.text])).toEqual([
      ["Speaker 1", "Wow!"],
      ["Speaker 2", "Yes, my uncle carved it."]
    ]);
    expect(got?.text).not.toMatch(/[*`]|^Transcript/);
  });

  test("a search snippet loses its Markdown punctuation, not its words", () => {
    expect(plainSnippet("**Speaker 1:** the `dabu` paste")).toBe("Speaker 1: the dabu paste");
  });
});

test.describe("speaker colours", () => {
  test("fixtures.speakerColor", () => {
    for (const [key, label, expected] of CONTRACT.fixtures.speakerColor.cases as [string | null, string | null, string][]) {
      expect(speakerColorKey(key, label), `${key} / ${label}`).toBe(expected);
    }
  });

  test("the palette is the contract's, slot for slot", () => {
    const { about: _about, ...palette } = CONTRACT.speakerPalette as Record<string, unknown>;
    void _about;
    expect(SPEAKER_PALETTE).toEqual(palette);
  });

  test("FNV-1a is the 32-bit one", () => {
    expect(fnv1a("")).toBe(0x811c9dc5);
    expect(fnv1a("a")).toBe(0xe40c292c);
  });

  test("a label and a mark are drawn from the same two custom properties", () => {
    expect(SPEAKER_TEXT.replace(/text-/g, "bg-")).toBe(SPEAKER_FILL);
  });
});
