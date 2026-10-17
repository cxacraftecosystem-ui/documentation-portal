import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { lookupKind, lookupStart } from "@/components/forms/artisanLookupRules";
import { draftFingerprint } from "@/components/forms/FormDraft";
import { exifTimeToMs, inCaptureOrder, templateStepsFrom, unnamedStepCount } from "@/components/forms/processSteps";
import { prefillStillStands, statedFromSource, statedPrefillFor, type StatedAddress } from "@/components/forms/statedPrefill";
import { failedFilesOf } from "@/components/media/failedUploads";
import { draftOfferSentence, latestNewDraft, newDraftPrefix, stripIdentity, type FormDraftRecord } from "@/lib/formDrafts";
import { failedOriginals, initialMediaTarget } from "@/lib/mediaTarget";
import { ARTISAN_FIELD_OPTIONS, artisanPrefill, automaticInterviewTitle, guessedArtisanField } from "@/lib/questionArtisanFields";
import { applyTranscripts, transcriptAnswerText, transcriptOffer } from "@/lib/transcriptAnswer";
import type { Artisan } from "@/lib/types";

/**
 * WEB CAPTURE (2026-10-17) — the pure halves of A7, A9, A11, B5–B8, C17, C18 and C22, plus the
 * source facts each screen owes. No browser: every rule a screen applies lives in a module this file
 * can call, and the screens are read as text for the wiring.
 */

const ROOT = join(__dirname, "..", "..");
const read = (...parts: string[]) => readFileSync(join(ROOT, ...parts), "utf-8");
const FRONTEND = (...parts: string[]) => read("frontend", ...parts);

const file = (name: string, lastModified = 0) => new File(["x"], name, { type: "image/jpeg", lastModified });

// ── A7 ─────────────────────────────────────────────────────────────────────────────────────────

test("a transcript becomes answer text by the shared vectors the server also runs", () => {
  const table = JSON.parse(read("shared", "transcript-answer-vectors.json")) as { vectors: { transcript: string; answer: string }[] };
  expect(table.vectors.length).toBeGreaterThanOrEqual(5);
  for (const row of table.vectors) expect(transcriptAnswerText(row.transcript), JSON.stringify(row)).toBe(row.answer);
});

test("use-as-answer fills only an empty answer and never a typed one", () => {
  expect(transcriptOffer("", "**S:** clay")).toBe("S: clay");
  expect(transcriptOffer("  ", "clay")).toBe("clay");
  expect(transcriptOffer("typed", "clay")).toBeNull();
  expect(transcriptOffer("", "   ")).toBeNull();
  const { answers, filled } = applyTranscripts({ a: "typed", b: "" }, { a: "from clip", b: "from clip", c: "new" });
  expect(answers).toEqual({ a: "typed", b: "from clip", c: "new" });
  expect(filled.sort()).toEqual(["b", "c"]);
});

test("the interview page marks, sends and un-marks a transcript answer", () => {
  const page = FRONTEND("app", "(protected)", "questionnaire", "page.tsx");
  expect(page).toContain("Use as answer");
  expect(page).toContain("From transcript");
  expect(page).toContain("answerSource: TRANSCRIPT_SOURCE");
  expect(page).toMatch(/out\.delete\(question\.id\)/);
  const view = FRONTEND("components", "records", "RecordView.tsx");
  expect(view).toContain("/apply-transcripts");
  expect(view).toContain("Use transcripts as answers");
});

// ── B8 ─────────────────────────────────────────────────────────────────────────────────────────

test("the question builder offers exactly the server's artisan fields", () => {
  const schema = read("backend", "app", "schemas", "questionnaire.py");
  const block = /ARTISAN_FIELD_KEYS: tuple\[str, \.\.\.\] = \(([\s\S]*?)\)/.exec(schema)?.[1] ?? "";
  const keys = Array.from(block.matchAll(/"([A-Za-z]+)"/g)).map((m) => m[1]);
  expect(keys.length).toBeGreaterThan(8);
  expect(ARTISAN_FIELD_OPTIONS.map((option) => option.value)).toEqual(keys);
});

