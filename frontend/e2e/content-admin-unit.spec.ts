import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { CONTENT_ADMIN_ROLES, canManageQuestionnaire, isAdmin, isContentAdmin, mayDeleteQuestionnaire, mayEditQuestionnaire } from "@/lib/permissions";
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

test("a questionnaire is deleted from by its author or a content admin, and nobody else (22 Oct 2026)", () => {
  // The author: a professor deleting from the form they made.
  expect(mayDeleteQuestionnaire({ ...user("PROFESSOR"), id: "author" }, "author")).toBe(true);
  for (const role of ["ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"] as UserRole[]) {
    expect(mayDeleteQuestionnaire(user(role), "author"), role).toBe(true);
  }
  // Every other role on somebody else's form, a questionnaire manager included.
  for (const role of ["CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER", "PROFESSOR"] as UserRole[]) {
    expect(mayDeleteQuestionnaire(user(role), "author"), role).toBe(false);
  }
  expect(mayDeleteQuestionnaire(null, "author")).toBe(false);
  expect(mayDeleteQuestionnaire(user("PROFESSOR"), null)).toBe(false);
});

test("a questionnaire is edited by its author or a content admin, and no other manager (22 Oct 2026)", () => {
  // The author edits the form they made, whatever their tier.
  expect(mayEditQuestionnaire({ ...user("PROFESSOR"), id: "author" }, "author")).toBe(true);
  expect(mayEditQuestionnaire({ ...user("RESEARCHER"), id: "author", canManageQuestionnaire: true }, "author")).toBe(true);
  // Every content admin edits every form, an author or none.
  for (const role of ["ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"] as UserRole[]) {
    expect(mayEditQuestionnaire(user(role), "author"), role).toBe(true);
    expect(mayEditQuestionnaire(user(role), null), `${role}, no recorded author`).toBe(true);
    expect(mayEditQuestionnaire(user(role), undefined), `${role}, author not sent`).toBe(true);
  }
  // Another questionnaire manager — a Professor, or a grant holder — may create a form, not edit this one.
  const professor = user("PROFESSOR");
  const grantHolder = { ...user("RESEARCHER"), canManageQuestionnaire: true } as User;
  for (const manager of [professor, grantHolder]) {
    expect(canManageQuestionnaire(manager), manager.role).toBe(true);
    expect(mayEditQuestionnaire(manager, "author"), manager.role).toBe(false);
  }
  // A form with no recorded author is the content admins' alone — no id matches a missing author.
  expect(mayEditQuestionnaire(professor, null)).toBe(false);
  expect(mayEditQuestionnaire(grantHolder, undefined)).toBe(false);
  expect(mayEditQuestionnaire({ ...user("PROFESSOR"), id: "" }, "")).toBe(false);
  for (const role of ["CROWDSOURCE_VOLUNTEER", "FIELD_CONTRIBUTOR", "RESEARCHER"] as UserRole[]) {
    expect(mayEditQuestionnaire(user(role), "author"), role).toBe(false);
  }
  expect(mayEditQuestionnaire(null, "author")).toBe(false);
  // Editing and deleting are one predicate.
  for (const role of ["PROFESSOR", "ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"] as UserRole[]) {
    for (const author of ["author", "u1", null]) {
      expect(mayDeleteQuestionnaire(user(role), author), `${role} on ${author}`).toBe(mayEditQuestionnaire(user(role), author));
    }
  }
});

