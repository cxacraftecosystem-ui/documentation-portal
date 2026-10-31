import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import type { Transcript, TranscriptTurn } from "../lib/transcript/contract";
import { clock, lengthLabel } from "../lib/transcript/format";
import { locate, pieces } from "../lib/transcript/karaoke";
import { copyAll, copySelected, copySingle } from "../lib/transcript/markdown";
import { safeUrl } from "../lib/transcript/sanitize";
import { statusAction, statusLabel } from "../lib/transcript/status";

/**
 * THE WEB VIEWER AGAINST THE BACKEND'S CONTRACT (`shared/transcript-contract.json`, transcript-v1-api).
 *
 * The contract lets a client build copy text only if it reproduces `fixtures.copy` byte for byte;
 * these tests run the web's renderer over `examples.TranscriptView` and compare. They also hold the
 * strings copy to the shared file, the example to the viewer's types, the timestamp and length rules,
 * the status actions, karaoke's lookup and the sanitiser's link rule. Pure: no browser, no server.
 */

const REPO = join(__dirname, "..", "..");
const CONTRACT = JSON.parse(readFileSync(join(REPO, "shared", "transcript-contract.json"), "utf8"));
const VIEW = CONTRACT.examples.TranscriptView as Transcript;
const COPY = CONTRACT.fixtures.copy as Record<string, string>;
const turn = (id: string) => VIEW.turns.find((entry) => entry.id === id) as TranscriptTurn;

test("the web's strings are the shared file, byte for byte", () => {
  const lf = (path: string) => readFileSync(path, "utf8").split("\r\n").join("\n");
  expect(lf(join(REPO, "frontend", "lib", "transcript", "strings.json"))).toBe(lf(join(REPO, "shared", "strings", "transcript.json")));
});

test("the example carries every field the viewer reads", () => {
  const keys: (keyof Transcript)[] = [
    "mediaId", "title", "status", "queueReason", "statusMessage", "version", "languages", "durationMs", "speakerCount", "badges",
    "speakers", "turns", "summary", "quotes", "cost", "audio", "permissions", "revision", "updatedAt"
  ];
  for (const key of keys) expect(VIEW, key).toHaveProperty(key);
  const turnKeys: (keyof TranscriptTurn)[] = [
    "id", "index", "speakerKey", "speakerName", "startMs", "endMs", "language", "text", "textSource", "lowConfidence", "words", "translation", "revision"
  ];
  for (const key of turnKeys) expect(VIEW.turns[0], key).toHaveProperty(key);
  for (const key of ["edit", "retry", "transcribeNow", "ask", "autodraft", "quoteCard", "mapSpeakers"]) expect(VIEW.permissions).toHaveProperty(key);
});

test("Copy all, in each track, is fixtures.copy exactly", () => {
  expect(copyAll(VIEW, "original")).toBe(COPY.all_original);
  expect(copyAll(VIEW, "english")).toBe(COPY.all_english);
  expect(copyAll(VIEW, "both")).toBe(COPY.all_both);
});

test("Copy selected and the per-turn copy are fixtures.copy exactly", () => {
  expect(copySelected(VIEW, ["turn_b"], "both")).toBe(COPY.selected_both_turn_b);
  expect(copySingle(turn("turn_a"), "original")).toBe(COPY.single_original_turn_a);
  expect(copySingle(turn("turn_a"), "english")).toBe(COPY.single_english_turn_a);
  expect(copySingle(turn("turn_a"), "both")).toBe(COPY.single_both_turn_a);
});

test("the copy rules hold beyond the fixtures", () => {
  const a = turn("turn_a");
  const already = { ...a, translation: { ...a.translation, status: "ALREADY_ENGLISH" as const, text: a.text } };
  expect(copySingle(already, "both")).toBe(a.text);
  const pending = { ...a, translation: { ...a.translation, status: "PENDING" as const, text: null } };
  expect(copySingle(pending, "english")).toBe(a.text);
  expect(copySingle(pending, "both")).toBe(a.text);
  const stale = { ...a, translation: { ...a.translation, status: "STALE" as const } };
  expect(copySingle(stale, "english")).toBe(a.translation.text);
  const multi = { ...a, translation: { ...a.translation, text: "one\ntwo" } };
  expect(copySingle(multi, "both")).toBe(`${a.text}\n> one\n> two`);
  const named = { ...VIEW, turns: [{ ...a, speakerName: "Na*nd`in|i" }] };
  expect(copySelected(named, [a.id], "original")).toBe(`**Nandini** · \`00:00:04\`\n${a.text}`);
  expect(copySelected(VIEW, ["turn_b", "turn_a"], "original")).toBe(copySelected(VIEW, ["turn_a", "turn_b"], "original"));
  expect(copySelected(VIEW, [], "both")).toBe("");
});

test("timestamps and lengths follow the fixtures", () => {
  for (const [ms, text] of CONTRACT.fixtures.timestamp.cases as [number, string][]) expect(clock(ms)).toBe(text);
  for (const [ms, text] of CONTRACT.fixtures.length.cases as [number, string][]) expect(lengthLabel(ms)).toBe(text);
});

test("each status carries the contract's label and one action", () => {
  for (const row of CONTRACT.enums.pipelineStatus as { value: Transcript["status"]; label: string; action: string | null }[]) {
    expect(statusLabel(row.value)).toBe(row.label);
    const everyone = { status: row.value, permissions: { ...VIEW.permissions, retry: true, transcribeNow: true } };
    const action = statusAction(everyone);
    expect(action === "retry" ? "RETRY" : action === "transcribeNow" ? "TRANSCRIBE_NOW" : null).toBe(row.action);
  }
  expect(statusAction({ status: "QUEUED", permissions: { ...VIEW.permissions, transcribeNow: false } })).toBeNull();
});

test("karaoke finds the turn and the word being spoken", () => {
  expect(locate(VIEW.turns, 0)).toBeNull();
  expect(locate(VIEW.turns, 4100)).toEqual({ turnIndex: 0, wordIndex: 0 });
  expect(locate(VIEW.turns, 5200)).toEqual({ turnIndex: 0, wordIndex: 3 });
  // Between two words: the turn stays lit, no word is.
  expect(locate(VIEW.turns, 4320)).toEqual({ turnIndex: 0, wordIndex: null });
  // The pause after a turn keeps it until the next speaker starts.
  expect(locate(VIEW.turns, 8000)).toEqual({ turnIndex: 0, wordIndex: null });
  expect(locate(VIEW.turns, 9000)).toEqual({ turnIndex: 1, wordIndex: null });
});

test("the text is cut into words and unsure stretches by code point", () => {
  const a = pieces(turn("turn_a"));
  expect(a.map((piece) => piece.text).join("")).toBe(turn("turn_a").text);
  expect(a.filter((piece) => piece.word !== null).map((piece) => piece.text)).toEqual(turn("turn_a").words.map((word) => word.text));
  const b = pieces(turn("turn_b"));
  expect(b.map((piece) => piece.text).join("")).toBe(turn("turn_b").text);
  expect(b.filter((piece) => piece.lowConfidence).map((piece) => piece.text)).toEqual(["dabu"]);
});

test("the sanitiser keeps only http(s) and mailto links", () => {
  expect(safeUrl("https://example.org/a")).toBe("https://example.org/a");
  expect(safeUrl("mailto:a@example.org")).toBe("mailto:a@example.org");
  for (const hostile of ["javascript:alert(1)", " JavaScript:alert(1)", "data:text/html,<script>x</script>", "vbscript:x", "/relative", "//evil.example"]) {
    expect(safeUrl(hostile), hostile).toBe("");
  }
});