const RAMESH = {
  id: "a-1",
  name: "Ramesh Chhipa",
  place: "Bagru",
  gender: "Male",
  phone: null,
  email: "r@example.org",
  craft: { id: "c-1", name: "Block printing" }
} as unknown as Artisan;

test("a mapped question fills from its field on any section; an unmapped respondent one is guessed", () => {
  const out = artisanPrefill(
    [
      { id: "q1", prompt: "Where is the workshop?", sectionCode: "B", artisanField: "place" },
      { id: "q2", prompt: "Name of respondent", sectionCode: "RESP" },
      { id: "q3", prompt: "Contact number", sectionCode: "RESP" },
      { id: "q4", prompt: "Name of the river", sectionCode: "B" },
      { id: "q5", prompt: "Who interviewed?", sectionCode: "RESP", artisanField: "interviewer" }
    ],
    RAMESH,
    { name: "R. Menon" }
  );
  expect(out).toEqual({ q1: "Bagru", q2: "Ramesh Chhipa", q3: "r@example.org", q5: "R. Menon" });
  expect(guessedArtisanField("Village or district")).toBe("place");
});

test("the automatic interview title is the server's, word for word", () => {
  expect(automaticInterviewTitle("Ramesh", 1, "Craft questionnaire", new Date(2026, 9, 17))).toBe("Ramesh · Craft questionnaire · 17 Oct");
  expect(automaticInterviewTitle("Ramesh", 3, null, new Date(2026, 8, 5))).toBe("Ramesh and others · 5 Sep");
  const page = FRONTEND("app", "(protected)", "questionnaire", "page.tsx");
  expect(page, "the title box is no longer required").not.toMatch(/name="title"\s+label="Interview title"\s+required/);
  expect(page).toContain("placeholder={automaticTitle}");
});

// ── C17 ────────────────────────────────────────────────────────────────────────────────────────

const EMPTY: StatedAddress = { state: "", district: "", village: "", pincode: "" };
const BAGRU = { key: "a-1", label: "Ramesh", state: "Rajasthan", district: "Jaipur", village: "Bagru", pincode: "303007" };

test("the artisan's address fills an empty card, and a typed field blocks it", () => {
  const filled = statedPrefillFor(BAGRU, EMPTY, null);
  expect(filled).toEqual({ state: "Rajasthan", district: "Jaipur", village: "Bagru", pincode: "303007" });
  expect(statedPrefillFor(BAGRU, { ...EMPTY, village: "Sanganer" }, null)).toBeNull();
  // A different artisan replaces what the last prefill wrote…
  const other = { key: "a-2", label: "Sita", state: "Gujarat", district: "Kachchh", village: "Bhuj" };
  expect(statedPrefillFor(other, filled!, filled)?.state).toBe("Gujarat");
  // …and beats this device's passive GPS guess, but never a person's choice.
  expect(statedPrefillFor(BAGRU, { ...EMPTY, state: "West Bengal" }, null, ["state"])?.state).toBe("Rajasthan");
  expect(statedPrefillFor(BAGRU, { ...EMPTY, state: "West Bengal" }, null, [])).toBeNull();
  expect(prefillStillStands(filled!, filled)).toBe(true);
  expect(prefillStillStands({ ...filled!, village: "x" }, filled)).toBe(false);
  expect(statedFromSource({ key: "k", label: "l", pincode: "30 30 07x" }).pincode).toBe("303007");
});

test("product, tool and interview forms hand the artisan to the location card", () => {
  expect(FRONTEND("components", "forms", "ProductForm.tsx")).toContain("prefillFrom={");
  expect(FRONTEND("app", "(protected)", "questionnaire", "page.tsx")).toContain("prefillFrom={");
  expect(FRONTEND("components", "forms", "ToolForm.tsx")).toContain("prefillFrom={");
  expect(FRONTEND("components", "forms", "LocationFields.tsx")).toContain("&rsquo;s record.");
});