test("the builder's edit controls are drawn only for the form's author or a content admin", () => {
  const builder = read("components/questionnaire/InstrumentBuilder.tsx");
  // The editor defaults to read-only, and the read-only branch returns before any edit control.
  expect(builder).toContain("mayEdit = false,");
  const guard = builder.indexOf("if (!mayEdit) {");
  expect(guard).toBeGreaterThan(-1);
  const readOnly = builder.slice(guard, builder.indexOf("\n  return (", guard + 1));
  expect(readOnly).toContain("Only the form&rsquo;s author or an admin can edit it.");
  for (const control of ["<form", "<button", "addSection", "updateSection", "performSectionReorder", "<QuestionTile", "<Toggle", "draggable"]) {
    expect(readOnly, control).not.toContain(control);
  }
  // Every write the editor makes sits after that return.
  for (const write of ["<form onSubmit={addSection}", "<QuestionTile", 'aria-label={`Drag section ${section.code}`}']) {
    expect(builder.indexOf(write), write).toBeGreaterThan(guard);
  }

  const screen = read("components/questionnaire/BuilderScreen.tsx");
  // Opening the builder is still a manager's; editing the selected form is its author's or an admin's.
  expect(screen).toContain("const allowed = canManageQuestionnaire(user);");
  expect(screen).toContain("const mayEdit = mayEditQuestionnaire(user, selected?.createdById);");
  expect(screen).toContain("mayEdit={mayEdit}");
  // The form's own workbook — the editing download and the re-upload — is offered to the same accounts.
  expect(screen).toContain("{selected && mayEdit ? (\n          <ButtonRow");
  expect(screen).toContain("downloadQuestionnaireWorkbook(selected.id)");
  expect(screen).toContain("{selected && mayEdit ? (\n        <WorkbookUploadDialog");
  expect(screen).not.toContain("downloadQuestionSet");
  expect(screen).not.toContain("downloadProForma");

  const page = read("app/(protected)/questionnaire/page.tsx");
  expect(page).toContain("mayEdit={mayEditQuestionnaire(user, instruments.find((instrument) => instrument.id === questionnaireId)?.createdById)}");

  // The admin workbook pages: re-uploading over a form follows the same rule, never the manager grant.
  const list = read("app/(protected)/questionnaire/workbooks/page.tsx");
  expect(list).toContain("{mayEditQuestionnaire(user, instrument.createdById) ? (");
  expect(list).not.toContain("canManageQuestionnaire");
  const one = read("app/(protected)/questionnaire/workbooks/[id]/page.tsx");
  expect(one).toContain("const mayUpdate = permitted && mayEditQuestionnaire(user, instrument?.createdById);");
  expect(one).not.toContain("canManageQuestionnaire");
});

test("the builder draws Delete, Remove, switch-off and Restore for the author or a content admin", () => {
  const builder = read("components/questionnaire/InstrumentBuilder.tsx");
  expect(builder).not.toContain("canDeleteRecords");
  expect(builder).toContain("onRemove={mayDelete ? () => removeQuestion(question) : undefined}");
  expect(builder).toContain("disabled={section.isActive && !mayDelete}");
  expect(builder).toContain("disabled={question.isActive && !mayDeactivate}");
  const screen = read("components/questionnaire/BuilderScreen.tsx");
  expect(screen).toContain("const mayDelete = mayDeleteQuestionnaire(user, selected?.createdById);");
  expect(screen).toContain("mayDeleteQuestionnaire(user, instrument.createdById)");
  expect(screen).toContain("mayDelete && !selected.isDefault ? (");
  expect(screen).toContain('body: JSON.stringify({ isActive: true })');
  const page = read("app/(protected)/questionnaire/page.tsx");
  expect(page).toContain("mayDelete={mayDeleteQuestionnaire(user, instruments.find((instrument) => instrument.id === questionnaireId)?.createdById)}");
  // Sessions and interviews stay a content admin's.
  expect(page).toContain("{canDeleteRecords ? (\n                          <button className={rowAction(\"danger\")} onClick={() => remove(interview.id)}>");
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
  // A section or a question is removed by the form's author or a content admin (22 Oct 2026).
  expect(read("components/questionnaire/InstrumentBuilder.tsx")).toContain("onRemove={mayDelete ? () => removeQuestion(question) : undefined}");
  expect(page).toContain("editing && isContentAdmin(user) ? await offerMergeOnConflict(err, editing.id) : false");
});

test("an instrument's Delete retires it, for a content admin", () => {
  const page = read("app/(protected)/questionnaire/workbooks/[id]/page.tsx");
  expect(page).toContain("const mayDelete = permitted && isContentAdmin(user);");
  expect(page).toContain("apiFetch(`/questionnaires/${encodeURIComponent(target.id)}`, { method: \"DELETE\" })");
});
