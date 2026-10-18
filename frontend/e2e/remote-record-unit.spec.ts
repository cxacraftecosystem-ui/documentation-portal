import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { interviewFromSearchParams, openInLabel, remoteRecordError, remoteRecordRows, safeOpenUrl } from "@/lib/unifiedSearch";

/** The read-only card for the other portal's record, and the two deep links search opens. */

test("the card lists the shared fields and the date, and nothing empty", () => {
  const rows = remoteRecordRows(
    {
      portal: "designer",
      type: "designWorkshops",
      id: "dw9",
      title: "Bagru camp",
      fields: [
        { label: "Craft", value: "Bagru block print" },
        { label: "Place", value: "" }
      ],
      recordDate: "2026-09-02T00:00:00Z"
    },
    () => "2 Sept 2026"
  );
  expect(rows).toEqual([
    { label: "Craft", value: "Bagru block print" },
    { label: "Recorded", value: "2 Sept 2026" }
  ]);
});

test("the button names the other portal", () => {
  expect(openInLabel("fieldRepository")).toBe("Open in Field Repository");
  expect(openInLabel("designer")).toBe("Open in Designer portal");
});

test("a missing or refused record and an unreachable portal read differently", () => {
  expect(remoteRecordError(new ApiError(404, "Not Found", null))).toBe("This record isn't available.");
  expect(remoteRecordError(new ApiError(503, "down", null))).toContain("can't be reached");
  expect(remoteRecordError(new TypeError("Failed to fetch"))).toContain("can't be reached");
});

test("only a web address from the other portal becomes a link", () => {
  expect(safeOpenUrl("https://docs.example/products/fr9")).toBe("https://docs.example/products/fr9");
  expect(safeOpenUrl("javascript:alert(1)")).toBeNull();
  expect(safeOpenUrl("data:text/html,x")).toBeNull();
  expect(safeOpenUrl("/products/fr9")).toBeNull();
  expect(safeOpenUrl(null)).toBeNull();
});

test("the page offers nothing to edit or download", () => {
  const page = readFileSync(join(__dirname, "..", "app", "(protected)", "remote", "[type]", "[id]", "page.tsx"), "utf8");
  expect(page).not.toMatch(/download|Update|<form|method:/i);
  expect(page).toContain('target="_blank"');
});

test("an interview link is read from the address and the questionnaire page follows it", () => {
  expect(interviewFromSearchParams(new URLSearchParams("interview=%20i1%20"))).toBe("i1");
  expect(interviewFromSearchParams(new URLSearchParams(""))).toBe("");
  const page = readFileSync(join(__dirname, "..", "app", "(protected)", "questionnaire", "page.tsx"), "utf8");
  expect(page).toContain("interviewFromSearchParams(searchParams)");
  expect(page).toContain("router.replace(`/questionnaire/interviews/");
});

test("media and transcript results have a read-only page to open on", () => {
  const page = readFileSync(join(__dirname, "..", "app", "(protected)", "media", "[id]", "page.tsx"), "utf8");
  expect(page).toContain("RecordMediaList");
  expect(page).not.toMatch(/<form|method:|Update/);
});