// ── B5 / A8 / B7 ───────────────────────────────────────────────────────────────────────────────

test("the product form picks the artisan first and offers the next step", () => {
  const form = FRONTEND("components", "forms", "ProductForm.tsx");
  expect(form).toContain('label="Artisan not on record"');
  expect(form).toContain("Save and add another");
  expect(form).toContain("<CarryForwardCards");
  expect(form, "the dead stop is gone").not.toContain("Select a linked craft first");
  expect(form).not.toContain("Reopen to retry");
  expect(FRONTEND("components", "forms", "ToolForm.tsx")).toContain("Add another tool");
  expect(FRONTEND("app", "(protected)", "crafts", "page.tsx")).toContain("Add an artisan");
});

test("a retry sends exactly the files that failed, duplicates included", () => {
  const a1 = file("a.jpg", 1);
  const a2 = file("a.jpg", 2);
  const b = file("b.jpg", 3);
  expect(failedFilesOf([a1, a2, b], [{ name: "a.jpg" }])).toEqual([a1]);
  expect(failedFilesOf([a1, a2, b], [{ name: "a.jpg" }, { name: "a.jpg" }, { name: "zzz" }])).toEqual([a1, a2]);
});

// ── A9 / A11 ───────────────────────────────────────────────────────────────────────────────────

test("the media page opens on the linked record, then the carried one", () => {
  expect(initialMediaTarget(new URLSearchParams("linkedType=product&linkedId=p-1"), null)).toEqual({ type: "product", id: "p-1", label: null });
  expect(initialMediaTarget(new URLSearchParams("linkedType=bogus"), { artisanId: "a-1", artisanName: "R" })).toEqual({ type: "artisan", id: "a-1", label: "R" });
  expect(initialMediaTarget(null, { artisanId: "a-1", productId: "p-9", productName: "Stole" })?.type).toBe("product");
  expect(initialMediaTarget(null, null)).toBeNull();
});

test("only the failed originals stay selected after a partial batch", () => {
  const originals = [file("1.jpg"), file("2.jpg"), file("3.jpg")];
  const renamed = [file("PRODUCT_A_IMG_1.jpg"), file("PRODUCT_A_IMG_2.jpg"), file("PRODUCT_A_IMG_3.jpg")];
  expect(failedOriginals(originals, renamed, ["PRODUCT_A_IMG_2.jpg"])).toEqual([originals[1]]);
});

test("the media page keeps its target, queues offline and searches the server", () => {
  const page = FRONTEND("app", "(protected)", "media", "page.tsx");
  expect(page).toContain("queueMediaOnly(");
  expect(page).toContain("onSearch={entryList.setQuery}");
  expect(page, "the target is not reset after a batch").not.toMatch(/setLinkedType\(""\)/);
  const offline = FRONTEND("lib", "offline.ts");
  expect(offline).toContain("progress.mediaOnly");
  expect(FRONTEND("components", "forms", "ProcessForm.tsx")).toContain("onSearch={artisanList.setQuery}");
});

// ── C18 ────────────────────────────────────────────────────────────────────────────────────────

test("the lookup tells an Aadhaar from a name and carries it into the form", () => {
  expect(lookupKind("2341 2341 2346")).toBe("aadhaar");
  expect(lookupKind("234123412346")).toBe("aadhaar");
  expect(lookupKind("Ramesh")).toBe("name");
  expect(lookupKind("12345")).toBe("name");
  expect(lookupKind("  ")).toBe("empty");
  expect(lookupStart("2341 2341 2346")).toEqual({ name: "", aadhaar: "234123412346" });
  expect(lookupStart(" Ramesh ")).toEqual({ name: "Ramesh", aadhaar: "" });
});

test("the artisan form finds first, starts Pehchan unanswered and offers Not shared", () => {
  const form = FRONTEND("components", "forms", "ArtisanForm.tsx");
  expect(form).toContain("<ArtisanLookup");
  expect(form).toContain('label="Not shared by artisan"');
  expect(form).toContain("initialAvailable={initial ? (initial.pehchanCardAvailable ?? true) : null}");
  expect(form).toContain('<option value="">Select…</option>');
});

