import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { NOT_RECORDED, recordFieldRows } from "@/components/records/recordFields";
import { decodeListReturn, encodeListReturn, LIST_RETURN_MAX_AGE_MS } from "@/lib/listReturn";
import {
  asRecordViewType,
  canUpdateRecord,
  RECORD_VIEW_TYPES,
  recordUpdateHref,
  recordViewHref,
  rowClickOpensRecord
} from "@/lib/recordView";
import type { User, UserRole } from "@/lib/types";

/**
 * A CLICK ON A RECORD READS IT; ONLY "UPDATE" EDITS IT (owner ruling, 2026-10-16).
 *
 * The pure halves run here, without a browser — there is no React renderer in this repository's
 * devDependencies, which is why the decisions are functions. The wiring (which pages build their
 * rows from them) is read off disk below, so a list that quietly goes back to opening its edit form
 * from a row fails here rather than in somebody's hands.
 *
 * The handset's twin is `android/.../ui/RecordOpenTest.kt`.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

function user(role: UserRole): User {
  return { id: "u1", name: "Somebody", email: "somebody@example.org", role } as User;
}

test.describe("where a click on a record goes", () => {
  test("every record type opens its own read-only view", () => {
    expect(recordViewHref("artisan", "a1")).toBe("/artisans/a1");
    expect(recordViewHref("product", "p1")).toBe("/products/p1");
    expect(recordViewHref("tool", "t1")).toBe("/tools/t1");
    expect(recordViewHref("craft", "c1")).toBe("/crafts/c1");
    expect(recordViewHref("workshop", "w1")).toBe("/workshops/w1");
    expect(recordViewHref("process", "x1")).toBe("/processes/x1");
    expect(recordViewHref("questionnaire", "q1")).toBe("/questionnaire/interviews/q1");
  });

  test("a view is never the edit form", () => {
    for (const type of RECORD_VIEW_TYPES) {
      const href = recordViewHref(type, "id1")!;
      expect(href, type).not.toContain("/edit");
      expect(href, type).not.toContain("?edit=");
    }
  });

  test("an interview is spelled two ways on the wire and opens the same view", () => {
    expect(asRecordViewType("interview")).toBe("questionnaire");
    expect(recordViewHref("Interview", "q1")).toBe("/questionnaire/interviews/q1");
  });

  test("a type with no view opens nothing rather than the wrong page", () => {
    expect(recordViewHref("media", "m1")).toBeNull();
    expect(recordViewHref("user", "u1")).toBeNull();
    expect(recordViewHref("", "u1")).toBeNull();
    expect(recordUpdateHref("media", "m1")).toBeNull();
  });

  test("the id is encoded, so an odd id cannot change the route", () => {
    expect(recordViewHref("artisan", "a/b?c")).toBe("/artisans/a%2Fb%3Fc");
  });

  test("every view has a route file", () => {
    for (const type of RECORD_VIEW_TYPES) {
      const href = recordViewHref(type, "x")!;
      const route = href.replace(/\/x$/, "/[id]");
      const file = join(__dirname, "..", "app", "(protected)", ...route.split("/").filter(Boolean), "page.tsx");
      expect(existsSync(file), `${type} → ${file}`).toBe(true);
    }
  });
});

test.describe("where Update goes", () => {
  test("Update opens the edit form that already existed for the type", () => {
    expect(recordUpdateHref("artisan", "a1")).toBe("/artisans/a1/edit");
    expect(recordUpdateHref("product", "p1")).toBe("/products/p1/edit");
    expect(recordUpdateHref("tool", "t1")).toBe("/tools/t1/edit");
    expect(recordUpdateHref("craft", "c1")).toBe("/crafts?edit=c1");
    expect(recordUpdateHref("workshop", "w1")).toBe("/workshops?edit=w1");
    expect(recordUpdateHref("process", "x1")).toBe("/processes?edit=x1");
    expect(recordUpdateHref("questionnaire", "q1")).toBe("/questionnaire?edit=q1");
  });
});

test.describe("who sees the Update button", () => {
  test("nobody signed out", () => {
    for (const type of RECORD_VIEW_TYPES) expect(canUpdateRecord(null, type), type).toBe(false);
  });

  test("crafts and workshops: Professor and up, as the server's PATCH requires", () => {
    for (const role of ["CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER"] as UserRole[]) {
      expect(canUpdateRecord(user(role), "craft"), role).toBe(false);
      expect(canUpdateRecord(user(role), "workshop"), role).toBe(false);
    }
    for (const role of ["PROFESSOR", "ADMIN", "MASTER_ADMIN"] as UserRole[]) {
      expect(canUpdateRecord(user(role), "craft"), role).toBe(true);
      expect(canUpdateRecord(user(role), "workshop"), role).toBe(true);
    }
  });

  test("the other records: every signed-in account, because the server decides field by field", () => {
    for (const type of ["artisan", "product", "tool", "process", "questionnaire"]) {
      expect(canUpdateRecord(user("CROWDSOURCE_VOLUNTEER"), type), type).toBe(true);
    }
  });

  test("a type with no edit form never shows Update", () => {
    expect(canUpdateRecord(user("MASTER_ADMIN"), "media")).toBe(false);
  });

  test("the view draws Update only through the gate", () => {
    const view = read("components", "records", "RecordView.tsx");
    expect(view).toMatch(/updateHref && canUpdateRecord\(user, type\)/);
    expect(view).toMatch(/>\s*<Pencil[^>]*\/>\s*Update\s*</);
  });
});

test.describe("a click inside a row", () => {
  const row = { tag: "tr" };
  const inside = (hit: unknown) => ({ closest: () => hit });

  test("opens the record when it lands on the row itself", () => {
    expect(rowClickOpensRecord(inside(null), row)).toBe(true);
  });

  test("leaves Update, Discuss, Delete and every other inner control alone", () => {
    expect(rowClickOpensRecord(inside({ tag: "button" }), row)).toBe(false);
  });

  test("treats a target with no DOM as the row", () => {
    expect(rowClickOpensRecord(null, row)).toBe(true);
  });
});

test.describe("what a view prints", () => {
  test("never an Aadhaar or Pehchan number, only whether one is on file", () => {
    const rows = recordFieldRows("artisan", {
      name: "Meena",
      place: "Bagru",
      aadhaarNumber: "234123412346",
      pehchanCardAvailable: true,
      pehchanCardNumber: "PEH-998877"
    });
    const printed = rows.map((r) => r.value).join(" | ");
    expect(printed).not.toContain("234123412346");
    expect(printed).not.toContain("2346");
    expect(printed).not.toContain("998877");
    expect(rows.find((r) => r.label === "Aadhaar card")?.value).toBe("On file");
    expect(rows.find((r) => r.label === "Pehchan card")?.value).toBe("On file");
  });

  test("a missing value says so instead of disappearing", () => {
    const rows = recordFieldRows("craft", { name: "Block printing" });
    expect(rows.map((r) => r.label)).toEqual(["Local name", "Category", "Place", "Description", "Workshop"]);
    expect(rows.every((r) => r.value === NOT_RECORDED)).toBe(true);
  });

  test("a Decimal that arrives as a string prints as a number", () => {
    const rows = recordFieldRows("product", { sellingPrice: "1250.00", lengthInches: "12", breadthInches: null, heightInches: "3.5" });
    expect(rows.find((r) => r.label === "Selling price")?.value).toBe("₹1250");
    expect(rows.find((r) => r.label === "Dimensions")?.value).toBe("12 × – × 3.5 in (L × B × H)");
  });
});

test.describe("coming back to the same place in the list", () => {
  const now = 1_800_000_000_000;

  test("a note round-trips", () => {
    const raw = encodeListReturn({ query: "bagru", page: 4, scrollY: 900, funnel: { workshopId: "w1", craftId: "", artisanId: "" } }, now);
    expect(decodeListReturn(raw, now + 1000)).toEqual({
      query: "bagru",
      page: 4,
      scrollY: 900,
      funnel: { workshopId: "w1", craftId: "", artisanId: "" },
      savedAt: now
    });
  });

  test("a stale or malformed note is ignored", () => {
    const raw = encodeListReturn({ query: "", page: 2, scrollY: 0 }, now);
    expect(decodeListReturn(raw, now + LIST_RETURN_MAX_AGE_MS + 1)).toBeNull();
    expect(decodeListReturn("{not json", now)).toBeNull();
    expect(decodeListReturn(null, now)).toBeNull();
  });

  test("an impossible page or scroll falls back to the top of page one", () => {
    const raw = JSON.stringify({ query: 7, page: -3, scrollY: "x", savedAt: now });
    expect(decodeListReturn(raw, now)).toEqual({ query: "", page: 1, scrollY: 0, funnel: null, savedAt: now });
  });
});

test.describe("the lists are wired to it", () => {
  const LISTS: Array<[string, string]> = [
    ["artisans", "artisan"],
    ["products", "product"],
    ["tools", "tool"],
    ["crafts", "craft"],
    ["workshops", "workshop"],
    ["processes", "process"],
    ["questionnaire", "questionnaire"]
  ];

  for (const [page, type] of LISTS) {
    test(`/${page}: the whole row opens the view, and the row action says Update`, () => {
      const code = read("app", "(protected)", page, "page.tsx");
      expect(code).toContain(`<OpenableRow key=`);
      expect(code).toContain(`recordViewHref("${type}"`);
      expect(code).toContain(`<RecordTitleLink href={recordViewHref("${type}"`);
      // No row action is labelled "Edit" any more.
      expect(code).not.toMatch(/>\s*Edit\s*</);
      expect(code).not.toMatch(/"Loading\.\.\." : "Edit"/);
      expect(code).toContain("useListReturn(");
    });
  }

  test("the dashboard, search, the data browser, the map and My Activity open views too", () => {
    expect(read("app", "(protected)", "dashboard", "page.tsx")).toMatch(/return recordViewHref\(type, id\);/);
    const search = read("app", "(protected)", "search", "page.tsx");
    expect(search).not.toMatch(/href: `\/(artisans|products|tools)\/\$\{item\.id\}\/edit`/);
    expect(search).toContain('href: recordViewHref("workshop", item.id)!');
    expect(read("app", "(protected)", "data", "page.tsx")).toContain("const viewHref = recordViewHref(typeKey, row.id);");
    expect(read("components", "data", "DataSearchPanel.tsx")).not.toMatch(/\/edit`/);
    expect(read("components", "map", "types.ts")).not.toMatch(/\/edit`|\?edit=/);
    expect(read("app", "(protected)", "activity", "page.tsx")).toContain("recordViewHref(group.viewType, row.id)");
  });

  test("a dashboard card is one link over the whole card, with Update beside it and not inside it", () => {
    const card = read("components", "DashboardCard.tsx");
    // The primary link stretches an ::after over the card…
    expect(card).toMatch(/after:absolute after:inset-0/);
    // …the card is its containing block…
    expect(card).toMatch(/className="relative flex cursor-pointer/);
    // …and Update is lifted above that area as its own link.
    expect(card).toMatch(/field-button-secondary relative z-10/);
    // Two links side by side: Update opens only after the primary link has closed.
    expect(card.indexOf("field-button-secondary")).toBeGreaterThan(card.indexOf("</Link>"));
  });
});
