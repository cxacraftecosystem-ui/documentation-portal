import { expect, test } from "@playwright/test";

import {
  dayLabel as boardDay,
  hitBars as boardHitBars,
  prettyTitle as boardTitle,
  chunks,
  downsample,
  findHits,
  fractionOf,
  highlightRanges,
  hitLabel,
  hitMarkers,
  languageOptions,
  languagesLine,
  lineAt,
  matchesState,
  nextIndex,
  paletteEntries,
  parseStartParam,
  quoteLine,
  rowState,
  shortcutAction,
  shortClock,
  speakerLanes,
  speakersLine,
  stateCounts,
  stepHit,
  talkMinutes,
  talkTime,
  timeLink,
  type KeyLike,
  type TimedLine
} from "@/lib/dataExplorer/preview";

const LINES: TimedLine[] = [
  { startMs: 0, endMs: 9_000, speakerKey: "speaker_0", speakerName: "Interviewer", text: "What goes into the mud?", english: null },
  { startMs: 9_000, endMs: 40_000, speakerKey: "speaker_1", speakerName: "Sunita", text: "Clay, lime and gum. Lime stops it cracking.", english: "Clay, lime and gum." },
  { startMs: 41_000, endMs: 50_000, speakerKey: "speaker_0", speakerName: "Interviewer", text: "Where do you buy the lime?", english: null },
  { startMs: 60_000, endMs: 120_000, speakerKey: "speaker_1", speakerName: "Sunita", text: "From the market in Sanganer.", english: null }
];

function key(key: string, mods: Partial<KeyLike> = {}): KeyLike {
  return { key, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...mods };
}

test.describe("preview clock and deep links", () => {
  test("clocks read mm:ss under an hour and h:mm:ss above", () => {
    expect(shortClock(0)).toBe("00:00");
    expect(shortClock(724_000)).toBe("12:04");
    expect(shortClock(3_723_000)).toBe("1:02:03");
    expect(shortClock(null)).toBe("00:00");
    expect(shortClock(-5)).toBe("00:00");
  });

  test("?t= takes seconds, mm:ss and h:mm:ss, and refuses nonsense", () => {
    expect(parseStartParam("754")).toBe(754_000);
    expect(parseStartParam("754s")).toBe(754_000);
    expect(parseStartParam("12:04")).toBe(724_000);
    expect(parseStartParam("1:02:03")).toBe(3_723_000);
    expect(parseStartParam("12:75")).toBeNull();
    expect(parseStartParam("soon")).toBeNull();
    expect(parseStartParam("")).toBeNull();
    expect(parseStartParam(null)).toBeNull();
  });

  test("a time link keeps the page and sets t in whole seconds", () => {
    expect(timeLink("https://portal.example/data?path=a%2Fb&t=3", 724_900)).toBe("https://portal.example/data?path=a%2Fb&t=724");
    expect(quoteLine(LINES[1], "https://x/data?t=9")).toBe("Sunita: “Clay, lime and gum. Lime stops it cracking.” (00:09) https://x/data?t=9");
  });
});

test.describe("transcript following and find", () => {
  test("the current line is the last one started", () => {
    expect(lineAt(LINES, -1)).toBe(-1);
    expect(lineAt(LINES, 0)).toBe(0);
    expect(lineAt(LINES, 40_500)).toBe(1);
    expect(lineAt(LINES, 59_999)).toBe(2);
    expect(lineAt(LINES, 10_000_000)).toBe(3);
    expect(lineAt([], 5)).toBe(-1);
  });

  test("find counts every occurrence, case and accent blind, per track", () => {
    const original = findHits(LINES, "LIME", "original");
    expect(original.map((hit) => hit.line)).toEqual([1, 1, 2]);
    expect(findHits(LINES, "lime", "english").map((hit) => hit.line)).toEqual([1]);
    expect(findHits(LINES, "lime", "both")).toHaveLength(4);
    expect(findHits(LINES, "   ", "original")).toEqual([]);
    expect(findHits([{ ...LINES[0], text: "Café nearby" }], "cafe", "original")).toHaveLength(1);
  });

  test("N of M wraps both ways", () => {
    expect(hitLabel([], 0)).toBe("No matches");
    const hits = findHits(LINES, "lime", "original");
    expect(hitLabel(hits, 0)).toBe("1 of 3");
    expect(hitLabel(hits, 2)).toBe("3 of 3");
    expect(stepHit(3, 2, 1)).toBe(0);
    expect(stepHit(3, 0, -1)).toBe(2);
    expect(stepHit(0, 0, 1)).toBe(0);
  });

  test("highlighting splits the text around each match", () => {
    expect(highlightRanges("Lime and lime", "lime")).toEqual([
      { text: "Lime", hit: true },
      { text: " and ", hit: false },
      { text: "lime", hit: true }
    ]);
    expect(highlightRanges("nothing", "")).toEqual([{ text: "nothing", hit: false }]);
    expect(highlightRanges("", "x")).toEqual([]);
  });

  test("hit markers sit at each matching line once, as a share of the length", () => {
    const hits = findHits(LINES, "lime", "original");
    expect(hitMarkers(LINES, hits, 120_000)).toEqual([9_000 / 120_000, 41_000 / 120_000]);
    expect(hitMarkers(LINES, hits, null)).toEqual([9_000 / 120_000, 41_000 / 120_000]);
    expect(hitMarkers([], [], null)).toEqual([]);
  });
});