// ── C22 ────────────────────────────────────────────────────────────────────────────────────────

test("steps from photos follow capture time, then modified time, then pick order", () => {
  expect(exifTimeToMs("2026:10:17 09:30:12")).toBe(new Date(2026, 9, 17, 9, 30, 12).getTime());
  expect(exifTimeToMs("nonsense")).toBeNull();
  const a = file("a.jpg", 300);
  const b = file("b.jpg", 100);
  const c = file("c.jpg", 200);
  expect(inCaptureOrder([a, b, c], [null, null, null])).toEqual([b, c, a]);
  expect(inCaptureOrder([a, b, c], [50, null, 10])).toEqual([c, a, b]);
});

test("a template keeps names and kinds in order and drops blanks; unnamed steps are counted", () => {
  expect(
    templateStepsFrom([
      { name: "Dye", stepType: "SEQUENTIAL", sortOrder: 2 },
      { name: "Wash", stepType: "GROUP", sortOrder: 1 },
      { name: " ", stepType: "SEQUENTIAL", sortOrder: 3 }
    ])
  ).toEqual([
    { name: "Wash", stepType: "GROUP" },
    { name: "Dye", stepType: "SEQUENTIAL" }
  ]);
  expect(unnamedStepCount([{ name: "" }, { name: "x" }, { name: "  " }])).toBe(2);
  const form = FRONTEND("components", "forms", "ProcessForm.tsx");
  expect(form).toContain("Steps from photos");
  expect(form).toContain("Start from last process");
  expect(form).toContain("Unnamed step");
});

// ── B6 ─────────────────────────────────────────────────────────────────────────────────────────

test("draft keys are the handset's, and identity never enters a draft", () => {
  const kotlin = read("android", "app", "src", "main", "java", "com", "fieldrepository", "app", "data", "DraftStore.kt");
  expect(kotlin).toContain('"new-" + (parentId?.let { "p_" + it.replace(Regex("[^A-Za-z0-9_-]"), "_") } ?: "none") + "-"');
  expect(newDraftPrefix(null)).toBe("new-none-");
  expect(newDraftPrefix("a b/1")).toBe("new-p_a_b_1-");
  expect(stripIdentity({ name: "R", aadhaarNumber: "1", pehchanCardNumber: "2", form: { a: 1 } })).toEqual({ name: "R", form: { a: 1 } });
});

test("the offer reads as the handset's and the newest draft for the parent wins", () => {
  const at = new Date(2026, 9, 17, 9, 5).toISOString();
  expect(draftOfferSentence({ label: "Dabu stole", savedAt: at, parentLabel: "Ramesh" })).toBe(
    "Unsaved draft for Ramesh (“Dabu stole”), last changed 17 Oct, 09:05."
  );
  expect(draftOfferSentence({ label: "", savedAt: "x", parentLabel: null })).toBe("Unsaved draft.");
  const base: Omit<FormDraftRecord, "recordKey" | "savedAt" | "key"> = {
    account: "u",
    formKind: "product",
    label: "",
    fields: {},
    parentId: null,
    parentLabel: null
  };
  const drafts = [
    { ...base, key: "1", recordKey: "new-p_a1-x", savedAt: "2026-10-17T01:00:00Z" },
    { ...base, key: "2", recordKey: "new-p_a1-y", savedAt: "2026-10-17T02:00:00Z" },
    { ...base, key: "3", recordKey: "new-none-z", savedAt: "2026-10-17T03:00:00Z" },
    { ...base, key: "4", recordKey: "new-p_a1-w", savedAt: "2026-10-17T04:00:00Z", account: "other" }
  ];
  expect(latestNewDraft(drafts, "u", "product", "a1")?.key).toBe("2");
  expect(latestNewDraft(drafts, "u", "product", null)?.key).toBe("3");
  expect(latestNewDraft(drafts, "u", "tool", "a1")).toBeNull();
});

