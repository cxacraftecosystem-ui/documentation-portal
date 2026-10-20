import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { CONTENT_ADMIN_ROLES, isAdmin, isContentAdmin } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * The content admins (owner's ruling, 2026-10-20): ADMIN and MASTER_ADMIN here, and MINISTRY_ADMIN
 * by name for the day the ladders merge, add, edit and delete every content type; every other role
 * adds and edits and never deletes. Delete controls read `adminMode`, which only a content admin can
 * turn on.
 *
 * Run: `npx playwright test e2e/content-admin-unit.spec.ts --reporter=line`
 */

const ROOT = join(__dirname, "..");
const read = (relative: string) => readFileSync(join(ROOT, relative), "utf8").split("\r\n").join("\n");

function user(role: UserRole): User {
  return { id: "u1", email: "someone@example.org", name: "Someone", role } as User;
}

test("the content admins are the three admin tiers by name, and isAdmin is not widened", () => {
  expect([...CONTENT_ADMIN_ROLES].sort()).toEqual(["ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"]);
  const roles: UserRole[] = ["CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "PROFESSOR", "ADMIN", "MASTER_ADMIN"];
  for (const role of roles) {
    expect(isContentAdmin(user(role)), role).toBe(role === "ADMIN" || role === "MASTER_ADMIN");
    expect(isAdmin(user(role)), role).toBe(role === "ADMIN" || role === "MASTER_ADMIN");
  }
  expect(isContentAdmin(null)).toBe(false);
});

test("admin view, and with it every Delete, is offered to the content admins", () => {
  const provider = read("components/AdminViewProvider.tsx");
  expect(provider).toContain("const canAdmin = isContentAdmin(user);");
  expect(provider).toContain("available: canAdmin,");
});

test("a comment's author edits it and only a content admin deletes it", () => {
  const panel = read("components/CollabPanel.tsx");
  expect(panel).toContain("user && c.authorId === user.id && editingId !== c.id ?");
  expect(panel).toContain('method: "PATCH"');
  expect(panel).toContain("user && adminMode ?");
});

test("files, sessions, takes and questionnaire structure are deleted in admin view only", () => {
  expect(read("components/media/ExistingMedia.tsx")).toContain("const mayRemove = !readOnly && adminMode;");
  expect(read("components/questionnaire/InboxList.tsx")).toContain("{adminMode ? (\n            <button type=\"button\" className={`${secondary} field-danger`}");
  expect(read("components/questionnaire/SessionWorkspace.tsx")).toContain("onRemove={adminMode ? (r) => setRemoveTake(r) : undefined}");
  const page = read("app/(protected)/questionnaire/page.tsx");
  expect(page).toContain("onRemove={adminMode ? () => removeQuestion(question) : undefined}");
  expect(page).toContain("editing && isContentAdmin(user) ? await offerMergeOnConflict(err, editing.id) : false");
});
