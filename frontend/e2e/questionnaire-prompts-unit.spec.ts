import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { alreadyRecorded, DISMISSED, prompts, type PromptSpec } from "@/lib/questionnairePrompts";

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

test("every prompt names a safe dismissal; P13 keeps the take and Finish does nothing (audit H16)", () => {
  // Escape, the backdrop and a lost focus resolve to `spec.dismiss`. It used to be "the first
  // secondary button", which made a dismissed P13 DISCARD the recovered take and a dismissed Finish
  // finish the session.
  const byId = Object.fromEntries(ALL);
  expect(byId.P13.dismiss).toBe("keep");
  expect(byId.FINISH.dismiss).toBeNull();
  expect(byId.P1.dismiss, "neither starts nor joins").toBeNull();
  expect(byId.P9.dismiss, "both answers wait").toBeNull();
  expect(byId.P3.dismiss).toBe("keep");
  expect(byId.P8.dismiss).toBe("keep");
  expect(byId.LEAVE.dismiss).toBe("stay");
  for (const [id, spec] of ALL) {
    expect(spec, `${id} declares its dismissal`).toHaveProperty("dismiss");
    if (spec.dismiss === null) continue;
    const button = spec.buttons.find((b) => b.id === spec.dismiss);
    expect(button, `${id} dismisses to one of its own buttons`).toBeTruthy();
    expect(button?.tone, `${id} never dismisses to a destructive button`).not.toBe("danger");
    expect(["discard", "remove", "finish", "replace", "leave", "stop", "mine", "theirs", "join", "new"], `${id} dismisses to a choice that loses nothing`).not.toContain(spec.dismiss);
  }
  const p2 = alreadyRecorded({
    check: { asOf: "", identical: null, targets: [], suggestedReason: null },
    names: {},
    targetLabel: "Section D",
    code: "D",
    part2Allowed: false,
    replaceAllowed: false
  }).spec;
  expect(p2.dismiss).toBe("cancel");
  const dialog = readFileSync(join(__dirname, "..", "components", "questionnaire", "Prompts.tsx"), "utf8");
  expect(dialog).toContain("const safe = spec.dismiss ?? DISMISSED;");
  expect(DISMISSED).toBe("dismissed");
  // And the workspace acts on P13's Discard only when Discard was pressed.
  const workspace = readFileSync(join(__dirname, "..", "components", "questionnaire", "SessionWorkspace.tsx"), "utf8");
  expect(workspace).toContain('} else if (choice === "discard") {');
});
