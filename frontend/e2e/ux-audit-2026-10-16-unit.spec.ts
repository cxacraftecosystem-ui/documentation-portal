import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { cappedListNotice, type ListCut } from "@/components/data/cappedList";
import { workshopRunsToday } from "@/components/forms/WorkshopSelect";
import { statusLabel } from "@/components/StatusBadge";
import { RequiredMark } from "@/components/ui/RequiredMark";

/**
 * THE 2026-10-16 UI/UX AUDIT (`docs/UX_AUDIT_2026-10-16.md`), the web half, held in place.
 *
 * Each block names its finding. Where a function can be called it is called; where the fix is a
 * class string or a piece of markup it is read out of source, because there is no React renderer in
 * this repository's devDependencies (see `record-parity-fields-unit.spec.ts`). What none of this
 * proves is the measured contrast in a browser — the forced-dark axe pass the audit proposed needs
 * a running app and is not part of the unit run.
 */

const read = (...parts: string[]) =>
  readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

test.describe("DC-01 · an optional field draws no asterisk", () => {
  test("RequiredMark renders nothing unless told `true`", () => {
    expect(RequiredMark({})).toBeNull();
    expect(RequiredMark({ when: undefined })).toBeNull();
    expect(RequiredMark({ when: false })).toBeNull();
    expect(RequiredMark({ when: true })).not.toBeNull();
  });

  test("the default parameter that turned `undefined` into `true` is gone", () => {
    expect(read("components", "ui", "RequiredMark.tsx")).not.toContain("RequiredMark({ when = true");
  });
});

test.describe("DC-02 / DC-07 / DC-12 · shared recipes", () => {
  const css = read("app", "globals.css");
  const recipe = (name: string) => {
    const match = css.match(new RegExp(`\\.${name} \\{\\n\\s*@apply ([^;]+);`));
    expect(match, `.${name} recipe`).not.toBeNull();
    return match![1];
  };

  for (const name of ["field-button", "field-button-secondary", "field-danger", "file-trigger"]) {
    test(`.${name} is a 44px target on a coarse pointer`, () => {
      expect(recipe(name)).toContain("pointer-coarse:min-h-11");
    });
  }

  test("the literal-colour recipes carry a dark ink", () => {
    expect(recipe("file-trigger")).toContain("dark:text-purple-300");
    expect(recipe("field-danger")).toContain("dark:text-red-300");
    expect(recipe("field-danger")).toContain("text-red-800");
  });

  test("every row-action tone that paints a literal ink has a dark pair, and the row is a phone target", () => {
    const source = read("components", "RowActions.tsx");
    expect(source).toContain("pointer-coarse:min-h-11");
    for (const tone of ["edit", "danger", "positive"]) {
      const line = source.split("\n").find((row) => row.trim().startsWith(`${tone}:`));
      expect(line, tone).toMatch(/dark:text-/);
    }
  });

  test("the dashboard tile buttons and the hamburger grow on a phone", () => {
    expect(read("components", "DashboardCard.tsx").match(/pointer-coarse:h-11/g)?.length).toBe(2);
    expect(read("components", "DynamicIslandNav.tsx")).toContain("pointer-coarse:h-11 pointer-coarse:w-11");
  });
});

test.describe("DC-04 / DC-05 · names for unnamed controls", () => {
  test("the Do's/Don'ts rows are a named group of named points", () => {
    const source = read("components", "forms", "DosDontsField.tsx");
    expect(source).toContain('role="group" aria-labelledby={groupId}');
    expect(source).toContain("aria-label={`Point ${index + 1}`}");
  });

  test("each user's role picker is named for that user", () => {
    // The picker moved off the list and behind Update on the person's own view (2026-10-16).
    expect(read("app", "(protected)", "users", "[id]", "page.tsx")).toContain("ariaLabel={`Role for ${person.name}`}");
  });
});

test.describe("DC-06 · the review queue", () => {
  const source = read("app", "(protected)", "review", "page.tsx");

  test("is paged by the server, not cut at 200 per type and paged here", () => {
    // Since 2026-10-20 the page and its filters go through `queueQuery` (lib/review.ts), which the
    // feedback-loop spec pins: page and pageSize first, then the workshop, creator and order.
    expect(source).toContain("/review/pending${queueQuery(onPage, PAGE_SIZE, filters)}");
    expect(source).not.toContain("items.slice(");
  });

  test("is a card list below md and the table from md up", () => {
    expect(source).toContain('className="md:hidden"');
    expect(source).toContain('className="hidden overflow-x-auto md:block"');
  });
});

test.describe("DC-08 / DC-09 / DC-10 · the artisan form", () => {
  const source = read("components", "forms", "ArtisanForm.tsx");

  test("Gender opens empty", () => {
    expect(source).not.toMatch(/name="gender" defaultValue=\{[^}]*"Male"/);
    expect(source).toContain('<option value="">Select gender</option>');
  });

  test("says Do's and Don'ts, and never EXIF or prompt jargon on screen", () => {
    expect(source).toContain('label="Do\'s"');
    expect(source).toContain('label="Don\'ts"');
    expect(source).not.toContain("positive prompt");
    expect(source).not.toContain("Image EXIF");
  });
});

test.describe("DC-11 · a new form never opens on an ended workshop", () => {
  const workshop = (startDate: string | null, endDate: string | null) =>
    ({ id: "w", title: "W", startDate, endDate }) as Parameters<typeof workshopRunsToday>[0];
  const today = Date.parse("2026-10-16T06:30:00Z");

  test("running today is inclusive of both ends", () => {
    expect(workshopRunsToday(workshop("2026-10-16", "2026-10-16"), today)).toBe(true);
    expect(workshopRunsToday(workshop("2026-10-01T00:00:00Z", "2026-10-20T23:59:59Z"), today)).toBe(true);
  });

  test("ended, not begun, or undated is not running", () => {
    expect(workshopRunsToday(workshop("2026-09-10", "2026-09-14"), today)).toBe(false);
    expect(workshopRunsToday(workshop("2026-10-17", "2026-10-20"), today)).toBe(false);
    expect(workshopRunsToday(workshop(null, null), today)).toBe(false);
  });
});

test("DC-16 · a Status option reads as its badge does", () => {
  expect(statusLabel("NEEDS_REVISION")).toBe("Needs revision");
  expect(statusLabel("APPROVED")).toBe("Approved");
  for (const file of [
    ["components", "forms", "ArtisanForm.tsx"],
    ["components", "forms", "ProductForm.tsx"],
    ["components", "forms", "ToolForm.tsx"]
  ]) {
    expect(read(...file)).not.toContain("<option key={status}>{status}</option>");
  }
});

test("DC-18 · the sign-in button says Sign in", () => {
  expect(read("app", "login", "page.tsx")).not.toContain('"Sign In"');
});

test("DC-A09 parity · a cut list points at Search instead of admitting the gap", () => {
  const cut: ListCut = { loaded: 100, total: 749, noun: "artisans" } as ListCut;
  expect(cappedListNotice(cut)).toBe("Showing 100 of 749 artisans. To find another, use Browse records from the menu.");
});