test.describe("speakers", () => {
  test("talk time adds up each speaker's lines", () => {
    const times = talkTime(LINES);
    expect(times).toEqual([
      { key: "speaker_0", name: "Interviewer", ms: 18_000 },
      { key: "speaker_1", name: "Sunita", ms: 91_000 }
    ]);
    expect(talkMinutes(0)).toBe("0 min");
    expect(talkMinutes(20_000)).toBe("<1 min");
    expect(talkMinutes(18 * 60_000 + 10_000)).toBe("18 min");
  });

  test("lanes join a speaker's touching lines and stay inside the bar", () => {
    const lanes = speakerLanes(LINES, 120_000);
    const sunita = lanes.get("speaker_1")!;
    expect(sunita).toHaveLength(2);
    for (const spans of lanes.values()) for (const span of spans) {
      expect(span.left).toBeGreaterThanOrEqual(0);
      expect(span.left + span.width).toBeLessThanOrEqual(1.000001);
    }
    expect(speakerLanes([], null).size).toBe(0);
  });
});

test.describe("waveform", () => {
  test("peaks fold to the loudest of each bucket and clamp to 0..1", () => {
    expect(downsample([0.1, 0.9, 0.2, 0.4], 2)).toEqual([0.9, 0.4]);
    expect(downsample([2, -1, Number.NaN], 10)).toEqual([1, 0, 0]);
    expect(downsample([], 10)).toEqual([]);
    expect(downsample(Array.from({ length: 800 }, (_, i) => (i % 10) / 10), 160)).toHaveLength(160);
  });

  test("a click maps to a share of the bar", () => {
    expect(fractionOf(150, { left: 100, width: 200 })).toBe(0.25);
    expect(fractionOf(50, { left: 100, width: 200 })).toBe(0);
    expect(fractionOf(500, { left: 100, width: 200 })).toBe(1);
    expect(fractionOf(5, { left: 0, width: 0 })).toBe(0);
  });

  test("previous and next stop at the ends", () => {
    expect(nextIndex(3, 0, -1)).toBeNull();
    expect(nextIndex(3, 2, 1)).toBeNull();
    expect(nextIndex(3, 1, 1)).toBe(2);
  });
});

test.describe("transcripts tab", () => {
  test("statuses fold into the four pills", () => {
    expect(rowState("READY", false)).toBe("ready");
    expect(rowState("TRANSLATING", false)).toBe("working");
    expect(rowState("TRANSCRIBING", true)).toBe("working");
    expect(rowState("NEEDS_ATTENTION", true)).toBe("failed");
    expect(rowState("QUEUED", false)).toBe("waiting");
    expect(rowState(null, true)).toBe("ready");
    expect(rowState(null, false)).toBe("none");
    expect(matchesState("failed", "all")).toBe(true);
    expect(matchesState("ready", "failed")).toBe(false);
    const counts = stateCounts([{ state: "ready" }, { state: "ready" }, { state: "working" }, { state: "failed" }, { state: "none" }]);
    expect(counts).toEqual({ all: 5, ready: 2, working: 1, failed: 1 });
  });

  test("languages and speakers read as one short line", () => {
    expect(languagesLine([{ label: "Hindi" }, { label: "English" }])).toBe("Hindi → English");
    expect(languagesLine([{ label: "English" }])).toBe("English");
    expect(languagesLine([])).toBeNull();
    expect(speakersLine(1)).toBe("1 speaker");
    expect(speakersLine(4)).toBe("4 speakers");
    expect(speakersLine(0)).toBeNull();
    const options = languageOptions([
      { summary: { languages: [{ code: "hi", label: "Hindi" }] } },
      { summary: { languages: [{ code: "hi", label: "Hindi" }, { code: "mwr", label: "Marwari" }] } },
      { summary: null }
    ]);
    expect(options.map((option) => option.value)).toEqual(["", "hi", "mwr"]);
  });

  test("batch ids go fifty at a time", () => {
    const ids = Array.from({ length: 120 }, (_, i) => `m${i}`);
    expect(chunks(ids, 50).map((part) => part.length)).toEqual([50, 50, 20]);
    expect(chunks([], 50)).toEqual([]);
  });
});

