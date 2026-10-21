import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { CONTENT_ADMIN_ROLES, isAdmin, isContentAdmin } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * The content admins (owner's ruling, 2026-10-20): ADMIN and MASTER_ADMIN here, and MINISTRY_ADMIN
 * by name for the day the ladders merge, add, edit and delete every content type; every other role
 * adds and edits and never deletes. Delete controls read `canDeleteRecords` — the content admins,
 * WHETHER OR NOT admin view is on (owner, 2026-10-21) — and nobody else ever sees one.
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
  // Delete is not admin view's: it is the content-admin set itself.
  expect(provider).toContain("canDeleteRecords: canAdmin,");
});

test("no Delete in the app reads admin view any more", () => {
  for (const file of [
    "app/(protected)/artisans/page.tsx",
    "app/(protected)/products/page.tsx",
    "app/(protected)/tools/page.tsx",
    "app/(protected)/processes/page.tsx",
    "app/(protected)/workshops/page.tsx",
    "app/(protected)/media/page.tsx",
    "components/CollabPanel.tsx",
    "components/media/ExistingMedia.tsx",
    "components/questionnaire/InboxList.tsx",
    "components/questionnaire/SessionWorkspace.tsx",
    "components/records/RecordDangerZone.tsx"
  ]) {
    expect(read(file), file).not.toContain("adminMode");
  }
  for (const file of ["app/(protected)/artisans/page.tsx", "app/(protected)/products/page.tsx", "app/(protected)/tools/page.tsx"]) {
    expect(read(file), file).toContain("{canDeleteRecords ? (");
  }
});

test("a comment's author edits it and only a content admin deletes it", () => {
  const panel = read("components/CollabPanel.tsx");
  expect(panel).toContain("user && c.authorId === user.id && editingId !== c.id ?");
  expect(panel).toContain('method: "PATCH"');
  expect(panel).toContain("user && canDeleteRecords ?");
});

test("files, sessions, takes and questionnaire structure are deleted by content admins, admin view or not", () => {
  expect(read("components/media/ExistingMedia.tsx")).toContain("const mayRemove = !readOnly && canDeleteRecords;");
  expect(read("components/questionnaire/InboxList.tsx")).toContain("{canDeleteRecords ? (\n            <button type=\"button\" className={`${secondary} field-danger`}");
  expect(read("components/questionnaire/SessionWorkspace.tsx")).toContain("onRemove={canDeleteRecords ? (r) => setRemoveTake(r) : undefined}");
  const page = read("app/(protected)/questionnaire/page.tsx");
  // The builder moved to its own module so /questionnaire/builder renders it alone (audit H15).
  expect(read("components/questionnaire/InstrumentBuilder.tsx")).toContain("onRemove={canDeleteRecords ? () => removeQuestion(question) : undefined}");
  expect(page).toContain("editing && isContentAdmin(user) ? await offerMergeOnConflict(err, editing.id) : false");
});

test("an instrument's Delete retires it, for a content admin", () => {
  const page = read("app/(protected)/questionnaire/workbooks/[id]/page.tsx");
  expect(page).toContain("const mayDelete = permitted && isContentAdmin(user);");
  expect(page).toContain("apiFetch(`/questionnaires/${encodeURIComponent(target.id)}`, { method: \"DELETE\" })");
});
