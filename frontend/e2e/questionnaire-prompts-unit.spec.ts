import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { prompts, type PromptSpec } from "@/lib/questionnairePrompts";

/**
 * DESIGN §4.5 copy, exactly, and the button-row rule for every prompt (test 48's rule half; the
 * pixel snapshots need a running app and are not part of the unit run). Every prompt draws through
 * `ButtonRow`, which lays one button centred, two on a line, more stacked or as pills.
 */

const ALL: [string, PromptSpec][] = [
  ["P1", prompts.p1({ researcher: "Nandini", artisans: "Giriraj and Meena", time: "10:12" })],
  ["P3", prompts.p3({ target: "Section D", elapsed: "12:40", code: "F3" })],
  ["P5", prompts.p5({ name: "Giriraj", date: "7 Jul", case: "OTHER" })],
  ["P7", prompts.p7({ position: 1, target: "Section D" })],
  ["P7r", prompts.p7r({ position: 2, target: "Section D" })],
  ["P8", prompts.p8({ name: "Giriraj", code: "D3", researcher: "Nandini", date: "7 Jul" })],
  ["P9", prompts.p9({ researcher: "Nandini", name: "Giriraj", code: "D3", time: "11:04" })],
  ["P10", prompts.p10({ name: "Ravi", count: 3 })],
  ["P11", prompts.p11({ count: 12 })],
  ["P12", prompts.p12()],
  ["P13", prompts.p13({ minutes: 12, target: "Section D" })],
  ["P14", prompts.p14()],
  ["LEAVE", prompts.leave({ count: 2 })],
  ["FINISH", prompts.finish({ sections: 6, artisans: 3, pending: 2 })]
];

test("exact copy (§4.5)", () => {
  const byId = Object.fromEntries(ALL);
  expect(byId.P7.title).toBe("Replace take 1 of Section D?");
  expect(byId.P7.body).toBe("Take 1 is removed once take 2 uploads. You can restore it.");
  expect(byId.P7r.title).toBe("Remove take 2 of Section D?");
  expect(byId.P7r.body).toBe("You can restore it from Removed.");
  expect(byId.P8.title).toBe("Replace Giriraj's answer to D3?");
  expect(byId.P8.body).toBe("The current answer came from Nandini's take of 7 Jul.");
  expect(byId.P10.title).toBe("Remove Ravi from this session?");
  expect(byId.P11.body).toBe("Move or remove its 12 recordings first.");
  expect(byId.P12.body).toBe("Locked once recording starts.");
  expect(byId.P14.body).toBe("Update when you have Wi-Fi. Your recordings are safe.");
  expect(byId.LEAVE.title).toBe("Leave this session?");
  expect(byId.LEAVE.body).toBe("2 takes haven't uploaded. They stay on this device and upload when you return.");
  expect(byId.FINISH.body).toBe("6 sections recorded for 3 artisans. 2 takes still uploading.");
});

test("button rows: 0 inline, 1 centred, 2 on one line; destructive last", () => {
  for (const [id, spec] of ALL) {
    expect(spec.buttons.length, id).toBeLessThanOrEqual(2);
    const danger = spec.buttons.findIndex((b) => b.tone === "danger");
    if (danger >= 0) expect(danger, id).toBe(spec.buttons.length - 1);
  }
  expect(ALL.filter(([, s]) => s.buttons.length === 1).map(([id]) => id)).toEqual(["P5", "P14", "FINISH"]);
  const src = readFileSync(join(__dirname, "..", "components", "questionnaire", "Prompts.tsx"), "utf8");
  expect(src).toContain("<ButtonRow");
  expect(src).not.toMatch(/className="flex[^"]*gap-[^"]*"\s*>\s*\{row/);
});