test("a draft notices a changed file but not a re-render", () => {
  const f = file("a.jpg", 1);
  expect(draftFingerprint({ files: [f], n: "x" })).toBe(draftFingerprint({ files: [f], n: "x" }));
  expect(draftFingerprint({ files: [f] })).not.toBe(draftFingerprint({ files: [file("b.jpg", 1)] }));
  for (const [name, path] of [
    ["product", ["components", "forms", "ProductForm.tsx"]],
    ["process", ["components", "forms", "ProcessForm.tsx"]],
    ["tool", ["components", "forms", "ToolForm.tsx"]],
    ["artisan", ["components", "forms", "ArtisanForm.tsx"]],
    ["interview", ["app", "(protected)", "questionnaire", "page.tsx"]]
  ] as const) {
    const source = FRONTEND(...path);
    expect(source, `${name} keeps a draft`).toMatch(/useFormDraft\(/);
    expect(source, `${name} offers it back`).toContain("<DraftOfferCard");
    expect(source, `${name} clears it on save`).toMatch(/[dD]raft\.clear\(\)/);
  }
});

// ── Review fixes (2026-10-17) ──────────────────────────────────────────────────────────────────

test("an artisan draft never reads an identity box from the form", () => {
  const form = FRONTEND("components", "forms", "ArtisanForm.tsx");
  const list = form.slice(form.indexOf("const ARTISAN_DRAFT_FORM_FIELDS"), form.indexOf("];", form.indexOf("const ARTISAN_DRAFT_FORM_FIELDS")));
  expect(list).toContain('"dos"');
  expect(list).not.toMatch(/aadhaar|pehchan/i);
  expect(stripIdentity({ aadhaarNumber: "x", "form.pehchanCardNumber": "y", name: "R" })).toEqual({ name: "R" });
});

test("switching off Not shared on a flagged artisan asks for the Aadhaar again", () => {
  const form = FRONTEND("components", "forms", "ArtisanForm.tsx");
  expect(form).toContain("Boolean(initial.aadhaarNotShared)) && !aadhaarNotShared");
});

test("save-and-add-another rides on the submitting button, not a ref", () => {
  const form = FRONTEND("components", "forms", "ProductForm.tsx");
  expect(form).not.toContain("addAnotherRef");
  expect(form).toContain('data-intent="add-another"');
  expect(form).toContain('submitter?.dataset.intent === "add-another"');
});

test("an edit leaves out a blank product name, and a linked artisan missing one asks for it", () => {
  const form = FRONTEND("components", "forms", "ProductForm.tsx");
  expect(form).toContain("Object.entries(names).filter(([, value]) => value)");
  expect(form).toContain("!pickedArtisan.craftId");
  expect(form).toContain("!pickedArtisan.place?.trim()");
});

test("source marks share one badge and the small actions are the secondary button", () => {
  for (const path of [
    ["app", "(protected)", "questionnaire", "page.tsx"],
    ["components", "forms", "ProcessForm.tsx"],
    ["components", "records", "RecordView.tsx"]
  ] as const) {
    const source = FRONTEND(...path);
    expect(source, path.join("/")).toContain("<Badge");
    expect(source, path.join("/")).not.toContain("rounded-full border border-purple-300 bg-purple-50 px-2 py-0.5");
    expect(source, path.join("/")).not.toContain("rounded-full border border-amber-500 bg-amber-100 px-2 py-0.5");
  }
  expect(FRONTEND("app", "(protected)", "questionnaire", "page.tsx")).not.toContain("border-purple-300 bg-white px-3 py-1.5");
  const location = FRONTEND("components", "forms", "LocationFields.tsx");
  const clear = location.slice(location.lastIndexOf("<button", location.indexOf("onClick={clearStatedPrefill}")), location.indexOf("onClick={clearStatedPrefill}"));
  expect(clear).toContain("field-button-secondary");
});