test.describe("keyboard shortcuts", () => {
  test("each key maps to its action and nothing fires while typing", () => {
    expect(shortcutAction(key("/"), false, false)).toBe("search");
    expect(shortcutAction(key("j"), false, false)).toBe("next");
    expect(shortcutAction(key("K"), false, false)).toBe("prev");
    expect(shortcutAction(key("Enter"), false, false)).toBe("open");
    expect(shortcutAction(key(" "), false, false)).toBe("open");
    expect(shortcutAction(key("x"), false, false)).toBe("toggle");
    expect(shortcutAction(key("a", { ctrlKey: true }), false, false)).toBe("selectAll");
    expect(shortcutAction(key("Backspace"), false, false)).toBe("up");
    expect(shortcutAction(key("1"), false, false)).toBe("files");
    expect(shortcutAction(key("2"), false, false)).toBe("tables");
    expect(shortcutAction(key("3"), false, false)).toBe("transcripts");
    expect(shortcutAction(key("d"), false, false)).toBe("download");
    expect(shortcutAction(key("?", { shiftKey: true }), false, false)).toBe("help");
    expect(shortcutAction(key("g"), false, false)).toBe("chord");
    expect(shortcutAction(key("d"), false, true)).toBe("goData");
    expect(shortcutAction(key("k", { metaKey: true }), true, false)).toBe("palette");
    expect(shortcutAction(key("k", { ctrlKey: true }), false, false)).toBe("palette");
    for (const typed of ["/", "j", "x", "1", "d", "Backspace", "Enter", "?"]) expect(shortcutAction(key(typed), true, false)).toBeNull();
    expect(shortcutAction(key("a", { ctrlKey: true }), true, false)).toBeNull();
    expect(shortcutAction(key("j", { altKey: true }), false, false)).toBeNull();
  });

  test("the palette lists records, then actions on the top one, then search everything", () => {
    const hits = [
      { id: "1", title: "Giriraj", type: "artisan" },
      { id: "2", title: "Giridhar", type: "artisan" }
    ];
    const actions = [{ key: "d", label: "Download", run: () => undefined }];
    expect(paletteEntries(hits, actions, "giri").map((entry) => entry.kind)).toEqual(["hit", "hit", "action", "everything"]);
    expect(paletteEntries([], actions, "giri").map((entry) => entry.kind)).toEqual(["everything"]);
    expect(paletteEntries([], actions, "")).toEqual([]);
  });
});

test("a translating transcript says how far it has got", async () => {
  const { translatingLabel } = await import("@/lib/dataExplorer/preview");
  expect(translatingLabel({ status: "TRANSLATING", translation: { done: 7, total: 10, percent: 70 } })).toBe("Translating 70%");
  expect(translatingLabel({ status: "TRANSLATING", translation: null })).toBe("Translating");
  expect(translatingLabel({ status: "TRANSCRIBING" })).toBe("Transcribing");
});

test("a recording's title reads like the boards: underscores become dots, hyphens spaces, no extension", () => {
  expect(boardTitle("Section-D_Materials_Group-of-3.m4a")).toBe("Section D · Materials · Group of 3");
  expect(boardTitle("interview.m4a")).toBe("interview");
  expect(boardTitle("Kiln firing wide.mov")).toBe("Kiln firing wide");
});

test("a capture date reads 22 Sep 2026 in India time", () => {
  expect(boardDay("2026-09-22T10:00:00Z")).toBe("22 Sep 2026");
  expect(boardDay("2026-09-22T20:00:00Z")).toBe("23 Sep 2026");
  expect(boardDay(null)).toBeNull();
  expect(boardDay("nonsense")).toBeNull();
});

test("a search hit colours its waveform bar and the next one", () => {
  expect([...boardHitBars([0.5], 10)].sort()).toEqual([5, 6]);
  expect([...boardHitBars([1], 10)]).toEqual([9]);
  expect(boardHitBars([0.3], 0).size).toBe(0);
});
