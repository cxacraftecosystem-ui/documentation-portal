import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { DANGER_ZONE, dangerZoneVisible, RECORD_LIST_HREF } from "@/components/records/dangerZoneRules";
import { CONTENT_ADMIN_ROLES } from "@/lib/permissions";
import type { User } from "@/lib/types";

/**
 * A RECORD'S PAGE ENDS WITH A "DANGER ZONE" CARD FOR CONTENT ADMINS (owner, 2026-10-21).
 *
 * Delete used to be reachable only from a list row. The card is drawn for Admin, Master Admin and
 * Ministry Admin WHETHER OR NOT admin view is on (owner, 2026-10-21), and for nobody else. Its words
 * are both Android apps' `RecordDangerZone`; the two portals' web copies of the component are
 * byte-identical.
 */

// Roles as strings: the two portals' `UserRole` unions differ, and this file is the same in both.
const user = (role: string): User => ({ id: role, email: `${role}@example.org`, name: role, role }) as unknown as User;

test("content admins see the card, admin view or not", () => {
  for (const role of CONTENT_ADMIN_ROLES) {
    expect(dangerZoneVisible(user(role)), role).toBe(true);
  }
  // The gate takes no admin-view argument at all: a Ministry Admin with admin view OFF sees it.
  expect(dangerZoneVisible.length).toBe(1);
  expect(dangerZoneVisible(user("MINISTRY_ADMIN"))).toBe(true);
});

test("no other role ever sees it", () => {
  // A sample below the content admins, not the ladder: `isContentAdmin` is set membership.
  for (const role of ["RESEARCHER", "DESIGNER", "PROFESSOR"]) {
    expect(dangerZoneVisible(user(role)), role).toBe(false);
  }
  expect(dangerZoneVisible(null)).toBe(false);
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
  expect(card).toContain("if (!dangerZoneVisible(user)) return null;");
  // Admin view does not reach the card: it never reads the switch.
  expect(card).not.toContain("useAdminView");
  expect(read("components/records/RecordView.tsx")).toContain("<RecordDangerZone");
});
