import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { DANGER_ZONE, dangerZoneVisible, RECORD_LIST_HREF } from "@/components/records/dangerZoneRules";
import type { User } from "@/lib/types";

/**
 * A RECORD'S PAGE ENDS WITH A "DANGER ZONE" CARD FOR CONTENT ADMINS (owner, 2026-10-21).
 *
 * Delete used to be reachable only from a list row. The card is drawn for Admin, Master Admin and
 * Ministry Admin with admin view on, and for nobody else. Its words are both Android apps'
 * `RecordDangerZone`; the two portals' web copies of the component are byte-identical.
 */

// Roles as strings: the two portals' `UserRole` unions differ, and this file is the same in both.
const user = (role: string): User => ({ id: role, email: `${role}@example.org`, name: role, role }) as unknown as User;

test("content admins in admin view see the card", () => {
  for (const role of ["MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN"]) {
    expect(dangerZoneVisible(user(role), true), role).toBe(true);
    expect(dangerZoneVisible(user(role), false), `${role} with admin view off`).toBe(false);
  }
});

test("no other role ever sees it", () => {
  for (const role of ["CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "DESIGNER", "PROFESSOR"]) {
    expect(dangerZoneVisible(user(role), true), role).toBe(false);
  }
  expect(dangerZoneVisible(null, true)).toBe(false);
});

test("the words are the handset's", () => {
  expect(DANGER_ZONE.title).toBe("Danger zone");
  expect(DANGER_ZONE.body("artisan")).toBe("Permanently deletes this artisan and its links.");
  expect(DANGER_ZONE.button("artisan")).toBe("Delete this artisan");
  expect(DANGER_ZONE.confirmTitle("artisan")).toBe("Delete artisan?");
  expect(DANGER_ZONE.confirmBody).toBe("This permanently deletes the record.");
});

test("a deleted record goes back to its list", () => {
  expect(RECORD_LIST_HREF.artisan).toBe("/artisans");
  expect(RECORD_LIST_HREF.media).toBe("/media");
  expect(RECORD_LIST_HREF.questionnaire).toBe("/questionnaire");
});

test("the card asks before it deletes, and the record pages draw it", () => {
  const read = (path: string) => readFileSync(join(__dirname, "..", path), "utf8");
  const card = read("components/records/RecordDangerZone.tsx");
  expect(card).toContain("deleteConfirm(DANGER_ZONE.confirmTitle(noun)");
  expect(card).toContain("if (!ok) return;");
  expect(card).toContain("if (!dangerZoneVisible(user, adminMode)) return null;");
  expect(read("components/records/RecordView.tsx")).toContain("<RecordDangerZone");
});
